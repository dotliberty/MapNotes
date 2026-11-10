package ru.main.explorer;

import android.app.Application;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.location.Location;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.DetectedActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowSystemClock;
import ru.main.explorer.motion.MovementType;
import ru.main.explorer.motion.PersonalMotionProfile;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "ru-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MovementIntegrationTest {
    Application app;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("walks.db");
        app.getSharedPreferences("motion", 0).edit().clear().commit();
        WalkService.running = false;
        MotionRuntime.reset(app);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    }

    @After
    public void cleanup() {
        WalkService.running = false;
        MotionRuntime.stop(app);
    }

    Location fix(double latitude, long seconds, float speed) {
        Location point = new Location("gps");
        point.setLatitude(latitude);
        point.setLongitude(37);
        point.setAccuracy(5);
        point.setTime(1700000000000L + seconds * 1000);
        point.setElapsedRealtimeNanos((seconds + 1) * 1000000000L);
        point.setSpeed(speed);
        point.setSpeedAccuracyMetersPerSecond(.5f);
        return point;
    }

    long seed(MovementType type, float speed) {
        try (Store store = new Store(app)) {
            long walk = store.startWalk(1700000000000L);
            long session = store.newWalkSession(walk);
            for (int i = 0; i <= 65; i++) {
                store.addMovement(
                        fix(55 + i * speed / 111195.0, i, speed),
                        session,
                        type.name(),
                        "auto",
                        false);
            }
            store.finishWalk(1700000066000L);
            return walk;
        }
    }

    @Test
    public void migrationV3KeepsIdsNotesAndUnfinishedWalkUnclassified() {
        long walk;
        long photo;
        try (Store store = new Store(app)) {
            walk = store.startWalk(100);
            long session = store.newWalkSession(walk);
            store.add(fix(55, 1, 1), session);
            photo = store.addPhoto("private/test.jpg", fix(55, 1, 1), 101);
            store.saveNote(photo, "retained note");
            SQLiteDatabase db = store.getWritableDatabase();
            db.execSQL("DROP TABLE motion_points");
            db.execSQL("DROP TABLE motion_samples");
            db.setVersion(3);
        }
        try (Store store = new Store(app);
                MovementStore data = new MovementStore(app)) {
            assertEquals(6, store.getReadableDatabase().getVersion());
            assertEquals(walk, store.openWalk().id);
            assertEquals("retained note", store.photo(photo).note);
            assertEquals("private/test.jpg", store.photo(photo).path);
            assertEquals(1, store.walkPoints(walk).size());
            assertEquals(1, store.points().size());
            assertEquals(MovementType.UNKNOWN, data.segments(walk).get(0).getType());
            assertEquals("legacy", data.segments(walk).get(0).getSource());
        }
    }

    @Test
    public void transportRecordedButNeverBridgesExploration() {
        try (Store store = new Store(app);
                MovementStore data = new MovementStore(app)) {
            long walk = store.startWalk(100);
            long session = store.newWalkSession(walk);
            store.addMovement(fix(55, 1, 1), session, "WALK", "manual", false);
            store.addMovement(fix(55.0001, 2, 20), session, "VEHICLE", "manual", false);
            store.addMovement(fix(55.0002, 3, 1), session, "WALK", "manual", false);
            assertEquals(3, store.walkPoints(walk).size());
            assertEquals(2, store.points().size());
            assertFalse(MapView.connects(store.points().get(0), store.points().get(1)));
            Store.PointPage page = store.pointsAfter(0, 100);
            assertEquals(3, page.lastId);
            assertEquals(2, page.points.size());
            assertFalse(MapView.connects(page.points.get(0), page.points.get(1)));
            double sum =
                    data.segments(walk).stream()
                            .mapToDouble(MovementStore.Segment::getMeters)
                            .sum();
            assertTrue(sum > 20 && sum < 24);
            assertEquals(sum, MovementStore.totalDistance(app, walk), .001);
        }
    }

    @Test
    public void invalidMetadataRollsBackCoordinateInsert() {
        try (Store store = new Store(app)) {
            long walk = store.startWalk(100);
            long session = store.newWalkSession(walk);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> store.addMovement(fix(55, 1, 1), session, "NOT_A_TYPE", "auto", false));
            assertTrue(store.walkPoints(walk).isEmpty());
            assertEquals(0, store.latestPointId());
        }
    }

    @Test
    public void explicitConfirmationPersistsAndNeverDoublesItself() {
        long walk = seed(MovementType.WALK, 4);
        try (MovementStore data = new MovementStore(app)) {
            assertEquals(0, data.profile().getWalkCount());
            MovementStore.Segment segment = data.segments(walk).get(0);
            assertTrue(data.confirm(segment));
            assertTrue(data.confirm(segment));
            assertEquals(1, data.profile().getWalkCount());
        }
        try (MovementStore data = new MovementStore(app)) {
            assertEquals(1, data.profile().getWalkCount());
            assertFalse(data.profile().getReady());
        }
    }

    @Test
    public void correctingPartRetractsTrainingAndUndoRestoresItWithoutMovingPoints() {
        long walk = seed(MovementType.WALK, 1);
        try (MovementStore data = new MovementStore(app)) {
            MovementStore.Segment segment = data.segments(walk).get(0);
            assertTrue(data.confirm(segment));
            MovementStore.Segment selected =
                    new MovementStore.Segment(new ArrayList<>(segment.getPoints().subList(10, 20)));
            MovementStore.Correction snapshot = data.correct(selected, MovementType.RUN);
            assertEquals(0, data.profile().getWalkCount());
            assertEquals(3, data.segments(walk).size());
            assertEquals(10, data.segments(walk).get(1).getPoints().size());
            assertEquals(MovementType.RUN, data.segments(walk).get(1).getType());
            assertEquals(segment.getFirst().getLat(), data.points(walk).get(0).getLat(), 0);
            assertEquals(66, data.points(walk).size());
            data.undo(snapshot);
            assertEquals(1, data.profile().getWalkCount());
            assertEquals(1, data.segments(walk).size());
            assertEquals("auto", data.segments(walk).get(0).getSource());
        }
    }

    @Test
    public void resetProfileRetainsJournalAndMixedPersonalSpeedsNeedNoUniversalCutoff() {
        for (int i = 0; i < 3; i++) {
            long walk = seed(MovementType.WALK, 4);
            long run = seed(MovementType.RUN, 2);
            try (MovementStore data = new MovementStore(app)) {
                assertTrue(data.confirm(data.segments(walk).get(0)));
                assertTrue(data.confirm(data.segments(run).get(0)));
            }
        }
        try (MovementStore data = new MovementStore(app);
                Store store = new Store(app)) {
            PersonalMotionProfile profile = data.profile();
            assertTrue(profile.getReady());
            assertEquals(MovementType.WALK, profile.suggest(4));
            assertEquals(MovementType.RUN, profile.suggest(2));
            data.resetProfile();
            assertEquals(0, data.profile().getWalkCount());
            assertEquals(6, store.walks().size());
            assertEquals(66, data.points(store.walks().get(0).id).size());
        }
    }

    @Test
    public void twoSegmentsOfOneJourneyCannotManufactureIndependentTrainingSamples() {
        long walk;
        try (Store store = new Store(app)) {
            walk = store.startWalk(100);
            for (int part = 0; part < 2; part++) {
                long session = store.newWalkSession(walk);
                for (int i = 0; i <= 65; i++) {
                    long seconds = part * 100 + i;
                    store.addMovement(
                            fix(55 + seconds / 111195.0, seconds, 1),
                            session,
                            "WALK",
                            "auto",
                            false);
                }
            }
        }
        try (MovementStore data = new MovementStore(app)) {
            List<MovementStore.Segment> segments = data.segments(walk);
            assertEquals(2, segments.size());
            assertTrue(data.confirm(segments.get(0)));
            assertTrue(data.confirm(segments.get(1)));
            assertEquals(1, data.profile().getWalkCount());
        }
    }

    @Test
    public void stopsAndGpsGapsAreNotInventedDistanceOrTraining() {
        try (Store store = new Store(app);
                MovementStore data = new MovementStore(app)) {
            long walk = store.startWalk(100);
            long session = store.newWalkSession(walk);
            store.addMovement(fix(55, 1, 0), session, "VEHICLE", "auto", true);
            store.addMovement(fix(55, 2, 0), session, "VEHICLE", "auto", true);
            store.addMovement(fix(56, 30, 20), session, "VEHICLE", "auto", false);
            List<MovementStore.Segment> segments = data.segments(walk);
            assertEquals(2, segments.size());
            assertEquals(1000, segments.get(0).getStoppedMs());
            assertEquals(0, segments.get(0).getMovingMs());
            assertEquals(0, MovementStore.totalDistance(app, walk), 0);
            assertFalse(data.confirm(segments.get(0)));
        }
    }

    @Test
    public void tripGpsAcceptsTransportRejectsTeleportAndRetainsTrustedAnchor() {
        TripGpsFilter filter = new TripGpsFilter();
        Location first = fix(55, 1, 25);
        assertNull(filter.accept(first, first.getElapsedRealtimeNanos()));
        Location next = fix(55.00022, 2, 25);
        assertNull(filter.accept(next, next.getElapsedRealtimeNanos()));
        Location jump = fix(56, 3, 25);
        assertEquals("too_fast_gps_jump", filter.accept(jump, jump.getElapsedRealtimeNanos()));
        Location recovered = fix(55.00044, 3, 25);
        assertNull(filter.accept(recovered, recovered.getElapsedRealtimeNanos()));
        Location gap = fix(56, 30, 25);
        assertNull(filter.accept(gap, gap.getElapsedRealtimeNanos()));
        assertTrue(filter.getBreakSegment());
    }

    @Test
    public void tripGpsRejectsBadAccuracyStaleAndBackwardTime() {
        TripGpsFilter filter = new TripGpsFilter();
        Location first = fix(55, 1, 2);
        first.setAccuracy(101);
        assertEquals("poor_gps_accuracy", filter.accept(first, first.getElapsedRealtimeNanos()));
        first.setAccuracy(5);
        assertEquals(
                "stale_location",
                filter.accept(first, first.getElapsedRealtimeNanos() + 16000000000L));
        assertNull(filter.accept(first, first.getElapsedRealtimeNanos()));
        assertEquals("invalid_gps_time", filter.accept(first, first.getElapsedRealtimeNanos()));
    }

    void event(int type, int confidence) {
        long now = SystemClock.elapsedRealtime();
        MotionRuntime.INSTANCE.receive(
                app,
                new ActivityRecognitionResult(
                        Arrays.asList(new DetectedActivity(type, confidence)),
                        1700000000000L + now,
                        now));
    }

    @Test
    public void providerRecognitionIsDynamicAndManualModeWins() {
        WalkService.running = true;
        for (int i = 0; i < 3; i++) {
            event(DetectedActivity.RUNNING, 90);
            ShadowSystemClock.advanceBy(Duration.ofSeconds(5));
        }
        assertEquals(MovementType.RUN, MotionRuntime.type(app, 1));
        MotionRuntime.INSTANCE.setMode(app, MovementType.WALK);
        event(DetectedActivity.IN_VEHICLE, 100);
        assertEquals(MovementType.WALK, MotionRuntime.type(app, 20));
        MotionRuntime.INSTANCE.setMode(app, null);
        assertEquals(MovementType.UNKNOWN, MotionRuntime.type(app, 20));
    }

    @Test
    public void staleEventsCannotLabelRecordingAndStillDoesNotBecomeWalking() {
        WalkService.running = true;
        for (int i = 0; i < 3; i++) {
            event(DetectedActivity.IN_VEHICLE, 90);
            ShadowSystemClock.advanceBy(Duration.ofSeconds(5));
        }
        event(DetectedActivity.STILL, 100);
        assertEquals(MovementType.VEHICLE, MotionRuntime.type(app, 0));
        assertTrue(MotionRuntime.stopped());
        ShadowSystemClock.advanceBy(Duration.ofSeconds(70));
        long old = SystemClock.elapsedRealtime() - 60000;
        MotionRuntime.INSTANCE.receive(
                app,
                new ActivityRecognitionResult(
                        Arrays.asList(new DetectedActivity(DetectedActivity.RUNNING, 100)),
                        1700000000000L,
                        old));
        assertEquals(MovementType.UNKNOWN, MotionRuntime.type(app, 0));
    }

    @Test
    public void recordingServiceRetainsManualTransportWithoutGoogleServices() {
        MotionRuntime.INSTANCE.setMode(app, MovementType.VEHICLE);
        ServiceController<WalkService> controller =
                Robolectric.buildService(WalkService.class).create();
        try {
            WalkService service = controller.get();
            service.onStartCommand(new Intent(app, WalkService.class), 0, 1);
            Location point = fix(55, 1, 25);
            point.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            service.onLocationChanged(point);
            try (Store store = new Store(app);
                    MovementStore data = new MovementStore(app)) {
                long walk = store.openWalk().id;
                assertEquals(1, store.walkPoints(walk).size());
                assertTrue(store.points().isEmpty());
                assertEquals(MovementType.VEHICLE, data.segments(walk).get(0).getType());
                assertEquals("manual", data.segments(walk).get(0).getSource());
            }
            service.onStartCommand(new Intent(app, WalkService.class).setAction("STOP"), 0, 2);
            assertFalse(WalkService.running);
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void movementScreenUsesRealStoredSegmentsAndNativeCanvas() throws Exception {
        long walk = seed(MovementType.WALK, 1);
        Language.select(app, "ru");
        ActivityController<MovementActivity> controller =
                Robolectric.buildActivity(
                                MovementActivity.class,
                                new Intent(app, MovementActivity.class).putExtra("walk", walk))
                        .create()
                        .start()
                        .resume();
        try {
            MovementActivity activity = controller.get();
            java.lang.reflect.Field field = MovementActivity.class.getDeclaredField("worker");
            field.setAccessible(true);
            ((ExecutorService) field.get(activity)).submit(() -> {}).get(10, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            View root = activity.findViewById(android.R.id.content);
            root.measure(
                    View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 432, 900);
            Bitmap image = Bitmap.createBitmap(432, 900, Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(image));
            assertNotEquals(image.getPixel(10, 10), image.getPixel(200, 200));
            File directory = new File("build/rendered");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            try (FileOutputStream file =
                    new FileOutputStream(new File(directory, "1.3-movement.png"))) {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, file));
            }
        } finally {
            controller.pause().stop().destroy();
        }
    }
}

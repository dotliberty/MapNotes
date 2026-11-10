package ru.main.explorer;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Intent;
import android.location.Location;
import android.os.SystemClock;
import java.time.Duration;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SpawnZoneWalkServiceTest {
    private Application app;
    private ServiceController<WalkService> controller;
    private WalkService service;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("walks.db");
        app.getSharedPreferences("motion", 0).edit().clear().commit();
        WalkService.running = false;
        WalkService.zoneMonitoring = false;
        MotionRuntime.INSTANCE.setMode(app, ru.main.explorer.motion.MovementType.WALK);
        create();
    }

    private void create() {
        controller = Robolectric.buildService(WalkService.class).create();
        service = controller.get();
    }

    @After
    public void teardown() {
        controller.destroy();
        WalkService.running = false;
    }

    private void circle() {
        try (SpawnZoneStore zones = new SpawnZoneStore(app)) {
            zones.save(new SpawnZone(0, "Home", 55, 37, 50));
        }
    }

    private void start() {
        service.onStartCommand(new Intent(app, WalkService.class), 0, 1);
    }

    private Location point(double latitude, float accuracy) {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(3));
        Location l = new Location("gps");
        l.setLatitude(latitude);
        l.setLongitude(37);
        l.setAccuracy(accuracy);
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        service.onLocationChanged(l);
        return l;
    }

    private void confirm(double latitude) {
        for (int n = 0; n < 3; n++) point(latitude, 5);
    }

    @Test
    public void entryFinishesAndExitStartsAnotherWalkWhileMonitoringContinues() {
        circle();
        start();
        try (Store store = new Store(app)) {
            assertNull(store.openWalk());
            confirm(55.001);
            long first = store.openWalk().id;
            confirm(55);
            assertNull(store.openWalk());
            assertTrue(WalkService.running);
            confirm(55.001);
            assertNotEquals(first, store.openWalk().id);
            assertEquals(2, store.walks().size());
            assertEquals(1, store.walkPoints(first).size());
        }
    }

    @Test
    public void duplicateFixesAndBoundaryJitterCannotConfirm() {
        circle();
        start();
        Location l = point(55.001, 5);
        for (int n = 0; n < 10; n++) service.onLocationChanged(l);
        try (Store store = new Store(app)) {
            assertNull(store.openWalk());
            point(55, 5);
            point(55.001, 5);
            point(55, 5);
            point(55.001, 5);
            assertNull(store.openWalk());
            point(55.001, 5);
            point(55.001, 5);
            long id = store.openWalk().id;
            confirm(55.001);
            assertEquals(id, store.openWalk().id);
            assertEquals(1, store.walks().size());
        }
    }

    @Test
    public void polygonAndAnyOfMultipleZonesFinishWalk() {
        circle();
        try (SpawnZoneStore zones = new SpawnZoneStore(app)) {
            zones.save(
                    new SpawnZone(
                            0,
                            "Polygon",
                            55.002,
                            37,
                            50,
                            Arrays.asList(
                                    new ZonePoint(55.0015, 36.9995),
                                    new ZonePoint(55.0025, 36.9995),
                                    new ZonePoint(55.0025, 37.0005),
                                    new ZonePoint(55.0015, 37.0005))));
        }
        start();
        confirm(55.001);
        confirm(55.002);
        try (Store store = new Store(app)) {
            assertNull(store.openWalk());
            confirm(55);
            assertNull(store.openWalk());
            confirm(55.001);
            assertNotNull(store.openWalk());
        }
    }

    @Test
    public void stopDisablesMonitoringUntilExplicitStart() {
        circle();
        start();
        confirm(55.001);
        service.onStartCommand(new Intent(app, WalkService.class).setAction("STOP"), 0, 2);
        confirm(55.001);
        try (Store store = new Store(app)) {
            assertFalse(WalkService.running);
            assertNull(store.openWalk());
            start();
            confirm(55.001);
            assertTrue(WalkService.running);
            assertNotNull(store.openWalk());
            assertEquals(2, store.walks().size());
        }
    }

    @Test
    public void explicitRestartReconfirmsAndSeparatesSession() {
        circle();
        start();
        confirm(55.001);
        long old;
        try (Store store = new Store(app)) {
            old = (long) store.walkPoints(store.openWalk().id).get(0)[2];
        }
        controller.destroy();
        create();
        start();
        try (Store store = new Store(app)) {
            point(55.001, 5);
            assertEquals(1, store.walkPoints(store.openWalk().id).size());
            point(55.001, 5);
            point(55.001, 5);
            assertNotEquals(old, (long) store.walkPoints(store.openWalk().id).get(1)[2]);
            confirm(55);
            assertNull(store.openWalk());
        }
    }

    @Test
    public void nullRestartDoesNotEnableMonitoring() {
        service.onStartCommand(null, 0, 1);
        assertFalse(WalkService.running);
    }

    @Test
    public void withoutZonesManualWalkStartsImmediatelyAndStopFinishes() {
        start();
        try (Store store = new Store(app)) {
            assertNotNull(store.openWalk());
            confirm(55);
            assertEquals(3, store.walkPoints(store.openWalk().id).size());
            service.onStartCommand(new Intent(app, WalkService.class).setAction("STOP"), 0, 2);
            assertNull(store.openWalk());
        }
    }

    @Test
    public void degradedFixIsRecordedButNeverReveals() {
        start();
        point(55, 100);
        point(55.00001, 30);
        try (Store store = new Store(app)) {
            assertEquals(2, store.walkPoints(store.openWalk().id).size());
            assertEquals(1, store.points().size());
            Store.PointPage page = store.pointsAfter(0, 1);
            assertTrue(page.points.isEmpty());
            assertTrue(page.lastId > 0);
            assertEquals(1, store.pointsAfter(page.lastId, 10).points.size());
        }
    }

    @Test
    public void correctingTransportCannotRevealItsInaccuratePoints() {
        MotionRuntime.INSTANCE.setMode(app, ru.main.explorer.motion.MovementType.VEHICLE);
        start();
        point(55, 100);
        point(55.00001, 5);
        try (Store store = new Store(app);
                MovementStore movements = new MovementStore(app)) {
            assertTrue(store.points().isEmpty());
            long walk = store.openWalk().id;
            movements.correct(
                    movements.segments(walk).get(0), ru.main.explorer.motion.MovementType.WALK);
            assertEquals(1, store.points().size());
            assertEquals(1, store.pointsAfter(0, 100).points.size());
            assertEquals(2, movements.points(walk).size());
        }
    }

    @Test
    public void zoneEditAndLongGapRequireFreshConfirmation() {
        circle();
        start();
        point(55.001, 5);
        point(55.001, 5);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(11));
        point(55.001, 5);
        try (Store store = new Store(app);
                SpawnZoneStore zones = new SpawnZoneStore(app)) {
            assertNull(store.openWalk());
            zones.save(new SpawnZone(zones.list().get(0).getId(), "Home", 55.002, 37, 50));
            point(55.001, 5);
            point(55.001, 5);
            assertNull(store.openWalk());
            point(55.001, 5);
            assertNotNull(store.openWalk());
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void activityShowsMonitoringInsideAndStopCannotFallBackToIdleReveal() throws Exception {
        app.getSharedPreferences("map-view", 0).edit().putBoolean("online", false).commit();
        circle();
        start();
        confirm(55);
        org.robolectric.android.controller.ActivityController<MainActivity> activityController =
                Robolectric.buildActivity(MainActivity.class).create().start().resume();
        try {
            MainActivity activity = activityController.get();
            activity.refresh();
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField("start");
            field.setAccessible(true);
            android.widget.Button button = (android.widget.Button) field.get(activity);
            assertEquals(
                    Language.text(activity, R.string.zone_stop_monitoring),
                    button.getText().toString());
            assertEquals(
                    Language.text(service, R.string.zone_inside),
                    service.notification().extras.getString(android.app.Notification.EXTRA_TEXT));
            button.performClick();
            Intent stop = org.robolectric.Shadows.shadowOf(activity).getNextStartedService();
            assertEquals("STOP", stop.getAction());
            service.onStartCommand(stop, 0, 2);
            Location l = point(55.001, 5);
            activity.onLocationChanged(l);
            try (Store store = new Store(app)) {
                assertFalse(WalkService.running);
                assertNull(store.openWalk());
                assertTrue(store.points().isEmpty());
                assertEquals(0, store.latestPointId());
            }
        } finally {
            activityController.pause().stop().destroy();
        }
    }
}

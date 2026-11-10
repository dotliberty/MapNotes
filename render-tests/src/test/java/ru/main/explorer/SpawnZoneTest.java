package ru.main.explorer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.DialogInterface;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.location.Location;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.EditText;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "ru-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SpawnZoneTest {
    Application app;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("walks.db");
        app.getSharedPreferences("map-view", 0).edit().putBoolean("online", false).commit();
        WalkService.running = false;
    }

    SpawnZone zone(String name, double lat, double lon, double radius) {
        return new SpawnZone(0, name, lat, lon, radius);
    }

    MapView map() {
        MapView map = new MapView(app, false);
        map.online = false;
        map.illustrated = true;
        map.follow = false;
        map.zoom = 18;
        map.cx = MapView.x(37.61);
        map.cy = MapView.y(55.75);
        map.layout(0, 0, 432, 800);
        return map;
    }

    @Test
    public void customCircleRadiusAndDateLineBoundaryAreGeographic() {
        for (SpawnZone zone :
                Arrays.asList(
                        zone("Moscow", 55.75, 37.61, 125.5),
                        zone("Date line", 0, 179.999, 1000),
                        zone("North", 84.8, 0, 500))) {
            double[] boundary = zone.boundary(128);
            assertEquals(256, boundary.length);
            for (int i = 0; i < boundary.length; i += 2) {
                assertTrue(Double.isFinite(boundary[i]));
                assertTrue(boundary[i + 1] >= -180 && boundary[i + 1] <= 180);
                assertEquals(
                        zone.getRadiusMeters(),
                        GpsFilter.distance(
                                zone.getLatitude(),
                                zone.getLongitude(),
                                boundary[i],
                                boundary[i + 1]),
                        .001);
            }
        }
    }

    @Test
    public void invalidRadiiCoordinatesNamesAndPolarExtentsAreRejected() {
        for (double radius : new double[] {0, -1, 100001, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> zone("Test", 0, 0, radius));
        }
        for (String name :
                new String[] {
                    "",
                    " ",
                    " padded ",
                    "line\nbreak",
                    "x\u0000x",
                    String.join("", Collections.nCopies(81, "a"))
                }) {
            assertThrows(IllegalArgumentException.class, () -> zone(name, 0, 0, 100));
        }
        assertThrows(IllegalArgumentException.class, () -> zone("Test", Double.NaN, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> zone("Test", 0, 181, 100));
        assertThrows(IllegalArgumentException.class, () -> zone("Test", 85, 0, 100000));
        assertThrows(IllegalArgumentException.class, () -> zone("Test", 90, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> zone("Test", 0, 0, 100).boundary(15));
        assertThrows(IllegalArgumentException.class, () -> zone("Test", 0, 0, 100).boundary(4097));
    }

    @Test
    public void createEditMoveResizeDeleteAndReopenKeepStableIds() {
        long id;
        try (SpawnZoneStore zones = new SpawnZoneStore(app)) {
            SpawnZone first = zones.save(zone("Bob's camp", 55.75, 37.61, 12.5));
            id = first.getId();
            assertTrue(id > 0);
            SpawnZone changed = zones.save(new SpawnZone(id, "Renamed", 55.76, 37.62, 543.25));
            assertEquals(id, changed.getId());
        }
        try (SpawnZoneStore zones = new SpawnZoneStore(app)) {
            assertEquals(1, zones.list().size());
            SpawnZone saved = zones.list().get(0);
            assertEquals(id, saved.getId());
            assertEquals("Renamed", saved.getName());
            assertEquals(55.76, saved.getLatitude(), 0);
            assertEquals(37.62, saved.getLongitude(), 0);
            assertEquals(543.25, saved.getRadiusMeters(), 0);
            assertTrue(zones.delete(id));
            assertFalse(zones.delete(id));
            SpawnZone next = zones.save(zone("New", 0, 0, 1));
            assertTrue(next.getId() > id);
        }
    }

    @Test
    public void staleEditDoesNotReinsertDeletedZone() {
        try (SpawnZoneStore zones = new SpawnZoneStore(app)) {
            SpawnZone saved = zones.save(zone("Old", 0, 0, 10));
            assertTrue(zones.delete(saved.getId()));
            assertThrows(IllegalStateException.class, () -> zones.save(saved));
            assertTrue(zones.list().isEmpty());
        }
    }

    @Test
    public void zoneLimitDoesNotPreventEditingExistingZone() {
        try (SpawnZoneStore zones = new SpawnZoneStore(app)) {
            SpawnZone first = null;
            for (int i = 0; i < SpawnZoneStore.MAX_ZONES; i++) {
                SpawnZone saved = zones.save(zone("Zone " + i, 0, 0, 1));
                if (first == null) first = saved;
            }
            assertThrows(IllegalStateException.class, () -> zones.save(zone("Extra", 0, 0, 10)));
            zones.save(new SpawnZone(first.getId(), "Edited at limit", 0, 0, 2));
            assertEquals(SpawnZoneStore.MAX_ZONES, zones.list().size());
            assertEquals("Edited at limit", zones.list().get(0).getName());
        }
    }

    @Test
    public void migrationFromV4PreservesJourneyPhotoNoteAndProfile() {
        long walk;
        long photo;
        try (Store store = new Store(app)) {
            walk = store.startWalk(100);
            long session = store.newWalkSession(walk);
            for (int i = 0; i <= 65; i++) {
                Location point = new Location("gps");
                point.setLatitude(55 + i / 111195.0);
                point.setLongitude(37);
                point.setAccuracy(5);
                point.setSpeed(1);
                point.setTime(1000L + i * 1000L);
                point.setElapsedRealtimeNanos((i + 1L) * 1000000000L);
                store.addMovement(point, session, "WALK", "auto", false);
            }
            Location location = new Location("gps");
            location.setLatitude(55);
            location.setLongitude(37);
            location.setAccuracy(5);
            photo = store.addPhoto("private/test.jpg", location, 1000);
            store.saveNote(photo, "retained note");
        }
        try (MovementStore movement = new MovementStore(app)) {
            assertTrue(movement.confirm(movement.segments(walk).get(0)));
        }
        try (Store store = new Store(app)) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.execSQL("DROP TABLE spawn_zones");
            db.setVersion(4);
        }
        try (Store store = new Store(app);
                SpawnZoneStore zones = new SpawnZoneStore(app);
                MovementStore movement = new MovementStore(app)) {
            assertEquals(6, store.getReadableDatabase().getVersion());
            assertEquals(walk, store.openWalk().id);
            assertEquals(66, store.walkPoints(walk).size());
            assertEquals("retained note", store.photo(photo).note);
            assertEquals(1, movement.profile().getWalkCount());
            assertTrue(zones.list().isEmpty());
            SpawnZone saved = zones.save(zone("Independent", 55, 37, 100));
            assertTrue(zones.delete(saved.getId()));
            assertEquals(66, store.walkPoints(walk).size());
            assertEquals("private/test.jpg", store.photo(photo).path);
            assertEquals(1, movement.profile().getWalkCount());
        }
    }

    @Test
    public void renderingIsVisibleButDoesNotRevealOrRecordAnything() throws Exception {
        MapView map = map();
        try {
            Bitmap before = Bitmap.createBitmap(432, 800, Bitmap.Config.ARGB_8888);
            map.draw(new Canvas(before));
            assertTrue(map.routeReveal().isEmpty());
            List<SpawnZone> zones = Arrays.asList(zone("Test camp", 55.75, 37.61, 45));
            map.setSpawnZones(zones);
            Bitmap after = Bitmap.createBitmap(432, 800, Bitmap.Config.ARGB_8888);
            map.draw(new Canvas(after));
            assertNotEquals(before.getPixel(246, 400), after.getPixel(246, 400));
            assertTrue(map.routeReveal().isEmpty());
            assertFalse(WalkService.running);
            try (Store store = new Store(app)) {
                assertTrue(store.points().isEmpty());
                assertTrue(store.walks().isEmpty());
            }
            File directory = new File("build/rendered");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            try (FileOutputStream file =
                    new FileOutputStream(new File(directory, "1.3.1-spawn-zone.png"))) {
                assertTrue(after.compress(Bitmap.CompressFormat.PNG, 100, file));
            }
        } finally {
            map.close();
        }
    }

    @Test
    public void fractionalProjectionAndWorldWrapHitTheSameZone() {
        MapView map = map();
        try {
            map.viewportFraction = 1.5f;
            double[] coordinate = map.zoneCoordinate(260, 330);
            double factor = (1 << map.zoom) * 1.5;
            double dx = MapView.x(coordinate[1]) - map.cx;
            assertEquals(260, map.getWidth() / 2.0 + dx * factor, .0001);
            assertEquals(
                    330,
                    map.getHeight() / 2.0 + (MapView.y(coordinate[0]) - map.cy) * factor,
                    .0001);
            SpawnZoneOverlay overlay = new SpawnZoneOverlay();
            overlay.setZones(Arrays.asList(zone("Here", coordinate[0], coordinate[1], 20)));
            assertEquals(1, overlay.hit(map, 260, 330).size());
            assertTrue(overlay.hit(map, 5, 760).isEmpty());
            map.zoom = 15;
            map.viewportFraction = 1;
            map.cx = MapView.x(-179.999);
            map.cy = MapView.y(0);
            overlay.setZones(Arrays.asList(zone("Date line", 0, 179.999, 500)));
            assertEquals(1, overlay.hit(map, 216, 400).size());
            Bitmap image = Bitmap.createBitmap(432, 800, Bitmap.Config.ARGB_8888);
            overlay.draw(new Canvas(image), map);
            assertNotEquals(0, image.getPixel(216, 400));
        } finally {
            map.close();
        }
    }

    @Test
    public void mapSnapshotsCannotBeMutatedByCallers() {
        MapView map = map();
        try {
            List<SpawnZone> input = new ArrayList<>();
            input.add(zone("Fixed", 55, 37, 1));
            map.setSpawnZones(input);
            input.clear();
            assertEquals(1, map.getSpawnZones().size());
            assertThrows(UnsupportedOperationException.class, () -> map.getSpawnZones().clear());
        } finally {
            map.close();
        }
    }

    @Test
    public void longPressSelectsMapCoordinateAndDetachCancelsPendingGesture() {
        MapView map = map();
        AtomicReference<double[]> picked = new AtomicReference<>();
        try {
            map.viewportFraction = 1.5f;
            map.setZoneLongPressListener((lat, lon) -> picked.set(new double[] {lat, lon}));
            double[] expected = map.zoneCoordinate(260, 330);
            press(map, 260, 330);
            Shadows.shadowOf(Looper.getMainLooper())
                    .idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 150));
            assertNotNull(picked.get());
            assertArrayEquals(expected, picked.get(), .000001);
            picked.set(null);
            release(map, 260, 330);
            press(map, 100, 100);
            map.onDetachedFromWindow();
            Shadows.shadowOf(Looper.getMainLooper())
                    .idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 150));
            assertNull(picked.get());
        } finally {
            map.close();
        }
    }

    @Test
    public void mainActivityReloadsZonesAndMenuCreatesAtVisibleCentre() throws Exception {
        try (SpawnZoneStore zones = new SpawnZoneStore(app)) {
            zones.save(zone("Test camp", 55.748, 37.612, 60));
        }
        WorldMapTest fixture = new WorldMapTest();
        fixture.setup();
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).create();
        try {
            MainActivity activity = controller.get();
            AlertDialog intro = ShadowAlertDialog.getLatestAlertDialog();
            if (intro != null && intro.isShowing()) intro.dismiss();
            Field mapField = MainActivity.class.getDeclaredField("map");
            mapField.setAccessible(true);
            MapView map = (MapView) mapField.get(activity);
            Field ownerField = MainActivity.class.getDeclaredField("spawnZoneController");
            ownerField.setAccessible(true);
            SpawnZoneController owner = (SpawnZoneController) ownerField.get(activity);
            await(owner);
            assertEquals(1, map.getSpawnZones().size());
            map.asyncRendering = false;
            map.online = false;
            map.follow = false;
            map.cx = MapView.x(37.612);
            map.cy = MapView.y(55.748);
            map.zoom = 17;
            File archive = fixture.database(fixture.fixture(), 14, 9903, 5122, false);
            WorldMap world = WorldMap.open(activity, android.net.Uri.fromFile(archive), null);
            world.tile(14, 9903, 5122, true);
            Field worldField = MapView.class.getDeclaredField("world");
            worldField.setAccessible(true);
            worldField.set(map, world);
            View root = activity.findViewById(android.R.id.content);
            root.measure(
                    View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 432, 900);
            Bitmap image = Bitmap.createBitmap(432, 900, Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(image));
            File directory = new File("build/rendered");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            try (FileOutputStream file =
                    new FileOutputStream(new File(directory, "1.3.1-main-zone.png"))) {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, file));
            }
            activity.menu();
            choose(ShadowAlertDialog.getLatestAlertDialog(), 11);
            await(owner);
            AlertDialog manager = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(1, manager.getListView().getAdapter().getCount());
            assertTrue(
                    manager.getListView().getAdapter().getItem(0).toString().contains("Test camp"));
            manager.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            await(owner);
            AlertDialog editor = ShadowAlertDialog.getLatestAlertDialog();
            EditText latitudeField =
                    input(
                            editor.getWindow().getDecorView(),
                            activity.getString(R.string.zone_latitude));
            assertNotNull("Zone centre latitude editor", latitudeField);
            String latitude = latitudeField.getText().toString();
            assertEquals(55.748, Double.parseDouble(latitude), .0000001);
            editor.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
            try (SpawnZoneStore zones = new SpawnZoneStore(app);
                    Store store = new Store(app)) {
                assertEquals(1, zones.list().size());
                assertTrue(store.points().isEmpty());
                assertTrue(store.walks().isEmpty());
            }
        } finally {
            controller.destroy();
            fixture.cleanup();
        }
    }

    void press(MapView map, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        map.onTouchEvent(event);
        event.recycle();
    }

    void release(MapView map, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0);
        map.onTouchEvent(event);
        event.recycle();
    }

    void await(SpawnZoneController controller) throws Exception {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Field field = SpawnZoneController.class.getDeclaredField("worker");
        field.setAccessible(true);
        ((ExecutorService) field.get(controller)).submit(() -> {}).get(10, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    EditText input(View root, String description) {
        if (root instanceof EditText && description.contentEquals(root.getContentDescription()))
            return (EditText) root;
        if (root instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
                EditText found = input(((ViewGroup) root).getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    void fill(AlertDialog dialog, Activity activity, int resource, String value) {
        EditText input = input(dialog.getWindow().getDecorView(), activity.getString(resource));
        assertNotNull(input);
        input.setText(value);
    }

    void choose(AlertDialog dialog, int index) {
        dialog.getListView()
                .performItemClick(null, index, dialog.getListView().getAdapter().getItemId(index));
    }

    @Test
    public void editorRejectsInvalidValuesAndCancelNeverPersistsDraft() throws Exception {
        ActivityController<Activity> activity =
                Robolectric.buildActivity(Activity.class).create().start().resume();
        MapView map = map();
        SpawnZoneController controller = new SpawnZoneController(activity.get(), map);
        try {
            await(controller);
            controller.createAt(55.75, 37.61);
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            fill(dialog, activity.get(), R.string.zone_name, "");
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            assertTrue(dialog.isShowing());
            assertNotNull(
                    input(
                                    dialog.getWindow().getDecorView(),
                                    activity.get().getString(R.string.zone_name))
                            .getError());
            fill(dialog, activity.get(), R.string.zone_name, "Valid");
            fill(dialog, activity.get(), R.string.zone_radius, "0");
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            assertTrue(dialog.isShowing());
            assertNotNull(
                    input(
                                    dialog.getWindow().getDecorView(),
                                    activity.get().getString(R.string.zone_radius))
                            .getError());
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
            await(controller);
            try (SpawnZoneStore zones = new SpawnZoneStore(app)) {
                assertTrue(zones.list().isEmpty());
            }
        } finally {
            controller.close();
            map.close();
            activity.pause().stop().destroy();
        }
    }

    @Test
    public void realEditorSavesDecimalCommaAndManagerSupportsChangeAndConfirmedDelete()
            throws Exception {
        ActivityController<Activity> activity =
                Robolectric.buildActivity(Activity.class).create().start().resume();
        MapView map = map();
        SpawnZoneController controller = new SpawnZoneController(activity.get(), map);
        try {
            await(controller);
            controller.createAt(55.75, 37.61);
            AlertDialog editor = ShadowAlertDialog.getLatestAlertDialog();
            fill(editor, activity.get(), R.string.zone_name, "Test zone");
            fill(editor, activity.get(), R.string.zone_radius, "125,5");
            fill(editor, activity.get(), R.string.zone_latitude, "55,75");
            fill(editor, activity.get(), R.string.zone_longitude, "37,61");
            editor.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            await(controller);
            assertFalse(editor.isShowing());
            assertEquals(1, map.getSpawnZones().size());
            long id = map.getSpawnZones().get(0).getId();
            assertEquals(125.5, map.getSpawnZones().get(0).getRadiusMeters(), 0);
            controller.show();
            await(controller);
            choose(ShadowAlertDialog.getLatestAlertDialog(), 0);
            choose(ShadowAlertDialog.getLatestAlertDialog(), 1);
            editor = ShadowAlertDialog.getLatestAlertDialog();
            fill(editor, activity.get(), R.string.zone_name, "Changed");
            fill(editor, activity.get(), R.string.zone_radius, "250");
            fill(editor, activity.get(), R.string.zone_latitude, "55.76");
            editor.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            await(controller);
            assertEquals(id, map.getSpawnZones().get(0).getId());
            assertEquals("Changed", map.getSpawnZones().get(0).getName());
            assertEquals(55.76, map.getSpawnZones().get(0).getLatitude(), 0);
            controller.show();
            await(controller);
            choose(ShadowAlertDialog.getLatestAlertDialog(), 0);
            choose(ShadowAlertDialog.getLatestAlertDialog(), 2);
            AlertDialog confirm = ShadowAlertDialog.getLatestAlertDialog();
            confirm.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
            try (SpawnZoneStore zones = new SpawnZoneStore(app)) {
                assertEquals(1, zones.list().size());
            }
            controller.show();
            await(controller);
            choose(ShadowAlertDialog.getLatestAlertDialog(), 0);
            choose(ShadowAlertDialog.getLatestAlertDialog(), 2);
            ShadowAlertDialog.getLatestAlertDialog()
                    .getButton(DialogInterface.BUTTON_POSITIVE)
                    .performClick();
            await(controller);
            assertTrue(map.getSpawnZones().isEmpty());
            try (SpawnZoneStore zones = new SpawnZoneStore(app)) {
                assertTrue(zones.list().isEmpty());
            }
        } finally {
            controller.close();
            map.close();
            activity.pause().stop().destroy();
        }
    }
}

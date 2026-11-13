package ru.main.explorer;

import static org.junit.Assert.*;

import android.graphics.*;
import android.os.Looper;
import android.view.MotionEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MapReviewR2Test {
    private long time = 1000, down;
    String gestureBeginning;

    void touch(MapView map, int action, float span, boolean pair) {
        int count = pair ? 2 : 1;
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = 256 + (i == 0 ? -span / 2 : span / 2);
            coords[i].y = 256;
            coords[i].pressure = coords[i].size = 1;
        }
        time += 16;
        MotionEvent event =
                MotionEvent.obtain(
                        down,
                        time,
                        action,
                        count,
                        properties,
                        coords,
                        0,
                        0,
                        1,
                        1,
                        0,
                        0,
                        android.view.InputDevice.SOURCE_TOUCHSCREEN,
                        0);
        map.onTouchEvent(event);
        if (gestureBeginning == null && event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN)
            gestureBeginning = MapTrace.snapshot();
        event.recycle();
    }

    void begin(MapView map) {
        down = time + 16;
        touch(map, MotionEvent.ACTION_DOWN, 200, false);
        touch(
                map,
                MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                200,
                true);
        touch(map, MotionEvent.ACTION_MOVE, 300, true);
    }

    void end(MapView map, float span) {
        touch(
                map,
                MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                span,
                true);
        touch(map, MotionEvent.ACTION_UP, span, false);
    }

    static ArrayList<double[]> history() {
        ArrayList<double[]> result = new ArrayList<>();
        for (int i = 0; i < 110000; i++) {
            int row = i / 330, col = i % 330;
            if ((row & 1) != 0) col = 329 - col;
            result.add(new double[] {(row - 166) * .001, (col - 165) * .001, 1, i * 1000L, i});
        }
        for (int i = 1; i < result.size(); i++)
            assertTrue(MapView.connects(result.get(i - 1), result.get(i)));
        return result;
    }

    static JSONObject scene() throws Exception {
        JSONArray elements = new JSONArray();
        JSONArray water = new JSONArray();
        for (double[] p : new double[][] {{-.3, -.3}, {-.3, .3}, {.3, .3}, {.3, -.3}, {-.3, -.3}})
            water.put(new JSONObject().put("lat", p[0]).put("lon", p[1]));
        elements.put(
                new JSONObject()
                        .put("tags", new JSONObject().put("natural", "water"))
                        .put("geometry", water));
        elements.put(
                new JSONObject()
                        .put("tags", new JSONObject().put("highway", "primary"))
                        .put(
                                "geometry",
                                new JSONArray()
                                        .put(new JSONObject().put("lat", -.4).put("lon", 0))
                                        .put(new JSONObject().put("lat", .4).put("lon", 0))));
        elements.put(
                new JSONObject()
                        .put("tags", new JSONObject().put("building", "yes"))
                        .put(
                                "geometry",
                                new JSONArray()
                                        .put(new JSONObject().put("lat", .0001).put("lon", .0001))
                                        .put(new JSONObject().put("lat", .0001).put("lon", .0002))
                                        .put(new JSONObject().put("lat", .0002).put("lon", .0002))
                                        .put(new JSONObject().put("lat", .0002).put("lon", .0001))
                                        .put(
                                                new JSONObject()
                                                        .put("lat", .0001)
                                                        .put("lon", .0001))));
        return new JSONObject().put("elements", elements);
    }

    void pixels(MapView map, OfflineAtlas atlas, String label) throws Exception {
        Bitmap image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        map.draw(new Canvas(image));
        for (boolean color : new boolean[] {false, true}) {
            image.eraseColor(Color.TRANSPARENT);
            atlas.draw(new Canvas(image), map, color);
            double coverage = color ? atlas.lastColorCoverageFraction : atlas.lastCoverageFraction;
            assertTrue(label + " usable " + color + " coverage=" + coverage, coverage > .01);
            AtlasViewport.Frame ready =
                    (AtlasViewport.Frame) MapStressMotionTest.get(atlas.viewport, "ready");
            if (ready.request.zoom == 14 && atlas.areas.size() == 2) {
                double factor = Math.pow(2, map.zoom - 14);
                float half = (float) (256 * factor);
                float centerX = map.screenX(0),
                        centerY = (float) ((128 - map.cy) * (1 << map.zoom) + 256);
                RectF expected =
                        new RectF(centerX - half, centerY - half, centerX + half, centerY + half);
                RectF visible = AtlasCoverage.visible(512, 512, map.viewportFraction);
                assertTrue(expected.intersect(visible));
                // Холодный сосед перекрывает только правую полосу; она не считается готовой.
                expected.right = Math.min(expected.right, map.screenX(.025) - 1);
                double expectedFraction =
                        expected.width() * expected.height() / (visible.width() * visible.height());
                assertEquals(
                        label + " exact usable overlap " + color,
                        expectedFraction,
                        coverage,
                        .0001);
            }
            int roadX = Math.round(map.screenX(0));
            assertTrue(
                    label + " reference road remains visible x=" + roadX,
                    roadX >= 0 && roadX < 512);
            int water = image.getPixel(roadX + 10, 256);
            assertEquals(label + " geometry remains opaque " + color, 255, Color.alpha(water));
            if (map.zoom <= 15)
                assertEquals(
                        label + " known water geometry " + color,
                        color ? 0xff78aeb7 : 0xff9cabb1,
                        water);
            else
                assertTrue(
                        label + " scaled known geometry palette " + color,
                        Color.red(water) >= 120
                                && Color.green(water) >= 150
                                && Color.blue(water) >= 160);
        }
        image.recycle();
    }

    void zoom(MapView map, OfflineAtlas atlas, double target) throws Exception {
        int gestures = 0;
        while (Math.abs(map.displayZoom - target) > .01) {
            assertTrue(
                    "bounded gestures reached z=" + map.displayZoom + " target=" + target,
                    gestures++ < 40);
            begin(map);
            double initial = map.displayZoom;
            float span = 300;
            for (int step = 1; step <= 4; step++) {
                double next =
                        initial
                                + Math.copySign(
                                        Math.min(Math.abs(target - initial), step * .125),
                                        target - initial);
                if (Math.abs(next - target) < .0001) next = target + .0001;
                span = (float) (300 * Math.pow(2, next - initial));
                touch(map, MotionEvent.ACTION_MOVE, span, true);
                pixels(map, atlas, "z" + map.displayZoom);
            }
            end(map, span);
        }
    }

    @Test(timeout = 45000)
    public void realPinchRetainsKnownDiskGeometryWith110000AcceptedPoints() throws Exception {
        System.out.println("R2 setup");
        MapMotionRegressionTest fixture = new MapMotionRegressionTest();
        fixture.setup();
        MapView map = fixture.map;
        OfflineAtlas atlas = fixture.atlas;
        map.zoom = 14;
        map.displayZoom = 14;
        map.illustrated = true;
        atlas.areas.clear();
        File file = new File(RuntimeEnvironment.getApplication().getCacheDir(), "r2-scene.json");
        Files.write(file.toPath(), scene().toString().getBytes(StandardCharsets.UTF_8));
        AtlasDiskSource disk =
                AtlasDiskSource.build(file, file.getParentFile(), false, false, () -> false);
        OfflineAtlas.IndexedWays ways = new OfflineAtlas.IndexedWays();
        ways.index();
        ways.disk = disk;
        fixture.addArea(-.4, .4, ways);
        ArrayList<double[]> points = history();
        System.out.println("R2 accepted history ready");
        java.lang.reflect.Field field = MapView.class.getDeclaredField("points");
        field.setAccessible(true);
        field.set(map, points);
        map.setPhotos(new ArrayList<>(Arrays.asList(new PhotoSelectionTest().photo(201, .0002))));
        CountDownLatch mask = MapStressMotionTest.block(map, "routeWorker");
        CountDownLatch tile = null, viewport = null, line = null, imports = null;
        try {
            for (int i = 0; i < 3; i++) {
                fixture.frame().recycle();
                atlas.viewport.awaitIdle();
            }
            ((RouteLines) MapStressMotionTest.get(map, "routeLines")).awaitIdle();
            pixels(map, atlas, "warm");
            File checkpoints = new File("build/review-r2");
            checkpoints.mkdirs();
            Files.write(
                    new File(checkpoints, "gesture-start.txt").toPath(),
                    MapTrace.snapshot().getBytes(StandardCharsets.UTF_8));
            System.out.println("R2 warm geometry ready");
            tile = MapStressMotionTest.block(atlas.tiles, "worker");
            viewport = MapStressMotionTest.block(atlas.viewport, "worker");
            line = MapStressMotionTest.block(MapStressMotionTest.get(map, "routeLines"), "worker");
            imports = MapStressMotionTest.block(atlas, "loader");
            System.out.println("R2 workers independently blocked");
            OfflineAtlas.Area neighbour = new OfflineAtlas.Area();
            neighbour.source = file;
            neighbour.legacy = true;
            neighbour.south = -.4;
            neighbour.north = .4;
            neighbour.west = .025;
            neighbour.east = .4;
            atlas.areas.add(neighbour);
            down = time + 16;
            touch(map, MotionEvent.ACTION_DOWN, 0, false);
            for (float span : new float[] {64, -64, 0}) {
                touch(map, MotionEvent.ACTION_MOVE, span, false);
                pixels(map, atlas, "pan");
            }
            touch(map, MotionEvent.ACTION_UP, 0, false);
            zoom(map, atlas, 12);
            zoom(map, atlas, 15);
            zoom(map, atlas, 14);
            zoom(map, atlas, 22);
            assertEquals(22, map.zoom);
            Files.write(
                    new File("build/review-r2/gesture-end.txt").toPath(),
                    MapTrace.snapshot().getBytes(StandardCharsets.UTF_8));
            assertTrue(
                    "cold available neighbour independently queued for import",
                    atlas.geometryRequests > 0);
            mask.countDown();
            viewport.countDown();
            tile.countDown();
            line.countDown();
            imports.countDown();
            long start = System.nanoTime();
            boolean recovered = false;
            while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(15)) {
                fixture.frame().recycle();
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
                RouteFrame ready = (RouteFrame) MapStressMotionTest.get(map, "readyFrame");
                RouteFrame readyLine =
                        ((RouteLines) MapStressMotionTest.get(map, "routeLines")).ready(0);
                if (atlas.viewport.exactReady()
                        && ready != null
                        && ready.complete
                        && ready.zoom == 22
                        && readyLine != null
                        && readyLine.zoom == 22) {
                    recovered = true;
                    break;
                }
                Thread.sleep(10);
            }
            assertTrue(
                    "latest geometry, mask and independent line recover after ACTION_UP",
                    recovered);
            RouteFrame ready = (RouteFrame) MapStressMotionTest.get(map, "readyFrame");
            assertEquals(
                    "mask revision is current",
                    (long) (Long) MapStressMotionTest.get(map, "revision"),
                    ready.revision);
            assertEquals(
                    "origin reveals known water", 255, Color.alpha(ready.mask.getPixel(256, 256)));
            assertTrue(
                    "source wall really occludes an inside origin",
                    Visibility.polygon(
                                            .00015,
                                            .00015,
                                            Visibility.RADIUS,
                                            atlas.buildingsNear(.00015, .00015, Visibility.RADIUS))
                                    .length
                            == 0);
            pixels(map, atlas, "stopped");
            Bitmap composed = fixture.frame();
            assertEquals(
                    "current mask composes the actual color pass",
                    0xff78aeb7,
                    composed.getPixel(320, 320));
            composed.recycle();
            System.out.println(
                    "R2 110000 accepted points recovery_ms=" + (System.nanoTime() - start) / 1e6);
        } finally {
            File trace = new File("build/review-r2/gesture-trace.txt");
            trace.getParentFile().mkdirs();
            Files.write(trace.toPath(), MapTrace.snapshot().getBytes(StandardCharsets.UTF_8));
            if (gestureBeginning != null)
                Files.write(
                        new File("build/review-r2/gesture-input-start.txt").toPath(),
                        gestureBeginning.getBytes(StandardCharsets.UTF_8));
            mask.countDown();
            for (CountDownLatch latch : new CountDownLatch[] {tile, viewport, line, imports})
                if (latch != null) latch.countDown();
            fixture.cleanup();
            disk.close();
        }
    }

    @Test
    public void changedAndRemovedSourcesNeverReplayAcrossZoom() throws Exception {
        MapMotionRegressionTest fixture = new MapMotionRegressionTest();
        fixture.setup();
        OfflineAtlas atlas = fixture.atlas;
        MapView map = fixture.map;
        AtlasTiles.Snapshot first = atlas.tileSnapshot(map, false);
        AtlasViewport viewport = atlas.viewport;
        Bitmap image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        CountDownLatch release = null;
        try {
            viewport.draw(new Canvas(image), first, map.cx, map.cy, 512, 512, false, 1);
            viewport.awaitIdle();
            assertFalse(
                    viewport.draw(new Canvas(image), first, map.cx, map.cy, 512, 512, false, 1)
                            .isEmpty());
            release = MapStressMotionTest.block(viewport, "worker");
            atlas.areas.get(0).stamp++;
            map.zoom++;
            AtlasTiles.Snapshot changed = atlas.tileSnapshot(map, false);
            image.eraseColor(Color.TRANSPARENT);
            assertTrue(
                    viewport.draw(new Canvas(image), changed, map.cx, map.cy, 512, 512, false, 1)
                            .isEmpty());
            assertEquals(0, image.getPixel(256, 256));
            atlas.areas.clear();
            AtlasTiles.Snapshot removed = atlas.tileSnapshot(map, false);
            assertTrue(
                    viewport.draw(new Canvas(image), removed, map.cx, map.cy, 512, 512, false, 1)
                            .isEmpty());
        } finally {
            if (release != null) release.countDown();
            fixture.cleanup();
        }
    }

    @Test
    public void lineAndMaskTrackLatestViewportBeforePointerUp() throws Exception {
        MapMotionRegressionTest fixture = new MapMotionRegressionTest();
        fixture.setup();
        MapView map = fixture.map;
        map.zoom = 14;
        map.displayZoom = 14.5;
        map.viewportFraction = (float) Math.sqrt(2);
        map.illustrated = true;
        java.lang.reflect.Field field = MapView.class.getDeclaredField("points");
        field.setAccessible(true);
        field.set(map, history());
        RouteLines lines = (RouteLines) MapStressMotionTest.get(map, "routeLines");
        CountDownLatch mask = MapStressMotionTest.block(map, "routeWorker");
        CountDownLatch line = MapStressMotionTest.block(lines, "worker");
        try {
            fixture.frame().recycle();
            begin(map);
            touch(map, MotionEvent.ACTION_MOVE, 380, true);
            fixture.frame().recycle();
            touch(map, MotionEvent.ACTION_MOVE, 450, true);
            fixture.frame().recycle();
            assertTrue("actual pinch crossed integer zoom", map.zoom > 14);
            RouteFrame wanted = (RouteFrame) MapStressMotionTest.get(lines, "wanted");
            assertEquals("line tracks the active pinch before POINTER_UP", map.zoom, wanted.zoom);
            assertEquals(
                    "mask cancellation tracks active pinch before POINTER_UP",
                    map.zoom,
                    (int) (Integer) MapStressMotionTest.get(map, "desiredZoom"));
            line.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            RouteFrame fresh = null;
            while (System.nanoTime() < deadline) {
                touch(map, MotionEvent.ACTION_MOVE, 450, true);
                fixture.frame().recycle();
                fresh = lines.ready(0);
                if (fresh != null && fresh.zoom == map.zoom) break;
                Thread.sleep(10);
            }
            assertNotNull("line publishes while independent mask worker stays blocked", fresh);
            assertEquals(map.zoom, fresh.zoom);
            assertTrue(
                    "fresh line contains actual accepted-route pixels",
                    Color.alpha(fresh.lineImage.getPixel(256, 256)) > 0);
            assertNull(
                    "fresh line is not treated as a ready reveal",
                    MapStressMotionTest.get(map, "readyFrame"));
            end(map, 450);
        } finally {
            line.countDown();
            mask.countDown();
            fixture.cleanup();
        }
    }

    @Test
    public void absentSourceIsNotReadyAndTraceIsBounded() throws Exception {
        MapMotionRegressionTest fixture = new MapMotionRegressionTest();
        fixture.setup();
        OfflineAtlas atlas = fixture.atlas;
        try {
            atlas.areas.clear();
            OfflineAtlas.Area missing = fixture.addArea(-1, 1, null);
            missing.source =
                    new File(RuntimeEnvironment.getApplication().getFilesDir(), "r2-absent.json");
            fixture.frame().recycle();
            ((ExecutorService) MapStressMotionTest.get(atlas, "loader"))
                    .submit(() -> {})
                    .get(5, TimeUnit.SECONDS);
            AtlasTiles.Snapshot snapshot = atlas.tileSnapshot(fixture.map, false);
            assertFalse(snapshot.layers.get(0).ready);
            assertEquals(0, atlas.geometryLoads);
            for (int i = 0; i < MapTrace.CAPACITY + 50; i++)
                MapTrace.record(MapTrace.Lane.FRAME, MapTrace.Event.DRAW, i, 0, 0, 0);
            String trace = MapTrace.snapshot();
            long ringRows =
                    Arrays.stream(trace.split("\n"))
                            .filter(row -> row.matches("[1-9][0-9]*,.*"))
                            .count();
            assertEquals(MapTrace.CAPACITY, ringRows);
            assertFalse(trace.contains("r2-absent"));
            assertFalse(trace.contains("packs/"));
        } finally {
            fixture.cleanup();
        }
    }

    @Test
    public void manualExportUsesSystemPickerAndWritesFrozenTrace() throws Exception {
        org.robolectric.android.controller.ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        try {
            activity.exportMapTrace();
            org.robolectric.shadows.ShadowActivity.IntentForResult request =
                    Shadows.shadowOf(activity).getNextStartedActivityForResult();
            assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT, request.intent.getAction());
            assertEquals("text/plain", request.intent.getType());
            assertEquals(131, request.requestCode);
            String expected = (String) MapStressMotionTest.get(activity, "pendingMapTrace");
            File destination = new File(activity.getCacheDir(), "r2-export.txt");
            activity.onActivityResult(
                    131,
                    android.app.Activity.RESULT_OK,
                    new android.content.Intent().setData(android.net.Uri.fromFile(destination)));
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((!destination.isFile()
                            || destination.length()
                                    != expected.getBytes(StandardCharsets.UTF_8).length)
                    && System.nanoTime() < end) Thread.sleep(10);
            assertEquals(
                    expected,
                    new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
            assertNull(MapStressMotionTest.get(activity, "pendingMapTrace"));
        } finally {
            controller.pause().stop().destroy();
        }
    }
}

package ru.main.explorer;

import static org.junit.Assert.*;

import android.graphics.*;
import android.os.Looper;
import android.view.MotionEvent;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MapStressMotionTest {
    static Object get(Object object, String name) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    static CountDownLatch block(Object owner, String name) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ((ExecutorService) get(owner, name))
                .submit(
                        () -> {
                            entered.countDown();
                            try {
                                release.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        return release;
    }

    @Test
    public void coldTileCandidatePreparationDoesNotRunOnUi() throws Exception {
        MapMotionRegressionTest fixture = new MapMotionRegressionTest();
        fixture.setup();
        CountDownLatch release = block(fixture.atlas.tiles, "worker");
        try {
            fixture.atlas.areas.get(0).eager = fixture.roads(30000, false);
            fixture.frame();
            assertTrue(fixture.atlas.tiles.pending() > 0);
            assertEquals(
                    "cold crop/query/sort must wait for the blocked worker",
                    0,
                    fixture.atlas.tiles.uiPreparations);
        } finally {
            release.countDown();
            fixture.cleanup();
        }
    }

    @Test
    public void loadingNeighbourDoesNotSwitchReadyCoverageToGeometry() throws Exception {
        MapMotionRegressionTest fixture = new MapMotionRegressionTest();
        fixture.setup();
        CountDownLatch release = block(fixture.atlas, "loader");
        try {
            fixture.warm();
            int hits = fixture.atlas.tiles.visibleHits;
            long geometry = fixture.atlas.geometryNanos;
            OfflineAtlas.Area loading = new OfflineAtlas.Area();
            loading.source =
                    new File(
                            RuntimeEnvironment.getApplication().getFilesDir(),
                            "not-yet-decoded-stress.json");
            loading.south = -1;
            loading.north = 1;
            loading.west = MapMotionRegressionTest.lon(128 + 400.0 / (1 << fixture.map.zoom));
            loading.east = MapMotionRegressionTest.lon(128 + 600.0 / (1 << fixture.map.zoom));
            loading.legacy = true;
            fixture.atlas.areas.add(loading);
            fixture.map.cx += .25 / (1 << fixture.map.zoom);
            fixture.frame();
            assertTrue(
                    "unaffected ready tiles stay on their fast path",
                    fixture.atlas.tiles.visibleHits > hits);
            assertEquals(
                    "no preparation of ready geometry merely because a neighbour loads",
                    geometry,
                    fixture.atlas.geometryNanos);
        } finally {
            release.countDown();
            fixture.cleanup();
        }
    }

    @Test
    public void rulerMeasuresActualFractionalScreenPixels() throws Exception {
        MapMotionRegressionTest fixture = new MapMotionRegressionTest();
        fixture.setup();
        try {
            fixture.map.cy = MapView.y(55.748);
            for (int zoom : new int[] {12, 13, 14, 15, 16})
                for (float fraction : new float[] {1, 1.37f, 1.99f}) {
                    fixture.map.zoom = zoom;
                    fixture.map.viewportFraction = fraction;
                    double expected =
                            156543.03392
                                    * Math.cos(Math.toRadians(55.748))
                                    / ((1 << zoom) * (double) fraction);
                    assertEquals(
                            "physical screen scale zoom=" + zoom + " fraction=" + fraction,
                            expected,
                            fixture.map.metersPerScreenPixel(),
                            1e-9);
                }
        } finally {
            fixture.cleanup();
        }
    }

    @Test
    public void decodedEvictionReusesFileTilesAndDoesNotCacheUnloadedEmptyContent()
            throws Exception {
        MapMotionRegressionTest fixture = new MapMotionRegressionTest();
        fixture.setup();
        File file =
                new File(RuntimeEnvironment.getApplication().getFilesDir(), "stress-eviction.json");
        CountDownLatch release = null;
        try {
            JSONObject data =
                    new JSONObject()
                            .put(
                                    "elements",
                                    new JSONArray()
                                            .put(
                                                    new JSONObject()
                                                            .put(
                                                                    "tags",
                                                                    new JSONObject()
                                                                            .put(
                                                                                    "highway",
                                                                                    "primary"))
                                                            .put(
                                                                    "geometry",
                                                                    new JSONArray()
                                                                            .put(
                                                                                    new JSONObject()
                                                                                            .put(
                                                                                                    "lat",
                                                                                                    0)
                                                                                            .put(
                                                                                                    "lon",
                                                                                                    0))
                                                                            .put(
                                                                                    new JSONObject()
                                                                                            .put(
                                                                                                    "lat",
                                                                                                    .00001)
                                                                                            .put(
                                                                                                    "lon",
                                                                                                    .00001)))));
            write(file, data, false);
            OfflineAtlas.Area area =
                    OfflineAtlas.area(new JSONArray(new double[] {-1, -1, 1, 1}), file);
            area.legacy = true;
            fixture.atlas.areas.clear();
            fixture.atlas.areas.add(area);
            fixture.atlas.loadWays(area);
            fixture.warm();
            AtlasTiles.Snapshot original = fixture.atlas.tileSnapshot(fixture.map, false);
            long tile = (long) (128L * (1 << fixture.map.zoom) / AtlasTiles.SIZE);
            String key = original.tileKey(tile, tile, false);
            release = block(fixture.atlas, "loader");
            ((android.util.LruCache<?, ?>) get(fixture.atlas, "geometry")).evictAll();
            int hits = fixture.atlas.tiles.visibleHits;
            fixture.map.cx += .25 / (1 << fixture.map.zoom);
            fixture.frame();
            assertEquals(1, fixture.atlas.loadingLayers);
            assertTrue(
                    "already decoded coverage survives geometry eviction",
                    fixture.atlas.tiles.visibleHits > hits);
            AtlasTiles.Snapshot loading = fixture.atlas.tileSnapshot(fixture.map, false);
            assertEquals(key, loading.tileKey(tile, tile, false));
            assertNull(
                    "unloaded source cannot publish empty cold content", loading.crop(tile, tile));
            release.countDown();
            release = null;
            ((ExecutorService) get(fixture.atlas, "loader"))
                    .submit(() -> {})
                    .get(10, TimeUnit.SECONDS);
            assertEquals(
                    key, fixture.atlas.tileSnapshot(fixture.map, false).tileKey(tile, tile, false));
        } finally {
            if (release != null) release.countDown();
            fixture.cleanup();
            file.delete();
        }
    }

    @Test
    public void saturatedSceneIsPreparedOffUiAndPreservesGlobalBudgetPixels() throws Exception {
        MapMotionRegressionTest fixture = new MapMotionRegressionTest();
        fixture.setup();
        OfflineAtlas.IndexedWays ways = fixture.roads(40002, false);
        ways.get(0).name = "Long Street";
        ways.get(1).name = "Long Street";
        for (String type : new String[] {"water", "building"}) {
            OfflineAtlas.Way area = new OfflineAtlas.Way();
            area.type = type;
            area.name = "";
            area.area = true;
            double d = "water".equals(type) ? 120 : 20;
            area.coords = new double[10];
            double[][] xy = {{-d, -d}, {d, -d}, {d, d}, {-d, d}, {-d, -d}};
            for (int i = 0; i < xy.length; i++) {
                double lat = MapMotionRegressionTest.lat(128 + xy[i][1] / (1 << fixture.map.zoom));
                double lon = MapMotionRegressionTest.lon(128 + xy[i][0] / (1 << fixture.map.zoom));
                area.coords[i * 2] = lat;
                area.coords[i * 2 + 1] = lon;
                area.south = Math.min(area.south, lat);
                area.north = Math.max(area.north, lat);
                area.west = Math.min(area.west, lon);
                area.east = Math.max(area.east, lon);
            }
            ways.add(area);
        }
        ways.index();
        fixture.atlas.areas.get(0).eager = ways;
        fixture.addArea(
                0,
                MapMotionRegressionTest.lon(128 + 256.0 / (1 << fixture.map.zoom)),
                fixture.roads(3, false));
        CountDownLatch release = block(fixture.atlas.viewport, "worker");
        try {
            fixture.atlas.draw(
                    new Canvas(Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)),
                    fixture.map,
                    false);
            assertEquals("viewport-worker", fixture.atlas.renderBackend);
            assertEquals("no global Path preparation on UI", 0, fixture.atlas.geometryNanos);
            assertTrue(fixture.atlas.viewport.requested > 0);
        } finally {
            release.countDown();
        }
        try {
            fixture.atlas.viewport.awaitIdle();
            for (boolean illustrated : new boolean[] {false, true}) {
                fixture.map.illustrated = illustrated;
                fixture.atlas.draw(
                        new Canvas(Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)),
                        fixture.map,
                        false);
                fixture.atlas.viewport.awaitIdle();
                for (boolean hybrid : new boolean[] {false, true}) {
                    Bitmap expected = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
                    Bitmap actual = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
                    fixture.atlas.drawGeometry(new Canvas(expected), fixture.map, hybrid);
                    int vertices = fixture.atlas.lastVertices;
                    fixture.atlas.draw(new Canvas(actual), fixture.map, hybrid);
                    assertEquals(80000, vertices);
                    assertEquals(vertices, fixture.atlas.lastVertices);
                    assertTrue(
                            "native global admission/labels/occlusion equal hybrid="
                                    + hybrid
                                    + " illustrated="
                                    + illustrated,
                            expected.sameAs(actual));
                }
            }
        } finally {
            fixture.cleanup();
        }
    }

    @Test
    public void viewportRejectsChangedSourcesAndCloseDropsCoalescedWork() throws Exception {
        MapMotionRegressionTest fixture = new MapMotionRegressionTest();
        fixture.setup();
        try {
            fixture.atlas.areas.get(0).eager = fixture.roads(40001, false);
            Canvas canvas = new Canvas(Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888));
            fixture.atlas.draw(canvas, fixture.map, false);
            fixture.atlas.viewport.awaitIdle();
            assertEquals(1, fixture.atlas.viewport.completed);
            CountDownLatch release = block(fixture.atlas.viewport, "worker");
            try {
                fixture.atlas.areas.get(0).stamp++;
                Bitmap cold = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
                fixture.atlas.draw(new Canvas(cold), fixture.map, false);
                assertEquals(
                        "old source may not be replayed", 0, Color.alpha(cold.getPixel(256, 256)));
                for (int i = 0; i < 20; i++) {
                    fixture.map.cx += 2.0 / (1 << fixture.map.zoom);
                    fixture.atlas.draw(canvas, fixture.map, false);
                }
                fixture.atlas.close();
            } finally {
                release.countDown();
            }
            fixture.atlas.viewport.awaitIdle();
            assertEquals(
                    "closed pending viewport is not published",
                    1,
                    fixture.atlas.viewport.completed);
        } finally {
            fixture.cleanup();
        }
    }

    static void write(File file, JSONObject json, boolean gzip) throws Exception {
        file.getParentFile().mkdirs();
        try (OutputStream raw = new FileOutputStream(file);
                OutputStream out = gzip ? new GZIPOutputStream(raw) : raw) {
            out.write(json.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    static JSONObject roads(int count, double lat, double lon, int side) throws Exception {
        JSONArray elements = new JSONArray();
        for (int i = 0; i < count; i++) {
            double y = lat - .02 + (i / 200 % 150) * .0002;
            double x = lon + (side < 0 ? -.04 : 0) + i % 200 * .0002;
            elements.put(
                    new JSONObject()
                            .put(
                                    "tags",
                                    new JSONObject()
                                            .put("highway", "primary")
                                            .put("name", "Street " + i))
                            .put(
                                    "geometry",
                                    new JSONArray()
                                            .put(new JSONObject().put("lat", y).put("lon", x))
                                            .put(
                                                    new JSONObject()
                                                            .put("lat", y + .00005)
                                                            .put("lon", x + .00005))));
        }
        return new JSONObject().put("elements", elements);
    }

    static void deleteFixture(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteFixture(child);
        file.delete();
    }

    @Test
    public void fullFrameZoomAndRapidReverseScrollTraceWithoutWorkerWaits() throws Exception {
        double lat = 55.748, lon = 37.612;
        File pack =
                new File(
                        RuntimeEnvironment.getApplication().getFilesDir(),
                        "packs/stress-" + UUID.randomUUID());
        JSONArray bounds = new JSONArray(new double[] {lat - .08, lon - .08, lat + .08, lon + .08});
        write(
                new File(pack, "pack.json"),
                new JSONObject().put("bounds", bounds).put("complete", true),
                false);
        write(new File(pack, "overview.json.gz"), roads(6000, lat, lon, -1), true);
        for (int side : new int[] {-1, 1}) {
            String name = "detail-" + side + ".json.gz";
            write(new File(pack, name), roads(30000, lat, lon, side), true);
            JSONArray area =
                    new JSONArray(
                            new double[] {
                                lat - .08,
                                lon + (side < 0 ? -.08 : 0),
                                lat + .08,
                                lon + (side < 0 ? 0 : .08)
                            });
            write(
                    new File(pack, side + ".meta"),
                    new JSONObject().put("bounds", area).put("file", name),
                    false);
        }
        MapView map = new MapView(RuntimeEnvironment.getApplication(), true);
        map.online = false;
        map.follow = false;
        map.illustrated = true;
        map.layout(0, 0, 1080, 2340);
        map.getResources().getDisplayMetrics().density = 3;
        map.cx = MapView.x(lon);
        map.cy = MapView.y(lat);
        map.zoom = 15;
        ArrayList<double[]> points = new ArrayList<>();
        for (int i = 0; i < 600; i++)
            points.add(
                    new double[] {
                        lat + (i / 100 - 3) * .0001, lon + (i % 100 - 50) * .0001, 1, i * 1000
                    });
        map.update(points);
        Canvas canvas = new Canvas(Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888));
        ArrayList<String> trace = new ArrayList<>();
        trace.add(
                "frame,zoom,fraction,barMetres,cx,cy,fullMs,baseMs,maskMs,geometryMs,uiPrepareMs,fallback,tilePending,hits,misses,geometryRequests,geometryLoads,routeRequested,routeCompleted,routeCancelled,routeBuildMs,backend,loadingLayers,workerCropMs,viewportRequested,viewportCompleted,viewportBuildMs,baseCoverage,colorCoverage");
        long[] times = new long[144];
        Map<String, Integer> reasons = new TreeMap<>();
        int peakPending = 0;
        long uiGeometryNanos = 0, uiPreparationNanos = 0;
        long time = 1000;
        int availableFrames = 0, fullFrames = 0;
        double coverageSum = 0;
        try {
            int[] zooms = {15, 14, 13, 12, 13, 14, 15, 15};
            for (int i = 0; i < times.length; i++) {
                map.zoom = zooms[i / 6 % zooms.length];
                map.viewportFraction = i % 3 == 0 ? 1.37f : 1;
                map.displayZoom = map.zoom + Math.log(map.viewportFraction) / Math.log(2);
                float x = i % 2 == 0 ? 180 : 900, y = i % 4 < 2 ? 500 : 1800;
                if (i % 6 == 0) {
                    // Независимые разнонаправленные протяжки возвращаются в ту же область.
                    map.cx = MapView.x(lon);
                    map.cy = MapView.y(lat);
                    MotionEvent down =
                            MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 540, 1170, 0);
                    map.onTouchEvent(down);
                    down.recycle();
                }
                time += 16;
                MotionEvent move =
                        MotionEvent.obtain(
                                time - i % 6 * 16, time, MotionEvent.ACTION_MOVE, x, y, 0);
                map.onTouchEvent(move);
                move.recycle();
                OfflineAtlas atlas = (OfflineAtlas) get(map, "atlas");
                long geometry = atlas == null ? 0 : atlas.geometryNanos;
                long prep = atlas == null ? 0 : atlas.tiles.uiPreparationNanos;
                long start = System.nanoTime();
                map.draw(canvas);
                times[i] = System.nanoTime() - start;
                String reason = atlas == null ? "index-loading" : atlas.tileFallback;
                reasons.merge(reason, 1, Integer::sum);
                int pending = atlas == null ? 0 : atlas.tiles.pending();
                peakPending = Math.max(peakPending, pending);
                if (atlas != null) {
                    uiGeometryNanos += atlas.geometryNanos - geometry;
                    uiPreparationNanos += atlas.tiles.uiPreparationNanos - prep;
                    assertTrue(
                            "coverage is a bounded screen fraction",
                            atlas.lastCoverageFraction >= 0 && atlas.lastCoverageFraction <= 1);
                    coverageSum += atlas.lastCoverageFraction;
                    if (atlas.lastCoverageFraction > 0) availableFrames++;
                    if (atlas.lastCoverageFraction >= .999999) fullFrames++;
                }
                trace.add(
                        i
                                + ","
                                + map.zoom
                                + ","
                                + map.viewportFraction
                                + ","
                                + map.scaleDistance()
                                + ","
                                + map.cx
                                + ","
                                + map.cy
                                + ","
                                + times[i] / 1e6
                                + ","
                                + map.lastBaseNanos / 1e6
                                + ","
                                + map.lastMaskNanos / 1e6
                                + ","
                                + (atlas == null ? 0 : atlas.geometryNanos - geometry) / 1e6
                                + ","
                                + (atlas == null ? 0 : atlas.tiles.uiPreparationNanos - prep) / 1e6
                                + ","
                                + reason
                                + ","
                                + pending
                                + ","
                                + (atlas == null ? 0 : atlas.tiles.visibleHits)
                                + ","
                                + (atlas == null ? 0 : atlas.tiles.visibleMisses)
                                + ","
                                + (atlas == null ? 0 : atlas.geometryRequests)
                                + ","
                                + (atlas == null ? 0 : atlas.geometryLoads)
                                + ","
                                + map.routeFramesRequested
                                + ","
                                + map.routeFramesCompleted
                                + ","
                                + map.routeFramesCancelled
                                + ","
                                + map.routeBuildNanos / 1e6
                                + ","
                                + (atlas == null ? "index-loading" : atlas.renderBackend)
                                + ","
                                + (atlas == null ? 0 : atlas.loadingLayers)
                                + ","
                                + (atlas == null ? 0 : atlas.tiles.workerPreparationNanos) / 1e6
                                + ","
                                + (atlas == null ? 0 : atlas.viewport.requested)
                                + ","
                                + (atlas == null ? 0 : atlas.viewport.completed)
                                + ","
                                + (atlas == null ? 0 : atlas.viewport.buildNanos) / 1e6
                                + ","
                                + (atlas == null ? 0 : atlas.lastCoverageFraction)
                                + ","
                                + (atlas == null ? 0 : atlas.lastColorCoverageFraction));
                // 60 Hz input cadence не зависит от готовности worker; awaitIdle/barrier нет.
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
                long remaining = TimeUnit.MILLISECONDS.toNanos(16) - (System.nanoTime() - start);
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
            }
            map.zoom = 13;
            map.viewportFraction = 1.37f;
            map.cx = MapView.x(lon);
            map.cy = MapView.y(lat);
            long denseStart = System.nanoTime();
            int denseFrames = 0;
            boolean denseRecovered = false;
            while (System.nanoTime() - denseStart < TimeUnit.SECONDS.toNanos(30)) {
                map.draw(canvas);
                denseFrames++;
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
                OfflineAtlas atlas = (OfflineAtlas) get(map, "atlas");
                RouteFrame ready = (RouteFrame) get(map, "readyFrame");
                if (atlas != null
                        && atlas.loadingLayers == 0
                        && atlas.viewport.exactReady()
                        && ready != null
                        && ready.complete
                        && map.nearFrame(ready)
                        && ready.revision == (Long) get(map, "revision")) {
                    denseRecovered = true;
                    break;
                }
                Thread.sleep(10);
            }
            double denseMs = (System.nanoTime() - denseStart) / 1e6;
            map.zoom = 15;
            map.viewportFraction = 1.37f;
            map.cx = MapView.x(lon);
            map.cy = MapView.y(lat);
            long recoveryStart = System.nanoTime();
            int recoveryFrames = 0;
            boolean recovered = false;
            while (System.nanoTime() - recoveryStart < TimeUnit.SECONDS.toNanos(30)) {
                OfflineAtlas before = (OfflineAtlas) get(map, "atlas");
                int oldMisses = before == null ? 0 : before.tiles.visibleMisses;
                int oldHits = before == null ? 0 : before.tiles.visibleHits;
                map.draw(canvas);
                recoveryFrames++;
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
                OfflineAtlas atlas = (OfflineAtlas) get(map, "atlas");
                RouteFrame ready = (RouteFrame) get(map, "readyFrame");
                if (atlas != null
                        && atlas == before
                        && atlas.tiles.visibleHits > oldHits
                        && atlas.tiles.visibleMisses == oldMisses
                        && atlas.loadingLayers == 0
                        && "none".equals(atlas.tileFallback)
                        && ready != null
                        && ready.complete
                        && map.nearFrame(ready)
                        && ready.revision == (Long) get(map, "revision")) {
                    recovered = true;
                    break;
                }
                Thread.sleep(10); // Только восстановление ПОСЛЕ последнего движения.
            }
            String summary =
                    "STRESS cold disk packs, 1080x2340/density3, z15/14/13/12 + fast reverse drags, no worker waits: full ms median/p95/max="
                            + MapMotionRegressionTest.stats(times)
                            + " fallbacks="
                            + reasons
                            + " peakPending="
                            + peakPending
                            + " recoveryMs="
                            + (System.nanoTime() - recoveryStart) / 1e6
                            + " recoveryFrames="
                            + recoveryFrames
                            + " denseRecoveryMs="
                            + denseMs
                            + " denseRecoveryFrames="
                            + denseFrames
                            + " denseRecovered="
                            + denseRecovered
                            + " uiGeometryMs="
                            + uiGeometryNanos / 1e6
                            + " uiCropMs="
                            + uiPreparationNanos / 1e6
                            + " recovered="
                            + recovered
                            + " routeRequested="
                            + map.routeFramesRequested
                            + " routeCompleted="
                            + map.routeFramesCompleted
                            + " availableMovingFrames="
                            + availableFrames
                            + "/"
                            + times.length
                            + " fullMovingFrames="
                            + fullFrames
                            + "/"
                            + times.length
                            + " meanValidCoverage="
                            + coverageSum / times.length
                            + "; native software Canvas, NOT phone FPS/source diagnosis";
            System.out.println(summary);
            File manifest = new File("../AndroidManifest.xml");
            if (!manifest.isFile()) manifest = new File("AndroidManifest.xml");
            String version =
                    new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8)
                            .replaceAll("(?s).*android:versionCode=\"(\\d+)\".*", "$1")
                            .trim();
            File result = new File("build/stress-motion/trace-code" + version + ".csv");
            result.getParentFile().mkdirs();
            Files.write(result.toPath(), trace, StandardCharsets.UTF_8);
            Files.write(
                    new File(result.getParentFile(), "summary-code" + version + ".txt").toPath(),
                    summary.getBytes(StandardCharsets.UTF_8));
            assertTrue("eventual source/mask coverage after gesture", recovered);
            assertTrue("dense 1-2 km viewport and mask recover too", denseRecovered);
            assertEquals(
                    "no UI vector preparation in this phone-sized stress scenario",
                    0,
                    uiGeometryNanos);
            assertEquals("no UI tile crop/query/sort", 0, uiPreparationNanos);
            assertTrue(map.routeFramesRequested > 0);
            assertTrue(
                    "timing without any ready map during input is not a passing stress result",
                    availableFrames > 0);
            assertTrue(peakPending <= AtlasTiles.MAX_PENDING);
            assertEquals(
                    "mask must not reveal an unrelated viewport corner",
                    0,
                    Color.alpha(((RouteFrame) get(map, "readyFrame")).mask.getPixel(0, 0)));
        } finally {
            OfflineAtlas atlas = (OfflineAtlas) get(map, "atlas");
            map.close();
            ((ExecutorService) get(map, "mapWorker")).awaitTermination(30, TimeUnit.SECONDS);
            ((ExecutorService) get(map, "routeWorker")).awaitTermination(30, TimeUnit.SECONDS);
            if (atlas != null) {
                atlas.tiles.awaitIdle();
                atlas.viewport.awaitIdle();
                ((ExecutorService) get(atlas, "loader")).awaitTermination(30, TimeUnit.SECONDS);
            }
            map.getResources().getDisplayMetrics().density = 1;
            deleteFixture(pack);
        }
    }
}

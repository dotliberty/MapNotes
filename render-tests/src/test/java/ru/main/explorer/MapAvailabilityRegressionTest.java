package ru.main.explorer;

import static org.junit.Assert.*;

import android.graphics.*;
import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
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
public class MapAvailabilityRegressionTest {
    void checkOversizedPack(int zoom) throws Exception {
        checkOversizedPack(zoom, 6000 * 384);
    }

    void checkOversizedPack(int zoom, int budget) throws Exception {
        checkOversizedPack(zoom, budget, 512, 512);
    }

    void checkOversizedPack(int zoom, int budget, int width, int height) throws Exception {
        int count = 6000;
        double lat = 0, lon = 0;
        File pack =
                new File(
                        RuntimeEnvironment.getApplication().getFilesDir(),
                        "packs/availability-100-" + UUID.randomUUID());
        JSONArray bounds = new JSONArray(new double[] {-.45, -.45, .45, .45});
        JSONObject data = MapStressMotionTest.roads(count, lat, lon, -1);
        JSONArray building = new JSONArray();
        for (double[] xy : new double[][] {{-3, -3}, {3, -3}, {3, 3}, {-3, 3}, {-3, -3}})
            building.put(
                    new JSONObject()
                            .put("lat", -.017 + xy[1] / 111195.08)
                            .put("lon", -.02 + xy[0] / 111195.08));
        data.getJSONArray("elements")
                .put(
                        new JSONObject()
                                .put("tags", new JSONObject().put("building", "yes"))
                                .put("geometry", building));
        MapStressMotionTest.write(
                new File(pack, "pack.json"),
                new JSONObject().put("bounds", bounds).put("sideKm", 100).put("complete", true),
                false);
        MapStressMotionTest.write(new File(pack, "overview.json.gz"), data, true);
        MapStressMotionTest.write(new File(pack, "cell.json.gz"), data, true);
        MapStressMotionTest.write(
                new File(pack, "cell.meta"),
                new JSONObject().put("bounds", bounds).put("file", "cell.json.gz"),
                false);
        MapView map = new MapView(RuntimeEnvironment.getApplication());
        map.asyncRendering = true;
        map.online = false;
        map.illustrated = false;
        map.follow = false;
        map.layout(0, 0, width, height);
        map.cx = MapView.x(-.02);
        map.cy = MapView.y(-.017);
        map.zoom = zoom;
        OfflineAtlas atlas = new OfflineAtlas(RuntimeEnvironment.getApplication(), true, null);
        // Масштабированный pressure: прежние raw ways помещались, расширенная геометрия — нет.
        android.util.LruCache<?, ?> cache =
                (android.util.LruCache<?, ?>) MapStressMotionTest.get(atlas, "geometry");
        cache.resize(budget);
        ExecutorService loader = (ExecutorService) MapStressMotionTest.get(atlas, "loader");
        try {
            assertEquals(1, atlas.regionCount());
            assertEquals(2, atlas.areas.size());
            Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            if (budget == 1024 * 1024) {
                CountDownLatch release = MapStressMotionTest.block(atlas.viewport, "worker");
                try {
                    atlas.draw(new Canvas(image), map, false);
                    loader.submit(() -> {}).get(15, TimeUnit.SECONDS);
                    atlas.draw(new Canvas(image), map, false);
                    assertTrue(atlas.viewport.requested > 0);
                    assertEquals(0, atlas.viewport.completed);
                    assertEquals(
                            "disk source preparation must not run on UI", 0, atlas.geometryNanos);
                    Map<?, ?> disk = (Map<?, ?>) MapStressMotionTest.get(atlas, "diskGeometry");
                    AtlasDiskSource source =
                            ((OfflineAtlas.IndexedWays) disk.values().iterator().next()).disk;
                    assertEquals(
                            "blocked worker has not read render coordinates",
                            0,
                            source.peakFeatureBytes);
                } finally {
                    release.countDown();
                    atlas.viewport.awaitIdle();
                }
            }
            for (int frame = 0; frame < 8; frame++) {
                image.eraseColor(Color.TRANSPARENT);
                atlas.draw(new Canvas(image), map, false);
                loader.submit(() -> {}).get(15, TimeUnit.SECONDS);
                atlas.tiles.awaitIdle();
                atlas.viewport.awaitIdle();
            }
            System.out.println(
                    "AVAILABILITY 100km pack zoom="
                            + zoom
                            + " decoded="
                            + atlas.geometryLoads
                            + " pending="
                            + atlas.tiles.pending()
                            + " hits="
                            + atlas.tiles.visibleHits
                            + " budget="
                            + cache.maxSize()
                            + " resident="
                            + cache.size());
            assertTrue(
                    "an oversized decoded source must become renderable, not evict/redecode forever",
                    atlas.tiles.visibleHits > 0 || atlas.viewport.completed > 0);
            assertEquals(
                    "identical frames must not decode the cold source again",
                    1,
                    atlas.geometryLoads);
            assertEquals("streaming import must not repeat either", 1, atlas.geometryStreams);
            if (budget == 1024 * 1024) {
                Map<?, ?> disk = (Map<?, ?>) MapStressMotionTest.get(atlas, "diskGeometry");
                assertEquals(1, disk.size());
                OfflineAtlas.IndexedWays stored =
                        (OfflineAtlas.IndexedWays) disk.values().iterator().next();
                assertNotNull(stored.disk);
                assertTrue(stored.disk.compactBytes > budget);
                assertTrue(
                        "streamed render holds one sampled feature, not the source",
                        stored.disk.peakFeatureBytes < 128 * 1024);
                assertTrue(atlas.lastCoverageFraction > .99);
            }
            assertEquals(255, Color.alpha(image.getPixel(width / 2, height / 2)));
            int streets = 0;
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++) {
                    int pixel = image.getPixel(x, y);
                    if (Color.red(pixel) > 210
                            && Color.green(pixel) > 210
                            && Color.blue(pixel) > 200) streets++;
                }
            assertTrue("road geometry survives cache compaction", streets > 10);
            if (width > 512) {
                AtlasViewport.Frame ready =
                        (AtlasViewport.Frame) MapStressMotionTest.get(atlas.viewport, "ready");
                assertNotNull(ready);
                long bitmapBytes =
                        (long) ready.base.getAllocationByteCount()
                                + ready.color.getAllocationByteCount();
                assertTrue(
                        "ready pair retains the existing limit",
                        bitmapBytes <= AtlasViewport.MAX_READY_BYTES);
                assertTrue(
                        "working frame retains the existing admission limit",
                        bitmapBytes + 80000L * 40 <= AtlasViewport.MAX_WORK_BYTES);
                assertTrue("logical viewport remains full-sized", atlas.lastCoverageFraction > .99);
                if (zoom >= 13)
                    assertEquals(255, Color.alpha(image.getPixel(width - 2, height - 2)));
                System.out.println(
                        "AVAILABILITY large viewport="
                                + width
                                + "x"
                                + height
                                + " bitmap bytes="
                                + bitmapBytes
                                + " road pixels="
                                + streets);
                CountDownLatch release = MapStressMotionTest.block(atlas.viewport, "worker");
                int completed = atlas.viewport.completed;
                try {
                    map.cx += 64.0 / (1 << zoom);
                    map.cy += 32.0 / (1 << zoom);
                    Bitmap moved = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    atlas.draw(new Canvas(moved), map, false);
                    assertEquals(completed, atlas.viewport.completed);
                    assertEquals(
                            (width - 64.0) * (height - 32) / (width * (double) height),
                            atlas.lastCoverageFraction,
                            1e-6);
                    int replayedRoads = 0;
                    for (int y = 0; y < height - 32; y++)
                        for (int x = 0; x < width - 64; x++) {
                            int original = image.getPixel(x + 64, y + 32);
                            if (Color.red(original) > 210
                                    && Color.green(original) > 210
                                    && Color.blue(original) > 200) {
                                assertEquals(
                                        "scaled replay keeps road coordinates",
                                        original,
                                        moved.getPixel(x, y));
                                replayedRoads++;
                            }
                        }
                    assertTrue(replayedRoads > 10);
                    assertEquals(0, Color.alpha(moved.getPixel(width - 2, height / 2)));
                } finally {
                    release.countDown();
                    atlas.viewport.awaitIdle();
                }
            }
            assertTrue(cache.size() <= cache.maxSize());
            if (zoom >= 15) {
                int loads = atlas.geometryLoads;
                for (int i = 0; i < 10; i++)
                    assertEquals(
                            "wall data must not be lost or decoded for every origin",
                            1,
                            atlas.buildingsNear(-.017, -.02, 50).size());
                assertEquals(loads, atlas.geometryLoads);
                List<double[]> walls = atlas.buildingsNear(-.017, -.02, 50);
                assertEquals(
                        "the stored raw wall still occludes an inside origin",
                        0,
                        Visibility.polygon(-.017, -.02, 50, walls).length);
                assertTrue(
                        "outside the building the reveal remains nonempty",
                        Visibility.polygon(-.017, -.02 - 20 / 111195.08, 50, walls).length > 0);
            }
            if (budget == 1024 * 1024) {
                Map<?, ?> disk = (Map<?, ?>) MapStressMotionTest.get(atlas, "diskGeometry");
                AtlasDiskSource source =
                        ((OfflineAtlas.IndexedWays) disk.values().iterator().next()).disk;
                try (AtlasDiskSource.Cursor reader = source.open(map.cx, map.cy, 1, 1, zoom)) {
                    atlas.close();
                    assertTrue("an open reader leases its derived file", source.file.exists());
                    try {
                        reader.next(() -> false);
                        fail("a closed source must cancel the old reader");
                    } catch (IOException expected) {
                        // Закрытый атлас не продолжает публикацию старого источника.
                    }
                }
                assertFalse(
                        "close removes the derived file after the last reader",
                        source.file.exists());
                assertTrue(new File(pack, "cell.json.gz").isFile());
                File changed = new File(pack, zoom < 13 ? "overview.json.gz" : "cell.json.gz");
                MapStressMotionTest.write(
                        changed, new JSONObject().put("elements", new JSONArray()), true);
                OfflineAtlas replacement =
                        new OfflineAtlas(RuntimeEnvironment.getApplication(), true, null);
                try {
                    android.util.LruCache<?, ?> replacementCache =
                            (android.util.LruCache<?, ?>)
                                    MapStressMotionTest.get(replacement, "geometry");
                    replacementCache.resize(budget);
                    // Изменённый исходный файл не наследует старые ready pixels/дисковый источник.
                    replacement.reuseGeometry(atlas);
                    OfflineAtlas.Area area =
                            replacement.areas.stream()
                                    .filter(a -> a.source.equals(changed))
                                    .findFirst()
                                    .get();
                    assertTrue(
                            replacement
                                    .loadWays(area, zoom < OfflineAtlas.OVERVIEW_ZOOM)
                                    .isEmpty());
                    assertTrue(
                            ((Map<?, ?>) MapStressMotionTest.get(replacement, "diskGeometry"))
                                    .isEmpty());
                } finally {
                    replacement.close();
                }
            }
        } finally {
            atlas.close();
            atlas.tiles.awaitIdle();
            atlas.viewport.awaitIdle();
            loader.awaitTermination(15, TimeUnit.SECONDS);
            map.close();
            MapStressMotionTest.deleteFixture(pack);
        }
    }

    @Test
    public void largeSavedOverviewRemainsAvailableUnderGeometryPressure() throws Exception {
        checkOversizedPack(12);
    }

    @Test
    public void largeSavedDetailRemainsAvailableAndKeepsWallsUnderGeometryPressure()
            throws Exception {
        checkOversizedPack(15);
    }

    @Test
    public void belowCompactBudgetOverviewBecomesAvailableWithoutReloading() throws Exception {
        checkOversizedPack(12, 1024 * 1024);
    }

    @Test
    public void belowCompactBudgetDetailBecomesAvailableAndKeepsWalls() throws Exception {
        checkOversizedPack(15, 1024 * 1024);
    }

    @Test
    public void oversizedDiskOverviewRemainsVisibleOn1440By2560Viewport() throws Exception {
        checkOversizedPack(12, 1024 * 1024, 1440, 2560);
    }

    @Test
    public void oversizedDiskDetailRemainsVisibleOn1440By2560Viewport() throws Exception {
        checkOversizedPack(15, 1024 * 1024, 1440, 2560);
    }

    @Test
    public void oversizedDiskDetailRemainsVisibleAboveTileSnapshotBudget() throws Exception {
        checkOversizedPack(15, 1024 * 1024, 2560, 3840);
    }

    @Test
    public void legacyMetadataAfterLargeElementsIsStreamedAndSourceRenders() throws Exception {
        File directory = new File(RuntimeEnvironment.getApplication().getFilesDir(), "regions");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File file = new File(directory, "legacy-" + UUID.randomUUID() + ".json");
        JSONObject data = MapStressMotionTest.roads(6000, 0, 0, -1);
        // Metadata идёт после elements. Whitespace больше старого read-limit, без большой строки.
        try (OutputStream out = Files.newOutputStream(file.toPath())) {
            String prefix = data.toString();
            out.write(prefix.substring(0, prefix.length() - 1).getBytes(StandardCharsets.UTF_8));
            byte[] padding = new byte[16384];
            Arrays.fill(padding, (byte) ' ');
            for (int i = 0; i < 1100; i++) out.write(padding);
            out.write(
                    (",\"mapnotes_bounds\":[-0.45,-0.45,0.45,0.45],"
                                    + "\"mapnotes_buildings_only\":false}")
                            .getBytes(StandardCharsets.UTF_8));
        }
        OfflineAtlas atlas = new OfflineAtlas(RuntimeEnvironment.getApplication(), true, null);
        MapView map = new MapView(RuntimeEnvironment.getApplication());
        ExecutorService loader = (ExecutorService) MapStressMotionTest.get(atlas, "loader");
        try {
            assertTrue(file.length() > 16000000);
            assertEquals(
                    "legacy indexing must not read the full file under the old byte limit",
                    1,
                    atlas.regionCount());
            assertEquals(1, atlas.areas.size());
            OfflineAtlas.Area area = atlas.areas.get(0);
            assertTrue(area.legacy);
            assertFalse(area.buildingsOnly);
            assertEquals(-.45, area.south, 0);
            assertEquals(.45, area.east, 0);
            assertNull("metadata indexing must not decode geometry", area.eager);
            assertEquals(0, atlas.geometryStreams);
            ((android.util.LruCache<?, ?>) MapStressMotionTest.get(atlas, "geometry"))
                    .resize(1024 * 1024);
            map.asyncRendering = true;
            map.illustrated = false;
            map.layout(0, 0, 512, 512);
            map.cx = MapView.x(-.02);
            map.cy = MapView.y(-.017);
            map.zoom = 15;
            Bitmap image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
            for (int i = 0; i < 5; i++) {
                image.eraseColor(Color.TRANSPARENT);
                atlas.draw(new Canvas(image), map, false);
                loader.submit(() -> {}).get(15, TimeUnit.SECONDS);
                atlas.viewport.awaitIdle();
            }
            assertEquals(1, atlas.geometryStreams);
            assertEquals(1, atlas.geometryLoads);
            int roads = 0;
            for (int y = 0; y < 512; y++)
                for (int x = 0; x < 512; x++) if (image.getPixel(x, y) == 0xfff5f5ee) roads++;
            assertTrue(
                    "legacy metadata must lead to real roads, not only a background", roads > 10);
            assertTrue(atlas.lastCoverageFraction > .99);
            System.out.println(
                    "AVAILABILITY streamed legacy bytes="
                            + file.length()
                            + " road pixels="
                            + roads);
        } finally {
            atlas.close();
            atlas.viewport.awaitIdle();
            atlas.tiles.awaitIdle();
            loader.awaitTermination(15, TimeUnit.SECONDS);
            map.close();
            MapStressMotionTest.deleteFixture(file);
        }
    }

    @Test
    public void streamedLegacyMetadataKeepsVisibilityFlagsAndRejectsRemark() throws Exception {
        File files = RuntimeEnvironment.getApplication().getFilesDir();
        File region = new File(files, "regions/metadata-region.json");
        File walls = new File(files, "visibility/metadata-walls.json");
        File remark = new File(files, "regions/metadata-remark.json");
        File missing = new File(files, "visibility/metadata-missing.json");
        try {
            MapStressMotionTest.write(
                    region,
                    new JSONObject()
                            .put("mapnotes_bounds", new JSONArray(new double[] {-1, -1, 0, 0}))
                            .put("elements", new JSONArray()),
                    false);
            MapStressMotionTest.write(
                    walls,
                    new JSONObject()
                            .put(
                                    "elements",
                                    new JSONArray()
                                            .put(
                                                    new JSONObject()
                                                            .put(
                                                                    "unknown",
                                                                    new JSONArray()
                                                                            .put("ignored"))))
                            .put("mapnotes_bounds", new JSONArray(new double[] {1, 1, 2, 2}))
                            .put("mapnotes_buildings_only", "true"),
                    false);
            MapStressMotionTest.write(
                    remark,
                    new JSONObject()
                            .put("mapnotes_bounds", new JSONArray(new double[] {3, 3, 4, 4}))
                            .put("remark", JSONObject.NULL),
                    false);
            MapStressMotionTest.write(
                    missing, new JSONObject().put("elements", new JSONArray()), false);
            OfflineAtlas atlas = new OfflineAtlas(RuntimeEnvironment.getApplication(), true, null);
            try {
                assertEquals(1, atlas.regionCount());
                assertEquals(2, atlas.areas.size());
                for (OfflineAtlas.Area area : atlas.areas) {
                    assertTrue(area.legacy);
                    assertEquals(area.source.equals(walls), area.buildingsOnly);
                    assertTrue(area.source.equals(walls) || area.source.equals(region));
                    assertNull(area.eager);
                }
                assertEquals(0, atlas.geometryStreams);
            } finally {
                atlas.close();
            }
        } finally {
            for (File file : new File[] {region, walls, remark, missing})
                MapStressMotionTest.deleteFixture(file);
        }
    }

    @Test
    public void neighbouringFilesAndWallRequestsStayResidentWithinUnchangedBudget()
            throws Exception {
        checkNeighbours(3 * 6000 * 384);
    }

    @Test
    public void threeOversizedNeighboursAndWallQueriesDoNotReloadAtOneMiB() throws Exception {
        checkNeighbours(1024 * 1024);
    }

    void checkNeighbours(int budget) throws Exception {
        File pack =
                new File(
                        RuntimeEnvironment.getApplication().getFilesDir(),
                        "packs/neighbours-100-" + UUID.randomUUID());
        JSONArray bounds = new JSONArray(new double[] {-.45, -.45, .45, .45});
        MapStressMotionTest.write(
                new File(pack, "pack.json"),
                new JSONObject().put("bounds", bounds).put("sideKm", 100).put("complete", true),
                false);
        for (int i = 0; i < 3; i++) {
            double lon = (i - 1) * .04 + .02;
            String file = "cell-" + i + ".json.gz";
            MapStressMotionTest.write(
                    new File(pack, file), MapStressMotionTest.roads(6000, 0, lon, -1), true);
            double west = i == 0 ? -.45 : i == 1 ? -.025 : .025;
            double east = i == 0 ? -.025 : i == 1 ? .025 : .45;
            MapStressMotionTest.write(
                    new File(pack, i + ".meta"),
                    new JSONObject()
                            .put("bounds", new JSONArray(new double[] {-.45, west, .45, east}))
                            .put("file", file),
                    false);
        }
        MapView map = new MapView(RuntimeEnvironment.getApplication());
        map.asyncRendering = true;
        map.online = false;
        map.illustrated = false;
        map.layout(0, 0, 1080, 512);
        map.cx = 128;
        map.cy = MapView.y(-.017);
        map.zoom = 14;
        OfflineAtlas atlas = new OfflineAtlas(RuntimeEnvironment.getApplication(), true, null);
        android.util.LruCache<?, ?> cache =
                (android.util.LruCache<?, ?>) MapStressMotionTest.get(atlas, "geometry");
        cache.resize(budget);
        ExecutorService loader = (ExecutorService) MapStressMotionTest.get(atlas, "loader");
        int stableLoads = -1;
        try {
            Bitmap image = Bitmap.createBitmap(1080, 512, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(image);
            for (int frame = 0; frame < 12; frame++) {
                map.cx = 128 + (frame & 1) * .25 / (1 << map.zoom);
                image.eraseColor(Color.TRANSPARENT);
                atlas.draw(canvas, map, false);
                loader.submit(() -> {}).get(15, TimeUnit.SECONDS);
                atlas.tiles.awaitIdle();
                atlas.viewport.awaitIdle();
                assertTrue(atlas.buildingsNear(-.017, 0, 50).isEmpty());
                assertEquals(budget, cache.maxSize());
                assertTrue(cache.size() <= budget);
                if (frame == 3) stableLoads = atlas.geometryLoads;
            }
            assertEquals(
                    "neighbour display and mask queries must not evict/redecode each other forever",
                    stableLoads,
                    atlas.geometryLoads);
            assertTrue(atlas.tiles.visibleHits > 0 || atlas.viewport.completed > 0);
            assertTrue("all three neighbours cover the viewport", atlas.lastCoverageFraction > .99);
            for (int[] strip : new int[][] {{0, 240}, {300, 780}, {840, 1080}}) {
                int roads = 0;
                for (int y = 0; y < image.getHeight(); y++)
                    for (int x = strip[0]; x < strip[1]; x++) {
                        int pixel = image.getPixel(x, y);
                        if (Color.red(pixel) > 210
                                && Color.green(pixel) > 210
                                && Color.blue(pixel) > 200) roads++;
                    }
                assertTrue("native road pixels from neighbour at x=" + strip[0], roads > 10);
            }
            System.out.println(
                    "AVAILABILITY neighbours=3 wall lookups=12 decoded="
                            + atlas.geometryLoads
                            + " resident="
                            + cache.size()
                            + " budget="
                            + budget);
        } finally {
            atlas.close();
            atlas.tiles.awaitIdle();
            atlas.viewport.awaitIdle();
            loader.awaitTermination(15, TimeUnit.SECONDS);
            map.close();
            MapStressMotionTest.deleteFixture(pack);
        }
    }

    @Test
    public void diskStreamKeepsNativePassOrderLabelsAndLongFeatureStride() throws Exception {
        JSONObject json = MapStressMotionTest.roads(6000, 0, 0, -1);
        JSONArray coordinates = new JSONArray();
        for (int i = 0; i < 100001; i++) {
            double angle = i * Math.PI * 2 / 100000;
            coordinates.put(
                    new JSONObject()
                            .put("lat", -.017 + Math.sin(angle) * .001)
                            .put("lon", -.02 + Math.cos(angle) * .001));
        }
        json.getJSONArray("elements")
                .put(
                        new JSONObject()
                                .put("geometry", coordinates)
                                .put(
                                        "tags",
                                        new JSONObject()
                                                .put("highway", "primary")
                                                .put("name", "Long road")));
        json.getJSONArray("elements")
                .put(
                        new JSONObject()
                                .put(
                                        "geometry",
                                        new JSONArray()
                                                .put(
                                                        new JSONObject()
                                                                .put("lat", -.0175)
                                                                .put("lon", -.0205))
                                                .put(
                                                        new JSONObject()
                                                                .put("lat", -.0175)
                                                                .put("lon", -.0195))
                                                .put(
                                                        new JSONObject()
                                                                .put("lat", -.0165)
                                                                .put("lon", -.0195))
                                                .put(
                                                        new JSONObject()
                                                                .put("lat", -.0175)
                                                                .put("lon", -.0205)))
                                .put("tags", new JSONObject().put("natural", "water")));
        File file =
                new File(RuntimeEnvironment.getApplication().getFilesDir(), "disk-stride.json.gz");
        MapStressMotionTest.write(file, json, true);
        try {
            for (boolean overview : new boolean[] {false, true}) {
                OfflineAtlas.IndexedWays memory =
                        (OfflineAtlas.IndexedWays) OfflineAtlas.decode(json, overview);
                try (AtlasDiskSource disk =
                        AtlasDiskSource.build(
                                file,
                                RuntimeEnvironment.getApplication().getCacheDir(),
                                overview,
                                false,
                                () -> false)) {
                    AtlasTiles.Layer actual =
                            new AtlasTiles.Layer(
                                    127, 129, 127, 129, false, Collections.emptyList());
                    actual.disk = disk;
                    AtlasTiles.Layer expected =
                            new AtlasTiles.Layer(127, 129, 127, 129, false, memory.tileFeatures);
                    for (int zoom : new int[] {12, 15, 18})
                        for (boolean illustrated : new boolean[] {false, true}) {
                            AtlasTiles.Snapshot a =
                                    new AtlasTiles.Snapshot(
                                            "disk",
                                            zoom,
                                            1,
                                            illustrated,
                                            false,
                                            Collections.singletonList(actual));
                            AtlasTiles.Snapshot e =
                                    new AtlasTiles.Snapshot(
                                            "memory",
                                            zoom,
                                            1,
                                            illustrated,
                                            false,
                                            Collections.singletonList(expected));
                            AtlasViewport.Frame af =
                                    AtlasViewport.render(
                                            a,
                                            new AtlasViewport.Request(
                                                    a, MapView.x(-.02), MapView.y(-.017), 512, 512),
                                            () -> false);
                            AtlasViewport.Frame ef =
                                    AtlasViewport.render(
                                            e,
                                            new AtlasViewport.Request(
                                                    e, MapView.x(-.02), MapView.y(-.017), 512, 512),
                                            () -> false);
                            assertNotNull(af);
                            assertEquals(ef.vertices, af.vertices);
                            assertEquals(ef.labels, af.labels);
                            assertTrue(
                                    "disk base native pixels z=" + zoom, ef.base.sameAs(af.base));
                            assertTrue(
                                    "disk color native pixels z=" + zoom,
                                    ef.color.sameAs(af.color));
                        }
                    assertTrue(
                            "even a 100001-point feature is sampled within one bounded buffer",
                            disk.peakFeatureBytes < 128 * 1024);
                }
            }
        } finally {
            MapStressMotionTest.deleteFixture(file);
        }
    }

    @Test
    public void cancelledDiskImportRemovesOnlyItsDerivedPartialFiles() throws Exception {
        File directory =
                new File(
                        RuntimeEnvironment.getApplication().getCacheDir(),
                        "cancel-atlas-" + UUID.randomUUID());
        assertTrue(directory.mkdir());
        File source =
                new File(
                        directory.getParentFile(),
                        "cancel-source-" + UUID.randomUUID() + ".json.gz");
        MapStressMotionTest.write(source, MapStressMotionTest.roads(2000, 0, 0, -1), true);
        java.util.concurrent.atomic.AtomicInteger checks =
                new java.util.concurrent.atomic.AtomicInteger();
        try {
            try {
                AtlasDiskSource.build(
                        source, directory, false, false, () -> checks.incrementAndGet() > 1000);
                fail("import must observe cancellation");
            } catch (java.io.InterruptedIOException expected) {
                assertEquals(0, directory.list().length);
                assertTrue(source.isFile());
            }
        } finally {
            MapStressMotionTest.deleteFixture(source);
            MapStressMotionTest.deleteFixture(directory);
        }
    }

    @Test
    public void independentLineKeepsSessionBreaksAndCoalescesLatestPoints() throws Exception {
        ArrayList<double[]> broken =
                new ArrayList<>(
                        Arrays.asList(
                                new double[] {0, -.0001, 1, 0}, new double[] {0, .0001, 2, 1000}));
        RouteFrame line = new RouteFrame(128, 128, 18, 512, 512, 1, broken, null, null);
        line.raster = true;
        line.buildLineOnly();
        assertTrue(line.complete);
        assertEquals(0, line.vertices);
        assertNull(line.mask);
        assertEquals(0, Color.alpha(line.lineImage.getPixel(256, 256)));
        RouteLines queue = new RouteLines(null);
        CountDownLatch release = MapStressMotionTest.block(queue, "worker");
        try {
            queue.request(
                    128,
                    128,
                    18,
                    512,
                    512,
                    1,
                    1,
                    new ArrayList<>(
                            Arrays.asList(
                                    new double[] {0, -.0001, 1, 0},
                                    new double[] {0, .0001, 1, 1000})));
            queue.request(
                    128,
                    128,
                    18,
                    512,
                    512,
                    1,
                    2,
                    new ArrayList<>(
                            Arrays.asList(
                                    new double[] {0, .0003, 1, 0},
                                    new double[] {0, .0005, 1, 1000})));
            release.countDown();
            queue.awaitIdle();
            assertEquals(1, queue.completed);
            assertNull(queue.ready(1));
            RouteFrame ready = queue.ready(2);
            assertNotNull(ready);
            assertNull(ready.mask);
            assertEquals(0, Color.alpha(ready.lineImage.getPixel(256, 256)));
            assertEquals(0xffb09a69, ready.lineImage.getPixel(330, 256));
            queue.close();
            assertNull(queue.ready(2));
        } finally {
            release.countDown();
            queue.close();
            queue.awaitIdle();
        }
    }

    @Test
    public void compactedCompatibilityGeometryKeepsExactRoadAndLabelPixels() throws Exception {
        MapMotionRegressionTest fixture = new MapMotionRegressionTest();
        fixture.setup();
        try {
            OfflineAtlas.IndexedWays original = fixture.roads(6000, false);
            for (int i = 0; i < 40; i++) original.get(i).name = "Street " + i;
            original.index();
            OfflineAtlas.IndexedWays compact = original.compact();
            assertTrue(compact.compacted);
            assertEquals(original.tileFeatures.size(), compact.tileFeatures.size());
            assertTrue(compact.estimatedBytes < original.estimatedBytes);
            for (int zoom : new int[] {12, 15, 18})
                for (boolean illustrated : new boolean[] {false, true})
                    for (boolean hybrid : new boolean[] {false, true}) {
                        fixture.map.zoom = zoom;
                        fixture.map.illustrated = illustrated;
                        Bitmap expected = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
                        Bitmap actual = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
                        fixture.atlas.areas.get(0).eager = original;
                        fixture.atlas.drawGeometry(new Canvas(expected), fixture.map, hybrid);
                        int vertices = fixture.atlas.lastVertices;
                        fixture.atlas.areas.get(0).eager = compact;
                        fixture.atlas.drawGeometry(new Canvas(actual), fixture.map, hybrid);
                        assertEquals(vertices, fixture.atlas.lastVertices);
                        assertTrue(
                                "compacted source keeps native pixels z=" + zoom,
                                expected.sameAs(actual));
                    }
        } finally {
            fixture.cleanup();
        }
    }

    @Test
    public void replacingAtlasCancelsBlockedOldRevealWithoutSuppressingIndependentLine()
            throws Exception {
        MapMotionRegressionTest fixture = new MapMotionRegressionTest();
        fixture.setup();
        BlockingWallsAtlas old = new BlockingWallsAtlas();
        fixture.map.replaceAtlas(old);
        fixture.atlas = old;
        fixture.map.illustrated = true;
        fixture.map.update(
                new ArrayList<>(
                        Arrays.asList(
                                new double[] {0, -.0001, 1, 0}, new double[] {0, .0001, 1, 1000})));
        try {
            fixture.map.draw(new Canvas(Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)));
            assertTrue(old.entered.await(5, TimeUnit.SECONDS));
            OfflineAtlas next = new OfflineAtlas(RuntimeEnvironment.getApplication(), true, null);
            next.areas.clear();
            fixture.map.replaceAtlas(next);
            fixture.atlas = next;
            old.release.countDown();
            ((ExecutorService) MapStressMotionTest.get(fixture.map, "routeWorker"))
                    .submit(() -> {})
                    .get(10, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0, fixture.map.routeFramesCompleted);
            assertTrue(fixture.map.routeFramesCancelled > 0);
            assertNull(
                    "closed old source cannot publish a newly built reveal",
                    MapStressMotionTest.get(fixture.map, "readyFrame"));
        } finally {
            old.release.countDown();
            fixture.cleanup();
        }
    }

    static final class BlockingWallsAtlas extends OfflineAtlas {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

        BlockingWallsAtlas() {
            super(RuntimeEnvironment.getApplication(), true, null);
            areas.clear();
        }

        @Override
        public List<double[]> buildingsNear(double lat, double lon, double radius) {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Collections.emptyList();
        }
    }

    @Test
    public void routeLineAppearsWhileWallLookupForRevealIsBlocked() throws Exception {
        MapMotionRegressionTest fixture = new MapMotionRegressionTest();
        fixture.setup();
        BlockingWallsAtlas walls = new BlockingWallsAtlas();
        fixture.map.replaceAtlas(walls);
        fixture.atlas = walls;
        fixture.map.illustrated = true;
        fixture.map.update(
                new ArrayList<>(
                        Arrays.asList(
                                new double[] {0, -.0001, 1, 0}, new double[] {0, .0001, 1, 1000})));
        Bitmap image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        try {
            fixture.map.draw(new Canvas(image));
            assertTrue(walls.entered.await(5, TimeUnit.SECONDS));
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean drawn = false;
            while (System.nanoTime() < end) {
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
                fixture.map.draw(new Canvas(image));
                if (image.getPixel(256, 256) == 0xffb09a69) {
                    drawn = true;
                    break;
                }
                Thread.sleep(10);
            }
            assertTrue("accepted route line must not wait for building decode/reveal IO", drawn);
            assertEquals("mask worker is still blocked", 0, fixture.map.routeFramesCompleted);
            assertNull(
                    "line publication must not publish an unvalidated reveal mask",
                    MapStressMotionTest.get(fixture.map, "readyFrame"));
        } finally {
            walls.release.countDown();
            fixture.cleanup();
        }
    }
}

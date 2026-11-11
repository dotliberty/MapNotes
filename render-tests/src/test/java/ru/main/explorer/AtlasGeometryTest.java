package ru.main.explorer;

import android.graphics.*;
import java.io.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AtlasGeometryTest {
    private MapView map;
    private OfflineAtlas atlas;
    private LegacyOfflineAtlas legacy;

    @Before
    public void setup() {
        map = new MapView(RuntimeEnvironment.getApplication());
        map.online = false;
        map.layout(0, 0, 512, 512);
        map.cx = map.cy = 128;
        map.zoom = 17;
        atlas = new OfflineAtlas(RuntimeEnvironment.getApplication());
        legacy = new LegacyOfflineAtlas(RuntimeEnvironment.getApplication());
        atlas.areas.clear();
        legacy.areas.clear();
    }

    @After
    public void cleanup() {
        map.close();
        atlas.close();
        legacy.close();
    }

    private JSONObject scene(int count, boolean offscreen) throws Exception {
        JSONArray elements = new JSONArray();
        for (int i = 0; i < count; i++) {
            double lat = offscreen ? .1 : (i % 100 - 50) * .00001;
            double lon = offscreen ? .1 : (i / 100 % 100 - 50) * .00001;
            boolean area = i % 7 == 0;
            JSONObject tags = new JSONObject().put("name", "Street " + i);
            if (area) {
                if (i % 3 == 0) tags.put("building", "yes");
                else if (i % 3 == 1) tags.put("natural", "water");
                else tags.put("landuse", "park");
            } else tags.put("highway", i % 4 == 0 ? "footway" : "residential");
            JSONArray geometry =
                    new JSONArray()
                            .put(new JSONObject().put("lat", lat).put("lon", lon))
                            .put(
                                    new JSONObject()
                                            .put("lat", lat + .00006)
                                            .put("lon", lon + .00004));
            if (area)
                geometry.put(new JSONObject().put("lat", lat).put("lon", lon + .00004))
                        .put(new JSONObject().put("lat", lat).put("lon", lon));
            elements.put(new JSONObject().put("tags", tags).put("geometry", geometry));
        }
        // A long crossing road and a containing polygon must not disappear when endpoints are
        // offscreen.
        elements.put(
                new JSONObject()
                        .put("tags", new JSONObject().put("highway", "primary"))
                        .put(
                                "geometry",
                                new JSONArray()
                                        .put(new JSONObject().put("lat", 0).put("lon", -.1))
                                        .put(new JSONObject().put("lat", 0).put("lon", .1))));
        return new JSONObject().put("elements", elements);
    }

    private void add(JSONObject root) throws Exception {
        OfflineAtlas.Area a = new OfflineAtlas.Area();
        a.source = new File("atlas-geometry-test-" + atlas.areas.size());
        a.south = a.west = -1;
        a.north = a.east = 1;
        a.legacy = true;
        a.eager = OfflineAtlas.decode(root);
        atlas.areas.add(a);
        LegacyOfflineAtlas.Area b = new LegacyOfflineAtlas.Area();
        b.source = a.source;
        b.south = b.west = -1;
        b.north = b.east = 1;
        b.legacy = true;
        b.eager = LegacyOfflineAtlas.decode(root);
        legacy.areas.add(b);
    }

    private Bitmap draw(boolean hybrid) {
        Bitmap b = Bitmap.createBitmap(map.getWidth(), map.getHeight(), Bitmap.Config.ARGB_8888);
        atlas.draw(new Canvas(b), map, hybrid);
        return b;
    }

    private void compare(boolean hybrid, String name) throws Exception {
        Bitmap actual = draw(hybrid);
        Bitmap expected =
                Bitmap.createBitmap(map.getWidth(), map.getHeight(), Bitmap.Config.ARGB_8888);
        legacy.draw(new Canvas(expected), map, hybrid);
        int[] a = new int[map.getWidth() * map.getHeight()], b = new int[a.length];
        actual.getPixels(a, 0, map.getWidth(), 0, 0, map.getWidth(), map.getHeight());
        expected.getPixels(b, 0, map.getWidth(), 0, 0, map.getWidth(), map.getHeight());
        assertArrayEquals(name, b, a);
        assertEquals(legacy.lastVertices, atlas.lastVertices);
        assertEquals(legacy.lastLabels, atlas.lastLabels);
        File dir = new File("build/atlas-geometry-images");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + "-before.png"))) {
            expected.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + "-after.png"))) {
            actual.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        actual.recycle();
        expected.recycle();
    }

    @Test
    public void nativePixelsMatchFrozenRendererAcrossZoomPanStylesAndLayers() throws Exception {
        add(scene(1200, false));
        for (int zoom : new int[] {14, 16, 17, 19}) {
            map.zoom = zoom;
            map.cx = 128 + .0000137;
            map.cy = 128 - .0000039;
            for (boolean illustrated : new boolean[] {false, true}) {
                map.illustrated = illustrated;
                for (boolean hybrid : new boolean[] {false, true})
                    compare(hybrid, "z" + zoom + "-i" + illustrated + "-h" + hybrid);
            }
        }
    }

    @Test
    public void repeatedAndRevealDrawsReuseIdenticalPaths() throws Exception {
        add(scene(1000, false));
        draw(false).recycle();
        int prepared = atlas.preparedFrames;
        AtlasGeometry.Frame frame = atlas.frames.get(atlas.areas.get(0));
        assertNotNull(frame);
        Path path = frame.shapes.get(0).path;
        draw(true).recycle();
        draw(false).recycle();
        assertEquals(prepared, atlas.preparedFrames);
        assertSame(frame, atlas.frames.get(atlas.areas.get(0)));
        assertSame(path, frame.shapes.get(0).path);
        assertTrue(frame.labelsReady);
        compare(true, "warm-reveal");
        compare(false, "warm-muted");
    }

    @Test
    public void nativeRevealCircleAndPolygonWithFractionalScaleMatch() throws Exception {
        add(scene(1200, false));
        for (boolean polygon : new boolean[] {false, true}) {
            Bitmap actual = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
            Bitmap expected = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
            Path mask = new Path();
            if (polygon) {
                mask.moveTo(51, 81);
                mask.lineTo(442, 151);
                mask.lineTo(300, 471);
                mask.lineTo(102, 390);
                mask.close();
            } else mask.addCircle(256, 256, 181, Path.Direction.CW);
            Canvas a = new Canvas(actual), b = new Canvas(expected);
            a.scale(1.23f, 1.23f, 256, 256);
            b.scale(1.23f, 1.23f, 256, 256);
            atlas.draw(a, map, false);
            legacy.draw(b, map, false);
            a.clipPath(mask);
            b.clipPath(mask);
            atlas.draw(a, map, true);
            legacy.draw(b, map, true);
            int[] aa = new int[512 * 512], bb = new int[aa.length];
            actual.getPixels(aa, 0, 512, 0, 0, 512, 512);
            expected.getPixels(bb, 0, 512, 0, 0, 512, 512);
            assertArrayEquals("Reveal polygon=" + polygon, bb, aa);
            actual.recycle();
            expected.recycle();
        }
    }

    @Test
    public void highLatitudeAndAntimeridianNativePixelsRemainExact() throws Exception {
        JSONObject root = scene(1200, false);
        JSONArray elements = root.getJSONArray("elements");
        for (int i = 0; i < elements.length(); i++) {
            JSONArray points = elements.getJSONObject(i).getJSONArray("geometry");
            for (int j = 0; j < points.length(); j++) {
                JSONObject point = points.getJSONObject(j);
                point.put("lat", point.getDouble("lat") + 70);
                point.put("lon", point.getDouble("lon") + 179.999);
            }
        }
        add(root);
        atlas.areas.get(0).south = legacy.areas.get(0).south = 69;
        atlas.areas.get(0).north = legacy.areas.get(0).north = 71;
        atlas.areas.get(0).west = legacy.areas.get(0).west = 179;
        atlas.areas.get(0).east = legacy.areas.get(0).east = 180;
        map.cx = MapView.x(179.9997);
        map.cy = MapView.y(70.0001);
        compare(false, "high-latitude-seam");
        compare(true, "high-latitude-seam-reveal");
    }

    @Test
    public void reloadReusesProjectionOnlyForUnchangedSourceStamp() throws Exception {
        add(scene(100, false));
        OfflineAtlas previous = atlas;
        OfflineAtlas next = new OfflineAtlas(RuntimeEnvironment.getApplication());
        next.areas.clear();
        OfflineAtlas.Area a = previous.areas.get(0);
        OfflineAtlas.Area b = new OfflineAtlas.Area();
        b.source = a.source;
        b.stamp = a.stamp;
        b.south = a.south;
        b.north = a.north;
        b.west = a.west;
        b.east = a.east;
        b.legacy = true;
        next.areas.add(b);
        java.lang.reflect.Field field = OfflineAtlas.class.getDeclaredField("geometry");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        android.util.LruCache<String, ArrayList<OfflineAtlas.Way>> cache =
                (android.util.LruCache<String, ArrayList<OfflineAtlas.Way>>) field.get(previous);
        cache.put(a.source.getPath(), a.eager);
        try {
            next.reuseGeometry(previous);
            assertSame(a.eager, next.ways(b));
            assertSame(
                    ((OfflineAtlas.IndexedWays) a.eager).renderGeometry,
                    ((OfflineAtlas.IndexedWays) next.ways(b)).renderGeometry);
            @SuppressWarnings("unchecked")
            android.util.LruCache<String, ArrayList<OfflineAtlas.Way>> nextCache =
                    (android.util.LruCache<String, ArrayList<OfflineAtlas.Way>>) field.get(next);
            nextCache.evictAll();
            b.stamp++;
            next.reuseGeometry(previous);
            assertNull(nextCache.get(b.source.getPath()));
            previous.close();
            assertEquals(0, previous.frames.size());
        } finally {
            next.close();
        }
    }

    @Test
    public void viewportAndDataChangesInvalidateWithoutMutatingSharedProjection() throws Exception {
        add(scene(100, false));
        OfflineAtlas.Area area = atlas.areas.get(0);
        AtlasGeometry projected = ((OfflineAtlas.IndexedWays) area.eager).renderGeometry;
        draw(false).recycle();
        int n = atlas.preparedFrames;
        map.cx += .00001;
        draw(false).recycle();
        assertEquals(++n, atlas.preparedFrames);
        map.cy += .00001;
        draw(false).recycle();
        assertEquals(++n, atlas.preparedFrames);
        map.zoom++;
        draw(false).recycle();
        assertEquals(++n, atlas.preparedFrames);
        map.layout(0, 0, 513, 511);
        draw(false).recycle();
        assertEquals(++n, atlas.preparedFrames);
        area.stamp++;
        draw(false).recycle();
        assertEquals(++n, atlas.preparedFrames);
        assertSame(projected, atlas.frames.get(area).geometry);
        area.eager = OfflineAtlas.decode(scene(101, false));
        draw(false).recycle();
        assertEquals(++n, atlas.preparedFrames);
        assertNotSame(projected, atlas.frames.get(area).geometry);
        legacy.areas.get(0).eager = LegacyOfflineAtlas.decode(scene(101, false));
        compare(false, "data-invalidation");
    }

    @Test
    public void candidatesContainEveryBruteForceVisibleBoundsIncludingWrapAndEdges()
            throws Exception {
        Random random = new Random(43);
        OfflineAtlas.IndexedWays ways = new OfflineAtlas.IndexedWays();
        for (int i = 0; i < 5000; i++) {
            OfflineAtlas.Way w = new OfflineAtlas.Way();
            w.type = "road";
            w.name = "";
            w.west = random.nextDouble() * 360 - 180;
            w.east = Math.min(180, w.west + random.nextDouble() * 30);
            w.south = random.nextDouble() * 160 - 80;
            w.north = Math.min(85, w.south + random.nextDouble() * 20);
            w.coords = new double[] {w.south, w.west, w.north, w.east};
            ways.add(w);
        }
        ways.index();
        for (int z : new int[] {8, 12, 17, 22}) {
            map.zoom = z;
            for (double longitude : new double[] {-179.9999, 0, 179.9999}) {
                map.cx = MapView.x(longitude);
                Set<OfflineAtlas.Way> actual = new HashSet<>();
                for (AtlasGeometry.Entry e : ways.renderGeometry.candidates(map)) actual.add(e.way);
                for (OfflineAtlas.Way w : ways) {
                    float l = map.screenX(w.west), r = map.screenX(w.east);
                    float t = atlas.sy(w.north, map, 1 << map.zoom);
                    float b = atlas.sy(w.south, map, 1 << map.zoom);
                    if (!(r < 0 || l > map.getWidth() || b < 0 || t > map.getHeight()))
                        assertTrue("Missing candidate at z=" + z, actual.contains(w));
                }
            }
        }
    }

    @Test
    public void denseOffscreenSelectionAndCacheEvictionAreBounded() throws Exception {
        add(scene(10000, true));
        draw(false).recycle();
        AtlasGeometry.Frame frame = atlas.frames.get(atlas.areas.get(0));
        assertNotNull(frame);
        assertEquals(1, frame.shapes.size());
        int prepared = atlas.preparedFrames;
        draw(false).recycle();
        assertEquals(prepared, atlas.preparedFrames);
        assertTrue(atlas.frames.size() <= OfflineAtlas.FRAME_BYTES);
        atlas.frames.resize(1);
        assertEquals(0, atlas.frames.size());
        draw(false).recycle();
        assertEquals(0, atlas.frames.size());
        atlas.close();
        assertEquals(0, atlas.frames.size());
        int closed = atlas.preparedFrames;
        draw(false).recycle();
        assertEquals(closed, atlas.preparedFrames);
    }

    @Test
    public void synchronousLoadFinishingAfterCloseCannotRepopulateCache() throws Exception {
        File source = File.createTempFile("atlas-close-race", ".json");
        try {
            try (FileWriter writer = new FileWriter(source)) {
                writer.write(scene(100, false).toString());
            }
            OfflineAtlas.Area area = new OfflineAtlas.Area();
            area.source = source;
            java.lang.reflect.Field field = OfflineAtlas.class.getDeclaredField("geometry");
            field.setAccessible(true);
            android.util.LruCache<?, ?> cache = (android.util.LruCache<?, ?>) field.get(atlas);
            Thread worker = new Thread(() -> atlas.loadWays(area));
            synchronized (cache) {
                worker.start();
                long deadline = System.nanoTime() + 5_000_000_000L;
                while (worker.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline)
                    Thread.yield();
                assertEquals(Thread.State.BLOCKED, worker.getState());
                atlas.close();
            }
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertEquals(0, cache.size());
        } finally {
            source.delete();
        }
    }

    @Test
    public void denseMultipleAreasRetainGlobalVertexAdmissionAndOrder() throws Exception {
        add(scene(21000, false));
        add(scene(21000, false));
        compare(false, "dense-multiple-areas");
        compare(true, "dense-multiple-areas-reveal");
        assertTrue(atlas.lastVertices <= 80000);
    }

    @Test
    public void longSimplifiedPolylineRetainsOriginalStrideAndClosure() throws Exception {
        JSONArray points = new JSONArray();
        for (int i = 0; i < 10000; i++) {
            double angle = i * Math.PI * 2 / 9999;
            points.put(
                    new JSONObject()
                            .put("lat", Math.sin(angle) * .001)
                            .put("lon", Math.cos(angle) * .001));
        }
        points.put(new JSONObject().put("lat", 0).put("lon", .001));
        add(
                new JSONObject()
                        .put(
                                "elements",
                                new JSONArray()
                                        .put(
                                                new JSONObject()
                                                        .put(
                                                                "tags",
                                                                new JSONObject()
                                                                        .put("natural", "water"))
                                                        .put("geometry", points))));
        compare(false, "long-closed-stride");
        compare(true, "long-closed-stride-reveal");
    }

    @Test
    public void changedEarlierAreaInvalidatesLaterLabelLayoutWithoutDiscardingPaths()
            throws Exception {
        add(scene(1200, false));
        add(scene(1200, false));
        draw(false).recycle();
        AtlasGeometry.Frame later = atlas.frames.get(atlas.areas.get(1));
        assertNotNull(later);
        JSONObject changed = scene(1200, false);
        JSONArray elements = changed.getJSONArray("elements");
        for (int i = 0; i < elements.length(); i++)
            elements.getJSONObject(i).getJSONObject("tags").remove("name");
        atlas.areas.get(0).eager = OfflineAtlas.decode(changed);
        legacy.areas.get(0).eager = LegacyOfflineAtlas.decode(changed);
        compare(false, "earlier-area-label-invalidation");
        assertSame(later, atlas.frames.get(atlas.areas.get(1)));
        compare(true, "earlier-area-label-invalidation-reveal");
    }
}

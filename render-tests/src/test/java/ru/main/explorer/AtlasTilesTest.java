package ru.main.explorer;

import static org.junit.Assert.*;

import android.graphics.*;
import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AtlasTilesTest {
    MapView map;
    OfflineAtlas atlas;

    @Before
    public void setup() throws Exception {
        map = new MapView(RuntimeEnvironment.getApplication());
        map.online = false;
        map.illustrated = false;
        map.asyncRendering = true;
        map.layout(0, 0, 512, 512);
        map.cx = map.cy = 128;
        map.zoom = 17;
        atlas = new OfflineAtlas(RuntimeEnvironment.getApplication(), true, null);
        atlas.areas.clear();
        fixture(10000);
    }

    private void fixture(int count) throws Exception {
        JSONArray elements = new JSONArray();
        for (int i = 0; i < count; i++) {
            double lat = (i % 100 - 50) * .00001;
            double lon = (i / 100 % 100 - 50) * .00001;
            elements.put(
                    new JSONObject()
                            .put(
                                    "tags",
                                    new JSONObject()
                                            .put("highway", "residential")
                                            .put("name", i == 0 ? "First street" : ""))
                            .put(
                                    "geometry",
                                    new JSONArray()
                                            .put(new JSONObject().put("lat", lat).put("lon", lon))
                                            .put(
                                                    new JSONObject()
                                                            .put("lat", lat + .00006)
                                                            .put("lon", lon + .00004))));
        }
        OfflineAtlas.Area area = new OfflineAtlas.Area();
        area.source = new File("tile-native-fixture");
        area.south = area.west = -1;
        area.north = area.east = 1;
        area.legacy = true;
        area.eager = OfflineAtlas.decode(new JSONObject().put("elements", elements));
        atlas.areas.clear();
        atlas.areas.add(area);
    }

    @After
    public void cleanup() throws Exception {
        atlas.close();
        atlas.tiles.awaitIdle();
        map.close();
    }

    private Bitmap image(boolean hybrid) {
        Bitmap bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        atlas.draw(new Canvas(bitmap), map, hybrid);
        return bitmap;
    }

    private void warm(boolean hybrid) throws Exception {
        // Bounded queue may need multiple redraws; never assume one callback covers a viewport.
        for (int i = 0; i < 5; i++) {
            image(hybrid);
            atlas.tiles.awaitIdle();
        }
    }

    @Test
    public void actualProductionPanReusesVisibleAndReadyNeighbourTiles() throws Exception {
        warm(false);
        int complete = atlas.tiles.completed;
        int hits = atlas.tiles.visibleHits;
        int misses = atlas.tiles.visibleMisses;
        map.cx += 128.25 / (1 << map.zoom);
        map.cy += 1.75 / (1 << map.zoom);
        Bitmap bitmap = image(false);
        assertTrue(atlas.tiles.visibleHits > hits);
        assertEquals("prefetched neighbour coverage", misses, atlas.tiles.visibleMisses);
        for (int x = 1; x < 511; x++) assertEquals(255, Color.alpha(bitmap.getPixel(x, 200)));
        assertTrue(complete > 4);
        assertTrue(atlas.tiles.pending() <= AtlasTiles.MAX_PENDING);
        assertTrue(atlas.tiles.bytes() <= AtlasTiles.MAX_BYTES);
    }

    @Test
    public void stylesSourcesBoundsZoomAndDensityInvalidateButColorPassDoesNot() throws Exception {
        warm(false);
        String key = atlas.tileSnapshot(map, false).key;
        warm(true);
        int done = atlas.tiles.completed;
        image(false);
        assertEquals(done, atlas.tiles.completed);
        assertEquals(key, atlas.tileSnapshot(map, true).key);
        atlas.areas.get(0).stamp++;
        assertNotEquals(key, atlas.tileSnapshot(map, false).key);
        warm(false);
        key = atlas.tileSnapshot(map, false).key;
        map.illustrated = !map.illustrated;
        assertNotEquals(key, atlas.tileSnapshot(map, false).key);
        key = atlas.tileSnapshot(map, false).key;
        map.zoom++;
        assertNotEquals(key, atlas.tileSnapshot(map, false).key);
        key = atlas.tileSnapshot(map, false).key;
        map.getResources().getDisplayMetrics().density = 2;
        assertNotEquals(key, atlas.tileSnapshot(map, false).key);
        map.getResources().getDisplayMetrics().density = 1;
        key = atlas.tileSnapshot(map, false).key;
        fixture(10);
        assertNotEquals(key, atlas.tileSnapshot(map, false).key);
        atlas.areas.clear();
        assertTrue(atlas.tileSnapshot(map, false).layers.isEmpty());
    }

    @Test
    public void snapshotHasNoMutableWayOrViewAndSurvivesSourceMutation() throws Exception {
        AtlasTiles.Snapshot snapshot = atlas.tileSnapshot(map, false);
        AtlasTiles.Feature feature = snapshot.layers.get(0).features.get(0);
        double first = feature.xy[0];
        String name = feature.name;
        OfflineAtlas.Way original = atlas.areas.get(0).eager.get(0);
        original.coords[1] = 100;
        original.name = "mutated";
        assertEquals(first, feature.xy[0], 0);
        assertEquals(name, feature.name);
        for (Class<?> type :
                new Class<?>[] {
                    AtlasTiles.Snapshot.class, AtlasTiles.Layer.class, AtlasTiles.Feature.class
                })
            for (Field field : type.getDeclaredFields()) {
                assertFalse(android.view.View.class.isAssignableFrom(field.getType()));
                assertNotEquals(OfflineAtlas.Way.class, field.getType());
            }
    }

    @Test
    public void adjacentGuardBandsMatchAndFractionalPanHasNoSeamHoles() throws Exception {
        AtlasTiles.Snapshot snapshot = atlas.tileSnapshot(map, false);
        long x = (long) (128L * (1 << map.zoom) / 256);
        Bitmap left = AtlasTiles.render(snapshot, x - 1, x);
        Bitmap right = AtlasTiles.render(snapshot, x, x);
        for (int y = 8; y < 264; y++)
            for (int i = 0; i < 16; i++) {
                int a = left.getPixel(256 + i, y), b = right.getPixel(i, y);
                assertEquals(Color.alpha(a), Color.alpha(b));
                // Independent local float projection can differ by one coverage level.
                assertTrue(Math.abs(Color.red(a) - Color.red(b)) <= 2);
                assertTrue(Math.abs(Color.green(a) - Color.green(b)) <= 2);
                assertTrue(Math.abs(Color.blue(a) - Color.blue(b)) <= 2);
            }
        warm(false);
        for (double pan : new double[] {.25, .5, .75, 1.25, 255.75}) {
            map.cx = 128 + pan / (1 << map.zoom);
            map.cy = 128 + .75 / (1 << map.zoom);
            Bitmap image = image(false);
            for (int yy = 1; yy < 511; yy += 17)
                for (int xx = 1; xx < 511; xx++) {
                    assertEquals("no transparent seam", 255, Color.alpha(image.getPixel(xx, yy)));
                }
        }
    }

    @Test
    public void oversizedSceneIsExplicitVectorFallbackAndQueueCloseIsBounded() throws Exception {
        fixture(40001);
        assertNull(atlas.tileSnapshot(map, false));
        image(false);
        assertEquals(0, atlas.tiles.completed);
        fixture(10000);
        AtlasTiles.Snapshot snapshot = atlas.tileSnapshot(map, false);
        atlas.tiles.scene(snapshot.key);
        synchronized (atlas.tiles) {
            for (int i = 0; i < 1000; i++) atlas.tiles.request(snapshot, i, i);
            assertEquals(AtlasTiles.MAX_PENDING, atlas.tiles.pending());
            atlas.tiles.scene("replacement");
        }
        atlas.tiles.awaitIdle();
        assertTrue(atlas.tiles.cancelled > 0);
        atlas.tiles.close();
        atlas.tiles.awaitIdle();
        assertEquals(0, atlas.tiles.bytes());
        assertNull(atlas.tiles.request(snapshot, 0, 0));
    }

    @Test
    public void actualDrawFrameMaskKeepsColdPartialAndWarmHybridInsideReveal() throws Exception {
        Field af = MapView.class.getDeclaredField("atlas");
        af.setAccessible(true);
        OfflineAtlas old = (OfflineAtlas) af.get(map);
        if (old != null) old.close();
        af.set(map, atlas);
        RouteFrame frame =
                new RouteFrame(
                        map.cx, map.cy, map.zoom, 512, 512, 1, new ArrayList<>(), null, atlas);
        frame.raster = true;
        frame.build();
        frame.mask.eraseColor(Color.TRANSPARENT);
        Paint paint = new Paint(3);
        paint.setColor(Color.WHITE);
        new Canvas(frame.mask).drawCircle(256, 256, 90, paint);
        Field ready = MapView.class.getDeclaredField("readyFrame");
        ready.setAccessible(true);
        ready.set(map, frame);
        for (int pass = 0; pass < 3; pass++) {
            Bitmap output = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
            map.drawFrameMask(new Canvas(output));
            assertEquals(0, Color.alpha(output.getPixel(0, 0)));
            assertEquals(0, Color.alpha(output.getPixel(400, 256)));
            assertEquals(0, Color.alpha(output.getPixel(256, 400)));
            warm(true);
            map.cx += .25 / (1 << map.zoom);
        }
    }

    @Test
    public void matchedMovingBeforeAfterColdHitAndMissBenchmark() throws Exception {
        Canvas canvas = new Canvas(Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888));
        long start = System.nanoTime();
        image(false);
        long coldUi = System.nanoTime() - start;
        atlas.tiles.awaitIdle();
        long coldReady = System.nanoTime() - start;
        warm(false);
        long[] before = new long[30], after = new long[30], miss = new long[30];
        int hits = atlas.tiles.visibleHits, misses = atlas.tiles.visibleMisses;
        for (int i = -5; i < 30; i++) {
            map.cx = 128 + (i + 5) * 3.25 / (1 << map.zoom);
            map.cy = 128 + (i + 5) * 1.75 / (1 << map.zoom);
            for (int order = 0; order < 2; order++) {
                boolean tiled = ((i + order) & 1) == 0;
                atlas.frames.evictAll();
                start = System.nanoTime();
                if (tiled) atlas.draw(canvas, map, false);
                else atlas.drawGeometry(canvas, map, false);
                if (i >= 0) (tiled ? after : before)[i] = System.nanoTime() - start;
            }
            atlas.tiles.awaitIdle();
        }
        int movingHits = atlas.tiles.visibleHits - hits;
        int movingMisses = atlas.tiles.visibleMisses - misses;
        for (int i = 0; i < 30; i++) {
            map.cx = 128 + (i + 1) * 1024.25 / (1 << map.zoom);
            start = System.nanoTime();
            atlas.draw(canvas, map, false);
            miss[i] = System.nanoTime() - start;
            atlas.tiles.awaitIdle();
        }
        Arrays.sort(before);
        Arrays.sort(after);
        Arrays.sort(miss);
        assertTrue(movingHits > 0);
        assertEquals(0, movingMisses);
        System.out.println(
                "CODE48 nativeCanvas moving 10k roads + label, decoded warm; ms median/p95/max"
                        + " before="
                        + stats(before)
                        + " hit="
                        + stats(after)
                        + " missUI="
                        + stats(miss)
                        + " coldUI="
                        + coldUi / 1e6
                        + " coldReady="
                        + coldReady / 1e6
                        + " visibleMovingHits="
                        + movingHits
                        + " visibleMovingMisses="
                        + movingMisses
                        + " bytes="
                        + atlas.tiles.bytes()
                        + " pending="
                        + atlas.tiles.pending()
                        + " backend=async-world-tiles; NOT phone FPS or decode benchmark");
    }

    @Test
    public void wrappedWorldEdgesAndPhoneViewportUseBoundedTilePath() throws Exception {
        atlas.areas.clear();
        for (int side : new int[] {-1, 1}) {
            double lon = side * 179.999;
            JSONObject way =
                    new JSONObject()
                            .put("tags", new JSONObject().put("highway", "primary"))
                            .put(
                                    "geometry",
                                    new JSONArray()
                                            .put(new JSONObject().put("lat", -.001).put("lon", lon))
                                            .put(
                                                    new JSONObject()
                                                            .put("lat", .001)
                                                            .put("lon", lon)));
            OfflineAtlas.Area area = new OfflineAtlas.Area();
            area.source = new File("wrap-tile-" + side);
            area.south = -.1;
            area.north = .1;
            area.west = side < 0 ? -180 : 179.9;
            area.east = side < 0 ? -179.9 : 180;
            area.legacy = true;
            area.eager =
                    OfflineAtlas.decode(new JSONObject().put("elements", new JSONArray().put(way)));
            atlas.areas.add(area);
        }
        map.cx = 0;
        map.zoom = 16;
        warm(false);
        Bitmap output = image(false);
        assertEquals(255, Color.alpha(output.getPixel(200, 256)));
        assertEquals(255, Color.alpha(output.getPixel(300, 256)));
        map.cx = 256 - .25 / (1 << map.zoom);
        warm(false);
        output = image(false);
        assertEquals(255, Color.alpha(output.getPixel(200, 256)));
        assertEquals(255, Color.alpha(output.getPixel(300, 256)));
        map.layout(0, 0, 1080, 2340);
        assertNotNull("normal phone height is not rejected", atlas.tileSnapshot(map, false));
        map.layout(0, 0, 8000, 8000);
        assertNull(
                "oversized two-pass viewport uses bounded fallback",
                atlas.tileSnapshot(map, false));
    }

    @Test
    public void mixedGeometryRetainsStylesAcrossColorPasses() throws Exception {
        JSONArray elements = new JSONArray();
        for (int i = 0; i < 4; i++) {
            JSONObject tags = new JSONObject();
            if (i == 0) tags.put("landuse", "park");
            else if (i == 1) tags.put("natural", "water");
            else if (i == 2) tags.put("building", "yes");
            else tags.put("highway", "residential");
            double lat = -.001 + i * .0004;
            JSONArray coords =
                    new JSONArray()
                            .put(new JSONObject().put("lat", lat).put("lon", -.001))
                            .put(new JSONObject().put("lat", lat + .0005).put("lon", -.001))
                            .put(new JSONObject().put("lat", lat + .0005).put("lon", .001))
                            .put(new JSONObject().put("lat", lat).put("lon", .001));
            if (i != 3) coords.put(new JSONObject().put("lat", lat).put("lon", -.001));
            elements.put(new JSONObject().put("tags", tags).put("geometry", coords));
        }
        atlas.areas.get(0).eager = OfflineAtlas.decode(new JSONObject().put("elements", elements));
        for (boolean illustrated : new boolean[] {false, true}) {
            map.illustrated = illustrated;
            for (boolean hybrid : new boolean[] {false, true}) {
                warm(hybrid);
                Bitmap actual = image(hybrid);
                Bitmap expected = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
                atlas.drawGeometry(new Canvas(expected), map, hybrid);
                int[] a = new int[512 * 512], b = new int[a.length];
                actual.getPixels(a, 0, 512, 0, 0, 512, 512);
                expected.getPixels(b, 0, 512, 0, 0, 512, 512);
                int max = 0;
                for (int i = 0; i < a.length; i++) {
                    max = Math.max(max, Math.abs(Color.alpha(a[i]) - Color.alpha(b[i])));
                    max = Math.max(max, Math.abs(Color.red(a[i]) - Color.red(b[i])));
                    max = Math.max(max, Math.abs(Color.green(a[i]) - Color.green(b[i])));
                    max = Math.max(max, Math.abs(Color.blue(a[i]) - Color.blue(b[i])));
                }
                assertTrue("mixed geometry style difference=" + max, max <= 3);
            }
        }
    }

    private String stats(long[] values) {
        return values[15] / 1e6 + "/" + values[28] / 1e6 + "/" + values[29] / 1e6;
    }

    @Test
    public void tileCompletionDoesNotSignalVisibilityGeometryChange() throws Exception {
        atlas.close();
        atlas.tiles.awaitIdle();
        java.util.concurrent.atomic.AtomicInteger data =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger repaint =
                new java.util.concurrent.atomic.AtomicInteger();
        atlas =
                new OfflineAtlas(
                        RuntimeEnvironment.getApplication(),
                        true,
                        data::incrementAndGet,
                        repaint::incrementAndGet);
        fixture(1000);
        warm(false);
        assertTrue(repaint.get() > 0);
        assertEquals("bitmap completion must not rebuild the exploration mask", 0, data.get());
    }
}

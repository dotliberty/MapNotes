package ru.main.explorer;

import static org.junit.Assert.*;

import android.graphics.*;

import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import java.io.File;
import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AtlasRasterTest {
    private MapView map;
    private OfflineAtlas atlas;

    @Before
    public void setup() throws Exception {
        map = new MapView(RuntimeEnvironment.getApplication());
        map.online = false;
        map.layout(0, 0, 512, 512);
        map.cx = map.cy = 128;
        map.zoom = 17;
        map.asyncRendering = true;
        atlas = new OfflineAtlas(RuntimeEnvironment.getApplication());
        atlas.areas.clear();
        JSONArray elements = new JSONArray();
        for (int i = 0; i < 10000; i++) {
            double lat = (i % 100 - 50) * .00001;
            double lon = (i / 100 - 50) * .00001;
            JSONArray points =
                    new JSONArray()
                            .put(new JSONObject().put("lat", lat).put("lon", lon))
                            .put(
                                    new JSONObject()
                                            .put("lat", lat + .00006)
                                            .put("lon", lon + .00004));
            elements.put(
                    new JSONObject()
                            .put("tags", new JSONObject().put("highway", "residential"))
                            .put("geometry", points));
        }
        OfflineAtlas.Area area = new OfflineAtlas.Area();
        area.source = new File("raster-synthetic");
        area.south = area.west = -1;
        area.north = area.east = 1;
        area.legacy = true;
        area.eager = OfflineAtlas.decode(new JSONObject().put("elements", elements));
        atlas.areas.add(area);
    }

    @After
    public void cleanup() {
        atlas.close();
        map.close();
    }

    private Bitmap image(boolean cached, boolean hybrid) {
        Bitmap result = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        if (cached) atlas.draw(canvas, map, hybrid);
        else atlas.drawGeometry(canvas, map, hybrid);
        return result;
    }

    private void same(Bitmap a, Bitmap b) {
        int[] first = new int[512 * 512];
        int[] second = new int[first.length];
        a.getPixels(first, 0, 512, 0, 0, 512, 512);
        b.getPixels(second, 0, 512, 0, 0, 512, 512);
        assertArrayEquals(first, second);
    }

    @Test
    public void exactReplayAndViewportStyleInvalidation() {
        for (boolean hybrid : new boolean[] {false, true}) {
            same(image(false, hybrid), image(true, hybrid));
            same(image(false, hybrid), image(true, hybrid));
            int hits = atlas.raster.hits;
            same(image(false, hybrid), image(true, hybrid));
            assertEquals(hits + 1, atlas.raster.hits);
            map.cx += .0002;
            hits = atlas.raster.hits;
            same(image(false, hybrid), image(true, hybrid));
            assertEquals(hits, atlas.raster.hits);
            map.illustrated = !map.illustrated;
            same(image(false, hybrid), image(true, hybrid));
        }
        assertTrue(atlas.raster.bytes() <= AtlasRasterCache.MAX_BYTES);
        atlas.close();
        assertEquals(0, atlas.raster.bytes());
    }

    @Test
    public void complexClipsAndPinchKeepVectorRendering() {
        image(true, false);
        int hits = atlas.raster.hits;
        Canvas canvas = new Canvas(Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888));
        Path clip = new Path();
        clip.addCircle(256, 256, 100, Path.Direction.CW);
        canvas.clipPath(clip);
        map.asyncRendering = false;
        atlas.draw(canvas, map, false);
        assertEquals(hits, atlas.raster.hits);
        map.asyncRendering = true;
        canvas = new Canvas(Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888));
        canvas.scale(1.23f, 1.23f);
        atlas.draw(canvas, map, false);
        assertEquals(hits, atlas.raster.hits);
    }

    @Test
    public void memoryBudgetRejectsHugeImages() {
        assertNull(atlas.raster.allocate(100000, 100000));
        assertNull(atlas.raster.allocate(0, 512));
        assertEquals(0, atlas.raster.bytes());
    }

    @Test
    public void warmAndMovingViewportBenchmark() {
        Canvas canvas = new Canvas(Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888));
        for (int i = 0; i < 5; i++) atlas.draw(canvas, map, false);
        long[] vector = new long[15];
        long[] raster = new long[15];
        long[] moving = new long[15];
        for (int i = 0; i < 15; i++) {
            long start = System.nanoTime();
            atlas.drawGeometry(canvas, map, false);
            vector[i] = System.nanoTime() - start;
            start = System.nanoTime();
            atlas.draw(canvas, map, false);
            raster[i] = System.nanoTime() - start;
        }
        for (int i = 0; i < 15; i++) {
            map.cx += .00001;
            long start = System.nanoTime();
            atlas.draw(canvas, map, false);
            moving[i] = System.nanoTime() - start;
        }
        Arrays.sort(vector);
        Arrays.sort(raster);
        Arrays.sort(moving);
        System.out.println(
                "CODE44 10k roads median ms: vector="
                        + vector[7] / 1e6
                        + " rasterHit="
                        + raster[7] / 1e6
                        + " movingMiss="
                        + moving[7] / 1e6);
    }

    @Test
    public void identicalMovingTrajectoryMeasuresFallbackWithoutClaimingTileHits() {
        Canvas canvas = new Canvas(Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888));
        long[] direct = new long[30];
        long[] fallback = new long[30];
        int hits = atlas.raster.hits;
        // Both paths see exactly the same fractional camera positions and all 10000 roads.
        // Alternate measurement order, forcing path preparation for BOTH calls independently.
        // This is a warm decoded scene, NOT cold file decoding or full MapView frame timing.
        for (int i = -5; i < 30; i++) {
            double x = 128 + (i + 5) * 3.25 / (1 << map.zoom);
            double y = 128 + (i + 5) * 1.75 / (1 << map.zoom);
            for (int order = 0; order < 2; order++) {
                boolean cached = ((i + order) & 1) == 0;
                map.cx = x;
                map.cy = y;
                atlas.frames.evictAll();
                long start = System.nanoTime();
                if (cached) atlas.draw(canvas, map, false);
                else atlas.drawGeometry(canvas, map, false);
                long elapsed = System.nanoTime() - start;
                if (i >= 0) {
                    if (cached) fallback[i] = elapsed;
                    else direct[i] = elapsed;
                }
            }
        }
        assertEquals("Continuous pan must not be described as cache hits", hits, atlas.raster.hits);
        assertEquals(0, atlas.raster.bytes());
        Arrays.sort(direct);
        Arrays.sort(fallback);
        System.out.println(
                "CODE47 SAME moving trajectory 10k roads, 5 warmup/30 samples: direct"
                        + " median/p95/max="
                        + direct[15] / 1e6
                        + "/"
                        + direct[28] / 1e6
                        + "/"
                        + direct[29] / 1e6
                        + " ms; unchanged fallback median/p95/max="
                        + fallback[15] / 1e6
                        + "/"
                        + fallback[28] / 1e6
                        + "/"
                        + fallback[29] / 1e6
                        + " ms; movingHits=0; coldDecode=NOT_MEASURED; tiles=NOT_IMPLEMENTED");
    }

    @Test
    public void sourceRevisionDensityAndSnapshotReplacementInvalidateStationaryRaster()
            throws Exception {
        image(true, false);
        image(true, false);
        OfflineAtlas.Area area = atlas.areas.get(0);
        int hits = atlas.raster.hits;
        area.stamp++;
        same(image(false, false), image(true, false));
        assertEquals(hits, atlas.raster.hits);
        area.eager = OfflineAtlas.decode(new JSONObject().put("elements", new JSONArray()));
        same(image(false, false), image(true, false));
        assertEquals(hits, atlas.raster.hits);
        image(true, false);
        float density = map.getResources().getDisplayMetrics().density;
        try {
            map.getResources().getDisplayMetrics().density = density * 1.5f;
            same(image(false, false), image(true, false));
            assertEquals(hits, atlas.raster.hits);
        } finally {
            map.getResources().getDisplayMetrics().density = density;
        }
        atlas.areas.clear();
        Bitmap absent = image(true, false);
        assertEquals(Color.TRANSPARENT, absent.getPixel(256, 256));
        atlas.areas.add(area);
        same(image(false, false), image(true, false));
        assertEquals(hits, atlas.raster.hits);
    }

    @Test
    public void cachedHybridCompositesThroughTransparentRevealAndCloseStaysEmpty() {
        image(true, true);
        image(true, true);
        Bitmap direct = reveal(false);
        Bitmap replay = reveal(true);
        same(direct, replay);
        assertEquals(Color.TRANSPARENT, replay.getPixel(0, 0));
        assertEquals(Color.TRANSPARENT, replay.getPixel(511, 511));
        assertNotEquals(Color.TRANSPARENT, replay.getPixel(256, 256));
        atlas.close();
        assertEquals(0, atlas.raster.bytes());
        assertEquals(0, atlas.frames.size());
        Bitmap closed = image(true, true);
        assertEquals(Color.TRANSPARENT, closed.getPixel(256, 256));
        assertEquals(0, atlas.raster.bytes());
    }

    private Bitmap reveal(boolean cached) {
        Bitmap result = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        int layer = canvas.saveLayer(0, 0, 512, 512, null);
        if (cached) atlas.draw(canvas, map, true);
        else atlas.drawGeometry(canvas, map, true);
        Bitmap mask = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        new Canvas(mask).drawCircle(256, 256, 90, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        canvas.drawBitmap(mask, 0, 0, paint);
        canvas.restoreToCount(layer);
        return result;
    }
}

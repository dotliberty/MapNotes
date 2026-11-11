package ru.main.explorer;

import static org.junit.Assert.*;

import android.graphics.*;
import android.view.MotionEvent;
import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import org.json.JSONArray;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MapMotionRegressionTest {
    static final class ControlAtlas extends OfflineAtlas {
        boolean vectorControl;

        ControlAtlas() {
            super(RuntimeEnvironment.getApplication(), true, null);
        }

        @Override
        public void draw(Canvas canvas, MapView map, boolean hybrid) {
            if (vectorControl) drawGeometry(canvas, map, hybrid);
            else super.draw(canvas, map, hybrid);
        }
    }

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
        map.zoom = 18;
        map.follow = false;
        atlas = new ControlAtlas();
        atlas.areas.clear();
        addArea(-1, 1, roads(1, false));
        Field field = MapView.class.getDeclaredField("atlas");
        field.setAccessible(true);
        OfflineAtlas old = (OfflineAtlas) field.get(map);
        if (old != null) old.close();
        field.set(map, atlas);
    }

    @After
    public void cleanup() throws Exception {
        map.close();
        atlas.tiles.awaitIdle();
    }

    static double lon(double x) {
        return x / 256 * 360 - 180;
    }

    static double lat(double y) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - y / 128))));
    }

    OfflineAtlas.IndexedWays roads(int count, boolean offscreen) {
        OfflineAtlas.IndexedWays ways = new OfflineAtlas.IndexedWays();
        for (int i = 0; i < count; i++) {
            double x = 128 + (offscreen && i > 0 ? 10000 : i % 100 - 50) / (double) (1 << map.zoom);
            double y = 128 + (i / 100 % 100 - 50) / (double) (1 << map.zoom);
            OfflineAtlas.Way way = new OfflineAtlas.Way();
            way.type = "road";
            way.kind = "residential";
            way.name = "";
            way.coords = new double[] {lat(y), lon(x), lat(y + .00001), lon(x + .00001)};
            way.south = Math.min(way.coords[0], way.coords[2]);
            way.north = Math.max(way.coords[0], way.coords[2]);
            way.west = way.coords[1];
            way.east = way.coords[3];
            ways.add(way);
        }
        ways.index();
        return ways;
    }

    OfflineAtlas.Area addArea(double west, double east, OfflineAtlas.IndexedWays ways) {
        OfflineAtlas.Area area = new OfflineAtlas.Area();
        area.source = new File("motion-synthetic-" + atlas.areas.size());
        area.south = -1;
        area.north = 1;
        area.west = west;
        area.east = east;
        area.legacy = true;
        area.eager = ways;
        atlas.areas.add(area);
        return area;
    }

    Bitmap frame() {
        Bitmap result =
                Bitmap.createBitmap(map.getWidth(), map.getHeight(), Bitmap.Config.ARGB_8888);
        map.draw(new Canvas(result));
        return result;
    }

    void warm() throws Exception {
        for (int i = 0; i < 40; i++) {
            int misses = atlas.tiles.visibleMisses;
            frame();
            atlas.tiles.awaitIdle();
            if (misses == atlas.tiles.visibleMisses) return;
        }
        fail("viewport did not become warm within bounded redraws");
    }

    @Test
    public void nativeWorldEdgeMovesQuarterPixelAtZoom17Through22() throws Exception {
        double cx = 150.123456, cy = 100.234567;
        double legacyMaxError = 0, localMaxError = 0;
        for (int z = 17; z <= 22; z++) {
            double scale = 1 << z;
            JSONArray ring = new JSONArray();
            for (double[] point :
                    new double[][] {{-16, -40}, {200, -40}, {200, 40}, {-16, 40}, {-16, -40}})
                ring.put(
                        new JSONArray()
                                .put(lon(cx + point[0] / scale))
                                .put(lat(cy + point[1] / scale)));
            WorldLand land = new WorldLand(new JSONArray().put(ring));
            double previous = edge(land, cx, cy, z, false);
            double oldPrevious = edge(land, cx, cy, z, true);
            for (int i = 1; i <= 16; i++) {
                double center = cx + i * .25 / scale;
                double next = edge(land, center, cy, z, false);
                double oldNext = edge(land, center, cy, z, true);
                localMaxError = Math.max(localMaxError, Math.abs(next - previous - .25));
                legacyMaxError = Math.max(legacyMaxError, Math.abs(oldNext - oldPrevious - .25));
                assertEquals("native edge zoom=" + z + " step=" + i, .25, next - previous, .025);
                previous = next;
                oldPrevious = oldNext;
            }
        }
        assertTrue("legacy Canvas reproduces quantized motion", legacyMaxError > .2);
        System.out.println(
                "MOTION world quarter-pixel max error local="
                        + localMaxError
                        + " legacy="
                        + legacyMaxError);
    }

    double edge(WorldLand land, double cx, double cy, int zoom, boolean legacy) {
        Bitmap image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        Paint paint = new Paint(3);
        paint.setColor(Color.WHITE);
        if (legacy) {
            Path path = new Path();
            for (double[] ring : land.rings) {
                for (int i = 0; i < ring.length; i += 2)
                    if (i == 0) path.moveTo((float) ring[i], (float) ring[i + 1]);
                    else path.lineTo((float) ring[i], (float) ring[i + 1]);
                path.close();
            }
            double scale = 1 << zoom;
            canvas.translate((float) (256 - cx * scale), (float) (256 - cy * scale));
            canvas.scale((float) scale, (float) scale);
            canvas.drawPath(path, paint);
        } else canvas.drawPath(land.project(cx, cy, zoom, 512, 512), paint);
        double coverage = 0;
        for (int x = 0; x < 300; x++) coverage += Color.alpha(image.getPixel(x, 256)) / 255.0;
        return coverage;
    }

    @Test
    public void actualDrawAfterPinchAndTouchInterruptionKeepsTilePathAndScale() throws Exception {
        map.displayZoom = map.zoom;
        CameraPinchTest events = new CameraPinchTest();
        events.event(map, MotionEvent.ACTION_DOWN, 1, 200, 10);
        events.event(map, MotionEvent.ACTION_POINTER_DOWN | 256, 2, 200, 30);
        events.event(map, MotionEvent.ACTION_MOVE, 2, 300, 70);
        events.event(map, MotionEvent.ACTION_MOVE, 2, 450, 110);
        events.event(map, MotionEvent.ACTION_CANCEL, 2, 450, 130);
        assertTrue(map.viewportFraction > 1);
        float fraction = map.viewportFraction;
        double zoom = map.displayZoom;
        warm();
        int prepared = atlas.preparedFrames,
                hits = atlas.tiles.visibleHits,
                draws = atlas.tileDraws;
        events.event(map, MotionEvent.ACTION_DOWN, 1, 200, 150);
        events.event(map, MotionEvent.ACTION_MOVE, 1, 210, 170);
        events.event(map, MotionEvent.ACTION_UP, 1, 210, 190);
        frame();
        assertEquals(fraction, map.viewportFraction, 0);
        assertEquals(zoom, map.displayZoom, 1e-7);
        assertEquals(prepared, atlas.preparedFrames);
        assertTrue(atlas.tileDraws > draws);
        assertTrue(atlas.tiles.visibleHits > hits);
        map.stopViewport();
        assertEquals(fraction, map.viewportFraction, 0);
    }

    @Test
    public void actualAnimatedHighZoomFrameIsContinuousWhenInterruptedByTouch() throws Exception {
        org.robolectric.android.controller.ActivityController<android.app.Activity> controller =
                Robolectric.buildActivity(android.app.Activity.class).setup();
        android.widget.FrameLayout root = new android.widget.FrameLayout(controller.get());
        controller.get().setContentView(root);
        root.addView(map, new android.widget.FrameLayout.LayoutParams(512, 512));
        map.layout(0, 0, 512, 512);
        MotionTest.durationScale(1);
        try {
            for (int zoom = 17; zoom < 22; zoom++) {
                map.zoom = zoom;
                map.viewportFraction = 1;
                map.moveViewport(map.cx + .25 / (1 << zoom), map.cy, zoom + 1);
                assertNotNull(map.viewportMotion);
                map.viewportMotion.setCurrentPlayTime(180);
                double x = map.cx, y = map.cy;
                float fraction = map.viewportFraction;
                synchronized (atlas.tiles) {
                    Bitmap before = frame();
                    MotionEvent down =
                            MotionEvent.obtain(1000, 1000, MotionEvent.ACTION_DOWN, 20, 20, 0);
                    map.onTouchEvent(down);
                    down.recycle();
                    assertNull(map.viewportMotion);
                    assertEquals(x, map.cx, 0);
                    assertEquals(y, map.cy, 0);
                    assertEquals(fraction, map.viewportFraction, 0);
                    assertTrue(
                            "native frame does not jump on touch zoom=" + zoom,
                            before.sameAs(frame()));
                }
            }
        } finally {
            root.removeView(map);
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void enteringNeighbourDoesNotInvalidateUnaffectedVisibleTiles() throws Exception {
        double scale = 1 << map.zoom;
        addArea(lon(128 + 512.125 / scale), lon(128 + 900 / scale), roads(1, false));
        warm();
        String old = atlas.tileSnapshot(map, false).key;
        int hits = atlas.tiles.visibleHits;
        map.cx += .25 / scale;
        synchronized (atlas.tiles) {
            frame();
            assertNotEquals("neighbour enters selection", old, atlas.tileSnapshot(map, false).key);
            assertTrue(
                    "unaffected visible tiles survive, including their cache hits",
                    atlas.tiles.visibleHits - hits >= 4);
        }
    }

    @Test
    public void denseOffscreenSourceUsesTilesAndVisibleSaturationKeepsBudgetFallback()
            throws Exception {
        atlas.areas.get(0).eager = roads(40001, true);
        assertNotNull(atlas.tileSnapshot(map, false));
        frame();
        assertEquals("none", atlas.tileFallback);
        assertEquals(0, atlas.preparedFrames);
        atlas.tiles.awaitIdle();
        atlas.areas.get(0).eager = roads(40001, false);
        assertNull(atlas.tileSnapshot(map, false));
        assertEquals("visible-vertex-budget", atlas.tileFallback);
        frame();
        assertTrue(atlas.lastVertices <= 80000);
    }

    @Test
    public void wholeZoomTransitionUsesReadyCoverageAndRejectsChangedSourceFallback()
            throws Exception {
        warm();
        int misses = atlas.tiles.visibleMisses;
        map.zoom++;
        synchronized (atlas.tiles) {
            Bitmap image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
            atlas.draw(new Canvas(image), map, false);
            assertTrue(atlas.tiles.visibleMisses > misses);
            assertEquals(
                    "old zoom remains visible while worker waits",
                    255,
                    Color.alpha(image.getPixel(260, 260)));
            atlas.areas.get(0).stamp++;
            image.eraseColor(Color.TRANSPARENT);
            atlas.draw(new Canvas(image), map, false);
            assertEquals(
                    "old source must not leak through zoom fallback",
                    0,
                    Color.alpha(image.getPixel(260, 260)));
        }
    }

    @Test
    public void removedSourceAndClosedQueuedJobsNeverReplayOldCoverage() throws Exception {
        warm();
        atlas.areas.clear();
        synchronized (atlas.tiles) {
            Bitmap image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
            atlas.draw(new Canvas(image), map, false);
            assertEquals(0, Color.alpha(image.getPixel(256, 256)));
            atlas.close();
        }
        atlas.tiles.awaitIdle();
        assertEquals(0, atlas.tiles.bytes());
        assertEquals(0, atlas.tiles.pending());
    }

    RouteFrame installMask() throws Exception {
        double s = 1 << map.zoom;
        ArrayList<double[]> points = new ArrayList<>();
        points.add(new double[] {lat(map.cy + 80 / s), lon(map.cx - 40 / s), 1, 0});
        points.add(new double[] {lat(map.cy + 80 / s), lon(map.cx + 40 / s), 1, 1000});
        RouteFrame mask =
                new RouteFrame(
                        map.cx,
                        map.cy,
                        map.zoom,
                        map.getWidth(),
                        map.getHeight(),
                        1,
                        points,
                        null,
                        atlas);
        mask.lineDensity = map.getResources().getDisplayMetrics().density;
        mask.raster = true;
        mask.build();
        mask.mask.eraseColor(Color.TRANSPARENT);
        Paint p = new Paint(3);
        p.setColor(Color.WHITE);
        Canvas canvas = new Canvas(mask.mask);
        canvas.scale(
                mask.mask.getWidth() / (float) map.getWidth(),
                mask.mask.getHeight() / (float) map.getHeight());
        canvas.drawCircle(map.getWidth() / 2f, map.getHeight() / 2f, 90, p);
        Field ready = MapView.class.getDeclaredField("readyFrame");
        ready.setAccessible(true);
        ready.set(map, mask);
        // Замеры не включают гонку с построением новой RouteFrame: маска фиксирована
        // и переигрывается настоящим drawFrameMask под движущимся viewport.
        map.viewportMotion = android.animation.ValueAnimator.ofFloat(0, 1);
        return mask;
    }

    @Test
    public void fractionalFullDrawKeepsDstInRoutePhotosAndZonesComposed() throws Exception {
        map.illustrated = true;
        map.viewportFraction = 1.37f;
        RouteFrame mask = installMask();
        Paint route = new Paint(3);
        route.setColor(0xfff7efd9);
        route.setStrokeWidth(5);
        new Canvas(mask.lineImage).drawLine(160, 400, 240, 400, route);
        map.setPhotos(new ArrayList<>(Arrays.asList(new PhotoSelectionTest().photo(91, .0002))));
        map.setSpawnZones(Arrays.asList(new SpawnZone(91, "Synthetic zone", -.0005, -.0005, 20)));
        for (int stage = 0; stage < 3; stage++) {
            Bitmap image = frame();
            assertEquals(
                    "grey outside reveal stage=" + stage, 0xff9cabb1, image.getPixel(400, 256));
            assertEquals(
                    "color inside reveal stage=" + stage, 0xff78aeb7, image.getPixel(256, 256));
            assertTrue("route is composed after reveal", Color.red(image.getPixel(180, 453)) > 180);
            atlas.tiles.awaitIdle();
        }
        PhotoGroups.Group group = map.compactPhotos.groups(map).get(0);
        long[] selected = {0};
        map.setPhotoTapListener(photos -> selected[0] = photos.get(0).id);
        assertTrue(map.tapPhotos(group.x, group.y));
        assertEquals(91, selected[0]);
        assertEquals(1, map.compactPhotos.groups(map).size());
    }

    @Test
    public void matchedFullFrameMovingControlAndTileMeasurements() throws Exception {
        atlas.areas.get(0).eager = roads(10000, false);
        for (int[] size : new int[][] {{512, 512, 1}, {1080, 2340, 3}, {1440, 2560, 2}}) {
            map.layout(0, 0, size[0], size[1]);
            map.getResources().getDisplayMetrics().density = size[2];
            for (float fraction : new float[] {1, 1.37f}) {
                OfflineAtlas old = atlas;
                ControlAtlas control = new ControlAtlas();
                control.areas.clear();
                control.areas.addAll(old.areas);
                old.close();
                old.tiles.awaitIdle();
                atlas = control;
                Field field = MapView.class.getDeclaredField("atlas");
                field.setAccessible(true);
                field.set(map, atlas);
                map.viewportFraction = fraction;
                map.illustrated = true;
                map.cx = map.cy = 128;
                installMask();
                Canvas canvas =
                        new Canvas(Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888));
                long coldStart = System.nanoTime();
                map.draw(canvas);
                long coldUi = System.nanoTime() - coldStart;
                atlas.tiles.awaitIdle();
                long coldReady = System.nanoTime() - coldStart;
                warm();
                long[] before = new long[20],
                        after = new long[20],
                        selection = new long[20],
                        replay = new long[20];
                int hits = atlas.tiles.visibleHits, misses = atlas.tiles.visibleMisses;
                for (int i = -4; i < 20; i++) {
                    map.cx = 128 + (i + 4) * .75 / (1 << map.zoom);
                    map.cy = 128 + (i + 4) * .25 / (1 << map.zoom);
                    for (int order = 0; order < 2; order++) {
                        control.vectorControl = ((i + order) & 1) == 0;
                        atlas.snapshotNanos = atlas.tileNanos = 0;
                        long start = System.nanoTime();
                        map.draw(canvas);
                        long elapsed = System.nanoTime() - start;
                        if (i >= 0) {
                            (control.vectorControl ? before : after)[i] = elapsed;
                            if (!control.vectorControl) {
                                selection[i] = atlas.snapshotNanos;
                                replay[i] = atlas.tileNanos;
                            }
                        }
                    }
                    control.vectorControl = false;
                    atlas.tiles.awaitIdle();
                }
                assertTrue(atlas.tiles.visibleHits > hits);
                assertEquals("none", atlas.tileFallback);
                System.out.println(
                        "MOTION full MapView.draw "
                                + size[0]
                                + "x"
                                + size[1]
                                + " density="
                                + size[2]
                                + " fraction="
                                + fraction
                                + " 10k roads + DST_IN + route; ms median/p95/max vectorControl="
                                + stats(before)
                                + " tiles="
                                + stats(after)
                                + " selection="
                                + stats(selection)
                                + " tileReplay="
                                + stats(replay)
                                + " coldUI="
                                + coldUi / 1e6
                                + " firstBatchReady="
                                + coldReady / 1e6
                                + " hits="
                                + (atlas.tiles.visibleHits - hits)
                                + " misses="
                                + (atlas.tiles.visibleMisses - misses)
                                + " fallback="
                                + atlas.tileFallback
                                + "; native software Canvas, not phone FPS");
            }
        }
        map.getResources().getDisplayMetrics().density = 1;
    }

    static String stats(long[] values) {
        Arrays.sort(values);
        return values[values.length / 2] / 1e6
                + "/"
                + values[(int) Math.ceil(values.length * .95) - 1] / 1e6
                + "/"
                + values[values.length - 1] / 1e6;
    }

    @Test
    public void externalWorldMapSeparatelyMeasuresNativeOverzoomPrecision() throws Exception {
        WorldMapTest helper = new WorldMapTest();
        helper.setup();
        Field world = MapView.class.getDeclaredField("world");
        world.setAccessible(true);
        map.illustrated = true;
        try {
            for (int sourceZoom : new int[] {14, 3}) {
                int x = (1 << sourceZoom) / 2, y = (1 << sourceZoom) / 2;
                byte[] water =
                        WorldMapTest.layer(
                                "water", 3, "", 9, 4096, 0, 26, 4096, 0, 0, 8192, 4095, 0, 15);
                File database = helper.database(water, sourceZoom, x, y, false);
                try (WorldMap source =
                        WorldMap.open(
                                RuntimeEnvironment.getApplication(),
                                android.net.Uri.fromFile(database),
                                null)) {
                    assertNotNull(source.tile(sourceZoom, x, y, true));
                    world.set(map, source);
                    double baseX = (x + .5) * 256 / (1 << sourceZoom);
                    map.cy = (y + .5) * 256 / (1 << sourceZoom);
                    double maxError = 0;
                    for (int zoom = 17; zoom <= 22; zoom++) {
                        map.zoom = zoom;
                        double previous = 0;
                        for (int step = 0; step <= 16; step++) {
                            map.cx = baseX + (16 + step * .25) / (1 << zoom);
                            Bitmap image = frame();
                            double coverage = 0;
                            for (int px = 0; px < 300; px++)
                                coverage += (198 - Color.red(image.getPixel(px, 200))) / 42.0;
                            if (step > 0)
                                maxError = Math.max(maxError, Math.abs(coverage - previous - .25));
                            previous = coverage;
                        }
                    }
                    System.out.println(
                            "MOTION external WorldMap sourceZoom="
                                    + sourceZoom
                                    + " displayZoom=17..22 quarter-pixel native maxError="
                                    + maxError
                                    + "; separate source branch, not an OfflineAtlas result");
                    assertTrue("external MVT overzoom source=" + sourceZoom, maxError < .08);
                } finally {
                    world.set(map, null);
                }
            }
        } finally {
            helper.cleanup();
        }
    }
}

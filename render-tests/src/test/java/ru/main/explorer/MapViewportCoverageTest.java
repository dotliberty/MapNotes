package ru.main.explorer;

import static org.junit.Assert.*;

import android.graphics.*;
import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MapViewportCoverageTest {
    MapMotionRegressionTest fixture;
    MapView map;
    OfflineAtlas atlas;

    @Before
    public void setup() throws Exception {
        fixture = new MapMotionRegressionTest();
        fixture.setup();
        map = fixture.map;
        atlas = fixture.atlas;
        atlas.areas.get(0).eager = fixture.roads(40002, false);
    }

    @After
    public void cleanup() throws Exception {
        fixture.cleanup();
        atlas.viewport.awaitIdle();
    }

    AtlasTiles.Snapshot snapshot() throws Exception {
        Method method =
                OfflineAtlas.class.getDeclaredMethod(
                        "buildTileSnapshot", MapView.class, boolean.class, boolean.class);
        method.setAccessible(true);
        return (AtlasTiles.Snapshot) method.invoke(atlas, map, false, false);
    }

    Bitmap viewport() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        if (map.viewportFraction != 1)
            canvas.scale(map.viewportFraction, map.viewportFraction, 256, 256);
        Method method =
                OfflineAtlas.class.getDeclaredMethod(
                        "drawViewport",
                        Canvas.class,
                        MapView.class,
                        boolean.class,
                        AtlasTiles.Snapshot.class);
        method.setAccessible(true);
        method.invoke(atlas, canvas, map, false, snapshot());
        return bitmap;
    }

    void readyViewport() throws Exception {
        map.layout(0, 0, 512, 512);
        map.cx = map.cy = 128;
        map.viewportFraction = 1;
        viewport();
        atlas.viewport.awaitIdle();
        assertEquals(255, Color.alpha(viewport().getPixel(256, 256)));
    }

    OfflineAtlas.Area coldArea(double left, double right) {
        OfflineAtlas.Area area = new OfflineAtlas.Area();
        area.source =
                new File(map.getContext().getFilesDir(), "coverage-neighbour-not-decoded.json");
        area.legacy = true;
        area.south = -1;
        area.north = 1;
        area.west = MapMotionRegressionTest.lon(128 + (left - 256) / (1 << map.zoom));
        area.east = MapMotionRegressionTest.lon(128 + (right - 256) / (1 << map.zoom));
        atlas.areas.add(area);
        return area;
    }

    @Test
    public void coldNeighbourInSelectionMarginKeepsIndependentSaturatedViewport() throws Exception {
        readyViewport();
        CountDownLatch decoder = MapStressMotionTest.block(atlas, "loader");
        try {
            coldArea(768.125, 1000);
            map.cx += .25 / (1 << map.zoom);
            Bitmap bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
            atlas.draw(new Canvas(bitmap), map, false);
            assertEquals(1, atlas.loadingLayers);
            assertEquals(
                    "cold margin neighbour cannot hide ready A",
                    255,
                    Color.alpha(bitmap.getPixel(256, 256)));
            atlas.viewport.awaitIdle();
            assertEquals(
                    "offscreen B must not block a new A render",
                    255,
                    Color.alpha(viewport().getPixel(511, 256)));
        } finally {
            decoder.countDown();
        }
    }

    void replayWithCachedMissingStrip(double shift, float fraction) throws Exception {
        // Прогреваем настоящие tiles в малом допустимом viewport до насыщенного кадра.
        map.layout(0, 0, 64, 64);
        map.cx = 128 + shift / (1 << map.zoom);
        fixture.warm();
        readyViewport();
        CountDownLatch worker = MapStressMotionTest.block(atlas.viewport, "worker");
        try {
            map.cx = 128 + shift / (1 << map.zoom);
            map.viewportFraction = fraction;
            Bitmap image = viewport();
            for (int y = 4; y < 508; y += 17)
                for (int x = 4; x < 508; x += 17)
                    assertEquals(
                            "cached tiles fill all uncovered pixels shift="
                                    + shift
                                    + " fraction="
                                    + fraction
                                    + " x="
                                    + x,
                            255,
                            Color.alpha(image.getPixel(x, y)));
            assertEquals(
                    "worker remains blocked; result really comes from ready coverage",
                    1,
                    atlas.viewport.completed);
            assertEquals(
                    "reported coverage equals the native covered screen",
                    1,
                    atlas.lastCoverageFraction,
                    1e-6);
        } finally {
            worker.countDown();
        }
    }

    @Test
    public void partialViewportReplayComposesReadyTilesInMissingStrip() throws Exception {
        replayWithCachedMissingStrip(128.25, 1.37f);
    }

    @Test
    public void fullyOffscreenViewportDoesNotSuppressReadyTiles() throws Exception {
        replayWithCachedMissingStrip(700.25, 1);
    }

    @Test
    public void rapidWarmDirectionsKeepCompleteCoverageWithBothWorkersBlocked() throws Exception {
        double[] positions = {128.25, 96.5, 700.25, 668.5, -128.25, -96.5, 160.25, 0};
        for (double position : positions) {
            map.layout(0, 0, 64, 64);
            map.cx = 128 + position / (1 << map.zoom);
            fixture.warm();
        }
        readyViewport();
        CountDownLatch viewWorker = MapStressMotionTest.block(atlas.viewport, "worker");
        CountDownLatch tileWorker = MapStressMotionTest.block(atlas.tiles, "worker");
        double minimum = 1;
        try {
            for (int frame = 0; frame < 32; frame++) {
                map.cx = 128 + positions[frame % positions.length] / (1 << map.zoom);
                map.viewportFraction = (frame & 1) == 0 ? 1 : 1.37f;
                Bitmap image = viewport();
                minimum = Math.min(minimum, atlas.lastCoverageFraction);
                assertEquals(
                        "ready map stays available throughout motion frame=" + frame,
                        1,
                        atlas.lastCoverageFraction,
                        1e-6);
                for (int y = 8; y < 504; y += 31)
                    for (int x = 8; x < 504; x += 31)
                        assertEquals(
                                "pixel availability is a pass criterion",
                                255,
                                Color.alpha(image.getPixel(x, y)));
            }
            assertEquals(1, atlas.viewport.completed);
            System.out.println(
                    "COVERAGE warm reverse movement 32 frames, both workers blocked, minimum valid screen fraction="
                            + minimum);
        } finally {
            viewWorker.countDown();
            tileWorker.countDown();
        }
    }

    @Test
    public void newVisibleSourceInvalidatesOnlyItsIntersectingRectangle() throws Exception {
        readyViewport();
        CountDownLatch decoder = MapStressMotionTest.block(atlas, "loader");
        try {
            coldArea(350, 700);
            Bitmap image = viewport();
            assertEquals(
                    "independent A remains visible", 255, Color.alpha(image.getPixel(128, 256)));
            assertEquals(
                    "new unknown B must not replay stale A pixels",
                    0,
                    Color.alpha(image.getPixel(450, 256)));
        } finally {
            decoder.countDown();
        }
    }
}

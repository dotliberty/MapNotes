package ru.main.explorer;

import static org.junit.Assert.*;

import android.graphics.*;
import android.view.MotionEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Файловый индекс pack/meta/gzip: оба направления и реально холодная другая редакция. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SavedPackTransitionTest {
    static int roadPixels(Bitmap image) {
        int count = 0;
        for (int y = 220; y < 290; y++)
            for (int x = 254; x <= 257; x++) {
                int road = image.getPixel(x, y), water = image.getPixel(270, y);
                if (Color.alpha(road) == 255
                        && Color.red(road) > Color.red(water) + 15
                        && Color.green(road) > Color.green(water) + 4) count++;
            }
        return count;
    }

    void transition(int startZoom, boolean cold, int mutation) throws Exception {
        transition(startZoom, cold, mutation, false);
    }

    void transition(int startZoom, boolean cold, int mutation, boolean memory) throws Exception {
        transition(startZoom, cold, mutation, memory, false);
    }

    void transition(int startZoom, boolean cold, int mutation, boolean memory, boolean split)
            throws Exception {
        File pack =
                new File(
                        RuntimeEnvironment.getApplication().getFilesDir(),
                        "packs/r2-transition-" + UUID.randomUUID());
        JSONArray bounds = new JSONArray(new double[] {-.4, -.4, .4, .4});
        MapStressMotionTest.write(
                new File(pack, "pack.json"),
                new JSONObject().put("bounds", bounds).put("complete", true).put("sideKm", 100),
                false);
        MapStressMotionTest.write(
                new File(pack, "overview.json.gz"), MapReviewR2Test.scene(), true);
        MapStressMotionTest.write(new File(pack, "cell.json.gz"), MapReviewR2Test.scene(), true);
        MapStressMotionTest.write(
                new File(pack, "cell.meta"),
                new JSONObject().put("bounds", bounds).put("file", "cell.json.gz"),
                false);
        if (split) {
            MapStressMotionTest.write(
                    new File(pack, "cell.meta"),
                    new JSONObject()
                            .put("bounds", new JSONArray(new double[] {-.4, -.4, .4, 0}))
                            .put("file", "cell.json.gz"),
                    false);
            MapStressMotionTest.write(
                    new File(pack, "east.json.gz"), MapReviewR2Test.scene(), true);
            MapStressMotionTest.write(
                    new File(pack, "east.meta"),
                    new JSONObject()
                            .put("bounds", new JSONArray(new double[] {-.4, 0, .4, .4}))
                            .put("file", "east.json.gz"),
                    false);
        }
        MapView map = new MapView(RuntimeEnvironment.getApplication());
        map.online = false;
        map.asyncRendering = true;
        map.follow = false;
        map.illustrated = true;
        map.layout(0, 0, 512, 512);
        map.cx = map.cy = 128;
        map.zoom = startZoom;
        map.displayZoom = startZoom + (startZoom == 12 ? .5 : 0);
        map.viewportFraction = startZoom == 12 ? (float) Math.sqrt(2) : 1;
        OfflineAtlas atlas = new OfflineAtlas(RuntimeEnvironment.getApplication(), true, null);
        atlas.areas.removeIf(area -> !pack.equals(area.source.getParentFile()));
        ((android.util.LruCache<?, ?>) MapStressMotionTest.get(atlas, "geometry"))
                .resize(memory ? 8 * 1024 * 1024 : 1);
        Bitmap image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        CountDownLatch viewport = null, loader = null, tiles = null;
        boolean passed = false;
        String beginning = "", ending = "";
        MapReviewR2Test gestures = new MapReviewR2Test();
        boolean activeGesture = false;
        float span = startZoom == 13 ? 225 : 450;
        try {
            assertTrue(atlas.regionCount() >= 1);
            assertEquals(split ? 3 : 2, atlas.areas.size());
            OfflineAtlas.Area original = null;
            for (OfflineAtlas.Area area : atlas.areas) {
                assertNull("no eager bypass", area.eager);
                assertTrue(area.source.isFile());
                if (area.overview == (startZoom == 12)) original = area;
                if (!cold || area.overview == (startZoom == 12)) atlas.loadWays(area, true);
            }
            for (int i = 0; i < 3; i++) {
                atlas.draw(new Canvas(image), map, false);
                atlas.draw(new Canvas(image), map, true);
                atlas.viewport.awaitIdle();
                atlas.tiles.awaitIdle();
            }
            assertEquals(1, atlas.lastCoverageFraction, .00001);
            assertTrue("known warm road pixels", roadPixels(image) > 20);
            beginning = MapTrace.snapshot();
            viewport = MapStressMotionTest.block(atlas.viewport, "worker");
            loader = MapStressMotionTest.block(atlas, "loader");
            tiles = MapStressMotionTest.block(atlas.tiles, "worker");
            if (mutation == 1) original.stamp++;
            if (mutation == 2) atlas.areas.remove(original);
            if (mutation == 3)
                for (OfflineAtlas.Area area : atlas.areas) if (area != original) area.stamp++;
            gestures.begin(map);
            activeGesture = true;
            gestures.touch(map, MotionEvent.ACTION_MOVE, span, true);
            assertEquals(startZoom == 13 ? 12 : 13, map.zoom);
            AtlasTiles.Snapshot target = atlas.tileSnapshot(map, false);
            assertEquals(!cold, target.allReady());
            int minimumRoads = Integer.MAX_VALUE;
            for (boolean color : new boolean[] {false, true}) {
                image.eraseColor(0);
                atlas.draw(new Canvas(image), map, color);
                double coverage =
                        color ? atlas.lastColorCoverageFraction : atlas.lastCoverageFraction;
                int roads = roadPixels(image);
                minimumRoads = Math.min(minimumRoads, roads);
                System.out.println(
                        "SAVED TRANSITION "
                                + startZoom
                                + "->"
                                + map.zoom
                                + " cold="
                                + cold
                                + " mutation="
                                + mutation
                                + " color="
                                + color
                                + " ready="
                                + target.allReady()
                                + " coverage="
                                + coverage
                                + " roadPixels="
                                + roads
                                + " road="
                                + Integer.toHexString(image.getPixel(256, 256))
                                + " water="
                                + Integer.toHexString(image.getPixel(270, 256)));
                if (mutation != 0) {
                    assertEquals(
                            "changed/deleted source cannot return through the other rendition",
                            0,
                            coverage,
                            0);
                    assertEquals(0, roads);
                } else {
                    assertTrue("unchanged saved pack retains actual road pixels", roads > 20);
                    double expected =
                            memory
                                    ? 1
                                    : Math.min(
                                            1,
                                            Math.pow(2, 2 * (map.zoom - startZoom))
                                                    * map.viewportFraction
                                                    * map.viewportFraction);
                    assertEquals(
                            memory
                                    ? "warm tiles and neighbour ring cover the target"
                                    : "only the projected ready rectangle counts",
                            expected,
                            coverage,
                            .00001);
                }
            }
            gestures.end(map, span);
            activeGesture = false;
            ending = MapTrace.snapshot();
            if (mutation == 0) {
                viewport.countDown();
                loader.countDown();
                tiles.countDown();
                for (int i = 0; i < 4; i++) {
                    atlas.draw(new Canvas(image), map, false);
                    ((ExecutorService) MapStressMotionTest.get(atlas, "loader"))
                            .submit(() -> {})
                            .get(5, TimeUnit.SECONDS);
                    atlas.viewport.awaitIdle();
                    atlas.tiles.awaitIdle();
                }
                assertEquals(1, atlas.lastCoverageFraction, .00001);
                if (!memory) assertTrue(atlas.viewport.exactReady());
            }
            passed = true;
        } finally {
            if (activeGesture) gestures.end(map, span);
            if (ending.isEmpty()) ending = MapTrace.snapshot();
            String prefix =
                    "build/review-r2/saved-pack-"
                            + startZoom
                            + "-cold-"
                            + cold
                            + "-mutation-"
                            + mutation
                            + "-memory-"
                            + memory
                            + "-split-"
                            + split
                            + "-"
                            + System.getProperty("mapnotes.trace.phase", "after");
            File trace = new File(prefix + ".txt");
            trace.getParentFile().mkdirs();
            Files.write(trace.toPath(), MapTrace.snapshot().getBytes(StandardCharsets.UTF_8));
            Files.write(
                    new File(prefix + "-start.txt").toPath(),
                    (gestures.gestureBeginning == null ? beginning : gestures.gestureBeginning)
                            .getBytes(StandardCharsets.UTF_8));
            Files.write(
                    new File(prefix + "-end.txt").toPath(),
                    ending.getBytes(StandardCharsets.UTF_8));
            if (viewport != null) viewport.countDown();
            if (loader != null) loader.countDown();
            if (tiles != null) tiles.countDown();
            map.close();
            atlas.close();
            atlas.viewport.awaitIdle();
            atlas.tiles.awaitIdle();
            image.recycle();
        }
    }

    @Test
    public void detailToOverviewKeepsRoadPixels() throws Exception {
        transition(13, false, 0);
    }

    @Test
    public void overviewToDetailKeepsRoadPixels() throws Exception {
        transition(12, false, 0);
    }

    @Test
    public void coldOverviewKeepsReadyDetailRoadPixels() throws Exception {
        transition(13, true, 0);
    }

    @Test
    public void coldDetailKeepsReadyOverviewRoadPixels() throws Exception {
        transition(12, true, 0);
    }

    @Test
    public void changedRenditionsCannotReplay() throws Exception {
        transition(13, false, 1);
        transition(12, false, 1);
    }

    @Test
    public void removedRenditionsCannotReplay() throws Exception {
        transition(13, false, 2);
        transition(12, false, 2);
    }

    @Test
    public void changedTargetRenditionsCannotReplayOldSource() throws Exception {
        transition(13, false, 3);
        transition(12, false, 3);
    }

    @Test
    public void splitDetailCellsAndOverviewRetainRoadPixels() throws Exception {
        transition(13, false, 0, false, true);
        transition(12, false, 0, false, true);
    }

    @Test
    public void memoryTilesRetainBothRenditions() throws Exception {
        transition(13, false, 0, true);
        transition(12, false, 0, true);
    }

    @Test
    public void memoryTilesRejectChangedAndRemovedRenditions() throws Exception {
        transition(13, false, 1, true);
        transition(12, false, 2, true);
    }

    @Test
    public void queuedViewportCancellationsHaveTraceReasons() throws Exception {
        MapMotionRegressionTest fixture = new MapMotionRegressionTest();
        fixture.setup();
        AtlasViewport viewport = fixture.atlas.viewport;
        CountDownLatch release = MapStressMotionTest.block(viewport, "worker");
        Bitmap image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        try {
            AtlasTiles.Snapshot snapshot = fixture.atlas.tileSnapshot(fixture.map, false);
            viewport.draw(new Canvas(image), snapshot, 128, 128, 512, 512, false, 1);
            long first = MapTrace.id(MapStressMotionTest.get(viewport, "latest"));
            viewport.draw(new Canvas(image), snapshot, 128.000001, 128, 512, 512, false, 1);
            long second = MapTrace.id(MapStressMotionTest.get(viewport, "latest"));
            assertEquals(1, viewport.cancelled);
            viewport.pause();
            assertEquals(2, viewport.cancelled);
            boolean coalesced = false, paused = false;
            for (String line : MapTrace.snapshot().split("\n")) {
                String[] fields = line.split(",");
                if (fields.length != 9
                        || !fields[3].equals("VIEWPORT")
                        || !fields[4].equals("CANCEL")) continue;
                if (Long.parseLong(fields[5]) == first && fields[8].equals("8")) coalesced = true;
                if (Long.parseLong(fields[5]) == second && fields[8].equals("9")) paused = true;
            }
            assertTrue("coalesced pending request has one terminal reason", coalesced);
            assertTrue("pause does not silently discard pending work", paused);
        } finally {
            release.countDown();
            fixture.cleanup();
            image.recycle();
        }
    }
}

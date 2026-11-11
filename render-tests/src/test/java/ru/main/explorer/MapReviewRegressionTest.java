package ru.main.explorer;

import static org.junit.Assert.*;

import android.graphics.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MapReviewRegressionTest {
    MapMotionRegressionTest fixture;
    MapView map;
    OfflineAtlas atlas;

    @Before
    public void setup() throws Exception {
        fixture = new MapMotionRegressionTest();
        fixture.setup();
        map = fixture.map;
        atlas = fixture.atlas;
    }

    @After
    public void cleanup() throws Exception {
        fixture.cleanup();
    }

    OfflineAtlas.Way road(double px, double py, String name) {
        double scale = 1 << map.zoom;
        double x = 128 + px / scale, y = 128 + py / scale;
        OfflineAtlas.Way way = new OfflineAtlas.Way();
        way.type = "road";
        way.kind = "residential";
        way.name = name;
        way.coords =
                new double[] {
                    MapMotionRegressionTest.lat(y),
                    MapMotionRegressionTest.lon(x),
                    MapMotionRegressionTest.lat(y + 2 / scale),
                    MapMotionRegressionTest.lon(x + 2 / scale)
                };
        way.south = way.coords[2];
        way.north = way.coords[0];
        way.west = way.coords[1];
        way.east = way.coords[3];
        return way;
    }

    @Test
    public void overloadedInvisiblePartOfSameColdTileCannotLeaveVisibleMapEmpty() throws Exception {
        map.layout(0, 0, 64, 64);
        double scale = 1 << map.zoom;
        map.cx = map.cy = 128 + 32 / scale;
        OfflineAtlas.IndexedWays ways = new OfflineAtlas.IndexedWays();
        ways.add(road(32, 32, ""));
        OfflineAtlas.Way invisible = road(200, 200, "");
        for (int i = 0; i < 70000; i++) ways.add(invisible);
        ways.index();
        atlas.areas.get(0).eager = ways;
        assertNotNull("screen admission is below its budget", atlas.tileSnapshot(map, false));
        Bitmap expected = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        atlas.drawGeometry(new Canvas(expected), map, false);
        for (int frame = 0; frame < 3; frame++) {
            Bitmap image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            atlas.draw(new Canvas(image), map, false);
            atlas.tiles.awaitIdle();
            assertEquals(
                    "visible coverage must recover frame=" + frame,
                    255,
                    Color.alpha(image.getPixel(32, 32)));
            assertTrue("bounded recovery preserves visible geometry", expected.sameAs(image));
        }
        assertEquals("tile-work-memory", atlas.tileFallback);
        assertEquals("recovery prepares only the visible road", 2, atlas.lastVertices);
        for (boolean hybrid : new boolean[] {false, true}) {
            map.viewportFraction = 1.37f;
            map.cx += .25 / scale;
            Bitmap actual = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            expected.eraseColor(Color.TRANSPARENT);
            Canvas a = new Canvas(actual), b = new Canvas(expected);
            a.scale(1.37f, 1.37f, 32, 32);
            b.scale(1.37f, 1.37f, 32, 32);
            atlas.draw(a, map, hybrid);
            atlas.drawGeometry(b, map, hybrid);
            assertTrue("fractional recovery and both color passes", actual.sameAs(expected));
        }
    }

    @Test
    public void blockedQueueDropsFarWorkAndInvalidSourceDependencies() throws Exception {
        ExecutorService worker = (ExecutorService) field(atlas.tiles, "worker");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        worker.submit(
                () -> {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            map.layout(0, 0, 64, 64);
            map.cx = map.cy = 128 + 128.0 / (1 << map.zoom);
            Canvas canvas = new Canvas(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888));
            atlas.draw(canvas, map, false);
            Set<String> original = queued("visibleJobs");
            original.addAll(queued("prefetchJobs"));
            map.cx += 1024.0 / (1 << map.zoom);
            atlas.draw(canvas, map, false);
            Set<String> moved = queued("visibleJobs");
            moved.addAll(queued("prefetchJobs"));
            assertTrue(Collections.disjoint(original, moved));
            atlas.areas.get(0).stamp++;
            atlas.draw(canvas, map, false);
            Set<String> replaced = queued("visibleJobs");
            replaced.addAll(queued("prefetchJobs"));
            assertTrue(Collections.disjoint(moved, replaced));
            assertTrue(atlas.tiles.cancelled >= original.size() + moved.size());
            assertTrue((Long) field(atlas.tiles, "workBytes") <= AtlasTiles.MAX_WORK_BYTES);
            assertTrue(atlas.tiles.pending() <= AtlasTiles.MAX_PENDING);
        } finally {
            release.countDown();
        }
    }

    static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    Set<String> queued(String queue) throws Exception {
        Set<String> keys = new HashSet<>();
        for (Object job : (Collection<?>) field(atlas.tiles, queue))
            keys.add((String) field(job, "key"));
        return keys;
    }

    @Test
    public void blockedWorkerPromotesPendingPrefetchAndDemotesOldVisibleAfterPan()
            throws Exception {
        ExecutorService worker = (ExecutorService) field(atlas.tiles, "worker");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        worker.submit(
                () -> {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            map.layout(0, 0, 64, 64);
            double scale = 1 << map.zoom;
            map.cx = map.cy = 128 + 128 / scale;
            long tx = (long) (128 * scale / AtlasTiles.SIZE), ty = tx;
            AtlasTiles.Snapshot snapshot = atlas.tileSnapshot(map, false);
            atlas.draw(
                    new Canvas(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)), map, false);
            String oldVisible = snapshot.tileKey(tx, ty, false);
            String nextVisible = snapshot.tileKey(tx + 1, ty, false);
            assertTrue(queued("visibleJobs").contains(oldVisible));
            assertTrue(queued("prefetchJobs").contains(nextVisible));
            map.cx += 256 / scale;
            atlas.draw(
                    new Canvas(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)), map, false);
            assertTrue(
                    "existing pending prefetch must be promoted",
                    queued("visibleJobs").contains(nextVisible));
            assertFalse(
                    "offscreen work must lose visible priority",
                    queued("visibleJobs").contains(oldVisible));
            assertTrue(
                    "old visible tile is still in the neighbour ring",
                    queued("prefetchJobs").contains(oldVisible));
            assertTrue(atlas.tiles.pending() <= AtlasTiles.MAX_PENDING);
        } finally {
            release.countDown();
        }
    }

    OfflineAtlas.IndexedWays named(int count) {
        OfflineAtlas.IndexedWays ways = new OfflineAtlas.IndexedWays();
        // Первые 24 подписи разнесены; хвост именован, но не должен готовиться после лимита.
        for (int i = 0; i < count; i++) {
            int position = i < 24 ? i : 0;
            ways.add(
                    road(200 + position % 3 * 200 - 540, 400 + position / 3 * 100 - 1170, "N" + i));
        }
        ways.index();
        return ways;
    }

    @Test
    public void denseNamedLabelsStopPreparingCandidatesAfterAcceptedLimit() throws Exception {
        map.layout(0, 0, 1080, 2340);
        atlas.areas.get(0).eager = named(40000);
        AtlasTiles.Snapshot snapshot = atlas.tileSnapshot(map, false);
        assertNotNull(snapshot);
        Canvas canvas = new Canvas(Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888));
        long[] times = new long[20], eager = new long[20];
        for (int i = -4; i < 20; i++) {
            map.cx = 128 + (i + 4) * .25 / (1 << map.zoom);
            for (int order = 0; order < 2; order++) {
                boolean legacy = ((i + order) & 1) == 0;
                long start = System.nanoTime();
                if (legacy) eagerLabels(canvas, snapshot, map.cx, map.cy, 1080, 2340, 1.37f);
                else atlas.tiles.drawLabels(canvas, snapshot, map.cx, map.cy, 1080, 2340, 1.37f);
                if (i >= 0) (legacy ? eager : times)[i] = System.nanoTime() - start;
            }
        }
        System.out.println(
                "REVIEW named=40000 labels="
                        + atlas.tiles.lastLabels
                        + " prepared="
                        + atlas.tiles.lastLabelExamined
                        + " label ms median/p95/max eager="
                        + MapMotionRegressionTest.stats(eager)
                        + " lazy="
                        + MapMotionRegressionTest.stats(times)
                        + "; native software Canvas, not phone FPS");
        assertEquals(24, atlas.tiles.lastLabels);
        assertTrue(
                "do not prepare and sort all 40000 after 24 accepted labels",
                atlas.tiles.lastLabelExamined <= 40);
    }

    @Test
    public void lazyLabelsMatchMaterializedOrderAndNativePixelsWithRejectionsAndWrap()
            throws Exception {
        map.layout(0, 0, 1080, 2340);
        OfflineAtlas.IndexedWays ways = named(200);
        ways.get(1).name = ways.get(0).name;
        ways.get(2).coords = ways.get(0).coords.clone();
        ways.get(2).south = ways.get(0).south;
        ways.get(2).north = ways.get(0).north;
        ways.get(2).west = ways.get(0).west;
        ways.get(2).east = ways.get(0).east;
        ways.index();
        atlas.areas.get(0).eager = ways;
        for (boolean hybrid : new boolean[] {false, true})
            for (float fraction : new float[] {1, 1.37f}) {
                AtlasTiles.Snapshot snapshot = atlas.tileSnapshot(map, hybrid);
                Bitmap expected = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888);
                Bitmap actual = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888);
                eagerLabels(new Canvas(expected), snapshot, map.cx, map.cy, 1080, 2340, fraction);
                atlas.tiles.drawLabels(
                        new Canvas(actual), snapshot, map.cx, map.cy, 1080, 2340, fraction);
                assertTrue("exact label pixel/occlusion order", expected.sameAs(actual));
            }
        OfflineAtlas.IndexedWays wrapped = new OfflineAtlas.IndexedWays();
        for (int i = 0; i < 200; i++) {
            double worldX = i % 2 == 0 ? .0001 + i * .000001 : 255.9999 - i * .000001;
            wrapped.add(road((worldX - 128) * (1 << map.zoom), i - 100, "W" + i));
        }
        wrapped.index();
        AtlasFeatureIndex index = wrapped.labelIndex;
        for (double cx : new double[] {0, .0002, 255.9998, 128}) {
            List<AtlasTiles.Feature> expected = index.query(cx, 128, .001, .001, 18);
            for (int limit : new int[] {1, 24, 1000}) {
                List<AtlasTiles.Feature> actual = new ArrayList<>();
                index.visit(
                        cx,
                        128,
                        .001,
                        .001,
                        18,
                        f -> {
                            actual.add(f);
                            return actual.size() < limit;
                        });
                assertEquals(expected.subList(0, Math.min(limit, expected.size())), actual);
            }
        }
    }

    static float screenX(double value, double cx, double scale, double offset) {
        double dx = value - cx;
        if (dx > 128) dx -= 256;
        if (dx < -128) dx += 256;
        return (float) (dx * scale + offset);
    }

    /** Материализующий label-путь code51: контроль порядка, коллизий и полной подготовки. */
    static void eagerLabels(
            Canvas canvas,
            AtlasTiles.Snapshot s,
            double cx,
            double cy,
            int width,
            int height,
            float fraction) {
        Paint p = new Paint(3);
        p.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        p.setTextSize(11 * s.density);
        p.setColor(s.hybrid ? Color.WHITE : 0xff33463c);
        Set<String> names = new HashSet<>();
        ArrayList<RectF> boxes = new ArrayList<>();
        double scale = 1 << s.zoom;
        double halfWidth = width / (2.0 * fraction), halfHeight = height / (2.0 * fraction);
        double left = width / 2.0 - halfWidth, top = height / 2.0 - halfHeight;
        double right = width / 2.0 + halfWidth, bottom = height / 2.0 + halfHeight;
        for (AtlasTiles.Layer layer : s.layers)
            for (AtlasTiles.Feature f :
                    layer.labels.query(cx, cy, halfWidth / scale, halfHeight / scale, s.zoom)) {
                if (boxes.size() >= 24) return;
                if (f.pass != 2
                        || f.name.isEmpty()
                        || names.contains(f.name)
                        || !MapDetail.roadVisible(f.kind, s.zoom)) continue;
                float x = screenX(f.xy[0], cx, scale, width / 2.0);
                float y = (float) ((f.xy[1] - cy) * scale + height / 2.0);
                if (x <= left
                        || x >= right - 90 * s.density
                        || y <= top + 20 * s.density
                        || y >= bottom - 45 * s.density) continue;
                String label = f.name.length() > 48 ? f.name.substring(0, 48) : f.name;
                RectF box =
                        new RectF(
                                x,
                                y - 18 * s.density,
                                x + p.measureText(label) + 12 * s.density,
                                y + 8 * s.density);
                boolean overlaps = false;
                for (RectF other : boxes)
                    if (RectF.intersects(box, other)) {
                        overlaps = true;
                        break;
                    }
                if (overlaps) continue;
                names.add(f.name);
                boxes.add(box);
                int save = canvas.save();
                canvas.clipRect(
                        screenX(layer.left, cx, scale, width / 2.0),
                        (float) ((layer.top - cy) * scale + height / 2.0),
                        screenX(layer.right, cx, scale, width / 2.0),
                        (float) ((layer.bottom - cy) * scale + height / 2.0));
                canvas.drawText(label, x + 3 * s.density, y - 3 * s.density, p);
                canvas.restoreToCount(save);
            }
    }
}

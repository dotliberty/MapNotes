package ru.main.explorer;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public class ImageryTraceTest {
    private static final ImageryTrace.Source SOURCE =
            new ImageryTrace.Source("synthetic-test", "generated", "test fixture only", "unknown");
    private static final ImageryTrace.GeoReference GEO =
            new ImageryTrace.GeoReference(10, 20, 16, 26, false);

    private int[] image(int w, int h) {
        int[] a = new int[w * h];
        Arrays.fill(a, 0xff808080);
        return a;
    }

    private ImageryTrace.Candidate trace(
            int w,
            int h,
            int[] a,
            boolean[] b,
            int sx,
            int sy,
            int ex,
            int ey,
            BooleanSupplier cancel) {
        return ImageryTrace.trace(w, h, a, b, sx, sy, ex, ey, sx, sy, 0, GEO, SOURCE, cancel);
    }

    @Test
    public void uniformDiagonalIsShortestAndPreservesEndpoints() {
        ImageryTrace.Candidate c = trace(5, 5, image(5, 5), null, 0, 0, 4, 4, () -> false);
        assertEquals(4 * Math.sqrt(2), c.cost, 1e-12);
        assertEquals(5, c.vertices.size());
        assertEquals(0, c.vertices.get(0).x);
        assertEquals(0, c.vertices.get(0).y);
        assertEquals(4, c.vertices.get(4).x);
        assertEquals(4, c.vertices.get(4).y);
        assertSame(SOURCE, c.source);
        assertSame(GEO, c.reference);
        assertEquals(0x808080, c.sampleRgb);
    }

    @Test
    public void identicalEndpointsReturnOneVertex() {
        ImageryTrace.Candidate c = trace(2, 2, image(2, 2), null, 1, 1, 1, 1, () -> false);
        assertEquals(1, c.vertices.size());
        assertEquals(0, c.cost, 0);
    }

    @Test
    public void barrierUsesOnlyGapAndDoesNotMutateInputs() {
        int[] a = image(7, 7), original = a.clone();
        boolean[] blocked = new boolean[49];
        for (int y = 0; y < 6; y++) blocked[y * 7 + 3] = true;
        boolean[] before = blocked.clone();
        ImageryTrace.Candidate c = trace(7, 7, a, blocked, 0, 0, 6, 0, () -> false);
        assertNotNull(c);
        boolean gap = false;
        for (ImageryTrace.Vertex v : c.vertices) {
            assertFalse(blocked[v.y * 7 + v.x]);
            if (v.x == 3 && v.y == 6) gap = true;
        }
        assertTrue(gap);
        assertArrayEquals(original, a);
        assertArrayEquals(before, blocked);
    }

    @Test
    public void cannotCutBlockedDiagonalCorner() {
        assertNull(
                trace(
                        2,
                        2,
                        image(2, 2),
                        new boolean[] {false, true, true, false},
                        0,
                        0,
                        1,
                        1,
                        () -> false));
    }

    @Test
    public void transparentBarrierDisconnects() {
        int[] a = image(3, 3);
        for (int y = 0; y < 3; y++) a[y * 3 + 1] = 0x00808080;
        assertNull(trace(3, 3, a, null, 0, 1, 2, 1, () -> false));
    }

    @Test
    public void colourSamplePrefersLongerMatchingCorridor() {
        int[] a = image(7, 5);
        Arrays.fill(a, 0xff000000);
        for (int x = 0; x < 7; x++) a[x] = 0xffffffff;
        for (int y = 0; y < 3; y++) {
            a[y * 7] = 0xffffffff;
            a[y * 7 + 6] = 0xffffffff;
        }
        ImageryTrace.Candidate c = trace(7, 5, a, null, 0, 2, 6, 2, () -> false);
        assertTrue(c.vertices.stream().anyMatch(v -> v.y == 0));
        for (ImageryTrace.Vertex v : c.vertices) assertEquals(0xffffffff, a[v.y * 7 + v.x]);
    }

    @Test
    public void sampleWindowIsExplicitAndClipped() {
        int[] a = {0xff000000, 0xff646464, 0xffc8c8c8, 0x00000000};
        ImageryTrace.Candidate c =
                ImageryTrace.trace(2, 2, a, null, 0, 0, 1, 0, 0, 0, 2, GEO, SOURCE, () -> false);
        assertEquals(0x646464, c.sampleRgb);
        assertEquals(2, c.sampleRadius);
    }

    @Test
    public void linearCoordinatesUsePixelCentresAndNorthDown() {
        double[] top = GEO.coordinate(0, 0, 3, 3), bottom = GEO.coordinate(2, 2, 3, 3);
        assertArrayEquals(new double[] {25, 11}, top, 1e-12);
        assertArrayEquals(new double[] {21, 15}, bottom, 1e-12);
    }

    @Test
    public void mercatorCoordinatesInterpolateProjectedY() {
        ImageryTrace.GeoReference g = new ImageryTrace.GeoReference(-10, 0, 10, 60, true);
        double[] centre = g.coordinate(0, 0, 1, 1);
        assertEquals(35.264389682754654, centre[0], 1e-10);
        assertEquals(0, centre[1], 0);
        assertArrayEquals(
                new double[] {0, 0},
                new ImageryTrace.GeoReference(-1, -60, 1, 60, true).coordinate(0, 0, 1, 1),
                1e-12);
    }

    @Test
    public void invalidInputsAreRejectedBeforeWork() {
        assertThrows(
                IllegalArgumentException.class,
                () -> trace(1025, 1, new int[1025], null, 0, 0, 0, 0, () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () -> trace(1024, 1024, new int[0], null, 0, 0, 0, 0, () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () -> trace(0, 1, new int[0], null, 0, 0, 0, 0, () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () -> trace(2, 2, new int[3], null, 0, 0, 1, 1, () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () -> trace(2, 2, image(2, 2), new boolean[1], 0, 0, 1, 1, () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () -> trace(2, 2, image(2, 2), null, -1, 0, 1, 1, () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () -> trace(2, 2, new int[4], null, 0, 0, 1, 1, () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ImageryTrace.GeoReference(170, 0, -170, 10, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ImageryTrace.GeoReference(0, 0, 1, 90, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ImageryTrace.GeoReference(Double.NaN, 0, 1, 10, false));
        assertThrows(
                IllegalArgumentException.class, () -> new ImageryTrace.Source("", "a", "b", "c"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ImageryTrace.trace(
                                2,
                                2,
                                image(2, 2),
                                null,
                                0,
                                0,
                                1,
                                1,
                                0,
                                0,
                                3,
                                GEO,
                                SOURCE,
                                () -> false));
    }

    @Test
    public void cancellationBeforeWorkAndDuringSearch() {
        assertThrows(
                CancellationException.class,
                () -> trace(2, 2, image(2, 2), null, 0, 0, 1, 1, () -> true));
        AtomicInteger checks = new AtomicInteger();
        assertThrows(
                CancellationException.class,
                () ->
                        trace(
                                128,
                                128,
                                image(128, 128),
                                null,
                                0,
                                0,
                                127,
                                127,
                                () -> checks.incrementAndGet() > 100));
        assertTrue(checks.get() > 100);
    }

    @Test
    public void threadInterruptionIsPreserved() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    CancellationException.class,
                    () -> trace(2, 2, image(2, 2), null, 0, 0, 1, 1, () -> false));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void maximumBudgetCompletesAndCandidateIsReadOnly() {
        ImageryTrace.Candidate c =
                trace(512, 512, image(512, 512), null, 0, 0, 511, 511, () -> false);
        assertEquals(512, c.vertices.size());
        assertEquals(511 * Math.sqrt(2), c.cost, 1e-8);
        assertThrows(UnsupportedOperationException.class, () -> c.vertices.clear());
    }

    @Test
    public void costMatchesIndependentSmallGraphOracle() {
        java.util.Random random = new java.util.Random(237);
        for (int run = 0; run < 40; run++) {
            int n = 25;
            int[] a = new int[n];
            boolean[] blocked = new boolean[n];
            for (int i = 0; i < n; i++) {
                a[i] = 0xff000000 | random.nextInt(0x1000000);
                blocked[i] = random.nextInt(5) == 0;
            }
            blocked[0] = blocked[24] = false;
            double[] d = new double[n];
            Arrays.fill(d, Double.POSITIVE_INFINITY);
            d[0] = 0;
            // Bellman-Ford over all directed edges, independent of the production heap.
            for (int iteration = 0; iteration < n - 1; iteration++)
                for (int u = 0; u < n; u++)
                    if (!blocked[u])
                        for (int v = 0; v < n; v++)
                            if (!blocked[v] && u != v) {
                                int dx = Math.abs(u % 5 - v % 5), dy = Math.abs(u / 5 - v / 5);
                                if (dx > 1
                                        || dy > 1
                                        || dx == 1
                                                && dy == 1
                                                && (blocked[u / 5 * 5 + v % 5]
                                                        || blocked[v / 5 * 5 + u % 5])) continue;
                                double cost =
                                        (weight(a[u], a[0]) + weight(a[v], a[0]))
                                                / 2
                                                * (dx + dy == 2 ? Math.sqrt(2) : 1);
                                d[v] = Math.min(d[v], d[u] + cost);
                            }
            ImageryTrace.Candidate c = trace(5, 5, a, blocked, 0, 0, 4, 4, () -> false);
            if (Double.isInfinite(d[24])) assertNull(c);
            else {
                assertNotNull(c);
                assertEquals(d[24], c.cost, 1e-10);
            }
        }
    }

    private double weight(int a, int b) {
        double sum = 0;
        for (int shift : new int[] {0, 8, 16}) {
            double d = ((a >>> shift) & 255) - ((b >>> shift) & 255);
            sum += d * d;
        }
        return 1 + 20 * sum / (3 * 255 * 255);
    }
}

package ru.main.explorer;

import static org.junit.Assert.*;

import android.graphics.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SourceStateRegressionTest {
    static void expireEvents() {
        for (int i = 0; i <= MapTrace.CAPACITY; i++)
            MapTrace.record(MapTrace.Lane.FRAME, MapTrace.Event.DRAW, 0, 0, 0, 0);
    }

    static void save(String name, String trace) throws Exception {
        File directory = new File("build/source-state");
        directory.mkdirs();
        Files.write(
                new File(directory, name + ".txt").toPath(),
                trace.getBytes(StandardCharsets.UTF_8));
    }

    static String sourceRows(String trace, OfflineAtlas.Area area) {
        StringBuilder result = new StringBuilder();
        for (String row : trace.split("\n")) {
            String[] fields = row.split(",");
            if (fields.length == 9
                    && fields[3].equals("SOURCE")
                    && fields[5].equals(Long.toString(MapTrace.id(area)))) {
                for (int i = 3; i < fields.length; i++) result.append(fields[i]).append(',');
                result.append('\n');
            }
        }
        return result.toString();
    }

    static int state(String trace, OfflineAtlas.Area area, int variant) {
        for (String row : trace.split("\n")) {
            String[] fields = row.split(",");
            if (fields.length == 9
                    && fields[0].equals("0")
                    && fields[3].equals("SOURCE")
                    && fields[4].equals("STATE")
                    && fields[5].equals(Long.toString(MapTrace.id(area)))
                    && fields[6].equals(Integer.toString(variant)))
                return Integer.parseInt(fields[7]);
        }
        return -1;
    }

    @Test
    public void terminalFailureSurvivesRingExpiry() throws Exception {
        MapMotionRegressionTest fixture = new MapMotionRegressionTest();
        fixture.setup();
        fixture.atlas.areas.clear();
        OfflineAtlas.Area area = fixture.addArea(-1, 1, null);
        area.source =
                new File(RuntimeEnvironment.getApplication().getCacheDir(), "state-invalid.json");
        Files.write(area.source.toPath(), "{\"elements\":[".getBytes(StandardCharsets.UTF_8));
        CountDownLatch release = MapStressMotionTest.block(fixture.atlas, "loader");
        Bitmap image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        try {
            fixture.atlas.draw(new Canvas(image), fixture.map, false);
            expireEvents();
            fixture.atlas.draw(new Canvas(image), fixture.map, false);
            String queuedTrace = MapTrace.snapshot();
            release.countDown();
            ((ExecutorService) MapStressMotionTest.get(fixture.atlas, "loader"))
                    .submit(() -> {})
                    .get(5, TimeUnit.SECONDS);
            expireEvents();
            fixture.atlas.draw(new Canvas(image), fixture.map, false);
            String failedTrace = MapTrace.snapshot();
            File directory = new File("build/source-state");
            directory.mkdirs();
            Files.write(
                    new File(directory, "queued.txt").toPath(),
                    queuedTrace.getBytes(StandardCharsets.UTF_8));
            Files.write(
                    new File(directory, "failed.txt").toPath(),
                    failedTrace.getBytes(StandardCharsets.UTF_8));
            assertNotEquals(
                    "fresh export distinguishes queued from persistent failure",
                    sourceRows(queuedTrace, area),
                    sourceRows(failedTrace, area));
            assertEquals(1, state(queuedTrace, area, 1));
            assertEquals(5, state(failedTrace, area, 1));
            CountDownLatch overview = MapStressMotionTest.block(fixture.atlas, "loader");
            try {
                fixture.atlas.ways(area, 14);
                expireEvents();
                String noDraw = MapTrace.snapshot();
                save("failed-detail-queued-overview", noDraw);
                assertEquals("failed detail survives without redraw", 5, state(noDraw, area, 1));
                assertEquals("another variant has its own queue state", 1, state(noDraw, area, 2));
            } finally {
                overview.countDown();
            }
        } finally {
            release.countDown();
            fixture.cleanup();
            image.recycle();
        }
    }

    static final class NoUiStatFile extends File {
        final Thread ui = Thread.currentThread();

        NoUiStatFile(File file) {
            super(file.getPath());
        }

        void check() {
            assertNotSame("source file stat must not run on UI/export", ui, Thread.currentThread());
        }

        @Override
        public boolean exists() {
            check();
            return super.exists();
        }

        @Override
        public boolean isFile() {
            check();
            return super.isFile();
        }

        @Override
        public long length() {
            check();
            return super.length();
        }

        @Override
        public long lastModified() {
            check();
            return super.lastModified();
        }
    }

    static final class ImportFaultFile extends File {
        boolean fail;

        ImportFaultFile(File file) {
            super(file.getPath());
        }

        @Override
        public String getName() {
            if (fail) {
                fail = false;
                throw new OutOfMemoryError("controlled import fault");
            }
            return super.getName();
        }
    }

    void retryClearsOnlyPublishedVariant(boolean disk) throws Exception {
        MapMotionRegressionTest fixture = new MapMotionRegressionTest();
        fixture.setup();
        OfflineAtlas atlas = fixture.atlas;
        atlas.areas.clear();
        OfflineAtlas.Area area = fixture.addArea(-1, 1, null);
        File file = new File(RuntimeEnvironment.getApplication().getCacheDir(), "state-retry.json");
        Files.write(
                file.toPath(), MapReviewR2Test.scene().toString().getBytes(StandardCharsets.UTF_8));
        ImportFaultFile fault = new ImportFaultFile(file);
        area.source = fault;
        android.util.LruCache<?, ?> cache =
                (android.util.LruCache<?, ?>) MapStressMotionTest.get(atlas, "geometry");
        if (disk) cache.resize(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            for (int variant = 1; variant <= 3; variant++) {
                final int selected = variant;
                fault.fail = true;
                ExecutionException error =
                        assertThrows(
                                ExecutionException.class,
                                () ->
                                        worker.submit(() -> importVariant(atlas, area, selected))
                                                .get(5, TimeUnit.SECONDS));
                assertTrue(
                        "only an injected OOM, not actual heap exhaustion",
                        error.getCause() instanceof OutOfMemoryError);
                assertEquals(5, state(MapTrace.snapshot(), area, variant));
            }
            for (int variant = 1; variant <= 3; variant++) {
                final int selected = variant;
                worker.submit(() -> importVariant(atlas, area, selected)).get(5, TimeUnit.SECONDS);
                assertEquals(variant, atlas.geometryLoads);
                assertEquals(3, state(MapTrace.snapshot(), area, variant));
                if (disk) {
                    // У disk descriptor нет LRU eviction: явно снимаем только тестовый ready entry.
                    String suffix = variant == 1 ? "" : variant == 2 ? "#o" : "#walls";
                    java.util.Map<?, ?> descriptors =
                            (java.util.Map<?, ?>) MapStressMotionTest.get(atlas, "diskGeometry");
                    OfflineAtlas.IndexedWays removed =
                            (OfflineAtlas.IndexedWays) descriptors.remove(file.getPath() + suffix);
                    assertNotNull(removed);
                    removed.disk.close();
                } else cache.evictAll();
                expireEvents();
                String trace = MapTrace.snapshot();
                save("recovery-" + (disk ? "disk" : "memory") + "-variant-" + variant, trace);
                assertEquals(
                        "successful retry then loss of ready data is MISSING",
                        4,
                        state(trace, area, variant));
                for (int other = 1; other <= 3; other++)
                    assertEquals(
                            "only the successfully published variant loses its old outcome",
                            other <= variant ? 4 : 5,
                            state(trace, area, other));
            }
        } finally {
            worker.shutdownNow();
            fixture.cleanup();
        }
    }

    static Object importVariant(OfflineAtlas atlas, OfflineAtlas.Area area, int variant) {
        return variant == 3
                ? atlas.buildingsNear(.00015, .00015, Visibility.RADIUS)
                : atlas.loadWays(area, variant == 2);
    }

    @Test
    public void successfulRetryThenEvictionKeepsOtherVariantOutcomes() throws Exception {
        retryClearsOnlyPublishedVariant(false);
    }

    @Test
    public void successfulRetryToDiskKeepsOtherVariantOutcomes() throws Exception {
        retryClearsOnlyPublishedVariant(true);
    }

    @Test
    public void allCurrentStatesExportWithoutDrawOrAtlasMonitor() throws Exception {
        MapMotionRegressionTest fixture = new MapMotionRegressionTest();
        fixture.setup();
        OfflineAtlas atlas = fixture.atlas;
        atlas.areas.clear();
        OfflineAtlas.Area area = fixture.addArea(-1, 1, null);
        File file = new File(RuntimeEnvironment.getApplication().getCacheDir(), "state-valid.json");
        Files.write(
                file.toPath(), MapReviewR2Test.scene().toString().getBytes(StandardCharsets.UTF_8));
        area.source = new NoUiStatFile(file);
        CountDownLatch release = MapStressMotionTest.block(atlas, "loader");
        try {
            assertEquals(4, state(MapTrace.snapshot(), area, 1));
            atlas.ways(area, 18);
            expireEvents();
            assertEquals(1, state(MapTrace.snapshot(), area, 1));
            synchronized (atlas) {
                release.countDown();
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (state(MapTrace.snapshot(), area, 1) != 2 && System.nanoTime() < end)
                    Thread.sleep(5);
                expireEvents();
                assertEquals(
                        "importing can be exported while worker waits for shared monitor",
                        2,
                        state(MapTrace.snapshot(), area, 1));
                atlas.ways(area, 14);
                assertEquals(1, state(MapTrace.snapshot(), area, 2));
                save("importing-detail-queued-overview", MapTrace.snapshot());
            }
            ((ExecutorService) MapStressMotionTest.get(atlas, "loader"))
                    .submit(() -> {})
                    .get(5, TimeUnit.SECONDS);
            expireEvents();
            android.util.LruCache<?, ?> cache =
                    (android.util.LruCache<?, ?>) MapStressMotionTest.get(atlas, "geometry");
            int hits = cache.hitCount();
            String ready = MapTrace.snapshot();
            save("ready", ready);
            assertEquals(3, state(ready, area, 1));
            assertEquals(3, state(ready, area, 2));
            assertEquals("full source also supplies walls", 3, state(ready, area, 3));
            assertEquals("export does not touch LRU recency/hits", hits, cache.hitCount());
            cache.evictAll();
            expireEvents();
            String evicted = MapTrace.snapshot();
            save("evicted-missing", evicted);
            assertEquals("ready is not remembered after eviction", 4, state(evicted, area, 1));
            assertEquals(4, state(evicted, area, 2));
            assertFalse(evicted.contains(file.getName()));
            atlas.close();
            assertEquals(
                    "closed providers are not exported", -1, state(MapTrace.snapshot(), area, 1));
        } finally {
            release.countDown();
            fixture.cleanup();
        }
    }

    @Test
    public void wallVariantAndMissingFileHaveCurrentStates() throws Exception {
        MapMotionRegressionTest fixture = new MapMotionRegressionTest();
        fixture.setup();
        OfflineAtlas atlas = fixture.atlas;
        atlas.areas.clear();
        OfflineAtlas.Area area = fixture.addArea(-1, 1, null);
        File file = new File(RuntimeEnvironment.getApplication().getCacheDir(), "state-walls.json");
        Files.write(
                file.toPath(), MapReviewR2Test.scene().toString().getBytes(StandardCharsets.UTF_8));
        area.source = new NoUiStatFile(file);
        ExecutorService wallWorker = Executors.newSingleThreadExecutor();
        try {
            Future<?> walls;
            synchronized (atlas) {
                walls =
                        wallWorker.submit(
                                () -> atlas.buildingsNear(.00015, .00015, Visibility.RADIUS));
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (state(MapTrace.snapshot(), area, 3) != 2 && System.nanoTime() < end)
                    Thread.sleep(5);
                expireEvents();
                save("importing-walls", MapTrace.snapshot());
                assertEquals(2, state(MapTrace.snapshot(), area, 3));
                assertEquals(4, state(MapTrace.snapshot(), area, 1));
            }
            walls.get(5, TimeUnit.SECONDS);
            expireEvents();
            assertEquals(3, state(MapTrace.snapshot(), area, 3));
            save("ready-walls", MapTrace.snapshot());
            OfflineAtlas.Area absent = fixture.addArea(-1, 1, null);
            absent.source = new NoUiStatFile(new File(file.getParentFile(), "state-absent.json"));
            wallWorker.submit(() -> atlas.loadWays(absent, false)).get(5, TimeUnit.SECONDS);
            expireEvents();
            save("missing-file", MapTrace.snapshot());
            assertEquals(
                    "missing file is distinct from a malformed failed import",
                    4,
                    state(MapTrace.snapshot(), absent, 1));
        } finally {
            wallWorker.shutdownNow();
            fixture.cleanup();
        }
    }
}

package ru.main.explorer;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Latch стоят в настоящем lookup, открытом reader и потоковом импорте routeWorker. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ClearMaskRegressionTest {
    static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicBoolean armed = new AtomicBoolean(true);

        void pause() {
            if (Thread.currentThread().getName().equals("clear-mask")
                    && armed.compareAndSet(true, false)) {
                entered.countDown();
                DownloadedMapsTest.uninterruptibly(release);
            }
        }
    }

    static final class PausedFile extends File {
        final Gate gate;
        final boolean importing;

        PausedFile(File file, Gate gate, boolean importing) {
            super(file.getPath());
            this.gate = gate;
            this.importing = importing;
        }

        @Override
        public String getName() {
            if (importing) gate.pause(); // FileInputStream уже открыт внутри build.
            return super.getName();
        }

        @Override
        public long length() {
            if (!importing) gate.pause(); // Cursor уже держит file handle и storage lease.
            return super.length();
        }
    }

    static final class ScanAtlas extends OfflineAtlas {
        final Gate gate;
        final String stage;
        int checks;

        ScanAtlas(Gate gate, String stage) {
            super(RuntimeEnvironment.getApplication(), true, null);
            this.gate = gate;
            this.stage = stage;
        }

        @Override
        boolean wallRequestCancelled() {
            if (Thread.currentThread().getName().equals("clear-mask") && gate.armed.get()) {
                for (StackTraceElement call : new Throwable().getStackTrace()) {
                    if (call.getClassName().equals(AtlasDiskSource.class.getName())
                            && call.getMethodName()
                                    .equals(stage.equals("scan") ? "readWays" : "element")) {
                        // Третий record: water пропущена, первая стена уже прочитана.
                        // В import остановка после нескольких разобранных полей/координат.
                        if (++checks == (stage.equals("scan") ? 3 : 10)) gate.pause();
                        break;
                    }
                }
            }
            return super.wallRequestCancelled();
        }
    }

    static final class Worker extends ThreadPoolExecutor {
        final Gate queued;
        final BlockingQueue<RouteFrame> frames = new LinkedBlockingQueue<>();
        final AtomicReference<Throwable> escaped = new AtomicReference<>();
        RouteFrame active;

        Worker(Gate queued) {
            super(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(),
                    runnable -> {
                        Thread thread = new Thread(runnable, "clear-mask");
                        thread.setUncaughtExceptionHandler((worker, error) -> {});
                        return thread;
                    });
            this.queued = queued;
        }

        @Override
        protected void beforeExecute(Thread thread, Runnable task) {
            active = null;
            for (Field field : task.getClass().getDeclaredFields()) {
                if (field.getType() == RouteFrame.class) {
                    try {
                        field.setAccessible(true);
                        active = (RouteFrame) field.get(task);
                    } catch (ReflectiveOperationException error) {
                        throw new AssertionError(error);
                    }
                }
            }
            if (active != null && queued != null) queued.pause();
        }

        @Override
        protected void afterExecute(Runnable task, Throwable failure) {
            if (failure != null) escaped.compareAndSet(null, failure);
            if (active != null) frames.add(active);
        }

        RouteFrame finish() throws Exception {
            RouteFrame frame = frames.poll(10, TimeUnit.SECONDS);
            assertNotNull("routeWorker terminates", frame);
            return frame;
        }
    }

    static void set(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                type.getDeclaredField(name);
                break;
            } catch (NoSuchFieldException absent) {
                type = type.getSuperclass();
            }
        }
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    public void clearBeforeQueuedMaskStarts() throws Exception {
        run("queued");
    }

    @Test
    public void clearBeforeWallLease() throws Exception {
        run("lookup");
    }

    @Test
    public void clearWithOpenWallReader() throws Exception {
        run("reader");
    }

    @Test
    public void clearDuringWallImport() throws Exception {
        run("import");
    }

    @Test
    public void clearAfterFirstWallRecord() throws Exception {
        run("scan");
    }

    @Test
    public void clearAfterImportHasParsedCoordinates() throws Exception {
        run("parsing");
    }

    void run(String stage) throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        MapNetwork.setEnabled(app, false);
        MapMotionRegressionTest fixture = new MapMotionRegressionTest();
        fixture.setup();
        MapView map = fixture.map;
        Gate gate = new Gate();
        if (stage.equals("scan") || stage.equals("parsing")) {
            fixture.atlas.close();
            fixture.atlas = new ScanAtlas(gate, stage);
            set(map, "atlas", fixture.atlas);
        }
        OfflineAtlas atlas = fixture.atlas;
        atlas.areas.clear();
        File source = new File(app.getFilesDir(), "regions/mask-cancel.json");
        source.getParentFile().mkdirs();
        Files.write(
                source.toPath(),
                MapReviewR2Test.scene().toString().getBytes(StandardCharsets.UTF_8));
        OfflineAtlas.Area area = fixture.addArea(-1, 1, null);
        area.source = source;
        ((android.util.LruCache<?, ?>) MapStressMotionTest.get(atlas, "geometry")).resize(1);
        AtlasDiskSource disk = null;
        if (stage.equals("import")) area.source = new PausedFile(source, gate, true);
        else if (stage.equals("parsing")) {
            /* Холодный wallsOnly import через ScanAtlas. */
        } else if (!stage.equals("queued")) {
            OfflineAtlas.IndexedWays ways = (OfflineAtlas.IndexedWays) atlas.loadWays(area, false);
            disk = ways.disk;
            assertNotNull(disk);
            if (stage.equals("reader")) set(disk, "file", new PausedFile(disk.file, gate, false));
            else if (!stage.equals("scan")) {
                Map<String, OfflineAtlas.IndexedWays> previous =
                        (Map<String, OfflineAtlas.IndexedWays>)
                                MapStressMotionTest.get(atlas, "diskGeometry");
                ConcurrentHashMap<String, OfflineAtlas.IndexedWays> paused =
                        new ConcurrentHashMap<String, OfflineAtlas.IndexedWays>() {
                            @Override
                            public OfflineAtlas.IndexedWays get(Object key) {
                                OfflineAtlas.IndexedWays value = super.get(key);
                                if (value != null) gate.pause();
                                return value;
                            }
                        };
                paused.putAll(previous);
                set(atlas, "diskGeometry", paused);
            }
        }
        Worker worker = new Worker(stage.equals("queued") ? gate : null);
        ((ExecutorService) MapStressMotionTest.get(map, "routeWorker")).shutdown();
        set(map, "routeWorker", worker);
        RevealCache oldCache = (RevealCache) MapStressMotionTest.get(map, "revealDisk");
        try {
            map.update(new ArrayList<>(Collections.singletonList(new double[] {0, 0, 1, 1000})));
            map.requestFrame();
            assertTrue("reached " + stage, gate.entered.await(5, TimeUnit.SECONDS));
            if (!stage.equals("queued")) {
                assertNotNull(
                        "reveal transaction is active",
                        MapStressMotionTest.get(oldCache, "transaction"));
            }
            assertTrue(DownloadedMaps.requestClear(app));
            gate.release.countDown();
            RouteFrame stale = worker.finish();
            DownloadedMapsTest.finishClear();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull("normal cancellation must not escape worker", worker.escaped.get());
            assertFalse(
                    "cancelled last sample must not mark a partial mask complete", stale.complete);
            assertNull(
                    "transaction lease released", MapStressMotionTest.get(oldCache, "transaction"));
            assertNull("old SQLite closed by cleaner", MapStressMotionTest.get(oldCache, "db"));
            if (disk != null) {
                assertEquals(0, MapStressMotionTest.get(disk, "readers"));
                assertFalse(disk.file.exists());
            }
            assertTrue(DownloadedMaps.lastResult(app).success);
            assertTrue(((Set<?>) MapStressMotionTest.get(atlas, "importingGeometry")).isEmpty());
            assertTrue(((Set<?>) MapStressMotionTest.get(atlas, "failedGeometry")).isEmpty());
            assertTrue(((Map<?, ?>) MapStressMotionTest.get(atlas, "sourceOutcomes")).isEmpty());
            try {
                atlas.buildingsNear(0, 0, Visibility.RADIUS);
                fail("retired atlas must cancel, never return successful empty walls");
            } catch (CancellationException expected) {
            }
            assertNull(MapStressMotionTest.get(map, "readyFrame"));
            assertTrue(((Collection<?>) MapStressMotionTest.get(map, "frameHistory")).isEmpty());
            assertEquals(0, map.routeFramesCompleted);
            assertEquals(1, map.routeFramesCancelled);
            assertFalse((Boolean) MapStressMotionTest.get(map, "rendering"));
            // На том же живом MapView/worker новое поколение реально строит и публикует маску.
            for (int i = 0; i < 8; i++) {
                ((ExecutorService) MapStressMotionTest.get(map, "mapWorker"))
                        .submit(() -> {})
                        .get(5, TimeUnit.SECONDS);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                if (!(Boolean) MapStressMotionTest.get(map, "indexing")) break;
            }
            map.requestFrame();
            RouteFrame fresh = worker.finish();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(fresh.complete);
            assertFalse(fresh.cancelled.getAsBoolean());
            assertSame(fresh, MapStressMotionTest.get(map, "readyFrame"));
            assertEquals(1, map.routeFramesCompleted);
            assertTrue(fresh.maskVertices > 0);
            RevealCache freshCache = (RevealCache) MapStressMotionTest.get(map, "revealDisk");
            assertNotSame(oldCache, freshCache);
            assertNull(MapStressMotionTest.get(freshCache, "transaction"));
            assertFalse(
                    ((SQLiteDatabase) MapStressMotionTest.get(freshCache, "db")).inTransaction());
        } finally {
            gate.release.countDown();
            fixture.cleanup();
            worker.awaitTermination(10, TimeUnit.SECONDS);
            DownloadedMapsTest.finishClear();
        }
    }

    @Test
    public void cancelledFrameRollsBackWrittenShapes() throws Exception {
        RevealCache cache = new RevealCache(RuntimeEnvironment.getApplication());
        RouteFrame frame =
                new RouteFrame(
                        128,
                        128,
                        18,
                        512,
                        512,
                        1,
                        new ArrayList<>(Collections.singletonList(new double[] {0, 0, 1, 1000})),
                        null,
                        null);
        frame.diskCache = cache;
        frame.cancelled =
                () -> {
                    if (frame.maskVertices == 0) return false;
                    cache.write("probe", new double[] {1, 2});
                    return true;
                };
        try {
            frame.build();
        } catch (CancellationException expected) {
            // В production этот сигнал принимает routeWorker.
        } finally {
            try {
                assertFalse(frame.complete);
                assertNull(MapStressMotionTest.get(cache, "transaction"));
                assertNull("cancelled transaction rolls back writes", cache.read("probe"));
            } finally {
                cache.close();
            }
        }
    }

    @Test
    public void genuineWallReadFailureDoesNotBecomeCancellationOrEmptySuccess() throws Exception {
        MapMotionRegressionTest fixture = new MapMotionRegressionTest();
        fixture.setup();
        try {
            fixture.atlas.areas.clear();
            File source =
                    new File(RuntimeEnvironment.getApplication().getFilesDir(), "wall-error.json");
            Files.write(
                    source.toPath(),
                    MapReviewR2Test.scene().toString().getBytes(StandardCharsets.UTF_8));
            OfflineAtlas.Area area = fixture.addArea(-1, 1, null);
            area.source = source;
            ((android.util.LruCache<?, ?>) MapStressMotionTest.get(fixture.atlas, "geometry"))
                    .resize(1);
            OfflineAtlas.IndexedWays ways =
                    (OfflineAtlas.IndexedWays) fixture.atlas.loadWays(area, false);
            assertNotNull(ways.disk);
            Files.write(ways.disk.file.toPath(), new byte[8]);
            try {
                fixture.atlas.buildingsNear(0, 0, Visibility.RADIUS);
                fail("corrupted wall records must not be successful empty walls");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getCause() instanceof IOException);
            }
        } finally {
            fixture.cleanup();
        }
    }
}

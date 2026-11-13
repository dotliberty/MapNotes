package ru.main.explorer;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.*;
import android.location.Location;
import java.io.*;
import java.net.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Остановка на входе в завершение — перед publish-lock, не внутри Set.remove. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ClearFinallyRegressionTest {
    static final class ControlledMap extends MapView {
        final CountDownLatch oldFinally = new CountDownLatch(1),
                releaseOld = new CountDownLatch(1),
                oldFinished = new CountDownLatch(1);
        final CountDownLatch freshFetch = new CountDownLatch(1),
                releaseFresh = new CountDownLatch(1);
        final AtomicBoolean first = new AtomicBoolean(true);
        volatile boolean slow, buildings;

        ControlledMap(Context context) {
            super(context, true);
        }

        void pauseOld() {
            oldFinally.countDown();
            DownloadedMapsTest.uninterruptibly(releaseOld);
        }

        @Override
        void finishTileRequest(String key, long expected) {
            if (!buildings && first.compareAndSet(true, false)) {
                pauseOld();
                try {
                    super.finishTileRequest(key, expected);
                } finally {
                    oldFinished.countDown();
                }
            } else super.finishTileRequest(key, expected);
        }

        @Override
        void finishBuildingRequest(long expected) {
            if (buildings && first.compareAndSet(true, false)) {
                pauseOld();
                try {
                    super.finishBuildingRequest(expected);
                } finally {
                    oldFinished.countDown();
                }
            } else super.finishBuildingRequest(expected);
        }

        void transport() {
            if (slow) {
                freshFetch.countDown();
                DownloadedMapsTest.uninterruptibly(releaseFresh);
            }
        }

        @Override
        void fetchBuildings(double lat, double lon, long expected) {
            transport();
        }

        @Override
        HttpURLConnection mapConnection(String address) throws IOException {
            return new HttpURLConnection(new URL(address)) {
                public void connect() {}

                public void disconnect() {}

                public boolean usingProxy() {
                    return false;
                }

                public int getResponseCode() {
                    return 200;
                }

                public InputStream getInputStream() {
                    transport();
                    return new ByteArrayInputStream(DownloadedMapsTest.image());
                }
            };
        }
    }

    void tileRace(boolean satellite) throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        MapNetwork.setEnabled(app, true);
        ControlledMap map = new ControlledMap(app);
        map.layout(0, 0, 64, 64);
        map.zoom = 14;
        map.cx = map.cy = 128 + 128.0 / (1 << 14);
        map.online = true;
        String key = (satellite ? "sat/" : "") + "14/8192/8192";
        Set<?> pending = (Set<?>) MapStressMotionTest.get(map, "pending");
        ((Set<String>) pending).add(key);
        ExecutorService old = Executors.newSingleThreadExecutor();
        Future<?> oldLoad =
                old.submit(
                        () -> {
                            if (satellite) map.loadSatellite(key, 14, 8192, 8192);
                            else map.loadTile(key, 14, 8192, 8192);
                        });
        Bitmap frame = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        try {
            assertTrue(map.oldFinally.await(5, TimeUnit.SECONDS));
            assertTrue(DownloadedMaps.requestClear(app));
            DownloadedMapsTest.finishClear();
            DownloadedMapsTest.settle(map);
            assertTrue(DownloadedMaps.lastResult(app).success);
            assertFalse(pending.contains(key));
            MapNetwork.setEnabled(app, true);
            map.online = true;
            map.slow = true;
            map.drawTiles(new Canvas(frame), satellite);
            assertTrue(map.freshFetch.await(5, TimeUnit.SECONDS));
            assertTrue(pending.contains(key));
            map.releaseOld.countDown();
            oldLoad.get(5, TimeUnit.SECONDS);
            System.out.println(
                    "FINALLY "
                            + (satellite ? "satellite" : "raster")
                            + " old complete, new pending="
                            + pending.contains(key));
            assertTrue("retired finally must leave the new request pending", pending.contains(key));
            map.releaseFresh.countDown();
            ExecutorService pool =
                    (ExecutorService)
                            MapStressMotionTest.get(map, satellite ? "satellitePool" : "pool");
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            assertFalse("current worker releases its own slot", pending.contains(key));
        } finally {
            map.releaseOld.countDown();
            map.releaseFresh.countDown();
            old.shutdownNow();
            map.close();
            frame.recycle();
        }
    }

    @Test
    public void oldRasterFinallyKeepsFreshPending() throws Exception {
        tileRace(false);
    }

    @Test
    public void oldSatelliteFinallyKeepsFreshPending() throws Exception {
        tileRace(true);
    }

    @Test
    public void oldBuildingFinallyKeepsFreshFetchFlag() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        MapNetwork.setEnabled(app, true);
        ControlledMap map = new ControlledMap(app);
        map.buildings = true;
        map.online = true;
        try {
            Location location = new Location("gps");
            location.setLatitude(0);
            location.setLongitude(0);
            location.setAccuracy(5);
            location.setTime(System.currentTimeMillis());
            map.setCurrent(location, true);
            assertTrue(map.oldFinally.await(5, TimeUnit.SECONDS));
            assertTrue(DownloadedMaps.requestClear(app));
            DownloadedMapsTest.finishClear();
            DownloadedMapsTest.settle(map);
            assertFalse((Boolean) MapStressMotionTest.get(map, "fetchingBuildings"));
            MapNetwork.setEnabled(app, true);
            map.online = true;
            map.slow = true;
            map.ensureBuildingData();
            assertTrue(map.freshFetch.await(5, TimeUnit.SECONDS));
            map.releaseOld.countDown();
            assertTrue(map.oldFinished.await(5, TimeUnit.SECONDS));
            boolean fetching = (Boolean) MapStressMotionTest.get(map, "fetchingBuildings");
            System.out.println("FINALLY buildings old complete, new fetching=" + fetching);
            assertTrue("retired finally must not clear a current building fetch", fetching);
            map.releaseFresh.countDown();
            ExecutorService pool = (ExecutorService) MapStressMotionTest.get(map, "pool");
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            assertFalse((Boolean) MapStressMotionTest.get(map, "fetchingBuildings"));
        } finally {
            map.releaseOld.countDown();
            map.releaseFresh.countDown();
            map.close();
        }
    }
}

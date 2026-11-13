package ru.main.explorer;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.location.Location;
import android.net.Uri;
import android.os.Looper;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "ru-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DownloadedMapsTest {
    Context app;

    @Before
    public void setup() throws Exception {
        app = RuntimeEnvironment.getApplication();
        DownloadedMaps.awaitIdle();
        DownloadedMaps.dismissResult(app);
        MapNetwork.setEnabled(app, false);
    }

    static File put(File root, String name) throws Exception {
        File file = new File(root, name);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), ("synthetic:" + name).getBytes(StandardCharsets.UTF_8));
        return file;
    }

    static void idleUi() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    static void finishClear() throws Exception {
        DownloadedMaps.awaitIdle();
        idleUi();
    }

    static void settle(MapView map) throws Exception {
        for (int i = 0; i < 3; i++) {
            ((ExecutorService) MapStressMotionTest.get(map, "mapWorker"))
                    .submit(() -> {})
                    .get(10, TimeUnit.SECONDS);
            idleUi();
        }
    }

    static void uninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true)
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @Test
    public void emptyCleanupAndAllOwnedFilesPreserveUserData() throws Exception {
        assertTrue(DownloadedMaps.requestClear(app));
        finishClear();
        assertTrue(DownloadedMaps.lastResult(app).success);
        ArrayList<File> deleted = new ArrayList<>(), kept = new ArrayList<>();
        for (String name :
                new String[] {
                    "packs/bad/pack.json",
                    "packs/unfinished/data.part",
                    "regions/old.json",
                    "visibility/buildings.part",
                    "satellite/12_1_2.jpg",
                    "packs.part"
                }) deleted.add(put(app.getFilesDir(), name));
        for (String name :
                new String[] {
                    "sat-12_1_2.jpg",
                    "12_1_2.png",
                    "12_1_2.png.part",
                    "atlas-ab.geometry",
                    "atlas-ab.points",
                    "atlas-ab.pass",
                    "reveal-v1.db-journal"
                }) deleted.add(put(app.getCacheDir(), name));
        OfflineAtlas corruptOnly = new OfflineAtlas(app, true, null);
        assertEquals(
                "corrupt/orphan files need no valid manifest count", 0, corruptOnly.regionCount());
        corruptOnly.close();
        for (int i = 0; i < 2; i++) {
            File pack = new File(app.getFilesDir(), "packs/overlapping-" + i);
            JSONArray bounds = new JSONArray(new double[] {-1, -1, 1, 1});
            MapStressMotionTest.write(
                    new File(pack, "pack.json"),
                    new JSONObject().put("bounds", bounds).put("complete", true),
                    false);
            MapStressMotionTest.write(
                    new File(pack, "cell.json.gz"), MapReviewR2Test.scene(), true);
            MapStressMotionTest.write(
                    new File(pack, "cell.meta"),
                    new JSONObject().put("bounds", bounds).put("file", "cell.json.gz"),
                    false);
            deleted.add(new File(pack, "pack.json"));
            deleted.add(new File(pack, "cell.json.gz"));
            deleted.add(new File(pack, "cell.meta"));
        }
        OfflineAtlas complete = new OfflineAtlas(app, true, null);
        assertEquals(2, complete.regionCount());
        complete.close();
        for (String name :
                new String[] {
                    "photos/keep.jpg",
                    "world-copy.mbtiles",
                    "world-import-keep.part",
                    "offline.mbtiles"
                }) kept.add(put(app.getFilesDir(), name));
        for (String name : new String[] {"unrelated-cache", "photo-thumb.png"})
            kept.add(put(app.getCacheDir(), name));
        byte[][] originals = new byte[kept.size()][];
        for (int i = 0; i < kept.size(); i++)
            originals[i] = Files.readAllBytes(kept.get(i).toPath());
        app.getSharedPreferences("world-map", 0)
                .edit()
                .putString("uri", "content://synthetic/external")
                .putBoolean("active", true)
                .commit();
        app.getSharedPreferences("map-view", 0)
                .edit()
                .putBoolean("online", true)
                .putString("keep", "settings")
                .commit();
        app.getSharedPreferences("walk-state", 0).edit().putString("progress", "keep").commit();
        MapDownloadService.preferences(app)
                .edit()
                .putBoolean("pending", true)
                .putString("status_key", "starting_download")
                .commit();
        try (Store store = new Store(app);
                SpawnZoneStore zones = new SpawnZoneStore(app)) {
            long walk = store.startWalk(1000), session = store.newWalkSession(walk);
            Location fix = new Location("gps");
            fix.setLatitude(0);
            fix.setLongitude(0);
            fix.setTime(2000);
            fix.setAccuracy(5);
            store.add(fix, session);
            long photo = store.addPhoto(kept.get(0).getPath(), fix, 2000);
            store.saveNote(photo, "synthetic note");
            zones.save(new SpawnZone(0, "synthetic zone", 0, 0, 20));
            assertTrue(DownloadedMaps.requestClear(app));
            finishClear();
            assertTrue(DownloadedMaps.lastResult(app).success);
            for (File file : deleted) assertFalse(file.getPath(), file.exists());
            for (int i = 0; i < kept.size(); i++)
                assertArrayEquals(originals[i], Files.readAllBytes(kept.get(i).toPath()));
            assertEquals(1, store.points().size());
            assertEquals(walk, store.openWalk().id);
            assertEquals("synthetic note", store.photo(photo).note);
            assertEquals(1, zones.list().size());
        }
        assertFalse(MapDownloadService.hasPending(app));
        assertEquals("", MapDownloadService.currentStatus(app));
        assertFalse(MapNetwork.enabled(app));
        assertEquals("settings", app.getSharedPreferences("map-view", 0).getString("keep", ""));
        assertEquals("keep", app.getSharedPreferences("walk-state", 0).getString("progress", ""));
        assertEquals(
                "content://synthetic/external",
                app.getSharedPreferences("world-map", 0).getString("uri", ""));
    }

    @Test
    public void confirmationCancelAndRecreationWhileCleanupRuns() throws Exception {
        File data = put(app.getFilesDir(), "packs/broken/invalid.part");
        ActivityController<MainActivity> first =
                Robolectric.buildActivity(MainActivity.class).setup();
        first.get().confirmClearDownloadedMaps();
        AlertDialog confirm = ShadowAlertDialog.getLatestAlertDialog();
        confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertTrue(data.exists());
        assertFalse(DownloadedMaps.clearing);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService writer = Executors.newSingleThreadExecutor();
        long epoch = DownloadedMaps.generation();
        Future<?> held =
                writer.submit(
                        () -> {
                            try (DownloadedMaps.Lease ignored = DownloadedMaps.enter(epoch)) {
                                entered.countDown();
                                uninterruptibly(release);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
        ActivityController<MainActivity> second = null;
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            first.get().confirmClearDownloadedMaps();
            ShadowAlertDialog.getLatestAlertDialog()
                    .getButton(AlertDialog.BUTTON_POSITIVE)
                    .performClick();
            idleUi();
            assertTrue(DownloadedMaps.clearing);
            assertFalse("second operation is rejected", DownloadedMaps.requestClear(app));
            first.pause().stop().destroy();
            first = null;
            second = Robolectric.buildActivity(MainActivity.class).setup();
            idleUi();
            assertFalse(
                    ((android.widget.Button) MapStressMotionTest.get(second.get(), "offlineButton"))
                            .isEnabled());
            second.get().menu();
            assertEquals(
                    second.get().getString(R.string.clear_maps_title),
                    ShadowAlertDialog.getLatestAlertDialog()
                            .getListView()
                            .getAdapter()
                            .getItem(13));
            release.countDown();
            held.get(5, TimeUnit.SECONDS);
            finishClear();
            assertTrue(DownloadedMaps.lastResult(app).success);
            assertFalse(data.exists());
            assertTrue(
                    ((android.widget.Button) MapStressMotionTest.get(second.get(), "offlineButton"))
                            .isEnabled());
        } finally {
            release.countDown();
            writer.shutdownNow();
            finishClear();
            if (first != null) first.pause().stop().destroy();
            if (second != null) second.pause().stop().destroy();
        }
    }

    @Test
    public void deletionFailureIsHonestAndRetryWorks() throws Exception {
        File stuck = put(app.getCacheDir(), "sat-failed.jpg");
        AtomicBoolean fail = new AtomicBoolean(true);
        Context wrapped =
                new ContextWrapper(app) {
                    @Override
                    public Context getApplicationContext() {
                        return this;
                    }

                    @Override
                    public File getCacheDir() {
                        return new File(super.getCacheDir().getPath()) {
                            @Override
                            public File[] listFiles() {
                                File[] files = super.listFiles();
                                for (int i = 0; files != null && i < files.length; i++)
                                    if (files[i].equals(stuck))
                                        files[i] =
                                                new File(stuck.getPath()) {
                                                    @Override
                                                    public boolean delete() {
                                                        return !fail.get() && super.delete();
                                                    }
                                                };
                                return files;
                            }
                        };
                    }
                };
        assertTrue(DownloadedMaps.requestClear(wrapped));
        finishClear();
        assertFalse(DownloadedMaps.lastResult(wrapped).success);
        assertTrue(stuck.exists());
        assertTrue(app.getSharedPreferences("downloaded-maps", 0).getBoolean("incomplete", false));
        java.lang.reflect.Field memoryResult = DownloadedMaps.class.getDeclaredField("result");
        memoryResult.setAccessible(true);
        memoryResult.set(null, null);
        assertFalse(
                "incomplete cleanup is restored from persisted state",
                DownloadedMaps.lastResult(wrapped).success);
        fail.set(false);
        assertTrue(DownloadedMaps.requestClear(wrapped));
        finishClear();
        assertTrue(DownloadedMaps.lastResult(wrapped).success);
        assertFalse(stuck.exists());
        assertFalse(app.getSharedPreferences("downloaded-maps", 0).getBoolean("incomplete", true));
    }

    public static class BlockedService extends MapDownloadService {
        final CountDownLatch fetched = new CountDownLatch(1), release = new CountDownLatch(1);

        @Override
        byte[] fetch(String url, String body, int limit) {
            fetched.countDown();
            uninterruptibly(release);
            return "{\"elements\":[]}".getBytes(StandardCharsets.UTF_8);
        }
    }

    @Test
    public void retiredDownloadCannotRestoreFilesPendingOrStatusAndRedownloadWorks()
            throws Exception {
        ServiceController<BlockedService> controller =
                Robolectric.buildService(BlockedService.class).create();
        BlockedService service = controller.get();
        Intent request =
                new Intent(app, BlockedService.class)
                        .putExtra("lat", 0d)
                        .putExtra("lon", 0d)
                        .putExtra("radius", 500d)
                        .putExtra("satellite", false);
        try {
            service.onStartCommand(request, 0, 1);
            assertTrue(service.fetched.await(5, TimeUnit.SECONDS));
            Thread worker = (Thread) MapStressMotionTest.get(service, "worker");
            assertTrue(MapDownloadService.hasPending(app));
            assertTrue(DownloadedMaps.requestClear(app));
            finishClear();
            assertFalse(new File(app.getFilesDir(), "packs").exists());
            service.release.countDown();
            worker.join(10000);
            assertFalse(worker.isAlive());
            idleUi();
            assertFalse(MapDownloadService.hasPending(app));
            assertEquals("", MapDownloadService.currentStatus(app));
            assertFalse(MapDownloadService.running);
            assertFalse(new File(app.getFilesDir(), "packs").exists());
            MapNetwork.setEnabled(app, true);
            service.onStartCommand(request, 0, 2);
            Thread fresh = (Thread) MapStressMotionTest.get(service, "worker");
            fresh.join(10000);
            assertFalse(fresh.isAlive());
            assertFalse(MapDownloadService.hasPending(app));
            assertTrue(
                    new File(
                                    new File(
                                            new File(app.getFilesDir(), "packs"),
                                            new RegionPlan(0, 0, 500).key()),
                                    "pack.json")
                            .isFile());
        } finally {
            service.release.countDown();
            controller.destroy();
        }
    }

    static byte[] image() {
        Bitmap bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff123456);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
        bitmap.recycle();
        return bytes.toByteArray();
    }

    static final class SlowTiles extends MapView {
        final CountDownLatch fetched = new CountDownLatch(1), release = new CountDownLatch(1);
        final byte[] image = image();

        SlowTiles(Context context) {
            super(context, true);
        }

        @Override
        HttpURLConnection mapConnection(String address) throws IOException {
            return new HttpURLConnection(new URL(address)) {
                public void connect() {}

                public void disconnect() {}

                public boolean usingProxy() {
                    return false;
                }

                @Override
                public int getResponseCode() {
                    return 200;
                }

                @Override
                public InputStream getInputStream() {
                    fetched.countDown();
                    uninterruptibly(release);
                    return new ByteArrayInputStream(image);
                }
            };
        }
    }

    @Test
    public void lateSatelliteAndRasterWorkersCannotRefillFilesOrMemory() throws Exception {
        for (boolean satellite : new boolean[] {false, true}) {
            MapNetwork.setEnabled(app, true);
            SlowTiles map = new SlowTiles(app);
            map.online = true;
            map.layout(0, 0, 64, 64);
            map.zoom = 14;
            map.cx = map.cy = 128;
            Bitmap frame = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            try {
                map.drawTiles(new Canvas(frame), satellite);
                assertTrue(map.fetched.await(5, TimeUnit.SECONDS));
                assertTrue(DownloadedMaps.requestClear(app));
                finishClear();
                settle(map);
                map.release.countDown();
                for (String name : new String[] {"pool", "satellitePool"}) {
                    ExecutorService pool = (ExecutorService) MapStressMotionTest.get(map, name);
                    pool.shutdown();
                    assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
                }
                idleUi();
                assertEquals(
                        0,
                        ((android.util.LruCache<?, ?>) MapStressMotionTest.get(map, "tiles"))
                                .size());
                for (String name : new String[] {"pending", "failed"})
                    assertTrue(((Set<?>) MapStressMotionTest.get(map, name)).isEmpty());
                assertTrue(
                        ((Map<?, ?>) MapStressMotionTest.get(map, "satelliteRetryAt")).isEmpty());
                assertFalse(new File(app.getFilesDir(), "satellite").exists());
                for (File file : app.getCacheDir().listFiles())
                    assertFalse(
                            file.getName(),
                            file.getName().matches("(?:sat-.*|[0-9]+_[0-9]+_[0-9]+\\.png)"));
            } finally {
                map.release.countDown();
                map.close();
                frame.recycle();
            }
        }
    }

    @Test
    public void staleAtlasCallbacksAndSeveralViewsCannotRestoreRegions() throws Exception {
        File pack = new File(app.getFilesDir(), "packs/callback");
        MapStressMotionTest.write(
                new File(pack, "pack.json"),
                new JSONObject()
                        .put("bounds", new JSONArray(new double[] {-1, -1, 1, 1}))
                        .put("complete", true),
                false);
        MapStressMotionTest.write(new File(pack, "cell.json.gz"), MapReviewR2Test.scene(), true);
        MapStressMotionTest.write(
                new File(pack, "cell.meta"),
                new JSONObject()
                        .put("bounds", new JSONArray(new double[] {-1, -1, 1, 1}))
                        .put("file", "cell.json.gz"),
                false);
        MapView one = new MapView(app, true), two = new MapView(app, true);
        try {
            java.lang.reflect.Field fetching = MapView.class.getDeclaredField("fetchingBuildings");
            fetching.setAccessible(true);
            fetching.setBoolean(one, true);
            fetching.setBoolean(two, true);
            // Конструкторы закончили индекс, но их UI callbacks ещё не исполнены.
            for (MapView view : new MapView[] {one, two})
                ((ExecutorService) MapStressMotionTest.get(view, "mapWorker"))
                        .submit(() -> {})
                        .get(5, TimeUnit.SECONDS);
            assertTrue(DownloadedMaps.requestClear(app));
            finishClear();
            settle(one);
            settle(two);
            assertEquals(0, one.savedRegionCount());
            assertEquals(0, two.savedRegionCount());
            assertFalse(fetching.getBoolean(one));
            assertFalse(fetching.getBoolean(two));
            assertFalse(one.online);
            assertFalse(two.online);
        } finally {
            one.close();
            two.close();
        }
    }

    @Test
    public void connectedWorldAndImportedOfflineDatabaseRemainUsable() throws Exception {
        WorldMapTest helper = new WorldMapTest();
        helper.setup();
        File worldFile = helper.database(WorldMapTest.synthetic(), 14, 8192, 8192, false);
        app.getSharedPreferences("world-map", 0)
                .edit()
                .putString("uri", Uri.fromFile(worldFile).toString())
                .commit();
        MapView map = new MapView(app, true);
        try {
            settle(map);
            assertTrue(map.hasOffline());
            byte[] original = Files.readAllBytes(worldFile.toPath());
            assertTrue(DownloadedMaps.requestClear(app));
            finishClear();
            settle(map);
            assertTrue(map.hasOffline());
            assertArrayEquals(original, Files.readAllBytes(worldFile.toPath()));
            assertEquals(
                    Uri.fromFile(worldFile).toString(),
                    app.getSharedPreferences("world-map", 0).getString("uri", ""));
        } finally {
            map.close();
            helper.cleanup();
        }
    }

    @Test
    public void activeDerivedImportWaitsOffUiAndCannotPublishAfterClear() throws Exception {
        MapMotionRegressionTest fixture = new MapMotionRegressionTest();
        fixture.setup();
        OfflineAtlas atlas = fixture.atlas;
        atlas.areas.clear();
        File source = new File(app.getFilesDir(), "regions/importing.json");
        source.getParentFile().mkdirs();
        Files.write(
                source.toPath(),
                MapReviewR2Test.scene().toString().getBytes(StandardCharsets.UTF_8));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        OfflineAtlas.Area area = fixture.addArea(-1, 1, null);
        area.source =
                new File(source.getPath()) {
                    @Override
                    public String getName() {
                        entered.countDown();
                        uninterruptibly(release);
                        return super.getName();
                    }
                };
        Future<?> importing =
                ((ExecutorService) MapStressMotionTest.get(atlas, "loader"))
                        .submit(() -> atlas.loadWays(area, false));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            long before = DownloadedMaps.generation();
            assertTrue(
                    "clear returns to UI while existing file IO is blocked",
                    DownloadedMaps.requestClear(app));
            assertEquals(before + 1, DownloadedMaps.generation());
            idleUi();
            assertTrue(DownloadedMaps.clearing);
            assertFalse(DownloadedMaps.requestClear(app));
            release.countDown();
            importing.get(10, TimeUnit.SECONDS);
            finishClear();
            settle(fixture.map);
            assertTrue(DownloadedMaps.lastResult(app).success);
            assertFalse(source.exists());
            assertEquals(0, atlas.geometryLoads);
            assertEquals(0, fixture.map.savedRegionCount());
            for (File file : app.getCacheDir().listFiles())
                assertFalse(file.getName(), file.getName().startsWith("atlas-"));
        } finally {
            release.countDown();
            finishClear();
            fixture.cleanup();
        }
    }

    @Test
    public void automaticBuildingFetchCannotRecreateVisibilityAfterClear() throws Exception {
        MapNetwork.setEnabled(app, true);
        ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<?> response =
                workers.submit(
                        () -> {
                            try (Socket socket = server.accept()) {
                                socket.setSoTimeout(5000);
                                InputStream in = socket.getInputStream();
                                ByteArrayOutputStream header = new ByteArrayOutputStream();
                                while (!header.toString("US-ASCII").endsWith("\r\n\r\n")) {
                                    int b = in.read();
                                    if (b < 0) throw new EOFException();
                                    header.write(b);
                                }
                                int length = 0;
                                for (String line : header.toString("US-ASCII").split("\r\n"))
                                    if (line.toLowerCase(Locale.ROOT).startsWith("content-length:"))
                                        length =
                                                Integer.parseInt(
                                                        line.substring(line.indexOf(':') + 1)
                                                                .trim());
                                new DataInputStream(in).readFully(new byte[length]);
                                entered.countDown();
                                uninterruptibly(release);
                                byte[] json = "{\"elements\":[]}".getBytes(StandardCharsets.UTF_8);
                                socket.getOutputStream()
                                        .write(
                                                ("HTTP/1.1 200 OK\r\nContent-Length: "
                                                                + json.length
                                                                + "\r\nConnection: close\r\n\r\n")
                                                        .getBytes(StandardCharsets.US_ASCII));
                                socket.getOutputStream().write(json);
                            } catch (IOException expectedDisconnect) {
                            }
                        });
        Set<HttpURLConnection> connections = Collections.synchronizedSet(new HashSet<>());
        Future<?> fetching =
                workers.submit(
                        () -> {
                            try {
                                BuildingFetch.fetchAt(
                                        app,
                                        0,
                                        0,
                                        "http://127.0.0.1:" + server.getLocalPort(),
                                        connections,
                                        () -> true);
                            } catch (Exception expectedCancellation) {
                            }
                        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(DownloadedMaps.requestClear(app));
            finishClear();
            release.countDown();
            fetching.get(10, TimeUnit.SECONDS);
            response.get(10, TimeUnit.SECONDS);
            assertFalse(new File(app.getFilesDir(), "visibility").exists());
            assertTrue(connections.isEmpty());
        } finally {
            release.countDown();
            server.close();
            workers.shutdownNow();
        }
    }

    @Test
    public void retiredRevealCachesCannotRecreateDeletedGeometryCache() throws Exception {
        RevealCache first = new RevealCache(app), second = new RevealCache(app);
        try {
            first.write("synthetic", new double[] {1, 2});
            assertArrayEquals(new double[] {1, 2}, second.read("synthetic"), 0);
            assertTrue(DownloadedMaps.requestClear(app));
            finishClear();
            File database = new File(app.getCacheDir(), "reveal-v1.db");
            assertFalse(database.exists());
            first.write("late", new double[] {3, 4});
            second.write("late", new double[] {5, 6});
            assertNull(first.read("synthetic"));
            assertFalse(database.exists());
            try (RevealCache current = new RevealCache(app)) {
                current.write("new", new double[] {7, 8});
                assertArrayEquals(new double[] {7, 8}, current.read("new"), 0);
            }
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    public void downloadStatusCommitFailureIsReportedAndCanBeRetried() throws Exception {
        MapDownloadService.preferences(app).edit().putBoolean("pending", true).commit();
        AtomicBoolean fail = new AtomicBoolean(true);
        Context context =
                new ContextWrapper(app) {
                    @Override
                    public Context getApplicationContext() {
                        return this;
                    }

                    @Override
                    public android.content.SharedPreferences getSharedPreferences(
                            String name, int mode) {
                        android.content.SharedPreferences real =
                                super.getSharedPreferences(name, mode);
                        if (!name.equals("map-download")) return real;
                        return (android.content.SharedPreferences)
                                java.lang.reflect.Proxy.newProxyInstance(
                                        getClassLoader(),
                                        new Class<?>[] {android.content.SharedPreferences.class},
                                        (proxy, method, args) -> {
                                            if (!method.getName().equals("edit"))
                                                return method.invoke(real, args);
                                            android.content.SharedPreferences.Editor edit =
                                                    real.edit();
                                            return java.lang.reflect.Proxy.newProxyInstance(
                                                    getClassLoader(),
                                                    new Class<?>[] {
                                                        android.content.SharedPreferences.Editor
                                                                .class
                                                    },
                                                    (editorProxy, editorMethod, editorArgs) -> {
                                                        if (editorMethod.getName().equals("commit")
                                                                && fail.get()) return false;
                                                        Object value =
                                                                editorMethod.invoke(
                                                                        edit, editorArgs);
                                                        return value
                                                                        instanceof
                                                                        android.content
                                                                                .SharedPreferences
                                                                                .Editor
                                                                ? editorProxy
                                                                : value;
                                                    });
                                        });
                    }
                };
        assertTrue(DownloadedMaps.requestClear(context));
        finishClear();
        assertFalse(DownloadedMaps.lastResult(context).success);
        assertFalse(DownloadedMaps.clearing);
        assertTrue(MapDownloadService.hasPending(app));
        fail.set(false);
        assertTrue(DownloadedMaps.requestClear(context));
        finishClear();
        assertTrue(DownloadedMaps.lastResult(context).success);
        assertFalse(MapDownloadService.hasPending(app));
    }

    @Test
    public void activeDiskCursorIsReleasedBeforeDerivedFilesAreDeleted() throws Exception {
        MapMotionRegressionTest fixture = new MapMotionRegressionTest();
        fixture.setup();
        OfflineAtlas atlas = fixture.atlas;
        atlas.areas.clear();
        File file = new File(app.getFilesDir(), "regions/cursor.json");
        file.getParentFile().mkdirs();
        Files.write(
                file.toPath(), MapReviewR2Test.scene().toString().getBytes(StandardCharsets.UTF_8));
        OfflineAtlas.Area area = fixture.addArea(-1, 1, null);
        area.source = file;
        ((android.util.LruCache<?, ?>) MapStressMotionTest.get(atlas, "geometry")).resize(1);
        OfflineAtlas.IndexedWays ways = (OfflineAtlas.IndexedWays) atlas.loadWays(area, false);
        CountDownLatch opened = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService reader = Executors.newSingleThreadExecutor();
        Future<?> reading =
                reader.submit(
                        () -> {
                            try (AtlasDiskSource.Cursor cursor =
                                    ways.disk.open(128, 128, 1, 1, 15)) {
                                assertNotNull(cursor.next(() -> false));
                                opened.countDown();
                                uninterruptibly(release);
                            } catch (IOException failure) {
                                throw new RuntimeException(failure);
                            }
                        });
        try {
            assertTrue(opened.await(5, TimeUnit.SECONDS));
            assertTrue(DownloadedMaps.requestClear(app));
            idleUi();
            assertTrue(DownloadedMaps.clearing);
            release.countDown();
            reading.get(10, TimeUnit.SECONDS);
            finishClear();
            assertTrue(DownloadedMaps.lastResult(app).success);
            assertFalse(ways.disk.file.exists());
        } finally {
            release.countDown();
            reader.shutdownNow();
            finishClear();
            fixture.cleanup();
        }
    }
}

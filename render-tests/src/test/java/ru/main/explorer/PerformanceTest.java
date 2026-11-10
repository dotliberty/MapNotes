package ru.main.explorer;

import android.app.Application;
import android.graphics.*;
import android.os.Looper;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PerformanceTest {
    @Test
    public void productionRasterStillHonoursWallsAndDoesNotLeakWhenPanned() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        MapView map = new MapView(app, true);
        map.online = false;
        map.layout(0, 0, 512, 512);
        File file = new File(new File(app.getFilesDir(), "regions"), "raster-wall.json");
        file.getParentFile().mkdirs();
        try {
            barrier(map, "mapWorker");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            double lat = 55.748, lon = 37.612, kx = 111195.08 * Math.cos(Math.toRadians(lat));
            JSONArray geometry = new JSONArray();
            for (double[] xy : new double[][] {{2, -4}, {3, -4}, {3, 4}, {2, 4}, {2, -4}})
                geometry.put(
                        new JSONObject()
                                .put("lat", lat + xy[1] / 111195.08)
                                .put("lon", lon + xy[0] / kx));
            JSONObject root =
                    new JSONObject()
                            .put(
                                    "mapnotes_bounds",
                                    new JSONArray(
                                            new double[] {
                                                lat - .001, lon - .001, lat + .001, lon + .001
                                            }))
                            .put(
                                    "elements",
                                    new JSONArray()
                                            .put(
                                                    new JSONObject()
                                                            .put(
                                                                    "tags",
                                                                    new JSONObject()
                                                                            .put("building", "yes"))
                                                            .put("geometry", geometry)));
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(root.toString().getBytes("UTF-8"));
            }
            map.replaceAtlas(new OfflineAtlas(app));
            map.follow = false;
            map.zoom = 20;
            map.cx = MapView.x(lon);
            map.cy = MapView.y(lat);
            ArrayList<double[]> points = new ArrayList<>();
            points.add(new double[] {lat, lon, 1, 1});
            map.update(points);
            Bitmap image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
            map.draw(new Canvas(image));
            barrier(map, "routeWorker");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Field f = MapView.class.getDeclaredField("readyFrame");
            f.setAccessible(true);
            RouteFrame frame = (RouteFrame) f.get(map);
            double mpp = 156543.03392 * Math.cos(Math.toRadians(lat)) / (1 << 20);
            assertTrue(Color.alpha(frame.mask.getPixel(256 - (int) (3 / mpp), 256)) > 250);
            assertEquals(0, Color.alpha(frame.mask.getPixel(256 + (int) (4 / mpp), 256)));
            map.draw(new Canvas(image));
            int visible = image.getPixel(256 - (int) (3 / mpp), 256),
                    hidden = image.getPixel(256 + (int) (4 / mpp), 256);
            assertNotEquals(visible, hidden);
            map.cx += 1024.0 / (1 << 20);
            map.draw(new Canvas(image));
            assertNotEquals(
                    "A shifted old mask must not reveal the entire new viewport",
                    visible,
                    image.getPixel(256, 256));
        } finally {
            map.close();
            file.delete();
        }
    }

    @Test
    public void rasterKeepsTailBeyondOldShapeLimit() {
        ArrayList<double[]> points = new ArrayList<>();
        double lat = 55.748, lon = 37.612, kx = 111195.08 * Math.cos(Math.toRadians(lat));
        for (int i = 0; i < 2100; i++)
            points.add(
                    new double[] {
                        lat + (-115 + (i / 46) * 5) / 111195.08,
                        lon + (-275 + (i % 46) * 5) / kx,
                        1,
                        i * 1000
                    });
        points.add(new double[] {lat, lon + 250 / kx, 2, 3000000});
        RouteFrame frame =
                new RouteFrame(MapView.x(lon), MapView.y(lat), 16, 512, 512, 1, points, null, null);
        frame.raster = true;
        frame.build();
        assertTrue(frame.complete);
        assertTrue(
                "The full visible history is processed beyond 1024 origins", frame.samples > 1024);
        double mpp = 156543.03392 * Math.cos(Math.toRadians(lat)) / (1 << 16);
        assertTrue(
                "The last, separate part of history must remain visible",
                Color.alpha(frame.mask.getPixel(256 + (int) (250 / mpp), 256)) > 0);
        assertTrue(frame.mask.getWidth() * frame.mask.getHeight() <= 262144);
    }

    @Test
    public void revisitingAnAreaRestoresItsFrameWithoutWaiting() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        MapView map = new MapView(app, true);
        map.online = false;
        map.layout(0, 0, 512, 512);
        try {
            barrier(map, "mapWorker");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            map.follow = false;
            map.cx = MapView.x(37.612);
            map.cy = MapView.y(55.748);
            map.zoom = 17;
            ArrayList<double[]> points = new ArrayList<>();
            points.add(new double[] {55.748, 37.612, 1, 1});
            map.update(points);
            Canvas canvas = new Canvas(Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888));
            map.draw(canvas);
            barrier(map, "routeWorker");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Field f = MapView.class.getDeclaredField("readyFrame");
            f.setAccessible(true);
            RouteFrame original = (RouteFrame) f.get(map);
            double originalX = map.cx;
            map.cx += 1024.0 / (1 << map.zoom);
            map.draw(canvas);
            barrier(map, "routeWorker");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotSame(original, f.get(map));
            map.cx = originalX;
            map.draw(canvas);
            assertSame(
                    "A previous viewport is restored synchronously from the bounded cache",
                    original,
                    f.get(map));
        } finally {
            map.close();
        }
    }

    @Test
    public void onlinePauseDisconnectsActiveLiveRequestsAndPersists() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        MapView map = new MapView(app);
        try {
            map.online = true;
            java.net.HttpURLConnection connection =
                    new java.net.HttpURLConnection(new java.net.URL("https://example.invalid")) {
                        public void connect() {}

                        public boolean usingProxy() {
                            return false;
                        }

                        public void disconnect() {
                            disconnected = true;
                        }

                        boolean disconnected;
                    };
            Field field = MapView.class.getDeclaredField("liveConnections");
            field.setAccessible(true);
            ((Set<java.net.HttpURLConnection>) field.get(map)).add(connection);
            MapNetwork.register(app, connection);
            map.toggleOnline();
            assertFalse(map.online);
            Field disconnected = connection.getClass().getDeclaredField("disconnected");
            disconnected.setAccessible(true);
            for (int i = 0; i < 100 && !disconnected.getBoolean(connection); i++) Thread.sleep(10);
            assertTrue(disconnected.getBoolean(connection));
            MapView reopened = new MapView(app);
            try {
                assertFalse("Online pause survives reopening the map", reopened.online);
            } finally {
                reopened.close();
            }
            map.toggleOnline();
            assertTrue(map.online);
        } finally {
            map.close();
            app.getSharedPreferences("map-view", 0).edit().clear().commit();
        }
    }

    @Test
    public void hundredThousandDuplicateFixesUseOneVisibilityShape() {
        ArrayList<double[]> points = new ArrayList<>();
        for (int i = 0; i < 100000; i++) points.add(new double[] {55.748, 37.612, 1, i * 1000.0});
        RouteFrame frame =
                new RouteFrame(
                        MapView.x(37.612), MapView.y(55.748), 17, 512, 512, 1, points, null, null);
        frame.build();
        assertEquals(1, frame.samples);
        assertTrue(frame.maskVertices <= 100000);
        assertTrue(frame.vertices <= 20000);
        assertFalse(frame.reveal.isEmpty());
    }

    @Test
    public void distantHistoryDoesNotBuildOffscreenMask() {
        ArrayList<double[]> points = new ArrayList<>();
        for (int i = 0; i < 100000; i++) points.add(new double[] {40, 10, 1, i * 1000.0});
        RouteFrame frame =
                new RouteFrame(
                        MapView.x(37.612), MapView.y(55.748), 17, 512, 512, 1, points, null, null);
        frame.build();
        assertEquals(0, frame.samples);
        assertEquals(0, frame.vertices);
        assertTrue(frame.reveal.isEmpty());
    }

    @Test
    public void asyncProductionViewPublishesAndReusesBackgroundFrame() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        MapView map = new MapView(app, true);
        map.online = false;
        map.layout(0, 0, 512, 512);
        try {
            barrier(map, "mapWorker");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            map.follow = false;
            map.cx = MapView.x(37.612);
            map.cy = MapView.y(55.748);
            map.zoom = 17;
            ArrayList<double[]> points = new ArrayList<>();
            points.add(new double[] {55.748, 37.612, 1, 1});
            map.update(points);
            Bitmap image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
            map.draw(new Canvas(image));
            barrier(map, "routeWorker");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Field ready = MapView.class.getDeclaredField("readyFrame");
            ready.setAccessible(true);
            RouteFrame first = (RouteFrame) ready.get(map);
            assertNotNull(first);
            assertNotNull(first.mask);
            assertTrue(first.samples > 0);
            map.draw(new Canvas(image));
            barrier(map, "routeWorker");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertSame(
                    "Unchanged viewport must reuse the completed visibility calculation",
                    first,
                    ready.get(map));
        } finally {
            map.close();
        }
    }

    static void barrier(MapView map, String name) throws Exception {
        Field field = MapView.class.getDeclaredField(name);
        field.setAccessible(true);
        ((ExecutorService) field.get(map)).submit(() -> {}).get(10, TimeUnit.SECONDS);
    }

    @Test
    public void geometryReadRunsOffUiThreadAndLabelsAreBounded() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File dir = new File(app.getFilesDir(), "regions");
        dir.mkdirs();
        File file = new File(dir, "performance.json");
        JSONArray elements = new JSONArray();
        double lat = 55.748, lon = 37.612;
        for (int i = 0; i < 400; i++) {
            double a = lat + (i % 20 - 10) * .0001, b = lon + (i / 20 - 10) * .0001;
            JSONArray coordinates =
                    new JSONArray()
                            .put(new JSONObject().put("lat", a).put("lon", b))
                            .put(new JSONObject().put("lat", a + .0001).put("lon", b + .0001));
            elements.put(
                    new JSONObject()
                            .put(
                                    "tags",
                                    new JSONObject()
                                            .put("highway", "residential")
                                            .put("name", "Road " + i))
                            .put("geometry", coordinates));
        }
        JSONObject root =
                new JSONObject()
                        .put(
                                "mapnotes_bounds",
                                new JSONArray(
                                        new double[] {lat - .01, lon - .01, lat + .01, lon + .01}))
                        .put("elements", elements);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(root.toString().getBytes("UTF-8"));
        }
        CountDownLatch loaded = new CountDownLatch(1);
        OfflineAtlas atlas =
                new OfflineAtlas(app, true, loaded::countDown) {
                    @Override
                    synchronized ArrayList<Way> loadWays(Area area) {
                        assertNotEquals(
                                "Geometry disk reads must not run on the UI thread",
                                Looper.getMainLooper().getThread(),
                                Thread.currentThread());
                        return super.loadWays(area);
                    }
                };
        MapView map = new MapView(app);
        map.online = false;
        map.layout(0, 0, 512, 512);
        map.cx = MapView.x(lon);
        map.cy = MapView.y(lat);
        map.zoom = 17;
        map.follow = false;
        try {
            for (OfflineAtlas.Area a : atlas.areas)
                assertNull("Legacy maps must not retain decoded geometry at startup", a.eager);
            Canvas canvas = new Canvas(Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888));
            atlas.draw(canvas, map, false);
            assertTrue(loaded.await(10, TimeUnit.SECONDS));
            atlas.draw(canvas, map, false);
            assertTrue(atlas.lastLabels > 0);
            assertTrue(atlas.lastLabels <= 24);
            assertTrue(atlas.lastVertices <= 80002);
        } finally {
            atlas.close();
            map.close();
            file.delete();
        }
    }

    @Test
    public void routeDatabasePagesOnlyReturnNewFixes() {
        Application app = RuntimeEnvironment.getApplication();
        try (Store store = new Store(app)) {
            android.location.Location l = new android.location.Location("gps");
            l.setLatitude(55.748);
            l.setLongitude(37.612);
            long start = store.latestPointId();
            l.setAccuracy(5);
            for (int i = 0; i < 7; i++) {
                l.setTime(i);
                store.add(l, 1);
            }
            Store.PointPage first = store.pointsAfter(start, 5);
            assertEquals(5, first.points.size());
            Store.PointPage second = store.pointsAfter(first.lastId, 5);
            assertEquals(2, second.points.size());
            assertTrue(store.pointsAfter(second.lastId, 5).points.isEmpty());
        }
    }
}

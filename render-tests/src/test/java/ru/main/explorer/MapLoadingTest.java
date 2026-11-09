package ru.main.explorer;

import android.app.Application;
import android.content.ContextWrapper;
import android.graphics.*;
import java.io.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MapLoadingTest {
    @Test
    public void resumeRetainsAtlasOnlyAndOldJobsStillIncludeSatellite() {
        Application app = RuntimeEnvironment.getApplication();
        android.content.SharedPreferences prefs = MapDownloadService.preferences(app);
        try {
            prefs.edit().clear().commit();
            assertTrue(MapDownloadService.resumeIntent(app).getBooleanExtra("satellite", false));
            prefs.edit()
                    .putBoolean("satellite", false)
                    .putLong("lat", Double.doubleToLongBits(55.748))
                    .putLong("radius", Double.doubleToLongBits(500))
                    .commit();
            android.content.Intent resume = MapDownloadService.resumeIntent(app);
            assertFalse(resume.getBooleanExtra("satellite", true));
            assertEquals(55.748, resume.getDoubleExtra("lat", 0), 0);
            assertEquals(500, resume.getDoubleExtra("radius", 0), 0);
        } finally {
            prefs.edit().clear().commit();
        }
    }

    @Test
    public void vectorRoadBorderDoesNotChangeWhenExplored() throws Exception {
        WorldMapTest helper = new WorldMapTest();
        helper.setup();
        byte[] road = WorldMapTest.layer("roads", 2, "", 9, 0, 4096, 10, 8192, 0);
        java.io.File file = helper.database(road, 14, 8192, 8192, false);
        MapView map = new MapView(RuntimeEnvironment.getApplication());
        map.online = false;
        map.layout(0, 0, 256, 256);
        map.zoom = 14;
        map.cx = (8192.5) * 256 / (1 << 14);
        map.cy = (8192.5) * 256 / (1 << 14);
        try (WorldMap world =
                WorldMap.open(
                        RuntimeEnvironment.getApplication(),
                        android.net.Uri.fromFile(file),
                        null)) {
            world.tile(14, 8192, 8192, true);
            Bitmap a = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888),
                    b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
            world.draw(new Canvas(a), map, false);
            world.draw(new Canvas(b), map, true);
            for (int y = 120; y < 136; y++)
                for (int x = 10; x < 246; x++)
                    assertEquals(
                            "Thin antialiased road coverage must retain its geometry",
                            a.getPixel(x, y) != 0xffc6c6b0,
                            b.getPixel(x, y) != 0xffb8cb96);
            assertTrue(a.getPixel(128, 128) != 0xffc6c6b0);
            for (int y = 120; y < 136; y++)
                assertEquals(
                        "Road core width must not change",
                        a.getPixel(128, y) == 0xfff5e9c9,
                        b.getPixel(128, y) == 0xfff5e9c9);
        } finally {
            map.close();
            helper.cleanup();
        }
    }

    @Test
    public void roadStrokeIsIdenticalAcrossRevealBoundaryAndRepeatedDraws() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        OfflineAtlas atlas = new OfflineAtlas(app);
        atlas.areas.clear();
        OfflineAtlas.Area area = new OfflineAtlas.Area();
        area.south = -1;
        area.west = -1;
        area.north = 1;
        area.east = 1;
        area.legacy = true;
        area.eager = new ArrayList<>();
        OfflineAtlas.Way road = new OfflineAtlas.Way();
        road.coords = new double[] {0, -.001, 0, .001};
        road.type = "road";
        road.name = "";
        road.south = road.north = 0;
        road.west = -.001;
        road.east = .001;
        area.eager.add(road);
        area.source = new File(app.getFilesDir(), "test-road");
        atlas.areas.add(area);
        MapView map = new MapView(app);
        map.online = false;
        map.layout(0, 0, 512, 512);
        map.cx = 128;
        map.cy = 128;
        map.zoom = 16;
        try {
            Bitmap muted = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888),
                    revealed = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
            atlas.draw(new Canvas(muted), map, false);
            atlas.draw(new Canvas(revealed), map, true);
            int[] first = new int[512 * 512], second = new int[first.length];
            muted.getPixels(first, 0, 512, 0, 0, 512, 512);
            revealed.getPixels(second, 0, 512, 0, 0, 512, 512);
            assertArrayEquals("Revealing land must not change the road stroke", first, second);
            revealed.eraseColor(Color.TRANSPARENT);
            atlas.draw(new Canvas(revealed), map, false);
            revealed.getPixels(second, 0, 512, 0, 0, 512, 512);
            assertArrayEquals(first, second);
        } finally {
            map.close();
            atlas.close();
        }
    }

    @Test
    public void atlasReloadRetainsUnchangedDecodedGeometryWithoutBlankFrame() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File root = new File(app.getFilesDir(), "stable-" + UUID.randomUUID()),
                dir = new File(root, "regions");
        dir.mkdirs();
        JSONObject point1 = new JSONObject().put("lat", 0).put("lon", -.001),
                point2 = new JSONObject().put("lat", 0).put("lon", .001);
        JSONObject road =
                new JSONObject()
                        .put("tags", new JSONObject().put("highway", "residential"))
                        .put("geometry", new JSONArray().put(point1).put(point2));
        MapPack.write(
                new File(dir, "one.json"),
                new JSONObject()
                        .put("mapnotes_bounds", MapPack.bounds(-.01, -.01, .01, .01))
                        .put("elements", new JSONArray().put(road))
                        .toString()
                        .getBytes("UTF-8"),
                false);
        ContextWrapper context =
                new ContextWrapper(app) {
                    public File getFilesDir() {
                        return root;
                    }
                };
        OfflineAtlas old = new OfflineAtlas(context, true, () -> {}),
                next = new OfflineAtlas(context, true, () -> {});
        try {
            ArrayList<OfflineAtlas.Way> decoded = old.loadWays(old.areas.get(0));
            next.reuseGeometry(old);
            assertSame(
                    "Reload must reuse geometry before its first draw",
                    decoded,
                    next.ways(next.areas.get(0)));
        } finally {
            old.close();
            next.close();
        }
    }

    @Test
    public void denseDownloadedRegionsStayDecodedWithoutEvictionReload() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File root = new File(app.getFilesDir(), "dense-" + UUID.randomUUID()),
                dir = new File(root, "regions");
        dir.mkdirs();
        writeDenseRegion(new File(dir, "dense-one.json"), -.02);
        writeDenseRegion(new File(dir, "dense-two.json"), .02);
        ContextWrapper context =
                new ContextWrapper(app) {
                    public File getFilesDir() {
                        return root;
                    }
                };
        OfflineAtlas atlas = new OfflineAtlas(context);
        try {
            assertEquals(2, atlas.areas.size());
            ArrayList<OfflineAtlas.Way> first = atlas.loadWays(atlas.areas.get(0)),
                    second = atlas.loadWays(atlas.areas.get(1));
            assertSame(
                    "A dense region above the old point budget must remain decoded instead of reloading in a loop",
                    first,
                    atlas.ways(atlas.areas.get(0)));
            assertSame(second, atlas.ways(atlas.areas.get(1)));
        } finally {
            atlas.close();
        }
    }

    static void writeDenseRegion(File file, double south) throws Exception {
        double west = .0;
        JSONArray elements = new JSONArray();
        for (int i = 0; i < 20000; i++) {
            JSONArray geometry = new JSONArray();
            for (int p = 0; p < 10; p++)
                geometry.put(
                        new JSONObject()
                                .put("lat", south + .001 + (i % 100) * .0003 + p * .00001)
                                .put("lon", west + .001 + (i / 100) * .0003 + p * .00001));
            elements.put(
                    new JSONObject()
                            .put("tags", new JSONObject().put("highway", "residential"))
                            .put("geometry", geometry));
        }
        MapPack.write(
                file,
                new JSONObject()
                        .put(
                                "mapnotes_bounds",
                                MapPack.bounds(south, west, south + .04, west + .04))
                        .put("elements", elements)
                        .toString()
                        .getBytes("UTF-8"),
                false);
    }

    @Test
    public void smallScaleUsesLighterOverviewGeometryWithoutBuildings() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File root = new File(app.getFilesDir(), "lod-" + UUID.randomUUID()),
                dir = new File(root, "regions");
        dir.mkdirs();
        JSONArray building = new JSONArray();
        double[][] corners = {
            {-.0004, -.0004},
            {-.0004, .0004},
            {0, .0004},
            {.0004, .0004},
            {.0004, 0},
            {.0004, -.0004},
            {0, -.0004},
            {-.0004, -.0004},
            {-.0004, 0},
            {-.0004, -.0004},
            {-.0002, -.0002},
            {-.0004, -.0004}
        };
        for (double[] point : corners)
            building.put(new JSONObject().put("lat", point[0]).put("lon", point[1]));
        JSONArray road = new JSONArray();
        for (int p = 0; p < 40; p++)
            road.put(new JSONObject().put("lat", .0015).put("lon", .001 + p * 2.0 / 111195.08));
        JSONArray elements =
                new JSONArray()
                        .put(
                                new JSONObject()
                                        .put("tags", new JSONObject().put("building", "yes"))
                                        .put("geometry", building))
                        .put(
                                new JSONObject()
                                        .put("tags", new JSONObject().put("highway", "residential"))
                                        .put("geometry", road));
        MapPack.write(
                new File(dir, "lod.json"),
                new JSONObject()
                        .put("mapnotes_bounds", MapPack.bounds(-.001, .001, .002, .0025))
                        .put("elements", elements)
                        .toString()
                        .getBytes("UTF-8"),
                false);
        ContextWrapper context =
                new ContextWrapper(app) {
                    public File getFilesDir() {
                        return root;
                    }
                };
        OfflineAtlas atlas = new OfflineAtlas(context);
        try {
            OfflineAtlas.Area area = atlas.areas.get(0);
            ArrayList<OfflineAtlas.Way> detail = atlas.loadWays(area),
                    overview = atlas.loadWays(area, true);
            assertEquals(2, detail.size());
            assertEquals(1, overview.size());
            assertEquals("building", detail.get(0).type);
            assertEquals("road", overview.get(0).type);
            assertEquals(80, detail.get(1).coords.length);
            assertTrue(
                    "Overview polyline must drop sub-4 m vertices",
                    overview.get(0).coords.length < 80 && overview.get(0).coords.length >= 8);
            assertSame(
                    "Small scales must use the cached overview set",
                    overview,
                    atlas.ways(area, 13));
            assertSame("Large scales must use the cached detail set", detail, atlas.ways(area, 15));
            OfflineAtlas next = new OfflineAtlas(context);
            next.reuseGeometry(atlas);
            try {
                assertSame(
                        "Reload must keep the overview set too",
                        overview,
                        next.ways(next.areas.get(0), 13));
                assertSame(detail, next.ways(next.areas.get(0), 15));
            } finally {
                next.close();
            }
        } finally {
            atlas.close();
        }
    }

    @Test
    public void atlasOnlyCompletesWithoutRequestingSatelliteAndCanUpgradeLater() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File root = new File(app.getFilesDir(), "atlas-only-" + UUID.randomUUID());
        root.mkdirs();
        RegionPlan plan = new RegionPlan(55.748, 37.612, 500);
        MapPackTest.Fake first = new MapPackTest.Fake();
        new MapPack(root, plan, first, false).run();
        assertEquals(1, first.vectors);
        assertEquals(0, first.images);
        File manifest = new File(new File(new File(root, "packs"), plan.key()), "pack.json");
        JSONObject state = new JSONObject(new String(MapPack.read(manifest, 65536), "UTF-8"));
        assertTrue(state.getBoolean("complete"));
        assertFalse(state.getBoolean("satellite"));
        MapPackTest.Fake resumed = new MapPackTest.Fake();
        new MapPack(root, plan, resumed, false).run();
        assertEquals(0, resumed.vectors);
        assertEquals(0, resumed.images);
        MapPackTest.Fake upgrade = new MapPackTest.Fake();
        new MapPack(root, plan, upgrade, true).run();
        assertEquals(0, upgrade.vectors);
        assertEquals(plan.tiles().size(), upgrade.images);
    }

    @Test
    public void serverFailureFallsBackImmediatelyWithoutLosingQuery() throws Exception {
        ArrayList<String> requests = new ArrayList<>();
        byte[] data =
                Overpass.fetch(
                        endpoint -> {
                            requests.add(endpoint);
                            if (requests.size() == 1)
                                throw new MapPack.PermanentException("HTTP 403");
                            if (requests.size() == 2) throw new MapPack.RateLimitException();
                            return new byte[] {42};
                        });
        assertArrayEquals(new byte[] {42}, data);
        assertEquals(3, requests.size());
        assertEquals(3, new HashSet<>(requests).size());
    }

    @Test
    public void pauseDoesNotTryOtherServers() throws Exception {
        int[] calls = {0};
        try {
            Overpass.fetch(
                    endpoint -> {
                        calls[0]++;
                        throw new MapPack.PausedException();
                    });
            fail();
        } catch (MapPack.PausedException expected) {
        }
        assertEquals(1, calls[0]);
    }

    @Test
    public void allTransientFailuresRemainRetryableAndAllPermanentFailuresStop() throws Exception {
        int[] calls = {0};
        try {
            Overpass.fetch(
                    endpoint -> {
                        calls[0]++;
                        throw new IOException("HTTP 504");
                    });
            fail();
        } catch (MapPack.QueryFailure expected) {
        }
        assertEquals(3, calls[0]);
        calls[0] = 0;
        try {
            Overpass.fetch(
                    endpoint -> {
                        calls[0]++;
                        throw new MapPack.PermanentException("HTTP 404");
                    });
            fail();
        } catch (MapPack.PermanentException expected) {
        }
        assertEquals(3, calls[0]);
    }

    @Test
    public void changingOneFileCannotReuseItsStaleGeometry() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File root = new File(app.getFilesDir(), "changed-" + UUID.randomUUID()),
                dir = new File(root, "regions");
        dir.mkdirs();
        File file = new File(dir, "one.json");
        MapPack.write(
                file, "{\"mapnotes_bounds\":[-1,-1,1,1],\"elements\":[]}".getBytes("UTF-8"), false);
        ContextWrapper context =
                new ContextWrapper(app) {
                    public File getFilesDir() {
                        return root;
                    }
                };
        OfflineAtlas old = new OfflineAtlas(context);
        ArrayList<OfflineAtlas.Way> decoded = old.loadWays(old.areas.get(0));
        MapPack.write(
                file,
                "{\"mapnotes_bounds\":[-1,-1,1,1],\"elements\":[],\"extra\":true}"
                        .getBytes("UTF-8"),
                false);
        OfflineAtlas next = new OfflineAtlas(context);
        try {
            next.reuseGeometry(old);
            assertNotSame(decoded, next.ways(next.areas.get(0)));
        } finally {
            old.close();
            next.close();
        }
    }
}

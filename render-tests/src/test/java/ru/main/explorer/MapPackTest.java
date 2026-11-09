package ru.main.explorer;

import android.app.Application;
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
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MapPackTest {
    Application app;
    File root;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        root = new File(app.getFilesDir(), "pack-test-" + UUID.randomUUID());
        root.mkdirs();
    }

    static class Fake implements MapPack.Transfer {
        int vectors, images, failures;
        boolean stopped, splitOnce;
        int stopAt = Integer.MAX_VALUE;
        Set<String> requests = new HashSet<>();
        List<Long> waits = new ArrayList<>();
        byte[] image;

        Fake() {
            Bitmap b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
            b.eraseColor(0xffa6b37b);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 80, out);
            image = out.toByteArray();
        }

        public byte[] fetch(String url, String query, int limit) throws Exception {
            if (failures > 0) {
                failures--;
                throw new IOException("temporary connection loss");
            }
            if (query != null) {
                if (vectors == stopAt) {
                    stopped = true;
                    throw new IOException("pause");
                }
                vectors++;
                requests.add(query);
                if (splitOnce) {
                    splitOnce = false;
                    throw new MapPack.TooLargeException();
                }
                return "{\"elements\":[]}".getBytes("UTF-8");
            }
            images++;
            return image;
        }

        public void check() throws IOException {
            if (stopped) throw new IOException("pause");
        }

        public void progress(String t, int d, int total) {}

        public void changed() {}

        public void waitMillis(long t) {
            waits.add(t);
        }

        public void validateImage(byte[] b) throws IOException {
            assertNotNull(BitmapFactory.decodeByteArray(b, 0, b.length));
        }
    }

    @Test
    public void smallPackCompletesAndSecondRunNeedsNoNetwork() throws Exception {
        RegionPlan plan = new RegionPlan(55.748, 37.612, 500);
        Fake first = new Fake();
        new MapPack(root, plan, first).run();
        assertEquals(1, first.vectors);
        assertEquals(plan.tiles().size(), first.images);
        File manifest = new File(new File(new File(root, "packs"), plan.key()), "pack.json");
        assertTrue(
                new JSONObject(new String(MapPack.read(manifest, 65536), "UTF-8"))
                        .getBoolean("complete"));
        Fake second = new Fake();
        new MapPack(root, plan, second).run();
        assertEquals(0, second.vectors);
        assertEquals(0, second.images);
    }

    @Test
    public void hundredKmPauseKeepsPartsAndResumeUsesSameGrid() throws Exception {
        RegionPlan plan = new RegionPlan(55.748, 37.612, 50000);
        Fake first = new Fake();
        first.stopAt = 5;
        try {
            new MapPack(root, plan, first).run();
            fail();
        } catch (IOException expected) {
        }
        assertEquals(5, first.vectors);
        Fake resumed = new Fake();
        resumed.stopAt = 3;
        try {
            new MapPack(root, plan, resumed).run();
            fail();
        } catch (IOException expected) {
        }
        assertEquals(3, resumed.vectors);
        for (String query : resumed.requests)
            assertFalse(
                    "A finished part must not be requested again", first.requests.contains(query));
        File pack = new File(new File(root, "packs"), plan.key());
        assertEquals(8, pack.listFiles((d, n) -> n.endsWith(".meta")).length);
        assertFalse(
                new JSONObject(
                                new String(
                                        MapPack.read(new File(pack, "pack.json"), 65536), "UTF-8"))
                        .getBoolean("complete"));
    }

    @Test
    public void denseCellSplitsAndChildrenAreReused() throws Exception {
        RegionPlan plan = new RegionPlan(55.748, 37.612, 500);
        Fake first = new Fake();
        first.splitOnce = true;
        new MapPack(root, plan, first).run();
        assertEquals(5, first.vectors);
        Fake resumed = new Fake();
        new MapPack(root, plan, resumed).run();
        assertEquals(0, resumed.vectors);
        assertEquals(0, resumed.images);
    }

    @Test
    public void transientNetworkFailureRetriesWithoutDiscardingPack() throws Exception {
        Fake transfer = new Fake();
        transfer.failures = 1;
        RegionPlan plan = new RegionPlan(55.748, 37.612, 500);
        new MapPack(root, plan, transfer).run();
        assertEquals(1, transfer.vectors);
        assertTrue(transfer.waits.contains(5000L));
        assertEquals(plan.tiles().size(), transfer.images);
    }

    @Test
    public void compressedGeometryCanBeReadByOfflineAtlas() throws Exception {
        RegionPlan plan = new RegionPlan(55.748, 37.612, 500);
        Fake first = new Fake();
        new MapPack(root, plan, first).run();
        File dir = new File(new File(root, "packs"), plan.key());
        File[] indexes = dir.listFiles((d, n) -> n.endsWith(".meta"));
        JSONObject index = new JSONObject(new String(MapPack.read(indexes[0], 65536), "UTF-8"));
        JSONObject data =
                new JSONObject(
                        new String(
                                MapPack.read(new File(dir, index.getString("file")), 16000000),
                                "UTF-8"));
        assertEquals(4, data.getJSONArray("mapnotes_bounds").length());
        assertEquals(0, data.getJSONArray("elements").length());
        android.content.Context wrapped =
                new android.content.ContextWrapper(app) {
                    public File getFilesDir() {
                        return root;
                    }
                };
        OfflineAtlas atlas = new OfflineAtlas(wrapped);
        assertEquals(1, atlas.regionCount());
        assertTrue(atlas.coversRadius(55.748, 37.612, 100));
        assertTrue(atlas.buildingsNear(55.748, 37.612, 100).isEmpty());
    }

    @Test
    public void corruptSavedSatelliteIsFetchedAgain() throws Exception {
        RegionPlan plan = new RegionPlan(55.748, 37.612, 500);
        Fake first = new Fake();
        new MapPack(root, plan, first).run();
        int[] tile = plan.tiles().get(0);
        File bad =
                new File(
                        new File(root, "satellite"),
                        RegionPlan.tileName(tile[0], tile[1], tile[2]));
        try (FileOutputStream out = new FileOutputStream(bad)) {
            out.write(new byte[] {1, 2, 3});
        }
        Fake second =
                new Fake() {
                    public void validateImage(byte[] bytes) throws IOException {
                        if (BitmapFactory.decodeByteArray(bytes, 0, bytes.length) == null)
                            throw new IOException("Broken image");
                    }
                };
        new MapPack(root, plan, second).run();
        assertEquals(1, second.images);
    }

    @Test
    public void downloadRecoversAfterMoreThanTwoConnectionFailures() throws Exception {
        Fake transfer = new Fake();
        transfer.failures = 4;
        RegionPlan plan = new RegionPlan(55.748, 37.612, 500);
        new MapPack(root, plan, transfer).run();
        assertEquals(4, transfer.waits.stream().filter(value -> value >= 5000).count());
        assertEquals(1, transfer.vectors);
        assertEquals(plan.tiles().size(), transfer.images);
    }

    @Test
    public void repeated502SplitsLargeQueryAndKeepsFinishedChildren() throws Exception {
        RegionPlan plan = new RegionPlan(55.748, 37.612, 2000);
        Fake transfer =
                new Fake() {
                    int errors;

                    public byte[] fetch(String url, String query, int limit) throws Exception {
                        if (query != null && errors++ < 2)
                            throw new MapPack.QueryFailure(
                                    "HTTP 502", new IOException("server closed connection"));
                        return super.fetch(url, query, limit);
                    }
                };
        new MapPack(root, plan, transfer).run();
        assertEquals(7, transfer.vectors);
        Fake resumed = new Fake();
        new MapPack(root, plan, resumed).run();
        assertEquals(0, resumed.vectors);
    }

    @Test
    public void longOutageKeepsRetryingUntilUserPause() throws Exception {
        Fake transfer =
                new Fake() {
                    int requests;

                    public byte[] fetch(String url, String query, int limit) throws Exception {
                        requests++;
                        if (requests > 12) stopped = true;
                        throw new IOException("server closed connection");
                    }
                };
        try {
            new MapPack(root, new RegionPlan(55.748, 37.612, 500), transfer).run();
            fail();
        } catch (IOException paused) {
        }
        assertTrue(transfer.stopped);
        assertEquals(12, transfer.waits.size());
    }
}

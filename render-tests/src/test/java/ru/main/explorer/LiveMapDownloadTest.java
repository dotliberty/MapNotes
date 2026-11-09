package ru.main.explorer;

import android.app.Application;
import android.content.ContextWrapper;
import android.graphics.*;
import java.io.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Opt-in public-network smoke test; normal suite does not contact map servers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LiveMapDownloadTest {
    @Test
    public void realOneKmAtlasDownloadsRendersAndReopensWithoutNetwork() throws Exception {
        Assume.assumeTrue(
                "Set MAPNOTES_LIVE_PROBE=1 for the bounded public-network smoke test",
                "1".equals(System.getenv("MAPNOTES_LIVE_PROBE")));
        runWorkflow(null);
    }

    @Test
    public void realCurlResponsePacksRendersAndReopensWithoutNetwork() throws Exception {
        String path = System.getenv("MAPNOTES_CAPTURED_RESPONSE");
        Assume.assumeTrue(
                "Set MAPNOTES_CAPTURED_RESPONSE to an independently fetched real Overpass response",
                path != null && !path.isEmpty());
        runWorkflow(new File(path));
    }

    private void runWorkflow(File captured) throws Exception {

        Application app = RuntimeEnvironment.getApplication();
        MapNetwork.setEnabled(app, true);
        File root = new File("build/live-map-probe-" + UUID.randomUUID());
        root.mkdirs();
        RegionPlan plan = new RegionPlan(55.748, 37.612, 500);
        int[] requests = {0};
        org.robolectric.android.controller.ServiceController<MapDownloadService> controller =
                Robolectric.buildService(MapDownloadService.class).create();
        MapDownloadService service = controller.get();
        MapPack.Transfer transfer =
                new MapPack.Transfer() {
                    public byte[] fetch(String url, String query, int limit) throws Exception {
                        requests[0]++;
                        assertNotNull("Atlas-only must not fetch satellite", query);
                        if (captured != null) {
                            System.out.println(
                                    "CAPTURED real curl response=" + captured.getAbsolutePath());
                            return MapPack.read(captured, limit);
                        }
                        return Overpass.fetch(
                                endpoint -> {
                                    System.out.println("LIVE source=" + endpoint);
                                    try {
                                        return service.fetch(endpoint, query, limit);
                                    } catch (Exception failure) {
                                        System.out.println(
                                                "LIVE failed="
                                                        + failure.getClass().getSimpleName()
                                                        + ": "
                                                        + failure.getMessage());
                                        throw failure;
                                    }
                                });
                    }

                    public void check() {}

                    public void progress(String text, int done, int total) {
                        System.out.println("LIVE " + text);
                    }

                    public void changed() {}

                    public void waitMillis(long millis) throws Exception {
                        if (millis >= 5000)
                            throw new IOException(
                                    "Bounded smoke test: no repeated public-server rounds");
                    }

                    public void validateImage(byte[] data) {
                        fail("Atlas-only must not fetch images");
                    }
                };
        try {
            new MapPack(root, plan, transfer, false).run();
            assertEquals(1, requests[0]);
            ContextWrapper context =
                    new ContextWrapper(app) {
                        public File getFilesDir() {
                            return root;
                        }
                    };
            OfflineAtlas atlas = new OfflineAtlas(context);
            MapView map = new MapView(context);
            map.online = false;
            map.layout(0, 0, 768, 768);
            map.cx = MapView.x(37.612);
            map.cy = MapView.y(55.748);
            map.zoom = 17;
            try {
                assertEquals(1, atlas.regionCount());
                assertTrue(atlas.coversRadius(55.748, 37.612, 100));
                int ways = 0;
                for (OfflineAtlas.Area a : atlas.areas)
                    if (!a.overview) ways += atlas.loadWays(a).size();
                assertTrue("Must contain real geometry", ways > 0);
                Bitmap image = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888);
                image.eraseColor(0xffc6c6b0);
                atlas.draw(new Canvas(image), map, true);
                assertTrue(atlas.lastVertices > 0);
                try (FileOutputStream out = new FileOutputStream(new File(root, "render.png"))) {
                    assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
                }
                System.out.println(
                        "LIVE saved="
                                + root.getAbsolutePath()
                                + " ways="
                                + ways
                                + " drawnVertices="
                                + atlas.lastVertices);
            } finally {
                map.close();
                atlas.close();
            }
            MapPack.Transfer offline =
                    new MapPack.Transfer() {
                        public byte[] fetch(String u, String q, int l) {
                            throw new AssertionError("Completed atlas must reopen without network");
                        }

                        public void check() {}

                        public void progress(String t, int d, int total) {}

                        public void changed() {}

                        public void waitMillis(long t) {}

                        public void validateImage(byte[] d) {
                            fail();
                        }
                    };
            new MapPack(root, plan, offline, false).run();
            System.out.println("LIVE offline second run passed");
        } finally {
            controller.destroy();
        }
    }
}

package ru.main.explorer;

import static org.junit.Assert.*;

import android.graphics.*;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import java.io.File;
import java.util.ArrayList;

/** Counterexamples that a future tile renderer must resolve, not tile implementation tests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AtlasTileContractTest {
    private MapView map;
    private OfflineAtlas atlas;

    @Before
    public void setup() {
        map = new MapView(RuntimeEnvironment.getApplication());
        map.online = false;
        map.layout(0, 0, 512, 512);
        map.cx = map.cy = 128;
        map.zoom = 17;
        atlas = new OfflineAtlas(RuntimeEnvironment.getApplication());
        atlas.areas.clear();
    }

    @After
    public void cleanup() {
        atlas.close();
        map.close();
    }

    private OfflineAtlas.Way road(float x0, float y0, float x1, float y1, String name) {
        double scale = 1 << map.zoom;
        double lon0 = ((128 + (x0 - 256) / scale) / 256) * 360 - 180;
        double lon1 = ((128 + (x1 - 256) / scale) / 256) * 360 - 180;
        double lat0 =
                Math.toDegrees(
                        Math.atan(Math.sinh(Math.PI * (1 - 2 * (128 + (y0 - 256) / scale) / 256))));
        double lat1 =
                Math.toDegrees(
                        Math.atan(Math.sinh(Math.PI * (1 - 2 * (128 + (y1 - 256) / scale) / 256))));
        OfflineAtlas.Way road = new OfflineAtlas.Way();
        road.type = "road";
        road.kind = "residential";
        road.name = name;
        road.coords = new double[] {lat0, lon0, lat1, lon1};
        road.south = Math.min(lat0, lat1);
        road.north = Math.max(lat0, lat1);
        road.west = Math.min(lon0, lon1);
        road.east = Math.max(lon0, lon1);
        return road;
    }

    private OfflineAtlas.Area install(ArrayList<OfflineAtlas.Way> ways) {
        OfflineAtlas.Area area = new OfflineAtlas.Area();
        area.source = new File("tile-contract-synthetic");
        area.south = area.west = -1;
        area.north = area.east = 1;
        area.legacy = true;
        area.eager = ways;
        atlas.areas.add(area);
        return area;
    }

    private Bitmap draw() {
        Bitmap image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        atlas.drawGeometry(new Canvas(image), map, false);
        return image;
    }

    @Test
    public void movingAllLabelsAboveGeometryChangesActualNativePixels() {
        ArrayList<OfflineAtlas.Way> ways = new ArrayList<>();
        OfflineAtlas.Way named = road(200, 240, 340, 240, "Long Street");
        ways.add(named);
        ways.add(road(180, 232, 360, 232, ""));
        OfflineAtlas.Area area = install(ways);
        Bitmap interleaved = draw();
        AtlasGeometry.Shape shape = atlas.frames.get(area).shapes.get(0);
        assertEquals("Long Street", shape.label);
        String label = shape.label;
        float x = shape.labelX;
        float y = shape.labelY;
        // Model the tempting geometry-tiles + one final global label pass proposal.
        named.name = "";
        atlas.frames.evictAll();
        Bitmap labelsAbove = draw();
        Paint paint = new Paint(3);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff33463c);
        paint.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        paint.setTextSize(map.dp(11));
        new Canvas(labelsAbove).drawText(label, x, y, paint);
        int[] before = new int[512 * 512];
        int[] after = new int[before.length];
        interleaved.getPixels(before, 0, 512, 0, 0, 512, 512);
        labelsAbove.getPixels(after, 0, 512, 0, 0, 512, 512);
        int changed = 0;
        for (int i = 0; i < before.length; i++) if (before[i] != after[i]) changed++;
        assertTrue("Final label overlay changes occlusion, not merely tile-edge AA", changed > 0);
        System.out.println(
                "CODE47 rejected final-label-overlay counterexample: changedPixels=" + changed);
    }

    @Test
    public void viewportVertexBudgetChangesSelectionInsideSameWorldTile() {
        OfflineAtlas.IndexedWays ways = new OfflineAtlas.IndexedWays();
        for (int i = 0; i < 40000; i++) ways.add(road(-20, 100, -18, 102, ""));
        OfflineAtlas.Way target = road(256, 256, 260, 260, "Target");
        ways.add(target);
        ways.index();
        OfflineAtlas.Area area = install(ways);
        draw();
        assertTrue(
                contains(new AtlasGeometry.Frame(ways.renderGeometry, area.stamp, map, 0), target));
        assertEquals(2, atlas.lastVertices);
        map.cx -= 40.0 / (1 << map.zoom);
        draw();
        // Oversized frames are deliberately not retained by the bounded production LRU.
        assertFalse(
                contains(new AtlasGeometry.Frame(ways.renderGeometry, area.stamp, map, 0), target));
        assertEquals(80000, atlas.lastVertices);
        // Target world coordinates did not change; only roads in another tile entered viewport.
        System.out.println(
                "CODE47 viewport-global budget counterexample: same target world geometry present"
                        + " -> absent on pan");
    }

    private boolean contains(AtlasGeometry.Frame frame, OfflineAtlas.Way target) {
        for (AtlasGeometry.Shape shape : frame.shapes) if (shape.entry.way == target) return true;
        return false;
    }
}

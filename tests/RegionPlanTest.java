import ru.main.explorer.RegionPlan;
import java.util.*;

public class RegionPlanTest {
    static int checks;

    static void ok(boolean v, String m) {
        checks++;
        if (!v) throw new AssertionError(m);
    }

    public static void main(String[] args) {
        ok(RegionPlan.x(0) == .5 && Math.abs(RegionPlan.y(0) - .5) < 1e-10, "equator XYZ origin");
        for (double lat : new double[] {0, 55.75, -33.86, 83})
            for (double radius : new double[] {500, 1000, 2000}) {
                RegionPlan p = new RegionPlan(lat, 37.62, radius);
                List<int[]> ts = p.tiles();
                Set<String> seen = new HashSet<>();
                for (int[] t : ts) {
                    int n = 1 << t[0];
                    ok(t[1] >= 0 && t[1] < n && t[2] >= 0 && t[2] < n, "valid tile indexes");
                    ok(seen.add(Arrays.toString(t)), "no duplicate downloads");
                }
                ok(ts.size() < 700, "bounded download");
                for (int z = 0; z <= p.maxZoom(); z++) {
                    boolean center = false;
                    int xx = (int) Math.floor(RegionPlan.x(37.62) * (1 << z)),
                            yy = (int) Math.floor(RegionPlan.y(lat) * (1 << z));
                    for (int[] t : ts) if (t[0] == z && t[1] == xx && t[2] == yy) center = true;
                    ok(center, "center covered at every zoom");
                }
                ok(
                        p.north > lat && p.south < lat && p.west < 37.62 && p.east > 37.62,
                        "symmetric area");
            }
        ok(
                RegionPlan.satelliteUrl(14, 9904, 5125).endsWith("/14/5125/9904.jpg"),
                "WMTS row before column");
        try {
            new RegionPlan(0, 179.999, 2000);
            throw new AssertionError("dateline accepted");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
        try {
            new RegionPlan(Double.NaN, 0, 1000);
            throw new AssertionError("NaN accepted");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
        for (double lat : new double[] {0, 55.75, 70, 83}) {
            RegionPlan big = new RegionPlan(lat, 37.62, 50000);
            ok(Math.abs((big.north - big.south) * 111195.08 - 100000) < .001, "100 km north/south");
            ok(
                    Math.abs(
                                    (big.east - big.west)
                                                    * 111195.08
                                                    * Math.cos(Math.toRadians(lat))
                                            - 100000)
                            < .001,
                    "100 km east/west");
            ok(big.cells().size() == 100, "100 spatial chunks");
            List<int[]> ts = big.tiles();
            ok(ts.size() > 1000 && ts.size() < 30000, "full 100 km satellite pyramid");
            for (int[] t : ts) {
                int n = 1 << t[0];
                ok(t[1] >= 0 && t[1] < n && t[2] >= 0 && t[2] < n, "large pack tile bounds");
            }
            double sum = 0;
            for (RegionPlan.Cell c : big.cells()) sum += (c.north - c.south) * (c.east - c.west);
            ok(
                    Math.abs(sum - (big.north - big.south) * (big.east - big.west)) < 1e-9,
                    "chunks cover entire square without holes");
        }
        System.out.println("PASS: " + checks + " offline area/tile checks");
        RegionPlan m = new RegionPlan(55.748, 37.612, 1000);
        System.out.println(
                "Moscow 2x2 km: " + m.tiles().size() + " satellite tiles, bbox " + m.bbox());
    }
}

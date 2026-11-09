import ru.main.explorer.Visibility;
import java.util.*;

public class VisibilityTest {
    static int checks;

    static void ok(boolean b, String m) {
        checks++;
        if (!b) throw new AssertionError(m);
    }

    static double[] building(double x0, double y0, double x1, double y1) {
        double k = 111195.08;
        return new double[] {
            y0 / k, x0 / k, y0 / k, x1 / k, y1 / k, x1 / k, y1 / k, x0 / k, y0 / k, x0 / k
        };
    }

    public static void main(String[] args) {
        double[] open = Visibility.polygon(0, 0, 5, Collections.emptyList());
        ok(Visibility.inside(4.9, 0, open), "five-metre radius visible");
        ok(!Visibility.inside(5.1, 0, open), "outside radius hidden");
        double[] view = Visibility.polygon(0, 0, 5, Arrays.asList(building(2, -3, 3, 3)));
        ok(Visibility.inside(1, 0, view), "before wall visible");
        ok(!Visibility.inside(2.5, 0, view), "building interior hidden");
        ok(!Visibility.inside(4, 0, view), "behind wall hidden");
        ok(Visibility.inside(-4, 0, view), "other side unaffected");
        ok(Visibility.inside(2.5, 4, view), "visible around corner");
        ok(
                Visibility.polygon(0, 0, 5, Arrays.asList(building(-2, -2, 2, 2))).length == 0,
                "observer inside building does not reveal roofs");
        double[] two =
                Visibility.polygon(
                        0, 0, 5, Arrays.asList(building(2, -3, 3, 3), building(-3, -3, -2, 3)));
        ok(!Visibility.inside(-4, 0, two), "second wall blocks west");
        ok(Visibility.inside(0, 4, two), "gap remains visible");
        for (int i = 0; i < view.length; i += 2)
            ok(Math.hypot(view[i], view[i + 1]) <= 5.000001, "bounded polygon");
        ok(Visibility.RADIUS == 100, "experimental radius is 100 m");
        double[] experiment = Visibility.polygon(0, 0, Visibility.RADIUS, Collections.emptyList());
        ok(Visibility.inside(99, 0, experiment), "99 metres opens");
        ok(!Visibility.inside(101, 0, experiment), "101 metres stays closed");
        double[] blocked =
                Visibility.polygon(
                        0, 0, Visibility.RADIUS, Arrays.asList(building(20, -40, 30, 40)));
        ok(!Visibility.inside(50, 0, blocked), "100 m radius still respects walls");
        System.out.println("PASS: " + checks + " visibility/occlusion checks");
    }
}

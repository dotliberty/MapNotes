package ru.main.explorer;

import java.util.*;

/** Local, horizontal line of sight. Buildings are opaque; coordinates are lat/lon pairs. */
public final class Visibility {
    public static final double RADIUS = 100;

    static double cross(double ax, double ay, double bx, double by) {
        return ax * by - ay * bx;
    }

    public static boolean inside(double x, double y, double[] polygon) {
        boolean in = false;
        int n = polygon.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double ax = polygon[i * 2],
                    ay = polygon[i * 2 + 1],
                    bx = polygon[j * 2],
                    by = polygon[j * 2 + 1];
            if ((ay > y) != (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax) in = !in;
        }
        return in;
    }

    public static double[] polygon(
            double lat, double lon, double radius, List<double[]> buildings) {
        double kx = 111195.08 * Math.cos(Math.toRadians(lat)), ky = 111195.08;
        ArrayList<double[]> walls = new ArrayList<>();
        TreeSet<Double> angles = new TreeSet<>();
        for (int i = 0; i < 180; i++) angles.add(i * Math.PI / 90);
        for (double[] geo : buildings) {
            if (geo.length < 6) continue;
            double[] p = new double[geo.length];
            double minx = Double.POSITIVE_INFINITY, miny = minx, maxx = -minx, maxy = maxx;
            for (int i = 0; i < geo.length; i += 2) {
                double dx = geo[i + 1] - lon;
                if (dx > 180) dx -= 360;
                if (dx < -180) dx += 360;
                p[i] = dx * kx;
                p[i + 1] = (geo[i] - lat) * ky;
                minx = Math.min(minx, p[i]);
                maxx = Math.max(maxx, p[i]);
                miny = Math.min(miny, p[i + 1]);
                maxy = Math.max(maxy, p[i + 1]);
            }
            if (minx > radius || maxx < -radius || miny > radius || maxy < -radius) continue;
            if (inside(0, 0, p)) return new double[0];
            for (int i = 0; i < p.length; i += 2) {
                int j = (i + 2) % p.length;
                double ax = p[i], ay = p[i + 1], bx = p[j], by = p[j + 1];
                if (ax == bx && ay == by) continue;
                if (Math.min(ax, bx) > radius
                        || Math.max(ax, bx) < -radius
                        || Math.min(ay, by) > radius
                        || Math.max(ay, by) < -radius) continue;
                walls.add(new double[] {ax, ay, bx, by});
                double a = Math.atan2(ay, ax);
                for (double eps : new double[] {-0.00001, 0, 0.00001}) {
                    double t = (a + eps + Math.PI * 2) % (Math.PI * 2);
                    angles.add(t);
                }
            }
        }
        @SuppressWarnings("unchecked")
        ArrayList<double[]>[] sectors = new ArrayList[180];
        for (int i = 0; i < sectors.length; i++) sectors[i] = new ArrayList<>();
        double sectorSize = Math.PI * 2 / sectors.length;
        for (double[] w : walls) {
            double ax = w[0],
                    ay = w[1],
                    bx = w[2],
                    by = w[3],
                    length = Math.hypot(bx - ax, by - ay);
            if (Math.abs(cross(ax, ay, bx, by)) < 1e-9 * length && ax * bx + ay * by <= 0) {
                for (ArrayList<double[]> sector : sectors) sector.add(w);
                continue;
            }
            double a = Math.atan2(ay, ax),
                    b = Math.atan2(by, bx),
                    delta = (b - a + Math.PI * 3) % (Math.PI * 2) - Math.PI,
                    start = ((delta >= 0 ? a : b) + Math.PI * 2) % (Math.PI * 2),
                    end = start + Math.abs(delta);
            for (int i = (int) Math.floor(start / sectorSize) - 1;
                    i <= (int) Math.floor(end / sectorSize) + 1;
                    i++) sectors[(i % sectors.length + sectors.length) % sectors.length].add(w);
        }
        double[] out = new double[angles.size() * 2];
        int n = 0;
        for (double a : angles) {
            double dx = Math.cos(a), dy = Math.sin(a), distance = radius;
            for (double[] w : sectors[Math.min(sectors.length - 1, (int) (a / sectorSize))]) {
                double sx = w[2] - w[0], sy = w[3] - w[1], den = cross(dx, dy, sx, sy);
                if (Math.abs(den) < 1e-10) continue;
                double t = cross(w[0], w[1], sx, sy) / den, u = cross(w[0], w[1], dx, dy) / den;
                if (t >= 0 && u >= -1e-9 && u <= 1 + 1e-9)
                    distance = Math.min(distance, Math.max(0, t - 0.02));
            }
            out[n++] = dx * distance;
            out[n++] = dy * distance;
        }
        return out;
    }
}

package ru.main.explorer;

import android.graphics.Path;
import java.util.ArrayList;
import org.json.JSONArray;

/** Мировая подложка: double до вычитания центра, float только в экранной системе. */
final class WorldLand {
    final ArrayList<double[]> rings = new ArrayList<>();
    private double left = 256, right, top = 256, bottom;

    WorldLand(JSONArray coordinates) throws Exception {
        for (int r = 0; r < coordinates.length(); r++) {
            JSONArray ring = coordinates.getJSONArray(r);
            double[] xy = new double[ring.length() * 2];
            for (int i = 0; i < ring.length(); i++) {
                JSONArray point = ring.getJSONArray(i);
                double x = MapView.x(point.getDouble(0)), y = MapView.y(point.getDouble(1));
                xy[i * 2] = x;
                xy[i * 2 + 1] = y;
                left = Math.min(left, x);
                right = Math.max(right, x);
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
            }
            rings.add(xy);
        }
    }

    Path project(double cx, double cy, int zoom, int width, int height) {
        double scale = 1 << zoom;
        // Искусственная граница клиппинга остаётся за экраном, в том числе для обводки.
        double l = cx - (width / 2.0 + 32) / scale;
        double r = cx + (width / 2.0 + 32) / scale;
        double t = cy - (height / 2.0 + 32) / scale;
        double b = cy + (height / 2.0 + 32) / scale;
        Path path = new Path();
        path.setFillType(Path.FillType.EVEN_ODD);
        for (int wrap = -1; wrap <= 1; wrap++) {
            double offset = wrap * 256.0;
            if (right + offset < l || left + offset > r || bottom < t || top > b) continue;
            for (double[] ring : rings) {
                double[] clipped = ring;
                for (int side = 0; side < 4 && clipped.length != 0; side++)
                    clipped =
                            clip(clipped, side, new double[] {l - offset, r - offset, t, b}[side]);
                for (int i = 0; i < clipped.length; i += 2) {
                    float x = (float) ((clipped[i] + offset - cx) * scale + width / 2.0);
                    float y = (float) ((clipped[i + 1] - cy) * scale + height / 2.0);
                    if (i == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                if (clipped.length != 0) path.close();
            }
        }
        return path;
    }

    static Path projectParts(
            java.util.List<float[]> parts,
            double left,
            double top,
            double factor,
            int width,
            int height,
            boolean closed) {
        Path path = new Path();
        path.setFillType(Path.FillType.EVEN_ODD);
        double[] edges = {-32, width + 32.0, -32, height + 32.0};
        for (float[] part : parts) {
            double[] xy = new double[part.length];
            for (int i = 0; i < part.length; i += 2) {
                xy[i] = left + part[i] * factor;
                xy[i + 1] = top + part[i + 1] * factor;
            }
            if (closed) {
                for (int side = 0; side < 4 && xy.length != 0; side++)
                    xy = clip(xy, side, edges[side]);
                for (int i = 0; i < xy.length; i += 2)
                    if (i == 0) path.moveTo((float) xy[i], (float) xy[i + 1]);
                    else path.lineTo((float) xy[i], (float) xy[i + 1]);
                if (xy.length != 0) path.close();
            } else {
                double previousX = Double.NaN, previousY = Double.NaN;
                for (int i = 2; i < xy.length; i += 2) {
                    double ax = xy[i - 2], ay = xy[i - 1], dx = xy[i] - ax, dy = xy[i + 1] - ay;
                    double start = 0, end = 1;
                    double[] p = {-dx, dx, -dy, dy};
                    double[] q = {ax + 32, width + 32 - ax, ay + 32, height + 32 - ay};
                    for (int side = 0; side < 4 && start <= end; side++) {
                        if (p[side] == 0) {
                            if (q[side] < 0) end = -1;
                        } else if (p[side] < 0) start = Math.max(start, q[side] / p[side]);
                        else end = Math.min(end, q[side] / p[side]);
                    }
                    if (start > end) {
                        previousX = previousY = Double.NaN;
                        continue;
                    }
                    double x0 = ax + start * dx, y0 = ay + start * dy;
                    if (x0 != previousX || y0 != previousY) path.moveTo((float) x0, (float) y0);
                    previousX = ax + end * dx;
                    previousY = ay + end * dy;
                    path.lineTo((float) previousX, (float) previousY);
                }
            }
        }
        return path;
    }

    private static double[] clip(double[] points, int side, double edge) {
        int axis = side < 2 ? 0 : 1;
        boolean minimum = side == 0 || side == 2;
        double[] out = new double[points.length * 2 + 4];
        int count = 0;
        int previous = points.length - 2;
        for (int i = 0; i < points.length; i += 2) {
            double a = points[previous + axis], b = points[i + axis];
            boolean insideA = minimum ? a >= edge : a <= edge;
            boolean insideB = minimum ? b >= edge : b <= edge;
            if (insideA != insideB) {
                double fraction = (edge - a) / (b - a);
                out[count++] = points[previous] + (points[i] - points[previous]) * fraction;
                out[count++] =
                        points[previous + 1] + (points[i + 1] - points[previous + 1]) * fraction;
                out[count - 2 + axis] = edge;
            }
            if (insideB) {
                out[count++] = points[i];
                out[count++] = points[i + 1];
            }
            previous = i;
        }
        return java.util.Arrays.copyOf(out, count);
    }
}

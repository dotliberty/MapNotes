package ru.main.explorer;

import android.graphics.*;
import java.util.*;

/** Непересекающиеся прямоугольники подтверждённого покрытия в логических координатах Canvas. */
final class AtlasCoverage {
    private static final int MAX_RECTS = 4096;
    final ArrayList<RectF> rectangles = new ArrayList<>();
    private boolean limited;
    private AtlasCoverage excluded;

    static RectF visible(int width, int height, float fraction) {
        float hw = width / (2 * fraction), hh = height / (2 * fraction);
        return new RectF(width / 2f - hw, height / 2f - hh, width / 2f + hw, height / 2f + hh);
    }

    void append(RectF rectangle) {
        if (!rectangle.isEmpty() && rectangles.size() < MAX_RECTS)
            rectangles.add(new RectF(rectangle));
    }

    void append(AtlasCoverage coverage) {
        for (RectF rectangle : coverage.rectangles) append(rectangle);
    }

    AtlasCoverage intersect(RectF bounds) {
        AtlasCoverage result = new AtlasCoverage();
        for (RectF rectangle : rectangles) {
            RectF clipped = new RectF(rectangle);
            if (clipped.intersect(bounds)) result.append(clipped);
        }
        if (excluded != null) for (RectF cut : excluded.rectangles) result.subtract(cut);
        return result;
    }

    AtlasCoverage intersect(AtlasCoverage other) {
        AtlasCoverage result = new AtlasCoverage();
        for (RectF bounds : other.rectangles) result.append(intersect(bounds));
        if (other.excluded != null)
            for (RectF cut : other.excluded.rectangles) result.subtract(cut);
        return result;
    }

    void subtract(RectF cut) {
        ArrayList<RectF> result = new ArrayList<>();
        for (RectF rectangle : rectangles) {
            RectF intersection = new RectF(rectangle);
            if (!intersection.intersect(cut)) {
                result.add(rectangle);
                continue;
            }
            if (rectangle.top < intersection.top)
                result.add(
                        new RectF(
                                rectangle.left, rectangle.top, rectangle.right, intersection.top));
            if (intersection.bottom < rectangle.bottom)
                result.add(
                        new RectF(
                                rectangle.left,
                                intersection.bottom,
                                rectangle.right,
                                rectangle.bottom));
            if (rectangle.left < intersection.left)
                result.add(
                        new RectF(
                                rectangle.left,
                                intersection.top,
                                intersection.left,
                                intersection.bottom));
            if (intersection.right < rectangle.right)
                result.add(
                        new RectF(
                                intersection.right,
                                intersection.top,
                                rectangle.right,
                                intersection.bottom));
        }
        rectangles.clear();
        if (result.size() <= MAX_RECTS) rectangles.addAll(result);
        else limited = true;
    }

    AtlasCoverage missing(RectF visible) {
        AtlasCoverage result = new AtlasCoverage();
        result.append(visible);
        for (RectF rectangle : rectangles) {
            result.subtract(rectangle);
            if (result.limited) {
                // Не трактуем переполнение разбиения как отсутствие дыр: native clipOutPath
                // задаёт тот же complement без неограниченного массива прямоугольников.
                result = new AtlasCoverage();
                result.append(visible);
                result.excluded = this;
                break;
            }
        }
        return result;
    }

    boolean isEmpty() {
        return rectangles.isEmpty();
    }

    double area() {
        double area = 0;
        for (RectF rectangle : rectangles) area += (double) rectangle.width() * rectangle.height();
        return area;
    }

    double fraction(RectF visible) {
        return Math.min(
                1,
                intersect(visible).area()
                        / Math.max(1, (double) visible.width() * visible.height()));
    }

    void clip(Canvas canvas) {
        canvas.clipPath(path());
        if (excluded != null) canvas.clipOutPath(excluded.path());
    }

    private Path path() {
        Path path = new Path();
        for (RectF rectangle : rectangles) path.addRect(rectangle, Path.Direction.CW);
        return path;
    }
}

package ru.main.explorer;

import java.util.*;

/** Неизменяемый индекс входов тайлов; не удерживает Way, View или Context. */
final class AtlasFeatureIndex {
    final List<AtlasTiles.Feature> features;
    final long bytes;
    private final AtlasTiles.Feature[] entries;
    private final Node root;
    private final boolean ordered;

    private static final class Node {
        double left = Double.POSITIVE_INFINITY, right = Double.NEGATIVE_INFINITY;
        double top = Double.POSITIVE_INFINITY, bottom = Double.NEGATIVE_INFINITY;
        final int start, end;
        final long[] vertices = new long[23];
        Node a, b;

        Node(AtlasTiles.Feature[] entries, int start, int end, boolean spatial) {
            this.start = start;
            this.end = end;
            for (int i = start; i < end; i++) {
                AtlasTiles.Feature f = entries[i];
                left = Math.min(left, f.left);
                right = Math.max(right, f.right);
                top = Math.min(top, f.top);
                bottom = Math.max(bottom, f.bottom);
            }
            if (end - start > 16) {
                boolean horizontal = right - left >= bottom - top;
                if (spatial)
                    Arrays.sort(
                            entries,
                            start,
                            end,
                            Comparator.comparingDouble(
                                    f -> horizontal ? f.left + f.right : f.top + f.bottom));
                int middle = (start + end) >>> 1;
                a = new Node(entries, start, middle, spatial);
                b = new Node(entries, middle, end, spatial);
                for (int z = 0; z <= 22; z++) vertices[z] = a.vertices[z] + b.vertices[z];
            } else {
                for (int z = 0; z <= 22; z++)
                    for (int i = start; i < end; i++)
                        if (visible(entries[i], z)) vertices[z] += entries[i].xy.length / 2;
            }
        }

        boolean visit(
                AtlasTiles.Feature[] entries,
                double cx,
                double cy,
                double dx,
                double dy,
                int zoom,
                java.util.function.Predicate<AtlasTiles.Feature> visitor,
                int[] examined) {
            if (!intersects(left, right, top, bottom, cx, cy, dx, dy) || vertices[zoom] == 0)
                return true;
            if (a != null)
                return a.visit(entries, cx, cy, dx, dy, zoom, visitor, examined)
                        && b.visit(entries, cx, cy, dx, dy, zoom, visitor, examined);
            for (int i = start; i < end; i++) {
                AtlasTiles.Feature f = entries[i];
                examined[0]++;
                if (intersects(f.left, f.right, f.top, f.bottom, cx, cy, dx, dy)
                        && visible(f, zoom)
                        && !visitor.test(f)) return false;
            }
            return true;
        }

        long count(
                AtlasTiles.Feature[] entries,
                double l,
                double t,
                double r,
                double bottom,
                int zoom,
                long limit) {
            if (right < l || left > r || this.bottom < t || top > bottom) return 0;
            if (left >= l && right <= r && top >= t && this.bottom <= bottom) return vertices[zoom];
            if (a != null) {
                long count = a.count(entries, l, t, r, bottom, zoom, limit);
                return count > limit
                        ? count
                        : count + b.count(entries, l, t, r, bottom, zoom, limit - count);
            }
            long count = 0;
            for (int i = start; i < end; i++) {
                AtlasTiles.Feature f = entries[i];
                if (f.right >= l
                        && f.left <= r
                        && f.bottom >= t
                        && f.top <= bottom
                        && visible(f, zoom)) count += f.xy.length / 2;
            }
            return count;
        }

        void query(
                AtlasTiles.Feature[] entries,
                double l,
                double t,
                double r,
                double bottom,
                Set<AtlasTiles.Feature> selected,
                int zoom) {
            if (right < l || left > r || this.bottom < t || top > bottom) return;
            if (zoom >= 0 && vertices[zoom] == 0) return;
            if (a != null) {
                a.query(entries, l, t, r, bottom, selected, zoom);
                b.query(entries, l, t, r, bottom, selected, zoom);
            } else {
                for (int i = start; i < end; i++) {
                    AtlasTiles.Feature f = entries[i];
                    if (f.right >= l
                            && f.left <= r
                            && f.bottom >= t
                            && f.top <= bottom
                            && (zoom < 0 || visible(f, zoom))) selected.add(f);
                }
            }
        }
    }

    AtlasFeatureIndex(List<AtlasTiles.Feature> features) {
        this(features, false);
    }

    AtlasFeatureIndex(List<AtlasTiles.Feature> features, boolean ordered) {
        this.ordered = ordered;
        this.features = Collections.unmodifiableList(new ArrayList<>(features));
        entries = features.toArray(new AtlasTiles.Feature[0]);
        if (ordered)
            Arrays.sort(
                    entries,
                    Comparator.comparingInt((AtlasTiles.Feature f) -> f.pass)
                            .thenComparingInt(f -> f.ordinal));
        root = new Node(entries, 0, entries.length, !ordered);
        long bytes = 256L + entries.length * 64L;
        for (AtlasTiles.Feature f : features) bytes += 128L + f.xy.length * 8L;
        this.bytes = bytes;
    }

    private static boolean intersects(
            double left,
            double right,
            double top,
            double bottom,
            double cx,
            double cy,
            double dx,
            double dy) {
        if (bottom < cy - dy || top > cy + dy) return false;
        for (int wrap = -1; wrap <= 1; wrap++) {
            double center = cx + wrap * 256;
            if (right >= center - dx && left <= center + dx) return true;
        }
        return false;
    }

    /** Обход в pass/ordinal-порядке без сбора/сортировки результата; false останавливает дерево. */
    int visit(
            double cx,
            double cy,
            double dx,
            double dy,
            int zoom,
            java.util.function.Predicate<AtlasTiles.Feature> visitor) {
        if (!ordered) throw new IllegalStateException("Ordered index required");
        int[] examined = {0};
        root.visit(entries, cx, cy, dx, dy, zoom, visitor, examined);
        return examined[0];
    }

    private static boolean visible(AtlasTiles.Feature f, int zoom) {
        double scale = 1 << zoom;
        return (f.pass != 2 || MapDetail.roadVisible(f.kind, zoom))
                && (f.pass != 1
                        || MapDetail.buildingVisible(
                                zoom,
                                (float) ((f.right - f.left) * scale),
                                (float) ((f.bottom - f.top) * scale)));
    }

    long vertices(double cx, double cy, double dx, double dy, int zoom, long limit) {
        long count = 0;
        for (int wrap = -1; wrap <= 1; wrap++) {
            double center = cx + wrap * 256;
            count +=
                    root.count(
                            entries,
                            center - dx,
                            cy - dy,
                            center + dx,
                            cy + dy,
                            zoom,
                            limit - count);
            if (count > limit) break;
        }
        return count;
    }

    List<AtlasTiles.Feature> query(double cx, double cy, double dx, double dy) {
        return query(cx, cy, dx, dy, -1);
    }

    List<AtlasTiles.Feature> query(double cx, double cy, double dx, double dy, int zoom) {
        Set<AtlasTiles.Feature> selected = new HashSet<>();
        for (int wrap = -1; wrap <= 1; wrap++) {
            double center = cx + wrap * 256;
            root.query(entries, center - dx, cy - dy, center + dx, cy + dy, selected, zoom);
        }
        ArrayList<AtlasTiles.Feature> result = new ArrayList<>(selected);
        result.sort(
                Comparator.comparingInt((AtlasTiles.Feature f) -> f.pass)
                        .thenComparingInt(f -> f.ordinal));
        return result;
    }
}

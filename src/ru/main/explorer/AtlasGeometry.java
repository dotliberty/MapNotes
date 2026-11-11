package ru.main.explorer;

import android.graphics.Path;
import android.graphics.RectF;
import java.util.*;

/** Immutable world projection and bounding-volume tree, prepared by the atlas decoder. */
final class AtlasGeometry {
    static final class Entry {
        final OfflineAtlas.Way way;
        final int ordinal, pass;
        final double[] xy;
        final double left, right, top, bottom;
        final String type, name, kind;
        final boolean area;

        Entry(OfflineAtlas.Way way, int ordinal) {
            this.way = way;
            type = way.type;
            name = way.name;
            kind = way.kind;
            area = way.area;
            this.ordinal = ordinal;
            pass = "road".equals(way.type) ? 2 : "building".equals(way.type) ? 1 : 0;
            left = MapView.x(way.west);
            right = MapView.x(way.east);
            top = MapView.y(way.north);
            bottom = MapView.y(way.south);
            xy = new double[way.coords.length];
            for (int i = 0; i < xy.length; i += 2) {
                xy[i] = MapView.x(way.coords[i + 1]);
                xy[i + 1] = MapView.y(way.coords[i]);
            }
        }
    }

    private static final class Node {
        double left = Double.POSITIVE_INFINITY, right = Double.NEGATIVE_INFINITY;
        double top = Double.POSITIVE_INFINITY, bottom = Double.NEGATIVE_INFINITY;
        final int start, end;
        Node a, b;

        Node(Entry[] entries, int start, int end) {
            this.start = start;
            this.end = end;
            for (int i = start; i < end; i++) {
                Entry e = entries[i];
                left = Math.min(left, e.left);
                right = Math.max(right, e.right);
                top = Math.min(top, e.top);
                bottom = Math.max(bottom, e.bottom);
            }
            if (end - start > 16) {
                boolean horizontal = right - left >= bottom - top;
                Arrays.sort(
                        entries,
                        start,
                        end,
                        Comparator.comparingDouble(
                                e -> horizontal ? e.left + e.right : e.top + e.bottom));
                int middle = (start + end) >>> 1;
                a = new Node(entries, start, middle);
                b = new Node(entries, middle, end);
            }
        }

        void query(Entry[] entries, double l, double t, double r, double b, Set<Entry> result) {
            if (right < l || left > r || bottom < t || top > b) return;
            if (a != null) {
                a.query(entries, l, t, r, b, result);
                this.b.query(entries, l, t, r, b, result);
            } else {
                for (int i = start; i < end; i++) {
                    Entry e = entries[i];
                    if (e.right >= l && e.left <= r && e.bottom >= t && e.top <= b) result.add(e);
                }
            }
        }
    }

    final ArrayList<OfflineAtlas.Way> source;
    final Entry[] entries;
    final Node root;
    final int bytes;

    AtlasGeometry(ArrayList<OfflineAtlas.Way> source) {
        this.source = source;
        entries = new Entry[source.size()];
        long size = 128;
        for (int i = 0; i < entries.length; i++) {
            OfflineAtlas.Way w = source.get(i);
            entries[i] = new Entry(w, i);
            // Includes retained source coordinates, projected doubles, tree/references and strings.
            size += 384L + w.coords.length * 16L + (w.name == null ? 0 : w.name.length() * 2L);
        }
        root = new Node(entries, 0, entries.length);
        bytes = (int) Math.min(Integer.MAX_VALUE, size);
    }

    List<Entry> candidates(MapView map) {
        double scale = 1 << map.zoom;
        // One pixel guard covers float rounding at the exact old viewport boundary.
        double dx = (map.getWidth() / 2.0 + 1) / scale;
        double dy = (map.getHeight() / 2.0 + 1) / scale;
        Set<Entry> selected = new HashSet<>();
        for (int wrap = -1; wrap <= 1; wrap++) {
            double cx = map.cx + wrap * 256;
            root.query(entries, cx - dx, map.cy - dy, cx + dx, map.cy + dy, selected);
        }
        ArrayList<Entry> result = new ArrayList<>(selected);
        result.sort(Comparator.comparingInt((Entry e) -> e.pass).thenComparingInt(e -> e.ordinal));
        return result;
    }

    static float screenX(double worldX, MapView map) {
        double dx = worldX - map.cx;
        if (dx > 128) dx -= 256;
        if (dx < -128) dx += 256;
        return (float) (dx * (1 << map.zoom) + map.getWidth() / 2.0);
    }

    static final class Shape {
        final Entry entry;
        final Path path = new Path();
        final int vertices;
        String label;
        RectF labelBox;
        float labelX, labelY;

        Shape(Entry entry, MapView map) {
            this.entry = entry;
            double[] xy = entry.xy;
            int stride = Math.max(2, 2 * (int) Math.ceil(xy.length / 8192.0));
            float previousX = Float.NaN, previousY = Float.NaN;
            int count = 0;
            for (int i = 0; i < xy.length; i = Math.min(xy.length, i + stride)) {
                float x = screenX(xy[i], map);
                float y = (float) ((xy[i + 1] - map.cy) * (1 << map.zoom) + map.getHeight() / 2.0);
                if (i == 0) {
                    path.moveTo(x, y);
                    previousX = x;
                    previousY = y;
                    count++;
                } else if (i == xy.length - 2
                        || Math.abs(x - previousX) + Math.abs(y - previousY) >= 1.5f) {
                    path.lineTo(x, y);
                    previousX = x;
                    previousY = y;
                    count++;
                }
            }
            if (entry.way.area) path.close();
            vertices = count;
        }
    }

    /** UI-owned, viewport-specific shapes. Shared decoded geometry is never mutated. */
    static final class Frame {
        final AtlasGeometry geometry;
        final long stamp;
        final int initialVertices;
        final ArrayList<Shape> shapes = new ArrayList<>();
        final int bytes;
        boolean labelsReady;
        HashSet<String> prefixNames;
        ArrayList<RectF> prefixBoxes;

        Frame(AtlasGeometry geometry, long stamp, MapView map, int initialVertices) {
            this.geometry = geometry;
            this.stamp = stamp;
            this.initialVertices = initialVertices;
            int vertices = initialVertices;
            long size =
                    4096L
                            + (geometry.source instanceof OfflineAtlas.IndexedWays
                                    ? ((OfflineAtlas.IndexedWays) geometry.source).estimatedBytes
                                    : geometry.bytes);
            double scale = 1 << map.zoom;
            for (Entry entry : geometry.candidates(map)) {
                OfflineAtlas.Way w = entry.way;
                float l = map.screenX(w.west), r = map.screenX(w.east);
                float t = (float) ((entry.top - map.cy) * scale + map.getHeight() / 2.0);
                float b = (float) ((entry.bottom - map.cy) * scale + map.getHeight() / 2.0);
                if (r < 0 || l > map.getWidth() || b < 0 || t > map.getHeight()) continue;
                if (entry.pass == 2 && !MapDetail.roadVisible(w.kind, map.zoom)) continue;
                if (entry.pass == 1 && !MapDetail.buildingVisible(map.zoom, r - l, b - t)) continue;
                if (vertices + Math.min(4096, w.coords.length / 2) > 80000) continue;
                Shape shape = new Shape(entry, map);
                shapes.add(shape);
                vertices += shape.vertices;
                size += 256L + shape.vertices * 32L;
            }
            bytes = (int) Math.min(Integer.MAX_VALUE, size);
        }
    }
}

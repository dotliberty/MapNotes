package ru.main.explorer;

import java.util.*;

/** Screen-space connected components: even a chain of overlapping markers stays selectable. */
final class PhotoGroups {
    static final class Group {
        float x, y, anchorX, anchorY, opacity = 1, sizeScale = 1;
        boolean exiting;
        final ArrayList<Store.Photo> photos = new ArrayList<>();

        Group(float x, float y) {
            this.x = x;
            this.y = y;
            anchorX = x;
            anchorY = y;
        }
    }

    /** One callout per photo. Exact GPS anchor is never moved by screen layout. */
    static ArrayList<Group> spread(
            ArrayList<Store.Photo> photos,
            float[] xs,
            float[] ys,
            float size,
            int width,
            int height) {
        ArrayList<Group> result = new ArrayList<>();
        float gap = size + 8;
        ArrayList<Integer> order = new ArrayList<>();
        for (int i = 0; i < photos.size(); i++) order.add(i);
        // Stable photo identity, not database/list traversal order, decides conflict priority.
        Collections.sort(
                order,
                (a, b) ->
                        photos.get(a) == null || photos.get(b) == null
                                ? Integer.compare(a, b)
                                : Long.compare(photos.get(a).id, photos.get(b).id));
        ArrayList<Integer> isolated = new ArrayList<>();
        HashMap<String, ArrayList<Integer>> cells = new HashMap<>();
        for (int i : order) {
            String key = (int) Math.floor(xs[i] / gap) + ":" + (int) Math.floor(ys[i] / gap);
            ArrayList<Integer> cell = cells.get(key);
            if (cell == null) {
                cell = new ArrayList<>();
                cells.put(key, cell);
            }
            cell.add(i);
        }
        for (int i : order) {
            int gx = (int) Math.floor(xs[i] / gap), gy = (int) Math.floor(ys[i] / gap);
            boolean near = false;
            for (int dx = -1; dx <= 1 && !near; dx++)
                for (int dy = -1; dy <= 1 && !near; dy++) {
                    ArrayList<Integer> cell = cells.get((gx + dx) + ":" + (gy + dy));
                    if (cell != null)
                        for (int j : cell)
                            if (j != i
                                    && Math.abs(xs[i] - xs[j]) < gap
                                    && Math.abs(ys[i] - ys[j]) < gap) {
                                near = true;
                                break;
                            }
                }
            if (!near) isolated.add(i);
        }
        int attempted = 0;
        for (int i : order) {
            if (!Float.isFinite(xs[i])
                    || !Float.isFinite(ys[i])
                    || xs[i] < 0
                    || xs[i] > width
                    || ys[i] < 0
                    || ys[i] > height) continue;
            if (result.size() >= 48 || attempted++ >= 96) break;
            Group g = new Group(xs[i], ys[i]);
            g.photos.add(photos.get(i));
            // Reserve exact anchors of isolated cards: a displaced cluster must not push them away.
            boolean conflict = !isolated.contains(i);
            boolean placed = free(g.x, g.y, result, gap);
            if (conflict && !placed) {
                for (int ring = 1; ring <= 24 && !placed; ring++) {
                    int steps = Math.max(8, ring * 8);
                    float radius = ring * gap / 4;
                    for (int side = 0; side < steps && !placed; side++) {
                        double angle = -Math.PI / 2 + side * 2 * Math.PI / steps;
                        float x = xs[i] + (float) Math.cos(angle) * radius,
                                y = ys[i] + (float) Math.sin(angle) * radius;
                        if (x < size / 2
                                || x > width - size / 2
                                || y < size / 2
                                || y > height - size / 2
                                || !free(x, y, result, gap)) continue;
                        boolean reserved = false;
                        for (int j : isolated)
                            if (j != i && Math.abs(x - xs[j]) < gap && Math.abs(y - ys[j]) < gap) {
                                reserved = true;
                                break;
                            }
                        if (!reserved) {
                            g.x = x;
                            g.y = y;
                            placed = true;
                        }
                    }
                }
            }
            if (placed) result.add(g); // Overflow remains available in the gallery.
        }
        return result;
    }

    private static boolean free(float x, float y, ArrayList<Group> groups, float gap) {
        for (Group g : groups) if (Math.abs(x - g.x) < gap && Math.abs(y - g.y) < gap) return false;
        return true;
    }

    static ArrayList<Group> build(
            ArrayList<Store.Photo> photos, float[] xs, float[] ys, float size) {
        int n = photos.size();
        int[] parent = new int[n];
        HashMap<String, ArrayList<Integer>> cells = new HashMap<>();
        for (int i = 0; i < n; i++) {
            parent[i] = i;
            int gx = (int) Math.floor(xs[i] / size), gy = (int) Math.floor(ys[i] / size);
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++) {
                    ArrayList<Integer> near = cells.get((gx + dx) + ":" + (gy + dy));
                    if (near != null)
                        for (int j : near)
                            if (Math.abs(xs[i] - xs[j]) <= size
                                    && Math.abs(ys[i] - ys[j]) <= size) {
                                int a = root(parent, i), b = root(parent, j);
                                parent[Math.max(a, b)] = Math.min(a, b);
                            }
                }
            String key = gx + ":" + gy;
            ArrayList<Integer> bucket = cells.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                cells.put(key, bucket);
            }
            bucket.add(i);
        }
        LinkedHashMap<Integer, Group> result = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int r = root(parent, i);
            Group g = result.get(r);
            if (g == null) {
                g = new Group(xs[r], ys[r]);
                result.put(r, g);
            }
            g.photos.add(photos.get(i));
        }
        return new ArrayList<>(result.values());
    }

    private static int root(int[] p, int i) {
        while (i != p[i]) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }
}

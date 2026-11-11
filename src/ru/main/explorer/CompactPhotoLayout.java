package ru.main.explorer;

import java.util.*;

/** Linear viewport layout: fixed small markers, no collision rings or displacement animations. */
final class CompactPhotoLayout {
    static final int MAX_MARKERS = 96;

    private static final class Entry {
        final Store.Photo photo;
        final double x, y;

        Entry(Store.Photo photo) {
            this.photo = photo;
            x = MapView.x(photo.lon);
            y = MapView.y(photo.lat);
        }
    }

    private final ArrayList<Entry> entries = new ArrayList<>();
    private ArrayList<PhotoGroups.Group> groups;
    private double cx, cy;
    private int zoom, width, height;
    private float fraction, density;
    int visibleCount;
    int builds;

    void setPhotos(ArrayList<Store.Photo> photos) {
        entries.clear();
        for (Store.Photo photo : photos) entries.add(new Entry(photo));
        entries.sort(Comparator.comparingLong(entry -> entry.photo.id));
        groups = null;
    }

    ArrayList<PhotoGroups.Group> groups(MapView map) {
        float currentDensity = map.getResources().getDisplayMetrics().density;
        if (groups != null
                && cx == map.cx
                && cy == map.cy
                && zoom == map.zoom
                && width == map.getWidth()
                && height == map.getHeight()
                && fraction == map.viewportFraction
                && density == currentDensity) return groups;
        cx = map.cx;
        cy = map.cy;
        zoom = map.zoom;
        width = map.getWidth();
        height = map.getHeight();
        fraction = map.viewportFraction;
        density = currentDensity;
        builds++;
        visibleCount = 0;
        groups = new ArrayList<>();
        HashMap<Long, PhotoGroups.Group> cells = new HashMap<>();
        double scale = (1 << zoom) * (double) fraction;
        float cellSize = map.dp(28);
        for (Entry entry : entries) {
            double dx = entry.x - cx;
            if (dx > 128) dx -= 256;
            if (dx < -128) dx += 256;
            float x = (float) (width / 2.0 + dx * scale);
            float y = (float) (height / 2.0 + (entry.y - cy) * scale);
            if (!Float.isFinite(x)
                    || !Float.isFinite(y)
                    || x < 0
                    || x > width
                    || y < 0
                    || y > height - map.dp(38)) continue;
            visibleCount++;
            int gx = (int) Math.floor(x / cellSize);
            int gy = (int) Math.floor(y / cellSize);
            long key = ((long) gx << 32) ^ (gy & 0xffffffffL);
            PhotoGroups.Group group = cells.get(key);
            if (group == null) {
                if (groups.size() == MAX_MARKERS) continue;
                group = new PhotoGroups.Group(x, y);
                cells.put(key, group);
                groups.add(group);
            }
            group.photos.add(entry.photo);
        }
        return groups;
    }
}

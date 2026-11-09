package ru.main.explorer;

import android.content.*;
import android.graphics.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** Disk index and bounded geometry cache: large packs are never decoded as one JSON. */
public class OfflineAtlas {
    static final class IndexedWays extends ArrayList<Way> {
        final HashMap<Long, ArrayList<Way>> grid = new HashMap<>();
        final ArrayList<Way> large = new ArrayList<>();

        static long key(int x, int y) {
            return ((long) x << 32) ^ (y & 0xffffffffL);
        }

        void index() {
            for (Way w : this) {
                if (!w.area || !"building".equals(w.type)) continue;
                int x0 = (int) Math.floor(w.west * 500),
                        x1 = (int) Math.floor(w.east * 500),
                        y0 = (int) Math.floor(w.south * 1000),
                        y1 = (int) Math.floor(w.north * 1000);
                if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) > 256) {
                    large.add(w);
                    continue;
                }
                for (int x = x0; x <= x1; x++)
                    for (int y = y0; y <= y1; y++)
                        grid.computeIfAbsent(key(x, y), k -> new ArrayList<>()).add(w);
            }
        }

        Collection<Way> near(double south, double west, double north, double east) {
            HashSet<Way> result = new HashSet<>(large);
            for (int x = (int) Math.floor(west * 500); x <= (int) Math.floor(east * 500); x++)
                for (int y = (int) Math.floor(south * 1000);
                        y <= (int) Math.floor(north * 1000);
                        y++) {
                    ArrayList<Way> bucket = grid.get(key(x, y));
                    if (bucket != null) result.addAll(bucket);
                }
            return result;
        }
    }

    static class Way {
        double[] coords;
        String type, name, kind = "";
        boolean area;
        double south = 90, west = 180, north = -90, east = -180;
    }

    static class Area {
        boolean buildingsOnly, overview, legacy;
        double south, west, north, east;
        File source;
        long stamp;
        ArrayList<Way> eager;
    }

    final ArrayList<Area> areas = new ArrayList<>();
    final Paint paint = new Paint(3);
    private int count;
    private final boolean async;
    private final Runnable changed;
    private final java.util.concurrent.ExecutorService loader;
    private final Set<String> pending = Collections.synchronizedSet(new HashSet<>());
    private static final Typeface LABEL_FONT = Typeface.create("serif", Typeface.NORMAL);
    int lastLabels, lastVertices;
    static final int OVERVIEW_ZOOM = 15;
    private final android.util.LruCache<String, ArrayList<Way>> geometry =
            new android.util.LruCache<String, ArrayList<Way>>(CacheBudget.geometry()) {
                protected int sizeOf(String key, ArrayList<Way> ways) {
                    int size = 0;
                    for (Way w : ways) size += w.coords.length * 8 + 96;
                    return Math.max(1, size);
                }
            };

    public OfflineAtlas(Context context) {
        this(context, false, null);
    }

    public OfflineAtlas(Context context, boolean async, Runnable changed) {
        this.async = async;
        this.changed = changed;
        loader = async ? java.util.concurrent.Executors.newSingleThreadExecutor() : null;
        for (String folder : new String[] {"regions", "visibility"}) {
            File[] files =
                    new File(context.getFilesDir(), folder)
                            .listFiles((d, n) -> n.endsWith(".json"));
            if (files == null) continue;
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File file : files)
                try {
                    JSONObject root = new JSONObject(new String(read(file), "UTF-8"));
                    if (root.has("remark")) continue;
                    Area a = area(root.getJSONArray("mapnotes_bounds"), file);
                    a.buildingsOnly = root.optBoolean("mapnotes_buildings_only", false);
                    a.legacy = true;
                    boolean redundant = false;
                    for (Area old : areas)
                        if (old.south <= a.south
                                && old.west <= a.west
                                && old.north >= a.north
                                && old.east >= a.east) {
                            redundant = true;
                            break;
                        }
                    if (redundant) continue;
                    areas.add(a);
                    if (!a.buildingsOnly) count++;
                } catch (Exception ignored) {
                }
        }
        File[] packs = new File(context.getFilesDir(), "packs").listFiles(File::isDirectory);
        if (packs == null) return;
        for (File pack : packs)
            try {
                File manifest = new File(pack, "pack.json");
                if (!manifest.exists()) continue;
                JSONObject root =
                        new JSONObject(new String(MapPack.read(manifest, 65536), "UTF-8"));
                JSONArray bounds = root.getJSONArray("bounds");
                if (root.optBoolean("complete")) count++;
                File overview = new File(pack, "overview.json.gz");
                if (overview.exists()) {
                    Area a = area(bounds, overview);
                    a.overview = true;
                    areas.add(a);
                }
                File[] indexes = pack.listFiles((d, n) -> n.endsWith(".meta"));
                if (indexes != null)
                    for (File index : indexes) {
                        JSONObject meta =
                                new JSONObject(new String(MapPack.read(index, 65536), "UTF-8"));
                        File data = new File(pack, meta.getString("file"));
                        if (data.exists()) areas.add(area(meta.getJSONArray("bounds"), data));
                    }
            } catch (Exception ignored) {
            }
    }

    static Area area(JSONArray bounds, File file) throws Exception {
        Area a = new Area();
        a.south = bounds.getDouble(0);
        a.west = bounds.getDouble(1);
        a.north = bounds.getDouble(2);
        a.east = bounds.getDouble(3);
        a.source = file;
        a.stamp = 31L * file.getPath().hashCode() + file.lastModified() + file.length();
        return a;
    }

    static ArrayList<Way> decode(JSONObject root) throws Exception {
        return decode(root, false);
    }

    // Overview geometry for small scales (zoom<OVERVIEW_ZOOM): buildings are invisible there anyway
    // and vertices closer than ~4 m to the kept polyline are dropped, so a large overview stays
    // decoded instead of thrashing.
    static ArrayList<Way> decode(JSONObject root, boolean overview) throws Exception {
        IndexedWays ways = new IndexedWays();
        JSONArray els = root.getJSONArray("elements");
        for (int i = 0; i < els.length(); i++) {
            JSONObject e = els.getJSONObject(i), tags = e.optJSONObject("tags");
            JSONArray g = e.optJSONArray("geometry");
            if (tags == null || g == null || g.length() < 2) continue;
            String type =
                    tags.has("building")
                            ? "building"
                            : tags.has("highway")
                                    ? "road"
                                    : tags.has("waterway")
                                                    || "water".equals(tags.optString("natural"))
                                            ? "water"
                                            : "land";
            if (overview && "building".equals(type)) continue;
            Way w = new Way();
            w.type = type;
            w.kind = tags.optString("highway", "");
            w.name = tags.optString("name", "");
            w.coords = new double[g.length() * 2];
            for (int j = 0; j < g.length(); j++) {
                JSONObject pt = g.getJSONObject(j);
                double lat = pt.getDouble("lat"), lon = pt.getDouble("lon");
                w.coords[j * 2] = lat;
                w.coords[j * 2 + 1] = lon;
                w.south = Math.min(w.south, lat);
                w.north = Math.max(w.north, lat);
                w.west = Math.min(w.west, lon);
                w.east = Math.max(w.east, lon);
            }
            if (overview) w.coords = simplify(w.coords);
            w.area =
                    w.coords[0] == w.coords[w.coords.length - 2]
                            && w.coords[1] == w.coords[w.coords.length - 1];
            ways.add(w);
        }
        ways.index();
        return ways;
    }

    static double[] simplify(double[] coords) {
        if (coords.length <= 6) return coords;
        double step = 4.0 / 111195.08, scale = Math.cos(Math.toRadians(coords[0]));
        double[] out = new double[coords.length];
        out[0] = coords[0];
        out[1] = coords[1];
        int n = 2;
        double lastLat = coords[0], lastLon = coords[1];
        for (int i = 2; i < coords.length - 2; i += 2) {
            if (Math.abs(coords[i] - lastLat) >= step
                    || Math.abs(coords[i + 1] - lastLon) * scale >= step) {
                out[n++] = coords[i];
                out[n++] = coords[i + 1];
                lastLat = coords[i];
                lastLon = coords[i + 1];
            }
        }
        out[n++] = coords[coords.length - 2];
        out[n++] = coords[coords.length - 1];
        return n == coords.length ? coords : Arrays.copyOf(out, n);
    }

    ArrayList<Way> ways(Area area) {
        return ways(area, OVERVIEW_ZOOM);
    }

    ArrayList<Way> ways(Area area, int zoom) {
        String key = area.source.getPath() + (zoom < OVERVIEW_ZOOM ? "#o" : "");
        ArrayList<Way> ready = geometry.get(key);
        if (ready != null) return ready;
        if (!async) return loadWays(area, zoom < OVERVIEW_ZOOM);
        if (pending.size() < 8 && pending.add(key) && !loader.isShutdown())
            loader.execute(
                    () -> {
                        try {
                            loadWays(area, zoom < OVERVIEW_ZOOM);
                        } finally {
                            pending.remove(key);
                            if (changed != null) changed.run();
                        }
                    });
        return new ArrayList<>();
    }

    // Transfer only immutable decoded data whose indexed file identity is unchanged.
    void reuseGeometry(OfflineAtlas previous) {
        if (previous == null) return;
        HashMap<String, Long> stamps = new HashMap<>();
        for (Area area : previous.areas) stamps.put(area.source.getPath(), area.stamp);
        for (Area area : areas) {
            String path = area.source.getPath();
            Long stamp = stamps.get(path);
            if (stamp == null || stamp != area.stamp) continue;
            for (String suffix : new String[] {"", "#o"}) {
                ArrayList<Way> ready = previous.geometry.get(path + suffix);
                if (ready != null) geometry.put(path + suffix, ready);
            }
        }
    }

    public void close() {
        if (loader != null) loader.shutdownNow();
    }

    synchronized ArrayList<Way> loadWays(Area area) {
        if (area.eager != null) return area.eager;
        String key = area.source.getPath();
        ArrayList<Way> data = geometry.get(key);
        if (data == null)
            try {
                data = decode(new JSONObject(new String(read(area.source), "UTF-8")));
                geometry.put(key, data);
            } catch (Exception e) {
                data = new ArrayList<>();
            }
        return data;
    }

    synchronized ArrayList<Way> loadWays(Area area, boolean overview) {
        if (!overview) return loadWays(area);
        if (area.eager != null) return area.eager;
        String key = area.source.getPath() + "#o";
        ArrayList<Way> data = geometry.get(key);
        if (data == null)
            try {
                data = decode(new JSONObject(new String(read(area.source), "UTF-8")), true);
                geometry.put(key, data);
            } catch (Exception e) {
                data = new ArrayList<>();
            }
        return data;
    }

    public List<double[]> buildingsNear(double lat, double lon, double radius) {
        double dy = radius / 111195.08, dx = dy / Math.cos(Math.toRadians(lat));
        ArrayList<double[]> buildings = new ArrayList<>();
        for (Area area : areas) {
            if (area.overview
                    || area.south > lat + dy
                    || area.north < lat - dy
                    || area.west > lon + dx
                    || area.east < lon - dx) continue;
            for (Way w : nearWays(area, lat - dy, lon - dx, lat + dy, lon + dx))
                if (w.area
                        && "building".equals(w.type)
                        && w.south <= lat + dy
                        && w.north >= lat - dy
                        && w.west <= lon + dx
                        && w.east >= lon - dx) buildings.add(w.coords);
        }
        return buildings;
    }

    Collection<Way> nearWays(Area area, double south, double west, double north, double east) {
        ArrayList<Way> all = loadWays(area);
        return all instanceof IndexedWays
                ? ((IndexedWays) all).near(south, west, north, east)
                : all;
    }

    String visibilityStamp(double lat, double lon, double radius) {
        double dy = radius / 111195.08, dx = dy / Math.cos(Math.toRadians(lat));
        long stamp = 0;
        for (Area a : areas)
            if (!a.overview
                    && a.south <= lat + dy
                    && a.north >= lat - dy
                    && a.west <= lon + dx
                    && a.east >= lon - dx) stamp += a.stamp;
        return Long.toHexString(stamp);
    }

    public boolean covers(double lat, double lon) {
        for (Area a : areas)
            if (!a.overview && lat >= a.south && lat <= a.north && lon >= a.west && lon <= a.east)
                return true;
        return false;
    }

    public boolean coversRadius(double lat, double lon, double radius) {
        double dy = radius / 111195.08, dx = dy / Math.cos(Math.toRadians(lat));
        for (int y = -1; y <= 1; y++)
            for (int x = -1; x <= 1; x++) if (!covers(lat + y * dy, lon + x * dx)) return false;
        return true;
    }

    public int regionCount() {
        return count;
    }

    public static byte[] read(File f) throws IOException {
        return MapPack.read(f, 16000000);
    }

    public void draw(Canvas canvas, MapView map, boolean hybrid) {
        if (map.zoom < 8) return;
        double scale = 1 << map.zoom;
        lastLabels = 0;
        lastVertices = 0;
        HashSet<String> labelNames = new HashSet<>();
        ArrayList<RectF> labelBoxes = new ArrayList<>();
        for (Area a : areas) {
            if (a.overview && map.zoom >= 13) continue;
            if (!a.overview && !a.legacy && map.zoom < 13) continue;
            if (a.legacy && map.zoom < 11) continue;
            float left = map.screenX(a.west),
                    right = map.screenX(a.east),
                    top = sy(a.north, map, scale),
                    bottom = sy(a.south, map, scale);
            if (right < 0
                    || left > map.getWidth()
                    || bottom < 0
                    || top > map.getHeight()
                    || right <= left) continue;
            int save = canvas.save();
            canvas.clipRect(left, top, right, bottom);
            paint.setColorFilter(null);
            paint.setStyle(Paint.Style.FILL);
            if (!hybrid && !a.buildingsOnly && !map.illustrated) {
                paint.setColor(0xffc6c6b0);
                canvas.drawRect(left, top, right, bottom, paint);
            }
            ArrayList<Way> data = ways(a, map.zoom);
            for (int pass = 0; pass < 3; pass++)
                for (Way w : data) {
                    boolean road = "road".equals(w.type);
                    int order = road ? 2 : "building".equals(w.type) ? 1 : 0;
                    if (order != pass) continue;
                    float wl = map.screenX(w.west),
                            wr = map.screenX(w.east),
                            wt = sy(w.north, map, scale),
                            wb = sy(w.south, map, scale);
                    if (wr < 0 || wl > map.getWidth() || wb < 0 || wt > map.getHeight()) continue;
                    if (road && !MapDetail.roadVisible(w.kind, map.zoom)) continue;
                    if ("building".equals(w.type)
                            && !MapDetail.buildingVisible(map.zoom, wr - wl, wb - wt)) continue;
                    if (lastVertices + Math.min(4096, w.coords.length / 2) > 80000) continue;
                    Path p = new Path();
                    int stride = Math.max(2, 2 * (int) Math.ceil(w.coords.length / 8192.0));
                    float previousX = Float.NaN, previousY = Float.NaN;
                    for (int i = 0;
                            i < w.coords.length;
                            i = Math.min(w.coords.length, i + stride)) {
                        float xx = map.screenX(w.coords[i + 1]), yy = sy(w.coords[i], map, scale);
                        if (i == 0) {
                            p.moveTo(xx, yy);
                            previousX = xx;
                            previousY = yy;
                            lastVertices++;
                        } else if (i == w.coords.length - 2
                                || Math.abs(xx - previousX) + Math.abs(yy - previousY) >= 1.5f) {
                            p.lineTo(xx, yy);
                            previousX = xx;
                            previousY = yy;
                            lastVertices++;
                        }
                    }
                    if (w.area) p.close();
                    if (!road && w.area && (!hybrid || map.illustrated)) {
                        paint.setStyle(Paint.Style.FILL);
                        paint.setColor(
                                map.illustrated
                                        ? ("water".equals(w.type)
                                                ? (hybrid ? 0xff78aeb7 : 0xff9cabb1)
                                                : "building".equals(w.type)
                                                        ? (hybrid ? 0xffe5d8b6 : 0xffb9b7a2)
                                                        : (hybrid ? 0xffa6bf89 : 0xffbebfa8))
                                        : ("water".equals(w.type)
                                                ? 0xffa9b6ba
                                                : "building".equals(w.type)
                                                        ? 0xffa3a8a5
                                                        : 0xffbfc6be));
                        canvas.drawPath(p, paint);
                    }
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeJoin(Paint.Join.ROUND);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setStrokeWidth(
                            road ? map.dp(1) * MapDetail.roadWidth(map.zoom) : map.dp(1) * .6f);
                    paint.setColor(
                            map.illustrated
                                    ? (road ? 0xfff5e9c9 : 0xff8b9c7b)
                                    : (hybrid
                                            ? (road ? 0xfff4e9a4 : 0xddd9e0d4)
                                            : (road ? 0xfff5f5ee : 0xff7d8982)));
                    canvas.drawPath(p, paint);
                    if (road
                            && map.zoom >= 16
                            && !w.name.isEmpty()
                            && lastLabels < 24
                            && !labelNames.contains(w.name)) {
                        paint.setStyle(Paint.Style.FILL);
                        paint.setColor(hybrid ? Color.WHITE : 0xff33463c);
                        paint.setTypeface(LABEL_FONT);
                        paint.setTextSize(map.dp(11));
                        float xx = map.screenX(w.coords[1]), yy = sy(w.coords[0], map, scale);
                        if (xx > 0
                                && xx < map.getWidth() - map.dp(90)
                                && yy > map.dp(20)
                                && yy < map.getHeight() - map.dp(45)) {
                            String label = w.name.length() > 48 ? w.name.substring(0, 48) : w.name;
                            RectF box =
                                    new RectF(
                                            xx,
                                            yy - map.dp(18),
                                            xx + paint.measureText(label) + map.dp(12),
                                            yy + map.dp(8));
                            boolean overlaps = false;
                            for (RectF other : labelBoxes)
                                if (RectF.intersects(box, other)) {
                                    overlaps = true;
                                    break;
                                }
                            if (!overlaps) {
                                labelBoxes.add(box);
                                labelNames.add(w.name);
                                lastLabels++;
                                canvas.drawText(label, xx + map.dp(3), yy - map.dp(3), paint);
                            }
                        }
                    }
                }
            paint.setStyle(Paint.Style.FILL);
            canvas.restoreToCount(save);
        }
    }

    float sy(double lat, MapView m, double s) {
        return (float) ((MapView.y(lat) - m.cy) * s + m.getHeight() / 2.0);
    }
}

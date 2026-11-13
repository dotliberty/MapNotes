package ru.main.explorer;

import android.content.*;
import android.graphics.*;
import android.util.JsonReader;
import android.util.JsonToken;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Disk index and bounded geometry cache: large packs are never decoded as one JSON. */
public class OfflineAtlas implements MapTrace.SourceProvider {
    final long downloadGeneration = DownloadedMaps.generation();

    static final class IndexedWays extends ArrayList<Way> {
        final HashMap<Long, ArrayList<Way>> grid = new HashMap<>();
        final ArrayList<Way> large = new ArrayList<>();
        AtlasGeometry renderGeometry;
        List<AtlasTiles.Feature> tileFeatures;
        AtlasFeatureIndex tileIndex, labelIndex;
        final long tileIdentity = TILE_IDENTITIES.incrementAndGet();
        int estimatedBytes;
        boolean compacted;
        AtlasDiskSource disk;

        static long key(int x, int y) {
            return ((long) x << 32) ^ (y & 0xffffffffL);
        }

        void index() {
            renderGeometry = new AtlasGeometry(this);
            ArrayList<AtlasGeometry.Entry> ordered =
                    new ArrayList<>(Arrays.asList(renderGeometry.entries));
            ordered.sort(
                    Comparator.comparingInt((AtlasGeometry.Entry e) -> e.pass)
                            .thenComparingInt(e -> e.ordinal));
            ArrayList<AtlasTiles.Feature> frozen = new ArrayList<>();
            for (AtlasGeometry.Entry entry : ordered) frozen.add(new AtlasTiles.Feature(entry));
            tileFeatures = Collections.unmodifiableList(frozen);
            tileIndex = new AtlasFeatureIndex(tileFeatures);
            ArrayList<AtlasTiles.Feature> labels = new ArrayList<>();
            for (AtlasTiles.Feature feature : frozen)
                if (feature.pass == 2 && !feature.name.isEmpty()) labels.add(feature);
            labelIndex = new AtlasFeatureIndex(labels, true);
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
            long size =
                    renderGeometry.bytes
                            + 16L * large.size()
                            + 128L * frozen.size()
                            + tileIndex.structureBytes
                            + labelIndex.structureBytes;
            for (ArrayList<Way> bucket : grid.values()) size += 112L + bucket.size() * 16L;
            estimatedBytes = (int) Math.min(Integer.MAX_VALUE, size);
        }

        IndexedWays compact() {
            if (compacted) return this;
            IndexedWays result = new IndexedWays();
            result.compacted = true;
            result.tileFeatures = tileFeatures;
            result.tileIndex = tileIndex;
            result.labelIndex = labelIndex;
            result.grid.putAll(grid);
            result.large.addAll(large);
            long size = 1024L + tileIndex.structureBytes + labelIndex.structureBytes;
            for (AtlasTiles.Feature feature : tileFeatures)
                size +=
                        128L
                                + feature.xy.length * 8L
                                + feature.name.length() * 2L
                                + feature.kind.length() * 2L;
            // Raw lat/lon остаются для всех стен. Для дорог достаточно immutable projected xy.
            for (Way way : this)
                if (way.area && "building".equals(way.type)) {
                    result.add(way);
                    size += 112L + way.coords.length * 8L;
                }
            for (ArrayList<Way> bucket : grid.values()) size += 112L + bucket.size() * 16L;
            size += large.size() * 16L;
            result.estimatedBytes = (int) Math.min(Integer.MAX_VALUE, size);
            return result;
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

    private static final java.util.concurrent.atomic.AtomicLong TILE_IDENTITIES =
            new java.util.concurrent.atomic.AtomicLong();
    private static final AtlasFeatureIndex EMPTY_FEATURES =
            new AtlasFeatureIndex(Collections.emptyList());
    private static final AtlasFeatureIndex EMPTY_LABELS =
            new AtlasFeatureIndex(Collections.emptyList(), true);

    static class Area {
        boolean buildingsOnly, overview, legacy;
        double south, west, north, east;
        File source;
        long stamp;
        String packKey;
        ArrayList<Way> eager;
    }

    final ArrayList<Area> areas = new ArrayList<>();
    private ArrayList<AreaSignature> replayCatalog = new ArrayList<>();
    private Map<String, String> replayFamilyKeys = Collections.emptyMap();

    private static final class AreaSignature {
        final java.lang.ref.WeakReference<Area> area;
        final java.lang.ref.WeakReference<ArrayList<Way>> eager;
        final boolean hadEager, overview, buildingsOnly, legacy;
        final String path, pack;
        final long stamp;
        final double south, west, north, east;

        AreaSignature(Area a) {
            area = new java.lang.ref.WeakReference<>(a);
            eager = new java.lang.ref.WeakReference<>(a.eager);
            hadEager = a.eager != null;
            path = a.source == null ? null : a.source.getPath();
            pack = a.packKey;
            stamp = a.stamp;
            south = a.south;
            west = a.west;
            north = a.north;
            east = a.east;
            overview = a.overview;
            buildingsOnly = a.buildingsOnly;
            legacy = a.legacy;
        }

        boolean matches(Area a) {
            return area.get() == a
                    && Objects.equals(path, a.source == null ? null : a.source.getPath())
                    && Objects.equals(pack, a.packKey)
                    && stamp == a.stamp
                    && south == a.south
                    && west == a.west
                    && north == a.north
                    && east == a.east
                    && overview == a.overview
                    && buildingsOnly == a.buildingsOnly
                    && legacy == a.legacy
                    && hadEager == (a.eager != null)
                    && eager.get() == a.eager;
        }
    }

    private Map<String, String> replayFamilies() {
        boolean same = replayCatalog.size() == areas.size();
        if (same)
            for (int i = 0; i < areas.size(); i++)
                if (!replayCatalog.get(i).matches(areas.get(i))) {
                    same = false;
                    break;
                }
        if (same) return replayFamilyKeys;
        HashMap<String, StringBuilder> families = new HashMap<>();
        ArrayList<AreaSignature> catalog = new ArrayList<>();
        for (Area a : areas) {
            catalog.add(new AreaSignature(a));
            if (a.packKey == null) continue;
            families.computeIfAbsent(a.packKey, keyPart -> new StringBuilder(keyPart))
                    .append('|')
                    .append(a.source.getPath())
                    .append(':')
                    .append(a.stamp)
                    .append(':')
                    .append(a.south)
                    .append(':')
                    .append(a.west)
                    .append(':')
                    .append(a.north)
                    .append(':')
                    .append(a.east)
                    .append(':')
                    .append(a.overview)
                    .append(':')
                    .append(a.buildingsOnly)
                    .append(':')
                    .append(a.eager == null ? 0 : System.identityHashCode(a.eager));
        }
        HashMap<String, String> keys = new HashMap<>();
        for (Map.Entry<String, StringBuilder> entry : families.entrySet())
            keys.put(entry.getKey(), "pack:" + entry.getValue());
        replayCatalog = catalog;
        replayFamilyKeys = keys;
        return keys;
    }

    final Paint paint = new Paint(3);
    private int count;
    private final boolean async;
    private final Runnable changed;
    private final java.util.concurrent.ExecutorService loader;
    private final Set<String> pending = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, IndexedWays> diskGeometry =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> failedGeometry = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<String> importingGeometry =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, MapTrace.SourceState> sourceOutcomes =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final File geometryDirectory;
    private static final Typeface LABEL_FONT = Typeface.create("serif", Typeface.NORMAL);
    int lastLabels, lastVertices;
    int preparedFrames;
    int tileDraws;
    String tileFallback = "none";
    long snapshotNanos, tileNanos, geometryNanos;
    int geometryRequests;
    int loadingLayers;
    volatile int geometryLoads;
    volatile int geometryStreams;
    final AtlasRasterCache raster = new AtlasRasterCache();
    final AtlasTiles tiles;
    final AtlasViewport viewport;
    String renderBackend = "none";
    AtlasCoverage lastCoverage = new AtlasCoverage();
    double lastCoverageFraction, lastColorCoverageFraction;
    private String rasterViewport;
    private final java.util.concurrent.atomic.AtomicLong rasterRevision =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean closed;
    private double frameX = Double.NaN, frameY;
    private float frameDensity;
    private int frameZoom, frameWidth, frameHeight;
    static final int FRAME_BYTES = 32 * 1024 * 1024;
    final android.util.LruCache<Area, AtlasGeometry.Frame> frames =
            new android.util.LruCache<Area, AtlasGeometry.Frame>(FRAME_BYTES) {
                protected int sizeOf(Area area, AtlasGeometry.Frame frame) {
                    return Math.max(1, frame.bytes);
                }
            };
    static final int OVERVIEW_ZOOM = 15;
    private final android.util.LruCache<String, ArrayList<Way>> geometry =
            new android.util.LruCache<String, ArrayList<Way>>(CacheBudget.geometry()) {
                protected int sizeOf(String key, ArrayList<Way> ways) {
                    int size = 0;
                    if (ways instanceof IndexedWays && ((IndexedWays) ways).estimatedBytes > 0)
                        return ((IndexedWays) ways).estimatedBytes;
                    for (Way w : ways) size += w.coords.length * 8 + 96;
                    return Math.max(1, size);
                }
            };

    public OfflineAtlas(Context context) {
        this(context, false, null);
    }

    public OfflineAtlas(Context context, boolean async, Runnable changed) {
        this(context, async, changed, changed);
    }

    public OfflineAtlas(Context context, boolean async, Runnable changed, Runnable tileChanged) {
        this.async = async;
        this.changed = changed;
        geometryDirectory = context.getCacheDir();
        tiles = new AtlasTiles(tileChanged);
        viewport = new AtlasViewport(tileChanged);
        loader = async ? java.util.concurrent.Executors.newSingleThreadExecutor() : null;
        for (String folder : new String[] {"regions", "visibility"}) {
            File[] files =
                    new File(context.getFilesDir(), folder)
                            .listFiles((d, n) -> n.endsWith(".json"));
            if (files == null) continue;
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File file : files)
                try {
                    Area a = legacyArea(file);
                    if (a == null) continue;
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
        if (packs == null) {
            MapTrace.registerSources(this);
            DownloadedMaps.register(this);
            return;
        }
        for (File pack : packs)
            try {
                File manifest = new File(pack, "pack.json");
                if (!manifest.exists()) continue;
                JSONObject root =
                        new JSONObject(new String(MapPack.read(manifest, 65536), "UTF-8"));
                JSONArray bounds = root.getJSONArray("bounds");
                String packKey =
                        manifest.getPath()
                                + ":"
                                + manifest.lastModified()
                                + ":"
                                + manifest.length()
                                + ":"
                                + bounds
                                + ":"
                                + root.optBoolean("complete");
                if (root.optBoolean("complete")) count++;
                File overview = new File(pack, "overview.json.gz");
                if (overview.exists()) {
                    Area a = area(bounds, overview);
                    a.overview = true;
                    a.packKey = packKey;
                    areas.add(a);
                }
                File[] indexes = pack.listFiles((d, n) -> n.endsWith(".meta"));
                if (indexes != null)
                    for (File index : indexes) {
                        JSONObject meta =
                                new JSONObject(new String(MapPack.read(index, 65536), "UTF-8"));
                        File data = new File(pack, meta.getString("file"));
                        if (data.exists()) {
                            Area a = area(meta.getJSONArray("bounds"), data);
                            a.packKey = packKey;
                            areas.add(a);
                        }
                    }
            } catch (Exception ignored) {
            }
        MapTrace.registerSources(this);
        DownloadedMaps.register(this);
    }

    @Override
    public void appendSourceStates(StringBuilder text, long nanos) {
        if (closed) return;
        // snapshot не меняет recency LRU; здесь нет file IO и общего monitor map/walls.
        Map<String, ArrayList<Way>> memory = geometry.snapshot();
        for (Area area : areas) {
            if (area.source == null) continue;
            String path = area.source.getPath();
            for (MapTrace.SourceVariant variant : MapTrace.SourceVariant.values()) {
                String key =
                        path
                                + (variant == MapTrace.SourceVariant.OVERVIEW
                                        ? "#o"
                                        : variant == MapTrace.SourceVariant.WALLS ? "#walls" : "");
                boolean ready =
                        area.eager != null
                                || memory.containsKey(key)
                                || diskGeometry.containsKey(key);
                if (variant == MapTrace.SourceVariant.WALLS)
                    ready |= memory.containsKey(path) || diskGeometry.containsKey(path);
                MapTrace.SourceState state =
                        ready
                                ? MapTrace.SourceState.READY
                                : importingGeometry.contains(key)
                                        ? MapTrace.SourceState.IMPORTING
                                        : pending.contains(key)
                                                ? MapTrace.SourceState.QUEUED
                                                : sourceOutcomes.getOrDefault(
                                                        key,
                                                        failedGeometry.contains(key)
                                                                ? MapTrace.SourceState.FAILED
                                                                : MapTrace.SourceState.MISSING);
                MapTrace.sourceState(text, nanos, MapTrace.id(area), variant, state, area.stamp);
            }
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

    /** Читаются только четыре bounds и флаги; elements/неизвестные поля не материализуются. */
    static Area legacyArea(File file) throws Exception {
        JSONArray bounds = null;
        boolean buildingsOnly = false, remark = false;
        try (JsonReader json =
                new JsonReader(
                        new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            json.beginObject();
            while (json.hasNext()) {
                String name = json.nextName();
                if ("mapnotes_bounds".equals(name)) {
                    bounds = new JSONArray();
                    json.beginArray();
                    for (int i = 0; i < 4; i++) bounds.put(json.nextDouble());
                    while (json.hasNext()) json.skipValue();
                    json.endArray();
                } else if ("mapnotes_buildings_only".equals(name)) {
                    JsonToken token = json.peek();
                    if (token == JsonToken.BOOLEAN) buildingsOnly = json.nextBoolean();
                    else if (token == JsonToken.STRING)
                        buildingsOnly = Boolean.parseBoolean(json.nextString());
                    else {
                        json.skipValue();
                        buildingsOnly = false;
                    }
                } else {
                    if ("remark".equals(name)) remark = true;
                    json.skipValue();
                }
            }
            json.endObject();
        }
        if (remark || bounds == null) return null;
        Area result = area(bounds, file);
        result.buildingsOnly = buildingsOnly;
        result.legacy = true;
        return result;
    }

    static ArrayList<Way> decode(JSONObject root) throws Exception {
        return decode(root, false);
    }

    // Overview geometry for small scales (zoom<OVERVIEW_ZOOM): buildings are invisible there anyway
    // and vertices closer than ~4 m to the kept polyline are dropped, so a large overview stays
    // decoded instead of thrashing.
    static ArrayList<Way> decode(JSONObject root, boolean overview) throws Exception {
        return decode(root, overview, false);
    }

    private static ArrayList<Way> decode(JSONObject root, boolean overview, boolean wallsOnly)
            throws Exception {
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
            if (overview && "building".equals(type) || wallsOnly && !"building".equals(type))
                continue;
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
        if (closed || !DownloadedMaps.current(downloadGeneration)) return new ArrayList<>();
        if (area.eager != null) return area.eager;
        String key = area.source.getPath() + (zoom < OVERVIEW_ZOOM ? "#o" : "");
        ArrayList<Way> ready = readyGeometry(key);
        if (ready != null) return ready;
        if (failedGeometry.contains(key)) return new ArrayList<>();
        if (!async) return loadWays(area, zoom < OVERVIEW_ZOOM);
        if (pending.size() < 8 && pending.add(key) && !loader.isShutdown()) {
            geometryRequests++;
            MapTrace.record(
                    MapTrace.Lane.IMPORT,
                    MapTrace.Event.QUEUE,
                    MapTrace.id(area),
                    zoom,
                    pending.size(),
                    rasterRevision.get());
            loader.execute(
                    () -> {
                        try {
                            loadWays(area, zoom < OVERVIEW_ZOOM);
                        } finally {
                            pending.remove(key);
                            if (closed) geometry.evictAll();
                            if (!closed && changed != null) changed.run();
                        }
                    });
        }
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
            for (String suffix : new String[] {"", "#o", "#walls"}) {
                ArrayList<Way> ready = previous.geometry.get(path + suffix);
                if (ready != null) geometry.put(path + suffix, ready);
            }
        }
    }

    public void close() {
        closed = true;
        MapTrace.unregisterSources(this);
        DownloadedMaps.forget(this);
        if (loader != null) loader.shutdownNow();
        frames.evictAll();
        raster.clear();
        tiles.close();
        viewport.close();
        ArrayList<AtlasDiskSource> obsolete = new ArrayList<>();
        synchronized (geometry) {
            geometry.evictAll();
            for (IndexedWays source : diskGeometry.values()) obsolete.add(source.disk);
            diskGeometry.clear();
        }
        for (AtlasDiskSource source : obsolete) source.close();
    }

    private boolean cacheGeometry(String key, ArrayList<Way> data) {
        ArrayList<Way> retained = data;
        HashMap<String, IndexedWays> replacements = new HashMap<>();
        if (data instanceof IndexedWays) {
            IndexedWays indexed = (IndexedWays) data;
            if ((long) geometry.size() + indexed.estimatedBytes > geometry.maxSize()) {
                retained = indexed.compact();
                // Компактизация вне LruCache monitor: UI get не ждёт обхода большого источника.
                for (Map.Entry<String, ArrayList<Way>> entry : geometry.snapshot().entrySet())
                    if (entry.getValue() instanceof IndexedWays
                            && !((IndexedWays) entry.getValue()).compacted)
                        replacements.put(
                                entry.getKey(), ((IndexedWays) entry.getValue()).compact());
            }
        }
        // A synchronous wall lookup may finish decoding after close(), outside the loader.
        synchronized (geometry) {
            if (!closed && DownloadedMaps.current(downloadGeneration)) {
                if (retained instanceof IndexedWays
                        && ((IndexedWays) retained).estimatedBytes > geometry.maxSize())
                    return false;
                for (Map.Entry<String, IndexedWays> entry : replacements.entrySet())
                    geometry.put(entry.getKey(), entry.getValue());
                geometry.put(key, retained);
                geometryLoads++;
                rasterRevision.incrementAndGet();
                return true;
            }
        }
        return false;
    }

    private ArrayList<Way> readyGeometry(String key) {
        ArrayList<Way> memory = geometry.get(key);
        return memory != null ? memory : diskGeometry.get(key);
    }

    ArrayList<Way> loadWays(Area area) {
        return loadWays(area, false);
    }

    ArrayList<Way> loadWays(Area area, boolean overview) {
        String key = area.eager == null ? area.source.getPath() + (overview ? "#o" : "") : null;
        boolean tracking = key != null && importingGeometry.add(key);
        try {
            long waiting = System.nanoTime();
            synchronized (this) {
                long acquired = System.nanoTime();
                try {
                    if (closed) return new ArrayList<>();
                    if (area.eager != null) return area.eager;
                    return loadSource(area, overview ? "#o" : "", overview, false);
                } finally {
                    MapTrace.record(
                            MapTrace.Lane.MONITOR,
                            MapTrace.Event.LOCK,
                            MapTrace.id(this),
                            acquired - waiting,
                            System.nanoTime() - acquired,
                            0);
                }
            }
        } finally {
            if (tracking) importingGeometry.remove(key);
        }
    }

    private ArrayList<Way> loadSource(
            Area area, String suffix, boolean overview, boolean wallsOnly) {
        String key = area.source.getPath() + suffix;
        ArrayList<Way> ready = readyGeometry(key);
        if (ready != null) return ready;
        if (closed || failedGeometry.contains(key)) return new ArrayList<>();
        AtlasDiskSource source = null;
        long traceStart = System.nanoTime();
        MapTrace.record(
                MapTrace.Lane.IMPORT,
                MapTrace.Event.START,
                MapTrace.id(area),
                overview ? 1 : 0,
                wallsOnly ? 1 : 0,
                0);
        try (DownloadedMaps.Lease ignored = DownloadedMaps.enter(downloadGeneration)) {
            geometryStreams++;
            source =
                    AtlasDiskSource.build(
                            area.source,
                            geometryDirectory,
                            overview,
                            wallsOnly,
                            this::wallRequestCancelled);
            MapTrace.record(
                    MapTrace.Lane.MONITOR,
                    MapTrace.Event.SCAN,
                    MapTrace.id(this),
                    source.count,
                    source.file.length(),
                    wallsOnly ? 1 : 0);
            if (source.compactBytes <= geometry.maxSize()
                    && source.rawBytes <= AtlasViewport.MAX_WORK_BYTES) {
                IndexedWays data = source.readSmall(this::wallRequestCancelled);
                // Фактический вес индексов также проверяется до публикации в LRU.
                if (data.compact().estimatedBytes <= geometry.maxSize()) {
                    if (cacheGeometry(key, data)) {
                        sourceOutcomes.remove(key);
                        return readyGeometry(key);
                    }
                }
            }
            IndexedWays data = new IndexedWays();
            data.disk = source;
            data.tileFeatures = Collections.emptyList();
            data.tileIndex = EMPTY_FEATURES;
            data.labelIndex = EMPTY_LABELS;
            data.estimatedBytes = 512;
            synchronized (geometry) {
                if (closed || !DownloadedMaps.current(downloadGeneration)) return new ArrayList<>();
                diskGeometry.put(key, data);
                sourceOutcomes.remove(key);
                source = null;
                geometryLoads++;
                rasterRevision.incrementAndGet();
            }
            return readyGeometry(key);
        } catch (IOException | RuntimeException e) {
            if (closed
                    || !DownloadedMaps.current(downloadGeneration)
                    || Thread.currentThread().isInterrupted()
                    || e instanceof InterruptedIOException
                    || e instanceof java.util.concurrent.CancellationException) {
                if (wallsOnly) throw cancelledWalls(e);
                return new ArrayList<>();
            }
            MapTrace.record(
                    MapTrace.Lane.IMPORT,
                    MapTrace.Event.ERROR,
                    MapTrace.id(area),
                    overview ? 1 : 0,
                    wallsOnly ? 1 : 0,
                    0);
            if (!closed) {
                sourceOutcomes.put(
                        key,
                        e instanceof FileNotFoundException
                                ? MapTrace.SourceState.MISSING
                                : MapTrace.SourceState.FAILED);
                failedGeometry.add(key);
            }
            return new ArrayList<>();
        } catch (OutOfMemoryError allocation) {
            if (!closed) sourceOutcomes.put(key, MapTrace.SourceState.FAILED);
            MapTrace.record(
                    MapTrace.Lane.IMPORT,
                    MapTrace.Event.OOM,
                    MapTrace.id(area),
                    overview ? 1 : 0,
                    wallsOnly ? 1 : 0,
                    0);
            throw allocation;
        } finally {
            MapTrace.record(
                    MapTrace.Lane.IMPORT,
                    MapTrace.Event.FINISH,
                    MapTrace.id(area),
                    readyGeometry(key) == null ? 0 : 1,
                    System.nanoTime() - traceStart,
                    rasterRevision.get());
            if (source != null) source.close();
        }
    }

    public List<double[]> buildingsNear(double lat, double lon, double radius) {
        long start = System.nanoTime();
        int results = -1;
        try {
            checkWallRequest();
            List<double[]> walls = queryBuildings(lat, lon, radius);
            checkWallRequest();
            results = walls.size();
            return walls;
        } finally {
            MapTrace.record(
                    MapTrace.Lane.WALL,
                    MapTrace.Event.FINISH,
                    MapTrace.id(this),
                    areas.size(),
                    results,
                    System.nanoTime() - start);
        }
    }

    private List<double[]> queryBuildings(double lat, double lon, double radius) {
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
        checkWallRequest();
        ArrayList<Way> all = area.eager != null ? area.eager : readyGeometry(area.source.getPath());
        if (all == null) all = loadWalls(area);
        if (all instanceof IndexedWays && ((IndexedWays) all).disk != null) {
            ArrayList<Way> nearby = new ArrayList<>();
            try (DownloadedMaps.Lease ignored = DownloadedMaps.enter(downloadGeneration)) {
                ((IndexedWays) all)
                        .disk.readWays(
                                nearby, south, west, north, east, true, this::wallRequestCancelled);
            } catch (IOException ignored) {
                if (closed
                        || !DownloadedMaps.current(downloadGeneration)
                        || Thread.currentThread().isInterrupted()
                        || ignored instanceof InterruptedIOException) throw cancelledWalls(ignored);
                // Не публикуем частичный набор стен при ошибке чтения.
                throw new IllegalStateException("Cannot read atlas walls", ignored);
            }
            checkWallRequest();
            return nearby;
        }
        return all instanceof IndexedWays
                ? ((IndexedWays) all).near(south, west, north, east)
                : all;
    }

    private ArrayList<Way> loadWalls(Area area) {
        String key = area.eager == null ? area.source.getPath() + "#walls" : null;
        boolean tracking = key != null && importingGeometry.add(key);
        try {
            long waiting = System.nanoTime();
            synchronized (this) {
                long acquired = System.nanoTime();
                try {
                    checkWallRequest();
                    if (area.eager != null) return area.eager;
                    ArrayList<Way> data = loadSource(area, "#walls", false, true);
                    checkWallRequest();
                    if (failedGeometry.contains(area.source.getPath() + "#walls"))
                        throw new IllegalStateException("Cannot load atlas walls");
                    return data;
                } finally {
                    MapTrace.record(
                            MapTrace.Lane.MONITOR,
                            MapTrace.Event.LOCK,
                            MapTrace.id(this),
                            acquired - waiting,
                            System.nanoTime() - acquired,
                            1);
                }
            }
        } finally {
            if (tracking) importingGeometry.remove(key);
        }
    }

    private void checkWallRequest() {
        if (wallRequestCancelled()) throw cancelledWalls(null);
    }

    boolean wallRequestCancelled() {
        return closed
                || !DownloadedMaps.current(downloadGeneration)
                || Thread.currentThread().isInterrupted();
    }

    private static java.util.concurrent.CancellationException cancelledWalls(Throwable cause) {
        java.util.concurrent.CancellationException cancelled =
                new java.util.concurrent.CancellationException("Atlas walls cancelled");
        if (cause != null) cancelled.initCause(cause);
        return cancelled;
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
        if (closed || !DownloadedMaps.current(downloadGeneration)) {
            lastCoverage = new AtlasCoverage();
            lastCoverageFraction = lastColorCoverageFraction = 0;
            return;
        }
        try {
            drawScene(canvas, map, hybrid);
        } finally {
            MapTrace.record(
                    hybrid ? MapTrace.Lane.COLOR : MapTrace.Lane.SOURCE,
                    MapTrace.Event.FINISH,
                    rasterRevision.get(),
                    "tiles".equals(renderBackend)
                            ? 1
                            : "viewport-worker".equals(renderBackend) ? 2 : 3,
                    fallbackCode(),
                    loadingLayers);
        }
    }

    private int fallbackCode() {
        switch (tileFallback) {
            case "none":
                return 0;
            case "disk-source":
                return 1;
            case "geometry-not-ready-replay":
                return 2;
            case "tile-work-memory":
                return 3;
            case "visible-vertex-budget":
                return 4;
            case "viewport-memory":
                return 5;
            default:
                return 6;
        }
    }

    private void drawScene(Canvas canvas, MapView map, boolean hybrid) {
        if (!hybrid) {
            lastCoverage = new AtlasCoverage();
            lastCoverageFraction = lastColorCoverageFraction = 0;
        }
        if (closed || map.zoom < 8) return;
        Matrix transform = new Matrix();
        canvas.getMatrix(transform);
        float[] matrix = new float[9];
        transform.getValues(matrix);
        // В production тайлы работают и под дробным Canvas-преобразованием.
        // Строгое совпадение legacy raster replay сохраняется для синхронного пути.
        if (!map.asyncRendering
                || !async
                        && (matrix[Matrix.MSCALE_X] != 1f
                                || matrix[Matrix.MSCALE_Y] != 1f
                                || matrix[Matrix.MSKEW_X] != 0f
                                || matrix[Matrix.MSKEW_Y] != 0f
                                || matrix[Matrix.MTRANS_X] != Math.rint(matrix[Matrix.MTRANS_X])
                                || matrix[Matrix.MTRANS_Y] != Math.rint(matrix[Matrix.MTRANS_Y]))) {
            AtlasTiles.Snapshot snapshot = buildTileSnapshot(map, hybrid, false);
            if (snapshot.hasDisk() && viewport.supports(map.getWidth(), map.getHeight())) {
                tileFallback = "disk-source";
                drawViewport(canvas, map, hybrid, snapshot);
                return;
            }
            drawGeometry(canvas, map, hybrid);
            return;
        }
        long revision = rasterRevision.get();
        if (async) {
            AtlasTiles.Snapshot snapshot = tileSnapshot(map, hybrid);
            if (snapshot != null) {
                if (snapshot.hasDisk()) {
                    tileFallback = "disk-source";
                    tiles.scene(tileFallback);
                    drawViewport(canvas, map, hybrid, snapshot);
                    return;
                }
                if (loadingLayers > 0
                        && viewport.hasReady(
                                snapshot,
                                map.cx,
                                map.cy,
                                map.getWidth(),
                                map.getHeight(),
                                map.viewportFraction)) {
                    tileFallback = "geometry-not-ready-replay";
                    drawViewport(canvas, map, hybrid, snapshot);
                    return;
                }
                raster.clear();
                long started = System.nanoTime();
                boolean tiled =
                        tiles.draw(
                                canvas,
                                snapshot,
                                map.cx,
                                map.cy,
                                map.getWidth(),
                                map.getHeight(),
                                map.viewportFraction);
                tileNanos += System.nanoTime() - started;
                if (tiled) {
                    frames.evictAll();
                    tileDraws++;
                    renderBackend = "tiles";
                    recordCoverage(tiles.lastCoverage, map, hybrid);
                    viewport.pause();
                } else {
                    tileFallback = "tile-work-memory";
                    if (smallVisibleSet(snapshot, map)) {
                        tiles.scene(tileFallback);
                        viewport.pause();
                        renderBackend = "geometry";
                        drawGeometry(canvas, map, hybrid);
                    } else {
                        drawViewport(canvas, map, hybrid, snapshot);
                    }
                }
                return;
            }
            if (("visible-vertex-budget".equals(tileFallback)
                            || "viewport-memory".equals(tileFallback))
                    && viewport.supports(map.getWidth(), map.getHeight())) {
                String reason = tileFallback;
                snapshot = buildTileSnapshot(map, hybrid, false);
                tileFallback = reason;
                drawViewport(canvas, map, hybrid, snapshot);
                return;
            }
            tiles.scene("fallback");
            viewport.pause();
            renderBackend = "geometry";
            drawGeometry(canvas, map, hybrid);
            return;
        }
        String key = rasterKey(map, hybrid, revision);
        if (raster.draw(canvas, key)) return;
        String viewport = hybrid ? rasterKey(map, false, revision) : key;
        if (!viewport.equals(rasterViewport)) {
            rasterViewport = viewport;
            raster.clear();
            // A moving viewport must not allocate/upload a new full-screen bitmap every frame.
            drawGeometry(canvas, map, hybrid);
            return;
        }
        Bitmap image = raster.allocate(map.getWidth(), map.getHeight());
        if (image == null) {
            drawGeometry(canvas, map, hybrid);
            return;
        }
        drawGeometry(new Canvas(image), map, hybrid);
        canvas.drawBitmap(image, 0, 0, null);
        // Loading can finish during drawing. Never retain a partially loaded scene.
        if (!closed && pending.isEmpty() && rasterRevision.get() == revision)
            raster.put(key, image);
    }

    private void drawViewport(
            Canvas canvas, MapView map, boolean hybrid, AtlasTiles.Snapshot snapshot) {
        if (!viewport.supports(map.getWidth(), map.getHeight())) {
            tiles.scene("fallback");
            viewport.pause();
            renderBackend = "geometry";
            drawGeometry(canvas, map, hybrid);
            return;
        }
        renderBackend = "viewport-worker";
        AtlasCoverage coverage =
                viewport.draw(
                        canvas,
                        snapshot,
                        map.cx,
                        map.cy,
                        map.getWidth(),
                        map.getHeight(),
                        hybrid,
                        map.viewportFraction);
        AtlasCoverage missing =
                coverage.missing(
                        AtlasCoverage.visible(
                                map.getWidth(), map.getHeight(), map.viewportFraction));
        if (!missing.isEmpty()) {
            int save = canvas.save();
            missing.clip(canvas);
            AtlasCoverage tileCoverage =
                    tiles.drawReady(
                            canvas,
                            snapshot,
                            map.cx,
                            map.cy,
                            map.getWidth(),
                            map.getHeight(),
                            map.viewportFraction);
            canvas.restoreToCount(save);
            coverage.append(tileCoverage.intersect(missing));
        }
        recordCoverage(coverage, map, hybrid);
        lastVertices = viewport.lastVertices();
    }

    private void recordCoverage(AtlasCoverage coverage, MapView map, boolean hybrid) {
        double fraction =
                coverage.fraction(
                        AtlasCoverage.visible(
                                map.getWidth(), map.getHeight(), map.viewportFraction));
        MapTrace.record(
                hybrid ? MapTrace.Lane.COLOR : MapTrace.Lane.SOURCE,
                MapTrace.Event.DRAW,
                rasterRevision.get(),
                "tiles".equals(renderBackend) ? 1 : "viewport-worker".equals(renderBackend) ? 2 : 3,
                loadingLayers,
                Math.round(fraction * 1000));
        if (hybrid) lastColorCoverageFraction = fraction;
        else {
            lastCoverage = coverage;
            lastCoverageFraction = fraction;
        }
    }

    private boolean smallVisibleSet(AtlasTiles.Snapshot snapshot, MapView map) {
        long vertices = 0;
        double scale = 1 << map.zoom;
        for (AtlasTiles.Layer layer : snapshot.layers) {
            vertices +=
                    layer.index.vertices(
                            map.cx,
                            map.cy,
                            map.getWidth() / (2 * scale),
                            map.getHeight() / (2 * scale),
                            map.zoom,
                            4096 - vertices);
            if (vertices > 4096) return false;
        }
        return true;
    }

    private String rasterKey(MapView map, boolean hybrid, long revision) {
        StringBuilder key = new StringBuilder();
        key.append(map.cx)
                .append(':')
                .append(map.cy)
                .append(':')
                .append(map.zoom)
                .append(':')
                .append(map.getWidth())
                .append(':')
                .append(map.getHeight())
                .append(':')
                .append(map.getResources().getDisplayMetrics().density)
                .append(':')
                .append(hybrid)
                .append(':')
                .append(map.illustrated)
                .append(':')
                .append(revision);
        for (Area area : areas) {
            key.append(':')
                    .append(System.identityHashCode(area))
                    .append(':')
                    .append(area.stamp)
                    .append(':')
                    .append(area.buildingsOnly)
                    .append(':')
                    .append(area.overview)
                    .append(':')
                    .append(area.legacy)
                    .append(':')
                    .append(area.south)
                    .append(':')
                    .append(area.west)
                    .append(':')
                    .append(area.north)
                    .append(':')
                    .append(area.east)
                    .append(':')
                    .append(System.identityHashCode(area.eager));
        }
        return key.toString();
    }

    void drawGeometry(Canvas canvas, MapView map, boolean hybrid) {
        // The synchronous compatibility path retains legacy interleaved labels.
        long started = System.nanoTime();
        drawGeometryLegacy(canvas, map, hybrid);
        geometryNanos += System.nanoTime() - started;
    }

    AtlasTiles.Snapshot tileSnapshot(MapView map, boolean hybrid) {
        long started = System.nanoTime();
        try {
            return buildTileSnapshot(map, hybrid);
        } finally {
            snapshotNanos += System.nanoTime() - started;
        }
    }

    private AtlasTiles.Snapshot buildTileSnapshot(MapView map, boolean hybrid) {
        return buildTileSnapshot(map, hybrid, true);
    }

    private AtlasTiles.Snapshot buildTileSnapshot(
            MapView map, boolean hybrid, boolean enforceBudget) {
        tileFallback = "none";
        loadingLayers = 0;
        double visibleWidth = map.getWidth() / (double) map.viewportFraction;
        double visibleHeight = map.getHeight() / (double) map.viewportFraction;
        long columns = (long) Math.ceil(visibleWidth / AtlasTiles.SIZE) + 1;
        long rows = (long) Math.ceil(visibleHeight / AtlasTiles.SIZE) + 1;
        long tileBytes =
                (AtlasTiles.SIZE + 2L * AtlasTiles.GUARD)
                        * (AtlasTiles.SIZE + 2L * AtlasTiles.GUARD)
                        * 4;
        if (enforceBudget && columns * rows * tileBytes * 2 > AtlasTiles.MAX_BYTES) {
            tileFallback = "viewport-memory";
            return null;
        }
        ArrayList<AtlasTiles.Layer> layers = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        key.append(map.zoom)
                .append(':')
                .append(map.illustrated)
                .append(':')
                .append(map.getResources().getDisplayMetrics().density)
                .append(':');
        long vertices = 0;
        double scale = 1 << map.zoom;
        double viewportLeft = (map.getWidth() - visibleWidth) / 2;
        double viewportTop = (map.getHeight() - visibleHeight) / 2;
        // Полный индекс pack, включая невыбранную редакцию. Не читаем файлы/геометрию на UI.
        Map<String, String> familyKeys = replayFamilies();
        for (Area a : areas) {
            if (a.overview && map.zoom >= 13
                    || !a.overview && !a.legacy && map.zoom < 13
                    || a.legacy && map.zoom < 11) continue;
            float left = map.screenX(a.west), right = map.screenX(a.east);
            float top = sy(a.north, map, scale), bottom = sy(a.south, map, scale);
            if (right < viewportLeft - 256
                    || left > viewportLeft + visibleWidth + 256
                    || bottom < viewportTop - 256
                    || top > viewportTop + visibleHeight + 256
                    || right <= left) continue;
            ArrayList<Way> data = ways(a, map.zoom);
            IndexedWays indexed = data instanceof IndexedWays ? (IndexedWays) data : null;
            if (indexed == null) loadingLayers++;
            // Повторный decode после LRU eviction не меняет содержимое файла.
            String identity =
                    a.eager == null
                            ? "file:" + a.source.getPath()
                            : indexed == null
                                    ? "loading:" + a.source.getPath()
                                    : "data:" + indexed.tileIdentity;
            AtlasTiles.Layer layer =
                    new AtlasTiles.Layer(
                            MapView.x(a.west),
                            MapView.x(a.east),
                            MapView.y(a.north),
                            MapView.y(a.south),
                            a.buildingsOnly,
                            indexed == null ? EMPTY_FEATURES : indexed.tileIndex,
                            indexed == null ? EMPTY_LABELS : indexed.labelIndex,
                            identity
                                    + ":"
                                    + a.stamp
                                    + ":"
                                    + a.south
                                    + ":"
                                    + a.west
                                    + ":"
                                    + a.north
                                    + ":"
                                    + a.east
                                    + ":"
                                    + a.buildingsOnly,
                            indexed != null,
                            a.packKey == null ? null : familyKeys.get(a.packKey));
            if (indexed != null) layer.disk = indexed.disk;
            MapTrace.record(
                    MapTrace.Lane.SOURCE,
                    MapTrace.Event.REPLAY,
                    MapTrace.id(a),
                    a.stamp,
                    indexed == null ? 0 : 1,
                    indexed != null && indexed.disk != null ? 2 : 1);
            // Дешёвый BVH admission: невидимые вершины области не отключают тайлы.
            // При потенциально насыщенном видимом наборе legacy сохраняет глобальный
            // viewport-бюджет и его порядок отбора после упрощения.
            if (enforceBudget)
                vertices +=
                        layer.index.vertices(
                                map.cx,
                                map.cy,
                                visibleWidth / (2 * scale),
                                visibleHeight / (2 * scale),
                                map.zoom,
                                80000 - vertices);
            if (enforceBudget && vertices > 80000) {
                tileFallback = "visible-vertex-budget";
                return null;
            }
            layers.add(layer);
            key.append(':')
                    .append(identity)
                    .append(':')
                    .append(indexed != null)
                    .append(':')
                    .append(a.stamp)
                    .append(':')
                    .append(a.south)
                    .append(':')
                    .append(a.west)
                    .append(':')
                    .append(a.north)
                    .append(':')
                    .append(a.east)
                    .append(':')
                    .append(a.buildingsOnly);
        }
        return new AtlasTiles.Snapshot(
                key.toString(),
                map.zoom,
                map.getResources().getDisplayMetrics().density,
                map.illustrated,
                hybrid,
                layers);
    }

    private void drawGeometryLegacy(Canvas canvas, MapView map, boolean hybrid) {
        if (closed || !DownloadedMaps.current(downloadGeneration) || map.zoom < 8) return;
        if (frameX != map.cx
                || frameY != map.cy
                || frameZoom != map.zoom
                || frameWidth != map.getWidth()
                || frameHeight != map.getHeight()
                || frameDensity != map.getResources().getDisplayMetrics().density) {
            frames.evictAll();
            frameX = map.cx;
            frameY = map.cy;
            frameZoom = map.zoom;
            frameWidth = map.getWidth();
            frameHeight = map.getHeight();
            frameDensity = map.getResources().getDisplayMetrics().density;
        }
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
            AtlasGeometry.Frame frame = frames.get(a);
            if (frame == null
                    || frame.geometry.source != data
                    || frame.stamp != a.stamp
                    || frame.initialVertices != lastVertices) {
                AtlasGeometry projected =
                        data instanceof IndexedWays ? ((IndexedWays) data).renderGeometry : null;
                if (projected == null)
                    projected =
                            data instanceof IndexedWays && ((IndexedWays) data).compacted
                                    ? new AtlasGeometry((IndexedWays) data, map)
                                    : new AtlasGeometry(data);
                frame = new AtlasGeometry.Frame(projected, a.stamp, map, lastVertices);
                preparedFrames++;
                frames.put(a, frame);
            }
            boolean reuseLabels =
                    frame.labelsReady
                            && labelNames.equals(frame.prefixNames)
                            && labelBoxes.equals(frame.prefixBoxes);
            if (!reuseLabels) {
                frame.prefixNames = new HashSet<>(labelNames);
                frame.prefixBoxes = new ArrayList<>(labelBoxes);
            }
            for (AtlasGeometry.Shape shape : frame.shapes) {
                if (!reuseLabels) {
                    shape.label = null;
                    shape.labelBox = null;
                }
                Way w = shape.entry.way;
                boolean road = "road".equals(w.type);
                Path p = shape.path;
                lastVertices += shape.vertices;
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
                                            : "building".equals(w.type) ? 0xffa3a8a5 : 0xffbfc6be));
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
                if (reuseLabels) {
                    if (shape.label != null) {
                        paint.setStyle(Paint.Style.FILL);
                        paint.setColor(hybrid ? Color.WHITE : 0xff33463c);
                        paint.setTypeface(LABEL_FONT);
                        paint.setTextSize(map.dp(11));
                        labelBoxes.add(shape.labelBox);
                        labelNames.add(w.name);
                        lastLabels++;
                        canvas.drawText(shape.label, shape.labelX, shape.labelY, paint);
                    }
                    continue;
                }
                if (road
                        && map.zoom >= 16
                        && !w.name.isEmpty()
                        && lastLabels < 24
                        && !labelNames.contains(w.name)) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(hybrid ? Color.WHITE : 0xff33463c);
                    paint.setTypeface(LABEL_FONT);
                    paint.setTextSize(map.dp(11));
                    float xx = AtlasGeometry.screenX(shape.entry.xy[0], map),
                            yy =
                                    (float)
                                            ((shape.entry.xy[1] - map.cy) * scale
                                                    + map.getHeight() / 2.0);
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
                            shape.label = label;
                            shape.labelBox = box;
                            shape.labelX = xx + map.dp(3);
                            shape.labelY = yy - map.dp(3);
                            labelBoxes.add(box);
                            labelNames.add(w.name);
                            lastLabels++;
                            canvas.drawText(label, xx + map.dp(3), yy - map.dp(3), paint);
                        }
                    }
                }
            }
            frame.labelsReady = true;
            paint.setStyle(Paint.Style.FILL);
            canvas.restoreToCount(save);
        }
    }

    float sy(double lat, MapView m, double s) {
        return (float) ((MapView.y(lat) - m.cy) * s + m.getHeight() / 2.0);
    }
}

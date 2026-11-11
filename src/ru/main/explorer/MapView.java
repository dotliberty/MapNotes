package ru.main.explorer;

import android.content.*;
import android.graphics.*;
import android.view.*;
import android.database.*;
import android.database.sqlite.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class MapView extends View {
    android.animation.ValueAnimator viewportMotion;
    double displayZoom = 2;
    float viewportFraction = 1;
    int targetZoom = 2;

    void stopViewport() {
        if (viewportMotion != null) {
            viewportMotion.cancel();
            viewportMotion = null;
        }
        displayZoom = zoom + Math.log(viewportFraction) / Math.log(2);
        invalidate();
    }

    void moveViewport(double tx, double ty, int tz) {
        stopViewport();
        final double sx = cx, sy = cy, sz = displayZoom;
        double wrapped = tx - sx;
        if (wrapped > 128) wrapped -= 256;
        if (wrapped < -128) wrapped += 256;
        final double dx = wrapped;
        if (!AtlasMotion.enabled()
                || !isAttachedToWindow()
                || getWidth() == 0
                || getHeight() == 0) {
            cx = tx;
            cy = ty;
            zoom = tz;
            displayZoom = tz;
            viewportFraction = 1;
            invalidate();
            return;
        }
        displayZoom = sz;
        targetZoom = tz;
        viewportMotion = android.animation.ValueAnimator.ofFloat(0, 1);
        viewportMotion.setDuration(480);
        viewportMotion.setInterpolator(AtlasMotion.EASE);
        viewportMotion.addUpdateListener(
                a -> {
                    double t = (float) a.getAnimatedValue();
                    cx = sx + dx * t;
                    cy = sy + (ty - sy) * t;
                    displayZoom = sz + (tz - sz) * t;
                    zoom = (int) Math.floor(displayZoom);
                    viewportFraction = (float) Math.pow(2, displayZoom - zoom);
                    invalidate();
                });
        viewportMotion.addListener(
                new android.animation.AnimatorListenerAdapter() {
                    boolean cancelled;

                    public void onAnimationCancel(android.animation.Animator a) {
                        cancelled = true;
                    }

                    public void onAnimationEnd(android.animation.Animator a) {
                        if (cancelled) return;
                        viewportMotion = null;
                        cx = tx;
                        cy = ty;
                        zoom = tz;
                        displayZoom = tz;
                        viewportFraction = 1;
                        groupedPhotos = null;
                        invalidate();
                    }
                });
        viewportMotion.start();
    }

    double cx = 128, cy = 100;
    int zoom = 2;
    boolean follow = true;
    volatile boolean online = true;
    boolean illustrated = true;
    private final Paint paint = new Paint(3);
    private final ArrayList<WorldLand> lands = new ArrayList<>();
    private final ArrayList<Path> landPaths = new ArrayList<>();
    private double landX = Double.NaN, landY;
    private int landZoom, landWidth, landHeight;
    private final android.util.LruCache<String, Bitmap> tiles =
            new android.util.LruCache<String, Bitmap>(CacheBudget.imagery()) {
                protected int sizeOf(String k, Bitmap b) {
                    return b.getAllocationByteCount();
                }
            };
    private final Set<String> pending = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> failed = Collections.synchronizedSet(new HashSet<>());
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final ExecutorService satellitePool = Executors.newFixedThreadPool(2);
    private final Map<String, Long> satelliteRetryAt = new ConcurrentHashMap<>();
    private final Map<String, String> satelliteErrors = new ConcurrentHashMap<>();
    private SQLiteDatabase offline;
    private ArrayList<double[]> points = new ArrayList<>();
    private final ScaleGestureDetector scale;
    private final GestureDetector gestures;
    private float scaleSum = 1;
    private final android.util.LruCache<String, double[]> visibilityCache =
            new android.util.LruCache<String, double[]>(6 * 1024 * 1024) {
                protected int sizeOf(String key, double[] shape) {
                    return Math.max(1, shape.length * 8 + key.length() * 2);
                }
            };
    private volatile boolean fetchingBuildings;
    private long nextBuildingFetch;
    private String buildingStatus = "";
    private ArrayList<Store.Photo> photos = new ArrayList<>();

    public interface PhotoTapListener {
        void open(ArrayList<Store.Photo> photos);
    }

    private PhotoTapListener photoTap;

    public interface ZoneLongPressListener {
        void open(double latitude, double longitude);
    }

    public interface ZoneTapListener {
        void open(List<SpawnZone> zones);
    }

    private ZoneLongPressListener zoneLongPress;
    private ZoneTapListener zoneTap;
    private List<SpawnZone> spawnZones = Collections.emptyList();
    private final SpawnZoneOverlay zoneOverlay = new SpawnZoneOverlay();
    private ZoneShapeEditor zoneEditor;

    public void setZoneEditor(ZoneShapeEditor editor) {
        if (zoneEditor != null) zoneEditor.cancelGesture();
        zoneEditor = editor;
        invalidate();
    }

    public void setZoneLongPressListener(ZoneLongPressListener listener) {
        zoneLongPress = listener;
    }

    public void setZoneTapListener(ZoneTapListener listener) {
        zoneTap = listener;
    }

    public void setSpawnZones(List<SpawnZone> value) {
        if (closed) return;
        spawnZones = Collections.unmodifiableList(new ArrayList<>(value));
        zoneOverlay.setZones(spawnZones);
        invalidate();
    }

    public List<SpawnZone> getSpawnZones() {
        return spawnZones;
    }

    public double[] zoneCoordinate(float px, float py) {
        double factor = (1 << zoom) * (double) viewportFraction;
        double worldX = cx + (px - getWidth() / 2.0) / factor;
        double worldY = Math.max(0, Math.min(256, cy + (py - getHeight() / 2.0) / factor));
        return new double[] {
            Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - worldY / 128)))),
            SpawnZone.normalizeLongitude(worldX / 256 * 360 - 180)
        };
    }

    private final ExecutorService photoPool = Executors.newSingleThreadExecutor();
    final CompactPhotoLayout compactPhotos = new CompactPhotoLayout();
    private ArrayList<PhotoGroups.Group> groupedPhotos;
    private double photoCx, photoCy;
    private int photoZoom, photoWidth, photoHeight;
    private float photoProjection = 1;

    public void setPhotos(ArrayList<Store.Photo> value) {
        photos = new ArrayList<>(value);
        compactPhotos.setPhotos(photos);
        groupedPhotos = null;
        invalidate();
    }

    public void setPhotoTapListener(PhotoTapListener listener) {
        photoTap = listener;
    }

    int visiblePhotoCount;
    final LinkedHashMap<Long, CardMotion> cards = new LinkedHashMap<>();

    static final class CardMotion {
        PhotoGroups.Group group;
        float fromX, fromY, toX, toY, fromOpacity = 0, opacity = 0;
        long since;
        boolean exiting;
    }

    float cardProgress(CardMotion card) {
        return !AtlasMotion.enabled() || !isAttachedToWindow()
                ? 1
                : AtlasMotion.EASE.getInterpolation(
                        Math.min(1, (android.os.SystemClock.uptimeMillis() - card.since) / 300f));
    }

    void retargetCards() {
        long now = android.os.SystemClock.uptimeMillis();
        HashSet<Long> live = new HashSet<>();
        for (PhotoGroups.Group group : groupedPhotos) {
            long id = group.photos.get(0).id;
            live.add(id);
            CardMotion card = cards.get(id);
            float ox = group.x - group.anchorX, oy = group.y - group.anchorY;
            if (card == null) {
                card = new CardMotion();
                card.since = now;
                cards.put(id, card);
            } else if (card.toX != ox || card.toY != oy || card.exiting) {
                float t = cardProgress(card);
                card.fromX = card.fromX + (card.toX - card.fromX) * t;
                card.fromY = card.fromY + (card.toY - card.fromY) * t;
                card.fromOpacity = card.opacity;
                card.since = now;
            }
            card.toX = ox;
            card.toY = oy;
            card.exiting = false;
            card.group = group;
        }
        for (Map.Entry<Long, CardMotion> entry : cards.entrySet())
            if (!live.contains(entry.getKey()) && !entry.getValue().exiting) {
                CardMotion card = entry.getValue();
                float t = cardProgress(card);
                card.fromX += (card.toX - card.fromX) * t;
                card.fromY += (card.toY - card.fromY) * t;
                card.fromOpacity = card.opacity;
                card.toX = 0;
                card.toY = -dp(18);
                card.since = now;
                card.exiting = true;
            }
    }

    ArrayList<PhotoGroups.Group> animatedPhotos() {
        photoGroups();
        ArrayList<PhotoGroups.Group> result = new ArrayList<>();
        for (Iterator<CardMotion> it = cards.values().iterator(); it.hasNext(); ) {
            CardMotion card = it.next();
            float t = cardProgress(card);
            if (card.exiting && t >= 1) {
                it.remove();
                continue;
            }
            PhotoGroups.Group target = card.group,
                    g = new PhotoGroups.Group(target.anchorX, target.anchorY);
            g.photos.addAll(target.photos);
            g.x = g.anchorX + card.fromX + (card.toX - card.fromX) * t;
            g.y = g.anchorY + card.fromY + (card.toY - card.fromY) * t;
            card.opacity = card.fromOpacity + ((card.exiting ? 0 : 1) - card.fromOpacity) * t;
            g.opacity = card.opacity;
            g.sizeScale = .55f + .45f * g.opacity;
            g.exiting = card.exiting;
            result.add(g);
            if (t < 1) postInvalidateOnAnimation();
        }
        return result;
    }

    ArrayList<PhotoGroups.Group> photoGroups() {
        if (groupedPhotos == null
                || photoCx != cx
                || photoCy != cy
                || photoZoom != zoom
                || photoProjection != viewportFraction
                || photoWidth != getWidth()
                || photoHeight != getHeight()) {
            float[] xs = new float[photos.size()], ys = new float[photos.size()];
            for (int i = 0; i < photos.size(); i++) {
                Store.Photo p = photos.get(i);
                xs[i] = getWidth() / 2f + (screenX(p.lon) - getWidth() / 2f) * viewportFraction;
                ys[i] =
                        (float)
                                ((y(p.lat) - cy) * (1 << zoom) * viewportFraction
                                        + getHeight() / 2.0);
            }
            visiblePhotoCount = 0;
            for (int i = 0; i < xs.length; i++)
                if (xs[i] >= 0
                        && xs[i] <= getWidth()
                        && ys[i] >= 0
                        && ys[i] <= getHeight() - dp(38)) visiblePhotoCount++;
            groupedPhotos =
                    PhotoGroups.spread(photos, xs, ys, dp(50), getWidth(), getHeight() - dp(38));
            // A long overlapping chain must not disappear because its newest member is off screen.
            for (PhotoGroups.Group group : groupedPhotos)
                if (group.x < 0
                        || group.x > getWidth()
                        || group.y < 0
                        || group.y > getHeight() - dp(38))
                    for (Store.Photo p : group.photos) {
                        float px = screenX(p.lon),
                                py = (float) ((y(p.lat) - cy) * (1 << zoom) + getHeight() / 2.0);
                        if (px >= 0 && px <= getWidth() && py >= 0 && py <= getHeight() - dp(38)) {
                            group.x = px;
                            group.y = py;
                            break;
                        }
                    }
            retargetCards();
            photoProjection = viewportFraction;
            photoCx = cx;
            photoCy = cy;
            photoZoom = zoom;
            photoWidth = getWidth();
            photoHeight = getHeight();
        }
        return groupedPhotos;
    }

    boolean asyncRendering;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile boolean closed;
    private final ExecutorService mapWorker = Executors.newSingleThreadExecutor(),
            routeWorker = Executors.newSingleThreadExecutor();
    private boolean indexing, rendering, indexAgain;
    long lastFrameNanos, lastBaseNanos, lastMaskNanos;
    volatile long routeBuildNanos;
    int routeFramesRequested, routeFramesCompleted, routeFramesCancelled;
    private long revision;
    private RouteFrame readyFrame;
    private final ArrayDeque<RouteFrame> frameHistory = new ArrayDeque<>();
    private volatile double desiredX, desiredY;
    private volatile int desiredZoom;
    private volatile long indexTicket;
    private final RevealCache revealDisk = new RevealCache(getContext());
    private double revealLat = Double.NaN, revealLon;
    private long revealSession;
    private volatile WorldMap world;
    private long worldTicket;
    private String worldStatus = "";
    private android.location.Location current;
    private boolean currentCanReveal;
    private OfflineAtlas atlas;

    public void reloadRegions() {
        if (closed) return;
        if (!asyncRendering) {
            replaceAtlas(new OfflineAtlas(getContext()));
            return;
        }
        if (indexing) {
            indexAgain = true;
            return;
        }
        indexing = true;
        long ticket = ++indexTicket;
        mapWorker.execute(
                () -> {
                    OfflineAtlas loaded =
                            new OfflineAtlas(
                                    getContext(),
                                    true,
                                    () ->
                                            ui.post(
                                                    () -> {
                                                        if (!closed) {
                                                            revision++;
                                                            invalidate();
                                                        }
                                                    }),
                                    () -> {
                                        if (!closed) postInvalidate();
                                    });
                    ui.post(
                            () -> {
                                if (closed || ticket != indexTicket) {
                                    loaded.close();
                                    return;
                                }
                                indexing = false;
                                replaceAtlas(loaded);
                                if (indexAgain) {
                                    indexAgain = false;
                                    reloadRegions();
                                }
                            });
                });
    }

    void replaceAtlas(OfflineAtlas loaded) {
        OfflineAtlas old = atlas;
        loaded.reuseGeometry(old);
        atlas = loaded;
        if (old != null) old.close();
        if (!asyncRendering) visibilityCache.evictAll();
        revision++;
        invalidate();
    }

    boolean nearFrame(RouteFrame f) {
        return f.zoom == zoom
                && f.width == getWidth()
                && f.height == getHeight()
                && Math.abs(f.cx - cx) * (1 << zoom) < getWidth() / 4.0
                && Math.abs(f.cy - cy) * (1 << zoom) < getHeight() / 4.0;
    }

    void requestFrame() {
        if (viewportMotion != null || (scale != null && scale.isInProgress())) return;
        desiredX = cx;
        desiredY = cy;
        desiredZoom = zoom;
        if (!asyncRendering || closed || getWidth() == 0 || getHeight() == 0) return;
        if (readyFrame == null || !nearFrame(readyFrame))
            for (Iterator<RouteFrame> it = frameHistory.descendingIterator(); it.hasNext(); ) {
                RouteFrame f = it.next();
                if (nearFrame(f)) {
                    readyFrame = f;
                    break;
                }
            }
        if (rendering) return;
        if (readyFrame != null
                && readyFrame.revision == revision
                && readyFrame.zoom == zoom
                && readyFrame.width == getWidth()
                && readyFrame.height == getHeight()
                && Math.abs(readyFrame.cx - cx) * (1 << zoom) < getWidth() / 4.0
                && Math.abs(readyFrame.cy - cy) * (1 << zoom) < getHeight() / 4.0) return;
        rendering = true;
        routeFramesRequested++;
        double[] live =
                current != null && currentCanReveal
                        ? new double[] {current.getLatitude(), current.getLongitude()}
                        : null;
        RouteFrame frame =
                new RouteFrame(
                        cx, cy, zoom, getWidth(), getHeight(), revision, points, live, atlas);
        frame.raster = true;
        frame.lineDensity = getResources().getDisplayMetrics().density;
        frame.progress = preview -> ui.post(() -> publishPreview(preview, frame));
        final WorldMap selectedWorld = world;
        frame.world = selectedWorld;
        frame.diskCache = revealDisk;
        frame.cache = visibilityCache;
        frame.cancelled =
                () ->
                        closed
                                || world != selectedWorld
                                || desiredZoom != frame.zoom
                                || Math.abs(frame.cx - desiredX) * (1 << frame.zoom)
                                        > frame.width / 2.0
                                || Math.abs(frame.cy - desiredY) * (1 << frame.zoom)
                                        > frame.height / 2.0;
        routeWorker.execute(
                () -> {
                    long buildStarted = System.nanoTime();
                    try {
                        frame.build();
                    } finally {
                        routeBuildNanos += System.nanoTime() - buildStarted;
                        ui.post(
                                () -> {
                                    rendering = false;
                                    if (!closed) {
                                        if (frame.complete && !frame.cancelled.getAsBoolean()) {
                                            routeFramesCompleted++;
                                            readyFrame = frame;
                                            frameHistory.removeIf(
                                                    old ->
                                                            old.zoom == frame.zoom
                                                                    && old.width == frame.width
                                                                    && old.height == frame.height
                                                                    && Math.abs(old.cx - frame.cx)
                                                                                    * (1
                                                                                            << frame.zoom)
                                                                            < frame.width / 4.0
                                                                    && Math.abs(old.cy - frame.cy)
                                                                                    * (1
                                                                                            << frame.zoom)
                                                                            < frame.height / 4.0);
                                            frameHistory.addLast(frame);
                                            while (frameHistory.size() > 6)
                                                frameHistory.removeFirst();
                                        } else {
                                            routeFramesCancelled++;
                                        }
                                        invalidate();
                                    }
                                });
                    }
                });
    }

    void publishPreview(RouteFrame preview, RouteFrame source) {
        if (!closed
                && !source.cancelled.getAsBoolean()
                && (readyFrame == null || !readyFrame.complete || !nearFrame(readyFrame))) {
            readyFrame = preview;
            invalidate();
        }
    }

    Path framePath(boolean reveal) {
        requestFrame();
        return readyFrame == null
                ? new Path()
                : readyFrame.project(
                        reveal ? readyFrame.reveal : readyFrame.line,
                        cx,
                        cy,
                        zoom,
                        getWidth(),
                        getHeight());
    }

    public int savedRegionCount() {
        return atlas == null ? 0 : atlas.regionCount();
    }

    public double centerLatitude() {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - cy / 128))));
    }

    public double centerLongitude() {
        return cx / 256 * 360 - 180;
    }

    public void setCurrent(android.location.Location l) {
        setCurrent(l, true);
    }

    public void setCurrent(android.location.Location l, boolean canReveal) {
        boolean first = current == null;
        boolean changed =
                first
                        || currentCanReveal != canReveal
                        || Double.isNaN(revealLat)
                        || GpsFilter.distance(
                                        revealLat, revealLon, l.getLatitude(), l.getLongitude())
                                >= 3;
        current = new android.location.Location(l);
        currentCanReveal = canReveal;
        if (changed) {
            revealLat = l.getLatitude();
            revealLon = l.getLongitude();
            revision++;
        }
        if (follow && viewportMotion == null) {
            double threshold = Math.max(15, Math.min(30, l.getAccuracy() * 1.5));
            if (first
                    || GpsFilter.distance(
                                    centerLatitude(),
                                    centerLongitude(),
                                    l.getLatitude(),
                                    l.getLongitude())
                            > threshold) {
                cx = x(l.getLongitude());
                cy = y(l.getLatitude());
            }
            if (first) {
                zoom =
                        Math.min(
                                20,
                                16
                                        + (int)
                                                Math.ceil(
                                                        Math.log(
                                                                        getResources()
                                                                                .getDisplayMetrics()
                                                                                .density)
                                                                / Math.log(2)));
                viewportFraction = 1;
                displayZoom = zoom;
            }
        }
        if (canReveal) ensureBuildingData();
        invalidate();
    }

    public String satelliteStatus() {
        if (illustrated && world != null) return worldDescription();
        if (!online && illustrated)
            return Language.text(getContext(), R.string.atlas_online_loading_paused);
        if (illustrated)
            return current == null
                    ? Language.text(getContext(), R.string.atlas_waiting_for_location)
                    : fetchingBuildings
                            ? Language.text(
                                    getContext(), R.string.atlas_loading_streets_and_buildings)
                            : !buildingStatus.isEmpty()
                                    ? buildingStatus
                                    : atlas != null
                                                    && atlas.coversRadius(
                                                            current.getLatitude(),
                                                            current.getLongitude(),
                                                            Visibility.RADIUS)
                                            ? Language.text(
                                                    getContext(),
                                                    R.string.atlas_map_ready_view_up_to_100_m)
                                            : Language.text(
                                                    getContext(),
                                                    R.string
                                                            .atlas_no_detailed_map_download_this_area);
        if (current == null)
            return Language.text(getContext(), R.string.satellite_waiting_for_location);
        if (!currentCanReveal)
            return Language.text(
                    getContext(), R.string.revealing_the_map_requires_precise_gps_within_30_m);
        int z = Math.min(14, zoom),
                xx = (int) Math.floor(RegionPlan.x(current.getLongitude()) * (1 << z)),
                yy = (int) Math.floor(RegionPlan.y(current.getLatitude()) * (1 << z));
        String key = "sat/" + z + "/" + xx + "/" + yy;
        if (tiles.get(key) != null) {
            if (atlas != null
                    && !atlas.coversRadius(
                            current.getLatitude(), current.getLongitude(), Visibility.RADIUS))
                return Language.text(getContext(), R.string.satellite_revealed_100_m)
                        + (fetchingBuildings
                                ? Language.text(getContext(), R.string.loading_walls)
                                : buildingStatus.isEmpty()
                                        ? Language.text(
                                                getContext(),
                                                R.string.no_wall_outlines_download_this_area)
                                        : buildingStatus);
            if (asyncRendering)
                return Language.text(
                        getContext(), R.string.satellite_revealed_visibility_up_to_100_m);
            double[] shape = visibleShape(current.getLatitude(), current.getLongitude());
            return shape.length == 0
                    ? Language.text(
                            getContext(), R.string.gps_inside_a_building_outside_visibility_unknown)
                    : Language.text(
                            getContext(),
                            R.string.satellite_revealed_view_up_to_100_m_limited_by_walls);
        }
        File f =
                new File(
                        new File(getContext().getFilesDir(), "satellite"),
                        RegionPlan.tileName(z, xx, yy));
        if (!online && !f.exists())
            return Language.text(
                    getContext(), R.string.no_offline_imagery_download_this_area_while_online);
        String error = satelliteErrors.get(key);
        return error != null
                ? Language.text(getContext(), R.string.image_not_loaded)
                        + error
                        + Language.text(getContext(), R.string.retry_in_10_s)
                : Language.text(getContext(), R.string.satellite_loading_image);
    }

    public MapView(Context c) {
        this(c, false);
    }

    public MapView(Context c, boolean asyncRendering) {
        super(c);
        Language.initialize(c);
        setContentDescription(Language.text(c, R.string.map_accessibility));
        this.asyncRendering = asyncRendering;
        online = c.getSharedPreferences("map-view", 0).getBoolean("online", true);
        try {
            InputStream in = c.getAssets().open("world.json");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            in.close();
            JSONArray fs = new JSONObject(out.toString("UTF-8")).getJSONArray("features");
            for (int i = 0; i < fs.length(); i++) {
                JSONObject g = fs.getJSONObject(i).getJSONObject("geometry");
                JSONArray co = g.getJSONArray("coordinates");
                if (g.getString("type").equals("Polygon")) polygon(co);
                else for (int j = 0; j < co.length(); j++) polygon(co.getJSONArray(j));
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    Language.text(getContext(), R.string.world_map_unavailable), e);
        }
        openOffline();
        reloadRegions();
        scale =
                new ScaleGestureDetector(
                        c,
                        new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                            public boolean onScaleBegin(ScaleGestureDetector d) {
                                stopViewport();
                                follow = false;
                                return true;
                            }

                            public boolean onScale(ScaleGestureDetector d) {
                                double old = Math.pow(2, displayZoom),
                                        next =
                                                Math.max(
                                                        1,
                                                        Math.min(
                                                                22,
                                                                displayZoom
                                                                        + Math.log(
                                                                                        d
                                                                                                .getScaleFactor())
                                                                                / Math.log(2))),
                                        factor = Math.pow(2, next);
                                cx += (d.getFocusX() - getWidth() / 2f) * (1 / old - 1 / factor);
                                cy += (d.getFocusY() - getHeight() / 2f) * (1 / old - 1 / factor);
                                displayZoom = next;
                                zoom = (int) Math.floor(next);
                                viewportFraction = (float) Math.pow(2, next - zoom);
                                normalize();
                                invalidate();
                                return true;
                            }
                        });
        gestures =
                new GestureDetector(
                        c,
                        new GestureDetector.SimpleOnGestureListener() {
                            public boolean onDown(MotionEvent e) {
                                return true;
                            }

                            public boolean onScroll(
                                    MotionEvent a, MotionEvent b, float dx, float dy) {
                                if (!scale.isInProgress()) {
                                    follow = false;
                                    cx += dx / ((1 << zoom) * viewportFraction);
                                    cy += dy / ((1 << zoom) * viewportFraction);
                                    normalize();
                                    invalidate();
                                }
                                return true;
                            }

                            public boolean onSingleTapConfirmed(MotionEvent e) {
                                if (tapPhotos(e.getX(), e.getY())) return true;
                                if (zoneTap == null) return false;
                                List<SpawnZone> matches =
                                        zoneOverlay.hit(MapView.this, e.getX(), e.getY());
                                if (matches.isEmpty()) return false;
                                zoneTap.open(matches);
                                return true;
                            }

                            public void onLongPress(MotionEvent e) {
                                if (closed
                                        || zoneLongPress == null
                                        || scale.isInProgress()
                                        || e.getX() < 0
                                        || e.getX() > getWidth()
                                        || e.getY() < 0
                                        || e.getY() >= getHeight() - dp(38)) return;
                                follow = false;
                                double[] coordinate = zoneCoordinate(e.getX(), e.getY());
                                zoneLongPress.open(coordinate[0], coordinate[1]);
                            }

                            public boolean onDoubleTap(MotionEvent e) {
                                changeZoom(1);
                                return true;
                            }
                        });
    }

    void polygon(JSONArray rings) throws Exception {
        lands.add(new WorldLand(rings));
        landX = Double.NaN;
    }

    static double x(double lon) {
        return (lon + 180) / 360 * 256;
    }

    static double y(double lat) {
        double r = Math.toRadians(Math.max(-85.05112878, Math.min(85.05112878, lat)));
        return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) * 128;
    }

    void normalize() {
        cx = (cx % 256 + 256) % 256;
        cy = Math.max(0, Math.min(256, cy));
    }

    public void changeZoom(int d) {
        int target =
                Math.max(
                        1,
                        Math.min(
                                22,
                                (viewportMotion == null
                                                ? (int)
                                                        Math.round(
                                                                zoom
                                                                        + Math.log(viewportFraction)
                                                                                / Math.log(2))
                                                : targetZoom)
                                        + d));
        failed.clear();
        moveViewport(cx, cy, target);
    }

    public void update(ArrayList<double[]> p) {
        boolean first = points.isEmpty() && !p.isEmpty();
        if (!p.isEmpty()) {
            double[] last = p.get(p.size() - 1);
            long session = last.length > 2 ? (long) last[2] : 0;
            if (first
                    || p.size() - points.size() > 16
                    || Double.isNaN(revealLat)
                    || session != revealSession
                    || GpsFilter.distance(revealLat, revealLon, last[0], last[1]) >= 3) {
                revealLat = last[0];
                revealLon = last[1];
                revealSession = session;
                revision++;
            }
        }
        points = new ArrayList<>(p);
        if (follow && viewportMotion == null && current == null && !p.isEmpty()) {
            double[] a = p.get(p.size() - 1);
            cx = x(a[1]);
            cy = y(a[0]);
            if (first) {
                zoom = 16;
                viewportFraction = 1;
                displayZoom = zoom;
            }
        }
        invalidate();
    }

    public void showPlace(double lat, double lon) {
        follow = false;
        moveViewport(x(lon), y(lat), 18);
    }

    public void locate() {
        double sx = cx, sy = cy;
        int sz = zoom;
        locateImmediately();
        double tx = cx, ty = cy;
        int tz = zoom;
        cx = sx;
        cy = sy;
        zoom = sz;
        moveViewport(tx, ty, tz);
    }

    private void locateImmediately() {
        follow = true;
        if (current != null) {
            cx = x(current.getLongitude());
            cy = y(current.getLatitude());
            zoom =
                    Math.min(
                            20,
                            16
                                    + (int)
                                            Math.ceil(
                                                    Math.log(
                                                                    getResources()
                                                                            .getDisplayMetrics()
                                                                            .density)
                                                            / Math.log(2)));
        } else if (!points.isEmpty()) {
            double[] a = points.get(points.size() - 1);
            cx = x(a[1]);
            cy = y(a[0]);
            zoom =
                    Math.min(
                            20,
                            16
                                    + (int)
                                            Math.ceil(
                                                    Math.log(
                                                                    getResources()
                                                                            .getDisplayMetrics()
                                                                            .density)
                                                            / Math.log(2)));
        }
        invalidate();
    }

    public synchronized void openOffline() {
        openWorld();
        if (offline != null) offline.close();
        File f = new File(getContext().getFilesDir(), "offline.mbtiles");
        offline =
                f.exists()
                        ? SQLiteDatabase.openDatabase(
                                f.getPath(), null, SQLiteDatabase.OPEN_READONLY)
                        : null;
        tiles.evictAll();
        failed.clear();
        invalidate();
    }

    void openWorld() {
        String uri = getContext().getSharedPreferences("world-map", 0).getString("uri", "");
        long ticket = ++worldTicket;
        if (uri.isEmpty()) {
            WorldMap old = world;
            world = null;
            if (old != null) mapWorker.execute(old::close);
            return;
        }
        worldStatus = Language.text(getContext(), R.string.connecting_local_map);
        mapWorker.execute(
                () -> {
                    WorldMap loaded = null;
                    String failure = "";
                    try {
                        loaded =
                                WorldMap.open(
                                        getContext(),
                                        android.net.Uri.parse(uri),
                                        () ->
                                                ui.post(
                                                        () -> {
                                                            if (!closed) invalidate();
                                                        }));
                    } catch (Exception error) {
                        failure = error.getMessage();
                    }
                    final WorldMap result = loaded;
                    final String error = failure;
                    ui.post(
                            () -> {
                                if (closed || ticket != worldTicket) {
                                    if (result != null) result.close();
                                    return;
                                }
                                if (result != null) {
                                    WorldMap old = world;
                                    world = result;
                                    readyFrame = null;
                                    frameHistory.clear();
                                    if (old != null) mapWorker.execute(old::close);
                                    worldStatus =
                                            result.name
                                                    + Language.text(
                                                            getContext(), R.string.offline_z)
                                                    + result.minZoom
                                                    + "–"
                                                    + result.maxZoom;
                                    getContext()
                                            .getSharedPreferences("world-map", 0)
                                            .edit()
                                            .putBoolean("active", true)
                                            .apply();
                                    revision++;
                                } else
                                    worldStatus =
                                            Language.text(
                                                            getContext(),
                                                            R.string.map_could_not_be_opened)
                                                    + error;
                                invalidate();
                            });
                });
    }

    public boolean hasOffline() {
        return offline != null || world != null;
    }

    public String worldDescription() {
        WorldMap source = world;
        return source != null && !source.error.isEmpty()
                ? worldStatus + " · " + source.error
                : worldStatus;
    }

    void disconnectWorld() {
        getContext()
                .getSharedPreferences("world-map", 0)
                .edit()
                .remove("uri")
                .putBoolean("active", false)
                .apply();
        WorldMap old = world;
        world = null;
        readyFrame = null;
        frameHistory.clear();
        worldTicket++;
        worldStatus = "";
        revision++;
        if (old != null) mapWorker.execute(old::close);
        invalidate();
    }

    private final Set<HttpURLConnection> liveConnections =
            Collections.synchronizedSet(new HashSet<>());

    public void toggleOnline() {
        online = !online;
        MapNetwork.setEnabled(getContext(), online);
        if (online) {
            failed.clear();
            satelliteRetryAt.clear();
            nextBuildingFetch = 0;
            ensureBuildingData();
        }
        invalidate();
    }

    public void close() {
        if (closed) return;
        stopViewport();
        closed = true;
        setZoneEditor(null);
        zoneLongPress = null;
        zoneTap = null;
        spawnZones = Collections.emptyList();
        zoneOverlay.setZones(spawnZones);
        MapNetwork.cancel(liveConnections);
        WorldMap oldWorld = world;
        world = null;
        if (oldWorld != null) mapWorker.execute(oldWorld::close);
        mapWorker.shutdown();
        routeWorker.execute(() -> revealDisk.close());
        routeWorker.shutdown();
        if (atlas != null) atlas.close();
        pool.shutdownNow();
        satellitePool.shutdownNow();
        photoPool.shutdownNow();
        synchronized (this) {
            if (offline != null) offline.close();
            offline = null;
        }
    }

    protected void onDetachedFromWindow() {
        stopViewport();
        if (zoneEditor != null) zoneEditor.cancelGesture();
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        gestures.onTouchEvent(cancel);
        scale.onTouchEvent(cancel);
        cancel.recycle();
        cards.clear();
        groupedPhotos = null;
        AtlasMotion.cancel(this);
        super.onDetachedFromWindow();
    }

    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            stopViewport();
            AtlasMotion.cancel(this);
        }
        if (zoneEditor != null && zoneEditor.onTouch(e)) return true;
        return handleMapTouch(e);
    }

    boolean handleMapTouch(MotionEvent e) {
        scale.onTouchEvent(e);
        gestures.onTouchEvent(e);
        return true;
    }

    protected void onDraw(Canvas c) {
        long frameStarted = System.nanoTime();
        super.onDraw(c);
        int viewportSave = c.save();
        if (viewportFraction != 1)
            c.scale(viewportFraction, viewportFraction, getWidth() / 2f, getHeight() / 2f);
        long baseStarted = System.nanoTime();
        drawMap(c, false);
        lastBaseNanos = System.nanoTime() - baseStarted;
        long maskStarted = System.nanoTime();
        if (asyncRendering) {
            requestFrame();
            drawFrameMask(c);
        }
        lastMaskNanos = System.nanoTime() - maskStarted;
        Path reveal = asyncRendering ? new Path() : routeReveal();
        if (!asyncRendering && current != null && currentCanReveal)
            addVisibility(reveal, current.getLatitude(), current.getLongitude());
        if (!reveal.isEmpty()) {
            int save = c.save();
            c.clipPath(reveal);
            drawMap(c, true);
            c.restoreToCount(save);
        }
        drawRoute(c);
        drawCurrentMarker(c);
        c.restoreToCount(viewportSave);
        zoneOverlay.draw(c, this);
        if (zoneEditor != null) zoneEditor.draw(c);
        drawPhotos(c);
        drawScale(c);
        if (illustrated) drawCompass(c);
        paint.setColor(0xbb12201f);
        c.drawRect(0, getHeight() - dp(38), getWidth(), getHeight(), paint);
        paint.setColor(0xffc4ceca);
        paint.setTextSize(dp(9));
        c.drawText(
                fitLabel(
                        Language.text(getContext(), R.string.map_attribution), getWidth() - dp(16)),
                dp(8),
                getHeight() - dp(23),
                paint);
        c.drawText(
                fitLabel(
                        Language.format(getContext(), R.string.satellite_attribution, zoom),
                        getWidth() - dp(16)),
                dp(8),
                getHeight() - dp(9),
                paint);
        lastFrameNanos = System.nanoTime() - frameStarted;
    }

    static boolean connects(double[] a, double[] b) {
        return a.length >= 4
                && b.length >= 4
                && a[2] == b[2]
                && (a.length < 5 || b.length < 5 || b[4] == a[4] + 1)
                && b[3] >= a[3]
                && b[3] - a[3] <= 30000
                && GpsFilter.distance(a[0], a[1], b[0], b[1]) <= 150;
    }

    Path routeReveal() {
        Path path = new Path();
        double[] previous = null;
        for (double[] point : points) {
            addVisibility(path, point[0], point[1]);
            if (previous != null && connects(previous, point)) {
                double distance = GpsFilter.distance(previous[0], previous[1], point[0], point[1]);
                int steps = (int) Math.ceil(distance / 10);
                for (int i = 1; i < steps; i++) {
                    double t = (double) i / steps;
                    addVisibility(
                            path,
                            previous[0] + (point[0] - previous[0]) * t,
                            previous[1] + (point[1] - previous[1]) * t);
                }
            }
            previous = point;
        }
        return path;
    }

    void drawRoute(Canvas c) {
        if (!illustrated) return;
        if (asyncRendering) {
            if (readyFrame != null && readyFrame.lineImage != null)
                readyFrame.drawImage(
                        c,
                        readyFrame.lineImage,
                        cx,
                        cy,
                        zoom,
                        getWidth(),
                        getHeight(),
                        new Paint(3));
            return;
        }
        Path route = asyncRendering ? framePath(false) : new Path();
        double[] previous = null;
        for (double[] point : asyncRendering ? Collections.<double[]>emptyList() : points) {
            float px = screenX(point[1]),
                    py = (float) ((y(point[0]) - cy) * (1 << zoom) + getHeight() / 2.0);
            if (previous != null && connects(previous, point)) route.lineTo(px, py);
            else route.moveTo(px, py);
            previous = point;
        }
        paint.setColorFilter(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(dp(5));
        paint.setColor(0xfff7efd9);
        c.drawPath(route, paint);
        paint.setStrokeWidth(dp(2));
        paint.setColor(0xffb09a69);
        c.drawPath(route, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    void drawCompass(Canvas c) {
        float px = getWidth() - dp(34), py = dp(40);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(AtlasUi.INK);
        c.drawCircle(px, py, dp(23), paint);
        AtlasDecor.compass(c, px, py, dp(23));
        AtlasDecor.star(c, px, py + dp(25), dp(4));
        paint.setTextSize(dp(8));
        paint.setColor(AtlasUi.INK);
        c.drawText(Language.text(getContext(), R.string.north), px - dp(3), py - dp(27), paint);
    }

    void drawFrameMask(Canvas c) {
        RouteFrame f = readyFrame;
        if (f == null || f.mask == null) return;
        float scale = (float) Math.pow(2, zoom - f.zoom);
        double dx = f.cx - cx;
        if (dx > 128) dx -= 256;
        if (dx < -128) dx += 256;
        float left = (float) (dx * (1 << zoom) + getWidth() / 2.0 - f.width * scale / 2.0),
                top =
                        (float)
                                ((f.cy - cy) * (1 << zoom)
                                        + getHeight() / 2.0
                                        - f.height * scale / 2.0);
        int outer = c.save();
        c.clipRect(left, top, left + f.width * scale, top + f.height * scale);
        int layer = c.saveLayer(0, 0, getWidth(), getHeight(), null);
        drawMap(c, true);
        Paint maskPaint = new Paint(3);
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        f.drawImage(c, f.mask, cx, cy, zoom, getWidth(), getHeight(), maskPaint);
        c.restoreToCount(layer);
        c.restoreToCount(outer);
    }

    void drawCurrentMarker(Canvas c) {
        if (current == null) return;
        if (illustrated) {
            float px = screenX(current.getLongitude()),
                    py =
                            (float)
                                    ((y(current.getLatitude()) - cy) * (1 << zoom)
                                            + getHeight() / 2.0);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xfff9f2dc);
            Path diamond = new Path();
            diamond.moveTo(px, py - dp(13));
            diamond.lineTo(px + dp(10), py);
            diamond.lineTo(px, py + dp(13));
            diamond.lineTo(px - dp(10), py);
            diamond.close();
            c.drawPath(diamond, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0xff5c8490);
            c.drawPath(diamond, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xff5c8490);
            c.drawCircle(px, py, dp(3), paint);
            return;
        }
        double s = 1 << zoom;
        float xx = screenX(current.getLongitude()),
                yy = (float) ((y(current.getLatitude()) - cy) * s + getHeight() / 2.0);
        double metersPerPixel = 156543.03392 * Math.cos(Math.toRadians(current.getLatitude())) / s;
        float radius = (float) (Visibility.RADIUS / metersPerPixel),
                markerRadius = Math.max(dp(9), radius + dp(4)),
                uncertainty = (float) (current.getAccuracy() / metersPerPixel);
        paint.setColorFilter(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0x997f8984);
        paint.setPathEffect(new DashPathEffect(new float[] {dp(4), dp(4)}, 0));
        if (uncertainty > markerRadius + dp(3)) c.drawCircle(xx, yy, uncertainty, paint);
        paint.setPathEffect(null);
        paint.setStrokeWidth(dp(4));
        paint.setColor(Color.WHITE);
        c.drawCircle(xx, yy, markerRadius, paint);
        paint.setStrokeWidth(dp(2));
        paint.setColor(0xffe4c88c);
        c.drawCircle(xx, yy, markerRadius, paint);
        float labelY = yy - markerRadius - dp(10);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(dp(12));
        String label = Language.text(getContext(), R.string.you_are_here);
        float labelWidth = paint.measureText(label);
        paint.setColor(0xff30394b);
        c.drawRoundRect(
                xx - labelWidth / 2 - dp(10),
                labelY - dp(21),
                xx + labelWidth / 2 + dp(10),
                labelY + dp(2),
                dp(8),
                dp(8),
                paint);
        paint.setColor(Color.WHITE);
        c.drawText(label, xx - labelWidth / 2, labelY - dp(5), paint);
    }

    float screenX(double lon) {
        double dx = x(lon) - cx;
        if (dx > 128) dx -= 256;
        if (dx < -128) dx += 256;
        return (float) (dx * (1 << zoom) + getWidth() / 2.0);
    }

    String fitLabel(String text, float width) {
        return android.text.TextUtils.ellipsize(
                        text,
                        new android.text.TextPaint(paint),
                        Math.max(0, width),
                        android.text.TextUtils.TruncateAt.END)
                .toString();
    }

    int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    void drawOutlines(Canvas c) {
        paint.setColorFilter(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f);
        paint.setColor(0xffa0ada5);
        for (Path path : projectedLands()) c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private List<Path> projectedLands() {
        if (landX != cx
                || landY != cy
                || landZoom != zoom
                || landWidth != getWidth()
                || landHeight != getHeight()) {
            landPaths.clear();
            for (WorldLand land : lands)
                landPaths.add(land.project(cx, cy, zoom, getWidth(), getHeight()));
            landX = cx;
            landY = cy;
            landZoom = zoom;
            landWidth = getWidth();
            landHeight = getHeight();
        }
        return landPaths;
    }

    void drawMap(Canvas c, boolean color) {
        if (color && !illustrated) {
            drawTiles(c, true);
            if (world != null) world.draw(c, this, true);
            if (atlas != null) atlas.draw(c, this, true);
            return;
        }
        c.drawColor(illustrated ? (color ? 0xff78aeb7 : 0xff9cabb1) : 0xff343e42);
        paint.setColorFilter(null);
        for (Path p : projectedLands()) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(illustrated ? (color ? 0xffb8cb96 : 0xffc6c6b0) : 0xff66706f);
            c.drawPath(p, paint);
        }
        if (world != null) world.draw(c, this, illustrated && color);
        else if (zoom >= 7 && !illustrated) drawTiles(c, false);
        if (atlas != null && world == null) atlas.draw(c, this, illustrated && color);
        drawOutlines(c);
    }

    double[] visibleShape(double lat, double lon) {
        String key = lat + ":" + lon;
        double[] shape = visibilityCache.get(key);
        if (shape == null) {
            shape =
                    Visibility.polygon(
                            lat,
                            lon,
                            Visibility.RADIUS,
                            atlas == null
                                    ? Collections.emptyList()
                                    : atlas.buildingsNear(lat, lon, Visibility.RADIUS));
            visibilityCache.put(key, shape);
        }
        return shape;
    }

    void addVisibility(Path path, double lat, double lon) {
        double s = 1 << zoom, metersPerPixel = 156543.03392 * Math.cos(Math.toRadians(lat)) / s;
        float xx = screenX(lon),
                yy = (float) ((y(lat) - cy) * s + getHeight() / 2.0),
                r = (float) (Visibility.RADIUS / metersPerPixel);
        if (xx + r < 0 || xx - r > getWidth() || yy + r < 0 || yy - r > getHeight()) return;
        double[] shape = visibleShape(lat, lon);
        if (shape.length == 0) return;
        Path area = new Path();
        for (int i = 0; i < shape.length; i += 2) {
            float px = xx + (float) (shape[i] / metersPerPixel),
                    py = yy - (float) (shape[i + 1] / metersPerPixel);
            if (i == 0) area.moveTo(px, py);
            else area.lineTo(px, py);
        }
        area.close();
        path.addPath(area);
    }

    void ensureBuildingData() {
        if (world != null && world.vector
                || !online
                || current == null
                || fetchingBuildings
                || android.os.SystemClock.uptimeMillis() < nextBuildingFetch
                || (atlas != null
                        && atlas.coversRadius(
                                current.getLatitude(), current.getLongitude(), Visibility.RADIUS))
                || pool.isShutdown()) return;
        fetchingBuildings = true;
        nextBuildingFetch = android.os.SystemClock.uptimeMillis() + 120000;
        double lat = current.getLatitude(), lon = current.getLongitude();
        pool.execute(
                () -> {
                    try {
                        BuildingFetch.fetch(
                                getContext(), lat, lon, liveConnections, () -> online && !closed);
                        ui.post(
                                () -> {
                                    reloadRegions();
                                    buildingStatus = "";
                                    invalidate();
                                });
                    } catch (Exception e) {
                        ui.post(
                                () -> {
                                    buildingStatus =
                                            Language.text(
                                                    getContext(),
                                                    R.string
                                                            .streets_and_buildings_not_loaded_retry_in_15_s);
                                    nextBuildingFetch =
                                            android.os.SystemClock.uptimeMillis() + 15000;
                                    invalidate();
                                });
                    } finally {
                        fetchingBuildings = false;
                    }
                });
    }

    boolean tapPhotos(float x, float y) {
        if (photoTap == null) return false;
        ArrayList<PhotoGroups.Group> visible = compactPhotos.groups(this);
        for (int i = visible.size() - 1; i >= 0; i--) {
            PhotoGroups.Group group = visible.get(i);
            if (Math.abs(x - group.x) <= dp(18) && Math.abs(y - group.y) <= dp(18)) {
                photoTap.open(new ArrayList<>(group.photos));
                return true;
            }
        }

        return false;
    }

    static RectF photoRect(float x, float y, Bitmap bitmap, float maxSide) {
        float width = maxSide, height = maxSide;
        if (bitmap != null) {
            float ratio = (float) bitmap.getWidth() / bitmap.getHeight();
            if (ratio >= 1) height /= ratio;
            else width *= ratio;
        }
        return new RectF(x - width / 2, y - height / 2, x + width / 2, y + height / 2);
    }

    Bitmap photoThumbnail(Store.Photo photo) {
        String key = "photo/" + photo.id;
        Bitmap bitmap = tiles.get(key);
        if (bitmap != null) return bitmap;
        Long retry = satelliteRetryAt.get(key);
        if (retry != null && android.os.SystemClock.uptimeMillis() >= retry) {
            failed.remove(key);
            satelliteRetryAt.remove(key);
        }
        if (!closed && !failed.contains(key) && pending.add(key) && !photoPool.isShutdown()) {
            photoPool.execute(
                    () -> {
                        try {
                            Bitmap loaded = PhotoFiles.load(photo.path, 64);
                            if (!closed && loaded != null) tiles.put(key, loaded);
                            else if (!closed) failed.add(key);
                        } catch (Exception error) {
                            if (!closed) failed.add(key);
                        } finally {
                            if (!closed && failed.contains(key)) {
                                satelliteRetryAt.put(
                                        key, android.os.SystemClock.uptimeMillis() + 10000);
                                postInvalidateDelayed(10000);
                            }
                            pending.remove(key);
                            if (!closed) postInvalidate();
                        }
                    });
        }
        return null;
    }

    void drawPhotos(Canvas canvas) {
        paint.setColorFilter(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        ArrayList<PhotoGroups.Group> groups = compactPhotos.groups(this);
        int represented = 0;
        for (PhotoGroups.Group group : groups) represented += group.photos.size();
        if (represented < compactPhotos.visibleCount) {
            paint.setColor(0xee183d3a);
            canvas.drawRoundRect(dp(8), dp(6), getWidth() - dp(8), dp(36), dp(8), dp(8), paint);
            paint.setColor(0xffffe4a7);
            paint.setTextSize(dp(11));
            canvas.drawText(
                    fitLabel(
                            Language.text(getContext(), R.string.photos_2)
                                    + represented
                                    + " / "
                                    + compactPhotos.visibleCount
                                    + Language.text(
                                            getContext(), R.string.zoom_in_or_open_the_gallery),
                            getWidth() - dp(32)),
                    dp(16),
                    dp(26),
                    paint);
        }
        for (PhotoGroups.Group group : groups) {
            Store.Photo photo = group.photos.get(0);
            Bitmap bitmap = photoThumbnail(photo);
            RectF inner = photoRect(group.x, group.y, bitmap, dp(24));
            RectF outer = new RectF(inner);
            outer.inset(-dp(2), -dp(2));
            paint.setColor(0xffead4a1);
            canvas.drawRoundRect(outer, dp(3), dp(3), paint);
            if (bitmap != null) canvas.drawBitmap(bitmap, null, inner, paint);
            else {
                paint.setColor(0xff254a3b);
                canvas.drawRect(inner, paint);
            }
            if (group.photos.size() > 1) {
                paint.setColor(0xff182f2c);
                canvas.drawCircle(inner.right, inner.bottom, dp(8), paint);
                paint.setColor(0xffffe0a0);
                paint.setTextSize(dp(9));
                String count = "" + group.photos.size();
                canvas.drawText(
                        count,
                        inner.right - paint.measureText(count) / 2,
                        inner.bottom + dp(3),
                        paint);
            }
        }
    }

    void drawTiles(Canvas c, boolean satellite) {
        double s = 1 << zoom;
        int tileZoom = Math.min(satellite ? 14 : 19, zoom);
        double tileSize = 256.0 * (1 << (zoom - tileZoom));
        int n = 1 << tileZoom;
        double wx = cx * s, wy = cy * s;
        int left = (int) Math.floor((wx - getWidth() / 2.0) / tileSize),
                top = (int) Math.floor((wy - getHeight() / 2.0) / tileSize),
                right = (int) Math.floor((wx + getWidth() / 2.0) / tileSize),
                bottom = (int) Math.floor((wy + getHeight() / 2.0) / tileSize);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0);
        paint.setColorFilter(satellite ? null : new ColorMatrixColorFilter(cm));
        for (int tx = left; tx <= right; tx++)
            for (int ty = Math.max(0, top); ty <= Math.min(n - 1, bottom); ty++) {
                int wrapped = (tx % n + n) % n;
                String base = tileZoom + "/" + wrapped + "/" + ty,
                        key = (satellite ? "sat/" : "") + base;
                Long retryAt = satelliteRetryAt.get(key);
                if (retryAt != null && android.os.SystemClock.uptimeMillis() >= retryAt) {
                    failed.remove(key);
                    satelliteRetryAt.remove(key);
                }
                Bitmap b = tiles.get(key);
                if (b != null)
                    c.drawBitmap(
                            b,
                            null,
                            new RectF(
                                    (float) (tx * tileSize - wx + getWidth() / 2.0),
                                    (float) (ty * tileSize - wy + getHeight() / 2.0),
                                    (float) ((tx + 1) * tileSize - wx + getWidth() / 2.0),
                                    (float) ((ty + 1) * tileSize - wy + getHeight() / 2.0)),
                            paint);
                else if (!failed.contains(key)
                        && pending.add(key)
                        && !pool.isShutdown()
                        && !satellitePool.isShutdown()) {
                    int z = tileZoom, xx = wrapped, yy = ty;
                    (satellite ? satellitePool : pool)
                            .execute(
                                    () -> {
                                        if (satellite) loadSatellite(key, z, xx, yy);
                                        else loadTile(key, z, xx, yy);
                                    });
                }
            }
        paint.setColorFilter(null);
    }

    Bitmap readAncestor(File directory, int z, int x, int y) {
        for (int depth = 1; depth <= Math.min(3, z); depth++) {
            int factor = 1 << depth;
            File parentFile =
                    new File(directory, RegionPlan.tileName(z - depth, x / factor, y / factor));
            if (!parentFile.exists()) continue;
            Bitmap parent = BitmapFactory.decodeFile(parentFile.getPath());
            if (parent == null) continue;
            int size = parent.getWidth() / factor,
                    sx = (x % factor) * size,
                    sy = (y % factor) * size;
            Bitmap crop = Bitmap.createBitmap(parent, sx, sy, size, size),
                    result = Bitmap.createScaledBitmap(crop, 256, 256, true);
            if (crop != parent && crop != result) crop.recycle();
            if (parent != result) parent.recycle();
            return result;
        }
        return null;
    }

    double metersPerScreenPixel() {
        return 156543.03392
                * Math.cos(Math.toRadians(centerLatitude()))
                / ((1 << zoom) * (double) viewportFraction);
    }

    double scaleDistance() {
        double target = metersPerScreenPixel() * dp(85),
                unit = Math.pow(10, Math.floor(Math.log10(target))),
                distance = target / unit >= 5 ? unit * 5 : target / unit >= 2 ? unit * 2 : unit;
        return distance;
    }

    void drawScale(Canvas canvas) {
        double mpp = metersPerScreenPixel(), distance = scaleDistance();
        float width = (float) (distance / mpp), left = dp(12), bottom = getHeight() - dp(49);
        paint.setColorFilter(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(4));
        paint.setColor(0xdd10251e);
        canvas.drawLine(left, bottom, left + width, bottom, paint);
        paint.setStrokeWidth(dp(2));
        paint.setColor(Color.WHITE);
        canvas.drawLine(left, bottom, left + width, bottom, paint);
        canvas.drawLine(left, bottom - dp(4), left, bottom + dp(1), paint);
        canvas.drawLine(left + width, bottom - dp(4), left + width, bottom + dp(1), paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(dp(11));
        String label =
                distance >= 1000
                        ? String.format(
                                Language.locale(getContext()),
                                Language.text(getContext(), R.string.km_2),
                                distance / 1000)
                        : String.format(
                                Language.locale(getContext()),
                                Language.text(getContext(), R.string.m_2),
                                distance);
        paint.setColor(0xdd10251e);
        canvas.drawRect(left - dp(3), bottom - dp(24), left + dp(80), bottom - dp(5), paint);
        paint.setColor(Color.WHITE);
        canvas.drawText(label, left, bottom - dp(10), paint);
    }

    void loadSatellite(String key, int z, int x, int y) {
        try {
            File dir = new File(getContext().getFilesDir(), "satellite");
            dir.mkdirs();
            File f = new File(dir, RegionPlan.tileName(z, x, y));
            Bitmap b = f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
            if (b == null) b = readAncestor(dir, z, x, y);
            if (b == null && online) {
                HttpURLConnection h =
                        (HttpURLConnection)
                                new URL(RegionPlan.satelliteUrl(z, x, y)).openConnection();
                liveConnections.add(h);
                MapNetwork.configure(h);
                h.setRequestProperty("User-Agent", "MapNotes/0.11 (Android; ru.main.explorer)");
                h.setConnectTimeout(10000);
                h.setReadTimeout(15000);
                try {
                    MapNetwork.register(getContext(), h);
                    int code = h.getResponseCode();
                    if (code != 200) throw new IOException("HTTP " + code);
                    byte[] bytes;
                    try (InputStream in = h.getInputStream();
                            ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[16384];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            if (out.size() > 2000000)
                                throw new IOException(
                                        Language.text(getContext(), R.string.tile_too_large));
                        }
                        bytes = out.toByteArray();
                    }
                    b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (b == null)
                        throw new IOException(
                                Language.text(
                                        getContext(), R.string.server_did_not_return_an_image));
                    File tmp =
                            new File(
                                    getContext().getCacheDir(),
                                    "sat-" + RegionPlan.tileName(z, x, y));
                    try (FileOutputStream out = new FileOutputStream(tmp)) {
                        out.write(bytes);
                    }
                    if (!tmp.renameTo(f))
                        throw new IOException(
                                Language.text(getContext(), R.string.could_not_save_image));
                } finally {
                    liveConnections.remove(h);
                    MapNetwork.finish(h);
                }
            }
            if (b == null)
                throw new IOException(Language.text(getContext(), R.string.no_saved_image));
            tiles.put(key, b);
            failed.remove(key);
            satelliteErrors.remove(key);
            satelliteRetryAt.remove(key);
        } catch (Exception e) {
            failed.add(key);
            satelliteErrors.put(
                    key,
                    e instanceof java.net.SocketTimeoutException
                            ? Language.text(getContext(), R.string.server_not_responding)
                            : e instanceof java.net.UnknownHostException
                                    ? Language.text(getContext(), R.string.cannot_connect_to_server)
                                    : e.getMessage() == null
                                            ? Language.text(getContext(), R.string.network_error)
                                            : e.getMessage());
            satelliteRetryAt.put(key, android.os.SystemClock.uptimeMillis() + 10000);
            postInvalidateDelayed(10000);
        } finally {
            pending.remove(key);
            postInvalidate();
        }
    }

    void loadTile(String key, int z, int x, int y) {
        try {
            Bitmap b = null;
            synchronized (this) {
                if (offline != null)
                    try (Cursor c =
                            offline.rawQuery(
                                    "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                                    new String[] {"" + z, "" + x, "" + ((1 << z) - 1 - y)})) {
                        if (c.moveToFirst()) {
                            byte[] v = c.getBlob(0);
                            b = BitmapFactory.decodeByteArray(v, 0, v.length);
                        }
                    }
            }
            File f = new File(getContext().getCacheDir(), key.replace('/', '_') + ".png");
            if (b == null && f.exists()) b = BitmapFactory.decodeFile(f.getPath());
            if (b == null && online) {
                HttpURLConnection h =
                        (HttpURLConnection)
                                new URL("https://tile.openstreetmap.org/" + key + ".png")
                                        .openConnection();
                liveConnections.add(h);
                MapNetwork.configure(h);
                h.setRequestProperty("User-Agent", "MapNotes/0.11 (Android; ru.main.explorer)");
                h.setConnectTimeout(8000);
                h.setReadTimeout(8000);
                try {
                    MapNetwork.register(getContext(), h);
                    if (h.getResponseCode() == 200) {
                        byte[] data;
                        try (InputStream in = h.getInputStream();
                                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                            byte[] buffer = new byte[16384];
                            int read;
                            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                            data = out.toByteArray();
                        }
                        b = BitmapFactory.decodeByteArray(data, 0, data.length);
                        if (b != null)
                            try (FileOutputStream out = new FileOutputStream(f)) {
                                out.write(data);
                            }
                    }
                } finally {
                    liveConnections.remove(h);
                    MapNetwork.finish(h);
                }
            }
            if (b != null) tiles.put(key, b);
            else {
                failed.add(key);
                satelliteRetryAt.put(key, android.os.SystemClock.uptimeMillis() + 10000);
                postInvalidateDelayed(10000);
            }
        } catch (Exception e) {
            failed.add(key);
            satelliteRetryAt.put(key, android.os.SystemClock.uptimeMillis() + 10000);
            postInvalidateDelayed(10000);
        } finally {
            pending.remove(key);
            postInvalidate();
        }
    }
}

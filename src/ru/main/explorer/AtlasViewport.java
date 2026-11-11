package ru.main.explorer;

import android.graphics.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Фоновый точный viewport для насыщенного набора: глобальный бюджет и порядок legacy. */
final class AtlasViewport {
    static final long MAX_READY_BYTES = 24L * 1024 * 1024;
    static final long MAX_WORK_BYTES = 32L * 1024 * 1024;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable changed;
    private AtlasTiles.Snapshot source, observed;
    private Request current, latest, viewRequest;
    private long observation;
    private Frame ready;
    private boolean running, closed;
    volatile int requested, completed, cancelled;
    volatile long buildNanos;

    AtlasViewport(Runnable changed) {
        this.changed = changed;
    }

    static final class Request {
        final WeakReference<AtlasTiles.Snapshot> source;
        final String dependencies, key;
        final double cx, cy;
        final int zoom, width, height;
        final float density;
        final boolean illustrated, sourcesReady;
        final String style;
        final ArrayList<Dependency> inputs = new ArrayList<>();
        long checkedObservation = -1;
        boolean sameSources;

        Request(AtlasTiles.Snapshot snapshot, double cx, double cy, int width, int height) {
            source = new WeakReference<>(snapshot);
            this.cx = cx;
            this.cy = cy;
            zoom = snapshot.zoom;
            this.width = width;
            this.height = height;
            density = snapshot.density;
            illustrated = snapshot.illustrated;
            style = zoom + ":" + density + ":" + illustrated + ":" + width + ":" + height;
            StringBuilder signature = new StringBuilder(style);
            boolean allReady = true;
            RectF extent = new RectF(0, 0, width, height);
            for (AtlasTiles.Layer layer : snapshot.layers) {
                Dependency dependency = new Dependency(layer);
                if (!RectF.intersects(dependency.screen(cx, cy, zoom, width, height), extent))
                    continue;
                inputs.add(dependency);
                signature.append(':').append(layer.key);
                allReady &= layer.ready;
            }
            dependencies = signature.toString();
            sourcesReady = allReady;
            key = dependencies + ":" + cx + ":" + cy + ":" + width + ":" + height;
        }
    }

    private static final class Dependency {
        final String key;
        final double left, right, top, bottom;

        Dependency(AtlasTiles.Layer layer) {
            key = layer.key;
            left = layer.left;
            right = layer.right;
            top = layer.top;
            bottom = layer.bottom;
        }

        RectF screen(double cx, double cy, int zoom, int width, int height) {
            double scale = 1 << zoom;
            double center = wrapped((left + right) / 2 - cx);
            double half = (right - left) / 2;
            return new RectF(
                    (float) ((center - half) * scale + width / 2.0),
                    (float) ((top - cy) * scale + height / 2.0),
                    (float) ((center + half) * scale + width / 2.0),
                    (float) ((bottom - cy) * scale + height / 2.0));
        }
    }

    static final class Frame {
        final Request request;
        final Bitmap base, color;
        int vertices, labels;

        Frame(Request request) {
            this.request = request;
            base = Bitmap.createBitmap(request.width, request.height, Bitmap.Config.ARGB_8888);
            color = Bitmap.createBitmap(request.width, request.height, Bitmap.Config.ARGB_8888);
            base.setDensity(Bitmap.DENSITY_NONE);
            color.setDensity(Bitmap.DENSITY_NONE);
        }
    }

    synchronized AtlasCoverage draw(
            Canvas canvas,
            AtlasTiles.Snapshot snapshot,
            double cx,
            double cy,
            int width,
            int height,
            boolean hybrid,
            float fraction) {
        AtlasCoverage coverage = new AtlasCoverage();
        long pixels = (long) width * height;
        if (closed
                || width <= 0
                || height <= 0
                || pixels * 8 > MAX_READY_BYTES
                || pixels * 8 + 80000L * 40 > MAX_WORK_BYTES) return coverage;
        Request request = new Request(snapshot, cx, cy, width, height);
        observed = snapshot;
        viewRequest = request;
        observation++;
        boolean exact = ready != null && ready.request.key.equals(request.key);
        if (request.sourcesReady
                && !exact
                && (current == null || !current.key.equals(request.key))) {
            source = snapshot;
            current = latest = request;
            requested++;
            if (!running) {
                running = true;
                worker.execute(this::drain);
            }
        }
        coverage = replayCoverage(snapshot, request, fraction);
        if (coverage.isEmpty()) return coverage;
        double dx = wrapped(ready.request.cx - cx) * (1 << request.zoom);
        double dy = (ready.request.cy - cy) * (1 << request.zoom);
        int save = canvas.save();
        coverage.clip(canvas);
        canvas.drawBitmap(
                hybrid ? ready.color : ready.base,
                (float) dx,
                (float) dy,
                new Paint(Paint.FILTER_BITMAP_FLAG));
        canvas.restoreToCount(save);
        return coverage;
    }

    private AtlasCoverage replayCoverage(
            AtlasTiles.Snapshot snapshot, Request target, float fraction) {
        AtlasCoverage result = new AtlasCoverage();
        if (ready == null || !ready.request.style.equals(target.style)) return result;
        Request original = ready.request;
        float dx = (float) (wrapped(original.cx - target.cx) * (1 << target.zoom));
        float dy = (float) ((original.cy - target.cy) * (1 << target.zoom));
        RectF overlap = new RectF(dx, dy, dx + original.width, dy + original.height);
        if (!overlap.intersect(AtlasCoverage.visible(target.width, target.height, fraction)))
            return result;
        result.append(overlap);
        LinkedHashMap<String, Dependency> before = new LinkedHashMap<>(),
                after = new LinkedHashMap<>();
        for (Dependency dependency : original.inputs) putDependency(before, dependency);
        for (AtlasTiles.Layer layer : snapshot.layers) putDependency(after, new Dependency(layer));
        ArrayList<String> oldOrder = new ArrayList<>(), newOrder = new ArrayList<>();
        for (String key : before.keySet()) if (after.containsKey(key)) oldOrder.add(key);
        for (String key : after.keySet()) if (before.containsKey(key)) newOrder.add(key);
        boolean reordered = !oldOrder.equals(newOrder);
        for (Map.Entry<String, Dependency> entry : before.entrySet())
            if (!after.containsKey(entry.getKey()) || reordered)
                remove(result, entry.getValue(), target);
        for (Map.Entry<String, Dependency> entry : after.entrySet())
            if (!before.containsKey(entry.getKey())) remove(result, entry.getValue(), target);
        return result;
    }

    private static void putDependency(Map<String, Dependency> dependencies, Dependency dependency) {
        String key = dependency.key;
        for (int occurrence = 1; dependencies.containsKey(key); occurrence++)
            key = dependency.key + "\u0000" + occurrence;
        dependencies.put(key, dependency);
    }

    private static void remove(AtlasCoverage coverage, Dependency dependency, Request target) {
        RectF bounds =
                dependency.screen(target.cx, target.cy, target.zoom, target.width, target.height);
        bounds.inset(-1, -1);
        coverage.subtract(bounds);
    }

    synchronized boolean supports(int width, int height) {
        long bytes = (long) width * height * 8;
        return width > 0
                && height > 0
                && bytes <= MAX_READY_BYTES
                && bytes + 80000L * 40 <= MAX_WORK_BYTES;
    }

    synchronized void pause() {
        source = null;
        observed = null;
        viewRequest = null;
        current = latest = null;
    }

    private synchronized boolean obsolete(Request request) {
        if (closed
                || observed == null
                || viewRequest == null
                || !request.style.equals(viewRequest.style)
                || Math.abs(wrapped(request.cx - viewRequest.cx)) * (1 << request.zoom)
                        > request.width
                || Math.abs(request.cy - viewRequest.cy) * (1 << request.zoom) > request.height)
            return true;
        if (request.checkedObservation != observation) {
            request.sameSources =
                    request.dependencies.equals(
                            new Request(
                                            observed,
                                            request.cx,
                                            request.cy,
                                            request.width,
                                            request.height)
                                    .dependencies);
            request.checkedObservation = observation;
        }
        return !request.sameSources;
    }

    private void drain() {
        while (true) {
            Request request;
            synchronized (this) {
                request = latest;
                latest = null;
                if (request == null) {
                    running = false;
                    notifyAll();
                    return;
                }
            }
            try {
                AtlasTiles.Snapshot snapshot = request.source.get();
                if (snapshot == null || obsolete(request)) {
                    discard(request);
                    continue;
                }
                long start = System.nanoTime();
                Frame frame = render(snapshot, request, () -> obsolete(request));
                buildNanos += System.nanoTime() - start;
                synchronized (this) {
                    if (frame == null || obsolete(request)) {
                        discard(request);
                        continue;
                    }
                    ready = frame;
                    completed++;
                    if (!closed && changed != null) changed.run();
                }
            } catch (OutOfMemoryError ignored) {
                synchronized (this) {
                    cancelled++;
                    current = null;
                }
            }
        }
    }

    private synchronized void discard(Request request) {
        cancelled++;
        if (current == request) current = null;
    }

    synchronized void awaitIdle() throws InterruptedException {
        long end = System.currentTimeMillis() + 30000;
        while (running && System.currentTimeMillis() < end) wait(50);
        if (running) throw new IllegalStateException("Viewport worker timeout");
    }

    synchronized void close() {
        closed = true;
        pause();
        ready = null;
        worker.shutdown();
    }

    synchronized int lastVertices() {
        return ready == null ? 0 : ready.vertices;
    }

    synchronized boolean exactReady() {
        return viewRequest != null && ready != null && viewRequest.key.equals(ready.request.key);
    }

    synchronized boolean hasReady(
            AtlasTiles.Snapshot snapshot,
            double cx,
            double cy,
            int width,
            int height,
            float fraction) {
        return !closed
                && !replayCoverage(snapshot, new Request(snapshot, cx, cy, width, height), fraction)
                        .isEmpty();
    }

    private static double wrapped(double dx) {
        if (dx > 128) dx -= 256;
        if (dx < -128) dx += 256;
        return dx;
    }

    private static float x(double world, Request r) {
        return (float) (wrapped(world - r.cx) * (1 << r.zoom) + r.width / 2.0);
    }

    private static float y(double world, Request r) {
        return (float) ((world - r.cy) * (1 << r.zoom) + r.height / 2.0);
    }

    static Frame render(AtlasTiles.Snapshot s, Request r, BooleanSupplier cancelled) {
        Frame frame = new Frame(r);
        Canvas[] canvases = {new Canvas(frame.base), new Canvas(frame.color)};
        Paint p = new Paint(3);
        Path path = new Path();
        Set<String> names = new HashSet<>();
        ArrayList<RectF> boxes = new ArrayList<>();
        float dp = Math.round(s.density);
        for (AtlasTiles.Layer layer : s.layers) {
            if (cancelled.getAsBoolean()) return null;
            float left = x(layer.left, r), right = x(layer.right, r);
            float top = y(layer.top, r), bottom = y(layer.bottom, r);
            if (right < 0 || left > r.width || bottom < 0 || top > r.height || right <= left)
                continue;
            int[] saves = {canvases[0].save(), canvases[1].save()};
            for (Canvas canvas : canvases) canvas.clipRect(left, top, right, bottom);
            if (!layer.buildingsOnly && !s.illustrated) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xffc6c6b0);
                canvases[0].drawRect(left, top, right, bottom, p);
            }
            // features уже упорядочены decoder по pass/ordinal. Никакого View/Way в worker.
            for (AtlasTiles.Feature f : layer.features) {
                if (cancelled.getAsBoolean()) return null;
                if (80000 - frame.vertices < 2) break;
                float l = x(f.left, r), rr = x(f.right, r), t = y(f.top, r), b = y(f.bottom, r);
                if (rr < 0 || l > r.width || b < 0 || t > r.height) continue;
                boolean road = f.pass == 2;
                if (road && !MapDetail.roadVisible(f.kind, r.zoom)) continue;
                if (f.pass == 1 && !MapDetail.buildingVisible(r.zoom, rr - l, b - t)) continue;
                if (frame.vertices + Math.min(4096, f.xy.length / 2) > 80000) continue;
                path.rewind();
                int stride = Math.max(2, 2 * (int) Math.ceil(f.xy.length / 8192.0));
                float previousX = Float.NaN, previousY = Float.NaN;
                for (int i = 0; i < f.xy.length; i = Math.min(f.xy.length, i + stride)) {
                    float xx = x(f.xy[i], r), yy = y(f.xy[i + 1], r);
                    if (i == 0) {
                        path.moveTo(xx, yy);
                        previousX = xx;
                        previousY = yy;
                        frame.vertices++;
                    } else if (i == f.xy.length - 2
                            || Math.abs(xx - previousX) + Math.abs(yy - previousY) >= 1.5f) {
                        path.lineTo(xx, yy);
                        previousX = xx;
                        previousY = yy;
                        frame.vertices++;
                    }
                }
                if (f.area) path.close();
                for (int color = 0; color < 2; color++) {
                    boolean hybrid = color == 1;
                    if (!road && f.area && (!hybrid || s.illustrated)) {
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(
                                s.illustrated
                                        ? ("water".equals(f.type)
                                                ? (hybrid ? 0xff78aeb7 : 0xff9cabb1)
                                                : "building".equals(f.type)
                                                        ? (hybrid ? 0xffe5d8b6 : 0xffb9b7a2)
                                                        : (hybrid ? 0xffa6bf89 : 0xffbebfa8))
                                        : ("water".equals(f.type)
                                                ? 0xffa9b6ba
                                                : "building".equals(f.type)
                                                        ? 0xffa3a8a5
                                                        : 0xffbfc6be));
                        canvases[color].drawPath(path, p);
                    }
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeJoin(Paint.Join.ROUND);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setStrokeWidth(dp * (road ? MapDetail.roadWidth(r.zoom) : .6f));
                    p.setColor(
                            s.illustrated
                                    ? (road ? 0xfff5e9c9 : 0xff8b9c7b)
                                    : (hybrid
                                            ? (road ? 0xfff4e9a4 : 0xddd9e0d4)
                                            : (road ? 0xfff5f5ee : 0xff7d8982)));
                    canvases[color].drawPath(path, p);
                }
                if (!road
                        || r.zoom < 16
                        || f.name.isEmpty()
                        || boxes.size() >= 24
                        || names.contains(f.name)) continue;
                float xx = x(f.xy[0], r), yy = y(f.xy[1], r);
                if (xx <= 0
                        || xx >= r.width - Math.round(90 * s.density)
                        || yy <= Math.round(20 * s.density)
                        || yy >= r.height - Math.round(45 * s.density)) continue;
                p.setStyle(Paint.Style.FILL);
                p.setTypeface(Typeface.create("serif", Typeface.NORMAL));
                p.setTextSize(Math.round(11 * s.density));
                String label = f.name.length() > 48 ? f.name.substring(0, 48) : f.name;
                RectF box =
                        new RectF(
                                xx,
                                yy - Math.round(18 * s.density),
                                xx + p.measureText(label) + Math.round(12 * s.density),
                                yy + Math.round(8 * s.density));
                boolean overlaps = false;
                for (RectF other : boxes)
                    if (RectF.intersects(box, other)) {
                        overlaps = true;
                        break;
                    }
                if (overlaps) continue;
                boxes.add(box);
                names.add(f.name);
                frame.labels++;
                for (int color = 0; color < 2; color++) {
                    p.setColor(color == 1 ? Color.WHITE : 0xff33463c);
                    canvases[color].drawText(
                            label,
                            xx + Math.round(3 * s.density),
                            yy - Math.round(3 * s.density),
                            p);
                }
            }
            for (int color = 0; color < 2; color++) canvases[color].restoreToCount(saves[color]);
        }
        return frame;
    }
}

package ru.main.explorer;

import android.graphics.*;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Фоновый viewport: логические координаты и порядок legacy, ограниченный размер raster. */
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
        final int zoom, width, height, rasterWidth, rasterHeight;
        final float density;
        final boolean illustrated, sourcesReady;
        final String style;
        final ArrayList<AtlasReplay.Input> inputs = new ArrayList<>();
        long checkedObservation = -1;
        boolean sameSources;
        int cancellationReason, replayReason;

        Request(AtlasTiles.Snapshot snapshot, double cx, double cy, int width, int height) {
            source = new WeakReference<>(snapshot);
            this.cx = cx;
            this.cy = cy;
            zoom = snapshot.zoom;
            this.width = width;
            this.height = height;
            long maxPixels = Math.min(MAX_READY_BYTES, MAX_WORK_BYTES - 80000L * 40) / 8;
            double rasterScale = Math.min(1, Math.sqrt(maxPixels / ((double) width * height)));
            // Размеры логического экрана не меняются: уменьшается только raster-разрешение.
            rasterWidth =
                    Math.max(1, Math.min((int) maxPixels, (int) Math.floor(width * rasterScale)));
            rasterHeight =
                    Math.max(
                            1,
                            Math.min(
                                    (int) (maxPixels / rasterWidth),
                                    (int) Math.floor(height * rasterScale)));
            density = snapshot.density;
            illustrated = snapshot.illustrated;
            style = density + ":" + illustrated + ":" + width + ":" + height;
            StringBuilder signature = new StringBuilder(zoom + ":" + style);
            boolean allReady = true;
            RectF extent = new RectF(0, 0, width, height);
            for (AtlasTiles.Layer layer : snapshot.layers) {
                AtlasReplay.Input dependency = new AtlasReplay.Input(layer);
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

    static final class Frame {
        final Request request;
        final Bitmap base, color;
        int vertices, labels;

        Frame(Request request) {
            this.request = request;
            base =
                    Bitmap.createBitmap(
                            request.rasterWidth, request.rasterHeight, Bitmap.Config.ARGB_8888);
            color =
                    Bitmap.createBitmap(
                            request.rasterWidth, request.rasterHeight, Bitmap.Config.ARGB_8888);
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
        if (closed || !supports(width, height)) return coverage;
        Request request = new Request(snapshot, cx, cy, width, height);
        observed = snapshot;
        viewRequest = request;
        observation++;
        boolean exact = ready != null && ready.request.key.equals(request.key);
        int state =
                !request.sourcesReady
                        ? 4
                        : exact ? 3 : current != null && current.key.equals(request.key) ? 2 : 1;
        MapTrace.record(
                MapTrace.Lane.VIEWPORT,
                MapTrace.Event.STATE,
                MapTrace.id(request),
                request.zoom,
                request.sourcesReady ? 1 : 0,
                state);
        if (request.sourcesReady
                && !exact
                && (current == null || !current.key.equals(request.key))) {
            source = snapshot;
            cancelPending(8);
            current = latest = request;
            requested++;
            MapTrace.record(
                    MapTrace.Lane.VIEWPORT,
                    MapTrace.Event.QUEUE,
                    MapTrace.id(request),
                    request.zoom,
                    request.inputs.size(),
                    request.sourcesReady ? 1 : 0);
            if (!running) {
                running = true;
                worker.execute(this::drain);
            }
        }
        coverage = replayCoverage(snapshot, request, fraction);
        MapTrace.record(
                MapTrace.Lane.VIEWPORT,
                MapTrace.Event.REPLAY,
                MapTrace.id(request),
                ready == null ? -1 : ready.request.zoom,
                coverage.isEmpty() ? 0 : 1,
                exact ? 0 : request.replayReason);
        if (coverage.isEmpty()) return coverage;
        double factor = Math.pow(2, request.zoom - ready.request.zoom);
        double dx =
                wrapped(ready.request.cx - cx) * (1 << request.zoom)
                        + width / 2.0
                        - ready.request.width * factor / 2;
        double dy =
                (ready.request.cy - cy) * (1 << request.zoom)
                        + height / 2.0
                        - ready.request.height * factor / 2;
        int save = canvas.save();
        coverage.clip(canvas);
        canvas.drawBitmap(
                hybrid ? ready.color : ready.base,
                null,
                new RectF(
                        (float) dx,
                        (float) dy,
                        (float) (dx + ready.request.width * factor),
                        (float) (dy + ready.request.height * factor)),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        canvas.restoreToCount(save);
        return coverage;
    }

    private AtlasCoverage replayCoverage(
            AtlasTiles.Snapshot snapshot, Request target, float fraction) {
        AtlasCoverage result = new AtlasCoverage();
        if (ready == null || !ready.request.style.equals(target.style)) {
            target.replayReason = ready == null ? 1 : 2;
            return result;
        }
        Request original = ready.request;
        double factor = Math.pow(2, target.zoom - original.zoom);
        float dx =
                (float)
                        (wrapped(original.cx - target.cx) * (1 << target.zoom)
                                + target.width / 2.0
                                - original.width * factor / 2);
        float dy =
                (float)
                        ((original.cy - target.cy) * (1 << target.zoom)
                                + target.height / 2.0
                                - original.height * factor / 2);
        RectF overlap =
                new RectF(
                        dx,
                        dy,
                        (float) (dx + original.width * factor),
                        (float) (dy + original.height * factor));
        if (!overlap.intersect(AtlasCoverage.visible(target.width, target.height, fraction))) {
            target.replayReason = 3;
            return result;
        }
        result.append(overlap);
        boolean alternative = (original.zoom < 13) != (target.zoom < 13);
        AtlasReplay.restrict(
                result,
                original.inputs,
                AtlasReplay.inputs(snapshot.layers),
                target.cx,
                target.cy,
                target.zoom,
                target.width,
                target.height,
                alternative);
        target.replayReason = result.isEmpty() ? 4 : alternative ? 5 : 6;
        return result;
    }

    synchronized boolean supports(int width, int height) {
        return width > 0 && height > 0;
    }

    synchronized void pause() {
        cancelPending(closed ? 1 : 9);
        MapTrace.record(
                MapTrace.Lane.VIEWPORT,
                MapTrace.Event.STATE,
                0,
                0,
                ready == null ? 0 : 1,
                closed ? 6 : 5);
        source = null;
        observed = null;
        viewRequest = null;
        current = latest = null;
    }

    private void cancelPending(int reason) {
        if (latest == null) return;
        latest.cancellationReason = reason;
        discard(latest);
        latest = null;
    }

    private synchronized boolean obsolete(Request request) {
        if (closed
                || observed == null
                || viewRequest == null
                || request.zoom != viewRequest.zoom
                || !request.style.equals(viewRequest.style)
                || Math.abs(wrapped(request.cx - viewRequest.cx)) * (1 << request.zoom)
                        > request.width
                || Math.abs(request.cy - viewRequest.cy) * (1 << request.zoom) > request.height) {
            request.cancellationReason =
                    closed
                            ? 1
                            : observed == null || viewRequest == null
                                    ? 2
                                    : request.zoom != viewRequest.zoom
                                            ? 3
                                            : !request.style.equals(viewRequest.style) ? 4 : 5;
            return true;
        }
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
        request.cancellationReason = request.sameSources ? 0 : 6;
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
                if (snapshot == null) request.cancellationReason = 7;
                if (snapshot == null || obsolete(request)) {
                    discard(request);
                    continue;
                }
                long start = System.nanoTime();
                MapTrace.record(
                        MapTrace.Lane.VIEWPORT,
                        MapTrace.Event.START,
                        MapTrace.id(request),
                        request.zoom,
                        request.rasterWidth,
                        request.rasterHeight);
                Frame frame = render(snapshot, request, () -> obsolete(request));
                buildNanos += System.nanoTime() - start;
                synchronized (this) {
                    if (frame == null || obsolete(request)) {
                        if (request.cancellationReason == 0 && !obsolete(request))
                            request.cancellationReason = 10;
                        discard(request);
                        continue;
                    }
                    ready = frame;
                    completed++;
                    MapTrace.record(
                            MapTrace.Lane.VIEWPORT,
                            MapTrace.Event.PUBLISH,
                            MapTrace.id(request),
                            frame.vertices,
                            System.nanoTime() - start,
                            (long) frame.base.getAllocationByteCount()
                                    + frame.color.getAllocationByteCount());
                    if (!closed && changed != null) changed.run();
                }
            } catch (OutOfMemoryError ignored) {
                MapTrace.record(
                        MapTrace.Lane.VIEWPORT,
                        MapTrace.Event.OOM,
                        MapTrace.id(request),
                        request.rasterWidth,
                        request.rasterHeight,
                        0);
                synchronized (this) {
                    request.cancellationReason = 12;
                    discard(request);
                }
            }
        }
    }

    private synchronized void discard(Request request) {
        MapTrace.record(
                MapTrace.Lane.VIEWPORT,
                MapTrace.Event.CANCEL,
                MapTrace.id(request),
                request.zoom,
                viewRequest == null ? -1 : viewRequest.zoom,
                request.cancellationReason);
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
        for (Canvas canvas : canvases)
            canvas.scale(r.rasterWidth / (float) r.width, r.rasterHeight / (float) r.height);
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
            try (AtlasTiles.Features features =
                    layer.openFeatures(
                            r.cx,
                            r.cy,
                            r.width / (2.0 * (1 << r.zoom)),
                            r.height / (2.0 * (1 << r.zoom)),
                            r.zoom)) {
                AtlasTiles.Feature f;
                while ((f = features.next(cancelled)) != null) {
                    if (cancelled.getAsBoolean()) return null;
                    if (80000 - frame.vertices < 2) break;
                    float l = x(f.left, r), rr = x(f.right, r), t = y(f.top, r), b = y(f.bottom, r);
                    if (rr < 0 || l > r.width || b < 0 || t > r.height) continue;
                    boolean road = f.pass == 2;
                    if (road && !MapDetail.roadVisible(f.kind, r.zoom)) continue;
                    if (f.pass == 1 && !MapDetail.buildingVisible(r.zoom, rr - l, b - t)) continue;
                    if (frame.vertices + Math.min(4096, f.sourceLength / 2) > 80000) continue;
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
                        } else if (i == f.xy.length - 2 && f.lastSampleIsEnd
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
            } catch (IOException e) {
                MapTrace.record(
                        MapTrace.Lane.VIEWPORT, MapTrace.Event.ERROR, MapTrace.id(r), 0, 0, 0);
                return null;
            }
            for (int color = 0; color < 2; color++) canvases[color].restoreToCount(saves[color]);
        }
        return frame;
    }
}

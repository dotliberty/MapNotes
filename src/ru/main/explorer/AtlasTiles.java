package ru.main.explorer;

import android.graphics.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.ref.WeakReference;
import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Bounded asynchronous world-pixel tiles. No worker input contains a View or mutable Way. */
final class AtlasTiles {
    static final int SIZE = 256, GUARD = 8, MAX_PENDING = 12;
    static final long MAX_BYTES = 48L * 1024 * 1024;
    static final long MAX_WORK_BYTES = 32L * 1024 * 1024;

    static final class Feature {
        final double[] xy;
        final String type, name, kind;
        final boolean area;
        final int pass, ordinal;
        final double left, right, top, bottom;
        final int sourceLength;
        final boolean lastSampleIsEnd;

        Feature(AtlasGeometry.Entry e) {
            // Entry's projected array is decoder-owned, never exposed or modified by rendering.
            xy = e.xy;
            type = e.type;
            name = e.name;
            kind = e.kind;
            area = e.area;
            pass = e.pass;
            ordinal = e.ordinal;
            left = e.left;
            right = e.right;
            top = e.top;
            bottom = e.bottom;
            sourceLength = xy.length;
            lastSampleIsEnd = true;
        }

        Feature(
                double[] xy,
                String type,
                String name,
                String kind,
                boolean area,
                int pass,
                int ordinal,
                double left,
                double right,
                double top,
                double bottom,
                int sourceLength,
                boolean lastSampleIsEnd) {
            this.xy = xy;
            this.type = type;
            this.name = name;
            this.kind = kind;
            this.area = area;
            this.pass = pass;
            this.ordinal = ordinal;
            this.left = left;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
            this.sourceLength = sourceLength;
            this.lastSampleIsEnd = lastSampleIsEnd;
        }
    }

    static final class Layer {
        final double left, right, top, bottom;
        final boolean buildingsOnly;
        final List<Feature> features;
        final long retainedBytes;
        final AtlasFeatureIndex index, labels;
        final String key;
        final String replayFamily;
        final boolean ready;
        AtlasDiskSource disk;

        Features openFeatures(double cx, double cy, double dx, double dy, int zoom)
                throws IOException {
            return new Features(features, disk == null ? null : disk.open(cx, cy, dx, dy, zoom));
        }

        Layer(
                double left,
                double right,
                double top,
                double bottom,
                boolean buildingsOnly,
                List<Feature> features) {
            this.left = left;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
            this.buildingsOnly = buildingsOnly;
            this.features = Collections.unmodifiableList(new ArrayList<>(features));
            index = labels = null;
            key = "job";
            replayFamily = null;
            ready = true;
            long bytes = 256;
            for (Feature feature : features) bytes += 128L + feature.xy.length * 8L;
            retainedBytes = bytes;
        }

        Layer(
                double left,
                double right,
                double top,
                double bottom,
                boolean buildingsOnly,
                AtlasFeatureIndex index,
                AtlasFeatureIndex labels,
                String key) {
            this(left, right, top, bottom, buildingsOnly, index, labels, key, true);
        }

        Layer(
                double left,
                double right,
                double top,
                double bottom,
                boolean buildingsOnly,
                AtlasFeatureIndex index,
                AtlasFeatureIndex labels,
                String key,
                boolean ready) {
            this(left, right, top, bottom, buildingsOnly, index, labels, key, ready, null);
        }

        Layer(
                double left,
                double right,
                double top,
                double bottom,
                boolean buildingsOnly,
                AtlasFeatureIndex index,
                AtlasFeatureIndex labels,
                String key,
                boolean ready,
                String replayFamily) {
            this.left = left;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
            this.buildingsOnly = buildingsOnly;
            this.index = index;
            this.labels = labels;
            this.key = key;
            this.replayFamily = replayFamily;
            this.ready = ready;
            this.features = index.features;
            retainedBytes = index.bytes + labels.bytes;
        }

        boolean intersects(double cx, double cy, double radius) {
            double middle = (left + right) / 2;
            double dx = middle - cx;
            dx -= Math.floor((dx + 128) / 256) * 256;
            return Math.abs(dx) <= (right - left) / 2 + radius
                    && bottom >= cy - radius
                    && top <= cy + radius;
        }
    }

    static final class Features implements AutoCloseable {
        private final Iterator<Feature> memory;
        private final AtlasDiskSource.Cursor disk;

        Features(List<Feature> memory, AtlasDiskSource.Cursor disk) {
            this.memory = memory.iterator();
            this.disk = disk;
        }

        Feature next(BooleanSupplier cancelled) throws IOException {
            return disk != null ? disk.next(cancelled) : memory.hasNext() ? memory.next() : null;
        }

        public void close() throws IOException {
            if (disk != null) disk.close();
        }
    }

    static final class Snapshot {
        final String key;
        final String sourceKey;
        final int zoom;
        final float density;
        final boolean illustrated, hybrid;
        final List<Layer> layers;
        final long workBytes;

        Snapshot(
                String key,
                int zoom,
                float density,
                boolean illustrated,
                boolean hybrid,
                List<Layer> layers) {
            this.key = key;
            this.zoom = zoom;
            this.density = density;
            this.illustrated = illustrated;
            this.hybrid = hybrid;
            this.layers = Collections.unmodifiableList(new ArrayList<>(layers));
            StringBuilder dependencies =
                    new StringBuilder(zoom + ":" + density + ":" + illustrated);
            for (Layer layer : layers) dependencies.append(':').append(layer.key);
            sourceKey = dependencies.toString();
            long retained = (SIZE + 2L * GUARD) * (SIZE + 2L * GUARD) * 4;
            for (Layer layer : layers) retained += layer.retainedBytes;
            workBytes = retained;
        }

        String tileKey(long x, long y, boolean color) {
            double scale = 1 << zoom;
            double cx = (x * SIZE + SIZE / 2.0) / scale;
            double cy = (y * SIZE + SIZE / 2.0) / scale;
            StringBuilder result = new StringBuilder();
            result.append(zoom)
                    .append(':')
                    .append(density)
                    .append(':')
                    .append(illustrated)
                    .append(':')
                    .append(color)
                    .append(':')
                    .append(x)
                    .append(':')
                    .append(y);
            for (Layer layer : layers)
                if (layer.intersects(cx, cy, (SIZE / 2.0 + GUARD) / scale))
                    result.append(':').append(layer.key);
            return result.toString();
        }

        boolean canCrop(long x, long y) {
            double scale = 1 << zoom;
            double cx = (x * SIZE + SIZE / 2.0) / scale;
            double cy = (y * SIZE + SIZE / 2.0) / scale;
            double radius = (SIZE / 2.0 + GUARD) / scale;
            long vertices = 0;
            for (Layer layer : layers) {
                if (!layer.intersects(cx, cy, radius)) continue;
                // До выделения списков ограничиваем даже плотную prefetch-территорию.
                vertices +=
                        layer.index.vertices(
                                cx, cy, radius, radius, zoom, MAX_WORK_BYTES / 256 - vertices);
                if (vertices > MAX_WORK_BYTES / 256) return false;
            }
            return true;
        }

        Snapshot crop(long x, long y) {
            if (!sourcesReady(x, y) || !canCrop(x, y)) return null;
            double scale = 1 << zoom;
            double cx = (x * SIZE + SIZE / 2.0) / scale;
            double cy = (y * SIZE + SIZE / 2.0) / scale;
            double radius = (SIZE / 2.0 + GUARD) / scale;
            ArrayList<Layer> selected = new ArrayList<>();
            for (Layer layer : layers) {
                if (!layer.intersects(cx, cy, radius)) continue;
                List<Feature> features = layer.index.query(cx, cy, radius, radius, zoom);
                selected.add(
                        new Layer(
                                layer.left,
                                layer.right,
                                layer.top,
                                layer.bottom,
                                layer.buildingsOnly,
                                features));
            }
            return new Snapshot(key, zoom, density, illustrated, hybrid, selected);
        }

        boolean allReady() {
            for (Layer layer : layers) if (!layer.ready) return false;
            return true;
        }

        boolean hasDisk() {
            for (Layer layer : layers) if (layer.disk != null) return true;
            return false;
        }

        boolean sourcesReady(long x, long y) {
            double scale = 1 << zoom;
            double cx = (x * SIZE + SIZE / 2.0) / scale;
            double cy = (y * SIZE + SIZE / 2.0) / scale;
            for (Layer layer : layers)
                if (!layer.ready && layer.intersects(cx, cy, (SIZE / 2.0 + GUARD) / scale))
                    return false;
            return true;
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LinkedHashMap<String, Bitmap> cache = new LinkedHashMap<>(16, .75f, true);
    private final Set<String> pending = new HashSet<>();
    private final ArrayDeque<Job> visibleJobs = new ArrayDeque<>(),
            prefetchJobs = new ArrayDeque<>();
    private boolean draining;
    private final Runnable changed;
    private long bytes, generation, workBytes;
    private String scene;
    private Snapshot active, previousZoom;
    private final Snapshot[] activePass = new Snapshot[2];
    private long visibleX0, visibleY0, visibleX1, visibleY1;
    private boolean closed;
    int hits, misses, completed, cancelled;
    int visibleHits, visibleMisses;
    int lastLabelExamined, lastLabelCandidates, lastLabels;
    int uiPreparations;
    long uiPreparationNanos;
    volatile int workerPreparations;
    volatile long workerPreparationNanos;
    AtlasCoverage lastCoverage = new AtlasCoverage();

    AtlasTiles(Runnable changed) {
        this.changed = changed;
    }

    synchronized long bytes() {
        return bytes;
    }

    synchronized int pending() {
        return pending.size();
    }

    synchronized void scene(String key) {
        if (Objects.equals(scene, key) && active == null) return;
        scene = key;
        generation++;
        active = null;
        previousZoom = null;
        Arrays.fill(activePass, null);
        // Jobs retain their pending slots until they actually terminate: no unbounded old queue.
    }

    synchronized Bitmap request(Snapshot snapshot, long x, long y) {
        return request(snapshot, x, y, true);
    }

    private synchronized Bitmap request(Snapshot snapshot, long x, long y, boolean visible) {
        if (closed) return null;
        if (active == null) activePass[snapshot.hybrid ? 1 : 0] = snapshot;
        String key = snapshot.tileKey(x, y, snapshot.hybrid);
        Bitmap image = cache.get(key);
        if (image != null) {
            hits++;
            return image;
        }
        misses++;
        if (!snapshot.sourcesReady(x, y)) {
            MapTrace.record(
                    MapTrace.Lane.TILE,
                    MapTrace.Event.STATE,
                    MapTrace.id(snapshot),
                    snapshot.zoom,
                    snapshot.hybrid ? 1 : 0,
                    1);
            return null;
        }
        if (pending.contains(key)) {
            if (visible) {
                Iterator<Job> iterator = prefetchJobs.iterator();
                while (iterator.hasNext()) {
                    Job job = iterator.next();
                    if (!job.key.equals(key)) continue;
                    iterator.remove();
                    visibleJobs.addLast(job);
                    break;
                }
            }
            return null;
        }
        if (visible && pending.size() >= MAX_PENDING && !prefetchJobs.isEmpty())
            release(prefetchJobs.removeLast(), 9);
        if (pending.size() >= MAX_PENDING) return null;
        long reservation = (SIZE + 2L * GUARD) * (SIZE + 2L * GUARD) * 4;
        if (workBytes + reservation > MAX_WORK_BYTES) return null;
        pending.add(key);
        workBytes += reservation;
        Job task = new Job(snapshot, key, x, y, generation, reservation);
        MapTrace.record(
                MapTrace.Lane.TILE,
                MapTrace.Event.QUEUE,
                MapTrace.id(task),
                snapshot.zoom,
                snapshot.hybrid ? 1 : 0,
                pending.size());
        (visible ? visibleJobs : prefetchJobs).addLast(task);
        if (!draining) {
            draining = true;
            worker.execute(this::drain);
        }
        return null;
    }

    private final class Job {
        final WeakReference<Snapshot> source;
        final int zoom;
        final boolean color;
        final String key;
        final long x, y, token;
        long reservedBytes;

        Job(Snapshot snapshot, String key, long x, long y, long token, long reservation) {
            source = new WeakReference<>(snapshot);
            zoom = snapshot.zoom;
            color = snapshot.hybrid;
            this.key = key;
            this.x = x;
            this.y = y;
            this.token = token;
            reservedBytes = reservation;
        }

        boolean valid() {
            return AtlasTiles.this.valid(zoom, color, key, x, y, token);
        }
    }

    private void release(Job job) {
        release(job, invalidReason(job) == 0 ? 8 : invalidReason(job));
    }

    private void release(Job job, int reason) {
        pending.remove(job.key);
        workBytes -= job.reservedBytes;
        cancel(job, reason);
        notifyAll();
    }

    private void cancel(Job job, int reason) {
        cancelled++;
        MapTrace.record(
                MapTrace.Lane.TILE,
                MapTrace.Event.CANCEL,
                MapTrace.id(job),
                reason,
                job.zoom,
                job.color ? 1 : 0);
    }

    private int invalidReason(Job job) {
        return closed
                ? 1
                : generation != job.token
                        ? 2
                        : active == null
                                ? 0
                                : active.zoom != job.zoom
                                        ? 3
                                        : !job.key.equals(active.tileKey(job.x, job.y, job.color))
                                                ? 4
                                                : 0;
    }

    private void reprioritize() {
        ArrayList<Job> jobs = new ArrayList<>(visibleJobs);
        jobs.addAll(prefetchJobs);
        visibleJobs.clear();
        prefetchJobs.clear();
        for (Job job : jobs) {
            if (!job.valid()
                    || job.x < visibleX0 - 1
                    || job.x > visibleX1 + 1
                    || job.y < visibleY0 - 1
                    || job.y > visibleY1 + 1) {
                release(job);
            } else if (job.x >= visibleX0
                    && job.x <= visibleX1
                    && job.y >= visibleY0
                    && job.y <= visibleY1) {
                visibleJobs.addLast(job);
            } else {
                prefetchJobs.addLast(job);
            }
        }
    }

    private void drain() {
        while (true) {
            Job job;
            synchronized (this) {
                job = visibleJobs.isEmpty() ? prefetchJobs.pollFirst() : visibleJobs.pollFirst();
                if (job == null) {
                    draining = false;
                    return;
                }
            }
            try {
                long traceStart = System.nanoTime();
                MapTrace.record(
                        MapTrace.Lane.TILE,
                        MapTrace.Event.START,
                        MapTrace.id(job),
                        job.zoom,
                        job.color ? 1 : 0,
                        0);
                synchronized (this) {
                    if (!job.valid()) {
                        cancel(job, invalidReason(job));
                        continue;
                    }
                }
                Snapshot source;
                synchronized (this) {
                    source = activePass[job.color ? 1 : 0];
                    if (source == null
                            || source.zoom != job.zoom
                            || !job.key.equals(source.tileKey(job.x, job.y, job.color)))
                        source = job.source.get();
                }
                if (source == null) {
                    synchronized (this) {
                        cancel(job, 5);
                    }
                    continue;
                }
                long started = System.nanoTime();
                Snapshot prepared = source.crop(job.x, job.y);
                workerPreparationNanos += System.nanoTime() - started;
                workerPreparations++;
                synchronized (this) {
                    if (prepared == null
                            || !job.valid()
                            || workBytes - job.reservedBytes + prepared.workBytes
                                    > MAX_WORK_BYTES) {
                        cancel(job, !job.valid() ? invalidReason(job) : prepared == null ? 6 : 7);
                        continue;
                    }
                    workBytes += prepared.workBytes - job.reservedBytes;
                    job.reservedBytes = prepared.workBytes;
                }
                Bitmap result = render(prepared, job.x, job.y);
                synchronized (this) {
                    if (!job.valid()) {
                        cancel(job, invalidReason(job));
                        continue;
                    }
                    long size = result.getAllocationByteCount();
                    while (bytes + size > MAX_BYTES && !cache.isEmpty()) {
                        Iterator<Bitmap> iterator = cache.values().iterator();
                        bytes -= iterator.next().getAllocationByteCount();
                        iterator.remove();
                    }
                    cache.put(job.key, result);
                    bytes += size;
                    completed++;
                    MapTrace.record(
                            MapTrace.Lane.TILE,
                            MapTrace.Event.PUBLISH,
                            MapTrace.id(job),
                            job.zoom,
                            job.color ? 1 : 0,
                            System.nanoTime() - traceStart);
                }
            } catch (OutOfMemoryError ignored) {
                synchronized (this) {
                    cancel(job, 10);
                }
                MapTrace.record(
                        MapTrace.Lane.TILE,
                        MapTrace.Event.OOM,
                        MapTrace.id(job),
                        job.zoom,
                        job.color ? 1 : 0,
                        0);
                // Не публикуем неполный тайл; композиция раскрытия выполняется снаружи.
            } finally {
                synchronized (this) {
                    pending.remove(job.key);
                    workBytes -= job.reservedBytes;
                    MapTrace.record(
                            MapTrace.Lane.TILE,
                            MapTrace.Event.FINISH,
                            MapTrace.id(job),
                            pending.size(),
                            workBytes,
                            closed ? 1 : 0);
                    notifyAll();
                    if (!closed && changed != null) changed.run();
                }
            }
        }
    }

    private boolean valid(int zoom, boolean color, String key, long x, long y, long token) {
        return !closed
                && generation == token
                && (active == null
                        || active.zoom == zoom && key.equals(active.tileKey(x, y, color)));
    }

    synchronized void awaitIdle() throws InterruptedException {
        long end = System.currentTimeMillis() + 30000;
        while (!pending.isEmpty() && System.currentTimeMillis() < end) wait(50);
        if (!pending.isEmpty()) throw new IllegalStateException("Tile worker timeout");
    }

    synchronized void close() {
        closed = true;
        generation++;
        cache.clear();
        bytes = 0;
        active = previousZoom = null;
        Arrays.fill(activePass, null);
        // Drain queued tasks cheaply so every slot is released by its finally block.
        worker.shutdown();
    }

    boolean draw(Canvas canvas, Snapshot snapshot, double cx, double cy, int width, int height) {
        return draw(canvas, snapshot, cx, cy, width, height, 1);
    }

    boolean draw(
            Canvas canvas,
            Snapshot snapshot,
            double cx,
            double cy,
            int width,
            int height,
            float fraction) {
        lastCoverage = new AtlasCoverage();
        RectF visible = AtlasCoverage.visible(width, height, fraction);
        double scale = 1 << snapshot.zoom;
        double ox = cx * scale - width / 2.0, oy = cy * scale - height / 2.0;
        double halfWidth = width / (2.0 * fraction), halfHeight = height / (2.0 * fraction);
        long x0 = (long) Math.floor((cx * scale - halfWidth) / SIZE);
        long y0 = (long) Math.floor((cy * scale - halfHeight) / SIZE);
        long x1 = (long) Math.floor((cx * scale + halfWidth - .001) / SIZE);
        long y1 = (long) Math.floor((cy * scale + halfHeight - .001) / SIZE);
        synchronized (this) {
            // До первого пикселя проверяем постоянный отказ: повторять пустой тайл
            // бесконечно нельзя. Вызывающий renderer восстановит видимый векторный кадр.
            if (closed) return false;
            for (long y = y0; y <= y1; y++)
                for (long x = x0; x <= x1; x++) {
                    String key = snapshot.tileKey(x, y, snapshot.hybrid);
                    if (!cache.containsKey(key)
                            && !pending.contains(key)
                            && !snapshot.canCrop(x, y)) return false;
                }
            if (active != null && active.zoom != snapshot.zoom) previousZoom = active;
            active = snapshot;
            activePass[snapshot.hybrid ? 1 : 0] = snapshot;
            visibleX0 = x0;
            visibleX1 = x1;
            visibleY0 = y0;
            visibleY1 = y1;
            reprioritize();
        }
        Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        for (long y = y0; y <= y1; y++)
            for (long x = x0; x <= x1; x++) {
                Bitmap bitmap = request(snapshot, x, y);
                if (bitmap == null) {
                    visibleMisses++;
                    lastCoverage.append(
                            drawPrevious(canvas, snapshot, cx, cy, width, height, x, y, ox, oy)
                                    .intersect(visible));
                    continue;
                }
                visibleHits++;
                float left = (float) (x * SIZE - ox), top = (float) (y * SIZE - oy);
                int save = canvas.save();
                canvas.clipRect(left, top, left + SIZE, top + SIZE);
                canvas.drawBitmap(bitmap, left - GUARD, top - GUARD, bitmapPaint);
                canvas.restoreToCount(save);
                RectF covered = new RectF(left, top, left + SIZE, top + SIZE);
                if (covered.intersect(visible)) lastCoverage.append(covered);
            }
        drawLabels(canvas, snapshot, cx, cy, width, height, fraction);
        // Visible work is admitted first; the same bounded queue fills a one-tile neighbour ring.
        long guardedTileBytes = (SIZE + 2L * GUARD) * (SIZE + 2L * GUARD) * 4;
        if ((x1 - x0 + 3) * (y1 - y0 + 3) * guardedTileBytes * 2 <= MAX_BYTES)
            for (long y = y0 - 1; y <= y1 + 1; y++)
                for (long x = x0 - 1; x <= x1 + 1; x++) {
                    if (x < x0 || x > x1 || y < y0 || y > y1) request(snapshot, x, y, false);
                }
        return true;
    }

    private synchronized AtlasCoverage drawPrevious(
            Canvas canvas,
            Snapshot current,
            double cx,
            double cy,
            int width,
            int height,
            long tx,
            long ty,
            double ox,
            double oy) {
        AtlasCoverage coverage = new AtlasCoverage();
        Snapshot old = previousZoom;
        if (old == null || old.illustrated != current.illustrated || old.density != current.density)
            return coverage;
        double factor = Math.pow(2, current.zoom - old.zoom);
        if (factor < .5 || factor > 2) return coverage;
        double oldScale = 1 << old.zoom;
        double oldOx = cx * oldScale - width / (2 * factor);
        double oldOy = cy * oldScale - height / (2 * factor);
        double left = tx * SIZE - ox, top = ty * SIZE - oy;
        AtlasCoverage safe = new AtlasCoverage();
        safe.append(
                new RectF((float) left, (float) top, (float) (left + SIZE), (float) (top + SIZE)));
        AtlasReplay.restrict(
                safe,
                AtlasReplay.inputs(old.layers),
                AtlasReplay.inputs(current.layers),
                cx,
                cy,
                current.zoom,
                width,
                height,
                (old.zoom < 13) != (current.zoom < 13));
        if (safe.isEmpty()) return coverage;
        long x0 = (long) Math.floor((oldOx + left / factor) / SIZE);
        long y0 = (long) Math.floor((oldOy + top / factor) / SIZE);
        long x1 = (long) Math.floor((oldOx + (left + SIZE) / factor) / SIZE);
        long y1 = (long) Math.floor((oldOy + (top + SIZE) / factor) / SIZE);
        int save = canvas.save();
        safe.clip(canvas);
        canvas.clipRect((float) left, (float) top, (float) (left + SIZE), (float) (top + SIZE));
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        for (long y = y0; y <= y1; y++)
            for (long x = x0; x <= x1; x++) {
                Bitmap image = cache.get(old.tileKey(x, y, current.hybrid));
                if (image == null) continue;
                float l = (float) ((x * SIZE - GUARD - oldOx) * factor);
                float t = (float) ((y * SIZE - GUARD - oldOy) * factor);
                int tile = canvas.save();
                canvas.clipRect(
                        (float) ((x * SIZE - oldOx) * factor),
                        (float) ((y * SIZE - oldOy) * factor),
                        (float) (((x + 1) * SIZE - oldOx) * factor),
                        (float) (((y + 1) * SIZE - oldOy) * factor));
                canvas.drawBitmap(
                        image,
                        null,
                        new RectF(
                                l,
                                t,
                                (float) (l + image.getWidth() * factor),
                                (float) (t + image.getHeight() * factor)),
                        p);
                canvas.restoreToCount(tile);
                RectF covered =
                        new RectF(
                                (float) ((x * SIZE - oldOx) * factor),
                                (float) ((y * SIZE - oldOy) * factor),
                                (float) (((x + 1) * SIZE - oldOx) * factor),
                                (float) (((y + 1) * SIZE - oldOy) * factor));
                if (covered.intersect(
                        (float) left, (float) top, (float) (left + SIZE), (float) (top + SIZE)))
                    coverage.append(safe.intersect(covered));
            }
        canvas.restoreToCount(save);
        return coverage;
    }

    /** Replay готового покрытия при фоновой подготовке насыщенного viewport; новых jobs нет. */
    synchronized AtlasCoverage drawReady(
            Canvas canvas,
            Snapshot snapshot,
            double cx,
            double cy,
            int width,
            int height,
            float fraction) {
        lastCoverage = new AtlasCoverage();
        if (closed) return lastCoverage;
        RectF visible = AtlasCoverage.visible(width, height, fraction);
        if (active != null && active.zoom != snapshot.zoom) previousZoom = active;
        active = snapshot;
        activePass[snapshot.hybrid ? 1 : 0] = snapshot;
        double scale = 1 << snapshot.zoom;
        double ox = cx * scale - width / 2.0, oy = cy * scale - height / 2.0;
        double hw = width / (2.0 * fraction), hh = height / (2.0 * fraction);
        visibleX0 = (long) Math.floor((cx * scale - hw) / SIZE);
        visibleX1 = (long) Math.floor((cx * scale + hw - .001) / SIZE);
        visibleY0 = (long) Math.floor((cy * scale - hh) / SIZE);
        visibleY1 = (long) Math.floor((cy * scale + hh - .001) / SIZE);
        reprioritize();
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        for (long y = visibleY0; y <= visibleY1; y++)
            for (long x = visibleX0; x <= visibleX1; x++) {
                Bitmap bitmap = cache.get(snapshot.tileKey(x, y, snapshot.hybrid));
                if (bitmap == null) {
                    visibleMisses++;
                    lastCoverage.append(
                            drawPrevious(canvas, snapshot, cx, cy, width, height, x, y, ox, oy)
                                    .intersect(visible));
                    continue;
                }
                visibleHits++;
                float left = (float) (x * SIZE - ox), top = (float) (y * SIZE - oy);
                int save = canvas.save();
                canvas.clipRect(left, top, left + SIZE, top + SIZE);
                canvas.drawBitmap(bitmap, left - GUARD, top - GUARD, p);
                canvas.restoreToCount(save);
                RectF covered = new RectF(left, top, left + SIZE, top + SIZE);
                if (covered.intersect(visible)) lastCoverage.append(covered);
            }
        return lastCoverage;
    }

    private static float x(double world, double cx, double scale, double offset) {
        double dx = world - cx;
        if (dx > 128) dx -= 256;
        if (dx < -128) dx += 256;
        return (float) (dx * scale + offset);
    }

    static Bitmap render(Snapshot s, long tx, long ty) {
        Bitmap bitmap =
                Bitmap.createBitmap(SIZE + 2 * GUARD, SIZE + 2 * GUARD, Bitmap.Config.ARGB_8888);
        bitmap.setDensity(Bitmap.DENSITY_NONE);
        Canvas canvas = new Canvas(bitmap);
        Paint p = new Paint(3);
        double scale = 1 << s.zoom;
        double cx = (tx * SIZE + SIZE / 2.0) / scale;
        double cy = (ty * SIZE + SIZE / 2.0) / scale;
        double center = SIZE / 2.0 + GUARD;
        for (Layer layer : s.layers) {
            float left = x(layer.left, cx, scale, center),
                    right = x(layer.right, cx, scale, center);
            float top = (float) ((layer.top - cy) * scale + center);
            float bottom = (float) ((layer.bottom - cy) * scale + center);
            if (right <= left) continue;
            int save = canvas.save();
            canvas.clipRect(left, top, right, bottom);
            p.setStyle(Paint.Style.FILL);
            if (!s.hybrid && !layer.buildingsOnly && !s.illustrated) {
                p.setColor(0xffc6c6b0);
                canvas.drawRect(left, top, right, bottom, p);
            }
            for (Feature f : layer.features) {
                float l = x(f.left, cx, scale, center), r = x(f.right, cx, scale, center);
                float t = (float) ((f.top - cy) * scale + center);
                float b = (float) ((f.bottom - cy) * scale + center);
                if (r < 0 || l > bitmap.getWidth() || b < 0 || t > bitmap.getHeight()) continue;
                boolean road = f.pass == 2;
                if (road && !MapDetail.roadVisible(f.kind, s.zoom)) continue;
                if (f.pass == 1 && !MapDetail.buildingVisible(s.zoom, r - l, b - t)) continue;
                Path path = new Path();
                int stride = Math.max(2, 2 * (int) Math.ceil(f.xy.length / 8192.0));
                float px = Float.NaN, py = Float.NaN;
                for (int i = 0; i < f.xy.length; i = Math.min(f.xy.length, i + stride)) {
                    float xx = x(f.xy[i], cx, scale, center);
                    float yy = (float) ((f.xy[i + 1] - cy) * scale + center);
                    if (i == 0) {
                        path.moveTo(xx, yy);
                        px = xx;
                        py = yy;
                    } else if (i == f.xy.length - 2
                            || Math.abs(xx - px) + Math.abs(yy - py) >= 1.5f) {
                        path.lineTo(xx, yy);
                        px = xx;
                        py = yy;
                    }
                }
                if (f.area) path.close();
                if (!road && f.area && (!s.hybrid || s.illustrated)) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(
                            s.illustrated
                                    ? ("water".equals(f.type)
                                            ? (s.hybrid ? 0xff78aeb7 : 0xff9cabb1)
                                            : "building".equals(f.type)
                                                    ? (s.hybrid ? 0xffe5d8b6 : 0xffb9b7a2)
                                                    : (s.hybrid ? 0xffa6bf89 : 0xffbebfa8))
                                    : ("water".equals(f.type)
                                            ? 0xffa9b6ba
                                            : "building".equals(f.type) ? 0xffa3a8a5 : 0xffbfc6be));
                    canvas.drawPath(path, p);
                }
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeJoin(Paint.Join.ROUND);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeWidth(
                        Math.round(s.density) * (road ? MapDetail.roadWidth(s.zoom) : .6f));
                p.setColor(
                        s.illustrated
                                ? (road ? 0xfff5e9c9 : 0xff8b9c7b)
                                : (s.hybrid
                                        ? (road ? 0xfff4e9a4 : 0xddd9e0d4)
                                        : (road ? 0xfff5f5ee : 0xff7d8982)));
                canvas.drawPath(path, p);
            }
            canvas.restoreToCount(save);
        }
        return bitmap;
    }

    void drawLabels(
            Canvas canvas,
            Snapshot s,
            double cx,
            double cy,
            int width,
            int height,
            float fraction) {
        lastLabelExamined = lastLabelCandidates = lastLabels = 0;
        if (s.zoom < 16) return;
        Paint p = new Paint(3);
        p.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        p.setTextSize(11 * s.density);
        p.setColor(s.hybrid ? Color.WHITE : 0xff33463c);
        HashSet<String> names = new HashSet<>();
        ArrayList<RectF> boxes = new ArrayList<>();
        double scale = 1 << s.zoom;
        double halfWidth = width / (2.0 * fraction), halfHeight = height / (2.0 * fraction);
        double left = width / 2.0 - halfWidth, top = height / 2.0 - halfHeight;
        double right = width / 2.0 + halfWidth, bottom = height / 2.0 + halfHeight;
        for (Layer layer : s.layers) {
            if (boxes.size() >= 24) return;
            lastLabelExamined +=
                    layer.labels.visit(
                            cx,
                            cy,
                            halfWidth / scale,
                            halfHeight / scale,
                            s.zoom,
                            f -> {
                                lastLabelCandidates++;
                                if (f.pass != 2
                                        || f.name.isEmpty()
                                        || names.contains(f.name)
                                        || boxes.size() >= 24
                                        || !MapDetail.roadVisible(f.kind, s.zoom)) return true;
                                float xx = x(f.xy[0], cx, scale, width / 2.0);
                                float yy = (float) ((f.xy[1] - cy) * scale + height / 2.0);
                                if (xx <= left
                                        || xx >= right - 90 * s.density
                                        || yy <= top + 20 * s.density
                                        || yy >= bottom - 45 * s.density) return true;
                                String label =
                                        f.name.length() > 48 ? f.name.substring(0, 48) : f.name;
                                RectF box =
                                        new RectF(
                                                xx,
                                                yy - 18 * s.density,
                                                xx + p.measureText(label) + 12 * s.density,
                                                yy + 8 * s.density);
                                boolean overlaps = false;
                                for (RectF other : boxes)
                                    if (RectF.intersects(box, other)) {
                                        overlaps = true;
                                        break;
                                    }
                                if (overlaps) return true;
                                names.add(f.name);
                                boxes.add(box);
                                lastLabels = boxes.size();
                                int save = canvas.save();
                                canvas.clipRect(
                                        x(layer.left, cx, scale, width / 2.0),
                                        (float) ((layer.top - cy) * scale + height / 2.0),
                                        x(layer.right, cx, scale, width / 2.0),
                                        (float) ((layer.bottom - cy) * scale + height / 2.0));
                                canvas.drawText(label, xx + 3 * s.density, yy - 3 * s.density, p);
                                canvas.restoreToCount(save);
                                return boxes.size() < 24;
                            });
        }
    }
}

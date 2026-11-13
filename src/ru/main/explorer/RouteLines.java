package ru.main.explorer;

import java.util.ArrayList;
import java.util.concurrent.*;

/** Независимая от стен/маски фоновая линия принятых точек; один ready и один latest. */
final class RouteLines {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable changed;
    private RouteFrame ready, wanted, latest;
    private boolean running, closed;
    volatile int completed, cancelled;

    RouteLines(Runnable changed) {
        this.changed = changed;
    }

    synchronized void request(
            double cx,
            double cy,
            int zoom,
            int width,
            int height,
            float density,
            long revision,
            ArrayList<double[]> points) {
        if (closed || width <= 0 || height <= 0 || points.isEmpty()) {
            MapTrace.record(
                    MapTrace.Lane.LINE, MapTrace.Event.STATE, 0, revision, zoom, closed ? 6 : 4);
            return;
        }
        if (ready != null
                && ready.revision == revision
                && ready.zoom == zoom
                && ready.width == width
                && ready.height == height
                && ready.lineDensity == density
                && Math.abs(wrapped(ready.cx - cx)) * (1 << zoom) < width / 4.0
                && Math.abs(ready.cy - cy) * (1 << zoom) < height / 4.0) {
            MapTrace.record(
                    MapTrace.Lane.LINE,
                    MapTrace.Event.STATE,
                    MapTrace.id(ready),
                    revision,
                    zoom,
                    3);
            return;
        }
        if (wanted != null
                && wanted.revision == revision
                && wanted.zoom == zoom
                && wanted.width == width
                && wanted.height == height
                && wanted.lineDensity == density
                && wanted.cx == cx
                && wanted.cy == cy) {
            MapTrace.record(
                    MapTrace.Lane.LINE,
                    MapTrace.Event.STATE,
                    MapTrace.id(wanted),
                    revision,
                    zoom,
                    2);
            return;
        }
        RouteFrame frame =
                new RouteFrame(cx, cy, zoom, width, height, revision, points, null, null);
        frame.raster = true;
        frame.lineDensity = density;
        frame.cancelled = () -> obsolete(frame);
        cancelPending(5);
        wanted = latest = frame;
        MapTrace.record(
                MapTrace.Lane.LINE,
                MapTrace.Event.QUEUE,
                MapTrace.id(frame),
                revision,
                zoom,
                points.size());
        if (!running) {
            running = true;
            worker.execute(this::drain);
        }
    }

    private static double wrapped(double dx) {
        if (dx > 128) dx -= 256;
        if (dx < -128) dx += 256;
        return dx;
    }

    private synchronized boolean obsolete(RouteFrame frame) {
        return closed
                || wanted == null
                || wanted.revision != frame.revision
                || wanted.zoom != frame.zoom
                || wanted.width != frame.width
                || wanted.height != frame.height
                || Math.abs(wrapped(wanted.cx - frame.cx)) * (1 << frame.zoom) > frame.width / 2.0
                || Math.abs(wanted.cy - frame.cy) * (1 << frame.zoom) > frame.height / 2.0;
    }

    private void drain() {
        while (true) {
            RouteFrame frame;
            synchronized (this) {
                frame = latest;
                latest = null;
                if (frame == null) {
                    running = false;
                    notifyAll();
                    return;
                }
            }
            try {
                long start = System.nanoTime();
                MapTrace.record(
                        MapTrace.Lane.LINE,
                        MapTrace.Event.START,
                        MapTrace.id(frame),
                        frame.revision,
                        frame.zoom,
                        0);
                frame.buildLineOnly();
                synchronized (this) {
                    if (!frame.complete || obsolete(frame)) {
                        cancelled++;
                        MapTrace.record(
                                MapTrace.Lane.LINE,
                                MapTrace.Event.CANCEL,
                                MapTrace.id(frame),
                                frame.revision,
                                frame.zoom,
                                closed || wanted == null
                                        ? 1
                                        : wanted.revision != frame.revision
                                                ? 2
                                                : wanted.zoom != frame.zoom ? 3 : 4);
                        if (wanted == frame) wanted = null;
                        continue;
                    }
                    ready = frame;
                    completed++;
                    MapTrace.record(
                            MapTrace.Lane.LINE,
                            MapTrace.Event.PUBLISH,
                            MapTrace.id(frame),
                            frame.revision,
                            frame.zoom,
                            System.nanoTime() - start);
                    if (changed != null && !closed) changed.run();
                }
            } catch (OutOfMemoryError ignored) {
                MapTrace.record(
                        MapTrace.Lane.LINE,
                        MapTrace.Event.OOM,
                        MapTrace.id(frame),
                        frame.width,
                        frame.height,
                        0);
                synchronized (this) {
                    cancelled++;
                    if (wanted == frame) wanted = null;
                }
            }
        }
    }

    synchronized RouteFrame ready(long revision) {
        return ready != null && ready.revision == revision ? ready : null;
    }

    synchronized void clear() {
        cancelPending(1);
        MapTrace.record(MapTrace.Lane.LINE, MapTrace.Event.STATE, 0, 0, 0, closed ? 6 : 5);
        ready = wanted = latest = null;
    }

    private void cancelPending(int reason) {
        if (latest == null) return;
        cancelled++;
        MapTrace.record(
                MapTrace.Lane.LINE,
                MapTrace.Event.CANCEL,
                MapTrace.id(latest),
                latest.revision,
                latest.zoom,
                reason);
        latest = null;
    }

    synchronized void close() {
        closed = true;
        clear();
        worker.shutdown();
    }

    synchronized void awaitIdle() throws InterruptedException {
        long end = System.currentTimeMillis() + 30000;
        while (running && System.currentTimeMillis() < end) wait(50);
        if (running) throw new IllegalStateException("Route line worker timeout");
    }
}

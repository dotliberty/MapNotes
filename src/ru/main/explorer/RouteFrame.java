package ru.main.explorer;

import android.graphics.*;
import java.util.*;

/** Immutable input/output for the single background visibility worker. No View access. */
final class RouteFrame {
    final double cx, cy;
    final int zoom, width, height;
    final long revision;
    ArrayList<double[]> points;
    final double[] current;
    OfflineAtlas atlas;
    WorldMap world;
    RevealCache diskCache;
    boolean complete, raster;
    float lineDensity = 1;
    Bitmap mask, lineImage;
    private Canvas maskCanvas, lineCanvas;
    private final Paint rasterPaint = new Paint(3);
    private float rasterScale;
    java.util.function.Consumer<RouteFrame> progress;
    private long lastProgress;
    java.util.function.BooleanSupplier cancelled = () -> false;
    final Path reveal = new Path(), line = new Path();
    int samples, vertices, maskVertices;
    android.util.LruCache<String, double[]> cache;
    private final Set<String> seen = new HashSet<>();

    RouteFrame(
            double cx,
            double cy,
            int zoom,
            int width,
            int height,
            long revision,
            ArrayList<double[]> points,
            double[] current,
            OfflineAtlas atlas) {
        this.cx = cx;
        this.cy = cy;
        this.zoom = zoom;
        this.width = width;
        this.height = height;
        this.revision = revision;
        this.points = points;
        this.current = current;
        this.atlas = atlas;
    }

    float sx(double lon) {
        double dx = MapView.x(lon) - cx;
        if (dx > 128) dx -= 256;
        if (dx < -128) dx += 256;
        return (float) (dx * (1 << zoom) + width / 2.0);
    }

    float sy(double lat) {
        return (float) ((MapView.y(lat) - cy) * (1 << zoom) + height / 2.0);
    }

    void build() {
        if (diskCache != null) diskCache.begin();
        try {
            if (raster) {
                rasterScale =
                        (float)
                                Math.min(
                                        1,
                                        Math.sqrt(262144.0 / Math.max(1, (double) width * height)));
                int w = Math.max(1, Math.round(width * rasterScale)),
                        h = Math.max(1, Math.round(height * rasterScale));
                mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                lineImage = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                maskCanvas = new Canvas(mask);
                lineCanvas = new Canvas(lineImage);
                maskCanvas.scale(rasterScale, rasterScale);
                lineCanvas.scale(rasterScale, rasterScale);
                lastProgress = System.nanoTime();
            }
            if (current != null) add(current[0], current[1]);
            double[] previous = null;
            for (double[] p : points) {
                if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) return;
                float px = sx(p[1]), py = sy(p[0]);
                boolean connected = previous != null && MapView.connects(previous, p);
                if (connected) {
                    float ax = sx(previous[1]), ay = sy(previous[0]);
                    if (Math.max(ax, px) >= -8
                            && Math.min(ax, px) <= width + 8
                            && Math.max(ay, py) >= -8
                            && Math.min(ay, py) <= height + 8
                            && (raster || vertices < 20000)) {
                        if (raster) {
                            rasterPaint.setStrokeCap(Paint.Cap.ROUND);
                            rasterPaint.setStrokeWidth(5 * lineDensity);
                            rasterPaint.setColor(0xfff7efd9);
                            lineCanvas.drawLine(ax, ay, px, py, rasterPaint);
                            rasterPaint.setStrokeWidth(2 * lineDensity);
                            rasterPaint.setColor(0xffb09a69);
                            lineCanvas.drawLine(ax, ay, px, py, rasterPaint);
                        } else {
                            line.moveTo(ax, ay);
                            line.lineTo(px, py);
                        }
                        vertices += 2;
                    }
                }
                add(p[0], p[1]);
                if (connected && (raster || samples < 1024)) {
                    double distance = GpsFilter.distance(previous[0], previous[1], p[0], p[1]);
                    int steps = (int) Math.ceil(distance / 10);
                    for (int i = 1; i < steps; i++) {
                        double t = (double) i / steps;
                        add(
                                previous[0] + (p[0] - previous[0]) * t,
                                previous[1] + (p[1] - previous[1]) * t);
                    }
                }
                if (raster && progress != null && System.nanoTime() - lastProgress > 300000000L) {
                    lastProgress = System.nanoTime();
                    RouteFrame preview =
                            new RouteFrame(
                                    cx,
                                    cy,
                                    zoom,
                                    width,
                                    height,
                                    revision,
                                    new ArrayList<>(),
                                    null,
                                    null);
                    preview.raster = true;
                    preview.rasterScale = rasterScale;
                    preview.mask = mask.copy(Bitmap.Config.ARGB_8888, false);
                    preview.lineImage = lineImage.copy(Bitmap.Config.ARGB_8888, false);
                    progress.accept(preview);
                }
                previous = p;
            }
            complete = true;
            points = new ArrayList<>();
            atlas = null;
            world = null;
            cache = null;
        } finally {
            if (diskCache != null) diskCache.end();
            diskCache = null;
        }
    }

    void add(double lat, double lon) {
        if (cancelled.getAsBoolean() || !raster && (samples >= 1024 || maskVertices >= 100000))
            return;
        double mpp = 156543.03392 * Math.cos(Math.toRadians(lat)) / (1 << zoom);
        float px = sx(lon), py = sy(lat), r = (float) (Visibility.RADIUS / mpp);
        if (px + r < 0 || px - r > width || py + r < 0 || py - r > height) return;
        double cell = Math.max(1, Math.min(5 / mpp, 4));
        String key = Math.round(px / cell) + ":" + Math.round(py / cell);
        if (!seen.add(key)) return;
        samples++;
        String shapeKey =
                "v3:"
                        + (world != null
                                ? world.stamp
                                : atlas == null
                                        ? "0"
                                        : atlas.visibilityStamp(lat, lon, Visibility.RADIUS))
                        + ":"
                        + lat
                        + ":"
                        + lon;
        double[] shape = cache == null ? null : cache.get(shapeKey);
        if (shape == null && diskCache != null) shape = diskCache.read(shapeKey);
        if (shape == null) {
            List<double[]> walls =
                    world != null && world.vector
                            ? world.buildings(lat, lon, Visibility.RADIUS)
                            : atlas == null
                                    ? Collections.emptyList()
                                    : atlas.buildingsNear(lat, lon, Visibility.RADIUS);
            if (cancelled.getAsBoolean()) return;
            shape = Visibility.polygon(lat, lon, Visibility.RADIUS, walls);
            if (cancelled.getAsBoolean()) return;
            if (diskCache != null) diskCache.write(shapeKey, shape);
        }
        if (cache != null) cache.put(shapeKey, shape);
        if (shape.length == 0 || !raster && maskVertices + shape.length / 2 > 100000) return;
        maskVertices += shape.length / 2;
        Path area = new Path();
        for (int i = 0; i < shape.length; i += 2) {
            float x = px + (float) (shape[i] / mpp), y = py - (float) (shape[i + 1] / mpp);
            if (i == 0) area.moveTo(x, y);
            else area.lineTo(x, y);
        }
        area.close();
        if (raster) {
            rasterPaint.setColor(Color.WHITE);
            rasterPaint.setStyle(Paint.Style.FILL);
            maskCanvas.drawPath(area, rasterPaint);
        } else reveal.addPath(area);
    }

    void drawImage(
            Canvas canvas,
            Bitmap image,
            double targetX,
            double targetY,
            int targetZoom,
            int targetWidth,
            int targetHeight,
            Paint paint) {
        float scale = (float) Math.pow(2, targetZoom - zoom);
        Matrix m = new Matrix();
        m.setScale(scale / rasterScale, scale / rasterScale);
        double dx = cx - targetX;
        if (dx > 128) dx -= 256;
        if (dx < -128) dx += 256;
        m.postTranslate(
                (float) (dx * (1 << targetZoom) + targetWidth / 2.0 - width * scale / 2.0),
                (float)
                        ((cy - targetY) * (1 << targetZoom)
                                + targetHeight / 2.0
                                - height * scale / 2.0));
        canvas.drawBitmap(image, m, paint);
    }

    Path project(
            Path path,
            double targetX,
            double targetY,
            int targetZoom,
            int targetWidth,
            int targetHeight) {
        float scale = (float) Math.pow(2, targetZoom - zoom);
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        double dx = cx - targetX;
        if (dx > 128) dx -= 256;
        if (dx < -128) dx += 256;
        m.postTranslate(
                (float) (dx * (1 << targetZoom) + targetWidth / 2.0 - width * scale / 2.0),
                (float)
                        ((cy - targetY) * (1 << targetZoom)
                                + targetHeight / 2.0
                                - height * scale / 2.0));
        Path result = new Path();
        path.transform(m, result);
        return result;
    }
}

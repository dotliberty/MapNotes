package ru.main.explorer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import java.util.LinkedHashMap;
import java.util.Map;

/** UI-owned exact-viewport cache. It never contains the exploration mask or user overlays. */
final class AtlasRasterCache {
    static final int MAX_BYTES = 24 * 1024 * 1024;
    private final LinkedHashMap<String, Bitmap> images = new LinkedHashMap<>(4, .75f, true);
    private int bytes;
    int hits;

    boolean draw(Canvas canvas, String key) {
        Bitmap image = images.get(key);
        if (image == null) return false;
        canvas.drawBitmap(image, 0, 0, null);
        hits++;
        return true;
    }

    Bitmap allocate(int width, int height) {
        if (width <= 0 || height <= 0 || (long) width * height * 4 > MAX_BYTES) return null;
        // Drop least-recently-used references before allocating, not after a memory spike.
        long requested = (long) width * height * 4;
        while (bytes + requested > MAX_BYTES && !images.isEmpty()) {
            Map.Entry<String, Bitmap> oldest = images.entrySet().iterator().next();
            bytes -= oldest.getValue().getAllocationByteCount();
            images.remove(oldest.getKey());
        }
        try {
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError exhausted) {
            return null;
        }
    }

    void put(String key, Bitmap image) {
        int size = image.getAllocationByteCount();
        if (size > MAX_BYTES) return;
        Bitmap previous = images.remove(key);
        if (previous != null) bytes -= previous.getAllocationByteCount();
        while (bytes + size > MAX_BYTES && !images.isEmpty()) {
            Map.Entry<String, Bitmap> oldest = images.entrySet().iterator().next();
            bytes -= oldest.getValue().getAllocationByteCount();
            images.remove(oldest.getKey());
        }
        images.put(key, image);
        bytes += size;
    }

    void clear() {
        // Do not recycle: an already submitted hardware display list may still reference a bitmap.
        images.clear();
        bytes = 0;
    }

    int bytes() {
        return bytes;
    }
}

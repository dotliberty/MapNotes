package ru.main.explorer;

import android.graphics.RectF;
import java.util.*;

/** Проверка происхождения готовых пикселей; редакции pack совместимы только в одной ревизии. */
final class AtlasReplay {
    static final class Input {
        final String key, family;
        final double left, right, top, bottom;
        final long token;

        Input(AtlasTiles.Layer layer) {
            key = layer.key;
            family = layer.replayFamily;
            left = layer.left;
            right = layer.right;
            top = layer.top;
            bottom = layer.bottom;
            token = MapTrace.id(layer);
        }

        RectF screen(double cx, double cy, int zoom, int width, int height) {
            double scale = 1 << zoom, center = (left + right) / 2 - cx;
            center -= Math.floor((center + 128) / 256) * 256;
            double half = (right - left) / 2;
            return new RectF(
                    (float) ((center - half) * scale + width / 2.0),
                    (float) ((top - cy) * scale + height / 2.0),
                    (float) ((center + half) * scale + width / 2.0),
                    (float) ((bottom - cy) * scale + height / 2.0));
        }
    }

    static ArrayList<Input> inputs(List<AtlasTiles.Layer> layers) {
        ArrayList<Input> result = new ArrayList<>();
        for (AtlasTiles.Layer layer : layers) result.add(new Input(layer));
        return result;
    }

    private static LinkedHashMap<String, ArrayList<Input>> groups(
            List<Input> inputs, boolean alternatives) {
        LinkedHashMap<String, ArrayList<Input>> result = new LinkedHashMap<>();
        String previous = null, previousUnique = null;
        for (Input input : inputs) {
            boolean family = alternatives && input.family != null;
            String key = family ? input.family : input.key;
            // Только соседние члены pack схлопываются: взаимный порядок независимых слоёв важен.
            if (family && key.equals(previous)) {
                result.get(previousUnique).add(input);
                continue;
            }
            String unique = key;
            for (int occurrence = 1; result.containsKey(unique); occurrence++)
                unique = key + "\u0000" + occurrence;
            result.put(unique, new ArrayList<>(Collections.singletonList(input)));
            previous = key;
            previousUnique = unique;
        }
        return result;
    }

    static AtlasCoverage restrict(
            AtlasCoverage result,
            List<Input> old,
            List<Input> selected,
            double cx,
            double cy,
            int zoom,
            int width,
            int height,
            boolean alternatives) {
        LinkedHashMap<String, ArrayList<Input>> before = groups(old, alternatives),
                after = groups(selected, alternatives);
        ArrayList<String> oldOrder = new ArrayList<>(), newOrder = new ArrayList<>();
        for (String key : before.keySet()) if (after.containsKey(key)) oldOrder.add(key);
        for (String key : after.keySet()) if (before.containsKey(key)) newOrder.add(key);
        boolean reordered = !oldOrder.equals(newOrder);
        for (Map.Entry<String, ArrayList<Input>> entry : before.entrySet())
            if (reordered || !after.containsKey(entry.getKey()))
                remove(result, entry.getValue(), cx, cy, zoom, width, height, reordered ? 3 : 1);
        for (Map.Entry<String, ArrayList<Input>> entry : after.entrySet()) {
            ArrayList<Input> original = before.get(entry.getKey());
            if (original == null) remove(result, entry.getValue(), cx, cy, zoom, width, height, 2);
            else if (!reordered && alternatives && entry.getValue().get(0).family != null) {
                // Новый detail не подтверждает пиксели за пределами старых cells, и наоборот.
                subtractDifference(result, original, entry.getValue(), cx, cy, zoom, width, height);
                subtractDifference(result, entry.getValue(), original, cx, cy, zoom, width, height);
            }
        }
        return result;
    }

    private static void remove(
            AtlasCoverage result,
            List<Input> inputs,
            double cx,
            double cy,
            int zoom,
            int width,
            int height,
            int reason) {
        for (Input input : inputs) {
            RectF bounds = input.screen(cx, cy, zoom, width, height);
            bounds.inset(-1, -1);
            result.subtract(bounds);
            MapTrace.record(
                    MapTrace.Lane.SOURCE,
                    MapTrace.Event.INVALIDATE,
                    input.token,
                    reason,
                    input.family == null ? 0 : 1,
                    zoom);
        }
    }

    private static void subtractDifference(
            AtlasCoverage result,
            List<Input> from,
            List<Input> covered,
            double cx,
            double cy,
            int zoom,
            int width,
            int height) {
        for (Input input : from) {
            AtlasCoverage difference = new AtlasCoverage();
            difference.append(input.screen(cx, cy, zoom, width, height));
            for (Input other : covered)
                difference.subtract(other.screen(cx, cy, zoom, width, height));
            for (RectF rectangle : difference.rectangles) result.subtract(rectangle);
        }
    }
}

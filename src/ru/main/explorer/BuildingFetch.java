package ru.main.explorer;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;

/** Small OSM building-only area; satellite downloads remain independent. */
final class BuildingFetch {
    static void fetch(Context context, double lat, double lon) throws Exception {
        fetch(context, lat, lon, new java.util.HashSet<>(), () -> true);
    }

    static void fetch(
            Context context,
            double lat,
            double lon,
            java.util.Set<HttpURLConnection> connections,
            java.util.function.BooleanSupplier allowed)
            throws Exception {
        final long generation = DownloadedMaps.generation();
        java.util.function.BooleanSupplier current =
                () -> allowed.getAsBoolean() && DownloadedMaps.current(generation);
        Overpass.fetch(
                endpoint -> {
                    if (!current.getAsBoolean()) throw new MapPack.PausedException();
                    fetchAt(context, lat, lon, endpoint, connections, current);
                    return null;
                });
    }

    static void fetchAt(
            Context context,
            double lat,
            double lon,
            String endpoint,
            java.util.Set<HttpURLConnection> connections,
            java.util.function.BooleanSupplier allowed)
            throws Exception {
        long generation = DownloadedMaps.generation();
        if (!allowed.getAsBoolean() || !DownloadedMaps.current(generation))
            throw new MapPack.PausedException();
        RegionPlan plan = new RegionPlan(lat, lon, 250);
        String query =
                "[out:json][timeout:25];(way[building]("
                        + plan.bbox()
                        + ");way[highway]("
                        + plan.bbox()
                        + ");way[waterway]("
                        + plan.bbox()
                        + ");way[natural=water]("
                        + plan.bbox()
                        + ");way[landuse]("
                        + plan.bbox()
                        + "););out tags geom;";
        HttpURLConnection h = (HttpURLConnection) new URL(endpoint).openConnection();
        connections.add(h);
        MapNetwork.configure(h);
        h.setConnectTimeout(12000);
        h.setReadTimeout(35000);
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setRequestProperty("User-Agent", "MapNotes/0.11 (Android; ru.main.explorer)");
        h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        try {
            MapNetwork.register(context, h);
            byte[] request = ("data=" + URLEncoder.encode(query, "UTF-8")).getBytes("UTF-8");
            h.setFixedLengthStreamingMode(request.length);
            try (OutputStream out = h.getOutputStream()) {
                out.write(request);
            }
            if (h.getResponseCode() != 200) throw new IOException("HTTP " + h.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) {
                byte[] b = new byte[32768];
                int n;
                while ((n = in.read(b)) != -1) {
                    if (!allowed.getAsBoolean())
                        throw new IOException(Language.text(context, R.string.network_paused));
                    out.write(b, 0, n);
                    if (out.size() > 3000000)
                        throw new IOException(Language.text(context, R.string.too_many_buildings));
                }
            }
            if (!allowed.getAsBoolean())
                throw new IOException(Language.text(context, R.string.network_paused));
            JSONObject root = new JSONObject(out.toString("UTF-8"));
            if (root.has("remark"))
                throw new IOException(Language.text(context, R.string.incomplete_building_data));
            root.getJSONArray("elements");
            root.put(
                    "mapnotes_bounds",
                    new JSONArray(new double[] {plan.south, plan.west, plan.north, plan.east}));
            root.put("mapnotes_buildings_only", false);
            File dir = new File(context.getFilesDir(), "visibility");
            File file = new File(dir, System.currentTimeMillis() + ".json"),
                    temp = new File(dir, "buildings.part");
            DownloadedMaps.write(
                    generation,
                    () -> {
                        if (!allowed.getAsBoolean())
                            throw new IOException(Language.text(context, R.string.network_paused));
                        dir.mkdirs();
                        try (FileOutputStream fileOut = new FileOutputStream(temp)) {
                            fileOut.write(root.toString().getBytes("UTF-8"));
                        }
                        if (!temp.renameTo(file))
                            throw new IOException(
                                    Language.text(context, R.string.could_not_save_buildings));
                    });
        } finally {
            connections.remove(h);
            MapNetwork.finish(h);
        }
    }
}

package ru.main.explorer;

import org.json.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** A resumable 100 km pack. Only one vector cell is decoded at a time. */
public final class MapPack {
    public static final String ENDPOINT = "https://overpass-api.de/api/interpreter";

    public static class PausedException extends IOException {
        public PausedException() {
            super(Messages.text("map_loading_paused"));
        }
    }

    public static class QueryFailure extends IOException {
        public QueryFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class PermanentException extends IOException {
        public PermanentException(String message) {
            super(message);
        }
    }

    public static class TooLargeException extends IOException {
        public TooLargeException() {
            super(Messages.text("section_too_large"));
        }
    }

    public static class RateLimitException extends IOException {
        public RateLimitException() {
            super(Messages.text("server_busy_waiting_30_seconds"));
        }
    }

    public interface Transfer {
        byte[] fetch(String url, String query, int limit) throws Exception;

        void check() throws IOException;

        void progress(String message, int done, int total);

        default void progressMessage(String key, int done, int total, Object... args) {
            progress(Messages.format(key, args), done, total);
        }

        void changed();

        void waitMillis(long millis) throws Exception;

        void validateImage(byte[] image) throws Exception;
    }

    private final File directory, satellite;
    private final RegionPlan plan;
    private final Transfer transfer;
    private final boolean includeSatellite;
    private int vectorDone;

    public MapPack(File files, RegionPlan plan, Transfer transfer) {
        this(files, plan, transfer, true);
    }

    public MapPack(File files, RegionPlan plan, Transfer transfer, boolean includeSatellite) {
        this.plan = plan;
        this.transfer = transfer;
        this.includeSatellite = includeSatellite;
        directory = new File(new File(files, "packs"), plan.key());
        satellite = new File(files, "satellite");
    }

    public void run() throws Exception {
        directory.mkdirs();
        satellite.mkdirs();
        File manifest = new File(directory, "pack.json");
        JSONObject info =
                new JSONObject()
                        .put("bounds", bounds(plan.south, plan.west, plan.north, plan.east))
                        .put("maxZoom", plan.maxZoom())
                        .put("complete", false)
                        .put("sideKm", Math.round((plan.north - plan.south) * 111195.08 / 1000));
        write(manifest, info.toString().getBytes("UTF-8"), false);
        List<RegionPlan.Cell> cells = plan.cells();
        for (int i = 0; i < cells.size(); i++) {
            transfer.check();
            transfer.progressMessage(
                    "map_section_progress", i, cells.size(), i + 1, cells.size(), vectorDone);
            vectorCell(cells.get(i));
        }
        buildOverview();
        transfer.changed();
        info.put("satellite", includeSatellite);
        List<int[]> tiles = includeSatellite ? plan.tiles() : Collections.emptyList();
        int done = 0;
        for (int[] tile : tiles) {
            transfer.check();
            File file = new File(satellite, RegionPlan.tileName(tile[0], tile[1], tile[2]));
            boolean valid = false;
            if (file.exists())
                try {
                    transfer.validateImage(read(file, 2000000));
                    valid = true;
                } catch (Exception invalid) {
                    file.delete();
                }
            if (!valid) {
                byte[] image =
                        retry(RegionPlan.satelliteUrl(tile[0], tile[1], tile[2]), null, 2000000);
                transfer.validateImage(image);
                write(file, image, false);
                transfer.waitMillis(180);
            }
            done++;
            if (done % 8 == 0 || done == tiles.size())
                transfer.progressMessage(
                        "satellite_progress",
                        done,
                        tiles.size(),
                        done,
                        tiles.size(),
                        info.getInt("sideKm"));
            if (done % 128 == 0) transfer.changed();
        }
        transfer.check();
        info.put("complete", true);
        write(manifest, info.toString().getBytes("UTF-8"), false);
        transfer.changed();
    }

    byte[] retry(String url, String body, int limit) throws Exception {
        return retry(url, body, limit, false);
    }

    byte[] retry(String url, String body, int limit, boolean subdivide) throws Exception {
        for (int attempt = 0; ; attempt++) {
            transfer.check();
            try {
                byte[] response = transfer.fetch(url, body, limit);
                if (body == null) transfer.validateImage(response);
                return response;
            } catch (TooLargeException e) {
                throw e;
            } catch (PermanentException e) {
                throw e;
            } catch (PausedException e) {
                transfer.waitMillis(250);
                attempt--;
                continue;
            } catch (IOException e) {
                transfer.check();
                if (subdivide
                        && e instanceof QueryFailure
                        && (attempt >= 1
                                || e.getMessage() != null
                                        && (e.getMessage().contains("502")
                                                || e.getMessage().contains("504"))))
                    throw new TooLargeException();
                transfer.progressMessage(
                        "retry_progress",
                        0,
                        0,
                        attempt + 1,
                        e.getMessage() == null ? Messages.text("network_error") : e.getMessage());
                transfer.waitMillis(
                        e instanceof RateLimitException
                                ? 60000
                                : attempt == 0 ? 5000 : Math.min(120000, 15000L * (attempt + 1)));
            }
        }
    }

    private void vectorCell(RegionPlan.Cell cell) throws Exception {
        transfer.check();
        File data = new File(directory, cell.id + ".json.gz"),
                meta = new File(directory, cell.id + ".meta"),
                split = new File(directory, cell.id + ".split");
        if (data.exists() && meta.exists()) {
            try {
                new JSONObject(new String(read(data, 16000000), "UTF-8")).getJSONArray("elements");
                new JSONObject(new String(read(meta, 65536), "UTF-8")).getJSONArray("bounds");
                vectorDone++;
                return;
            } catch (Exception corrupt) {
                data.delete();
                meta.delete();
            }
        }
        if (split.exists()) {
            for (RegionPlan.Cell child : cell.split()) vectorCell(child);
            return;
        }
        try {
            JSONObject root;
            if (data.exists()) root = new JSONObject(new String(read(data, 16000000), "UTF-8"));
            else {
                String bbox = cell.bbox();
                String query =
                        "[out:json][timeout:25][maxsize:67108864];(way[highway]("
                                + bbox
                                + ");way[building]("
                                + bbox
                                + ");way[waterway]("
                                + bbox
                                + ");way[natural=water]("
                                + bbox
                                + ");way[landuse]("
                                + bbox
                                + "););out tags geom;";
                root =
                        new JSONObject(
                                new String(
                                        retry(
                                                ENDPOINT,
                                                query,
                                                6000000,
                                                cell.depth < 4
                                                        && (cell.north - cell.south) * 111195.08
                                                                > 1250.01),
                                        "UTF-8"));
                if (root.has("remark")) throw new TooLargeException();
                root.getJSONArray("elements");
                root.put("mapnotes_bounds", bounds(cell.south, cell.west, cell.north, cell.east));
                write(data, root.toString().getBytes("UTF-8"), true);
                transfer.waitMillis(1000);
            }
            JSONObject index =
                    new JSONObject()
                            .put("bounds", bounds(cell.south, cell.west, cell.north, cell.east))
                            .put("file", data.getName());
            write(meta, index.toString().getBytes("UTF-8"), false);
            vectorDone++;
            if (vectorDone % 4 == 0) transfer.changed();
        } catch (TooLargeException e) {
            if (cell.depth >= 4)
                throw new IOException(
                        Messages.text(
                                "map_too_dense_completed_sections_are_saved_try_again_later"));
            write(split, new byte[] {1}, false);
            for (RegionPlan.Cell child : cell.split()) vectorCell(child);
        }
    }

    private void buildOverview() throws Exception {
        File target = new File(directory, "overview.json.gz");
        if (target.exists()) return;
        JSONArray ways = new JSONArray();
        HashSet<Long> seen = new HashSet<>();
        File[] cells = directory.listFiles((dir, name) -> name.endsWith(".meta"));
        if (cells == null) return;
        int points = 0;
        for (File index : cells) {
            transfer.check();
            JSONObject meta = new JSONObject(new String(read(index, 65536), "UTF-8"));
            JSONObject root =
                    new JSONObject(
                            new String(
                                    read(new File(directory, meta.getString("file")), 16000000),
                                    "UTF-8"));
            JSONArray elements = root.getJSONArray("elements");
            for (int i = 0; i < elements.length(); i++) {
                JSONObject element = elements.getJSONObject(i),
                        tags = element.optJSONObject("tags");
                JSONArray geometry = element.optJSONArray("geometry");
                if (tags == null || geometry == null || geometry.length() < 2) continue;
                String road = tags.optString("highway");
                if (!road.matches("motorway|trunk|primary|secondary|tertiary|.*_link")
                        || !seen.add(element.optLong("id", i))) continue;
                JSONArray simplified = new JSONArray();
                int step = Math.max(1, (int) Math.ceil(geometry.length() / 12.0));
                for (int j = 0; j < geometry.length(); j += step)
                    simplified.put(geometry.getJSONObject(j));
                simplified.put(geometry.getJSONObject(geometry.length() - 1));
                points += simplified.length();
                if (points > 150000) continue;
                ways.put(
                        new JSONObject()
                                .put(
                                        "tags",
                                        new JSONObject()
                                                .put("highway", road)
                                                .put("name", tags.optString("name")))
                                .put("geometry", simplified));
            }
        }
        JSONObject overview =
                new JSONObject()
                        .put(
                                "mapnotes_bounds",
                                bounds(plan.south, plan.west, plan.north, plan.east))
                        .put("mapnotes_overview", true)
                        .put("elements", ways);
        write(target, overview.toString().getBytes("UTF-8"), true);
    }

    public static JSONArray bounds(double south, double west, double north, double east)
            throws JSONException {
        return new JSONArray(new double[] {south, west, north, east});
    }

    public static void write(File file, byte[] bytes, boolean gzip) throws IOException {
        File temp = new File(file.getPath() + ".part");
        try (FileOutputStream raw = new FileOutputStream(temp)) {
            if (gzip) {
                GZIPOutputStream compressed = new GZIPOutputStream(raw);
                compressed.write(bytes);
                compressed.finish();
                compressed.flush();
            } else raw.write(bytes);
            raw.getFD().sync();
        }
        if (!temp.renameTo(file))
            throw new IOException(Messages.text("could_not_save") + file.getName());
    }

    public static byte[] read(File file, int limit) throws IOException {
        try (InputStream raw = new FileInputStream(file);
                InputStream in = file.getName().endsWith(".gz") ? new GZIPInputStream(raw) : raw;
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32768];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                if (out.size() > limit) throw new TooLargeException();
            }
            return out.toByteArray();
        }
    }
}

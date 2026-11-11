package ru.main.explorer;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.system.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Indexed read-only access to a planet MBTiles archive. Only visible tiles are decoded. */
final class WorldMap implements AutoCloseable {
    static final class InvalidMap extends IOException {
        InvalidMap(String message) {
            super(message);
        }
    }

    final SQLiteDatabase db;
    private ParcelFileDescriptor descriptor;
    final String name, format, stamp;
    final int minZoom, maxZoom;
    final boolean vector;
    private final boolean xyz;
    private volatile boolean closed;
    volatile String error = "";
    private final Runnable changed;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Set<String> pending = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> absent = Collections.synchronizedSet(new HashSet<>());
    private final android.util.LruCache<String, Mvt.Tile> geometry =
            new android.util.LruCache<String, Mvt.Tile>(CacheBudget.geometry()) {
                protected int sizeOf(String key, Mvt.Tile tile) {
                    return tile.bytes();
                }
            };
    private final android.util.LruCache<String, Bitmap> images =
            new android.util.LruCache<String, Bitmap>(CacheBudget.imagery()) {
                protected int sizeOf(String key, Bitmap bitmap) {
                    return bitmap.getAllocationByteCount();
                }
            };
    private final Paint paint = new Paint(3);
    private static final Typeface LABEL_FONT = Typeface.create("serif", Typeface.NORMAL);
    int tileReads;

    static WorldMap open(Context context, Uri uri, Runnable changed) throws Exception {
        Language.initialize(context);
        ParcelFileDescriptor fd = null;
        SQLiteDatabase database = null;
        try {
            String path;
            if ("file".equals(uri.getScheme())) path = uri.getPath();
            else {
                fd = context.getContentResolver().openFileDescriptor(uri, "r");
                if (fd == null) throw new IOException(Messages.text("file_unavailable"));
                try {
                    Os.lseek(fd.getFileDescriptor(), 0, OsConstants.SEEK_CUR);
                } catch (ErrnoException pipe) {
                    throw new IOException(
                            Messages.text(
                                    "a_local_file_is_required_save_mbtiles_to_your_phone_s_downloads_f"),
                            pipe);
                }
                path = "/proc/self/fd/" + fd.getFd();
            }
            byte[] header = new byte[16];
            if (fd == null) {
                try (DataInputStream in = new DataInputStream(new FileInputStream(path))) {
                    try {
                        in.readFully(header);
                    } catch (EOFException shortFile) {
                        throw new InvalidMap(
                                Messages.text(
                                        "a_ready_mbtiles_file_is_required_zip_pmtiles_and_raw_osm_pbf_are_"));
                    }
                }
            } else {
                if (Os.pread(fd.getFileDescriptor(), header, 0, header.length, 0) != header.length)
                    throw new InvalidMap(Messages.text("map_file_truncated"));
            }
            if (!Arrays.equals(header, "SQLite format 3\0".getBytes("US-ASCII")))
                throw new InvalidMap(
                        Messages.text(
                                "a_ready_mbtiles_file_is_required_zip_pmtiles_and_raw_osm_pbf_are_"));
            database =
                    SQLiteDatabase.openDatabase(
                            path,
                            null,
                            SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            WorldMap map =
                    new WorldMap(
                            database,
                            uri.toString()
                                    + ":"
                                    + context.getSharedPreferences("world-map", 0)
                                            .getLong("attached", 0)
                                    + ":"
                                    + (fd == null
                                            ? new File(path).lastModified()
                                                    + ":"
                                                    + new File(path).length()
                                            : fd.getStatSize()),
                            changed);
            map.descriptor = fd;
            return map;
        } catch (Exception e) {
            if (database != null) database.close();
            if (fd != null) fd.close();
            throw e;
        }
    }

    WorldMap(SQLiteDatabase database, String stamp, Runnable changed) throws Exception {
        db = database;
        this.stamp = stamp;
        this.changed = changed;
        HashMap<String, String> metadata = new HashMap<>();
        try (Cursor c = db.rawQuery("SELECT name,value FROM metadata LIMIT 256", null)) {
            while (c.moveToNext()) metadata.put(c.getString(0), c.getString(1));
        } catch (Exception missing) {
        }
        name = clean(metadata.get("name"), Messages.text("local_world_map"));
        format = clean(metadata.get("format"), "").toLowerCase(Locale.ROOT);
        xyz = "xyz".equalsIgnoreCase(metadata.get("scheme"));
        byte[] sample;
        try (Cursor c =
                db.rawQuery(
                        "SELECT zoom_level,tile_column,tile_row,length(tile_data) FROM tiles LIMIT 1",
                        null)) {
            if (!c.moveToFirst()) throw new IOException(Messages.text("file_contains_no_tiles"));
            sample = readBlob(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3));
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(sample, 0, sample.length, options);
        vector = options.outWidth < 1;
        if (vector) Mvt.decode(sample);
        try (Cursor plan =
                db.rawQuery(
                        "EXPLAIN QUERY PLAN SELECT tile_data FROM tiles WHERE zoom_level=14 AND tile_column=0 AND tile_row=0",
                        null)) {
            boolean indexed = false;
            while (plan.moveToNext()) {
                String detail = plan.getString(3).toUpperCase(Locale.ROOT);
                if (detail.contains("SEARCH")
                        && (detail.contains("INDEX") || detail.contains("PRIMARY KEY")))
                    indexed = true;
            }
            if (!indexed)
                throw new InvalidMap(
                        Messages.text(
                                "mbtiles_has_no_tile_index_this_file_would_be_too_slow_on_a_phone"));
        }
        minZoom = zoom(metadata.get("minzoom"), false);
        maxZoom = zoom(metadata.get("maxzoom"), true);
        if (minZoom < 0 || maxZoom > 22 || maxZoom < minZoom)
            throw new IOException(Messages.text("invalid_map_zoom_levels"));
    }

    int zoom(String value, boolean highest) throws Exception {
        if (value != null)
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
            }
        try (Cursor c =
                db.rawQuery(
                        "SELECT zoom_level FROM tiles ORDER BY zoom_level "
                                + (highest ? "DESC" : "ASC")
                                + " LIMIT 1",
                        null)) {
            if (!c.moveToFirst()) throw new IOException(Messages.text("empty_map"));
            return c.getInt(0);
        }
    }

    static String clean(String value, String fallback) {
        if (value == null || value.isEmpty()) return fallback;
        return value.length() > 160 ? value.substring(0, 160) : value;
    }

    int chooseZoom(int viewZoom) {
        return Math.max(minZoom, Math.min(maxZoom, viewZoom));
    }

    byte[] readBlob(int z, int x, int row, int length) throws IOException {
        if (length < 1 || length > 8 * 1024 * 1024)
            throw new IOException(Messages.text("invalid_tile_size"));
        byte[] result = new byte[length];
        for (int offset = 0; offset < length; ) {
            int amount = Math.min(512 * 1024, length - offset);
            try (Cursor c =
                    db.rawQuery(
                            "SELECT substr(tile_data,?,?) FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                            new String[] {
                                "" + (offset + 1), "" + amount, "" + z, "" + x, "" + row
                            })) {
                if (!c.moveToFirst()) throw new EOFException(Messages.text("tile_missing"));
                byte[] chunk = c.getBlob(0);
                if (chunk.length != amount) throw new EOFException(Messages.text("tile_truncated"));
                System.arraycopy(chunk, 0, result, offset, amount);
                offset += amount;
            }
        }
        return result;
    }

    synchronized byte[] bytes(int z, int x, int y) throws IOException {
        if (closed) return null;
        tileReads++;
        int row = xyz ? y : (1 << z) - 1 - y;
        try (Cursor c =
                db.rawQuery(
                        "SELECT length(tile_data) FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                        new String[] {"" + z, "" + x, "" + row})) {
            if (!c.moveToFirst()) return null;
            return readBlob(z, x, row, c.getInt(0));
        } catch (RuntimeException failure) {
            throw new IOException(Messages.text("could_not_read_local_map"), failure);
        }
    }

    Mvt.Tile tile(int z, int x, int y, boolean blocking) {
        String key = z + "/" + x + "/" + y;
        Mvt.Tile cached = geometry.get(key);
        if (cached != null || absent.contains(key) || closed) return cached;
        if (blocking) {
            try {
                return loadVector(key, z, x, y);
            } catch (Exception failure) {
                error = failure.getMessage();
                return null;
            }
        }
        queue(key, () -> loadVector(key, z, x, y));
        return null;
    }

    private synchronized Mvt.Tile loadVector(String key, int z, int x, int y) throws Exception {
        Mvt.Tile cached = geometry.get(key);
        if (cached != null) return cached;
        byte[] bytes = bytes(z, x, y);
        if (bytes == null) {
            rememberAbsent(key);
            return null;
        }
        Mvt.Tile tile = Mvt.decode(bytes);
        geometry.put(key, tile);
        return tile;
    }

    private void rememberAbsent(String key) {
        if (absent.size() > 2048) absent.clear();
        absent.add(key);
    }

    interface Load {
        void run() throws Exception;
    }

    private void queue(String key, Load action) {
        if (closed || pending.size() >= 16 || !pending.add(key)) return;
        try {
            loader.execute(
                    () -> {
                        try {
                            if (!closed) action.run();
                        } catch (Exception failure) {
                            error = failure.getMessage();
                            rememberAbsent(key);
                        } finally {
                            pending.remove(key);
                            if (!closed && changed != null) changed.run();
                        }
                    });
        } catch (RejectedExecutionException closing) {
            pending.remove(key);
        }
    }

    Bitmap image(int z, int x, int y) {
        String key = z + "/" + x + "/" + y;
        Bitmap bitmap = images.get(key);
        if (bitmap != null || absent.contains(key) || closed) return bitmap;
        queue(
                key,
                () -> {
                    byte[] bytes = bytes(z, x, y);
                    if (bytes == null) {
                        rememberAbsent(key);
                        return;
                    }
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                    if (bounds.outWidth > 2048 || bounds.outHeight > 2048)
                        throw new IOException(Messages.text("raster_image_too_large"));
                    Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (b == null) throw new IOException(Messages.text("invalid_raster_tile"));
                    images.put(key, b);
                });
        return null;
    }

    boolean covers(double lat, double lon) {
        int z = maxZoom,
                n = 1 << z,
                x = (int) Math.floor(RegionPlan.x(lon) * n),
                y = (int) Math.floor(RegionPlan.y(lat) * n);
        try {
            return bytes(z, Math.floorMod(x, n), Math.max(0, Math.min(n - 1, y))) != null;
        } catch (Exception unavailable) {
            return false;
        }
    }

    List<double[]> buildings(double lat, double lon, double radius) {
        ArrayList<double[]> result = new ArrayList<>();
        if (!vector || maxZoom < 12 || closed) return result;
        int z = Math.min(14, maxZoom), n = 1 << z;
        double dy = radius / 111195.08, dx = dy / Math.cos(Math.toRadians(lat));
        int left = (int) Math.floor(RegionPlan.x(lon - dx) * n),
                right = (int) Math.floor(RegionPlan.x(lon + dx) * n),
                top = (int) Math.floor(RegionPlan.y(lat + dy) * n),
                bottom = (int) Math.floor(RegionPlan.y(lat - dy) * n);
        for (int x = left; x <= right; x++)
            for (int y = Math.max(0, top); y <= Math.min(n - 1, bottom); y++) {
                Mvt.Tile tile = tile(z, Math.floorMod(x, n), y, true);
                if (tile == null) continue;
                for (Mvt.Feature f :
                        tile.nearBuildings(
                                RegionPlan.x(lon - dx) * n - x,
                                RegionPlan.y(lat + dy) * n - y,
                                RegionPlan.x(lon + dx) * n - x,
                                RegionPlan.y(lat - dy) * n - y))
                    if (f.type == 3 && isBuilding(f.layer)) {
                        for (float[] ring : f.parts) {
                            if (ring.length < 6) continue;
                            double[] coordinates = new double[ring.length + 2];
                            for (int i = 0; i < ring.length; i += 2) {
                                double wx = (x + ring[i] / f.extent) / n,
                                        wy = (y + ring[i + 1] / f.extent) / n;
                                coordinates[i] =
                                        Math.toDegrees(
                                                Math.atan(Math.sinh(Math.PI * (1 - 2 * wy))));
                                coordinates[i + 1] = wx * 360 - 180;
                            }
                            coordinates[coordinates.length - 2] = coordinates[0];
                            coordinates[coordinates.length - 1] = coordinates[1];
                            double south = 90, west = 180, north = -90, east = -180;
                            for (int i = 0; i < coordinates.length; i += 2) {
                                south = Math.min(south, coordinates[i]);
                                north = Math.max(north, coordinates[i]);
                                west = Math.min(west, coordinates[i + 1]);
                                east = Math.max(east, coordinates[i + 1]);
                            }
                            if (south <= lat + dy
                                    && north >= lat - dy
                                    && west <= lon + dx
                                    && east >= lon - dx) result.add(coordinates);
                        }
                    }
            }
        return result;
    }

    static boolean isBuilding(String layer) {
        return layer.equals("building") || layer.equals("buildings");
    }

    static boolean road(String layer) {
        return layer.equals("transportation") || layer.equals("roads") || layer.equals("road");
    }

    static int order(String layer) {
        if (layer.equals("landcover")
                || layer.equals("landuse")
                || layer.equals("park")
                || layer.equals("earth")) return 0;
        if (layer.equals("water") || layer.equals("waterway")) return 1;
        if (isBuilding(layer)) return 2;
        if (road(layer) || layer.equals("boundary") || layer.equals("boundaries")) return 3;
        return 4;
    }

    void draw(Canvas canvas, MapView map, boolean explored) {
        if (closed) return;
        int z = chooseZoom(map.zoom), n = 1 << z;
        double size = 256 * Math.pow(2, map.zoom - z),
                wx = map.cx * (1 << map.zoom),
                wy = map.cy * (1 << map.zoom);
        int left = (int) Math.floor((wx - map.getWidth() / 2.0) / size),
                right = (int) Math.floor((wx + map.getWidth() / 2.0) / size),
                top = (int) Math.floor((wy - map.getHeight() / 2.0) / size),
                bottom = (int) Math.floor((wy + map.getHeight() / 2.0) / size);
        if ((long) (right - left + 1) * (bottom - top + 1) > 128) return;
        ArrayList<RectF> labels = new ArrayList<>();
        HashSet<String> names = new HashSet<>();
        for (int x = left; x <= right; x++)
            for (int y = Math.max(0, top); y <= Math.min(n - 1, bottom); y++) {
                double exactX = x * size - wx + map.getWidth() / 2.0;
                double exactY = y * size - wy + map.getHeight() / 2.0;
                float sx = (float) exactX, sy = (float) exactY;
                float ex = (float) (exactX + size), ey = (float) (exactY + size);
                int wrapped = Math.floorMod(x, n);
                if (!vector) {
                    Bitmap b = image(z, wrapped, y);
                    if (b != null) {
                        paint.setColorFilter(null);
                        if (!explored) {
                            ColorMatrix grey = new ColorMatrix();
                            grey.setSaturation(.15f);
                            paint.setColorFilter(new ColorMatrixColorFilter(grey));
                        }
                        canvas.drawBitmap(b, null, new RectF(sx, sy, ex, ey), paint);
                        paint.setColorFilter(null);
                    }
                    continue;
                }
                Mvt.Tile tile = tile(z, wrapped, y, false);
                if (tile == null) continue;
                int save = canvas.save();
                canvas.clipRect(sx, sy, ex, ey);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(explored ? 0xffb8cb96 : 0xffc6c6b0);
                canvas.drawRect(sx, sy, ex, ey, paint);
                for (int pass = 0; pass < 4; pass++)
                    for (Mvt.Feature f : tile.features) {
                        if (order(f.layer) != pass || f.type == 1) continue;
                        if (road(f.layer) && !MapDetail.roadVisible(f.kind, map.zoom)) continue;
                        if (isBuilding(f.layer) && map.zoom < 15) continue;
                        double exactFactor = size / f.extent;
                        float factor = (float) exactFactor;
                        RectF bounds =
                                new RectF(
                                        (float) (exactX + f.bounds.left * exactFactor),
                                        (float) (exactY + f.bounds.top * exactFactor),
                                        (float) (exactX + f.bounds.right * exactFactor),
                                        (float) (exactY + f.bounds.bottom * exactFactor));
                        if (bounds.right < 0
                                || bounds.left > map.getWidth()
                                || bounds.bottom < 0
                                || bounds.top > map.getHeight()) continue;
                        int item = canvas.save();
                        boolean local = Math.abs(exactX) > 8192 || Math.abs(exactY) > 8192;
                        if (!local) {
                            canvas.translate(sx, sy);
                            canvas.scale(factor, factor);
                        }
                        float strokeFactor = local ? 1 : factor;
                        paint.setStrokeCap(Paint.Cap.ROUND);
                        paint.setStrokeJoin(Paint.Join.ROUND);
                        paint.setStyle(f.type == 3 ? Paint.Style.FILL : Paint.Style.STROKE);
                        paint.setStrokeWidth(map.dp(1) / strokeFactor);
                        if (pass == 0) paint.setColor(explored ? 0xffa6bf89 : 0xffbebfa8);
                        else if (pass == 1) paint.setColor(explored ? 0xff78aeb7 : 0xff9cabb1);
                        else if (pass == 2) paint.setColor(explored ? 0xffe5d8b6 : 0xffb9b7a2);
                        else paint.setColor(explored ? 0xffc7b58b : 0xffb4b29d);
                        if (road(f.layer)) {
                            float width = map.dp(1) * MapDetail.roadWidth(map.zoom);
                            paint.setStyle(Paint.Style.STROKE);
                            paint.setColor(0xfff5e9c9);
                            paint.setStrokeWidth(width / strokeFactor);
                        }
                        canvas.drawPath(
                                local
                                        ? WorldLand.projectParts(
                                                f.parts,
                                                exactX,
                                                exactY,
                                                exactFactor,
                                                map.getWidth(),
                                                map.getHeight(),
                                                f.type == 3)
                                        : f.path,
                                paint);
                        canvas.restoreToCount(item);
                    }
                canvas.restoreToCount(save);
                for (Mvt.Feature f : tile.features) {
                    if (f.name.isEmpty()
                            || names.contains(f.name)
                            || labels.size() >= 32
                            || f.parts.isEmpty()) continue;
                    boolean
                            place =
                                    f.layer.equals("place")
                                            || f.layer.equals("places")
                                            || f.layer.equals("water_name")
                                            || f.layer.equals("mountain_peak")
                                            || f.layer.equals("aerodrome_label"),
                            street = f.layer.equals("transportation_name") || road(f.layer),
                            poi =
                                    f.layer.equals("poi")
                                            || f.layer.equals("pois")
                                            || f.layer.equals("housenumber");
                    if (!place && !(street && map.zoom >= 15) && !(poi && map.zoom >= 18)) continue;
                    float[] coordinates = f.parts.get(0);
                    float px = (float) (exactX + coordinates[0] * size / f.extent),
                            py = (float) (exactY + coordinates[1] * size / f.extent);
                    if (px < 8
                            || py < map.dp(55)
                            || px > map.getWidth() - map.dp(35)
                            || py > map.getHeight() - map.dp(40)) continue;
                    String text = f.name.length() > 48 ? f.name.substring(0, 48) : f.name;
                    paint.setTypeface(LABEL_FONT);
                    paint.setTextSize(map.dp(place ? 13 : 10));
                    float textWidth = paint.measureText(text);
                    RectF box =
                            new RectF(
                                    px - textWidth / 2 - map.dp(4),
                                    py - map.dp(14),
                                    px + textWidth / 2 + map.dp(4),
                                    py + map.dp(6));
                    boolean collide = false;
                    for (RectF previous : labels)
                        if (RectF.intersects(previous, box)) {
                            collide = true;
                            break;
                        }
                    if (collide) continue;
                    labels.add(box);
                    names.add(f.name);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(map.dp(3));
                    paint.setColor(0xffeee7cf);
                    canvas.drawText(text, px - textWidth / 2, py, paint);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(0xff384d43);
                    canvas.drawText(text, px - textWidth / 2, py, paint);
                }
            }
        paint.setStyle(Paint.Style.FILL);
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        loader.shutdownNow();
        geometry.evictAll();
        images.evictAll();
        db.close();
        if (descriptor != null)
            try {
                descriptor.close();
            } catch (IOException ignored) {
            }
    }
}

package ru.main.explorer;

import android.util.JsonReader;
import android.util.JsonToken;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;

/** Производный временный источник. В памяти нет списка records или координат всего файла. */
final class AtlasDiskSource implements AutoCloseable {
    static final int BUFFER_BYTES = 16384;
    final File file;
    private final long downloadGeneration = DownloadedMaps.generation();
    long compactBytes = 1024, rawBytes, peakFeatureBytes;
    int count;
    private int readers;
    private volatile boolean closed;

    private AtlasDiskSource(File file) {
        this.file = file;
    }

    static AtlasDiskSource build(
            File source,
            File directory,
            boolean overview,
            boolean wallsOnly,
            BooleanSupplier cancelled)
            throws IOException {
        AtlasDiskSource result =
                new AtlasDiskSource(File.createTempFile("atlas-", ".geometry", directory));
        File[] passes = new File[3];
        DataOutputStream[] writers = new DataOutputStream[3];
        boolean complete = false;
        long traceStart = System.nanoTime();
        try (Points points = new Points(directory)) {
            for (int i = 0; i < 3; i++) {
                passes[i] = File.createTempFile("atlas-", ".pass", directory);
                writers[i] =
                        new DataOutputStream(
                                new BufferedOutputStream(
                                        new FileOutputStream(passes[i]), BUFFER_BYTES));
            }
            try (InputStream bytes = new FileInputStream(source);
                    InputStream stream =
                            source.getName().endsWith(".gz")
                                    ? new GZIPInputStream(bytes, BUFFER_BYTES)
                                    : bytes;
                    JsonReader json =
                            new JsonReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                json.beginObject();
                boolean elements = false;
                while (json.hasNext()) {
                    check(cancelled);
                    if (!"elements".equals(json.nextName())) {
                        json.skipValue();
                        continue;
                    }
                    elements = true;
                    json.beginArray();
                    while (json.hasNext())
                        result.element(json, points, writers, overview, wallsOnly, cancelled);
                    json.endArray();
                }
                json.endObject();
                if (!elements) throw new IOException("Missing elements");
            }
            for (DataOutputStream writer : writers) writer.close();
            try (OutputStream out =
                    new BufferedOutputStream(new FileOutputStream(result.file), BUFFER_BYTES)) {
                byte[] buffer = new byte[BUFFER_BYTES];
                for (File pass : passes)
                    try (InputStream in = new FileInputStream(pass)) {
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            check(cancelled);
                            out.write(buffer, 0, n);
                        }
                    }
            }
            complete = true;
            return result;
        } finally {
            MapTrace.record(
                    MapTrace.Lane.IMPORT,
                    complete ? MapTrace.Event.SCAN : MapTrace.Event.CANCEL,
                    MapTrace.id(result),
                    result.count,
                    source.length(),
                    System.nanoTime() - traceStart);
            for (DataOutputStream writer : writers)
                if (writer != null)
                    try {
                        writer.close();
                    } catch (IOException ignored) {
                    }
            for (File pass : passes) if (pass != null) pass.delete();
            if (!complete) result.close();
        }
    }

    private static void check(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Atlas source cancelled");
    }

    /** Маленький объект не открывает файл; длинный сбрасывается за фиксированным порогом. */
    private static final class Points extends OutputStream implements AutoCloseable {
        final File file;
        final byte[] buffer = new byte[BUFFER_BYTES];
        int size;
        OutputStream spill;

        Points(File directory) throws IOException {
            file = File.createTempFile("atlas-", ".points", directory);
        }

        void reset() throws IOException {
            finish();
            size = 0;
        }

        public void write(int value) throws IOException {
            if (size < buffer.length) buffer[size++] = (byte) value;
            else {
                if (spill == null) {
                    spill = new BufferedOutputStream(new FileOutputStream(file), BUFFER_BYTES);
                    spill.write(buffer);
                }
                spill.write(value);
                size++;
            }
        }

        void finish() throws IOException {
            if (spill != null) {
                spill.close();
                spill = null;
            }
        }

        DataInputStream input() throws IOException {
            finish();
            return new DataInputStream(
                    size <= buffer.length
                            ? new ByteArrayInputStream(buffer, 0, size)
                            : new BufferedInputStream(new FileInputStream(file), BUFFER_BYTES));
        }

        public void close() throws IOException {
            try {
                finish();
            } finally {
                file.delete();
            }
        }
    }

    private void element(
            JsonReader json,
            Points points,
            DataOutputStream[] writers,
            boolean overview,
            boolean wallsOnly,
            BooleanSupplier cancelled)
            throws IOException {
        points.reset();
        DataOutputStream coordinates = new DataOutputStream(points);
        String kind = "", name = "", natural = "";
        boolean building = false, highway = false, waterway = false, tags = false;
        double south = 90, west = 180, north = -90, east = -180;
        double firstLat = 0, firstLon = 0, finalLat = 0, finalLon = 0;
        int length = 0;
        json.beginObject();
        while (json.hasNext()) {
            check(cancelled);
            String field = json.nextName();
            if ("tags".equals(field)) {
                if (json.peek() != JsonToken.BEGIN_OBJECT) {
                    json.skipValue();
                    continue;
                }
                tags = true;
                json.beginObject();
                while (json.hasNext()) {
                    String key = json.nextName();
                    if ("building".equals(key)) {
                        building = true;
                        json.skipValue();
                    } else if ("highway".equals(key)) {
                        highway = true;
                        kind = text(json);
                    } else if ("name".equals(key)) name = text(json);
                    else if ("natural".equals(key)) natural = text(json);
                    else {
                        if ("waterway".equals(key)) waterway = true;
                        json.skipValue();
                    }
                }
                json.endObject();
            } else if ("geometry".equals(field)) {
                if (json.peek() != JsonToken.BEGIN_ARRAY) {
                    json.skipValue();
                    continue;
                }
                json.beginArray();
                while (json.hasNext()) {
                    check(cancelled);
                    double lat = Double.NaN, lon = Double.NaN;
                    json.beginObject();
                    while (json.hasNext()) {
                        String key = json.nextName();
                        if ("lat".equals(key)) lat = json.nextDouble();
                        else if ("lon".equals(key)) lon = json.nextDouble();
                        else json.skipValue();
                    }
                    json.endObject();
                    if (!Double.isFinite(lat) || !Double.isFinite(lon))
                        throw new IOException("Invalid coordinate");
                    coordinates.writeDouble(lat);
                    coordinates.writeDouble(lon);
                    if (length == 0) {
                        firstLat = lat;
                        firstLon = lon;
                    }
                    finalLat = lat;
                    finalLon = lon;
                    length += 2;
                    south = Math.min(south, lat);
                    north = Math.max(north, lat);
                    west = Math.min(west, lon);
                    east = Math.max(east, lon);
                }
                json.endArray();
            } else json.skipValue();
        }
        json.endObject();
        points.finish();
        if (!tags || length < 4 || overview && building || wallsOnly && !building) return;
        String type =
                building
                        ? "building"
                        : highway ? "road" : waterway || "water".equals(natural) ? "water" : "land";
        int pass = building ? 1 : highway ? 2 : 0;
        int kept = overview ? copyPoints(points, length, true, null, cancelled) : length;
        byte[] typeBytes = type.getBytes(StandardCharsets.UTF_8),
                nameBytes = name.getBytes(StandardCharsets.UTF_8),
                kindBytes = kind.getBytes(StandardCharsets.UTF_8);
        DataOutputStream out = writers[pass];
        out.writeLong(
                8L
                        + 4
                        + 4
                        + 1
                        + 32
                        + 4
                        + 12
                        + typeBytes.length
                        + nameBytes.length
                        + kindBytes.length
                        + kept * 8L);
        out.writeInt(count++);
        out.writeInt(pass);
        out.writeBoolean(firstLat == finalLat && firstLon == finalLon);
        out.writeDouble(south);
        out.writeDouble(west);
        out.writeDouble(north);
        out.writeDouble(east);
        out.writeInt(kept);
        writeString(out, typeBytes);
        writeString(out, nameBytes);
        writeString(out, kindBytes);
        copyPoints(points, length, overview, out, cancelled);
        compactBytes += 256L + kept * 8L + name.length() * 2L + kind.length() * 2L;
        long wallIndex = 0;
        if (building && firstLat == finalLat && firstLon == finalLon) {
            long cells =
                    ((long) Math.floor(east * 500) - (long) Math.floor(west * 500) + 1)
                            * ((long) Math.floor(north * 1000)
                                    - (long) Math.floor(south * 1000)
                                    + 1);
            wallIndex = cells > 256 ? 16 : Math.max(0, cells) * 128;
        }
        if (building) compactBytes += 112L + kept * 8L + wallIndex;
        rawBytes += 960L + kept * 16L + name.length() * 2L + kind.length() * 2L + wallIndex;
    }

    private static String text(JsonReader json) throws IOException {
        if (json.peek() == JsonToken.NULL) {
            json.nextNull();
            return "";
        }
        return json.nextString();
    }

    private static int copyPoints(
            Points points,
            int length,
            boolean overview,
            DataOutputStream out,
            BooleanSupplier cancelled)
            throws IOException {
        int kept = 0;
        try (DataInputStream in = points.input()) {
            double lastLat = 0, lastLon = 0, scale = 1, step = 4.0 / 111195.08;
            for (int i = 0; i < length; i += 2) {
                check(cancelled);
                double lat = in.readDouble(), lon = in.readDouble();
                if (i == 0) scale = Math.cos(Math.toRadians(lat));
                if (!overview
                        || length <= 6
                        || i == 0
                        || i == length - 2
                        || Math.abs(lat - lastLat) >= step
                        || Math.abs(lon - lastLon) * scale >= step) {
                    if (out != null) {
                        out.writeDouble(lat);
                        out.writeDouble(lon);
                    }
                    lastLat = lat;
                    lastLon = lon;
                    kept += 2;
                }
            }
        }
        return kept;
    }

    private static void writeString(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0) throw new IOException("Invalid atlas string");
        byte[] bytes = new byte[size];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void skip(DataInputStream in, long bytes) throws IOException {
        if (bytes < 0) throw new IOException("Invalid atlas record");
        while (bytes > 0) {
            long skipped = in.skip(bytes);
            if (skipped <= 0) {
                in.readByte();
                skipped = 1;
            }
            bytes -= skipped;
        }
    }

    synchronized Cursor open(double cx, double cy, double dx, double dy, int zoom)
            throws IOException {
        if (closed) throw new IOException("Closed atlas source");
        Cursor result = new Cursor(cx, cy, dx, dy, zoom);
        readers++;
        return result;
    }

    final class Cursor implements AutoCloseable {
        private final DataInputStream in;
        private final DownloadedMaps.Lease storage;
        private final double cx, cy, dx, dy;
        private final int zoom;
        private final long fileBytes;
        private long position;
        private long records, accepted;
        private final long traceStart = System.nanoTime();
        private boolean wallQuery;
        private boolean done;

        Cursor(double cx, double cy, double dx, double dy, int zoom) throws IOException {
            storage = DownloadedMaps.enter(downloadGeneration);
            try {
                in =
                        new DataInputStream(
                                new BufferedInputStream(new FileInputStream(file), BUFFER_BYTES));
                fileBytes = file.length();
            } catch (IOException | RuntimeException | Error failure) {
                storage.close();
                throw failure;
            }
            this.cx = cx;
            this.cy = cy;
            this.dx = dx;
            this.dy = dy;
            this.zoom = zoom;
        }

        AtlasTiles.Feature next(BooleanSupplier cancelled) throws IOException {
            while (position < fileBytes) {
                if (closed) throw new InterruptedIOException("Closed atlas source");
                check(cancelled);
                long bytes = in.readLong();
                if (bytes < 65 || bytes > fileBytes - position)
                    throw new IOException("Invalid atlas record");
                position += bytes;
                records++;
                int ordinal = in.readInt(), pass = in.readInt();
                boolean area = in.readBoolean();
                double south = in.readDouble(),
                        west = in.readDouble(),
                        north = in.readDouble(),
                        east = in.readDouble();
                int length = in.readInt();
                double left = MapView.x(west),
                        right = MapView.x(east),
                        top = MapView.y(north),
                        bottom = MapView.y(south);
                boolean intersects = false;
                for (int wrap = -1; wrap <= 1; wrap++)
                    if (right >= cx + wrap * 256 - dx
                            && left <= cx + wrap * 256 + dx
                            && bottom >= cy - dy
                            && top <= cy + dy) intersects = true;
                if (!intersects) {
                    skip(in, bytes - 53);
                    continue;
                }
                String type = readString(in), name = readString(in), kind = readString(in);
                double scale = 1 << zoom;
                if (pass == 2 && !MapDetail.roadVisible(kind, zoom)
                        || pass == 1
                                && !MapDetail.buildingVisible(
                                        zoom,
                                        (float) ((right - left) * scale),
                                        (float) ((bottom - top) * scale))) {
                    skip(in, length * 8L);
                    continue;
                }
                int stride = Math.max(2, 2 * (int) Math.ceil(length / 8192.0));
                int samples = (length + stride - 1) / stride;
                double[] xy = new double[samples * 2];
                for (int j = 0, i = 0; i < length; j += 2, i += stride) {
                    check(cancelled);
                    double lat = in.readDouble(), lon = in.readDouble();
                    xy[j] = MapView.x(lon);
                    xy[j + 1] = MapView.y(lat);
                    skip(in, (Math.min(stride, length - i) - 2) * 8L);
                }
                peakFeatureBytes =
                        Math.max(
                                peakFeatureBytes,
                                256L + xy.length * 8L + 2L * (name.length() + kind.length()));
                accepted++;
                return new AtlasTiles.Feature(
                        xy,
                        type,
                        name,
                        kind,
                        area,
                        pass,
                        ordinal,
                        left,
                        right,
                        top,
                        bottom,
                        length,
                        (samples - 1) * stride == length - 2);
            }
            return null;
        }

        public void close() throws IOException {
            if (done) return;
            done = true;
            try {
                in.close();
            } finally {
                try {
                    MapTrace.record(
                            wallQuery ? MapTrace.Lane.WALL : MapTrace.Lane.VIEWPORT,
                            MapTrace.Event.SCAN,
                            MapTrace.id(AtlasDiskSource.this),
                            records,
                            position,
                            System.nanoTime() - traceStart);
                    MapTrace.record(
                            wallQuery ? MapTrace.Lane.WALL : MapTrace.Lane.VIEWPORT,
                            MapTrace.Event.FINISH,
                            MapTrace.id(this),
                            accepted,
                            fileBytes,
                            zoom);
                    synchronized (AtlasDiskSource.this) {
                        readers--;
                        if (closed && readers == 0) file.delete();
                    }
                } finally {
                    storage.close();
                }
            }
        }
    }

    /** Только для источника, чей оценённый raw-граф заранее помещается в рабочий бюджет. */
    OfflineAtlas.IndexedWays readSmall(BooleanSupplier cancelled) throws IOException {
        OfflineAtlas.IndexedWays result = new OfflineAtlas.IndexedWays();
        readWays(result, -90, -180, 90, 180, false, cancelled);
        result.sort(Comparator.comparingInt(w -> ((StoredWay) w).ordinal));
        result.index();
        return result;
    }

    private static final class StoredWay extends OfflineAtlas.Way {
        int ordinal;
    }

    void readWays(
            Collection<OfflineAtlas.Way> result,
            double south,
            double west,
            double north,
            double east,
            boolean wallsOnly,
            BooleanSupplier cancelled)
            throws IOException {
        try (Cursor lease = open(0, 0, 0, 0, 0)) {
            lease.wallQuery = wallsOnly;
            DataInputStream in = lease.in;
            while (lease.position < lease.fileBytes) {
                if (closed) throw new InterruptedIOException("Closed atlas source");
                check(cancelled);
                long bytes = in.readLong();
                if (bytes < 65 || bytes > lease.fileBytes - lease.position)
                    throw new IOException("Invalid atlas record");
                lease.position += bytes;
                lease.records++;
                StoredWay w = new StoredWay();
                w.ordinal = in.readInt();
                int pass = in.readInt();
                w.area = in.readBoolean();
                w.south = in.readDouble();
                w.west = in.readDouble();
                w.north = in.readDouble();
                w.east = in.readDouble();
                int length = in.readInt();
                if (wallsOnly && (pass != 1 || !w.area)
                        || w.south > north
                        || w.north < south
                        || w.west > east
                        || w.east < west) {
                    skip(in, bytes - 53);
                    continue;
                }
                w.type = readString(in);
                w.name = readString(in);
                w.kind = readString(in);
                w.coords = new double[length];
                for (int i = 0; i < length; i++) w.coords[i] = in.readDouble();
                result.add(w);
                lease.accepted++;
            }
        }
    }

    public synchronized void close() {
        closed = true;
        if (readers == 0) file.delete();
    }
}

package ru.main.explorer;

import java.util.Arrays;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Experimental, provider-independent pixel path candidate. No network, View or storage access. */
final class ImageryTrace {
    static final int MAX_SIDE = 1024, MAX_PIXELS = 262144;
    private ImageryTrace() {}

    /** Caller-supplied provenance, not a claim that the source is licensed or accurate. */
    static final class Source {
        final String id, attribution, permission, capturedAt;
        Source(String id, String attribution, String permission, String capturedAt) {
            this.id = required(id); this.attribution = required(attribution);
            this.permission = required(permission); this.capturedAt = required(capturedAt);
        }
        private static String required(String s) {
            if (s == null || s.trim().isEmpty() || s.length() > 4096)
                throw new IllegalArgumentException("Missing or oversized provenance");
            return s;
        }
    }

    /** North-up raster outer edges, NOT pixel centres. No rotated rasters or dateline wrap.
     * mercator=false means linear EPSG:4326; true means linear EPSG:3857 y.
     * The caller must verify the CRS/extent against its source; screenshots are not georeferenced.
     */
    static final class GeoReference {
        final double west, south, east, north;
        final boolean mercator;
        GeoReference(double west, double south, double east, double north, boolean mercator) {
            double limit = mercator ? 85.0511287798066 : 90;
            if (!Double.isFinite(west) || !Double.isFinite(east) || !Double.isFinite(south)
                    || !Double.isFinite(north) || west < -180 || east > 180 || west >= east
                    || south < -limit || north > limit || south >= north)
                throw new IllegalArgumentException("Invalid north-up geographic extent");
            this.west = west; this.south = south; this.east = east; this.north = north;
            this.mercator = mercator;
        }
        double[] coordinate(int x, int y, int width, int height) {
            dimensions(width, height);
            point(x, y, width, height);
            double t = (y + .5) / height;
            double lat = mercator ? Math.toDegrees(Math.atan(Math.sinh(project(north)
                    + t * (project(south) - project(north))))) : north + t * (south - north);
            return new double[]{lat, west + (x + .5) / width * (east - west)};
        }
        private static double project(double lat) {
            return Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2));
        }
    }

    static final class Vertex {
        final int x, y;
        final double latitude, longitude;
        Vertex(int x, int y, double[] geo) {
            this.x = x; this.y = y; latitude = geo[0]; longitude = geo[1];
        }
    }
    static final class Candidate {
        final List<Vertex> vertices;
        final Source source;
        final GeoReference reference;
        final int width, height, startX, startY, endX, endY, sampleX, sampleY, sampleRgb, sampleRadius;
        final double cost;
        // A candidate is never a confirmed road or recorded walk.
        Candidate(List<Vertex> vertices, Source source, GeoReference reference, int width, int height,
                  int sx, int sy, int ex, int ey, int cx, int cy, int rgb, int radius, double cost) {
            this.width = width; this.height = height;
            this.vertices = Collections.unmodifiableList(vertices); this.source = source;
            this.reference = reference; startX = sx; startY = sy; endX = ex; endY = ey;
            sampleX = cx; sampleY = cy; sampleRgb = rgb; sampleRadius = radius; this.cost = cost;
        }
    }

    /** Blocking CPU work: call on a worker with exclusively owned arrays for the call duration.
     * Returns null when disconnected. Cancellation throws, never returns a partial candidate.
     * Alpha < 255 and blocked=true are impassable; diagonal corner cutting is forbidden.
     * Cost = step length * mean(1 + 20 * normalized RGB squared error).
     * Memory is O(pixels), including an indexed heap with at most one entry per pixel.
     */
    static Candidate trace(int width, int height, int[] argb, boolean[] blocked,
                           int sx, int sy, int ex, int ey, int sampleX, int sampleY,
                           int radius, GeoReference reference, Source source, BooleanSupplier cancelled) {
        dimensions(width, height);
        int count = width * height;
        if (argb == null || argb.length != count || blocked != null && blocked.length != count
                || reference == null || source == null || cancelled == null || radius < 0 || radius > 2)
            throw new IllegalArgumentException("Invalid trace input");
        point(sx, sy, width, height); point(ex, ey, width, height);
        point(sampleX, sampleY, width, height);
        check(cancelled);
        int start = sy * width + sx, end = ey * width + ex;
        if (!passable(start, argb, blocked) || !passable(end, argb, blocked))
            throw new IllegalArgumentException("Endpoint is blocked or transparent");
        long red = 0, green = 0, blue = 0; int samples = 0;
        for (int y = Math.max(0, sampleY-radius); y <= Math.min(height-1, sampleY+radius); y++)
            for (int x = Math.max(0, sampleX-radius); x <= Math.min(width-1, sampleX+radius); x++) {
                int i = y * width + x;
                if (passable(i, argb, blocked)) {
                    red += (argb[i] >>> 16) & 255; green += (argb[i] >>> 8) & 255;
                    blue += argb[i] & 255; samples++;
                }
            }
        if (samples == 0) throw new IllegalArgumentException("Empty colour sample");
        int r = (int)(red/samples), g = (int)(green/samples), b = (int)(blue/samples);
        int rgb = (r << 16) | (g << 8) | b;
        double[] weight = new double[count], distance = new double[count];
        int[] previous = new int[count];
        Arrays.fill(distance, Double.POSITIVE_INFINITY); Arrays.fill(previous, -1);
        for (int i = 0; i < count; i++) {
            if ((i & 255) == 0) check(cancelled);
            int dr = ((argb[i] >>> 16) & 255)-r, dg = ((argb[i] >>> 8) & 255)-g;
            int db = (argb[i] & 255)-b;
            weight[i] = 1 + 20.0 * (dr*dr + dg*dg + db*db) / (3*255*255);
        }
        Heap heap = new Heap(count, distance);
        distance[start] = 0; heap.update(start);
        while (heap.size > 0) {
            check(cancelled);
            int u = heap.pop();
            if (u == end) break;
            int ux = u % width, uy = u / width;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int x = ux+dx, y = uy+dy;
                if (dx == 0 && dy == 0 || x < 0 || y < 0 || x >= width || y >= height) continue;
                int v = y*width+x;
                if (heap.position[v] == -2 || !passable(v, argb, blocked)) continue;
                if (dx != 0 && dy != 0 && (!passable(uy*width+x, argb, blocked)
                        || !passable(y*width+ux, argb, blocked))) continue;
                double next = distance[u] + (dx != 0 && dy != 0 ? Math.sqrt(2) : 1)
                        * (weight[u]+weight[v]) / 2;
                if (next < distance[v]) { distance[v] = next; previous[v] = u; heap.update(v); }
            }
        }
        check(cancelled);
        if (!Double.isFinite(distance[end])) return null;
        List<Vertex> vertices = new ArrayList<>();
        for (int i = end; i != -1; i = previous[i]) {
            check(cancelled);
            vertices.add(new Vertex(i%width, i/width, reference.coordinate(i%width, i/width, width, height)));
        }
        Collections.reverse(vertices);
        check(cancelled);
        return new Candidate(vertices, source, reference, width, height, sx, sy, ex, ey, sampleX, sampleY, rgb, radius, distance[end]);
    }
    private static void dimensions(int w, int h) {
        if (w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE || (long)w*h > MAX_PIXELS)
            throw new IllegalArgumentException("Raster exceeds tracing budget");
    }
    private static void point(int x, int y, int w, int h) {
        if (x < 0 || y < 0 || x >= w || y >= h) throw new IllegalArgumentException("Pixel out of bounds");
    }
    private static boolean passable(int i, int[] argb, boolean[] blocked) {
        return (argb[i] >>> 24) == 255 && (blocked == null || !blocked[i]);
    }
    private static void check(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new CancellationException();
    }
    private static final class Heap {
        final int[] nodes, position; final double[] distance; int size;
        Heap(int n, double[] distance) {
            nodes = new int[n]; position = new int[n]; Arrays.fill(position, -1); this.distance = distance;
        }
        void update(int node) {
            int p = position[node];
            if (p == -1) { p = size++; nodes[p] = node; position[node] = p; }
            while (p > 0) {
                int parent = (p-1)/2;
                if (distance[nodes[parent]] <= distance[node]) break;
                swap(p, parent); p = parent;
            }
        }
        int pop() {
            int node = nodes[0]; swap(0, --size); position[node] = -2;
            int p = 0;
            while (p*2+1 < size) {
                int c = p*2+1;
                if (c+1 < size && distance[nodes[c+1]] < distance[nodes[c]]) c++;
                if (distance[nodes[p]] <= distance[nodes[c]]) break;
                swap(p, c); p = c;
            }
            return node;
        }
        void swap(int a, int b) {
            int t = nodes[a]; nodes[a] = nodes[b]; nodes[b] = t;
            position[nodes[a]] = a; position[nodes[b]] = b;
        }
    }
}

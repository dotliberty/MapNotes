package ru.main.explorer;

import org.json.*;
import java.util.*;

/** Discovery geometry is not a promise that the raster is safe to trace. */
final class OamCoverage {
    enum Reason {
        LICENSE,
        METADATA,
        GSD,
        CRS,
        ROTATION,
        EXTENT,
        SHAPE,
        GEOMETRY,
        ASSET,
        ID,
        COLLECTION
    }

    final String id, title, producer, captured, license, gsd;
    final List<List<double[]>> polygons;
    final Set<Reason> reasons;
    final OamImagery.Source source;

    OamCoverage(JSONObject item) {
        JSONObject p = item.optJSONObject("properties"), a = item.optJSONObject("assets");
        if (p == null) p = new JSONObject();
        a = a == null ? null : a.optJSONObject("visual");
        id = item.optString("id", "");
        title = p.optString("title", "");
        producer = p.optString("oam:producer_name", "");
        captured = p.optString("datetime", p.optString("start_datetime", ""));
        license = p.optString("license", "");
        gsd = p.optString("gsd", "");
        EnumSet<Reason> r = EnumSet.noneOf(Reason.class);
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) r.add(Reason.ID);
        if (!"openaerialmap".equals(item.optString("collection"))) r.add(Reason.COLLECTION);
        if (!"CC-BY-4.0".equals(license)) r.add(Reason.LICENSE);
        double g = p.optDouble("gsd", Double.NaN);
        if (!Double.isFinite(g) || g <= 0 || g > .5) r.add(Reason.GSD);
        try {
            OamImagery.required(title);
            OamImagery.required(producer);
            OamImagery.required(captured);
            java.time.OffsetDateTime.parse(captured);
        } catch (Exception e) {
            r.add(Reason.METADATA);
        }
        if (a == null) {
            r.addAll(
                    Arrays.asList(
                            Reason.ASSET,
                            Reason.CRS,
                            Reason.ROTATION,
                            Reason.SHAPE,
                            Reason.EXTENT));
        } else {
            if (!a.optString("href").startsWith("https://") || a.optString("href").length() > 4096)
                r.add(Reason.ASSET);
            if (!"EPSG:4326".equals(a.optString("proj:code"))) r.add(Reason.CRS);
            JSONArray t = a.optJSONArray("proj:transform"), s = a.optJSONArray("proj:shape");
            try {
                if (t == null || t.length() != 9) throw new Exception();
                for (int i = 0; i < 9; i++)
                    if (!Double.isFinite(t.getDouble(i))) throw new Exception();
                if (t.getDouble(1) != 0
                        || t.getDouble(3) != 0
                        || t.getDouble(0) <= 0
                        || t.getDouble(4) >= 0
                        || t.getDouble(6) != 0
                        || t.getDouble(7) != 0
                        || t.getDouble(8) != 1) throw new Exception();
            } catch (Exception e) {
                r.add(Reason.ROTATION);
            }
            try {
                if (s == null
                        || s.length() != 2
                        || s.getDouble(0) != s.getInt(0)
                        || s.getDouble(1) != s.getInt(1)
                        || s.getInt(0) < 1
                        || s.getInt(1) < 1
                        || s.getInt(0) > 1000000
                        || s.getInt(1) > 1000000) throw new Exception();
            } catch (Exception e) {
                r.add(Reason.SHAPE);
            }
            try {
                JSONArray b = a.getJSONArray("proj:bbox");
                if (b.length() != 4) throw new Exception();
                OamImagery.Bounds box =
                        new OamImagery.Bounds(
                                b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3));
                if (!r.contains(Reason.ROTATION)
                        && !r.contains(Reason.SHAPE)
                        && (Math.abs(t.getDouble(2) - box.w) > 1e-8
                                || Math.abs(t.getDouble(5) - box.n) > 1e-8
                                || Math.abs(t.getDouble(2) + s.getInt(1) * t.getDouble(0) - box.e)
                                        > 1e-8
                                || Math.abs(t.getDouble(5) + s.getInt(0) * t.getDouble(4) - box.s)
                                        > 1e-8)) r.add(Reason.EXTENT);
                try {
                    OamImagery.Bounds geo =
                            OamImagery.Source.rectangle(item.getJSONObject("geometry"));
                    if (Math.abs(geo.w - box.w) > 1e-6
                            || Math.abs(geo.s - box.s) > 1e-6
                            || Math.abs(geo.e - box.e) > 1e-6
                            || Math.abs(geo.n - box.n) > 1e-6) r.add(Reason.EXTENT);
                } catch (Exception unsupported) {
                    r.add(Reason.GEOMETRY);
                }
            } catch (Exception e) {
                r.add(Reason.EXTENT);
            }
        }
        List<List<double[]>> geometry;
        try {
            geometry = parseGeometry(item.getJSONObject("geometry"));
        } catch (Exception e) {
            geometry = Collections.emptyList();
            r.add(Reason.GEOMETRY);
        }
        // The tracing pilot supports just one north-up rectangular footprint, even
        // though discovery can render valid nonrectangular and multipart shapes.
        try {
            OamImagery.Source.rectangle(item.getJSONObject("geometry"));
        } catch (Exception e) {
            r.add(Reason.GEOMETRY);
        }
        OamImagery.Source eligible = null;
        try {
            eligible = new OamImagery.Source(item);
        } catch (Exception e) {
            if (r.isEmpty()) r.add(Reason.METADATA);
        }
        source = r.isEmpty() ? eligible : null;
        reasons = Collections.unmodifiableSet(r);
        polygons = geometry;
    }

    static List<List<double[]>> parseGeometry(JSONObject geometry) throws Exception {
        String type = geometry.getString("type");
        JSONArray parts;
        if ("Polygon".equals(type))
            parts = new JSONArray().put(geometry.getJSONArray("coordinates"));
        else if ("MultiPolygon".equals(type)) parts = geometry.getJSONArray("coordinates");
        else throw new Exception("Geometry type");
        if (parts.length() < 1 || parts.length() > 128) throw new Exception("Geometry budget");
        List<List<double[]>> result = new ArrayList<>();
        int vertices = 0;
        for (int p = 0; p < parts.length(); p++) {
            JSONArray rings = parts.getJSONArray(p);
            if (rings.length() < 1 || rings.length() > 128) throw new Exception("Rings");
            List<double[]> polygon = new ArrayList<>();
            for (int k = 0; k < rings.length(); k++) {
                JSONArray ring = rings.getJSONArray(k);
                if (ring.length() < 4 || (vertices += ring.length()) > 10000)
                    throw new Exception("Vertices");
                double[] coords = new double[ring.length() * 2];
                for (int i = 0; i < ring.length(); i++) {
                    JSONArray point = ring.getJSONArray(i);
                    if (point.length() < 2 || point.length() > 3) throw new Exception("Dimensions");
                    double lon = point.getDouble(0), lat = point.getDouble(1);
                    if (!Double.isFinite(lon)
                            || !Double.isFinite(lat)
                            || lon < -180
                            || lon > 180
                            || lat < -85
                            || lat > 85) throw new Exception("Coordinate");
                    coords[i * 2] = lon;
                    coords[i * 2 + 1] = lat;
                    if (i > 0 && Math.abs(lon - coords[(i - 1) * 2]) > 180)
                        throw new Exception("Date line");
                }
                if (coords[0] != coords[coords.length - 2]
                        || coords[1] != coords[coords.length - 1]) throw new Exception("Open ring");
                double area = 0;
                for (int i = 2; i < coords.length; i += 2) {
                    if (coords[i] == coords[i - 2] && coords[i + 1] == coords[i - 1])
                        throw new Exception("Duplicate vertex");
                    area +=
                            (coords[i - 2] - coords[0]) * (coords[i + 1] - coords[1])
                                    - (coords[i] - coords[0]) * (coords[i - 1] - coords[1]);
                }
                if (!Double.isFinite(area) || area == 0) throw new Exception("Degenerate ring");
                polygon.add(coords);
            }
            result.add(Collections.unmodifiableList(polygon));
        }
        return Collections.unmodifiableList(result);
    }

    String label() {
        return (title.isEmpty() ? id : title) + "\n" + id;
    }
}

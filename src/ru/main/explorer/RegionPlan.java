package ru.main.explorer;

import java.util.*;

/** Shared area and tile planning for downloading and rendering. */
public final class RegionPlan {
    public final double south, west, north, east;

    public RegionPlan(double lat, double lon, double radiusMeters) {
        if (!Double.isFinite(lat)
                || !Double.isFinite(lon)
                || Math.abs(lat) > 84
                || Math.abs(lon) > 180
                || radiusMeters < 100
                || radiusMeters > 50000)
            throw new IllegalArgumentException(
                    Messages.text("area_must_be_within_100_100_km_centred_between_84_s_and_84_n"));
        double dy = radiusMeters / 111195.08, dx = dy / Math.cos(Math.toRadians(lat));
        south = lat - dy;
        north = lat + dy;
        west = lon - dx;
        east = lon + dx;
        if (west < -180 || east > 180 || south < -85 || north > 85)
            throw new IllegalArgumentException(
                    Messages.text("move_the_centre_area_crosses_the_map_boundary"));
    }

    public static double x(double lon) {
        return (lon + 180) / 360;
    }

    public static double y(double lat) {
        double r = Math.toRadians(lat);
        return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2;
    }

    public int maxZoom() {
        return Math.min(
                14,
                (int)
                        Math.ceil(
                                Math.log(
                                                156543.03392
                                                        * Math.cos(
                                                                Math.toRadians((north + south) / 2))
                                                        / 6)
                                        / Math.log(2)));
    }

    public List<int[]> tiles() {
        ArrayList<int[]> out = new ArrayList<>();
        for (int z = 0; z <= maxZoom(); z++) {
            int n = 1 << z;
            int left = (int) Math.floor(x(west) * n),
                    right = (int) Math.floor(x(east) * n),
                    top = (int) Math.floor(y(north) * n),
                    bottom = (int) Math.floor(y(south) * n);
            for (int xx = left; xx <= right; xx++)
                for (int yy = top; yy <= bottom; yy++) out.add(new int[] {z, xx, yy});
        }
        if (out.size() > 30000)
            throw new IllegalArgumentException(
                    Messages.text("too_many_tiles_move_the_centre_to_a_lower_latitude"));
        return out;
    }

    public static final class Cell {
        public final double south, west, north, east;
        public final String id;
        public final int depth;

        public Cell(String id, double s, double w, double n, double e, int depth) {
            this.id = id;
            south = s;
            west = w;
            north = n;
            east = e;
            this.depth = depth;
        }

        public String bbox() {
            return String.format(Locale.US, "%.7f,%.7f,%.7f,%.7f", south, west, north, east);
        }

        public List<Cell> split() {
            double lat = (south + north) / 2, lon = (west + east) / 2;
            return Arrays.asList(
                    new Cell(id + "0", south, west, lat, lon, depth + 1),
                    new Cell(id + "1", south, lon, lat, east, depth + 1),
                    new Cell(id + "2", lat, west, north, lon, depth + 1),
                    new Cell(id + "3", lat, lon, north, east, depth + 1));
        }
    }

    public List<Cell> cells() {
        int count = Math.max(1, (int) Math.ceil(((north - south) * 111195.08) / 10000 - 1e-8));
        ArrayList<Cell> out = new ArrayList<>();
        for (int row = 0; row < count; row++)
            for (int col = 0; col < count; col++)
                out.add(
                        new Cell(
                                "r" + row + "c" + col + "-",
                                south + (north - south) * row / count,
                                west + (east - west) * col / count,
                                south + (north - south) * (row + 1) / count,
                                west + (east - west) * (col + 1) / count,
                                0));
        return out;
    }

    public String key() {
        return String.format(
                Locale.US,
                "%.5f_%.5f_%.0f",
                (south + north) / 2,
                (west + east) / 2,
                (north - south) * 111195.08 / 2);
    }

    public long estimatedBytes() {
        return tiles().size() * 45000L + cells().size() * 4000000L;
    }

    public String bbox() {
        return String.format(Locale.US, "%.7f,%.7f,%.7f,%.7f", south, west, north, east);
    }

    public static String satelliteUrl(int z, int x, int y) {
        return "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless_3857/default/g/"
                + z
                + "/"
                + y
                + "/"
                + x
                + ".jpg";
    }

    public static String tileName(int z, int x, int y) {
        return z + "_" + x + "_" + y + ".jpg";
    }
}

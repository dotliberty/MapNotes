package ru.main.explorer;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import java.util.*;

public class Store extends SQLiteOpenHelper {
    public Store(Context c) {
        super(c, "walks.db", null, 3);
    }

    public void onCreate(SQLiteDatabase d) {
        d.execSQL(
                "CREATE TABLE points(id INTEGER PRIMARY KEY,lat REAL,lon REAL,acc REAL,t INTEGER,session INTEGER)");
        createPhotos(d);
        createWalks(d);
    }

    private void createPhotos(SQLiteDatabase d) {
        d.execSQL(
                "CREATE TABLE IF NOT EXISTS photos(id INTEGER PRIMARY KEY,lat REAL NOT NULL,lon REAL NOT NULL,acc REAL NOT NULL,path TEXT NOT NULL,note TEXT NOT NULL DEFAULT '',created INTEGER NOT NULL)");
    }

    public void onUpgrade(SQLiteDatabase d, int a, int b) {
        if (a < 2) createPhotos(d);
        if (a < 3) createWalks(d);
    }

    private void createWalks(SQLiteDatabase d) {
        d.execSQL(
                "CREATE TABLE walks(id INTEGER PRIMARY KEY,started INTEGER NOT NULL,ended INTEGER)");
        d.execSQL("CREATE UNIQUE INDEX one_open_walk ON walks((1)) WHERE ended IS NULL");
        d.execSQL(
                "CREATE TABLE walk_sessions(session INTEGER PRIMARY KEY AUTOINCREMENT,walk INTEGER NOT NULL)");
        d.execSQL("CREATE INDEX walk_session_owner ON walk_sessions(walk)");
        d.execSQL("CREATE INDEX photos_time ON photos(created)");
        d.execSQL("CREATE INDEX points_session ON points(session,id)");
    }

    public static final class Walk {
        public final long id, started, ended;

        Walk(Cursor c) {
            id = c.getLong(0);
            started = c.getLong(1);
            ended = c.isNull(2) ? 0 : c.getLong(2);
        }

        public long elapsed(long now) {
            return Math.max(0, (ended == 0 ? now : ended) - started);
        }
    }

    public Walk openWalk() {
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery("SELECT id,started,ended FROM walks WHERE ended IS NULL", null)) {
            return c.moveToFirst() ? new Walk(c) : null;
        }
    }

    public ArrayList<Walk> walks() {
        ArrayList<Walk> out = new ArrayList<>();
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery("SELECT id,started,ended FROM walks ORDER BY id DESC", null)) {
            while (c.moveToNext()) out.add(new Walk(c));
        }
        return out;
    }

    public long startWalk(long now) {
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            Walk w = openWalk();
            long id;
            if (w != null) id = w.id;
            else {
                ContentValues v = new ContentValues();
                v.put("started", now);
                id = d.insertOrThrow("walks", null, v);
            }
            d.setTransactionSuccessful();
            return id;
        } finally {
            d.endTransaction();
        }
    }

    public void finishWalk(long now) {
        ContentValues v = new ContentValues();
        Walk w = openWalk();
        if (w == null) return;
        v.put("ended", Math.max(w.started, now));
        getWritableDatabase()
                .update("walks", v, "id=? AND ended IS NULL", new String[] {"" + w.id});
    }

    // Negative ids cannot collide with legacy wall-clock sessions. Each service lifetime is
    // separate.
    public long newWalkSession(long walk) {
        ContentValues v = new ContentValues();
        v.put("walk", walk);
        return -getWritableDatabase().insertOrThrow("walk_sessions", null, v);
    }

    public ArrayList<double[]> walkPoints(long walk) {
        ArrayList<double[]> out = new ArrayList<>();
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT p.lat,p.lon,p.session,p.t FROM points p JOIN walk_sessions s ON p.session=-s.session WHERE s.walk=? ORDER BY p.id",
                                new String[] {"" + walk})) {
            while (c.moveToNext())
                out.add(new double[] {c.getDouble(0), c.getDouble(1), c.getLong(2), c.getLong(3)});
        }
        return out;
    }

    public static double distance(ArrayList<double[]> points) {
        double meters = 0;
        double[] previous = null;
        for (double[] p : points) {
            if (previous != null && MapView.connects(previous, p)) {
                double dt = (p[3] - previous[3]) / 1000.0,
                        d = GpsFilter.distance(previous[0], previous[1], p[0], p[1]);
                if (dt > 0 && d / dt <= 4.5) meters += d;
            }
            previous = p;
        }
        return meters;
    }

    public ArrayList<Photo> walkPhotos(Walk w, long now) {
        ArrayList<Photo> out = new ArrayList<>();
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT id,lat,lon,acc,path,note,created FROM photos WHERE created>=? AND created<? ORDER BY created,id",
                                new String[] {
                                    "" + w.started, "" + (w.ended == 0 ? now + 1 : w.ended)
                                })) {
            while (c.moveToNext()) out.add(new Photo(c));
        }
        return out;
    }

    public static final class Photo {
        public final long id, created;
        public final double lat, lon;
        public final float accuracy;
        public final String path, note;

        Photo(Cursor c) {
            id = c.getLong(0);
            lat = c.getDouble(1);
            lon = c.getDouble(2);
            accuracy = c.getFloat(3);
            path = c.getString(4);
            note = c.getString(5);
            created = c.getLong(6);
        }
    }

    public long addPhoto(String path, android.location.Location l, long created) {
        ContentValues v = new ContentValues();
        v.put("path", path);
        v.put("lat", l.getLatitude());
        v.put("lon", l.getLongitude());
        v.put("acc", l.getAccuracy());
        v.put("created", created);
        return getWritableDatabase().insertOrThrow("photos", null, v);
    }

    public ArrayList<Photo> photos() {
        ArrayList<Photo> r = new ArrayList<>();
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT id,lat,lon,acc,path,note,created FROM photos ORDER BY created DESC,id DESC",
                                null)) {
            while (c.moveToNext()) r.add(new Photo(c));
        }
        return r;
    }

    public Photo photo(long id) {
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT id,lat,lon,acc,path,note,created FROM photos WHERE id=?",
                                new String[] {"" + id})) {
            return c.moveToFirst() ? new Photo(c) : null;
        }
    }

    public void saveNote(long id, String note) {
        ContentValues v = new ContentValues();
        v.put("note", note);
        getWritableDatabase().update("photos", v, "id=?", new String[] {"" + id});
    }

    public void add(android.location.Location l, long session) {
        ContentValues v = new ContentValues();
        v.put("lat", l.getLatitude());
        v.put("lon", l.getLongitude());
        v.put("acc", l.getAccuracy());
        v.put("t", l.getTime());
        v.put("session", session);
        getWritableDatabase().insertOrThrow("points", null, v);
    }

    public static final class PointPage {
        public long lastId;
        public final ArrayList<double[]> points = new ArrayList<>();
    }

    public long latestPointId() {
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery("SELECT id FROM points ORDER BY id DESC LIMIT 1", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    public PointPage pointsAfter(long id, int limit) {
        PointPage page = new PointPage();
        page.lastId = id;
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT id,lat,lon,session,t FROM points WHERE id>? ORDER BY id LIMIT ?",
                                new String[] {"" + id, "" + limit})) {
            while (c.moveToNext()) {
                page.lastId = c.getLong(0);
                page.points.add(
                        new double[] {c.getDouble(1), c.getDouble(2), c.getLong(3), c.getLong(4)});
            }
        }
        return page;
    }

    public ArrayList<double[]> points() {
        ArrayList<double[]> r = new ArrayList<>();
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery("SELECT lat,lon,session,t FROM points ORDER BY id", null)) {
            while (c.moveToNext())
                r.add(new double[] {c.getDouble(0), c.getDouble(1), c.getLong(2), c.getLong(3)});
        }
        return r;
    }
}

package ru.main.explorer;

import android.app.*;
import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.location.Location;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.*;
import org.robolectric.shadows.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "ru-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TimelineTest {
    Application app;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("walks.db");
        WalkService.running = false;
        app.getSharedPreferences("map-view", 0).edit().putBoolean("online", false).commit();
    }

    Location fix(double lat, long time) {
        Location l = new Location("gps");
        l.setLatitude(lat);
        l.setLongitude(37);
        l.setAccuracy(5);
        l.setTime(time);
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        return l;
    }

    Button button(View v, String text) {
        if (v instanceof Button && ((Button) v).getText().toString().equals(text))
            return (Button) v;
        if (v instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                Button b = button(((ViewGroup) v).getChildAt(i), text);
                if (b != null) return b;
            }
        return null;
    }

    @Test
    public void migrationV2RetainsPointsPhotosNotesAndDoesNotInventWalks() {
        try (SQLiteDatabase d = app.openOrCreateDatabase("walks.db", 0, null)) {
            d.execSQL(
                    "CREATE TABLE points(id INTEGER PRIMARY KEY,lat REAL,lon REAL,acc REAL,t INTEGER,session INTEGER)");
            d.execSQL(
                    "CREATE TABLE photos(id INTEGER PRIMARY KEY,lat REAL NOT NULL,lon REAL NOT NULL,acc REAL NOT NULL,path TEXT NOT NULL,note TEXT NOT NULL DEFAULT '',created INTEGER NOT NULL)");
            d.execSQL("INSERT INTO points VALUES(1,55,37,5,100,123)");
            d.execSQL(
                    "INSERT INTO photos VALUES(7,55,37,5,'private/original.jpg','Старая заметка',100)");
            d.setVersion(2);
        }
        try (Store s = new Store(app)) {
            assertEquals(6, s.getReadableDatabase().getVersion());
            assertEquals(1, s.points().size());
            assertEquals("Старая заметка", s.photo(7).note);
            assertEquals("private/original.jpg", s.photo(7).path);
            assertTrue(s.walks().isEmpty());
            assertNull(s.openWalk());
        }
    }

    @Test
    public void startStopAreIdempotentAndSeparateWalks() {
        try (Store s = new Store(app)) {
            long id = s.startWalk(100);
            assertEquals(id, s.startWalk(200));
            s.finishWalk(1000);
            s.finishWalk(2000);
            assertEquals(1000, s.walks().get(0).ended);
            assertNotEquals(id, s.startWalk(3000));
            assertEquals(2, s.walks().size());
        }
    }

    @Test
    public void reopenRestoresUnfinishedWalkButCreatesDisconnectedSession() {
        long id, session;
        try (Store s = new Store(app)) {
            id = s.startWalk(100);
            session = s.newWalkSession(id);
            s.add(fix(55, 100), session);
        }
        try (Store s = new Store(app)) {
            assertEquals(id, s.openWalk().id);
            assertEquals(id, s.startWalk(200));
            long next = s.newWalkSession(id);
            assertNotEquals(session, next);
            s.add(fix(55.0001, 200), next);
            assertEquals(2, s.walkPoints(id).size());
            assertFalse(MapView.connects(s.walkPoints(id).get(0), s.walkPoints(id).get(1)));
            assertEquals(0, Store.distance(s.walkPoints(id)), 0);
        }
    }

    @Test
    public void distanceIncludesWalkingButNotGapsSessionsOrJumps() {
        ArrayList<double[]> p = new ArrayList<>();
        p.add(new double[] {55, 37, -1, 1000});
        p.add(new double[] {55.0001, 37, -1, 11000});
        double expected = GpsFilter.distance(55, 37, 55.0001, 37);
        assertEquals(expected, Store.distance(p), .001);
        p.add(new double[] {56, 37, -1, 12000});
        p.add(new double[] {56.0001, 37, -2, 13000});
        p.add(new double[] {56.0002, 37, -2, 50000});
        p.add(new double[] {56.001, 37, -2, 51000});
        assertEquals(expected, Store.distance(p), .001);
    }

    @Test
    public void detailShowsTimeDistancePhotosAndNavigates() throws Exception {
        Store.Walk w;
        long photo;
        try (Store s = new Store(app)) {
            long id = s.startWalk(1000), session = s.newWalkSession(id);
            s.add(fix(55, 1000), session);
            s.add(fix(55.0001, 11000), session);
            photo = s.addPhoto("missing", fix(55.0001, 5000), 5000);
            s.finishWalk(61000);
            w = s.walks().get(0);
        }
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity a = c.get();
            a.showWalk(w);
            java.lang.reflect.Field worker = MainActivity.class.getDeclaredField("dataWorker");
            worker.setAccessible(true);
            ((java.util.concurrent.ExecutorService) worker.get(a))
                    .submit(() -> {})
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            View root = dialog.getWindow().getDecorView();
            assertTrue(allText(root).contains("полное время 0 ч 01 мин 00 с"));
            assertTrue(allText(root).contains("Фото за весь период: 1"));
            assertNotNull(button(root, "К месту прогулки на карте"));
            button(
                            root,
                            "Фото №"
                                    + photo
                                    + " · "
                                    + new java.text.SimpleDateFormat(
                                                    "HH:mm:ss", Locale.getDefault())
                                            .format(new Date(5000)))
                    .performClick();
            assertEquals(
                    photo, Shadows.shadowOf(a).getNextStartedActivity().getLongExtra("id", -1));
            dialog.getButton(-1).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField("map");
            field.setAccessible(true);
            assertEquals(55, ((MapView) field.get(a)).centerLatitude(), .00001);
        } finally {
            c.pause().stop().destroy();
        }
    }

    String allText(View v) {
        String out = v instanceof TextView ? ((TextView) v).getText().toString() : "";
        if (v instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++)
                out += allText(((ViewGroup) v).getChildAt(i));
        return out;
    }

    @Test
    public void distanceRejectsDuplicateAndBackwardTimes() {
        ArrayList<double[]> p = new ArrayList<>();
        p.add(new double[] {55, 37, 1, 1000});
        p.add(new double[] {55.0001, 37, 1, 1000});
        p.add(new double[] {55.0002, 37, 1, 999});
        assertEquals(0, Store.distance(p), 0);
    }

    @Test
    public void elapsedExplicitlyIncludesNoGpsAndInterruption() {
        try (Store s = new Store(app)) {
            s.startWalk(1000);
            assertEquals(9000, s.openWalk().elapsed(10000));
            s.finishWalk(21000);
            assertEquals(20000, s.walks().get(0).elapsed(90000));
        }
    }

    @Test
    public void photosUseShutterPeriodEvenWhenSavedAfterStop() {
        try (Store s = new Store(app)) {
            s.addPhoto("older", fix(55, 90), 90);
            s.startWalk(100);
            s.finishWalk(200);
            long inside = s.addPhoto("delayed", fix(55.0001, 150), 150);
            s.addPhoto("atEnd", fix(55, 200), 200);
            s.startWalk(200);
            Store.Walk first = s.walks().get(1);
            assertEquals(1, s.walkPhotos(first, 999).size());
            assertEquals(inside, s.walkPhotos(first, 999).get(0).id);
            assertEquals(1, s.walkPhotos(s.openWalk(), 999).size());
            assertEquals(3, s.photos().size());
        }
    }

    @Test
    public void foregroundHistoryNeverAssignedToWalk() {
        try (Store s = new Store(app)) {
            long id = s.startWalk(100);
            s.add(fix(55, 101), 12345);
            long session = s.newWalkSession(id);
            s.add(fix(55.0001, 102), session);
            assertEquals(2, s.points().size());
            assertEquals(1, s.walkPoints(id).size());
        }
    }

    @Test
    public void serviceStopAndUnexpectedDestroyHaveDifferentMeaning() {
        ServiceController<WalkService> c = Robolectric.buildService(WalkService.class).create();
        c.get().onStartCommand(new Intent(app, WalkService.class), 0, 1);
        try (Store s = new Store(app)) {
            assertNotNull(s.openWalk());
        }
        c.destroy();
        try (Store s = new Store(app)) {
            assertNotNull(s.openWalk());
        }
        c = Robolectric.buildService(WalkService.class).create();
        c.get().onStartCommand(new Intent(app, WalkService.class), 0, 2);
        try (Store s = new Store(app)) {
            assertEquals(1, s.walks().size());
            try (android.database.Cursor rows =
                    s.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM walk_sessions", null)) {
                assertTrue(rows.moveToFirst());
                assertEquals(2, rows.getInt(0));
            }
        }
        c.get().onStartCommand(new Intent(app, WalkService.class).setAction("STOP"), 0, 3);
        try (Store s = new Store(app)) {
            assertNull(s.openWalk());
            assertEquals(1, s.walks().size());
        }
        c.destroy();
    }

    @Test
    public void activityShowsHistoryAndRecoveryChoice() {
        try (Store s = new Store(app)) {
            s.startWalk(100);
        }
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity a = c.get();
            a.refresh();
            assertNotNull(button(a.getWindow().getDecorView(), Language.text(a, "walk_history")));
            Button resume = button(a.getWindow().getDecorView(), Language.text(a, "resume_walk"));
            assertNotNull(resume);
            resume.performClick();
            AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("Продолжить", d.getButton(-1).getText().toString());
            d.getButton(-2).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            try (Store s = new Store(app)) {
                assertNull(s.openWalk());
            }
            button(a.getWindow().getDecorView(), Language.text(a, "walk_history")).performClick();
            assertEquals(
                    1,
                    ShadowAlertDialog.getLatestAlertDialog().getListView().getAdapter().getCount());
        } finally {
            c.pause().stop().destroy();
        }
    }

    @Test
    public void photoButtonNavigatesToExactMapPlace() {
        long id;
        try (Store s = new Store(app)) {
            id = s.addPhoto("missing-file", fix(55.125, 100), 100);
        }
        ActivityController<PhotoActivity> c =
                Robolectric.buildActivity(
                                PhotoActivity.class,
                                new Intent(app, PhotoActivity.class).putExtra("id", id))
                        .setup();
        try {
            button(c.get().getWindow().getDecorView(), "Место снимка на карте").performClick();
            Intent next = Shadows.shadowOf(c.get()).getNextStartedActivity();
            assertEquals(55.125, next.getDoubleExtra("mapLat", 0), .000001);
            ActivityController<MainActivity> map =
                    Robolectric.buildActivity(MainActivity.class, next).setup();
            try {
                java.lang.reflect.Field field = MainActivity.class.getDeclaredField("map");
                field.setAccessible(true);
                MapView v = (MapView) field.get(map.get());
                assertFalse(v.follow);
                assertEquals(55.125, v.centerLatitude(), .000001);
                assertEquals(37, v.centerLongitude(), .000001);
            } catch (Exception e) {
                throw new AssertionError(e);
            } finally {
                map.pause().stop().destroy();
            }
        } finally {
            c.pause().stop().destroy();
        }
    }
}

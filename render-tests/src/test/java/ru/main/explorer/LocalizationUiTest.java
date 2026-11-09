package ru.main.explorer;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.location.Location;
import android.os.*;
import android.util.Size;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.*;
import org.robolectric.shadows.*;
import static org.junit.Assert.*;

/** Synthetic fixtures only; these are JVM Android tests, not device acceptance. */
@RunWith(ParameterizedRobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LocalizationUiTest {
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    public static Collection<Object[]> languages() {
        return Arrays.asList(new Object[][] {{"en"}, {"ru"}});
    }

    final String language;
    Application app;

    public LocalizationUiTest(String language) {
        this.language = language;
    }

    @Before
    public void setup() throws Exception {
        field(MapDownloadService.class, "statusText", null);
        MapDownloadService.status = "";
        app = RuntimeEnvironment.getApplication();
        Language.select(app, language);
        MapNetwork.setEnabled(app, false);
        WalkService.running = false;
        MapDownloadService.running = false;
        Shadows.shadowOf(app)
                .grantPermissions(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.CAMERA,
                        Manifest.permission.POST_NOTIFICATIONS);
    }

    @After
    public void cleanup() {
        Language.select(app, "system");
        WalkService.running = false;
        MapDownloadService.running = false;
    }

    static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    static void field(Object target, String name, Object value) throws Exception {
        Field f =
                (target instanceof Class ? (Class<?>) target : target.getClass())
                        .getDeclaredField(name);
        f.setAccessible(true);
        f.set(target instanceof Class ? null : target, value);
    }

    static String allText(View view) {
        if (view.getWidth() == 0) {
            view.measure(
                    View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, 360, 640);
        }
        StringBuilder out = new StringBuilder();
        if (view instanceof TextView) out.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                out.append(allText(((ViewGroup) view).getChildAt(i)));
        return out.toString();
    }

    static Button button(View view, String text) {
        if (view instanceof Button && ((Button) view).getText().toString().equals(text))
            return (Button) view;
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                Button b = button(((ViewGroup) view).getChildAt(i), text);
                if (b != null) return b;
            }
        return null;
    }

    static void select(AlertDialog dialog, int index) {
        ListView list = dialog.getListView();
        list.performItemClick(null, index, list.getAdapter().getItemId(index));
    }

    Location fix() {
        Location l = new Location("synthetic");
        l.setLatitude(0);
        l.setLongitude(0);
        l.setAccuracy(12);
        l.setTime(1700000000000L);
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        return l;
    }

    long photo() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(0xffadc5a2);
        Paint p = new Paint();
        p.setColor(0xff416967);
        canvas.drawCircle(40, 30, 17, p);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bytes);
        return PhotoFiles.save(app, bytes.toByteArray(), fix(), 1700000000000L);
    }

    void syntheticAtlas() throws Exception {
        org.json.JSONArray elements = new org.json.JSONArray();
        for (int i = -4; i <= 4; i++) {
            org.json.JSONArray line = new org.json.JSONArray();
            line.put(new org.json.JSONObject().put("lat", -.004).put("lon", i * .001));
            line.put(new org.json.JSONObject().put("lat", .004).put("lon", i * .001 + .001));
            elements.put(
                    new org.json.JSONObject()
                            .put("tags", new org.json.JSONObject().put("highway", "residential"))
                            .put("geometry", line));
            org.json.JSONArray cross = new org.json.JSONArray();
            cross.put(new org.json.JSONObject().put("lat", i * .001).put("lon", -.004));
            cross.put(new org.json.JSONObject().put("lat", i * .001).put("lon", .004));
            elements.put(
                    new org.json.JSONObject()
                            .put("tags", new org.json.JSONObject().put("highway", "secondary"))
                            .put("geometry", cross));
        }
        org.json.JSONObject json =
                new org.json.JSONObject()
                        .put(
                                "mapnotes_bounds",
                                new org.json.JSONArray(new double[] {-.005, -.005, .005, .005}))
                        .put("elements", elements);
        File dir = new File(app.getFilesDir(), "regions");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        try (OutputStream out =
                new FileOutputStream(new File(dir, "localization-synthetic.json"))) {
            out.write(json.toString().getBytes("UTF-8"));
        }
    }

    void dismissIntro() {
        AlertDialog intro = ShadowAlertDialog.getLatestAlertDialog();
        if (intro != null && intro.isShowing()) intro.dismiss();
    }

    Bitmap screenshot(View root, String name, int width, int height) throws Exception {
        root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(out));
        File dir = new File("build/rendered");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        try (OutputStream file =
                new FileOutputStream(new File(dir, name + "-" + language + ".png"))) {
            assertTrue(out.compress(Bitmap.CompressFormat.PNG, 100, file));
        }
        return out;
    }

    @Test
    public void onboardingNavigationAboutAndMainCanvas() throws Exception {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).create();
        try {
            MainActivity a = c.get();
            AlertDialog intro = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(
                    allText(intro.getWindow().getDecorView())
                            .contains(Language.text(a, R.string.the_world_awaits_your_footsteps)));
            dismissIntro();
            View root = a.findViewById(android.R.id.content);
            assertNotNull(button(root, Language.text(a, R.string.my_photos)));
            assertNotNull(button(root, Language.text(a, R.string.walk_history)));
            MapView map = (MapView) field(a, "map");
            map.asyncRendering = false;
            map.online = false;
            map.illustrated = true;
            syntheticAtlas();
            map.reloadRegions();
            a.onLocationChanged(fix());
            ArrayList<double[]> points = new ArrayList<>();
            points.add(new double[] {0, 0, 1, 1700000000000L});
            points.add(new double[] {.00015, .0002, 1, 1700000010000L});
            map.update(points);
            a.refresh();
            ((java.util.concurrent.ExecutorService) field(a, "dataWorker"))
                    .submit(() -> {})
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Bitmap output = screenshot(root, "main", 360, 800);
            assertNotEquals(output.getPixel(5, 5), output.getPixel(180, 300));
            assertTrue(map.getHeight() > 150);
            a.menu();
            AlertDialog menu = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(
                    Language.text(a, R.string.about), menu.getListView().getAdapter().getItem(4));
            select(menu, 4);
            AlertDialog about = ShadowAlertDialog.getLatestAlertDialog();
            String text = allText(about.getWindow().getDecorView());
            assertTrue(text.contains("MapNotes · " + Language.version(a)));
            assertTrue(
                    text.contains(
                            Language.text(
                                    a,
                                    R.string
                                            .gps_accuracy_within_30_m_speed_up_to_4_5_m_s_with_additional_accu)));
            about.dismiss();
        } finally {
            c.destroy();
        }
    }

    @Test
    public void permissionAndDownloadDialogFamiliesUseSelectedLanguage() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).create();
        try {
            MainActivity a = c.get();
            dismissIntro();
            Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
            a.onRequestPermissionsResult(
                    7, new String[] {Manifest.permission.ACCESS_FINE_LOCATION}, new int[] {-1});
            AlertDialog denied = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(
                    Language.text(a, R.string.open_settings),
                    denied.getButton(-1).getText().toString());
            assertTrue(
                    allText(denied.getWindow().getDecorView())
                            .contains(
                                    Language.text(
                                            a,
                                            R.string
                                                    .precise_location_is_needed_to_reveal_small_areas_enable_it_in_app)));
            denied.dismiss();
            a.download();
            AlertDialog download = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(
                    Language.text(a, R.string.download),
                    download.getButton(-1).getText().toString());
            download.dismiss();
            a.selectArea();
            AlertDialog type = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(
                    Language.text(a, R.string.atlas_streets_and_buildings_faster),
                    type.getListView().getAdapter().getItem(0));
            type.dismiss();
        } finally {
            c.destroy();
        }
    }

    @Test
    public void cameraControlsGpsErrorsAndDimensionsAreLocalized() throws Exception {
        ActivityController<CameraActivity> c =
                Robolectric.buildActivity(CameraActivity.class).create();
        try {
            CameraActivity a = c.get();
            Button shutter = (Button) field(a, "shutter");
            assertEquals(
                    Language.text(a, R.string.take_a_photo_with_a_map_location),
                    shutter.getContentDescription().toString());
            a.capture();
            assertEquals(
                    Language.text(a, R.string.camera_is_not_ready_yet),
                    ((TextView) field(a, "status")).getText().toString());
            field(a, "jpegSizes", new Size[] {new Size(2000, 1500)});
            field(a, "selectedSize", new Size(2000, 1500));
            field(a, "active", true);
            field(a, "ready", true);
            a.selectSize();
            AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(
                    String.format(
                            Language.locale(a), Language.text(a, R.string.mp_2), 2000, 1500, 3.0),
                    d.getListView().getAdapter().getItem(0));
            d.dismiss();
            a.onLocationChanged(fix());
            assertTrue(((TextView) field(a, "geo")).getText().toString().contains("±12"));
            screenshot(a.findViewById(android.R.id.content), "camera", 320, 640);
        } finally {
            c.destroy();
        }
    }

    @Test
    public void photoNotesDatesUnitsAndExportControlsPreserveContent() throws Exception {
        long id = photo();
        String note = "Начать прогулку / Start walk · 12,34";
        try (Store store = new Store(app)) {
            store.saveNote(id, note);
        }
        ActivityController<PhotoActivity> c =
                Robolectric.buildActivity(
                                PhotoActivity.class,
                                new Intent(app, PhotoActivity.class).putExtra("id", id))
                        .create()
                        .start()
                        .resume();
        try {
            PhotoActivity a = c.get();
            assertEquals(note, a.note.getText().toString());
            assertEquals(
                    Language.text(a, R.string.save_to_gallery), a.gallery.getText().toString());
            String text = allText(a.findViewById(android.R.id.content));
            assertTrue(text.contains(Language.date(a, 1700000000000L)));
            assertTrue(
                    text.contains(
                            String.format(
                                    Language.locale(a),
                                    Language.text(a, R.string.gps_m),
                                    0.0,
                                    0.0,
                                    12.0)));
            assertNotNull(
                    button(
                            a.findViewById(android.R.id.content),
                            Language.text(a, R.string.save_note)));
            try (Store saved = new Store(app)) {
                ((PhotoActivity.ZoomPhoto) ((ViewGroup) a.note.getParent()).getChildAt(1))
                        .setImageBitmap(PhotoFiles.load(saved.photo(id).path, 2048));
            }
            screenshot(a.findViewById(android.R.id.content), "photo", 360, 800);
            a.note.setText(note + " ✓");
        } finally {
            c.pause().stop().destroy();
        }
        try (Store store = new Store(app)) {
            assertEquals(note + " ✓", store.photo(id).note);
        }
    }

    @Test
    public void galleryTimelineAndImportCrashFamilies() throws Exception {
        long first = photo();
        photo();
        try (Store s = new Store(app)) {
            s.saveNote(first, "Моя заметка / My note");
            long walk = s.startWalk(1700000000000L);
            long session = s.newWalkSession(walk);
            s.add(fix(), session);
            s.finishWalk(1700000060000L);
        }
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).create();
        try {
            MainActivity a = c.get();
            dismissIntro();
            try (Store s = new Store(app)) {
                a.showPhotos(s.photos());
            }
            AlertDialog gallery = ShadowAlertDialog.getLatestAlertDialog();
            String text = allText(gallery.getWindow().getDecorView());
            assertTrue(text.contains("Моя заметка / My note"));
            assertTrue(text.contains(Language.text(a, R.string.return_to_map)));
            screenshot(gallery.getWindow().getDecorView(), "gallery", 360, 640);
            gallery.dismiss();
            a.showWalks();
            AlertDialog history = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(1, history.getListView().getAdapter().getCount());
            select(history, 0);
            ((java.util.concurrent.ExecutorService) field(a, "dataWorker"))
                    .submit(() -> {})
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog detail = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(
                    button(
                            detail.getWindow().getDecorView(),
                            Language.text(a, R.string.show_walk_location_on_map)));
            detail.dismiss();
            MapImport.catalog(a);
            AlertDialog catalog = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(
                    Language.text(a, R.string.connect_file),
                    catalog.getButton(-3).getText().toString());
            catalog.dismiss();
            CrashReport.show(a);
            AlertDialog crash = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(Language.text(a, R.string.copy), crash.getButton(-1).getText().toString());
            assertEquals(
                    Language.text(a, R.string.no_crash_report_has_been_recorded_yet),
                    CrashReport.read(a));
            crash.dismiss();
        } finally {
            c.destroy();
        }
    }

    @Test
    public void mapDynamicStatesAndCanvasUseSelectedResources() throws Exception {
        MapView map = new MapView(Language.wrap(app));
        try {
            map.online = false;
            assertEquals(
                    Language.text(app, R.string.atlas_online_loading_paused),
                    map.satelliteStatus());
            map.illustrated = false;
            assertEquals(
                    Language.text(app, R.string.satellite_waiting_for_location),
                    map.satelliteStatus());
            map.setCurrent(fix(), false);
            assertEquals(
                    Language.text(app, R.string.revealing_the_map_requires_precise_gps_within_30_m),
                    map.satelliteStatus());
            map.setCurrent(fix(), true);
            assertEquals(
                    Language.text(app, R.string.no_offline_imagery_download_this_area_while_online),
                    map.satelliteStatus());
            screenshot(map, "map", 360, 360);
            assertEquals(
                    Language.text(app, R.string.invalid_coordinates),
                    Language.text(
                            app,
                            new GpsFilter()
                                    .accept(new GpsFilter.Fix(Double.NaN, 0, 5, 1, false), 1)));
        } finally {
            map.close();
        }
    }

    @Test
    public void walkServiceWithoutActivitySwitchesNotificationWithoutRestartOrDataLoss()
            throws Exception {
        ServiceController<WalkService> c = Robolectric.buildService(WalkService.class).create();
        try {
            WalkService service = c.get();
            service.onStartCommand(new Intent(app, WalkService.class), 0, 1);
            assertTrue(WalkService.running);
            service.onLocationChanged(fix());
            long walk;
            try (Store s = new Store(app)) {
                walk = s.openWalk().id;
                assertEquals(1, s.points().size());
            }
            NotificationManager manager =
                    (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            Notification before = Shadows.shadowOf(manager).getNotification(1);
            assertEquals(Language.text(app, R.string.stop), before.actions[0].title.toString());
            Language.select(app, language.equals("en") ? "ru" : "en");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Notification after = Shadows.shadowOf(manager).getNotification(1);
            assertEquals(Language.text(app, R.string.stop), after.actions[0].title.toString());
            assertEquals(
                    Language.text(app, R.string.mapnotes_walk_in_progress),
                    after.extras.getString(Notification.EXTRA_TITLE));
            assertEquals(
                    Language.format(app, R.string.walk_accuracy, 12),
                    WalkService.currentStatus(app));
            try (Store s = new Store(app)) {
                assertEquals(walk, s.openWalk().id);
                assertEquals(1, s.points().size());
            }
            service.onStartCommand(new Intent(app, WalkService.class).setAction("STOP"), 0, 2);
            Language.select(app, language);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(WalkService.running);
        } finally {
            c.destroy();
        }
    }

    @Test
    public void downloadNotificationRetainsProgressAcrossLocaleSwitch() throws Exception {
        ServiceController<MapDownloadService> c =
                Robolectric.buildService(MapDownloadService.class).create();
        try {
            MapDownloadService service = c.get();
            NotificationManager manager =
                    (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(
                    new NotificationChannel("maps", "Test", NotificationManager.IMPORTANCE_LOW));
            field(service, "notifications", manager);
            field(service, "foreground", true);
            MapDownloadService.running = true;
            service.message("map_section_progress", 3, 10, 4, 10, 3);
            Language.select(app, language.equals("en") ? "ru" : "en");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Notification n = Shadows.shadowOf(manager).getNotification(2);
            assertEquals(Language.text(app, R.string.pause), n.actions[0].title.toString());
            assertEquals(
                    Language.format(app, R.string.map_section_progress, 4, 10, 3),
                    n.extras.getString(Notification.EXTRA_TEXT));
            assertEquals(3, n.extras.getInt(Notification.EXTRA_PROGRESS));
            assertEquals(10, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX));
            assertTrue(MapDownloadService.running);
        } finally {
            c.destroy();
        }
    }

    @Test
    public void everyResourceCanBeFormattedInBothLanguages() throws Exception {
        for (Field f : R.string.class.getFields()) {
            int id = f.getInt(null);
            String value = Language.text(app, id);
            java.util.regex.Matcher matches =
                    java.util.regex.Pattern.compile(
                                    "%(?:(\\d+)\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([dfs])")
                            .matcher(value);
            ArrayList<Object> args = new ArrayList<>();
            int next = 0;
            while (matches.find()) {
                int index =
                        matches.group(1) == null ? next++ : Integer.parseInt(matches.group(1)) - 1;
                while (args.size() <= index) args.add(null);
                args.set(
                        index,
                        matches.group(2).equals("d")
                                ? Integer.valueOf(12)
                                : matches.group(2).equals("f")
                                        ? (Object) Double.valueOf(12.5)
                                        : "demo");
            }
            assertNotNull(String.format(Language.locale(app), value, args.toArray()));
        }
    }
}

package ru.main.explorer;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

public class MapDownloadService extends Service {
    public static volatile boolean running;
    public static volatile String status = "";
    public static volatile long generation;
    private static volatile StatusText statusText;
    private int lastDone, lastTotal;
    private boolean foreground;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener
            languageChanged = (prefs, key) -> ui.post(this::refreshLanguage);

    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Language.initialize(base);
    }

    public void onCreate() {
        super.onCreate();
        Language.initialize(this);
        getSharedPreferences(Language.PREFS, 0)
                .registerOnSharedPreferenceChangeListener(languageChanged);
    }

    synchronized void refreshLanguage() {
        if (foreground && notifications != null) {
            status = currentStatus(this);
            notifications.createNotificationChannel(
                    new NotificationChannel(
                            "maps",
                            Language.text(MapDownloadService.this, R.string.offline_map_downloads),
                            NotificationManager.IMPORTANCE_LOW));
            notifications.notify(2, notification(status, lastDone, lastTotal));
        }
    }

    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        refreshLanguage();
    }

    static String currentStatus(Context context) {
        StatusText text = statusText;
        if (text != null) return text.render(context);
        android.content.SharedPreferences p = preferences(context);
        String key = p.getString("status_key", "");
        if (!key.isEmpty())
            try {
                JSONArray a = new JSONArray(p.getString("status_args", "[]"));
                Object[] args = new Object[a.length()];
                for (int i = 0; i < args.length; i++) args[i] = a.get(i);
                return new StatusText(key, args).render(context);
            } catch (Exception ignored) {
            }
        return hasPending(context) ? Language.text(context, R.string.download_paused) : status;
    }

    synchronized void message(String key, int done, int total, Object... args) {
        statusText = new StatusText(key, args);
        progress(statusText.render(this), done, total);
    }

    private synchronized void publish(String text, int done, int total) {
        status = text;
        lastDone = done;
        lastTotal = total;
        if (foreground && notifications != null)
            notifications.notify(2, notification(text, done, total));
    }

    private volatile boolean cancel;
    private volatile HttpURLConnection connection;
    private Thread worker;
    private NotificationManager notifications;
    private boolean includeSatellite = true;

    public IBinder onBind(Intent i) {
        return null;
    }

    public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "CANCEL".equals(intent.getAction())) {
            cancel = true;
            if (connection != null) connection.disconnect();
            if (worker != null) worker.interrupt();
            else stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(
                new NotificationChannel(
                        "maps",
                        Language.text(MapDownloadService.this, R.string.offline_map_downloads),
                        NotificationManager.IMPORTANCE_LOW));
        statusText = new StatusText("starting_download");
        Notification n = notification(statusText.render(this), 0, 0);
        foreground = true;
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(2, n);
        running = true;
        cancel = false;
        double lat = intent.getDoubleExtra("lat", 0),
                lon = intent.getDoubleExtra("lon", 0),
                radius = intent.getDoubleExtra("radius", 1000);
        includeSatellite = intent.getBooleanExtra("satellite", true);
        worker = new Thread(() -> download(lat, lon, radius), "offline-map-download");
        worker.start();
        return START_NOT_STICKY;
    }

    Notification notification(String text, int done, int total) {
        PendingIntent open =
                PendingIntent.getActivity(
                        this,
                        0,
                        new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop =
                PendingIntent.getService(
                        this,
                        3,
                        new Intent(this, MapDownloadService.class).setAction("CANCEL"),
                        PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "maps")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(
                        Language.text(MapDownloadService.this, R.string.mapnotes_offline_map))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setProgress(total, done, total == 0)
                .addAction(
                        new Notification.Action.Builder(
                                        null,
                                        Language.text(MapDownloadService.this, R.string.pause),
                                        stop)
                                .build())
                .build();
    }

    void progress(String text, int done, int total) {
        publish(text, done, total);
    }

    void check() throws IOException {
        if (cancel || Thread.currentThread().isInterrupted())
            throw new IOException(
                    Language.text(MapDownloadService.this, R.string.download_cancelled));
    }

    void awaitNetwork() throws Exception {
        if (MapNetwork.enabled(this)) return;
        message("network_paused_tap_network_on_the_map", 0, 0);
        while (!MapNetwork.enabled(this)) {
            check();
            Thread.sleep(250);
        }
        check();
        message("resuming_download_of_saved_area", 0, 0);
    }

    byte[] fetch(String url, String body, int limit) throws Exception {
        check();
        awaitNetwork();
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        connection = h;
        h.setConnectTimeout(15000);
        h.setReadTimeout(body == null ? 25000 : 40000);
        MapNetwork.configure(h);
        h.setRequestProperty("User-Agent", "MapNotes/0.11 (Android; ru.main.explorer)");
        try {
            MapNetwork.register(this, h);
            if (body != null) {
                h.setRequestMethod("POST");
                h.setDoOutput(true);
                h.setRequestProperty(
                        "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                byte[] request = ("data=" + URLEncoder.encode(body, "UTF-8")).getBytes("UTF-8");
                h.setFixedLengthStreamingMode(request.length);
                try (OutputStream out = h.getOutputStream()) {
                    out.write(request);
                }
            }
            int code = h.getResponseCode();
            if (code == 429 || code == 406) throw new MapPack.RateLimitException();
            if (code >= 400 && code < 500 && code != 408)
                throw new MapPack.PermanentException(
                        Language.text(MapDownloadService.this, R.string.map_server_http) + code);
            if (code != 200)
                throw new IOException(
                        Language.text(MapDownloadService.this, R.string.map_server_http) + code);
            try (InputStream in = h.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] b = new byte[32768];
                int n;
                while ((n = in.read(b)) != -1) {
                    check();
                    out.write(b, 0, n);
                    if (out.size() > limit) throw new MapPack.TooLargeException();
                }
                if (h.getContentLengthLong() >= 0 && out.size() != h.getContentLengthLong())
                    throw new EOFException(
                            Language.text(
                                    MapDownloadService.this,
                                    R.string.server_interrupted_map_transfer));
                byte[] response = out.toByteArray();
                if (body != null)
                    try {
                        new JSONObject(new String(response, "UTF-8")).getJSONArray("elements");
                    } catch (JSONException malformed) {
                        throw new IOException(
                                Language.text(
                                        MapDownloadService.this,
                                        R.string.server_returned_incomplete_map_data),
                                malformed);
                    }
                return response;
            }
        } finally {
            MapNetwork.finish(h);
            connection = null;
        }
    }

    void save(File file, byte[] data) throws Exception {
        File temp = new File(file.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!temp.renameTo(file))
            throw new IOException(
                    Language.text(MapDownloadService.this, R.string.could_not_save_map));
    }

    public static android.content.SharedPreferences preferences(Context c) {
        return c.getSharedPreferences("map-download", 0);
    }

    public static boolean hasPending(Context c) {
        return preferences(c).getBoolean("pending", false);
    }

    public static Intent resumeIntent(Context c) {
        android.content.SharedPreferences p = preferences(c);
        return new Intent(c, MapDownloadService.class)
                .putExtra("lat", Double.longBitsToDouble(p.getLong("lat", 0)))
                .putExtra("lon", Double.longBitsToDouble(p.getLong("lon", 0)))
                .putExtra(
                        "radius",
                        Double.longBitsToDouble(p.getLong("radius", Double.doubleToLongBits(1000))))
                .putExtra("satellite", p.getBoolean("satellite", true));
    }

    void download(double lat, double lon, double radius) {
        try {
            RegionPlan plan = new RegionPlan(lat, lon, radius);
            preferences(this)
                    .edit()
                    .putBoolean("pending", true)
                    .putBoolean("satellite", includeSatellite)
                    .putLong("lat", Double.doubleToLongBits(lat))
                    .putLong("lon", Double.doubleToLongBits(lon))
                    .putLong("radius", Double.doubleToLongBits(radius))
                    .apply();
            new MapPack(
                            getFilesDir(),
                            plan,
                            new MapPack.Transfer() {
                                public byte[] fetch(String url, String query, int limit)
                                        throws Exception {
                                    if (query != null && url.equals(MapPack.ENDPOINT))
                                        return Overpass.fetch(
                                                endpoint -> {
                                                    check();
                                                    try {
                                                        return MapDownloadService.this.fetch(
                                                                endpoint, query, limit);
                                                    } catch (IOException failure) {
                                                        if (!MapNetwork.enabled(
                                                                MapDownloadService.this))
                                                            throw new MapNetwork.Paused();
                                                        if (cancel)
                                                            throw new MapPack.PausedException();
                                                        throw failure;
                                                    }
                                                });
                                    return MapDownloadService.this.fetch(url, query, limit);
                                }

                                public void check() throws IOException {
                                    MapDownloadService.this.check();
                                    if (getFilesDir().getUsableSpace() < 32L * 1024 * 1024)
                                        throw new IOException(
                                                Language.text(
                                                        MapDownloadService.this,
                                                        R.string
                                                                .low_storage_free_some_space_and_resume_the_download));
                                }

                                public void progress(String text, int done, int total) {
                                    MapDownloadService.this.progress(text, done, total);
                                }

                                public void progressMessage(
                                        String key, int done, int total, Object... args) {
                                    MapDownloadService.this.message(key, done, total, args);
                                }

                                public void changed() {
                                    generation = System.currentTimeMillis();
                                }

                                public void waitMillis(long millis) throws Exception {
                                    while (millis > 0) {
                                        check();
                                        if (!MapNetwork.enabled(MapDownloadService.this)) {
                                            awaitNetwork();
                                            return;
                                        }
                                        long step = Math.min(millis, 1000);
                                        Thread.sleep(step);
                                        millis -= step;
                                    }
                                }

                                public void validateImage(byte[] bytes) throws Exception {
                                    BitmapFactory.Options o = new BitmapFactory.Options();
                                    o.inJustDecodeBounds = true;
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                                    if (o.outWidth != 256 || o.outHeight != 256)
                                        throw new IOException(
                                                Language.text(
                                                        MapDownloadService.this,
                                                        R.string
                                                                .satellite_server_returned_an_invalid_image));
                                }
                            },
                            includeSatellite)
                    .run();
            preferences(this).edit().putBoolean("pending", false).apply();
            statusText =
                    new StatusText(
                            includeSatellite
                                    ? "download_complete_satellite"
                                    : "download_complete_atlas",
                            Math.round(radius / 500));
            status = statusText.render(this);
        } catch (Exception e) {
            statusText =
                    cancel
                            ? new StatusText("download_paused_tap_resume")
                            : new StatusText(
                                    "download_failure",
                                    e.getMessage() == null
                                            ? e.getClass().getSimpleName()
                                            : e.getMessage());
            status = statusText.render(this);
        } finally {
            synchronized (this) {
                foreground = false;
                android.content.SharedPreferences.Editor edit =
                        preferences(this).edit().putString("status", status);
                StatusText text = statusText;
                if (text != null)
                    edit.putString("status_key", text.key)
                            .putString(
                                    "status_args",
                                    new JSONArray(Arrays.asList(text.args)).toString());
                else edit.remove("status_key").remove("status_args");
                edit.apply();
                running = false;
                stopForeground(true);
            }
            stopSelf();
        }
    }

    public void onTimeout(int startId, int fgsType) {
        cancel = true;
        if (connection != null) connection.disconnect();
        if (worker != null) worker.interrupt();
        stopSelf();
    }

    public void onDestroy() {
        synchronized (this) {
            foreground = false;
        }
        getSharedPreferences(Language.PREFS, 0)
                .unregisterOnSharedPreferenceChangeListener(languageChanged);
        ui.removeCallbacksAndMessages(null);
        cancel = true;
        if (connection != null) connection.disconnect();
        if (worker != null && worker != Thread.currentThread()) worker.interrupt();
        running = false;
        super.onDestroy();
    }
}

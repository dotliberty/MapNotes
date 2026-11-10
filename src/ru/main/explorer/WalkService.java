package ru.main.explorer;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.location.*;
import android.os.*;

public class WalkService extends Service implements LocationListener {
    // True while the explicitly enabled GPS service monitors, including waiting inside zones.
    public static volatile boolean running = false;
    public static volatile boolean zoneMonitoring = false;
    public static volatile String status = "";
    private static volatile StatusText statusText = new StatusText("start_hint");
    private LocationManager manager;
    private Store store;
    private TripGpsFilter filter;
    private long walkId;
    private long session;
    private SpawnZoneStore zones;
    private Boolean pendingInside;
    private long pendingSince;
    private int pendingCount;
    private java.util.List<SpawnZone> previousZones = java.util.Collections.emptyList();
    private boolean foreground;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SharedPreferences.OnSharedPreferenceChangeListener languageChanged =
            (prefs, key) ->
                    ui.post(
                            () -> {
                                if (foreground) {
                                    status = currentStatus(this);
                                    ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                                            .notify(1, notification());
                                }
                            });

    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Language.initialize(base);
    }

    public void onCreate() {
        super.onCreate();
        Language.initialize(this);
        getSharedPreferences(Language.PREFS, 0)
                .registerOnSharedPreferenceChangeListener(languageChanged);
        store = new Store(this);
        zones = new SpawnZoneStore(this);
        filter = new TripGpsFilter();
        MotionRuntime.reset(this);
        session = System.currentTimeMillis();
        manager = (LocationManager) getSystemService(LOCATION_SERVICE);
    }

    static String currentStatus(Context context) {
        return statusText.render(context);
    }

    void setStatus(String key, Object... args) {
        statusText = new StatusText(key, args);
        status = currentStatus(this);
        if (foreground)
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .notify(1, notification());
    }

    Notification notification() {
        NotificationManager n = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        n.createNotificationChannel(
                new NotificationChannel(
                        "walk",
                        Language.text(this, R.string.walk_recording),
                        NotificationManager.IMPORTANCE_LOW));
        PendingIntent open =
                PendingIntent.getActivity(
                        this,
                        0,
                        new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop =
                PendingIntent.getService(
                        this,
                        1,
                        new Intent(this, WalkService.class).setAction("STOP"),
                        PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "walk")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(
                        Language.text(
                                this,
                                zoneMonitoring
                                        ? R.string.zone_monitoring
                                        : R.string.mapnotes_walk_in_progress))
                .setContentText(currentStatus(this))
                .setContentIntent(open)
                .addAction(
                        new Notification.Action.Builder(
                                        null, Language.text(this, R.string.stop), stop)
                                .build())
                .setOngoing(true)
                .build();
    }

    public int onStartCommand(Intent i, int flags, int id) {
        if (i == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (i != null && "STOP".equals(i.getAction())) {
            running = false;
            foreground = false;
            store.finishWalk(System.currentTimeMillis());
            walkId = 0;
            manager.removeUpdates(this);
            MotionRuntime.stop(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        filter = new TripGpsFilter();
        pendingInside = null;
        zoneMonitoring = !zones.list().isEmpty();
        Notification notification = notification();
        foreground = true;
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        else startForeground(1, notification);
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
            if (zones.list().isEmpty()) beginWalk();
            running = true;
            MotionRuntime.start(this);
            setStatus("finding_precise_gps");
        } catch (Exception e) {
            setStatus("gps_unavailable_enable_location");
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    public void onLocationChanged(Location l) {
        if (!running) return;
        if (!l.hasAccuracy()) {
            setStatus("gps_accuracy_unavailable");
            return;
        }
        String why = filter.accept(l, SystemClock.elapsedRealtimeNanos());
        if (why == null) {
            try {
                java.util.List<SpawnZone> snapshot = zones.list();
                if (!snapshot.equals(previousZones)) {
                    pendingInside = null;
                    previousZones = snapshot;
                }
                zoneMonitoring = !snapshot.isEmpty();
                if (!snapshot.isEmpty()) {
                    boolean inside = false;
                    for (SpawnZone zone : snapshot)
                        if (zone.contains(l.getLatitude(), l.getLongitude())) inside = true;
                    if (pendingInside == null
                            || pendingInside != inside
                            || filter.getBreakSegment()) {
                        pendingInside = inside;
                        pendingSince = l.getElapsedRealtimeNanos();
                        pendingCount = 1;
                    } else pendingCount++;
                    // Three accepted fixes over five seconds suppress single-fix boundary jitter.
                    if (pendingCount < 3
                            || l.getElapsedRealtimeNanos() - pendingSince < 5_000_000_000L) {
                        setStatus("zone_confirming");
                        return;
                    }
                    if (inside) {
                        store.finishWalk(System.currentTimeMillis());
                        walkId = 0;
                        setStatus("zone_inside");
                        return;
                    }
                } else pendingInside = null;
                if (walkId == 0) beginWalk();
                if (filter.getBreakSegment()) session = store.newWalkSession(walkId);
                String type =
                        MotionRuntime.type(this, l.hasSpeed() ? l.getSpeed() : Double.NaN).name();
                boolean stopped =
                        (MotionRuntime.stopped() && (!l.hasSpeed() || l.getSpeed() < 1f))
                                || (l.hasSpeed()
                                        && l.hasSpeedAccuracy()
                                        && l.getSpeedAccuracyMetersPerSecond() <= .5f
                                        && l.getSpeed() <= .3f);
                store.addMovement(l, session, type, MotionRuntime.source(), stopped);
                setStatus("walk_accuracy", Math.round(l.getAccuracy()));
            } catch (Exception e) {
                setStatus("could_not_save_location");
                stopSelf();
            }
        } else setStatus(why);
    }

    private void beginWalk() {
        walkId = store.startWalk(System.currentTimeMillis());
        session = store.newWalkSession(walkId);
    }

    public void onProviderDisabled(String p) {
        setStatus("enable_gps_in_device_settings");
    }

    public void onProviderEnabled(String p) {
        setStatus("finding_precise_gps");
    }

    public void onStatusChanged(String p, int s, Bundle b) {}

    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        if (foreground) {
            status = currentStatus(this);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .notify(1, notification());
        }
    }

    public IBinder onBind(Intent i) {
        return null;
    }

    public void onDestroy() {
        foreground = false;
        MotionRuntime.stop(this);
        getSharedPreferences(Language.PREFS, 0)
                .unregisterOnSharedPreferenceChangeListener(languageChanged);
        ui.removeCallbacksAndMessages(null);
        manager.removeUpdates(this);
        store.close();
        zones.close();
        running = false;
        super.onDestroy();
    }
}

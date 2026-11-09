package ru.main.explorer;

import android.content.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** Shared persisted pause for foreground map requests and the offline service. */
final class MapNetwork {
    private static final Set<HttpURLConnection> active = new HashSet<>();
    private static final java.util.concurrent.ExecutorService closing =
            java.util.concurrent.Executors.newCachedThreadPool(
                    r -> {
                        Thread thread = new Thread(r, "map-disconnect");
                        thread.setDaemon(true);
                        return thread;
                    });

    static boolean enabled(Context c) {
        return c.getSharedPreferences("map-view", 0).getBoolean("online", true);
    }

    static void setEnabled(Context c, boolean enabled) {
        HttpURLConnection[] disconnect;
        synchronized (active) {
            c.getSharedPreferences("map-view", 0).edit().putBoolean("online", enabled).commit();
            disconnect =
                    enabled ? new HttpURLConnection[0] : active.toArray(new HttpURLConnection[0]);
            if (!enabled) active.clear();
        }
        for (HttpURLConnection h : disconnect) closing.execute(h::disconnect);
    }

    static void cancel(Collection<HttpURLConnection> connections) {
        HttpURLConnection[] snapshot;
        synchronized (connections) {
            snapshot = connections.toArray(new HttpURLConnection[0]);
            connections.clear();
        }
        synchronized (active) {
            active.removeAll(Arrays.asList(snapshot));
        }
        for (HttpURLConnection h : snapshot) closing.execute(h::disconnect);
    }

    static void register(Context c, HttpURLConnection h) throws IOException {
        synchronized (active) {
            if (!enabled(c)) throw new Paused();
            active.add(h);
        }
    }

    static void finish(HttpURLConnection h) {
        synchronized (active) {
            active.remove(h);
        }
        h.disconnect();
    }

    static final class Paused extends MapPack.PausedException {
        Paused() {
            super();
        }
    }

    static void configure(HttpURLConnection h) {
        h.setRequestProperty("Connection", "close");
        h.setRequestProperty("Accept-Encoding", "identity");
        h.setUseCaches(false);
    }
}

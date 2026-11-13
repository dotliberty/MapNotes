package ru.main.explorer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Поколение загрузок меняется сразу; ожидание старого IO и удаление идут только в worker. */
final class DownloadedMaps {
    private DownloadedMaps() {}

    private static final Object stateLock = new Object();
    private static final ReentrantReadWriteLock files = new ReentrantReadWriteLock();
    private static final ExecutorService cleaner = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<Listener, Boolean> listeners = new WeakHashMap<>();
    private static final WeakHashMap<OfflineAtlas, Boolean> atlases = new WeakHashMap<>();
    private static final WeakHashMap<RevealCache, Boolean> reveals = new WeakHashMap<>();
    private static volatile long generation;
    static volatile boolean clearing;
    private static volatile Result result;

    interface Listener {
        void downloadedMapsChanged();
    }

    interface Write {
        void run() throws IOException;
    }

    static final class Result {
        final boolean success;
        final int errors;

        Result(int errors) {
            this.errors = errors;
            success = errors == 0;
        }
    }

    static long generation() {
        return generation;
    }

    static boolean current(long expected) {
        return !clearing && expected == generation;
    }

    static void observe(Listener listener) {
        synchronized (stateLock) {
            listeners.put(listener, true);
        }
    }

    static void forget(Listener listener) {
        synchronized (stateLock) {
            listeners.remove(listener);
        }
    }

    static void register(OfflineAtlas atlas) {
        synchronized (stateLock) {
            atlases.put(atlas, true);
        }
    }

    static void forget(OfflineAtlas atlas) {
        synchronized (stateLock) {
            atlases.remove(atlas);
        }
    }

    static void register(RevealCache cache) {
        synchronized (stateLock) {
            reveals.put(cache, true);
        }
    }

    static void forget(RevealCache cache) {
        synchronized (stateLock) {
            reveals.remove(cache);
        }
    }

    static void retire(OfflineAtlas atlas) {
        if (atlas != null) cleaner.execute(atlas::close);
    }

    static final class Lease implements AutoCloseable {
        private boolean closed;

        private Lease() {}

        public void close() {
            if (!closed) {
                closed = true;
                files.readLock().unlock();
            }
        }
    }

    static Lease enter(long expected) throws IOException {
        if (!current(expected)) throw new IOException("Map download generation expired");
        files.readLock().lock();
        if (!current(expected)) {
            files.readLock().unlock();
            throw new IOException("Map download generation expired");
        }
        return new Lease();
    }

    static void write(long expected, Write write) throws IOException {
        try (Lease ignored = enter(expected)) {
            write.run();
        }
    }

    /** Только короткая публикация памяти; никакого file IO под stateLock. */
    static boolean publish(long expected, Runnable update) {
        synchronized (stateLock) {
            if (!current(expected)) return false;
            update.run();
            return true;
        }
    }

    static boolean beginClear() {
        synchronized (stateLock) {
            if (clearing) return false;
            clearing = true;
            generation++;
            result = null;
            return true;
        }
    }

    static boolean requestClear(Context context) {
        if (!beginClear()) return false;
        Context app = context.getApplicationContext();
        notifyListeners();
        cleaner.execute(() -> performClear(app));
        return true;
    }

    static Result lastResult(Context context) {
        Result known = result;
        if (known != null || clearing) return known;
        // Маркер прерванной операции переживает процесс, а не только Activity.
        return context.getSharedPreferences("downloaded-maps", 0).getBoolean("incomplete", false)
                ? new Result(1)
                : null;
    }

    static void dismissResult(Context context) {
        if (clearing) return;
        result = null;
        context.getSharedPreferences("downloaded-maps", 0).edit().remove("incomplete").apply();
    }

    private static void notifyListeners() {
        main.post(
                () -> {
                    ArrayList<Listener> copy;
                    synchronized (stateLock) {
                        copy = new ArrayList<>(listeners.keySet());
                    }
                    for (Listener listener : copy) listener.downloadedMapsChanged();
                });
    }

    private static void performClear(Context context) {
        int errors = 0;
        try {
            if (!context.getSharedPreferences("downloaded-maps", 0)
                    .edit()
                    .putBoolean("incomplete", true)
                    .commit()) throw new IOException("Cannot record map cleanup state");
            if (!MapNetwork.setEnabled(context, false))
                throw new IOException("Cannot pause map network");
            MapDownloadService.cancelForClear();
            ArrayList<OfflineAtlas> oldAtlases;
            ArrayList<RevealCache> oldReveals;
            synchronized (stateLock) {
                oldAtlases = new ArrayList<>(atlases.keySet());
                oldReveals = new ArrayList<>(reveals.keySet());
            }
            for (OfflineAtlas atlas : oldAtlases)
                if (atlas.downloadGeneration != generation) atlas.close();
            // Только worker ждёт начатые записи/импорты/запросы стен и транзакции reveal.
            files.writeLock().lock();
            try {
                for (RevealCache cache : oldReveals)
                    if (cache.downloadGeneration != generation) cache.close();
                try {
                    clear(context);
                } catch (IOException failure) {
                    errors++;
                }
                try {
                    MapDownloadService.clearStatus(context);
                } catch (IOException failure) {
                    errors++;
                }
            } finally {
                files.writeLock().unlock();
            }
        } catch (Exception failure) {
            errors++;
        }
        try {
            if (!context.getSharedPreferences("downloaded-maps", 0)
                    .edit()
                    .putBoolean("incomplete", errors != 0)
                    .commit()) errors++;
        } catch (RuntimeException failure) {
            errors++;
        }
        synchronized (stateLock) {
            result = new Result(errors);
            clearing = false;
        }
        notifyListeners();
    }

    static void clear(Context context) throws IOException {
        IOException failure = null;
        for (String name : new String[] {"packs", "regions", "visibility", "satellite"}) {
            for (String suffix : new String[] {"", ".part"})
                try {
                    remove(new File(context.getFilesDir(), name + suffix));
                } catch (IOException error) {
                    failure = error;
                }
        }
        File[] cached = context.getCacheDir().listFiles();
        if (cached == null) failure = new IOException("Cannot list map cache");
        else
            for (File file : cached) {
                String name = file.getName();
                if (!name.startsWith("sat-")
                        && !name.matches("[0-9]+_[0-9]+_[0-9]+\\.png(?:\\.part)?")
                        && !name.matches("atlas-.*\\.(?:geometry|pass|points)(?:\\.part)?")
                        && !name.matches("reveal-v1\\.db(?:-journal|-wal|-shm)?")) continue;
                try {
                    remove(file);
                } catch (IOException error) {
                    failure = error;
                }
            }
        if (failure != null) throw failure;
    }

    private static void remove(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) {
            if (!file.delete()) throw new IOException("Cannot delete downloaded map link");
            return;
        }
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot list downloaded map directory");
            IOException failure = null;
            for (File child : children)
                try {
                    remove(child);
                } catch (IOException error) {
                    failure = error;
                }
            if (failure != null) throw failure;
        }
        if (!file.delete()) throw new IOException("Cannot delete downloaded map file");
    }

    static void awaitIdle() throws Exception {
        cleaner.submit(() -> {}).get(45, TimeUnit.SECONDS);
    }
}

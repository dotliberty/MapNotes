package ru.main.explorer;

/** Ограниченный локальный trace: только категории и числа, без координат, путей и названий. */
final class MapTrace {
    enum SourceVariant {
        DETAIL,
        OVERVIEW,
        WALLS
    }

    enum SourceState {
        QUEUED,
        IMPORTING,
        READY,
        MISSING,
        FAILED
    }

    interface SourceProvider {
        void appendSourceStates(StringBuilder text, long nanos);
    }

    private static final java.util.WeakHashMap<SourceProvider, Boolean> sources =
            new java.util.WeakHashMap<>();

    static synchronized void registerSources(SourceProvider provider) {
        sources.put(provider, Boolean.TRUE);
    }

    static synchronized void unregisterSources(SourceProvider provider) {
        sources.remove(provider);
    }

    static void sourceState(
            StringBuilder text,
            long nanos,
            long token,
            SourceVariant variant,
            SourceState state,
            long revision) {
        // seq=0: актуальный снимок, не событие из вытесняемого кольца.
        text.append("0,")
                .append(nanos)
                .append(",0,SOURCE,STATE,")
                .append(token)
                .append(',')
                .append(variant.ordinal() + 1)
                .append(',')
                .append(state.ordinal() + 1)
                .append(',')
                .append(revision)
                .append('\n');
    }

    enum Lane {
        INPUT,
        FRAME,
        SOURCE,
        IMPORT,
        TILE,
        VIEWPORT,
        LINE,
        MASK,
        COLOR,
        MONITOR,
        WALL
    }

    enum Event {
        INPUT,
        DRAW,
        QUEUE,
        START,
        CANCEL,
        PUBLISH,
        OOM,
        ERROR,
        LOCK,
        SCAN,
        REPLAY,
        FINISH,
        STATE,
        INVALIDATE
    }

    static final int CAPACITY = 2048;
    private static final long[][] rows = new long[CAPACITY][9];
    private static long sequence;
    private static int size;

    static synchronized void record(Lane lane, Event event, long token, long a, long b, long c) {
        long[] row = rows[(int) (sequence % CAPACITY)];
        row[0] = ++sequence;
        row[1] = System.nanoTime();
        row[2] = Thread.currentThread().getId();
        row[3] = lane.ordinal();
        row[4] = event.ordinal();
        row[5] = token;
        row[6] = a;
        row[7] = b;
        row[8] = c;
        size = Math.min(CAPACITY, size + 1);
    }

    static long id(Object object) {
        return object == null ? 0 : System.identityHashCode(object);
    }

    static String snapshot() {
        StringBuilder text =
                new StringBuilder("MapNotes trace v1\nseq,nanos,thread,lane,event,token,a,b,c\n");
        java.util.ArrayList<SourceProvider> providers;
        synchronized (MapTrace.class) {
            for (long n = sequence - size; n < sequence; n++) {
                long[] row = rows[(int) (n % CAPACITY)];
                text.append(row[0])
                        .append(',')
                        .append(row[1])
                        .append(',')
                        .append(row[2])
                        .append(',')
                        .append(Lane.values()[(int) row[3]])
                        .append(',')
                        .append(Event.values()[(int) row[4]]);
                for (int i = 5; i < row.length; i++) text.append(',').append(row[i]);
                text.append('\n');
            }
            providers = new java.util.ArrayList<>(sources.keySet());
        }
        // Не держим monitor trace при чтении текущих cache/queue states.
        long nanos = System.nanoTime();
        for (SourceProvider provider : providers) provider.appendSourceStates(text, nanos);
        Runtime runtime = Runtime.getRuntime();
        text.append("heap_used,")
                .append(runtime.totalMemory() - runtime.freeMemory())
                .append("\nheap_max,")
                .append(runtime.maxMemory())
                .append('\n');
        text.append("native_heap_used,")
                .append(android.os.Debug.getNativeHeapAllocatedSize())
                .append('\n');
        return text.toString();
    }
}

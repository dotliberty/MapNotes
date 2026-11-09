package ru.main.explorer;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.net.HttpURLConnection;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Standalone, GPS-free virtual image viewport. All provider/CPU/disk work is on one worker. */
public class ImageryTraceActivity extends Activity {
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final Handler ui = new Handler(Looper.getMainLooper());
    final Set<HttpURLConnection> connections = Collections.synchronizedSet(new HashSet<>());
    volatile long generation;
    volatile boolean inactive;
    Future<?> pending;
    boolean busy;
    OamImagery client;
    ImageryDrafts drafts;
    OamImagery.Raster raster;
    ImageryTrace.Candidate candidate;
    EditText bbox;
    boolean settingBounds;
    TextView status, sourceLabel;
    TraceView image;
    Button trace, save, export;
    String exportJson;
    int mode;
    final int[][] points = {{-1, -1}, {-1, -1}, {-1, -1}};
    ImageryCoverageMap coverage;
    boolean coverageMode;
    OamImagery.Search coverageResult;
    LinearLayout pixelControls;

    void showCoverage(boolean show) {
        cancel();
        coverageMode = show;
        coverage.setVisibility(show ? View.VISIBLE : View.GONE);
        image.setVisibility(show ? View.GONE : View.VISIBLE);
        bbox.setVisibility(show ? View.GONE : View.VISIBLE);
        if (pixelControls != null) pixelControls.setVisibility(show ? View.GONE : View.VISIBLE);
        updateButtons();
    }

    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Language.wrap(base));
    }

    String t(String key) {
        return Language.text(this, key);
    }

    Button button(LinearLayout parent, String key, Runnable action) {
        Button b = new Button(this);
        b.setText(t(key));
        b.setAllCaps(false);
        b.setTag(key);
        parent.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        parent.addView(r);
        return r;
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        client = OamImagery.network(this, connections);
        drafts = new ImageryDrafts(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(1);
        setContentView(root);
        UiInsets.apply(root, 0, 0, 0, 0);
        TextView title = new TextView(this);
        title.setText(t("imagery_title"));
        title.setTextSize(20);
        root.addView(title);
        ScrollView controls = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(1);
        controls.addView(body);
        root.addView(controls, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView warning = new TextView(this);
        warning.setText(t("imagery_hint"));
        body.addView(warning);
        bbox = new EditText(this);
        bbox.setTag("imagery_bbox");
        bbox.setSingleLine(false);
        bbox.setText(
                state != null ? state.getString("bbox", "") : getIntent().getStringExtra("bbox"));
        bbox.setHint("west,south,east,north");
        body.addView(bbox);
        if (bbox.getText().toString().trim().isEmpty())
            setCentre(
                    Math.max(-84.9, Math.min(84.9, getIntent().getDoubleExtra("latitude", 0))),
                    Math.max(
                            -179.99, Math.min(179.99, getIntent().getDoubleExtra("longitude", 0))));
        LinearLayout r = row(body);
        button(r, "imagery_search", this::search);
        button(
                r,
                "imagery_crop",
                () -> {
                    try {
                        OamImagery.Bounds b = OamImagery.Bounds.parse(bbox.getText().toString());
                        setCentre((b.s + b.n) / 2, (b.w + b.e) / 2);
                    } catch (Exception e) {
                        status.setText(t("imagery_bad_bbox"));
                    }
                });
        r = row(body);
        button(
                r,
                "imagery_demo",
                () -> {
                    cancel();
                    clearImage();
                    setCentre(55.814, 37.425);
                    status.setText(t("imagery_demo_notice"));
                });
        button(r, "imagery_reopen", this::reopen);
        button(
                r,
                "imagery_cancel",
                () -> {
                    cancel();
                    status.setText(t("imagery_cancelled"));
                });
        status = new TextView(this);
        status.setTag("imagery_status");
        status.setText(t("imagery_ready"));
        body.addView(status);
        sourceLabel = new TextView(this);
        sourceLabel.setTextIsSelectable(true);
        body.addView(sourceLabel);
        image = new TraceView();
        image.setTag("imagery_canvas");
        root.addView(image, new LinearLayout.LayoutParams(-1, 0, 2));
        coverage = new ImageryCoverageMap(this);
        root.addView(coverage, new LinearLayout.LayoutParams(-1, 0, 2));
        coverage.listener =
                new ImageryCoverageMap.Listener() {
                    public void inspect(List<OamCoverage> items) {
                        listCoverage(items);
                    }

                    public void moved() {
                        if (busy) {
                            cancel();
                            status.setText(t("imagery_cancelled"));
                        }
                    }
                };
        OamImagery.Bounds initial;
        try {
            initial = OamImagery.Bounds.parse(bbox.getText().toString());
        } catch (Exception invalid) {
            initial = new OamImagery.Bounds(-.001, -.001, .001, .001);
        }
        coverage.showPlace((initial.s + initial.n) / 2, (initial.w + initial.e) / 2);
        coverage.zoom = Math.max(1, Math.min(22, getIntent().getIntExtra("zoom", 15)));
        r = row(body);
        button(r, "imagery_coverage", () -> showCoverage(true));
        button(
                r,
                "imagery_sources",
                () -> {
                    if (coverageResult != null) listCoverage(coverageResult.items);
                });
        button(r, "imagery_load_selection", this::loadSelection);
        r = row(root);
        button(
                r,
                "imagery_zoom_out",
                () -> {
                    cancel();
                    coverage.changeZoom(-1);
                });
        button(
                r,
                "imagery_zoom_in",
                () -> {
                    cancel();
                    coverage.changeZoom(1);
                });
        button(
                r,
                "imagery_network",
                () -> {
                    cancel();
                    MapNetwork.setEnabled(this, !MapNetwork.enabled(this));
                    status.setText(
                            t(MapNetwork.enabled(this) ? "imagery_ready" : "imagery_paused"));
                });
        showCoverage(
                state != null ? state.getBoolean("coverage", true) : !getIntent().hasExtra("bbox"));
        if (state != null) {
            coverage.cx = state.getDouble("coverage_x", coverage.cx);
            coverage.cy = state.getDouble("coverage_y", coverage.cy);
            coverage.zoom = state.getInt("coverage_zoom", coverage.zoom);
        }
        pixelControls = new LinearLayout(this);
        pixelControls.setOrientation(1);
        root.addView(pixelControls);
        pixelControls.setVisibility(coverageMode ? View.GONE : View.VISIBLE);
        r = row(pixelControls);
        button(r, "imagery_start", () -> select(0));
        button(r, "imagery_end", () -> select(1));
        button(r, "imagery_sample", () -> select(2));
        r = row(pixelControls);
        trace = button(r, "imagery_trace", this::runTrace);
        save = button(r, "imagery_save", this::saveDraft);
        export = button(r, "imagery_export", this::exportDraft);
        updateButtons();
        bbox.addTextChangedListener(
                new android.text.TextWatcher() {
                    public void beforeTextChanged(
                            CharSequence s, int start, int count, int after) {}

                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        if (!settingBounds) {
                            cancel();
                            clearImage();
                            status.setText(t("imagery_ready"));
                        }
                    }

                    public void afterTextChanged(android.text.Editable s) {}
                });
        if (state != null) {
            String id = state.getString("draft");
            if (id != null) openDraft(id);
        }
    }

    String savedId;

    void setCentre(double lat, double lon) {
        double dy = 64 / 111320.0, dx = dy / Math.cos(Math.toRadians(lat));
        bbox.setText(new OamImagery.Bounds(lon - dx, lat - dy, lon + dx, lat + dy).csv());
        if (coverage != null) {
            coverage.showPlace(lat, lon);
            coverage.zoom = 15;
            coverage.invalidate();
        }
    }

    void select(int m) {
        if (busy || raster == null) return;
        mode = m;
        status.setText(
                t(
                        new String[] {
                                    "imagery_pick_start", "imagery_pick_end", "imagery_pick_sample"
                                }
                                [m]));
    }

    void updateButtons() {
        if (trace == null) return;
        trace.setEnabled(
                !coverageMode
                        && !busy
                        && raster != null
                        && points[0][0] >= 0
                        && points[1][0] >= 0
                        && points[2][0] >= 0);
        save.setEnabled(!coverageMode && !busy && candidate != null && savedId == null);
        export.setEnabled(!coverageMode && !busy && exportJson != null);
    }

    void clearImage() {
        raster = null;
        candidate = null;
        savedId = null;
        exportJson = null;
        for (int[] p : points) Arrays.fill(p, -1);
        if (image != null) image.invalidate();
        if (sourceLabel != null) sourceLabel.setText("");
        updateButtons();
    }

    void cancel() {
        generation++;
        if (pending != null) pending.cancel(true);
        pending = null;
        MapNetwork.cancel(connections);
        busy = false;
        updateButtons();
    }

    interface Job {
        Runnable run(long token) throws Exception;
    }

    void submit(String message, Job job) {
        cancel();
        final long token = generation;
        busy = true;
        status.setText(t(message));
        updateButtons();
        pending =
                worker.submit(
                        () -> {
                            try {
                                Runnable result = job.run(token);
                                ui.post(
                                        () -> {
                                            if (!inactive && generation == token) {
                                                busy = false;
                                                pending = null;
                                                result.run();
                                                updateButtons();
                                            }
                                        });
                            } catch (Exception e) {
                                ui.post(
                                        () -> {
                                            if (!inactive && generation == token) {
                                                busy = false;
                                                pending = null;
                                                status.setText(
                                                        t(
                                                                        !MapNetwork.enabled(this)
                                                                                ? "imagery_paused"
                                                                                : e
                                                                                                instanceof
                                                                                                CancellationException
                                                                                        ? "imagery_cancelled"
                                                                                        : "imagery_failure")
                                                                + (e
                                                                                instanceof
                                                                                CancellationException
                                                                        ? ""
                                                                        : "\n"
                                                                                + e.getClass()
                                                                                        .getSimpleName()
                                                                                + ": "
                                                                                + e.getMessage()));
                                                updateButtons();
                                            }
                                        });
                            }
                        });
    }

    boolean cancelled(long token) {
        return inactive || generation != token;
    }

    void searchCoverage() {
        final OamImagery.Bounds area;
        try {
            area = coverage.viewport();
        } catch (Exception e) {
            status.setText(t("imagery_coverage_extent"));
            return;
        }
        clearImage();
        coverage.selected = null;
        coverage.items = Collections.emptyList();
        coverageResult = null;
        coverage.searched = area;
        coverage.invalidate();
        submit(
                "imagery_searching",
                token -> {
                    OamImagery.Search found = client.search(area, () -> cancelled(token));
                    return () -> {
                        coverageResult = found;
                        coverage.items = found.items;
                        coverage.invalidate();
                        status.setText(
                                (found.returned == 0
                                                ? t("imagery_no_coverage")
                                                : t("imagery_coverage_legend")
                                                        + "\n"
                                                        + found.returned
                                                        + " / "
                                                        + found.rejected
                                                        + " "
                                                        + t("imagery_rejected"))
                                        + "\n"
                                        + t("imagery_search_limit")
                                        + "\n"
                                        + t(found.more ? "imagery_more_yes" : "imagery_more_no"));
                    };
                });
    }

    void listCoverage(List<OamCoverage> entries) {
        if (entries.isEmpty()) return;
        final long token = generation;
        String[] labels = new String[entries.size()];
        for (int i = 0; i < labels.length; i++) {
            OamCoverage entry = entries.get(i);
            labels[i] =
                    entry.label()
                            + "\n"
                            + t(entry.source != null ? "imagery_eligible" : "imagery_rejected")
                            + (entry.polygons.isEmpty()
                                    ? " · " + t("imagery_unknown_geometry")
                                    : "");
        }
        new AlertDialog.Builder(this)
                .setTitle(t("imagery_sources"))
                .setItems(
                        labels,
                        (d, i) -> {
                            if (!inactive && generation == token) inspectCoverage(entries.get(i));
                        })
                .setNegativeButton(t("cancel"), null)
                .show();
    }

    void inspectCoverage(OamCoverage entry) {
        final long token = generation;
        StringBuilder details =
                new StringBuilder(entry.label())
                        .append("\n")
                        .append(entry.producer)
                        .append("\n")
                        .append(entry.captured)
                        .append("\n")
                        .append(entry.license)
                        .append(" · ")
                        .append(entry.gsd)
                        .append(" m");
        if (entry.polygons.isEmpty()) details.append("\n").append(t("imagery_unknown_geometry"));
        for (OamCoverage.Reason reason : entry.reasons)
            details.append("\n• ")
                    .append(
                            t(
                                    "imagery_reason_"
                                            + reason.name().toLowerCase(java.util.Locale.ROOT)));
        if (entry.source != null) details.append("\n").append(t("imagery_fragment_hint"));
        AlertDialog.Builder dialog =
                new AlertDialog.Builder(this)
                        .setTitle(t("imagery_sources"))
                        .setMessage(details.toString())
                        .setNegativeButton(t("cancel"), null);
        if (entry.source != null)
            dialog.setPositiveButton(
                    t("imagery_choose"),
                    (d, i) -> {
                        if (!inactive && generation == token) {
                            cancel();
                            coverage.selected = entry;
                            coverage.invalidate();
                            sourceLabel.setText(entry.source.label());
                            status.setText(t("imagery_fragment_hint"));
                        }
                    });
        dialog.show();
    }

    void loadSelection() {
        if (!coverageMode || coverage.selected == null || coverage.selected.source == null) {
            status.setText(t("imagery_choose_first"));
            return;
        }
        final OamImagery.Bounds fragment;
        try {
            fragment = coverage.fragment();
            OamImagery.rasterUrl(coverage.selected.source, fragment);
        } catch (Exception e) {
            status.setText(t("imagery_fragment_outside"));
            return;
        }
        OamImagery.Source source = coverage.selected.source;
        clearImage();
        settingBounds = true;
        try {
            bbox.setText(fragment.csv());
        } finally {
            settingBounds = false;
        }
        showCoverage(false);
        load(source, fragment);
    }

    void search() {
        if (coverageMode) {
            searchCoverage();
            return;
        }
        final OamImagery.Bounds b;
        try {
            b = OamImagery.Bounds.parse(bbox.getText().toString());
            b.fragment();
        } catch (Exception e) {
            status.setText(t("imagery_bad_bbox"));
            return;
        }
        clearImage();
        submit(
                "imagery_searching",
                token -> {
                    OamImagery.Search found = client.search(b, () -> cancelled(token));
                    return () -> {
                        if (found.sources.isEmpty()) {
                            status.setText(
                                    t(
                                                    found.returned == 0
                                                            ? "imagery_no_coverage"
                                                            : "imagery_no_eligible")
                                            + (found.more ? "\n" + t("imagery_search_limit") : ""));
                            return;
                        }
                        status.setText(
                                t("imagery_select_source")
                                        + (found.more ? "\n" + t("imagery_search_limit") : ""));
                        String[] labels = new String[found.sources.size()];
                        for (int i = 0; i < labels.length; i++)
                            labels[i] = found.sources.get(i).label();
                        new AlertDialog.Builder(this)
                                .setTitle(t("imagery_select_source"))
                                .setItems(
                                        labels,
                                        (d, i) -> {
                                            if (!inactive && generation == token)
                                                load(found.sources.get(i), b);
                                        })
                                .setNegativeButton(t("cancel"), null)
                                .show();
                    };
                });
    }

    void load(OamImagery.Source source, OamImagery.Bounds bounds) {
        submit(
                "imagery_loading",
                token -> {
                    OamImagery.Raster r = client.load(source, bounds, () -> cancelled(token));
                    return () -> {
                        raster = r;
                        candidate = null;
                        sourceLabel.setText(source.label());
                        status.setText(t("imagery_pick_start"));
                        mode = 0;
                        image.invalidate();
                    };
                });
    }

    void runTrace() {
        if (busy || raster == null) return;
        final OamImagery.Raster r = raster;
        final int sx = points[0][0],
                sy = points[0][1],
                ex = points[1][0],
                ey = points[1][1],
                cx = points[2][0],
                cy = points[2][1];
        candidate = null;
        exportJson = null;
        savedId = null;
        submit(
                "imagery_tracing",
                token -> {
                    int[] pixels = new int[512 * 512];
                    r.bitmap.getPixels(pixels, 0, 512, 0, 0, 512, 512);
                    ImageryTrace.Candidate c =
                            ImageryTrace.trace(
                                    512,
                                    512,
                                    pixels,
                                    null,
                                    sx,
                                    sy,
                                    ex,
                                    ey,
                                    cx,
                                    cy,
                                    0,
                                    r.bounds.reference(),
                                    r.source.provenance(),
                                    () -> cancelled(token));
                    String json =
                            c != null
                                            && c.vertices.size() >= 2
                                            && c.vertices.size() <= ImageryDrafts.MAX_VERTICES
                                    ? ImageryDrafts.record(r, c).getJSONObject("feature").toString()
                                    : null;
                    return () -> {
                        candidate = json == null ? null : c;
                        exportJson = json;
                        status.setText(t(json == null ? "imagery_no_path" : "imagery_preview"));
                        image.invalidate();
                    };
                });
    }

    void saveDraft() {
        if (candidate == null || busy) return;
        OamImagery.Raster r = raster;
        ImageryTrace.Candidate c = candidate;
        submit(
                "imagery_saving",
                token -> {
                    if (cancelled(token)) throw new CancellationException();
                    String id = drafts.save(r, c);
                    ImageryDrafts.Draft verified = drafts.read(id);
                    return () -> {
                        savedId = id;
                        exportJson = verified.geojson;
                        status.setText(t("imagery_saved"));
                    };
                });
    }

    void reopen() {
        submit(
                "imagery_opening",
                token -> {
                    List<String> ids = drafts.list();
                    return () -> {
                        if (ids.isEmpty()) {
                            status.setText(t("imagery_no_drafts"));
                            return;
                        }
                        new AlertDialog.Builder(this)
                                .setTitle(t("imagery_reopen"))
                                .setItems(
                                        ids.toArray(new String[0]), (d, i) -> openDraft(ids.get(i)))
                                .setNegativeButton(t("cancel"), null)
                                .show();
                    };
                });
    }

    void openDraft(String id) {
        submit(
                "imagery_opening",
                token -> {
                    ImageryDrafts.Draft d = drafts.read(id);
                    return () -> {
                        showCoverage(false);
                        raster = d.raster;
                        candidate = d.candidate;
                        savedId = id;
                        exportJson = d.geojson;
                        settingBounds = true;
                        try {
                            bbox.setText(raster.bounds.csv());
                        } finally {
                            settingBounds = false;
                        }
                        sourceLabel.setText(raster.source.label());
                        int[][] p = {
                            {candidate.startX, candidate.startY},
                            {candidate.endX, candidate.endY},
                            {candidate.sampleX, candidate.sampleY}
                        };
                        for (int i = 0; i < 3; i++) System.arraycopy(p[i], 0, points[i], 0, 2);
                        status.setText(t("imagery_preview"));
                        image.invalidate();
                    };
                });
    }

    void exportDraft() {
        if (exportJson == null) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("application/geo+json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, "MapNotes-OAM-candidate.geojson");
        startActivityForResult(intent, 71);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 71
                && result == RESULT_OK
                && data != null
                && data.getData() != null
                && exportJson != null) {
            final android.net.Uri uri = data.getData();
            final String json = exportJson;
            submit(
                    "imagery_saving",
                    token -> {
                        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                            if (out == null) throw new IOException("No output");
                            out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }
                        byte[] expected = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        try (InputStream in = getContentResolver().openInputStream(uri)) {
                            if (in == null) throw new IOException("Cannot verify export");
                            for (int offset = 0; offset < expected.length; ) {
                                byte[] b = new byte[Math.min(8192, expected.length - offset)];
                                int count = in.read(b);
                                if (count < 0) throw new IOException("Truncated export");
                                for (int i = 0; i < count; i++)
                                    if (b[i] != expected[offset + i])
                                        throw new IOException("Export mismatch");
                                offset += count;
                            }
                            if (in.read() != -1) throw new IOException("Oversized export");
                        }
                        return () -> status.setText(t("imagery_exported"));
                    });
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("bbox", bbox.getText().toString());
        state.putBoolean("coverage", coverageMode);
        state.putDouble("coverage_x", coverage.cx);
        state.putDouble("coverage_y", coverage.cy);
        state.putInt("coverage_zoom", coverage.zoom);
        if (savedId != null) state.putString("draft", savedId);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onResume() {
        super.onResume();
        inactive = false;
    }

    @Override
    protected void onPause() {
        inactive = true;
        boolean wasBusy = busy;
        cancel();
        if (wasBusy) status.setText(t("imagery_cancelled"));
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        inactive = true;
        cancel();
        worker.shutdownNow();
        if (coverage != null) coverage.close();
        super.onDestroy();
    }

    final class TraceView extends View {
        final Paint paint = new Paint(3);
        final RectF area = new RectF();

        TraceView() {
            super(ImageryTraceActivity.this);
            setContentDescription(t("imagery_canvas_description"));
            setBackgroundColor(0xff202830);
        }

        void fit() {
            float side = Math.min(getWidth(), getHeight());
            area.set(
                    (getWidth() - side) / 2,
                    (getHeight() - side) / 2,
                    (getWidth() + side) / 2,
                    (getHeight() + side) / 2);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            fit();
            if (raster == null) return;
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawBitmap(raster.bitmap, null, area, paint);
            if (candidate != null) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3 * getResources().getDisplayMetrics().density);
                paint.setColor(0xffff00ff);
                Path path = new Path();
                boolean first = true;
                for (ImageryTrace.Vertex v : candidate.vertices) {
                    float x = area.left + (v.x + .5f) / 512 * area.width(),
                            y = area.top + (v.y + .5f) / 512 * area.height();
                    if (first) {
                        path.moveTo(x, y);
                        first = false;
                    } else path.lineTo(x, y);
                }
                canvas.drawPath(path, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            int[] colors = {Color.GREEN, Color.RED, Color.CYAN};
            for (int i = 0; i < 3; i++)
                if (points[i][0] >= 0) {
                    paint.setColor(colors[i]);
                    canvas.drawCircle(
                            area.left + (points[i][0] + .5f) / 512 * area.width(),
                            area.top + (points[i][1] + .5f) / 512 * area.height(),
                            6 * getResources().getDisplayMetrics().density,
                            paint);
                }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_UP) return true;
            fit();
            if (raster == null
                    || busy
                    || area.width() <= 0
                    || !area.contains(event.getX(), event.getY())) return true;
            int x = (int) ((event.getX() - area.left) / area.width() * 512),
                    y = (int) ((event.getY() - area.top) / area.height() * 512);
            if ((raster.bitmap.getPixel(x, y) >>> 24) != 255) {
                status.setText(t("imagery_transparent"));
                return true;
            }
            cancel();
            points[mode][0] = x;
            points[mode][1] = y;
            candidate = null;
            exportJson = null;
            savedId = null;
            status.setText(t("imagery_selected") + " " + (mode + 1) + ": " + x + ", " + y);
            updateButtons();
            invalidate();
            performClick();
            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}

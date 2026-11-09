package ru.main.explorer;

import android.app.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.graphics.*;
import java.text.*;
import java.util.*;

public class PhotoActivity extends Activity {
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Language.wrap(base));
    }

    Store store;
    long id;
    EditText note;
    Button gallery;
    TextView exportStatus;
    boolean exporting;

    public void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        id = getIntent().getLongExtra("id", -1);
        Store.Photo photo = store.photo(id);
        if (photo == null) {
            finish();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(1);
        root.setPadding(16, 30, 16, 25);
        root.setBackgroundColor(0xff101e1c);
        setContentView(root);
        UiInsets.apply(root, 16, 8, 16, 8);
        TextView title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setText(Language.text(this, R.string.photo_3) + Language.date(this, photo.created));
        title.setPadding(8, 16, 8, 16);
        root.addView(title);
        title.setContentDescription(Language.text(this, R.string.back_to_photos));
        title.setOnClickListener(v -> finish());
        ZoomPhoto image = new ZoomPhoto(this);
        image.setContentDescription(Language.text(this, R.string.photo_accessibility));
        root.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
        new Thread(
                        () -> {
                            Bitmap bitmap = PhotoFiles.load(photo.path, 2048);
                            runOnUiThread(
                                    () -> {
                                        if (!isDestroyed()) {
                                            if (bitmap != null) image.setImageBitmap(bitmap);
                                            else
                                                Toast.makeText(
                                                                this,
                                                                Language.text(
                                                                        this,
                                                                        R.string
                                                                                .photo_file_unavailable),
                                                                Toast.LENGTH_LONG)
                                                        .show();
                                        }
                                    });
                        })
                .start();
        TextView location = new TextView(this);
        location.setTextColor(0xffb8c8c0);
        location.setText(
                String.format(
                        Language.locale(this),
                        Language.text(this, R.string.gps_m),
                        photo.lat,
                        photo.lon,
                        photo.accuracy));
        root.addView(location);
        Button place = new Button(this);
        place.setText(Language.text(this, R.string.show_photo_location_on_map));
        root.addView(place);
        place.setOnClickListener(
                v ->
                        startActivity(
                                new android.content.Intent(this, MainActivity.class)
                                        .putExtra("mapLat", photo.lat)
                                        .putExtra("mapLon", photo.lon)));
        note = new EditText(this);
        note.setTextColor(Color.WHITE);
        note.setHintTextColor(0xffa8bbb0);
        note.setHint(Language.text(this, R.string.photo_note));
        note.setText(photo.note);
        note.setMinLines(2);
        note.setMaxLines(4);
        root.addView(note);
        Button save = new Button(this);
        save.setText(Language.text(this, R.string.save_note));
        root.addView(save);
        save.setOnClickListener(
                v -> {
                    store.saveNote(id, note.getText().toString());
                    Toast.makeText(
                                    this,
                                    Language.text(this, R.string.note_saved),
                                    Toast.LENGTH_SHORT)
                            .show();
                });
    }

    protected void onStart() {
        super.onStart();
        if (note != null && gallery == null) {
            LinearLayout root = (LinearLayout) note.getParent();
            TextView heading = (TextView) root.getChildAt(0);
            heading.setText(
                    Language.text(this, R.string.back_to_photos)
                            + "\n"
                            + Language.date(this, store.photo(id).created));
            heading.setMinHeight(AtlasUi.dp(heading, 48));
            heading.setFocusable(true);
            heading.setTooltipText(Language.text(this, R.string.back_to_photos));
            gallery = new Button(this);
            gallery.setText(Language.text(this, R.string.save_to_gallery));
            root.addView(gallery);
            exportStatus = new TextView(this);
            exportStatus.setTextColor(0xffb8c8c0);
            exportStatus.setText(
                    Language.text(
                            this,
                            R.string.original_stays_in_mapnotes_a_copy_appears_in_the_gallery));
            root.addView(exportStatus);
            gallery.setOnClickListener(v -> requestExport());
            root.setBackgroundColor(AtlasUi.INK);
            AtlasUi.apply(root);
        }
    }

    void requestExport() {
        if (exporting) return;
        if (GalleryExport.needsPermission(this)) {
            requestPermissions(
                    new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 71);
            return;
        }
        exportPhoto();
    }

    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 71) {
            if (results.length > 0
                    && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED)
                exportPhoto();
            else
                exportStatus.setText(
                        Language.text(
                                this,
                                R.string
                                        .not_saved_storage_permission_denied_original_stays_in_mapnotes));
        }
    }

    void exportPhoto() {
        if (exporting) return;
        Store.Photo photo = store.photo(id);
        if (photo == null) return;
        exporting = true;
        gallery.setEnabled(false);
        exportStatus.setText(Language.text(this, R.string.saving_to_gallery));
        android.content.Context app = getApplicationContext();
        new Thread(
                        () -> {
                            String message;
                            try {
                                GalleryExport.Result result = GalleryExport.save(app, photo);
                                message =
                                        result.alreadySaved
                                                ? Language.text(
                                                        this,
                                                        R.string
                                                                .already_saved_to_pictures_mapnotes_no_new_copy_created)
                                                : Language.text(
                                                        this,
                                                        R.string
                                                                .saved_to_gallery_pictures_mapnotes);
                            } catch (Exception error) {
                                message =
                                        Language.text(this, R.string.not_saved)
                                                + (error.getMessage() == null
                                                        ? Language.text(
                                                                this, R.string.gallery_error)
                                                        : error.getMessage())
                                                + Language.text(
                                                        this, R.string.original_stays_in_mapnotes);
                            }
                            final String status = message;
                            runOnUiThread(
                                    () -> {
                                        exporting = false;
                                        if (!isDestroyed()) {
                                            gallery.setEnabled(true);
                                            exportStatus.setText(status);
                                            Toast.makeText(this, status, Toast.LENGTH_LONG).show();
                                        }
                                    });
                        },
                        "photo-gallery-export")
                .start();
    }

    protected void onPause() {
        if (store != null && note != null) store.saveNote(id, note.getText().toString());
        super.onPause();
    }

    protected void onDestroy() {
        if (store != null) store.close();
        super.onDestroy();
    }

    static class ZoomPhoto extends ImageView {
        Matrix matrix = new Matrix();
        float zoom = 1;
        ScaleGestureDetector scale;
        GestureDetector gestures;
        android.animation.ValueAnimator resetMotion;
        final Runnable pendingFit = this::fit;

        ZoomPhoto(android.content.Context c) {
            super(c);
            setScaleType(ScaleType.MATRIX);
            scale =
                    new ScaleGestureDetector(
                            c,
                            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                                public boolean onScale(ScaleGestureDetector d) {
                                    float next =
                                            Math.max(1, Math.min(8, zoom * d.getScaleFactor()));
                                    matrix.postScale(
                                            next / zoom, next / zoom, d.getFocusX(), d.getFocusY());
                                    zoom = next;
                                    setImageMatrix(matrix);
                                    return true;
                                }
                            });
            gestures =
                    new GestureDetector(
                            c,
                            new GestureDetector.SimpleOnGestureListener() {
                                public boolean onDown(MotionEvent e) {
                                    return true;
                                }

                                public boolean onScroll(
                                        MotionEvent a, MotionEvent b, float dx, float dy) {
                                    if (zoom > 1) {
                                        matrix.postTranslate(-dx, -dy);
                                        setImageMatrix(matrix);
                                    }
                                    return true;
                                }

                                public boolean onDoubleTap(MotionEvent e) {
                                    fit();
                                    return true;
                                }
                            });
        }

        public void setImageBitmap(Bitmap b) {
            removeCallbacks(pendingFit);
            cancelReset();
            zoom = 1;
            super.setImageBitmap(b);
            post(pendingFit);
            if (b != null) AtlasMotion.reveal(this);
        }

        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            cancelReset();
            zoom = 1;
            fit();
        }

        void cancelReset() {
            if (resetMotion != null) {
                resetMotion.cancel();
                resetMotion = null;
            }
        }

        void fit() {
            if (getDrawable() == null || getWidth() == 0 || getHeight() == 0) return;
            cancelReset();
            float w = getDrawable().getIntrinsicWidth(), h = getDrawable().getIntrinsicHeight();
            if (w <= 0 || h <= 0) return;
            float s = Math.min(getWidth() / w, getHeight() / h);
            Matrix target = new Matrix();
            target.postScale(s, s);
            target.postTranslate((getWidth() - w * s) / 2, (getHeight() - h * s) / 2);
            if (zoom <= 1 || !AtlasMotion.enabled()) {
                matrix.set(target);
                zoom = 1;
                setImageMatrix(matrix);
                return;
            }
            final float startZoom = zoom;
            final float[] from = new float[9], to = new float[9], frame = new float[9];
            matrix.getValues(from);
            target.getValues(to);
            resetMotion = android.animation.ValueAnimator.ofFloat(0, 1);
            resetMotion.setDuration(260);
            resetMotion.setInterpolator(AtlasMotion.EASE);
            resetMotion.addUpdateListener(
                    a -> {
                        float t = (float) a.getAnimatedValue();
                        for (int i = 0; i < 9; i++) frame[i] = from[i] + (to[i] - from[i]) * t;
                        matrix.setValues(frame);
                        zoom = startZoom + (1 - startZoom) * t;
                        setImageMatrix(matrix);
                    });
            resetMotion.start();
        }

        protected void onDetachedFromWindow() {
            removeCallbacks(pendingFit);
            cancelReset();
            AtlasMotion.cancel(this);
            super.onDetachedFromWindow();
        }

        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) cancelReset();
            scale.onTouchEvent(e);
            if (!scale.isInProgress()) gestures.onTouchEvent(e);
            return true;
        }
    }
}

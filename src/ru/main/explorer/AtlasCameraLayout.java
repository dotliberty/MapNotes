package ru.main.explorer;

import android.content.Intent;
import android.graphics.*;
import android.view.*;
import android.widget.*;

/** Camera chrome only: sensor transform, capture and lifecycle remain in CameraActivity. */
final class AtlasCameraLayout {
    static void apply(
            CameraActivity owner,
            FrameLayout root,
            LinearLayout header,
            LinearLayout bottom,
            TextView captureLabel,
            CameraControls controls,
            Button size,
            Button shutter,
            TextView geo,
            TextView status) {
        header.setBackground(AtlasDecor.background(header, AtlasDecor.HEADER));
        header.setPadding(dp(header, 6), dp(header, 4), dp(header, 6), dp(header, 10));
        TextView brand = new TextView(owner);
        brand.setText("MapNotes");
        brand.setTextSize(27);
        AtlasDecor.heading(brand);
        header.addView(brand, 0, new LinearLayout.LayoutParams(-1, dp(brand, 38)));
        TextView section = new TextView(owner);
        section.setText(Language.text(owner, R.string.camera_atlas_header));
        section.setTextSize(10);
        section.setLetterSpacing(.22f);
        section.setTextColor(AtlasUi.GOLD);
        section.setGravity(Gravity.CENTER);
        header.addView(section, 1);
        ViewGroup old = (ViewGroup) controls.facing.getParent();
        header.removeView(old);
        old.removeView(controls.facing);
        old.removeView(controls.lens);
        old.removeView(controls.zoom);
        LinearLayout left = new LinearLayout(owner);
        left.setOrientation(1);
        left.addView(controls.facing, new LinearLayout.LayoutParams(dp(left, 48), dp(left, 48)));
        left.addView(controls.lens, new LinearLayout.LayoutParams(dp(left, 48), dp(left, 48)));
        FrameLayout.LayoutParams lp =
                new FrameLayout.LayoutParams(-2, -2, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        lp.leftMargin = dp(left, 8);
        root.addView(left, lp);
        AtlasDecor.circular(controls.facing);
        AtlasDecor.circular(controls.lens);
        LinearLayout right = new LinearLayout(owner);
        right.setOrientation(1);
        Button plus = new Button(owner), minus = new Button(owner);
        controls.zoomIn = plus;
        controls.zoomOut = minus;
        plus.setText("+");
        minus.setText("−");
        plus.setContentDescription(Language.text(owner, R.string.zoom_in));
        minus.setContentDescription(Language.text(owner, R.string.zoom_out));
        for (Button button : new Button[] {plus, controls.zoom, minus}) {
            AtlasUi.styleButton(button, false);
            right.addView(button, new LinearLayout.LayoutParams(dp(right, 52), dp(right, 48)));
        }
        AtlasDecor.circular(plus);
        AtlasDecor.circular(minus);
        AtlasDecor.button(controls.zoom, true);
        controls.zoom.setPadding(0, 0, 0, 0);
        controls.zoom.setTextSize(13);
        plus.setOnClickListener(v -> controls.scaleZoom(1.2f, owner.cameraGeneration()));
        minus.setOnClickListener(v -> controls.scaleZoom(1 / 1.2f, owner.cameraGeneration()));
        controls.iconify();
        FrameLayout.LayoutParams rp =
                new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        rp.rightMargin = dp(right, 8);
        root.addView(right, rp);
        header.removeView(geo);
        bottom.removeView(shutter);
        bottom.removeView(status);
        bottom.removeView(captureLabel);
        bottom.setBackground(AtlasDecor.background(bottom, AtlasDecor.PANEL));
        bottom.setPadding(dp(bottom, 14), dp(bottom, 12), dp(bottom, 14), dp(bottom, 12));
        geo.setTextSize(11);
        geo.setMaxLines(2);
        geo.setTextColor(AtlasUi.CREAM);
        geo.setPadding(dp(geo, 8), 0, dp(geo, 42), dp(geo, 3));
        bottom.addView(geo);
        status.setTextSize(10);
        status.setMaxLines(2);
        status.setTextColor(AtlasUi.MUTED);
        status.setPadding(dp(status, 8), 0, dp(status, 42), dp(status, 4));
        bottom.addView(status);
        LinearLayout row = new LinearLayout(owner);
        row.setGravity(Gravity.CENTER_VERTICAL);
        bottom.addView(row, new LinearLayout.LayoutParams(-1, dp(row, 88)));
        Button gallery = new Button(owner);
        gallery.setText(Language.text(owner, R.string.camera_atlas_gallery));
        AtlasUi.styleButton(gallery, false);
        gallery.setPadding(dp(gallery, 10), 0, dp(gallery, 10), 0);
        row.addView(gallery, new LinearLayout.LayoutParams(0, dp(row, 54), 1));
        LinearLayout.LayoutParams shot = new LinearLayout.LayoutParams(dp(row, 72), dp(row, 72));
        shot.setMargins(dp(row, 9), 0, dp(row, 9), 0);
        row.addView(shutter, shot);
        shutter.setBackground(AtlasDecor.background(shutter, AtlasDecor.SHUTTER));
        shutter.setTextColor(Color.TRANSPARENT);
        Button modes = new Button(owner);
        modes.setText(Language.text(owner, R.string.camera_atlas_modes));
        AtlasUi.styleButton(modes, false);
        modes.setPadding(dp(modes, 10), 0, dp(modes, 10), 0);
        row.addView(modes, new LinearLayout.LayoutParams(0, dp(row, 54), 1));
        modes.setOnClickListener(
                v ->
                        new AtlasUi.DialogBuilder(owner, v)
                                .setTitle(Language.text(owner, R.string.camera_atlas_modes))
                                .setItems(
                                        new String[] {
                                            Language.text(owner, R.string.camera_facing),
                                            Language.text(owner, R.string.camera_lens),
                                            Language.text(owner, R.string.camera_zoom)
                                        },
                                        (d, i) -> {
                                            if (i == 0) controls.chooseFacing();
                                            else if (i == 1) controls.chooseLens();
                                            else controls.chooseZoom();
                                        })
                                .setNegativeButton(Language.text(owner, R.string.close), null)
                                .show());
        gallery.setOnClickListener(
                v -> {
                    owner.startActivity(
                            new Intent(owner, MainActivity.class)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    .putExtra("openPhotos", true));
                    owner.finish();
                });
        captureLabel.setTypeface(Typeface.create("serif", Typeface.ITALIC));
        bottom.addView(captureLabel);
        AtlasDecor.button(size, true);
        View guides =
                new View(owner) {
                    final Paint line = new Paint(3);

                    protected void onDraw(Canvas canvas) {
                        line.setColor(0xffe6c98e);
                        line.setStyle(Paint.Style.STROKE);
                        line.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
                        float pad = dp(this, 13),
                                length = dp(this, 19),
                                top = header.getBottom() + pad,
                                base = bottom.getTop() - pad;
                        if (base <= top) return;
                        for (int i = 0; i < 4; i++) {
                            float x = i % 2 == 0 ? pad : getWidth() - pad,
                                    y = i < 2 ? top : base,
                                    dx = i % 2 == 0 ? length : -length,
                                    dy = i < 2 ? length : -length;
                            canvas.drawLine(x, y, x + dx, y, line);
                            canvas.drawLine(x, y, x, y + dy, line);
                        }
                    }
                };
        guides.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(guides, 1, new FrameLayout.LayoutParams(-1, -1));
    }

    static int dp(View v, int n) {
        return AtlasUi.dp(v, n);
    }
}

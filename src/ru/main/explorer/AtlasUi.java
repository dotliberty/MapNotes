package ru.main.explorer;

import android.graphics.Typeface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import android.view.*;
import android.widget.*;

/** Shared atlas palette; native accessible controls retain their semantics. */
final class AtlasUi {
    static final int INK = 0xff19283f,
            GOLD = 0xffdec38d,
            CREAM = 0xfff6e7bd,
            SURFACE = 0xff293b55,
            MUTED = 0xffd5cbb3;

    static void styleButton(Button b, boolean primary) {
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setMinHeight(dp(b, 48));
        b.setMinimumHeight(dp(b, 48));
        AtlasDecor.button(b, primary);
    }

    static void enter(View v) {
        AtlasMotion.enter(v);
    }

    static android.app.AlertDialog show(android.app.AlertDialog dialog) {
        dialog.show();
        if (dialog.getWindow() != null) {
            View decor = dialog.getWindow().getDecorView();
            dialog.getWindow()
                    .setBackgroundDrawable(AtlasDecor.background(decor, AtlasDecor.PANEL));
            apply(decor);
            int titleId = decor.getResources().getIdentifier("alertTitle", "id", "android");
            View heading = decor.findViewById(titleId);
            if (heading instanceof TextView) {
                TextView title = (TextView) heading;
                title.setTypeface(Typeface.create("serif", 0));
                title.setTextSize(26);
                title.setTextColor(GOLD);
                title.setPadding(dp(title, 12), dp(title, 10), dp(title, 52), dp(title, 12));
            }
            ListView list = dialog.getListView();
            if (list != null) {
                list.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                list.setDividerHeight(dp(list, 5));
                list.setPadding(dp(list, 10), dp(list, 4), dp(list, 10), dp(list, 6));
                list.setSelector(new android.graphics.drawable.ColorDrawable(0x33dec38d));
                if (list.getAdapter() != null && !(list.getAdapter() instanceof AtlasRows))
                    AtlasRows.install(list);
            }
            enter(decor);
        }
        return dialog;
    }

    static final class DialogBuilder extends android.app.AlertDialog.Builder {
        View anchor;
        android.content.DialogInterface.OnClickListener negative;

        @Override
        public DialogBuilder setNegativeButton(
                CharSequence text, android.content.DialogInterface.OnClickListener listener) {
            negative = listener;
            super.setNegativeButton(text, listener);
            return this;
        }

        DialogBuilder(android.content.Context context) {
            super(context, android.R.style.Theme_Material_Dialog_Alert);
        }

        DialogBuilder(android.content.Context context, View anchor) {
            this(context);
            this.anchor = anchor;
        }

        @Override
        public android.app.AlertDialog show() {
            android.app.AlertDialog dialog = AtlasUi.show(create());
            if (anchor != null) {
                anchorDialog(dialog, anchor);
                Button cancel = dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE);
                if (cancel != null)
                    cancel.setOnClickListener(
                            v -> {
                                if (Boolean.TRUE.equals(anchorPanel(dialog).getTag())) return;
                                if (negative != null)
                                    negative.onClick(
                                            dialog,
                                            android.content.DialogInterface.BUTTON_NEGATIVE);
                                if (dialog.isShowing()) dismiss(dialog);
                            });
                dialog.setOnKeyListener(
                        (d, key, event) -> {
                            if (key != KeyEvent.KEYCODE_BACK) return false;
                            if (event.getAction() == KeyEvent.ACTION_UP) dismiss(dialog);
                            return true;
                        });
            }
            return dialog;
        }
    }

    static View anchorPanel(android.app.AlertDialog dialog) {
        View decor = dialog.getWindow().getDecorView();
        int id = decor.getResources().getIdentifier("parentPanel", "id", "android");
        View panel = decor.findViewById(id);
        return panel == null ? decor : panel;
    }

    /** Full-screen transparent surface: an external pivot must not be clipped by a small window. */
    static void anchorDialog(android.app.AlertDialog dialog, View anchor) {
        if (dialog.getWindow() == null) return;
        android.view.Window window = dialog.getWindow();
        View decor = window.getDecorView();
        AtlasMotion.cancel(decor);
        View panel = anchorPanel(dialog);
        if (panel != decor && panel.getParent() instanceof FrameLayout) {
            window.setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            window.setLayout(-1, -1);
            FrameLayout parent = (FrameLayout) panel.getParent();
            parent.setClipChildren(false);
            parent.setClipToPadding(false);
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
            int margin = dp(panel, 12);
            p.setMargins(margin, margin, margin, margin);
            panel.setLayoutParams(p);
            panel.setBackground(AtlasDecor.background(panel, AtlasDecor.PANEL));
        }
        AtlasMotion.fromAnchor(panel, anchor);
    }

    static int dp(View v, int n) {
        return Math.round(n * v.getResources().getDisplayMetrics().density);
    }

    static void dismiss(android.content.DialogInterface target) {
        if (!(target instanceof android.app.AlertDialog)) {
            target.dismiss();
            return;
        }
        android.app.AlertDialog dialog = (android.app.AlertDialog) target;
        if (dialog.getWindow() == null || !AtlasMotion.enabled()) {
            dialog.dismiss();
            return;
        }
        View panel = anchorPanel(dialog);
        if (Boolean.TRUE.equals(panel.getTag())) return;
        panel.setTag(Boolean.TRUE);
        float x = panel.getScaleX(), y = panel.getScaleY(), alpha = panel.getAlpha();
        panel.setEnabled(false);
        AtlasMotion.frames(
                panel,
                180,
                t -> {
                    panel.setScaleX(x * (1 - .85f * t));
                    panel.setScaleY(y * (1 - .85f * t));
                    panel.setAlpha(alpha * (1 - t));
                },
                dialog::dismiss);
    }

    static GradientDrawable panel(int color, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        g.setStroke(2, stroke);
        return g;
    }

    static void apply(View v) {
        if (v instanceof Button) {
            Button b = (Button) v;
            b.setAllCaps(false);
            b.setTextColor(CREAM);
            b.setTextSize(16);
            b.setTypeface(Typeface.create("sans-serif-medium", 0));
            b.setMinHeight(dp(b, 48));
            b.setBackgroundTintList(null);
            b.setBackground(panel(INK, GOLD, dp(b, 18)));
            b.setPadding(dp(b, 16), dp(b, 10), dp(b, 16), dp(b, 10));
            if (b.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) b.getLayoutParams();
                p.setMargins(dp(b, 6), dp(b, 5), dp(b, 6), dp(b, 5));
                b.setLayoutParams(p);
            }
        } else if (v instanceof EditText) {
            EditText e = (EditText) v;
            e.setTextColor(INK);
            e.setHintTextColor(0xff687d72);
            e.setBackground(panel(CREAM, GOLD, dp(e, 12)));
            e.setPadding(dp(e, 14), dp(e, 10), dp(e, 14), dp(e, 10));
        } else if (v instanceof TextView) {
            TextView t = (TextView) v;
            t.setTextColor(CREAM);
            t.setTypeface(
                    Typeface.create(
                            t.getTextSize() / t.getResources().getDisplayMetrics().scaledDensity
                                            >= 22
                                    ? "serif"
                                    : "sans-serif",
                            0));
            t.setLineSpacing(dp(t, 2), 1);
        }
        if (v instanceof Button) {
            AtlasDecor.button((Button) v, false);
            TextView text = (TextView) v;
            text.setMaxLines(2);
            if (android.os.Build.VERSION.SDK_INT >= 26)
                text.setAutoSizeTextTypeUniformWithConfiguration(
                        11, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) apply(g.getChildAt(i));
        }
    }
}

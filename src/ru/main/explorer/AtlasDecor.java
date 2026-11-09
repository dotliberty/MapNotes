package ru.main.explorer;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import java.io.InputStream;

/** Resolution-independent atlas ornaments; never rasterized text or fake map content. */
final class AtlasDecor extends GradientDrawable {
    static final int PANEL = 0,
            HEADER = 1,
            BUTTON = 2,
            PARCHMENT = 3,
            ROUND = 4,
            SHUTTER = 5,
            THUMB = 6;
    final int kind;
    final float density;
    final Paint ink = new Paint(3);
    final Path shape = new Path();
    static Bitmap landscape, blossoms;
    static boolean loaded;

    AtlasDecor(Context context, int kind) {
        this.kind = kind;
        density = context.getResources().getDisplayMetrics().density;
        setColor(kind == PARCHMENT ? 0xffeddbb3 : AtlasUi.INK);
        load(context);
    }

    // A GradientDrawable ConstantState would recreate a plain gradient and discard our artwork
    // inside RippleDrawable.
    @Override
    public Drawable.ConstantState getConstantState() {
        return null;
    }

    @Override
    public Drawable mutate() {
        return this;
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    static synchronized void load(Context context) {
        if (loaded) return;
        loaded = true;
        landscape = read(context, "landscape");
        blossoms = read(context, "blossoms");
    }

    static Bitmap read(Context c, String name) {
        try (InputStream in = c.getAssets().open("atlas124/" + name + ".png")) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        invalidateSelf();
        return true;
    }

    @Override
    public boolean getPadding(Rect padding) {
        return false;
    }

    boolean enabled() {
        for (int state : getState()) if (state == android.R.attr.state_enabled) return true;
        return false;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        int save = canvas.save();
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(density, density);
        float w = bounds.width() / density, h = bounds.height() / density;
        ink.setStyle(Paint.Style.FILL);
        ink.setShader(null);
        ink.setAlpha(255);
        if (kind == ROUND || kind == SHUTTER) {
            round(canvas, w, h);
            canvas.restoreToCount(save);
            return;
        }
        boolean light = kind == PARCHMENT;
        float inset = kind == HEADER ? 0 : 3;
        path(w, h, inset);
        ink.setShader(
                new LinearGradient(
                        0,
                        0,
                        w,
                        h,
                        light
                                ? new int[] {0xfffff2ce, 0xffe4c99a, 0xffffefc9}
                                : new int[] {0xff15263d, 0xff263853, 0xff162338},
                        null,
                        Shader.TileMode.CLAMP));
        canvas.drawPath(shape, ink);
        ink.setShader(null);
        int clipped = canvas.save();
        canvas.clipPath(shape);
        texture(canvas, w, h, light);
        if (kind == HEADER) {
            bitmap(canvas, landscape, new RectF(Math.max(0, w - 128), 0, w, h));
            bitmap(canvas, blossoms, new RectF(0, 0, Math.min(88, w * .24f), h));
        }
        if (kind == PANEL) {
            int watermark = canvas.saveLayerAlpha(0, 0, w, h, 23);
            compass(canvas, 38, h - 42, 42);
            canvas.restoreToCount(watermark);
            if (w > 240 && h > 140) book(canvas, w - 40, 37, .58f);
        }
        canvas.restoreToCount(clipped);
        ink.setStyle(Paint.Style.STROKE);
        ink.setAlpha(255);
        ink.setStrokeWidth(2.2f);
        ink.setShader(
                new LinearGradient(
                        0,
                        0,
                        0,
                        h,
                        new int[] {0xffffe8a5, 0xff9e793f, 0xfff9dda0},
                        null,
                        Shader.TileMode.CLAMP));
        canvas.drawPath(shape, ink);
        path(w, h, inset + 3.5f);
        ink.setStrokeWidth(.7f);
        canvas.drawPath(shape, ink);
        ink.setShader(null);
        if (kind == PANEL) {
            star(canvas, w / 2, 5, 8);
            star(canvas, w / 2, h - 5, 6);
            corners(canvas, w, h);
        } else if (kind == HEADER) {
            ink.setColor(0xffdbc18b);
            ink.setStrokeWidth(.7f);
            canvas.drawLine(w * .22f, h - 5, w * .78f, h - 5, ink);
            star(canvas, w / 2, h - 5, 5);
        } else if (kind == BUTTON || kind == PARCHMENT) {
            star(canvas, 12, h / 2, 3);
            star(canvas, w - 12, h / 2, 3);
        } else if (kind == THUMB) star(canvas, w / 2, h - 3, 6);
        ink.setStyle(Paint.Style.FILL);
        canvas.restoreToCount(save);
    }

    void path(float w, float h, float s) {
        shape.reset();
        if (kind == BUTTON || kind == PARCHMENT) {
            float b = Math.min(15, h * .28f);
            shape.moveTo(s + b, s);
            shape.lineTo(w - s - b, s);
            shape.quadTo(w - s - 5, s + 1, w - s - 4, s + 8);
            shape.lineTo(w - s, h / 2);
            shape.lineTo(w - s - 4, h - s - 8);
            shape.quadTo(w - s - 5, h - s - 1, w - s - b, h - s);
            shape.lineTo(s + b, h - s);
            shape.quadTo(s + 5, h - s - 1, s + 4, h - s - 8);
            shape.lineTo(s, h / 2);
            shape.lineTo(s + 4, s + 8);
            shape.quadTo(s + 5, s + 1, s + b, s);
            shape.close();
        } else {
            float radius =
                    kind == HEADER ? 0 : Math.min(kind == THUMB ? 6 : 20, Math.min(w, h) / 4);
            shape.addRoundRect(s, s, w - s, h - s, radius, radius, Path.Direction.CW);
        }
    }

    void texture(Canvas c, float w, float h, boolean light) {
        ink.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 120; i++) {
            float x = ((i * 73 + 17) % 251) / 251f * w, y = ((i * 41 + 5) % 127) / 127f * h;
            ink.setColor(light ? 0x13947643 : 0x0d98aac7);
            c.drawCircle(x, y, i % 4 == 0 ? .8f : .35f, ink);
        }
        if (light) {
            ink.setColor(0x17a28151);
            ink.setStrokeWidth(.5f);
            for (int i = 0; i < 4; i++) {
                float y = h * (i + 1) / 5;
                c.drawLine(18, y, 30, y - 4, ink);
            }
        }
    }

    void bitmap(Canvas c, Bitmap image, RectF destination) {
        if (image != null) {
            ink.setColor(Color.WHITE);
            ink.setAlpha(215);
            c.drawBitmap(image, null, destination, ink);
            ink.setAlpha(255);
        }
    }

    void round(Canvas c, float w, float h) {
        float r = Math.min(w, h) / 2 - 4, cx = w / 2, cy = h / 2;
        ink.setShader(
                new LinearGradient(
                        0,
                        0,
                        0,
                        h,
                        kind == SHUTTER ? 0xffffedbd : 0xff35465e,
                        kind == SHUTTER ? 0xffd5b27a : 0xff162238,
                        Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, ink);
        ink.setShader(null);
        ink.setStyle(Paint.Style.STROKE);
        ink.setColor(0xffe9cb8d);
        ink.setStrokeWidth(1.8f);
        c.drawCircle(cx, cy, r, ink);
        ink.setStrokeWidth(.65f);
        c.drawCircle(cx, cy, r - 3, ink);
        star(c, cx, h - 3, 4);
        if (kind == SHUTTER) {
            ink.setColor(0xff8d7248);
            float radius = r * .45f;
            for (int i = 0; i < 6; i++) {
                c.save();
                c.rotate(i * 60, cx, cy);
                Path blade = new Path();
                blade.moveTo(cx - radius * .2f, cy - radius);
                blade.lineTo(cx + radius * .9f, cy - radius * .3f);
                blade.lineTo(cx + radius * .45f, cy + radius * .25f);
                blade.close();
                ink.setStyle(Paint.Style.FILL);
                c.drawPath(blade, ink);
                c.restore();
            }
        }
    }

    void corners(Canvas c, float w, float h) {
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(.6f);
        ink.setColor(0xffb99d67);
        for (int i = 0; i < 4; i++) {
            c.save();
            if (i % 2 == 1) {
                c.translate(w, 0);
                c.scale(-1, 1);
            }
            if (i >= 2) {
                c.translate(0, h);
                c.scale(1, -1);
            }
            Path curl = new Path();
            curl.moveTo(8, 49);
            curl.cubicTo(9, 17, 13, 12, 45, 10);
            curl.moveTo(10, 33);
            curl.cubicTo(20, 23, 18, 19, 24, 14);
            c.drawPath(curl, ink);
            c.restore();
        }
    }

    static void star(Canvas c, float x, float y, float r) {
        Paint p = new Paint(3);
        p.setColor(0xffeed49c);
        p.setStyle(Paint.Style.FILL);
        Path q = new Path();
        q.moveTo(x, y - r);
        q.lineTo(x + r * .3f, y - r * .25f);
        q.lineTo(x + r, y);
        q.lineTo(x + r * .3f, y + r * .25f);
        q.lineTo(x, y + r);
        q.lineTo(x - r * .3f, y + r * .25f);
        q.lineTo(x - r, y);
        q.lineTo(x - r * .3f, y - r * .25f);
        q.close();
        c.drawPath(q, p);
        p.setColor(0xff9b7b48);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(.5f);
        c.drawPath(q, p);
    }

    static void compass(Canvas c, float x, float y, float r) {
        Paint p = new Paint(3);
        p.setColor(0xffddc58f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(.7f);
        c.drawCircle(x, y, r * .75f, p);
        c.drawCircle(x, y, r * .67f, p);
        for (int i = 0; i < 8; i++) {
            c.save();
            c.rotate(i * 45, x, y);
            Path q = new Path();
            q.moveTo(x, y - r);
            q.lineTo(x + r * .15f, y);
            q.lineTo(x, y + r * .25f);
            q.lineTo(x - r * .15f, y);
            q.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(i % 2 == 0 ? 0xffdac18a : 0xff8b7957);
            c.drawPath(q, p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(0xffe6d1a2);
            c.drawPath(q, p);
            c.restore();
        }
    }

    void book(Canvas c, float x, float y, float scale) {
        c.save();
        c.translate(x, y);
        c.scale(scale, scale);
        c.rotate(18);
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(0xffd5bd8b);
        c.drawRect(-34, -28, 22, 30, ink);
        ink.setColor(0xff523e34);
        c.drawRoundRect(-27, -34, 32, 32, 5, 5, ink);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(1.5f);
        ink.setColor(0xffc6a66c);
        c.drawRoundRect(-23, -30, 28, 28, 3, 3, ink);
        compass(c, 2, -1, 20);
        ink.setStrokeWidth(2);
        c.drawLine(-25, -24, -25, 25, ink);
        c.restore();
    }

    static Drawable background(View view, int kind) {
        return new AtlasDecor(view.getContext(), kind);
    }

    static void button(Button view, boolean primary) {
        view.setBackgroundTintList(null);
        view.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(0x33ead39e),
                        background(view, primary ? PARCHMENT : BUTTON),
                        null));
        view.setTextColor(primary ? AtlasUi.INK : AtlasUi.CREAM);
        view.setTypeface(Typeface.create("serif", 0));
        view.setPadding(
                AtlasUi.dp(view, 22),
                AtlasUi.dp(view, 8),
                AtlasUi.dp(view, 22),
                AtlasUi.dp(view, 8));
        AtlasMotion.button(view);
    }

    static void circular(Button view) {
        view.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(0x44ead39e), background(view, ROUND), null));
        view.setPadding(0, 0, 0, 0);
        view.setTextSize(24);
        view.setTypeface(Typeface.create("serif", 0));
    }

    static void heading(TextView view) {
        view.setTypeface(Typeface.create("serif", Typeface.ITALIC));
        view.setTextColor(AtlasUi.CREAM);
        view.setGravity(Gravity.CENTER);
    }
}

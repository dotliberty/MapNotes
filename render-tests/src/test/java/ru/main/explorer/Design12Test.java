package ru.main.explorer;

import android.app.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.time.Duration;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import static org.junit.Assert.*;

/** Native Canvas evidence of the real Activity, never a parallel HTML mockup. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class Design12Test {
    void save(View root, String name, int width, int height) throws Exception {
        root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(image));
        File target = new File("build/rendered/1.2-" + name + ".png");
        target.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(target)) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
    }

    void activity(String name, int width, int height) throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Language.select(app, "ru");
        MapNetwork.setEnabled(app, false);
        org.robolectric.android.controller.ActivityController<MainActivity> c =
                Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity a = c.get();
            AlertDialog intro = ShadowAlertDialog.getLatestAlertDialog();
            if (intro != null) intro.dismiss();
            java.lang.reflect.Field f = MainActivity.class.getDeclaredField("map");
            f.setAccessible(true);
            MapView map = (MapView) f.get(a);
            map.online = false;
            save(a.findViewById(android.R.id.content), name, width, height);
            assertTrue("Useful map height", map.getHeight() >= 180);
            assertTrue("Useful map width", map.getWidth() >= 180);
            a.menu();
            AlertDialog menu = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(10, menu.getListView().getAdapter().getCount());
            assertTrue(
                    menu.getWindow().getDecorView().getBackground()
                            instanceof android.graphics.drawable.GradientDrawable);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
            assertEquals(1, menu.getWindow().getDecorView().getAlpha(), .001);
            save(
                    menu.getWindow().getDecorView(),
                    name + "-menu",
                    Math.min(width, 360),
                    Math.min(height, 640));
            menu.dismiss();
        } finally {
            c.pause().stop().destroy();
            Language.select(app, "system");
        }
    }

    @Test
    public void networkToggleStaysAwayFromRightHandActions() throws Exception {
        org.robolectric.android.controller.ActivityController<MainActivity> c =
                Robolectric.buildActivity(MainActivity.class).setup();
        try {
            java.lang.reflect.Field f = MainActivity.class.getDeclaredField("onlineButton");
            f.setAccessible(true);
            View toggle = (View) f.get(c.get());
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) toggle.getLayoutParams();
            assertEquals(Gravity.LEFT, p.gravity & Gravity.HORIZONTAL_GRAVITY_MASK);
        } finally {
            c.pause().stop().destroy();
        }
    }

    @Test
    public void compactPortraitAndActionPanel() throws Exception {
        activity("portrait", 360, 640);
    }

    @Test
    @Config(qualifiers = "land-mdpi")
    public void landscapeKeepsMapAndScrollableActions() throws Exception {
        activity("landscape", 640, 360);
    }

    @Test
    public void primaryAndSecondaryControlsHaveDifferentHierarchy() {
        Button primary = new Button(RuntimeEnvironment.getApplication()),
                secondary = new Button(RuntimeEnvironment.getApplication());
        AtlasUi.styleButton(primary, true);
        AtlasUi.styleButton(secondary, false);
        assertEquals(AtlasUi.INK, primary.getCurrentTextColor());
        assertEquals(AtlasUi.CREAM, secondary.getCurrentTextColor());
        assertTrue(primary.getMinimumHeight() >= 48);
        assertTrue(primary.getBackground() instanceof android.graphics.drawable.RippleDrawable);
    }
}

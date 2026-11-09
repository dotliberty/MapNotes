package ru.main.explorer;

import android.location.Location;
import android.os.SystemClock;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.*;

/** Real Activity/UI and policy tests, not a Camera2 hardware simulation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "ru-mdpi")
public class CameraUiTest {
    Object get(CameraActivity a, String name) throws Exception {
        Field f = CameraActivity.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(a);
    }

    void set(CameraActivity a, String name, Object value) throws Exception {
        Field f = CameraActivity.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(a, value);
    }

    @Test
    public void shutterStartsDisabledAndHasAccessibleLabel() throws Exception {
        ActivityController<CameraActivity> c =
                Robolectric.buildActivity(CameraActivity.class).create();
        CameraActivity a = c.get();
        Button shutter = (Button) get(a, "shutter");
        assertFalse(shutter.isEnabled());
        assertTrue(shutter.getContentDescription().toString().contains("фотографию"));
        assertEquals(72, shutter.getLayoutParams().height);
        assertTrue(((TextView) get(a, "geo")).getText().toString().contains("Ждём GPS"));
        assertEquals(View.GONE, ((Button) get(a, "light")).getVisibility());
        c.destroy();
    }

    @Test
    public void supportedLightAndBusyControlsFollowState() throws Exception {
        ActivityController<CameraActivity> c =
                Robolectric.buildActivity(CameraActivity.class).create();
        CameraActivity a = c.get();
        set(a, "active", true);
        set(a, "ready", true);
        set(a, "hasFlash", true);
        a.refreshControls();
        Button shutter = (Button) get(a, "shutter"), light = (Button) get(a, "light");
        assertTrue(shutter.isEnabled());
        assertEquals(View.VISIBLE, light.getVisibility());
        assertTrue(light.isEnabled());
        set(a, "capturing", true);
        a.refreshControls();
        assertFalse(shutter.isEnabled());
        assertFalse(light.isEnabled());
        assertEquals("…", shutter.getText().toString());
        c.destroy();
    }

    @Test
    public void pauseInvalidatesCallbacksAndResetsBusyState() throws Exception {
        ActivityController<CameraActivity> c =
                Robolectric.buildActivity(CameraActivity.class).setup();
        CameraActivity a = c.get();
        set(a, "capturing", true);
        set(a, "ready", true);
        set(a, "torch", true);
        int generation = (Integer) get(a, "generation");
        c.pause();
        assertTrue((Integer) get(a, "generation") > generation);
        assertFalse((Boolean) get(a, "capturing"));
        assertFalse((Boolean) get(a, "torch"));
        assertFalse(((Button) get(a, "shutter")).isEnabled());
        c.stop().destroy();
    }

    @Test
    public void missingAndStaleGpsAreNotPresentedAsGeotagged() {
        long now = 60000000000L;
        assertFalse(CameraActivity.freshFix(null, now));
        Location l = new Location("gps");
        l.setElapsedRealtimeNanos(now - 30000000000L);
        assertTrue(CameraActivity.freshFix(l, now));
        l.setElapsedRealtimeNanos(now - 30000000001L);
        assertFalse(CameraActivity.freshFix(l, now));
        l.setElapsedRealtimeNanos(now + 2000000000L);
        assertFalse(CameraActivity.freshFix(l, now));
    }

    @Test
    public void gpsStatusIsSeparateFromCameraStatus() throws Exception {
        ActivityController<CameraActivity> c =
                Robolectric.buildActivity(CameraActivity.class).create();
        CameraActivity a = c.get();
        Location l = new Location("gps");
        l.setLatitude(55);
        l.setLongitude(37);
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        l.setAccuracy(12);
        a.message("Ошибка камеры");
        a.onLocationChanged(l);
        assertEquals("Ошибка камеры", ((TextView) get(a, "status")).getText().toString());
        assertTrue(((TextView) get(a, "geo")).getText().toString().contains("±12"));
        c.destroy();
    }

    @Test
    public void jpegRotationUsesPhysicalDeviceNotPortraitLockedDisplay() {
        assertEquals(90, CameraActivity.jpegRotation(90, 0, false));
        assertEquals(180, CameraActivity.jpegRotation(90, 90, false));
        assertEquals(270, CameraActivity.jpegRotation(90, 180, false));
        assertEquals(0, CameraActivity.jpegRotation(90, 270, false));
        assertEquals(0, CameraActivity.jpegRotation(90, 90, true));
        assertEquals(180, CameraActivity.jpegRotation(270, 90, true));
    }

    @Test
    public void jpegSelectionBoundsMemoryAndUsesSmallestOversizedFallback() {
        assertEquals(
                new Size(4000, 3000),
                CameraActivity.chooseJpeg(
                        new Size[] {
                            new Size(8000, 6000), new Size(1920, 1080), new Size(4000, 3000)
                        }));
        assertEquals(
                new Size(5000, 3000),
                CameraActivity.chooseJpeg(new Size[] {new Size(8000, 6000), new Size(5000, 3000)}));
    }

    @Test
    public void shutterRemainsLargeAfterSharedStyling() throws Exception {
        ActivityController<CameraActivity> c =
                Robolectric.buildActivity(CameraActivity.class).create();
        try {
            Button b = (Button) get(c.get(), "shutter");
            assertEquals(TextView.AUTO_SIZE_TEXT_TYPE_NONE, b.getAutoSizeTextType());
            assertEquals(
                    40, b.getTextSize() / b.getResources().getDisplayMetrics().scaledDensity, .1);
            assertTrue(b.getContentDescription().length() > 0);
        } finally {
            c.destroy();
        }
    }

    @Test
    public void compactLensKeepsIdentityInAccessibilityAndZoomAlwaysShowsValue() throws Exception {
        ActivityController<CameraActivity> c =
                Robolectric.buildActivity(CameraActivity.class).create();
        try {
            CameraControls controls = (CameraControls) get(c.get(), "controls");
            controls.selected = new CameraControls.Option("device-long-id", "physical-id", 1, 6);
            controls.update(false, false);
            assertFalse(controls.lens.getText().toString().contains("device-long-id"));
            assertTrue(controls.lens.getContentDescription().toString().contains("physical-id"));
            assertTrue(controls.zoom.getText().toString().contains("×"));
            assertFalse(controls.zoom.isEnabled());
            assertTrue(controls.facing.getContentDescription().toString().contains("заднюю"));
        } finally {
            c.destroy();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingJpegFormatsFailClearly() {
        CameraActivity.chooseJpeg(new Size[0]);
    }
}

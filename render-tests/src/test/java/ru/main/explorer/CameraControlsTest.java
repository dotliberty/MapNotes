package ru.main.explorer;

import android.graphics.Rect;
import android.content.pm.ActivityInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.widget.Button;
import java.lang.reflect.Field;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

/** Policy and real Activity regression tests; not evidence of physical camera access. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CameraControlsTest {
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
    public void cropUsesOffsetActiveArrayAndIsCentered() {
        assertEquals(
                new Rect(1100, 950, 3100, 2450),
                CameraControls.crop(new Rect(100, 200, 4100, 3200), 2));
    }

    @Test
    public void pinchMultipliesAndBoundsWithoutChangingLens() {
        assertEquals(3, CameraControls.gestureRatio(2, 1.5f, .5f, 8), 0);
        assertEquals(.5f, CameraControls.gestureRatio(1, .01f, .5f, 8), 0);
        assertEquals(8, CameraControls.gestureRatio(2, Float.MAX_VALUE, .5f, 8), 0);
        for (float invalid : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY})
            assertEquals(2, CameraControls.gestureRatio(2, invalid, .5f, 8), 0);
    }

    @Test
    public void pinchRejectsBusyUnsupportedAndStaleGeneration() throws Exception {
        ActivityController<CameraActivity> c =
                Robolectric.buildActivity(CameraActivity.class).create();
        CameraActivity a = c.get();
        CameraControls controls = (CameraControls) get(a, "controls");
        set(a, "active", true);
        set(a, "ready", true);
        controls.min = .5f;
        controls.max = 4;
        controls.ratio = 1;
        controls.update(true, false);
        assertTrue(controls.scaleZoom(2, a.cameraGeneration()));
        assertEquals(2, controls.ratio, 0);
        assertFalse(controls.scaleZoom(2, a.cameraGeneration() + 1));
        set(a, "capturing", true);
        assertFalse(controls.scaleZoom(2, a.cameraGeneration()));
        set(a, "capturing", false);
        controls.max = controls.min = 1;
        controls.update(true, false);
        assertFalse(controls.scaleZoom(2, a.cameraGeneration()));
        assertEquals(2, controls.ratio, 0);
        c.destroy();
    }

    @Test
    public void cropNeverExceedsSensor() {
        Rect sensor = new Rect(17, 23, 4017, 3023);
        assertEquals(sensor, CameraControls.crop(sensor, 1));
        Rect c = CameraControls.crop(sensor, 100000);
        assertTrue(sensor.contains(c));
        assertEquals(1, c.width());
        assertEquals(1, c.height());
    }

    @Test
    public void zoomClampsAndRejectsMalformedLimits() {
        assertEquals(4, CameraControls.clamp(100, 1, 4), 0);
        assertEquals(.5f, CameraControls.clamp(0, .5f, 4), 0);
        assertEquals(1, CameraControls.clamp(Float.NaN, 1, 4), 0);
        assertTrue(CameraControls.validRange(.5f, 8));
        assertFalse(CameraControls.validRange(Float.NaN, 8));
        assertFalse(CameraControls.validRange(0, 8));
        assertFalse(CameraControls.validRange(2, 8));
        assertFalse(CameraControls.validRange(1, Float.POSITIVE_INFINITY));
    }

    @Test
    public void physicalIdsAreNamespacedByTheirLogicalParent() {
        CameraControls.Option
                a = new CameraControls.Option("0", "3", CameraCharacteristics.LENS_FACING_BACK, 19),
                b = new CameraControls.Option("1", "3", CameraCharacteristics.LENS_FACING_BACK, 19);
        assertNotEquals(a.key(), b.key());
        assertNotEquals(a.key(), new CameraControls.Option("0", null, 1, 6).key());
    }

    @Test
    public void uiOrientationIsLockedInManifest() throws Exception {
        ActivityController<CameraActivity> c =
                Robolectric.buildActivity(CameraActivity.class).create();
        CameraActivity a = c.get();
        ActivityInfo info = a.getPackageManager().getActivityInfo(a.getComponentName(), 0);
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, info.screenOrientation);
        c.destroy();
    }

    @Test
    public void unsupportedModeKeepsManualRecoveryButDisablesShutter() throws Exception {
        ActivityController<CameraActivity> c =
                Robolectric.buildActivity(CameraActivity.class).create();
        CameraActivity a = c.get();
        set(a, "active", true);
        CameraControls controls = (CameraControls) get(a, "controls");
        controls.options.add(new CameraControls.Option("0", null, 1, 6));
        a.modeFailed("test");
        a.refreshControls();
        assertTrue(controls.lens.isEnabled());
        assertTrue(((Button) get(a, "sizeButton")).isEnabled());
        assertFalse(((Button) get(a, "shutter")).isEnabled());
        assertFalse(controls.zoom.isEnabled());
        c.destroy();
    }

    @Test
    public void busyCaptureCannotSwitchOrRestart() throws Exception {
        ActivityController<CameraActivity> c =
                Robolectric.buildActivity(CameraActivity.class).create();
        CameraActivity a = c.get();
        set(a, "active", true);
        set(a, "ready", true);
        set(a, "capturing", true);
        int before = a.cameraGeneration();
        a.restartCamera();
        assertEquals(before, a.cameraGeneration());
        assertFalse(a.cameraControlsEditable());
        c.destroy();
    }

    @Test
    public void pendingDeviceClosePreventsAnotherOpen() throws Exception {
        ActivityController<CameraActivity> c =
                Robolectric.buildActivity(CameraActivity.class).create();
        CameraActivity a = c.get();
        set(a, "active", true);
        set(a, "deviceTransitions", 1);
        int before = a.cameraGeneration();
        a.openCamera();
        assertEquals(before, a.cameraGeneration());
        assertEquals(1, get(a, "deviceTransitions"));
        c.destroy();
    }

    @Test
    public void sizePreferenceIsIsolatedPerPhysicalLens() throws Exception {
        ActivityController<CameraActivity> c =
                Robolectric.buildActivity(CameraActivity.class).create();
        CameraActivity a = c.get();
        CameraControls controls = (CameraControls) get(a, "controls");
        controls.selected = new CameraControls.Option("0", "2", 1, 2);
        String first = a.sizeKey("jpegWidth");
        controls.selected = new CameraControls.Option("0", "3", 1, 19);
        assertNotEquals(first, a.sizeKey("jpegWidth"));
        c.destroy();
    }

    @Test
    public void frontAndBackJpegRotationCoversAllQuadrants() {
        for (int sensor : new int[] {0, 90, 180, 270})
            for (int d : new int[] {0, 90, 180, 270}) {
                assertEquals((sensor + d) % 360, CameraActivity.jpegRotation(sensor, d, false));
                assertEquals(
                        (sensor - d + 360) % 360, CameraActivity.jpegRotation(sensor, d, true));
            }
    }
}

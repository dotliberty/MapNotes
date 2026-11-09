package ru.main.explorer;

import android.view.*;
import java.lang.reflect.Field;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CameraPinchTest {
    Object field(CameraActivity a, String n) throws Exception {
        Field f = CameraActivity.class.getDeclaredField(n);
        f.setAccessible(true);
        return f.get(a);
    }

    void set(CameraActivity a, String n, Object v) throws Exception {
        Field f = CameraActivity.class.getDeclaredField(n);
        f.setAccessible(true);
        f.set(a, v);
    }

    void event(View view, int action, int count, float span, long time) {
        MotionEvent.PointerProperties[] p = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] c = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            p[i] = new MotionEvent.PointerProperties();
            p[i].id = i;
            p[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            c[i] = new MotionEvent.PointerCoords();
            c[i].x = 300 + (i == 0 ? -span : span) / 2;
            c[i].y = 400;
            c[i].pressure = 1;
            c[i].size = 1;
        }
        MotionEvent e =
                MotionEvent.obtain(
                        0,
                        time,
                        action,
                        count,
                        p,
                        c,
                        0,
                        0,
                        1,
                        1,
                        0,
                        0,
                        android.view.InputDevice.SOURCE_TOUCHSCREEN,
                        0);
        view.dispatchTouchEvent(e);
        e.recycle();
    }

    @Test
    public void twoFingerEventsOnPreviewZoomAndCancelStopsGesture() throws Exception {
        ActivityController<CameraActivity> c =
                Robolectric.buildActivity(CameraActivity.class).create();
        CameraActivity a = c.get();
        set(a, "active", true);
        set(a, "ready", true);
        CameraControls controls = (CameraControls) field(a, "controls");
        controls.min = 1;
        controls.max = 4;
        controls.update(true, false);
        View preview = (View) field(a, "preview");
        preview.layout(0, 0, 600, 800);
        event(preview, MotionEvent.ACTION_DOWN, 1, 200, 10);
        event(
                preview,
                MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2,
                200,
                30);
        event(preview, MotionEvent.ACTION_MOVE, 2, 300, 70);
        event(preview, MotionEvent.ACTION_MOVE, 2, 450, 110);
        assertTrue("Preview listener must feed the scale detector", controls.ratio > 1);
        assertTrue(controls.ratio <= 4);
        float before = controls.ratio;
        event(preview, MotionEvent.ACTION_CANCEL, 2, 450, 130);
        event(preview, MotionEvent.ACTION_DOWN, 1, 200, 150);
        event(preview, MotionEvent.ACTION_MOVE, 1, 500, 170);
        event(preview, MotionEvent.ACTION_UP, 1, 500, 190);
        assertEquals(before, controls.ratio, 0);
        c.destroy();
    }
}

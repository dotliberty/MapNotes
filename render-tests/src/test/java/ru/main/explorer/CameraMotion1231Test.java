package ru.main.explorer;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class CameraMotion1231Test {
    @After
    public void reset() {
        MotionTest.durationScale(1);
    }

    @Test
    public void rotationUsesShortestPath() {
        assertEquals(-360, AtlasMotion.nearestAngle(-270, 0), 0);
        assertEquals(0, AtlasMotion.nearestAngle(-90, 0), 0);
    }

    @Test
    public void cameraIconsRotateWithoutRotatingPreviewAndSurviveRefresh() throws Exception {
        MotionTest.durationScale(1);
        ActivityController<CameraActivity> c =
                Robolectric.buildActivity(CameraActivity.class).setup();
        CameraActivity a = c.get();
        a.orientControls(90);
        a.orientationMotion.setCurrentPlayTime(100);
        assertTrue(a.controlsAngle < 0 && a.controlsAngle > -90);
        a.orientationMotion.end();
        CameraControlsTest access = new CameraControlsTest();
        View preview = (View) access.get(a, "preview");
        assertEquals(0, preview.getRotation(), 0);
        a.refreshControls();
        Button light = (Button) access.get(a, "light");
        assertEquals(-90, ((AtlasIcon) light.getForeground()).angle, 0);
        a.orientControls(180);
        a.onPause();
        assertFalse(a.orientationMotion.isRunning());
        c.destroy();
    }

    @Test
    public void disabledRotationAndLaunchReachFinalState() {
        MotionTest.durationScale(0);
        ActivityController<CameraActivity> c =
                Robolectric.buildActivity(CameraActivity.class).setup();
        CameraActivity a = c.get();
        a.orientControls(270);
        assertEquals(90, a.controlsAngle, 0);
        View root = ((android.view.ViewGroup) a.findViewById(android.R.id.content)).getChildAt(0);
        root.layout(0, 0, 400, 800);
        AtlasMotion.launch(root, new int[] {20, 30, 60, 40});
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, root.getScaleX(), 0);
        assertEquals(1, root.getAlpha(), 0);
        c.pause().stop().destroy();
    }

    @Test
    public void launchHasButtonPivotAndIntermediateScale() {
        MotionTest.durationScale(1);
        ActivityController<Activity> c = Robolectric.buildActivity(Activity.class).setup();
        Activity a = c.get();
        FrameLayout root = new FrameLayout(a);
        a.setContentView(root);
        c.visible();
        root.layout(0, 0, 400, 800);
        assertTrue(root.isAttachedToWindow());
        AtlasMotion.launch(root, new int[] {20, 30, 60, 40});
        assertNotNull(AtlasMotion.running.get(root));
        AtlasMotion.running.get(root).setCurrentPlayTime(70);
        assertTrue(root.getScaleX() > .15f && root.getScaleX() < 1);
        AtlasMotion.running.get(root).end();
        assertEquals(1, root.getScaleY(), 0);
        c.pause().stop().destroy();
    }

    @Test
    public void cancelMenuAnimatesOnceThenDismisses() {
        MotionTest.durationScale(1);
        ActivityController<Activity> c = Robolectric.buildActivity(Activity.class).setup();
        Activity a = c.get();
        Button anchor = new Button(a);
        a.setContentView(anchor);
        final int[] calls = {0};
        AlertDialog d =
                new AtlasUi.DialogBuilder(a, anchor)
                        .setTitle("Camera")
                        .setNegativeButton("Cancel", (dialog, which) -> calls[0]++)
                        .show();
        shadowOf(Looper.getMainLooper()).idle();
        d.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        d.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        assertEquals(1, calls[0]);
        View panel = AtlasUi.anchorPanel(d);
        assertTrue(d.isShowing());
        AtlasMotion.running.get(panel).setCurrentPlayTime(70);
        assertTrue(panel.getScaleX() < 1);
        AtlasMotion.running.get(panel).end();
        assertFalse(d.isShowing());
        c.pause().stop().destroy();
    }
}

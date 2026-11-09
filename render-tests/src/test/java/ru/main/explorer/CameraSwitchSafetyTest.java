package ru.main.explorer;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.hardware.camera2.CameraCharacteristics;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.util.ArrayList;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.*;

/** Transition/state regressions, not a simulated successful Camera2 capture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CameraSwitchSafetyTest {
    public static class TransitionHarness extends CameraActivity {
        boolean provideSnapshot = true;
        int restarts;
        ImageView image;
        Bitmap bitmap;

        @Override
        void snapshotSwitch() {
            if (!provideSnapshot) return;
            try {
                clearSwitchImage();
                FrameLayout root = (FrameLayout) new CameraControlsTest().get(this, "cameraRoot");
                bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.BLUE);
                image = new ImageView(this);
                image.setImageBitmap(bitmap);
                root.addView(image, new FrameLayout.LayoutParams(48, 48));
                new CameraControlsTest().set(this, "switchBitmap", bitmap);
                new CameraControlsTest().set(this, "switchImage", image);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        void restartCamera() {
            // Record the transition boundary; do not pretend hardware opened or became ready.
            restarts++;
        }
    }

    @After
    public void resetAnimations() {
        MotionTest.durationScale(1);
    }

    ActivityController<TransitionHarness> activity() throws Exception {
        MotionTest.durationScale(1);
        ActivityController<TransitionHarness> controller =
                Robolectric.buildActivity(TransitionHarness.class).create();
        CameraControlsTest fields = new CameraControlsTest();
        fields.set(controller.get(), "active", true);
        fields.set(controller.get(), "ready", true);
        return controller;
    }

    @Test
    public void clearedOverlayRetainsBitmapForAlreadyQueuedDraw() throws Exception {
        ActivityController<TransitionHarness> c = activity();
        try {
            TransitionHarness a = c.get();
            a.snapshotSwitch();
            Bitmap queuedFrame = a.bitmap;
            a.clearSwitchImage();
            assertFalse(
                    "RenderThread may still reference the removed overlay",
                    queuedFrame.isRecycled());
            Bitmap output = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            new Canvas(output).drawBitmap(queuedFrame, 0, 0, null);
            assertEquals(Color.BLUE, output.getPixel(4, 4));
            assertNull(new CameraControlsTest().get(a, "switchBitmap"));
        } finally {
            c.destroy();
        }
    }

    @Test
    public void flipOnlyAnimatesSnapshotAndRestartsExactlyOnce() throws Exception {
        ActivityController<TransitionHarness> c = activity();
        try {
            TransitionHarness a = c.get();
            View preview = (View) new CameraControlsTest().get(a, "preview");
            a.switchCamera();
            ValueAnimator animation = AtlasMotion.running.get(a.image);
            assertNotNull("The overlay, not live TextureView, must animate", animation);
            animation.setCurrentPlayTime(80);
            assertTrue(a.image.getRotationY() > 0);
            assertEquals(0, preview.getRotationY(), 0);
            assertNull(AtlasMotion.running.get(preview));
            a.switchCamera();
            animation.end();
            assertEquals(1, a.restarts);
            assertFalse((Boolean) new CameraControlsTest().get(a, "ready"));
            assertFalse(a.isFinishing());
            a.onPause();
            assertFalse(a.bitmap.isRecycled());
        } finally {
            c.destroy();
        }
    }

    @Test
    public void deviceFailureKeepsManualRecoveryAndNeverFinishesActivity() throws Exception {
        ActivityController<TransitionHarness> c = activity();
        try {
            TransitionHarness a = c.get();
            CameraControlsTest fields = new CameraControlsTest();
            CameraControls controls = (CameraControls) fields.get(a, "controls");
            CameraControls.Option selected = new CameraControls.Option("0", null, 1, 4);
            controls.selected = selected;
            controls.options.add(selected);
            fields.set(a, "switching", true);
            a.snapshotSwitch();
            Bitmap queuedFrame = a.bitmap;
            int generation = a.cameraGeneration();
            a.cameraUnavailable("fixture device failure");
            assertEquals(generation + 1, a.cameraGeneration());
            assertFalse((Boolean) fields.get(a, "ready"));
            assertFalse((Boolean) fields.get(a, "switching"));
            assertFalse((Boolean) fields.get(a, "restartPending"));
            assertTrue(a.cameraControlsEditable());
            assertTrue(controls.facing.isEnabled());
            assertTrue(controls.lens.isEnabled());
            assertFalse(((android.widget.Button) fields.get(a, "shutter")).isEnabled());
            assertEquals(selected.key(), controls.selected.key());
            assertFalse(queuedFrame.isRecycled());
            assertFalse(a.isFinishing());
        } finally {
            c.destroy();
        }
    }

    @Test
    public void pauseCancelsOutgoingCallbackWithoutOpeningAnotherDevice() throws Exception {
        ActivityController<TransitionHarness> c = activity();
        try {
            TransitionHarness a = c.get();
            a.switchCamera();
            ValueAnimator outgoing = AtlasMotion.running.get(a.image);
            a.onPause();
            outgoing.end();
            assertEquals(0, a.restarts);
            assertFalse(a.bitmap.isRecycled());
            assertFalse((Boolean) new CameraControlsTest().get(a, "ready"));
        } finally {
            c.destroy();
        }
    }

    @Test
    public void oldFadeCannotRemoveSnapshotOfNextSwitch() throws Exception {
        ActivityController<TransitionHarness> c = activity();
        try {
            TransitionHarness a = c.get();
            a.switchCamera();
            AtlasMotion.running.get(a.image).end();
            new CameraControlsTest().set(a, "ready", true);
            a.finishSwitch();
            ImageView oldImage = a.image;
            ValueAnimator oldFade = AtlasMotion.running.get(oldImage);
            a.switchCamera();
            ImageView newImage = a.image;
            assertNotSame(oldImage, newImage);
            oldFade.end();
            assertSame(newImage, new CameraControlsTest().get(a, "switchImage"));
            assertFalse(a.isFinishing());
        } finally {
            c.destroy();
        }
    }

    @Test
    public void unavailableSnapshotDoesNotDelayRestartOrTransformPreview() throws Exception {
        ActivityController<TransitionHarness> c = activity();
        try {
            TransitionHarness a = c.get();
            a.provideSnapshot = false;
            a.switchCamera();
            assertEquals(1, a.restarts);
            View preview = (View) new CameraControlsTest().get(a, "preview");
            assertEquals(0, preview.getRotationY(), 0);
            assertFalse(a.isFinishing());
        } finally {
            c.destroy();
        }
    }

    @Test
    public void choosingPhysicalModeUsesSameSafeTransition() throws Exception {
        ActivityController<TransitionHarness> c = activity();
        try {
            TransitionHarness a = c.get();
            CameraControls controls = (CameraControls) new CameraControlsTest().get(a, "controls");
            CameraControls.Option logical = new CameraControls.Option("0", null, 1, 4);
            CameraControls.Option physical = new CameraControls.Option("0", "2", 1, 8);
            controls.selected = logical;
            ArrayList<CameraControls.Option> choices = new ArrayList<>();
            choices.add(logical);
            choices.add(physical);
            controls.showChoices(choices, R.string.camera_lens);
            ShadowAlertDialog.getLatestAlertDialog().getListView().performItemClick(null, 1, 1);
            assertEquals(physical.key(), controls.selected.key());
            AtlasMotion.running.get(a.image).end();
            assertEquals(1, a.restarts);
            assertFalse(a.isFinishing());
        } finally {
            c.destroy();
        }
    }

    @Test
    public void choosingAnotherFacingKeepsCameraActivityOpen() throws Exception {
        ActivityController<TransitionHarness> c = activity();
        try {
            TransitionHarness a = c.get();
            CameraControls controls = (CameraControls) new CameraControlsTest().get(a, "controls");
            CameraControls.Option back =
                    new CameraControls.Option("0", null, CameraCharacteristics.LENS_FACING_BACK, 4);
            CameraControls.Option front =
                    new CameraControls.Option(
                            "1", null, CameraCharacteristics.LENS_FACING_FRONT, 3);
            controls.selected = back;
            ArrayList<CameraControls.Option> choices = new ArrayList<>();
            choices.add(back);
            choices.add(front);
            controls.showChoices(choices, R.string.camera_facing);
            AlertDialog menu = ShadowAlertDialog.getLatestAlertDialog();
            menu.getListView().performItemClick(null, 1, 1);
            assertEquals(front.key(), controls.selected.key());
            assertFalse(menu.isShowing());
            ValueAnimator animation = AtlasMotion.running.get(a.image);
            assertNotNull(animation);
            animation.end();
            assertEquals(1, a.restarts);
            assertFalse(a.isFinishing());
        } finally {
            c.destroy();
        }
    }
}

package ru.main.explorer;

import android.app.*;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
public class Ui1211Test {
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void galleryPreviewRendersRecycledThumbnailRows() throws Exception {
        org.robolectric.android.controller.ActivityController<MainActivity> c =
                Robolectric.buildActivity(MainActivity.class).create();
        try {
            MainActivity a = c.get();
            PhotoSelectionTest data = new PhotoSelectionTest();
            a.showPhotos(data.photos(data.photo(7, 0), data.photo(8, 0)));
            AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
            new Design12Test().save(d.getWindow().getDecorView(), "gallery-1211", 360, 560);
            d.dismiss();
        } finally {
            c.destroy();
        }
    }

    @Test
    public void chooserRetainsSelectionSurfaceAfterOpeningPhoto() {
        org.robolectric.android.controller.ActivityController<MainActivity> c =
                Robolectric.buildActivity(MainActivity.class).create();
        try {
            MainActivity a = c.get();
            PhotoSelectionTest data = new PhotoSelectionTest();
            a.showPhotos(data.photos(data.photo(7, 0), data.photo(8, 0)));
            AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
            Button b =
                    data.find(d.getWindow().getDecorView(), Language.text(a, R.string.photo) + 7);
            assertNotNull(b);
            b.performClick();
            assertTrue(d.isShowing());
            assertEquals(7, Shadows.shadowOf(a).getNextStartedActivity().getLongExtra("id", -1));
            d.dismiss();
        } finally {
            c.destroy();
        }
    }

    @Test
    public void iconsKeepLocalizedTooltipAndMinimumTarget() throws Exception {
        for (String lang : new String[] {"en", "ru"}) {
            Language.select(RuntimeEnvironment.getApplication(), lang);
            org.robolectric.android.controller.ActivityController<CameraActivity> c =
                    Robolectric.buildActivity(CameraActivity.class).create();
            try {
                java.lang.reflect.Field f = CameraActivity.class.getDeclaredField("controls");
                f.setAccessible(true);
                CameraControls controls = (CameraControls) f.get(c.get());
                for (Button b : new Button[] {controls.facing, controls.lens}) {
                    assertEquals("", b.getText().toString());
                    assertNotNull(b.getForeground());
                    assertTrue(b.getMinimumHeight() >= 48);
                    assertEquals(b.getContentDescription(), b.getTooltipText());
                    assertTrue(b.getContentDescription().length() > 0);
                }
            } finally {
                c.destroy();
            }
        }
        Language.select(RuntimeEnvironment.getApplication(), "system");
    }
}

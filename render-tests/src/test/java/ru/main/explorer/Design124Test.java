package ru.main.explorer;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

/**
 * Actual native Views and a real checked-in Moscow MVT. Screens are test fixtures, not a phone
 * recording.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class Design124Test {
    @Before
    public void setup() {
        MotionTest.durationScale(0);
        Language.select(RuntimeEnvironment.getApplication(), "ru");
        MapNetwork.setEnabled(RuntimeEnvironment.getApplication(), false);
    }

    @After
    public void cleanup() {
        MotionTest.durationScale(1);
        Language.select(RuntimeEnvironment.getApplication(), "system");
    }

    void brandState(View v) {
        if (v instanceof TextView && "MapNotes".contentEquals(((TextView) v).getText())) {
            TextView t = (TextView) v;
            assertTrue(t.getWidth() > 80);
            assertTrue(t.getHeight() > 20);
            assertTrue(t.getTextSize() > 20);
            assertNotNull(t.getLayout());
            assertTrue(
                    "Centered branding must not scroll into an offscreen text layout",
                    t.getLayout().getWidth() <= t.getWidth());
            assertTrue(t.getLayout().getLineWidth(0) < t.getWidth());
        }
        if (v instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++)
                brandState(((ViewGroup) v).getChildAt(i));
    }

    @Test
    public void brandRetainsVisibleMeasuredText() throws Exception {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).create();
        try {
            dismissIntro();
            View root = c.get().findViewById(android.R.id.content);
            save(root, "brand", 432, 800);
            brandState(root);
        } finally {
            c.destroy();
        }
    }

    Object get(Object object, Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(object);
    }

    void save(View root, String name, int width, int height) throws Exception {
        root.measure(
                View.MeasureSpec.makeMeasureSpec(width, 1073741824),
                View.MeasureSpec.makeMeasureSpec(height, 1073741824));
        root.layout(0, 0, width, height);
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(image));
        File file = new File("build/rendered/1.2.4-" + name + ".png");
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
    }

    void dismissIntro() {
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        if (d != null && d.isShowing()) d.dismiss();
    }

    @Test
    public void artworkIsSmallAndButtonSurvivesRippleMutate() {
        Button b = new Button(RuntimeEnvironment.getApplication());
        AtlasUi.styleButton(b, true);
        b.getBackground().mutate();
        b.getBackground().setBounds(0, 0, 240, 60);
        Bitmap bitmap = Bitmap.createBitmap(240, 60, Bitmap.Config.ARGB_8888);
        b.getBackground().draw(new Canvas(bitmap));
        int middle = bitmap.getPixel(120, 30);
        assertTrue(Color.red(middle) > 180);
        assertTrue(Color.green(middle) > 150);
        int gold = 0;
        for (int x = 20; x < 220; x++)
            for (int y = 1; y < 9; y++) {
                int color = bitmap.getPixel(x, y);
                if (Color.alpha(color) > 200
                        && Color.red(color) > 150
                        && Color.red(color) > Color.blue(color) + 25) gold++;
            }
        assertTrue("Gold border must be painted, not lost in a GradientDrawable clone", gold > 100);
        assertNotNull(AtlasDecor.landscape);
        assertNotNull(AtlasDecor.blossoms);
        assertTrue(AtlasDecor.landscape.getWidth() <= 256);
        assertTrue(AtlasDecor.blossoms.getWidth() <= 256);
    }

    @Test
    public void restyledSingleChoiceKeepsSelectionAndCallback() {
        ActivityController<Activity> c = Robolectric.buildActivity(Activity.class).setup();
        try {
            int[] selected = {-1};
            AlertDialog d =
                    new AtlasUi.DialogBuilder(c.get())
                            .setTitle("Camera")
                            .setSingleChoiceItems(
                                    new String[] {"One", "Two", "Three"},
                                    1,
                                    (dialog, index) -> selected[0] = index)
                            .show();
            assertEquals(1, d.getListView().getCheckedItemPosition());
            assertEquals(3, d.getListView().getAdapter().getCount());
            View row = d.getListView().getAdapter().getView(2, null, d.getListView());
            d.getListView().performItemClick(row, 2, 2);
            assertEquals(2, selected[0]);
            assertEquals(2, d.getListView().getCheckedItemPosition());
            d.dismiss();
        } finally {
            c.pause().stop().destroy();
        }
    }

    @Test
    public void realActivityMapMenuWalksAndGalleryRender() throws Exception {
        WorldMapTest helper = new WorldMapTest();
        helper.setup();
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).create();
        try {
            MainActivity a = c.get();
            dismissIntro();
            MapView map = (MapView) get(a, MainActivity.class, "map");
            map.asyncRendering = false;
            map.online = false;
            map.follow = false;
            map.cx = MapView.x(37.612);
            map.cy = MapView.y(55.748);
            map.zoom = 17;
            File archive = helper.database(helper.fixture(), 14, 9903, 5122, false);
            WorldMap world = WorldMap.open(a, Uri.fromFile(archive), null);
            world.tile(14, 9903, 5122, true);
            Field field = MapView.class.getDeclaredField("world");
            field.setAccessible(true);
            field.set(map, world);
            save(a.findViewById(android.R.id.content), "map", 432, 800);
            assertTrue(map.getHeight() >= 250);
            a.menu();
            AlertDialog menu = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(11, menu.getListView().getAdapter().getCount());
            assertEquals(
                    a.getString(R.string.motion_title),
                    menu.getListView().getAdapter().getItem(10));
            save(menu.getWindow().getDecorView(), "menu", 408, 720);
            menu.dismiss();
            Store store = (Store) get(a, MainActivity.class, "store");
            store.startWalk(1794200000000L);
            store.finishWalk(1794200300000L);
            store.startWalk(1794200400000L);
            a.showWalks();
            AlertDialog walks = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(2, walks.getListView().getAdapter().getCount());
            save(walks.getWindow().getDecorView(), "history", 408, 400);
            walks.dismiss();
            PhotoSelectionTest fixtures = new PhotoSelectionTest();
            a.showPhotos(
                    fixtures.photos(
                            fixtures.photo(1, 0), fixtures.photo(2, 0), fixtures.photo(3, 0)));
            AlertDialog gallery = ShadowAlertDialog.getLatestAlertDialog();
            save(gallery.getWindow().getDecorView(), "gallery", 408, 650);
            gallery.dismiss();
        } finally {
            c.destroy();
            helper.cleanup();
        }
    }

    @Test
    public void compactPortraitKeepsUsefulMapInBothLanguages() throws Exception {
        for (String lang : new String[] {"en", "ru"}) {
            Language.select(RuntimeEnvironment.getApplication(), lang);
            ActivityController<MainActivity> c =
                    Robolectric.buildActivity(MainActivity.class).create();
            try {
                dismissIntro();
                save(c.get().findViewById(android.R.id.content), "compact-" + lang, 320, 640);
                MapView map = (MapView) get(c.get(), MainActivity.class, "map");
                assertTrue(map.getHeight() >= 180);
                Button start = (Button) get(c.get(), MainActivity.class, "start");
                assertTrue(start.getHeight() >= 48);
                assertTrue(start.getBottom() <= ((View) start.getParent()).getHeight());
            } finally {
                c.destroy();
            }
        }
    }

    @Test
    public void cameraLayoutRendersAndNewZoomButtonsFollowReadiness() throws Exception {
        ActivityController<CameraActivity> c =
                Robolectric.buildActivity(CameraActivity.class).create();
        try {
            CameraActivity a = c.get();
            CameraControls controls = (CameraControls) get(a, CameraActivity.class, "controls");
            assertFalse(controls.zoomIn.isEnabled());
            assertFalse(controls.zoomOut.isEnabled());
            CameraControlsTest fields = new CameraControlsTest();
            fields.set(a, "active", true);
            fields.set(a, "ready", true);
            controls.max = 4;
            controls.min = 1;
            controls.ratio = 1;
            a.refreshControls();
            assertTrue(controls.zoomIn.isEnabled());
            assertFalse(controls.zoomOut.isEnabled());
            controls.zoomIn.performClick();
            assertEquals(1.2f, controls.ratio, .001);
            assertTrue(controls.zoomOut.isEnabled());
            save(a.findViewById(android.R.id.content), "camera", 432, 800);
            Button modes =
                    find(
                            a.findViewById(android.R.id.content),
                            Language.text(a, R.string.camera_atlas_modes));
            assertNotNull(modes);
            modes.performClick();
            AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(3, d.getListView().getAdapter().getCount());
            save(d.getWindow().getDecorView(), "camera-modes", 400, 400);
            d.dismiss();
            fields.set(a, "capturing", true);
            a.refreshControls();
            assertFalse(controls.zoomIn.isEnabled());
            assertFalse(controls.zoomOut.isEnabled());
        } finally {
            c.destroy();
        }
    }

    Button find(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button) view).getText()))
            return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button b = find(group.getChildAt(i), text);
                if (b != null) return b;
            }
        }
        return null;
    }

    @Test
    public void cameraGalleryUsesRealMainActivityNavigation() throws Exception {
        ActivityController<CameraActivity> c =
                Robolectric.buildActivity(CameraActivity.class).create();
        try {
            CameraActivity a = c.get();
            Button gallery =
                    find(
                            a.findViewById(android.R.id.content),
                            Language.text(a, R.string.camera_atlas_gallery));
            assertNotNull(gallery);
            gallery.performClick();
            Intent intent = Shadows.shadowOf(a).getNextStartedActivity();
            assertEquals(MainActivity.class.getName(), intent.getComponent().getClassName());
            assertTrue(intent.getBooleanExtra("openPhotos", false));
            assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0);
        } finally {
            c.destroy();
        }
    }
}

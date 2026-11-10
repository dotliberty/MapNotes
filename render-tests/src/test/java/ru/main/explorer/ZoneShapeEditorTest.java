package ru.main.explorer;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "ru-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ZoneShapeEditorTest {
    SpawnZoneTest fixture;
    MapView map;
    SpawnZone base;
    ZoneShapeEditor editor;
    List<ZonePoint> square;

    @Before
    public void setup() {
        fixture = new SpawnZoneTest();
        fixture.setup();
        map = fixture.map();
        square = SpawnZone.quadrilateral(55.75, 37.61, 35);
        base = new SpawnZone(0, "Test contour", 55.75, 37.61, 35, square);
        editor = new ZoneShapeEditor(map, base, square, () -> {});
        map.setZoneEditor(editor);
    }

    void event(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        assertTrue(map.onTouchEvent(event));
        event.recycle();
    }

    @Test
    public void vertexDragChangesOnlySelectedPointAndUndoRestoresShape() {
        try {
            float[] xy = ZoneProjection.project(map, square);
            double cx = map.cx;
            event(MotionEvent.ACTION_DOWN, xy[0], xy[1]);
            event(MotionEvent.ACTION_MOVE, xy[0] - 20, xy[1] - 20);
            event(MotionEvent.ACTION_UP, xy[0] - 20, xy[1] - 20);
            assertNotEquals(square.get(0), editor.getPoints().get(0));
            assertEquals(square.subList(1, 4), editor.getPoints().subList(1, 4));
            assertEquals(cx, map.cx, 0);
            assertTrue(editor.getCanUndo());
            editor.undo();
            assertEquals(square, editor.getPoints());
            assertFalse(editor.getCanUndo());
        } finally {
            map.close();
        }
    }

    @Test
    public void addingEdgePointAndDraggingItDeformsQuadrilateral() {
        try {
            editor.setAdding(true);
            float[] xy = ZoneProjection.project(map, square);
            float x = (xy[0] + xy[2]) / 2;
            float y = (xy[1] + xy[3]) / 2;
            event(MotionEvent.ACTION_DOWN, x, y);
            event(MotionEvent.ACTION_MOVE, x, y - 25);
            event(MotionEvent.ACTION_UP, x, y - 25);
            assertEquals(5, editor.getPoints().size());
            SpawnZone changed = new SpawnZone(0, "Changed", 55.75, 37.61, 35, editor.getPoints());
            assertTrue(changed.isPolygon());
            try (SpawnZoneStore zones = new SpawnZoneStore(fixture.app)) {
                zones.save(changed);
                assertEquals(editor.getPoints(), zones.list().get(0).getPoints());
            }
            editor.undo();
            assertEquals(square, editor.getPoints());
        } finally {
            map.close();
        }
    }

    @Test
    public void invalidCrossingMoveAndCancelledDragLeaveOriginalGeometry() {
        try {
            float[] xy = ZoneProjection.project(map, square);
            event(MotionEvent.ACTION_DOWN, xy[0], xy[1]);
            event(MotionEvent.ACTION_MOVE, xy[4], xy[5]);
            event(MotionEvent.ACTION_UP, xy[4], xy[5]);
            assertEquals(square, editor.getPoints());
            assertFalse(editor.getCanUndo());
            event(MotionEvent.ACTION_DOWN, xy[0], xy[1]);
            event(MotionEvent.ACTION_MOVE, xy[0] - 20, xy[1] - 20);
            event(MotionEvent.ACTION_CANCEL, xy[0] - 20, xy[1] - 20);
            assertEquals(square, editor.getPoints());
            assertFalse(editor.getCanUndo());
        } finally {
            map.close();
        }
    }

    @Test
    public void pointDeletionStopsAtTriangleAndCanBeUndone() {
        try {
            editor.setSelected(0);
            editor.deleteSelected();
            assertEquals(3, editor.getPoints().size());
            editor.setSelected(0);
            assertFalse(editor.getCanDelete());
            editor.deleteSelected();
            assertEquals(3, editor.getPoints().size());
            editor.undo();
            assertEquals(square, editor.getPoints());
        } finally {
            map.close();
        }
    }

    @Test
    public void secondFingerCancelsVertexDragAndStartsMapGesture() {
        try {
            float[] xy = ZoneProjection.project(map, square);
            event(MotionEvent.ACTION_DOWN, xy[0], xy[1]);
            event(MotionEvent.ACTION_MOVE, xy[0] - 20, xy[1] - 20);
            MotionEvent.PointerProperties first = new MotionEvent.PointerProperties();
            first.id = 0;
            first.toolType = MotionEvent.TOOL_TYPE_FINGER;
            MotionEvent.PointerProperties second = new MotionEvent.PointerProperties();
            second.id = 1;
            second.toolType = MotionEvent.TOOL_TYPE_FINGER;
            MotionEvent.PointerCoords a = new MotionEvent.PointerCoords();
            a.x = xy[0] - 20;
            a.y = xy[1] - 20;
            MotionEvent.PointerCoords b = new MotionEvent.PointerCoords();
            b.x = 300;
            b.y = 500;
            long now = SystemClock.uptimeMillis();
            MotionEvent pinch =
                    MotionEvent.obtain(
                            now,
                            now,
                            MotionEvent.ACTION_POINTER_DOWN
                                    | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                            2,
                            new MotionEvent.PointerProperties[] {first, second},
                            new MotionEvent.PointerCoords[] {a, b},
                            0,
                            0,
                            1,
                            1,
                            0,
                            0,
                            0,
                            0);
            map.onTouchEvent(pinch);
            pinch.recycle();
            assertEquals(square, editor.getPoints());
            event(MotionEvent.ACTION_CANCEL, 100, 100);
            assertFalse(editor.getCanUndo());
        } finally {
            map.close();
        }
    }

    @Test
    public void polygonRenderingAndHitTestingUseVerticesNotLegacyRadius() {
        try {
            SpawnZone far = new SpawnZone(0, "Far", 0, 0, 1, square);
            map.setSpawnZones(java.util.Collections.singletonList(far));
            SpawnZoneOverlay overlay = new SpawnZoneOverlay();
            overlay.setZones(java.util.Collections.singletonList(far));
            assertEquals(1, overlay.hit(map, 216, 400).size());
            Bitmap image = Bitmap.createBitmap(432, 800, Bitmap.Config.ARGB_8888);
            overlay.draw(new Canvas(image), map);
            assertNotEquals(0, image.getPixel(216, 400));
            assertTrue(map.routeReveal().isEmpty());
        } finally {
            map.close();
        }
    }

    @Test
    public void circleCanGainAnEdgePointWithoutMovingAndThenDeform() {
        try {
            SpawnZone circle = new SpawnZone(0, "Circle", 55.75, 37.61, 35);
            List<ZonePoint> boundary = SpawnZone.circlePoints(circle);
            editor = new ZoneShapeEditor(map, circle, boundary, () -> {});
            map.setZoneEditor(editor);
            editor.setAdding(true);
            float[] xy = ZoneProjection.project(map, boundary);
            float x = (xy[0] + xy[2]) / 2;
            float y = (xy[1] + xy[3]) / 2;
            event(MotionEvent.ACTION_DOWN, x, y);
            event(MotionEvent.ACTION_UP, x, y);
            assertEquals(17, editor.getPoints().size());
            assertFalse(editor.getAdding());
            List<ZonePoint> added = editor.getPoints();
            float[] now = ZoneProjection.project(map, added);
            event(MotionEvent.ACTION_DOWN, now[2], now[3]);
            event(MotionEvent.ACTION_MOVE, now[2] + 12, now[3] - 12);
            event(MotionEvent.ACTION_UP, now[2] + 12, now[3] - 12);
            assertNotEquals(added, editor.getPoints());
            assertTrue(
                    new SpawnZone(0, "Deformed", 55.75, 37.61, 35, editor.getPoints()).isPolygon());
        } finally {
            map.close();
        }
    }

    @Test
    public void cancellingShapeActivityReturnsOriginalCircleWithoutWriting() {
        map.close();
        SpawnZone circle = new SpawnZone(0, "Unsaved circle", 55.75, 37.61, 35);
        Intent request =
                ZoneShapeActivity.payload(circle).setClass(fixture.app, ZoneShapeActivity.class);
        ActivityController<ZoneShapeActivity> owner =
                Robolectric.buildActivity(ZoneShapeActivity.class, request)
                        .create()
                        .start()
                        .resume();
        try {
            ZoneShapeActivity activity = owner.get();
            Button cancel =
                    (Button)
                            find(
                                    activity.findViewById(android.R.id.content),
                                    activity.getString(android.R.string.cancel));
            assertNotNull(cancel);
            cancel.performClick();
            assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(activity).getResultCode());
            assertFalse(
                    ZoneShapeActivity.decode(Shadows.shadowOf(activity).getResultIntent())
                            .isPolygon());
            try (SpawnZoneStore zones = new SpawnZoneStore(fixture.app)) {
                assertTrue(zones.list().isEmpty());
            }
        } finally {
            owner.pause().stop().destroy();
        }
    }

    View find(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText()))
            return view;
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                View result = find(((ViewGroup) view).getChildAt(i), label);
                if (result != null) return result;
            }
        }
        return null;
    }

    @Test
    public void shapeActivityAppliesDraftWithoutDatabaseWriteAndRestoresRotation()
            throws Exception {
        map.close();
        Intent request =
                ZoneShapeActivity.payload(base).setClass(fixture.app, ZoneShapeActivity.class);
        ActivityController<ZoneShapeActivity> owner =
                Robolectric.buildActivity(ZoneShapeActivity.class, request)
                        .create()
                        .start()
                        .resume();
        try {
            ZoneShapeActivity activity = owner.get();
            View root = activity.findViewById(android.R.id.content);
            root.measure(
                    View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 432, 900);
            Field field = ZoneShapeActivity.class.getDeclaredField("editor");
            field.setAccessible(true);
            ZoneShapeEditor actual = (ZoneShapeEditor) field.get(activity);
            actual.setSelected(0);
            actual.deleteSelected();
            Bundle state = new Bundle();
            owner.saveInstanceState(state);
            owner.pause().stop().destroy();
            owner =
                    Robolectric.buildActivity(ZoneShapeActivity.class, request)
                            .create(state)
                            .start()
                            .resume();
            activity = owner.get();
            actual = (ZoneShapeEditor) field.get(activity);
            assertEquals(3, actual.getPoints().size());
            root = activity.findViewById(android.R.id.content);
            root.measure(
                    View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 432, 900);
            Bitmap image = Bitmap.createBitmap(432, 900, Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(image));
            File directory = new File("build/rendered");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            try (FileOutputStream output =
                    new FileOutputStream(new File(directory, "1.3.1-contour-editor.png"))) {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
            Button apply = (Button) find(root, activity.getString(R.string.zone_apply_shape));
            assertNotNull(apply);
            apply.performClick();
            assertEquals(Activity.RESULT_OK, Shadows.shadowOf(activity).getResultCode());
            SpawnZone result =
                    ZoneShapeActivity.decode(Shadows.shadowOf(activity).getResultIntent());
            assertEquals(3, result.getPoints().size());
            try (SpawnZoneStore zones = new SpawnZoneStore(fixture.app)) {
                assertTrue(zones.list().isEmpty());
            }
        } finally {
            owner.pause().stop().destroy();
        }
    }

    @Test
    public void metadataFormOffersQuadrilateralAndReopensEditedContourWithoutSaving()
            throws Exception {
        map.close();
        ActivityController<Activity> owner =
                Robolectric.buildActivity(Activity.class).create().start().resume();
        map = fixture.map();
        SpawnZoneController controller = new SpawnZoneController(owner.get(), map);
        try {
            fixture.await(controller);
            controller.createAt(55.75, 37.61);
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            RadioButton quad =
                    (RadioButton)
                            find(
                                    dialog.getWindow().getDecorView(),
                                    owner.get().getString(R.string.zone_quad));
            assertNotNull(quad);
            quad.setChecked(true);
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
            Intent request = Shadows.shadowOf(owner.get()).getNextStartedActivityForResult().intent;
            SpawnZone initial = ZoneShapeActivity.decode(request);
            assertEquals(4, initial.getPoints().size());
            controller.shapeResult(ZoneShapeActivity.payload(initial));
            dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(
                    find(
                            dialog.getWindow().getDecorView(),
                            owner.get().getString(R.string.zone_polygon)));
            try (SpawnZoneStore zones = new SpawnZoneStore(fixture.app)) {
                assertTrue(zones.list().isEmpty());
            }
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            fixture.await(controller);
            try (SpawnZoneStore zones = new SpawnZoneStore(fixture.app)) {
                assertEquals(4, zones.list().get(0).getPoints().size());
            }
        } finally {
            controller.close();
            map.close();
            owner.pause().stop().destroy();
        }
    }
}

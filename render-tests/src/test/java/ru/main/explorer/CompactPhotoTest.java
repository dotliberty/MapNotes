package ru.main.explorer;

import android.database.MatrixCursor;
import android.graphics.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CompactPhotoTest {
    private MapView map;

    @Before
    public void setup() {
        map = new MapView(RuntimeEnvironment.getApplication());
        map.online = false;
        map.layout(0, 0, 512, 512);
        map.cx = map.cy = 128;
        map.zoom = 16;
    }

    @After
    public void cleanup() {
        map.close();
    }

    private Store.Photo photo(long id, double lat, double lon, String path) {
        try (MatrixCursor cursor =
                new MatrixCursor(
                        new String[] {"id", "lat", "lon", "acc", "path", "note", "created"})) {
            cursor.addRow(new Object[] {id, lat, lon, 5, path, "", 1000});
            cursor.moveToFirst();
            return new Store.Photo(cursor);
        }
    }

    @Test
    public void compactGroupsStayAtAnchorsAndKeepAllCoincidentIds() {
        ArrayList<Store.Photo> photos = new ArrayList<>();
        for (int i = 500; i >= 1; i--) photos.add(photo(i, 0, 0, "missing"));
        map.setPhotos(photos);
        ArrayList<PhotoGroups.Group> groups = map.compactPhotos.groups(map);
        assertEquals(1, groups.size());
        PhotoGroups.Group group = groups.get(0);
        assertEquals(500, group.photos.size());
        assertEquals(1, group.photos.get(0).id);
        assertEquals(256, group.x, 0);
        assertEquals(group.anchorX, group.x, 0);
        assertEquals(group.anchorY, group.y, 0);
        int builds = map.compactPhotos.builds;
        assertSame(groups, map.compactPhotos.groups(map));
        assertEquals(builds, map.compactPhotos.builds);
        map.cx += .001;
        groups = map.compactPhotos.groups(map);
        assertEquals(builds + 1, map.compactPhotos.builds);
        assertEquals(map.screenX(0), groups.get(0).x, .001);
        assertTrue(map.cards.isEmpty());
    }

    @Test
    public void previewPreservesLandscapeAndPortraitWithoutCrop() {
        Bitmap landscape = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888);
        Bitmap portrait = Bitmap.createBitmap(100, 300, Bitmap.Config.ARGB_8888);
        RectF small = MapView.photoRect(100, 100, landscape, 24);
        RectF big = MapView.photoRect(100, 100, landscape, 180);
        assertEquals(24, small.width(), 0);
        assertEquals(8, small.height(), 0);
        assertEquals(3, big.width() / big.height(), .0001);
        RectF tall = MapView.photoRect(100, 100, portrait, 180);
        assertEquals(60, tall.width(), 0);
        assertEquals(180, tall.height(), 0);
    }

    @Test
    public void firstTapOpensEntireBlockWithoutEnlargingMapPhoto() throws Exception {
        Bitmap photoImage = Bitmap.createBitmap(300, 150, Bitmap.Config.ARGB_8888);
        Canvas photoCanvas = new Canvas(photoImage);
        photoCanvas.drawColor(0xff2277aa);
        Paint color = new Paint();
        color.setColor(0xffffcc44);
        photoCanvas.drawRect(150, 0, 300, 150, color);
        File file =
                new File(RuntimeEnvironment.getApplication().getCacheDir(), "compact-photo.png");
        try (FileOutputStream stream = new FileOutputStream(file)) {
            photoImage.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        Store.Photo photo = photo(1, 0, 0, file.getPath());
        Store.Photo second = photo(2, 0, 0, file.getPath());
        map.setPhotos(new ArrayList<>(Arrays.asList(second, photo)));
        map.photoThumbnail(photo);
        Field field = MapView.class.getDeclaredField("photoPool");
        field.setAccessible(true);
        ((ExecutorService) field.get(map)).submit(() -> {}).get();
        int[] opened = {0};
        map.setPhotoTapListener(
                photos -> {
                    assertEquals(2, photos.size());
                    assertEquals(1, photos.get(0).id);
                    assertEquals(2, photos.get(1).id);
                    opened[0]++;
                });
        Bitmap small = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        map.drawPhotos(new Canvas(small));
        assertTrue(map.tapPhotos(256, 256));
        assertEquals(1, opened[0]);
        Bitmap afterTap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        map.drawPhotos(new Canvas(afterTap));
        assertTrue(small.sameAs(afterTap));
        assertFalse(map.tapPhotos(20, 400));
        File dir = new File("build/rendered");
        dir.mkdirs();
        try (FileOutputStream out =
                new FileOutputStream(new File(dir, "compact-photo-small.png"))) {
            small.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    @Test
    public void visibleBudgetOffscreenAndDateLineRemainBounded() {
        ArrayList<Store.Photo> photos = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            double lon = (i % 20 * 25 - 240) * 360.0 / (256 * (1 << 16));
            double lat = (i / 20 * 20 - 200) * 360.0 / (256 * (1 << 16));
            photos.add(photo(i, lat, lon, "missing"));
        }
        photos.add(photo(1000, 50, 50, "missing"));
        map.setPhotos(photos);
        assertTrue(map.compactPhotos.groups(map).size() <= CompactPhotoLayout.MAX_MARKERS);
        assertEquals(400, map.compactPhotos.visibleCount);
        map.cx = 0;
        map.zoom = 12;
        map.setPhotos(
                new ArrayList<>(
                        Arrays.asList(
                                photo(1, 0, 179.999, "missing"),
                                photo(2, 0, -179.999, "missing"))));
        int represented = 0;
        for (PhotoGroups.Group group : map.compactPhotos.groups(map))
            represented += group.photos.size();
        assertEquals(2, represented);
    }

    @Test
    public void movingPhotoLayoutBenchmark() {
        ArrayList<Store.Photo> photos = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            double lon = (i % 40 * 6 - 120) * 360.0 / (256 * (1 << 16));
            double lat = (i / 40 * 5 - 125) * 360.0 / (256 * (1 << 16));
            photos.add(photo(i, lat, lon, "missing"));
        }
        map.setPhotos(photos);
        long[] oldTimes = new long[15], newTimes = new long[15];
        for (int i = -5; i < 15; i++) {
            map.cx += .00001;
            long start = System.nanoTime();
            map.photoGroups();
            long middle = System.nanoTime();
            map.compactPhotos.groups(map);
            long end = System.nanoTime();
            if (i >= 0) {
                oldTimes[i] = middle - start;
                newTimes[i] = end - middle;
            }
        }
        Arrays.sort(oldTimes);
        Arrays.sort(newTimes);
        System.out.println(
                "CODE45 photo layout pan, 2000 photos, median ms: spread="
                        + oldTimes[7] / 1e6
                        + " compact="
                        + newTimes[7] / 1e6);
        assertTrue(map.compactPhotos.groups(map).size() <= CompactPhotoLayout.MAX_MARKERS);
    }
}

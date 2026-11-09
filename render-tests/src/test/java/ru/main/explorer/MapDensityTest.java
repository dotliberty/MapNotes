package ru.main.explorer;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MapDensityTest {
    @Test
    public void minorRoadsAppearOnlyAtUsefulScale() {
        assertFalse(MapDetail.roadVisible("service", 13));
        assertFalse(MapDetail.roadVisible("path", 15));
        assertTrue(MapDetail.roadVisible("primary", 10));
        assertTrue(MapDetail.roadVisible("service", 17));
        assertTrue(MapDetail.roadWidth(10) < MapDetail.roadWidth(17));
    }

    @Test
    public void subpixelBuildingsAreNotDrawn() {
        assertFalse(MapDetail.buildingVisible(13, 20, 20));
        assertFalse(MapDetail.buildingVisible(17, 1, 20));
        assertTrue(MapDetail.buildingVisible(17, 8, 8));
    }

    @Test(timeout = 3000)
    public void tenThousandCoincidentPhotosHaveBoundedLayout() {
        ArrayList<Store.Photo> p = new ArrayList<>();
        float[] xs = new float[10000], ys = new float[10000];
        Arrays.fill(xs, 250);
        Arrays.fill(ys, 250);
        for (int i = 0; i < 10000; i++) p.add(null);
        ArrayList<PhotoGroups.Group> result = PhotoGroups.spread(p, xs, ys, 50, 500, 500);
        assertTrue(result.size() > 0);
        assertTrue(result.size() <= 48);
        for (int i = 0; i < result.size(); i++)
            for (int j = 0; j < i; j++) {
                PhotoGroups.Group a = result.get(i), b = result.get(j);
                assertTrue(Math.abs(a.x - b.x) >= 58 || Math.abs(a.y - b.y) >= 58);
            }
    }
}

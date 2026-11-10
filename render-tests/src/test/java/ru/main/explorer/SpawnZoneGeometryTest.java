package ru.main.explorer;

import static org.junit.Assert.*;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SpawnZoneGeometryTest {
    Application app;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        app.deleteDatabase("walks.db");
    }

    List<ZonePoint> points(double... coordinates) {
        List<ZonePoint> result = new ArrayList<>();
        for (int i = 0; i < coordinates.length; i += 2) {
            result.add(new ZonePoint(coordinates[i], coordinates[i + 1]));
        }
        return result;
    }

    SpawnZone polygon(List<ZonePoint> points) {
        return new SpawnZone(0, "Polygon", 0, 0, 100, points);
    }

    @Test
    public void quadrilateralAndCircleConversionKeepGeographicBoundary() {
        List<ZonePoint> corners = SpawnZone.quadrilateral(55, 37, 100);
        assertEquals(4, corners.size());
        SpawnZone square = polygon(corners);
        assertTrue(square.isPolygon());
        assertTrue(square.contains(55, 37));
        assertEquals(8, square.boundary(1).length);
        SpawnZone circle = new SpawnZone(0, "Circle", 55, 37, 100);
        assertFalse(circle.isPolygon());
        assertTrue(circle.contains(55, 37));
        assertFalse(circle.contains(56, 37));
        List<ZonePoint> sampled = SpawnZone.circlePoints(circle);
        assertEquals(16, sampled.size());
        for (ZonePoint p : sampled) {
            assertEquals(100, GpsFilter.distance(55, 37, p.getLatitude(), p.getLongitude()), .001);
        }
        assertTrue(polygon(sampled).contains(55, 37));
    }

    @Test
    public void concaveContainmentIncludesEdgesAndVertices() {
        SpawnZone zone = polygon(points(0, 0, 0, 3, 1, 3, 1, 1, 3, 1, 3, 0));
        assertTrue(zone.contains(.5, 2));
        assertTrue(zone.contains(2, .5));
        assertFalse(zone.contains(2, 2));
        assertTrue(zone.contains(0, 1));
        assertTrue(zone.contains(1, 1));
        assertTrue(zone.contains(2, 1));
        assertFalse(zone.contains(Double.NaN, 1));
    }

    @Test
    public void dateLinePolygonUnwrapsWithoutCoveringTheWorld() {
        SpawnZone zone = polygon(points(-1, 179, -1, -179, 1, -179, 1, 179));
        assertTrue(zone.contains(0, 180));
        assertTrue(zone.contains(0, -180));
        assertTrue(zone.contains(0, -179.5));
        assertFalse(zone.contains(0, 0));
        assertFalse(zone.contains(0, 178));
        assertTrue(polygon(SpawnZone.quadrilateral(0, 179.999, 1000)).contains(0, -180));
    }

    @Test
    public void rejectsCrossingDegenerateDuplicateAndInvalidVertices() {
        for (List<ZonePoint> invalid :
                Arrays.asList(
                        points(0, 0, 2, 2, 0, 2, 2, 0),
                        points(0, 0, 0, 1, 0, 2),
                        points(0, 0, 1, 1),
                        points(0, 0, 0, 1, 1, 1, 0, 0),
                        points(0, 0, 0, 2, 0, 1, 1, 0),
                        points(0, 0, 1, 120, 0, -120))) {
            assertThrows(IllegalArgumentException.class, () -> polygon(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> new ZonePoint(Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new ZonePoint(86, 0));
        assertThrows(IllegalArgumentException.class, () -> new ZonePoint(0, 181));
        List<ZonePoint> many = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            double angle = 2 * Math.PI * i / 65;
            many.add(new ZonePoint(Math.sin(angle), Math.cos(angle)));
        }
        assertThrows(IllegalArgumentException.class, () -> polygon(many));
        // Polygon compatibility fields need not contain a whole circle near the pole.
        assertTrue(
                new SpawnZone(0, "Polar", 85, 0, 100000, points(84, 0, 84, 1, 85, 1)).isPolygon());
    }

    @Test
    public void pointsAreDefensivelyCopiedAndValueEqualityIsStable() {
        List<ZonePoint> input = SpawnZone.quadrilateral(0, 0, 100);
        List<ZonePoint> mutable = new ArrayList<>(input);
        SpawnZone zone = polygon(mutable);
        mutable.clear();
        assertEquals(input, zone.getPoints());
        assertThrows(UnsupportedOperationException.class, () -> zone.getPoints().clear());
        SpawnZone same = polygon(input);
        assertEquals(zone, same);
        assertEquals(zone.hashCode(), same.hashCode());
    }

    @Test
    public void polygonAddEditReopenDeleteDoesNotTouchOtherData() {
        long id;
        long walk;
        try (Store store = new Store(app);
                SpawnZoneStore zones = new SpawnZoneStore(app)) {
            walk = store.startWalk(123);
            SpawnZone saved = zones.save(polygon(SpawnZone.quadrilateral(0, 0, 100)));
            id = saved.getId();
            zones.save(
                    new SpawnZone(id, "Edited", 1, 1, 321, points(0, 0, 0, 2, 1, 2, 2, 1, 2, 0)));
            zones.save(new SpawnZone(0, "Circle", 0, 0, 42));
        }
        try (Store store = new Store(app);
                SpawnZoneStore zones = new SpawnZoneStore(app)) {
            List<SpawnZone> saved = zones.list();
            assertEquals(2, saved.size());
            assertEquals(id, saved.get(0).getId());
            assertEquals("Edited", saved.get(0).getName());
            assertEquals(5, saved.get(0).getPoints().size());
            assertEquals(321, saved.get(0).getRadiusMeters(), 0);
            assertTrue(zones.delete(id));
            assertEquals(1, zones.list().size());
            assertFalse(zones.list().get(0).isPolygon());
            assertEquals(walk, store.openWalk().id);
        }
    }

    @Test
    public void migrationFivePreservesCircleIdAndRadiusThenAcceptsPolygon() {
        long walk;
        try (Store store = new Store(app)) {
            walk = store.startWalk(100);
            SQLiteDatabase db = store.getWritableDatabase();
            db.execSQL("DROP TABLE spawn_zones");
            db.execSQL(
                    "CREATE TABLE spawn_zones(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT"
                            + " NULL,lat REAL NOT NULL,lon REAL NOT NULL,radius REAL NOT NULL)");
            db.execSQL("INSERT INTO spawn_zones VALUES(17,'Old circle',55,37,123.5)");
            db.setVersion(5);
        }
        try (Store store = new Store(app);
                SpawnZoneStore zones = new SpawnZoneStore(app)) {
            assertEquals(6, store.getReadableDatabase().getVersion());
            SpawnZone circle = zones.list().get(0);
            assertEquals(17, circle.getId());
            assertEquals(123.5, circle.getRadiusMeters(), 0);
            assertFalse(circle.isPolygon());
            zones.save(
                    new SpawnZone(
                            circle.getId(),
                            circle.getName(),
                            circle.getLatitude(),
                            circle.getLongitude(),
                            circle.getRadiusMeters(),
                            SpawnZone.circlePoints(circle)));
            SpawnZoneStore.createSchema(store.getWritableDatabase());
            assertEquals(walk, store.openWalk().id);
        }
        try (SpawnZoneStore zones = new SpawnZoneStore(app)) {
            assertEquals(17, zones.list().get(0).getId());
            assertEquals(16, zones.list().get(0).getPoints().size());
        }
    }

    @Test
    public void malformedStoredPolygonIsNeverSilentlyTruncatedOrConvertedToCircle() {
        for (String json :
                new String[] {
                    "[]",
                    "[[0,0],[0,1],[1,0],]",
                    "[[0,0],[0,1],[01,0]]",
                    "[[0,0],[0,1],[1,0]] /* comment */",
                    "[[0,0],[0,1],[1]]",
                    "[[0,0],[0,1],[1,0]]x",
                    "[[0,0],[0,1],[1,\"0\"]]",
                    "[[0,0],[0,1],[0,2]]"
                }) {
            app.deleteDatabase("walks.db");
            try (Store store = new Store(app);
                    SpawnZoneStore zones = new SpawnZoneStore(app)) {
                store.getWritableDatabase()
                        .execSQL(
                                "INSERT INTO spawn_zones(name,lat,lon,radius,polygon)"
                                        + " VALUES('Bad',0,0,100,?)",
                                new Object[] {json});
                assertThrows(Exception.class, zones::list);
            }
        }
    }
}

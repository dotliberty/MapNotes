package ru.main.explorer;

import static org.junit.Assert.*;

import android.location.Location;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TripGpsFilterTest {
    private Location fix(double latitude, long seconds, float accuracy, float speed) {
        Location location = new Location("gps");
        location.setLatitude(latitude);
        location.setLongitude(37);
        location.setAccuracy(accuracy);
        location.setSpeed(speed);
        location.setSpeedAccuracyMetersPerSecond(.5f);
        location.setElapsedRealtimeNanos(seconds * 1000000000L);
        return location;
    }

    private String accept(TripGpsFilter filter, Location location) {
        return filter.accept(location, location.getElapsedRealtimeNanos());
    }

    @Test
    public void transportRetainsModerateAccuracyWithoutRelaxingExplorationFilter() {
        TripGpsFilter filter = new TripGpsFilter();
        assertNull(accept(filter, fix(55, 1, 5, 25)));
        assertNull(accept(filter, fix(55.00022, 2, 50, 25)));
        assertNull(accept(filter, fix(55.00044, 3, 100, 25)));
        assertFalse(filter.getBreakSegment());
        assertEquals(
                "poor_gps_accuracy",
                new GpsFilter()
                        .accept(new GpsFilter.Fix(55, 37, 50, 1000000000L, false), 1000000000L));
    }

    @Test
    public void laggingReportedSpeedDoesNotDiscardMovingTransport() {
        TripGpsFilter filter = new TripGpsFilter();
        assertNull(accept(filter, fix(55, 1, 5, 0)));
        assertNull(accept(filter, fix(55.00045, 2, 5, 0)));
        assertNull(accept(filter, fix(55.00090, 3, 5, 1)));
        assertFalse(filter.getBreakSegment());
    }

    @Test
    public void teleportStillRejectsWithoutPoisoningTrustedAnchor() {
        TripGpsFilter filter = new TripGpsFilter();
        assertNull(accept(filter, fix(55, 1, 50, 0)));
        assertEquals("too_fast_gps_jump", accept(filter, fix(56, 2, 50, 0)));
        assertNull(accept(filter, fix(55.00045, 2, 50, 0)));
        assertFalse(filter.getBreakSegment());
    }

    @Test
    public void unusableAccuracyStillRejectsAndDoesNotAdvanceAnchor() {
        TripGpsFilter filter = new TripGpsFilter();
        assertNull(accept(filter, fix(55, 1, 5, 25)));
        for (float accuracy : new float[] {101, 0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertEquals("poor_gps_accuracy", accept(filter, fix(56, 2, accuracy, 25)));
        }
        Location missing = fix(56, 2, 5, 25);
        missing.removeAccuracy();
        assertEquals("poor_gps_accuracy", accept(filter, missing));
        assertNull(accept(filter, fix(55.00022, 2, 5, 25)));
    }

    @Test
    public void gapStartsNewSegmentAndFollowingPointResumesContinuity() {
        TripGpsFilter filter = new TripGpsFilter();
        assertNull(accept(filter, fix(55, 1, 50, 0)));
        assertNull(accept(filter, fix(56, 12, 50, 0)));
        assertTrue(filter.getBreakSegment());
        assertNull(accept(filter, fix(56.00045, 13, 50, 0)));
        assertFalse(filter.getBreakSegment());
    }
}

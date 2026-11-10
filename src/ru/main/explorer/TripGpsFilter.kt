package ru.main.explorer

import android.location.Location

/** Quality/continuity checks for a mixed journey, not a walk/run speed classifier. */
class TripGpsFilter {
    private var anchor: Location? = null
    var breakSegment = false
        private set

    fun accept(location: Location, nowNanos: Long): String? {
        breakSegment = false
        if (location.isFromMockProvider) return "mock_gps_location"
        if (
            !location.latitude.isFinite() ||
                !location.longitude.isFinite() ||
                kotlin.math.abs(location.latitude) > 85.05112878 ||
                kotlin.math.abs(location.longitude) > 180
        )
            return "invalid_coordinates"
        // Journey recording tolerates degraded reception; exploration keeps its own 30 m limit.
        if (
            !location.hasAccuracy() ||
                !location.accuracy.isFinite() ||
                location.accuracy <= 0 ||
                location.accuracy > 100
        )
            return "poor_gps_accuracy"
        val time = location.elapsedRealtimeNanos
        if (time <= 0 || time > nowNanos || nowNanos - time > 15_000_000_000L)
            return "stale_location"
        if (
            location.hasSpeed() &&
                (!location.speed.isFinite() || location.speed < 0 || location.speed > 120)
        ) {
            return "too_fast_gps_jump"
        }
        val previous = anchor
        if (previous != null) {
            val seconds = (time - previous.elapsedRealtimeNanos) / 1e9
            if (seconds <= 0) return "invalid_gps_time"
            if (seconds > 10) {
                breakSegment = true
            } else {
                val distance =
                    GpsFilter.distance(
                        previous.latitude,
                        previous.longitude,
                        location.latitude,
                        location.longitude,
                    )
                val uncertainty = previous.accuracy + location.accuracy
                if (distance > 120 * seconds + uncertainty) return "too_fast_gps_jump"
                // Reported speed can lag position after a tunnel or a stop. It is not
                // an independent reason to discard an otherwise plausible transport fix.
            }
        }
        anchor = Location(location)
        return null
    }
}

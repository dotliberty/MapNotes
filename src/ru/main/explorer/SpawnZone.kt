package ru.main.explorer

import java.util.Collections
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tan

data class ZonePoint(val latitude: Double, val longitude: Double) {
    init {
        require(
            latitude.isFinite() && latitude in -SpawnZone.MAX_LATITUDE..SpawnZone.MAX_LATITUDE
        ) {
            "latitude"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "longitude" }
    }
}

/** Local walk boundary; never itself an exploration fix or an Android geofence. */
class SpawnZone
@JvmOverloads
constructor(
    val id: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    points: List<ZonePoint> = emptyList(),
) {
    val points: List<ZonePoint> = Collections.unmodifiableList(ArrayList(points))
    val isPolygon: Boolean
        get() = points.isNotEmpty()

    private val projected: List<Pair<Double, Double>> =
        this.points.map {
            Pair(unwrap(it.longitude, this.points[0].longitude), mercator(it.latitude))
        }

    init {
        require(id >= 0) { "id" }
        require(
            name.isNotBlank() &&
                name == name.trim() &&
                name.length <= MAX_NAME_LENGTH &&
                name.none { it.isISOControl() }
        ) {
            "name"
        }
        require(
            latitude.isFinite() &&
                longitude.isFinite() &&
                latitude in -MAX_LATITUDE..MAX_LATITUDE &&
                longitude in -180.0..180.0
        ) {
            "coordinates"
        }
        require(radiusMeters.isFinite() && radiusMeters in MIN_RADIUS..MAX_RADIUS) { "radius" }
        require(
            isPolygon ||
                kotlin.math.abs(latitude) + Math.toDegrees(radiusMeters / EARTH_RADIUS) <=
                    MAX_LATITUDE
        ) {
            "extent"
        }
        if (isPolygon) validatePolygon()
    }

    fun copy(
        id: Long = this.id,
        name: String = this.name,
        latitude: Double = this.latitude,
        longitude: Double = this.longitude,
        radiusMeters: Double = this.radiusMeters,
        points: List<ZonePoint> = this.points,
    ): SpawnZone = SpawnZone(id, name, latitude, longitude, radiusMeters, points)

    override fun equals(other: Any?): Boolean =
        other is SpawnZone &&
            id == other.id &&
            name == other.name &&
            latitude.toBits() == other.latitude.toBits() &&
            longitude.toBits() == other.longitude.toBits() &&
            radiusMeters.toBits() == other.radiusMeters.toBits() &&
            points == other.points

    override fun hashCode(): Int =
        listOf(id, name, latitude, longitude, radiusMeters, points).hashCode()

    private fun validatePolygon() {
        require(points.size in 3..MAX_VERTICES) { "vertices" }
        require(projected.distinct().size == points.size) { "duplicate vertices" }
        require(projected.maxOf { it.first } - projected.minOf { it.first } < 180.0) {
            "longitude span"
        }
        val origin = projected[0]
        var area = 0.0
        for (i in projected.indices) {
            val a = projected[i]
            val b = projected[(i + 1) % points.size]
            area += cross(origin, a, b)
            val c = projected[(i + 2) % points.size]
            require(!onSegment(a, b, c) && !onSegment(b, c, a)) { "overlapping edges" }
            for (j in i + 1 until points.size) {
                if (j == i + 1 || (i == 0 && j == points.lastIndex)) continue
                require(!intersects(a, b, projected[j], projected[(j + 1) % points.size])) {
                    "self intersection"
                }
            }
        }
        require(abs(area) > 1e-14) { "area" }
    }

    fun contains(latitude: Double, longitude: Double): Boolean {
        if (
            !latitude.isFinite() ||
                !longitude.isFinite() ||
                latitude !in -MAX_LATITUDE..MAX_LATITUDE ||
                longitude !in -180.0..180.0
        )
            return false
        if (!isPolygon)
            return GpsFilter.distance(this.latitude, this.longitude, latitude, longitude) <=
                radiusMeters
        val p = Pair(unwrap(longitude, points[0].longitude), mercator(latitude))
        var inside = false
        for (i in projected.indices) {
            val a = projected[i]
            val b = projected[(i + 1) % points.size]
            if (onSegment(a, b, p)) return true
            if (
                (a.second > p.second) != (b.second > p.second) &&
                    p.first <
                        (b.first - a.first) * (p.second - a.second) / (b.second - a.second) +
                            a.first
            )
                inside = !inside
        }
        return inside
    }

    /** Spherical circle boundary; longitudes are normalized across the date line. */
    fun boundary(vertices: Int): DoubleArray {
        if (isPolygon) return points.flatMap { listOf(it.latitude, it.longitude) }.toDoubleArray()
        require(vertices in 16..4096)
        val result = DoubleArray(vertices * 2)
        val lat = Math.toRadians(latitude)
        val lon = Math.toRadians(longitude)
        val angle = radiusMeters / EARTH_RADIUS
        for (index in 0 until vertices) {
            val bearing = 2 * Math.PI * index / vertices
            val destinationLat = asin(sin(lat) * cos(angle) + cos(lat) * sin(angle) * cos(bearing))
            val destinationLon =
                lon +
                    atan2(
                        sin(bearing) * sin(angle) * cos(lat),
                        cos(angle) - sin(lat) * sin(destinationLat),
                    )
            result[index * 2] = Math.toDegrees(destinationLat)
            result[index * 2 + 1] = normalizeLongitude(Math.toDegrees(destinationLon))
        }
        return result
    }

    companion object {
        const val MIN_RADIUS = 1.0
        const val MAX_RADIUS = 100_000.0
        const val MAX_LATITUDE = 85.05112878
        const val MAX_NAME_LENGTH = 80
        const val EARTH_RADIUS = 6_371_008.8
        const val MAX_VERTICES = 64

        private fun mercator(latitude: Double): Double =
            Math.toDegrees(ln(tan(Math.PI / 4 + Math.toRadians(latitude) / 2)))

        private fun unwrap(longitude: Double, origin: Double): Double =
            origin + normalizeLongitude(longitude - origin)

        private fun cross(
            a: Pair<Double, Double>,
            b: Pair<Double, Double>,
            c: Pair<Double, Double>,
        ): Double =
            (b.first - a.first) * (c.second - a.second) -
                (b.second - a.second) * (c.first - a.first)

        private fun onSegment(
            a: Pair<Double, Double>,
            b: Pair<Double, Double>,
            p: Pair<Double, Double>,
        ): Boolean {
            val tolerance = 1e-10 * maxOf(abs(b.first - a.first), abs(b.second - a.second), 1e-6)
            return abs(cross(a, b, p)) <= tolerance &&
                p.first >= minOf(a.first, b.first) - 1e-10 &&
                p.first <= maxOf(a.first, b.first) + 1e-10 &&
                p.second >= minOf(a.second, b.second) - 1e-10 &&
                p.second <= maxOf(a.second, b.second) + 1e-10
        }

        private fun intersects(
            a: Pair<Double, Double>,
            b: Pair<Double, Double>,
            c: Pair<Double, Double>,
            d: Pair<Double, Double>,
        ): Boolean =
            onSegment(a, b, c) ||
                onSegment(a, b, d) ||
                onSegment(c, d, a) ||
                onSegment(c, d, b) ||
                ((cross(a, b, c) > 0) != (cross(a, b, d) > 0) &&
                    (cross(c, d, a) > 0) != (cross(c, d, b) > 0))

        @JvmStatic
        fun circlePoints(zone: SpawnZone): List<ZonePoint> {
            val circle =
                SpawnZone(zone.id, zone.name, zone.latitude, zone.longitude, zone.radiusMeters)
            return circle.boundary(16).asList().chunked(2).map { ZonePoint(it[0], it[1]) }
        }

        @JvmStatic
        fun quadrilateral(
            latitude: Double,
            longitude: Double,
            radiusMeters: Double,
        ): List<ZonePoint> {
            val boundary = SpawnZone(0, "Shape", latitude, longitude, radiusMeters).boundary(16)
            val north = boundary[0]
            val east = boundary[9]
            val south = boundary[16]
            val west = boundary[25]
            return listOf(
                ZonePoint(north, west),
                ZonePoint(north, east),
                ZonePoint(south, east),
                ZonePoint(south, west),
            )
        }

        @JvmStatic
        fun normalizeLongitude(longitude: Double): Double =
            ((longitude + 180) % 360 + 360) % 360 - 180
    }
}

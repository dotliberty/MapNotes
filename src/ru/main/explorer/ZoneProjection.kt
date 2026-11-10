package ru.main.explorer

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Shared Mercator projection for rendering, edge hit testing and the vertex editor. */
object ZoneProjection {
    @JvmStatic
    fun project(map: MapView, points: List<ZonePoint>): FloatArray {
        if (points.isEmpty()) return FloatArray(0)
        val scale = (1 shl map.zoom).toDouble() * map.viewportFraction
        val anchor = points.first().longitude
        var dx = MapView.x(anchor) - map.cx
        if (dx > 128) dx -= 256
        if (dx < -128) dx += 256
        val origin = map.width / 2.0 + dx * scale
        return FloatArray(points.size * 2).also { result ->
            points.forEachIndexed { index, point ->
                result[index * 2] =
                    (origin +
                            SpawnZone.normalizeLongitude(point.longitude - anchor) / 360 *
                                256 *
                                scale)
                        .toFloat()
                result[index * 2 + 1] =
                    (map.height / 2.0 + (MapView.y(point.latitude) - map.cy) * scale).toFloat()
            }
        }
    }

    @JvmStatic
    fun fit(map: MapView, points: List<ZonePoint>) {
        if (points.isEmpty() || map.width <= 0 || map.height <= 0) return
        val anchor = points.first().longitude
        val xs =
            points.map {
                MapView.x(anchor) + SpawnZone.normalizeLongitude(it.longitude - anchor) / 360 * 256
            }
        val ys = points.map { MapView.y(it.latitude) }
        val width = max(1e-10, xs.max() - xs.min())
        val height = max(1e-10, ys.max() - ys.min())
        val margin = map.dp(72)
        val scale = min(max(32, map.width - margin) / width, max(32, map.height - margin) / height)
        val zoom = floor(ln(scale) / ln(2.0)).toInt().coerceIn(1, 22)
        map.follow = false
        map.moveViewport(
            ((xs.min() + xs.max()) / 2 % 256 + 256) % 256,
            (ys.min() + ys.max()) / 2,
            zoom,
        )
    }

    /** Returns segment index, interpolation factor and squared pixel distance. */
    fun nearestEdge(xy: FloatArray, x: Float, y: Float): DoubleArray {
        var best = doubleArrayOf(-1.0, 0.0, Double.POSITIVE_INFINITY)
        for (index in 0 until xy.size / 2) {
            val next = (index + 1) % (xy.size / 2)
            val ax = xy[index * 2].toDouble()
            val ay = xy[index * 2 + 1].toDouble()
            val dx = xy[next * 2] - ax
            val dy = xy[next * 2 + 1] - ay
            val length = dx * dx + dy * dy
            val fraction =
                if (length > 0) (((x - ax) * dx + (y - ay) * dy) / length).coerceIn(0.0, 1.0)
                else 0.0
            val distance =
                (x - ax - dx * fraction) * (x - ax - dx * fraction) +
                    (y - ay - dy * fraction) * (y - ay - dy * fraction)
            if (distance < best[2]) best = doubleArrayOf(index.toDouble(), fraction, distance)
        }
        return best
    }
}

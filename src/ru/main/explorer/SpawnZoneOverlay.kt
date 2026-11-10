package ru.main.explorer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/** Drawn after fog/reveal compositing; this object never changes the exploration mask. */
class SpawnZoneOverlay {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val boundaryCache =
        object : LruCache<String, DoubleArray>(1024 * 1024) {
            override fun sizeOf(key: String, value: DoubleArray): Int =
                value.size * 8 + key.length * 2
        }
    private var zones: List<SpawnZone> = emptyList()

    fun setZones(value: List<SpawnZone>) {
        zones = value.toList()
        boundaryCache.evictAll()
    }

    fun hit(map: MapView, px: Float, py: Float): List<SpawnZone> {
        if (px < 0 || px > map.width || py < 0 || py >= map.height - map.dp(38)) return emptyList()
        val coordinate = map.zoneCoordinate(px, py)
        val scale = (1 shl map.zoom).toDouble() * map.viewportFraction
        val metersPerPixel = 156543.03392 * cos(Math.toRadians(coordinate[0])) / scale
        return zones.filter {
            if (it.isPolygon) {
                val xy = ZoneProjection.project(map, it.points)
                val world = (256 * scale).toFloat()
                it.contains(coordinate[0], coordinate[1]) ||
                    (-1..1).any { copy ->
                        ZoneProjection.nearestEdge(xy, px - copy * world, py)[2] <=
                            map.dp(12).toDouble() * map.dp(12)
                    }
            } else
                GpsFilter.distance(it.latitude, it.longitude, coordinate[0], coordinate[1]) <=
                    it.radiusMeters + map.dp(12) * metersPerPixel
        }
    }

    fun draw(canvas: Canvas, map: MapView) {
        if (zones.isEmpty() || map.width <= 0 || map.height <= map.dp(38)) return
        val density = map.resources.displayMetrics.density
        val scale = (1 shl map.zoom).toDouble() * map.viewportFraction
        val world = 256 * scale
        val labels = mutableListOf<RectF>()
        val save = canvas.save()
        canvas.clipRect(0, 0, map.width, map.height - map.dp(38))
        try {
            for (zone in zones) {
                if (zone.isPolygon) {
                    drawPolygon(canvas, map, zone, labels)
                    continue
                }
                val angular = zone.radiusMeters / SpawnZone.EARTH_RADIUS
                val latitudeRadius = Math.toDegrees(angular)
                val longitudeRadius =
                    Math.toDegrees(
                        asin(
                            (kotlin.math.sin(angular) / cos(Math.toRadians(zone.latitude)))
                                .coerceIn(-1.0, 1.0)
                        )
                    )
                var dx = MapView.x(zone.longitude) - map.cx
                if (dx > 128) dx -= 256
                if (dx < -128) dx += 256
                val center = map.width / 2.0 + dx * scale
                val centerY = map.height / 2.0 + (MapView.y(zone.latitude) - map.cy) * scale
                val top =
                    map.height / 2.0 + (MapView.y(zone.latitude + latitudeRadius) - map.cy) * scale
                val bottom =
                    map.height / 2.0 + (MapView.y(zone.latitude - latitudeRadius) - map.cy) * scale
                val radiusX = longitudeRadius / 360 * world
                if (bottom < 0 || top > map.height - map.dp(38)) continue
                for (copy in -1..1) {
                    val centerX = center + copy * world
                    if (centerX + radiusX < 0 || centerX - radiusX > map.width) continue
                    val radiusPixels = max(radiusX, (bottom - top) / 2)
                    val count =
                        ceil(Math.PI * sqrt(max(1.0, radiusPixels) / .75))
                            .toInt()
                            .coerceIn(64, 4096)
                    val key =
                        "${zone.id}:${zone.latitude}:${zone.longitude}:${zone.radiusMeters}:$count"
                    val coordinates =
                        boundaryCache.get(key)
                            ?: zone.boundary(count).also { boundaryCache.put(key, it) }
                    val path = Path()
                    for (index in coordinates.indices step 2) {
                        val longitude =
                            SpawnZone.normalizeLongitude(coordinates[index + 1] - zone.longitude)
                        val x = (centerX + longitude / 360 * world).toFloat()
                        val y =
                            (map.height / 2.0 + (MapView.y(coordinates[index]) - map.cy) * scale)
                                .toFloat()
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    paint.style = Paint.Style.FILL
                    paint.color = 0x226b4f9c
                    canvas.drawPath(path, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2 * density
                    paint.color = 0xff7951a8.toInt()
                    canvas.drawPath(path, paint)
                    if (
                        centerX >= 0 &&
                            centerX <= map.width &&
                            centerY >= 0 &&
                            centerY < map.height - map.dp(38)
                    ) {
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(centerX.toFloat(), centerY.toFloat(), 5 * density, paint)
                        if (labels.size < 32) {
                            text.textSize = 12 * density
                            val label =
                                TextUtils.ellipsize(
                                        zone.name,
                                        text,
                                        minOf(
                                            180 * density,
                                            (map.width - 20 * density).coerceAtLeast(1f),
                                        ),
                                        TextUtils.TruncateAt.END,
                                    )
                                    .toString()
                            val width = text.measureText(label)
                            val x =
                                (centerX.toFloat() - width / 2).coerceIn(
                                    8 * density,
                                    max(8 * density, map.width - width - 8 * density),
                                )
                            val y =
                                (centerY.toFloat() - 12 * density).coerceIn(
                                    24 * density,
                                    max(24 * density, map.height - 48 * density),
                                )
                            val bounds =
                                RectF(
                                    x - 5 * density,
                                    y - 15 * density,
                                    x + width + 5 * density,
                                    y + 5 * density,
                                )
                            if (labels.none { RectF.intersects(it, bounds) }) {
                                paint.color = 0xee19283f.toInt()
                                canvas.drawRoundRect(bounds, 5 * density, 5 * density, paint)
                                text.color = 0xffeddbff.toInt()
                                canvas.drawText(label, x, y, text)
                                labels.add(bounds)
                            }
                        }
                    }
                }
            }
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun drawPolygon(
        canvas: Canvas,
        map: MapView,
        zone: SpawnZone,
        labels: MutableList<RectF>,
    ) {
        val xy = ZoneProjection.project(map, zone.points)
        val world = (256 * (1 shl map.zoom).toDouble() * map.viewportFraction).toFloat()
        val density = map.resources.displayMetrics.density
        val minY = xy.indices.filter { it % 2 == 1 }.minOf { xy[it] }
        val maxY = xy.indices.filter { it % 2 == 1 }.maxOf { xy[it] }
        if (maxY < 0 || minY > map.height - map.dp(38)) return
        for (copy in -1..1) {
            val shift = copy * world
            val minX = xy.indices.filter { it % 2 == 0 }.minOf { xy[it] } + shift
            val maxX = xy.indices.filter { it % 2 == 0 }.maxOf { xy[it] } + shift
            if (maxX < 0 || minX > map.width) continue
            val path = Path()
            for (i in xy.indices step 2) {
                if (i == 0) path.moveTo(xy[i] + shift, xy[i + 1])
                else path.lineTo(xy[i] + shift, xy[i + 1])
            }
            path.close()
            paint.style = Paint.Style.FILL
            paint.color = 0x226b4f9c
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE
            paint.color = 0xff7951a8.toInt()
            paint.strokeWidth = 2 * density
            canvas.drawPath(path, paint)
            val centerX = (minX + maxX) / 2
            val centerY = (minY + maxY) / 2
            if (
                labels.size >= 32 ||
                    centerX !in 0f..map.width.toFloat() ||
                    centerY !in 0f..(map.height - map.dp(38)).toFloat()
            )
                continue
            text.textSize = 12 * density
            val label =
                TextUtils.ellipsize(
                        zone.name,
                        text,
                        minOf(180 * density, (map.width - 20 * density).coerceAtLeast(1f)),
                        TextUtils.TruncateAt.END,
                    )
                    .toString()
            val width = text.measureText(label)
            val x =
                (centerX - width / 2).coerceIn(
                    8 * density,
                    max(8 * density, map.width - width - 8 * density),
                )
            val y = centerY.coerceIn(24 * density, max(24 * density, map.height - 48 * density))
            val bounds =
                RectF(x - 5 * density, y - 15 * density, x + width + 5 * density, y + 5 * density)
            if (labels.none { RectF.intersects(it, bounds) }) {
                paint.style = Paint.Style.FILL
                paint.color = 0xee19283f.toInt()
                canvas.drawRoundRect(bounds, 5 * density, 5 * density, paint)
                text.color = 0xffeddbff.toInt()
                canvas.drawText(label, x, y, text)
                labels.add(bounds)
            }
        }
    }
}

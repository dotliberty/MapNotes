package ru.main.explorer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.widget.Toast
import java.util.ArrayDeque

/** Draft-only vertex manipulation. No database or service access. */
class ZoneShapeEditor(
    private val map: MapView,
    private val zone: SpawnZone,
    initial: List<ZonePoint>,
    private val changed: Runnable,
) {
    private var draft = initial.toList()
    private val history = ArrayDeque<List<ZonePoint>>()
    private var beforeDrag: List<ZonePoint>? = null
    private var consuming = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var selected = -1
    var adding = false
    val points: List<ZonePoint>
        get() = draft.toList()

    val canUndo: Boolean
        get() = !history.isEmpty()

    val canDelete: Boolean
        get() = selected in draft.indices && draft.size > 3

    init {
        zone.copy(points = initial)
    }

    private fun valid(): Boolean =
        try {
            zone.copy(points = draft)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun refresh() {
        map.invalidate()
        changed.run()
    }

    private fun remember(value: List<ZonePoint>) {
        if (history.size >= 32) history.removeFirst()
        history.addLast(value.toList())
    }

    fun undo() {
        cancelGesture()
        if (history.isEmpty()) return
        draft = history.removeLast()
        selected = -1
        refresh()
    }

    fun deleteSelected() {
        cancelGesture()
        if (!canDelete) return
        val old = draft
        draft = draft.filterIndexed { index, _ -> index != selected }
        if (!valid()) {
            draft = old
            message(R.string.zone_invalid_shape)
        } else {
            remember(old)
            selected = -1
        }
        refresh()
    }

    fun cancelGesture() {
        beforeDrag?.let { draft = it }
        beforeDrag = null
        consuming = false
        refresh()
    }

    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val xy = ZoneProjection.project(map, draft)
                val nearest =
                    draft.indices.minByOrNull { index ->
                        val dx = xy[index * 2] - event.x
                        val dy = xy[index * 2 + 1] - event.y
                        dx * dx + dy * dy
                    } ?: return false
                val dx = xy[nearest * 2] - event.x
                val dy = xy[nearest * 2 + 1] - event.y
                val hit = dx * dx + dy * dy <= map.dp(18).toFloat() * map.dp(18)
                if (adding) {
                    val edge = ZoneProjection.nearestEdge(xy, event.x, event.y)
                    if (edge[2] > map.dp(26).toDouble() * map.dp(26)) return false
                    consuming = true
                    if (draft.size >= SpawnZone.MAX_VERTICES) {
                        message(R.string.zone_vertex_limit)
                        return true
                    }
                    val index = edge[0].toInt()
                    val next = (index + 1) % draft.size
                    val fraction = edge[1].coerceIn(.05, .95)
                    val x = xy[index * 2] + (xy[next * 2] - xy[index * 2]) * fraction
                    val y = xy[index * 2 + 1] + (xy[next * 2 + 1] - xy[index * 2 + 1]) * fraction
                    val coordinate = map.zoneCoordinate(x.toFloat(), y.toFloat())
                    beforeDrag = draft
                    selected = index + 1
                    adding = false
                    draft =
                        draft.toMutableList().also {
                            it.add(selected, ZonePoint(coordinate[0], coordinate[1]))
                        }
                } else if (hit) {
                    consuming = true
                    selected = nearest
                    beforeDrag = draft
                } else {
                    selected = -1
                    refresh()
                    return false
                }
                refresh()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!consuming) return false
                cancelGesture()
                // The map did not receive DOWN while a vertex was being dragged.
                val down =
                    MotionEvent.obtain(
                        event.downTime,
                        event.eventTime,
                        MotionEvent.ACTION_DOWN,
                        event.getX(0),
                        event.getY(0),
                        0,
                    )
                map.handleMapTouch(down)
                down.recycle()
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!consuming) return false
                if (beforeDrag != null && selected in draft.indices) {
                    val coordinate = map.zoneCoordinate(event.x, event.y)
                    draft =
                        draft.toMutableList().also {
                            it[selected] = ZonePoint(coordinate[0], coordinate[1])
                        }
                    refresh()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!consuming) return false
                val old = beforeDrag
                if (old != null) {
                    if (!valid()) {
                        draft = old
                        message(R.string.zone_invalid_shape)
                    } else if (draft != old) remember(old)
                }
                beforeDrag = null
                consuming = false
                refresh()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!consuming) return false
                cancelGesture()
                return true
            }
        }
        return consuming
    }

    fun draw(canvas: Canvas) {
        val xy = ZoneProjection.project(map, draft)
        val path = Path()
        for (index in xy.indices step 2) {
            if (index == 0) path.moveTo(xy[index], xy[index + 1])
            else path.lineTo(xy[index], xy[index + 1])
        }
        path.close()
        val save = canvas.save()
        canvas.clipRect(0, 0, map.width, (map.height - map.dp(38)).coerceAtLeast(0))
        paint.style = Paint.Style.FILL
        paint.color = if (valid()) 0x337951a8 else 0x55dc4545
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = map.dp(2).toFloat()
        paint.color = if (valid()) 0xff7951a8.toInt() else 0xffdc4545.toInt()
        canvas.drawPath(path, paint)
        for (index in draft.indices) {
            val x = xy[index * 2]
            val y = xy[index * 2 + 1]
            paint.style = Paint.Style.FILL
            paint.color = if (index == selected) AtlasUi.GOLD else 0xff7951a8.toInt()
            canvas.drawCircle(x, y, map.dp(8).toFloat(), paint)
            paint.style = Paint.Style.STROKE
            paint.color = AtlasUi.CREAM
            canvas.drawCircle(x, y, map.dp(8).toFloat(), paint)
            if (adding && draft.size < SpawnZone.MAX_VERTICES) {
                val next = (index + 1) % draft.size
                val mx = (x + xy[next * 2]) / 2
                val my = (y + xy[next * 2 + 1]) / 2
                paint.style = Paint.Style.FILL
                paint.color = AtlasUi.INK
                canvas.drawCircle(mx, my, map.dp(10).toFloat(), paint)
                paint.color = AtlasUi.GOLD
                canvas.drawLine(mx - map.dp(5), my, mx + map.dp(5), my, paint)
                canvas.drawLine(mx, my - map.dp(5), mx, my + map.dp(5), paint)
            }
        }
        canvas.restoreToCount(save)
    }

    private fun message(resource: Int) =
        Toast.makeText(map.context, resource, Toast.LENGTH_SHORT).show()
}

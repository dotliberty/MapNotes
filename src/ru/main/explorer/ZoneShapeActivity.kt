package ru.main.explorer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Full-screen, local draft editor. Applying a contour returns to the metadata form, not SQLite. */
class ZoneShapeActivity : Activity() {
    private lateinit var map: MapView
    private lateinit var editor: ZoneShapeEditor
    private lateinit var original: SpawnZone
    private lateinit var count: TextView
    private lateinit var add: Button
    private lateinit var remove: Button
    private lateinit var undo: Button

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Language.initialize(base)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Language.initialize(this)
        original =
            try {
                decode(intent)
            } catch (_: IllegalArgumentException) {
                Toast.makeText(this, R.string.zone_invalid_shape, Toast.LENGTH_LONG).show()
                finish()
                return
            }
        setResult(RESULT_CANCELED, payload(original))
        window.statusBarColor = AtlasUi.INK
        window.navigationBarColor = AtlasUi.INK
        window.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        )
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(AtlasUi.INK)
                setOnApplyWindowInsetsListener { view, insets ->
                    view.setPadding(
                        insets.systemWindowInsetLeft,
                        insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight,
                        insets.systemWindowInsetBottom,
                    )
                    insets
                }
            }
        count =
            TextView(this).apply {
                setTextColor(AtlasUi.GOLD)
                textSize = 18f
                setPadding(16, 8, 16, 4)
            }
        root.addView(count)
        root.addView(
            TextView(this).apply {
                setText(R.string.zone_shape_hint)
                setTextColor(AtlasUi.CREAM)
                textSize = 13f
                setPadding(16, 0, 16, 8)
            }
        )
        map =
            MapView(this).apply {
                follow = false
                online = intent.getBooleanExtra("zone_online", false)
            }
        root.addView(map, LinearLayout.LayoutParams(-1, 0, 1f))
        fun row(): LinearLayout = LinearLayout(this).also { root.addView(it) }
        fun button(row: LinearLayout, resource: Int, action: () -> Unit): Button =
            Button(this).apply {
                setText(resource)
                AtlasUi.styleButton(this, false)
                textSize = 14f
                row.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
                setOnClickListener { action() }
            }
        val first = row()
        add =
            button(first, R.string.zone_add_vertex) {
                editor.adding = !editor.adding
                update()
            }
        remove = button(first, R.string.zone_remove_vertex) { editor.deleteSelected() }
        val second = row()
        undo = button(second, R.string.zone_undo_vertex) { editor.undo() }
        button(second, R.string.zone_fit) { ZoneProjection.fit(map, editor.points) }
        val last = row()
        button(last, android.R.string.cancel) { finish() }
        button(last, R.string.zone_apply_shape) {
            editor.cancelGesture()
            try {
                setResult(RESULT_OK, payload(original.copy(points = editor.points)))
                finish()
            } catch (_: IllegalArgumentException) {
                Toast.makeText(this, R.string.zone_invalid_shape, Toast.LENGTH_LONG).show()
            }
        }
        val initial =
            state?.getDoubleArray("vertices")?.let { unpack(it) }
                ?: if (original.isPolygon) original.points else SpawnZone.circlePoints(original)
        editor = ZoneShapeEditor(map, original, initial, Runnable { update() })
        editor.selected = state?.getInt("selected", -1) ?: -1
        editor.adding = state?.getBoolean("adding", false) ?: false
        map.setZoneEditor(editor)
        setContentView(root)
        if (state != null) {
            map.cx = state.getDouble("cx", MapView.x(original.longitude))
            map.cy = state.getDouble("cy", MapView.y(original.latitude))
            map.zoom = state.getInt("zoom", 17)
            map.viewportFraction = state.getFloat("fraction", 1f)
        } else {
            map.addOnLayoutChangeListener(
                object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        view: View,
                        l: Int,
                        t: Int,
                        r: Int,
                        b: Int,
                        ol: Int,
                        ot: Int,
                        or: Int,
                        ob: Int,
                    ) {
                        if (map.width > 0 && map.height > 0) {
                            map.removeOnLayoutChangeListener(this)
                            ZoneProjection.fit(map, editor.points)
                        }
                    }
                }
            )
        }
        update()
    }

    private fun update() {
        if (!::editor.isInitialized) return
        count.text =
            getString(R.string.zone_shape_count, editor.points.size, SpawnZone.MAX_VERTICES)
        add.isSelected = editor.adding
        add.setText(
            if (editor.adding) R.string.zone_add_vertex_active else R.string.zone_add_vertex
        )
        remove.isEnabled = editor.canDelete
        undo.isEnabled = editor.canUndo
        map.invalidate()
    }

    override fun onSaveInstanceState(state: Bundle) {
        if (::editor.isInitialized) {
            editor.cancelGesture()
            state.putDoubleArray("vertices", pack(editor.points))
            state.putInt("selected", editor.selected)
            state.putBoolean("adding", editor.adding)
            state.putDouble("cx", map.cx)
            state.putDouble("cy", map.cy)
            state.putInt("zoom", map.zoom)
            state.putFloat("fraction", map.viewportFraction)
        }
        super.onSaveInstanceState(state)
    }

    override fun onPause() {
        if (::editor.isInitialized) editor.cancelGesture()
        if (::map.isInitialized) map.stopViewport()
        cancelMotion(findViewById(android.R.id.content))
        super.onPause()
    }

    override fun onDestroy() {
        if (::map.isInitialized) map.close()
        super.onDestroy()
    }

    private fun cancelMotion(view: View?) {
        if (view == null) return
        AtlasMotion.cancel(view)
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) cancelMotion(view.getChildAt(index))
        }
    }

    companion object {
        const val REQUEST = 131

        @JvmStatic
        fun payload(zone: SpawnZone): Intent =
            Intent()
                .putExtra("id", zone.id)
                .putExtra("name", zone.name)
                .putExtra("latitude", zone.latitude)
                .putExtra("longitude", zone.longitude)
                .putExtra("radius", zone.radiusMeters)
                .putExtra("points", pack(zone.points))

        @JvmStatic
        fun decode(data: Intent): SpawnZone =
            SpawnZone(
                data.getLongExtra("id", 0),
                data.getStringExtra("name") ?: "",
                data.getDoubleExtra("latitude", Double.NaN),
                data.getDoubleExtra("longitude", Double.NaN),
                data.getDoubleExtra("radius", Double.NaN),
                unpack(data.getDoubleArrayExtra("points") ?: doubleArrayOf()),
            )

        private fun pack(points: List<ZonePoint>): DoubleArray =
            points.flatMap { listOf(it.latitude, it.longitude) }.toDoubleArray()

        private fun unpack(values: DoubleArray): List<ZonePoint> {
            require(values.size % 2 == 0 && values.size <= SpawnZone.MAX_VERTICES * 2)
            return values.indices.step(2).map { ZonePoint(values[it], values[it + 1]) }
        }
    }
}

package ru.main.explorer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.max
import ru.main.explorer.motion.MovementType

class MovementActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var generation = 0
    private var walk = 0L
    private var undo: MovementStore.Correction? = null
    private var live: TextView? = null
    private val ticker =
        object : Runnable {
            override fun run() {
                live?.text =
                    getString(
                        R.string.motion_live,
                        label(
                            this@MovementActivity,
                            MotionRuntime.type(this@MovementActivity, Double.NaN),
                        ),
                    )
                ui.postDelayed(this, 2_000)
            }
        }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Language.initialize(base)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Language.initialize(this)
        walk = state?.getLong("walk") ?: intent.getLongExtra("walk", 0L)
        window.statusBarColor = AtlasUi.INK
        window.navigationBarColor = AtlasUi.INK
    }

    override fun onResume() {
        super.onResume()
        reload()
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putLong("walk", walk)
        super.onSaveInstanceState(out)
    }

    private fun reload() {
        val token = ++generation
        worker.execute {
            try {
                val walks = Store(this).use { it.walks() }
                if (walk == 0L) walk = walks.firstOrNull()?.id ?: 0L
                val segments = MovementStore(this).use { it.segments(walk) }
                val profile = MovementStore(this).use { it.profile() }
                ui.post {
                    if (!isDestroyed && token == generation)
                        render(walks, segments, profile.walkCount, profile.runCount, profile.ready)
                }
            } catch (_: Exception) {
                ui.post { if (!isDestroyed) toast(R.string.motion_error) }
            }
        }
    }

    private fun render(
        walks: List<Store.Walk>,
        segments: List<MovementStore.Segment>,
        walking: Int,
        running: Int,
        ready: Boolean,
    ) {
        val body =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 36, 20, 32)
                setBackgroundColor(AtlasUi.INK)
            }
        fun text(value: String) =
            TextView(this).apply {
                setTextColor(AtlasUi.CREAM)
                textSize = 16f
                text = value
                setPadding(8, 12, 8, 12)
                body.addView(this)
            }
        fun button(value: String, action: () -> Unit) =
            Button(this).apply {
                text = value
                AtlasUi.styleButton(this, false)
                body.addView(this)
                setOnClickListener { action() }
            }
        text(getString(R.string.motion_title)).textSize = 25f
        text(getString(R.string.motion_start_hint))
        button(
            getString(
                R.string.motion_mode,
                MotionRuntime.mode(this)?.let { label(this, it) } ?: getString(R.string.motion_auto),
            )
        ) {
            val types = listOf(null, MovementType.WALK, MovementType.RUN, MovementType.VEHICLE)
            val labels =
                types.map { it?.let { label(this, it) } ?: getString(R.string.motion_auto) }
            AtlasUi.DialogBuilder(this)
                .setTitle(R.string.motion_mode_title)
                .setItems(labels.toTypedArray()) { _, index ->
                    MotionRuntime.setMode(this, types[index])
                    reload()
                }
                .show()
        }
        live =
            text(getString(R.string.motion_live, label(this, MotionRuntime.type(this, Double.NaN))))
        text(
            getString(
                if (MotionRuntime.canRecognize(this)) R.string.motion_available
                else R.string.motion_unavailable
            )
        )
        button(getString(R.string.motion_enable)) {
            if (
                Build.VERSION.SDK_INT >= 29 &&
                    checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) !=
                        PackageManager.PERMISSION_GRANTED
            )
                requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 130)
            else {
                if (WalkService.running) MotionRuntime.start(this)
                reload()
            }
        }
        val preferences = getSharedPreferences("motion", 0)
        text(
            getString(
                R.string.motion_profile,
                walking,
                running,
                getString(if (ready) R.string.motion_ready else R.string.motion_learning),
            )
        )
        button(
            getString(
                R.string.motion_profile_toggle,
                getString(
                    if (preferences.getBoolean("personal", true)) R.string.motion_on
                    else R.string.motion_off
                ),
            )
        ) {
            preferences
                .edit()
                .putBoolean("personal", !preferences.getBoolean("personal", true))
                .apply()
            reload()
        }
        button(getString(R.string.motion_reset)) {
            AtlasUi.DialogBuilder(this)
                .setMessage(R.string.motion_reset_question)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    change {
                        it.resetProfile()
                        undo = null
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        button(getString(R.string.motion_history)) {
            val labels = walks.map { "#${it.id} · ${Language.date(this, it.started)}" }
            AtlasUi.DialogBuilder(this)
                .setTitle(R.string.motion_history)
                .setItems(labels.toTypedArray()) { _, index ->
                    walk = walks[index].id
                    undo = null
                    reload()
                }
                .show()
        }
        text(getString(R.string.motion_map_notice))
        if (segments.isEmpty()) text(getString(R.string.motion_empty))
        else {
            body.addView(RouteSchematic(this, segments), LinearLayout.LayoutParams(-1, 320))
            for (type in MovementType.values()) {
                val own = segments.filter { it.type == type }
                text(
                    getString(
                        R.string.motion_summary,
                        label(this, type),
                        own.sumOf { it.meters } / 1000,
                        own.sumOf { it.movingMs } / 1000,
                        own.sumOf { it.stoppedMs } / 1000,
                    )
                )
            }
            text(getString(R.string.motion_help))
            if (undo != null)
                button(getString(R.string.motion_undo)) {
                    val snapshot = undo ?: return@button
                    change {
                        it.undo(snapshot)
                        undo = null
                    }
                }
            // Render bounded pages of controls; all coordinates remain in the database.
            var shown = 0
            val more = Button(this)
            fun appendPage() {
                body.removeView(more)
                val until = minOf(shown + 100, segments.size)
                for (segment in segments.subList(shown, until)) {
                    button(
                        getString(
                            R.string.motion_segment,
                            label(this, segment.type),
                            segment.meters / 1000,
                            segment.durationMs / 1000,
                            getString(
                                when (segment.source) {
                                    "manual" -> R.string.motion_manual_source
                                    "legacy" -> R.string.motion_legacy
                                    else -> R.string.motion_auto_source
                                }
                            ),
                        )
                    ) {
                        segmentActions(segment)
                    }
                }
                shown = until
                if (shown < segments.size) body.addView(more)
            }
            more.text = getString(R.string.motion_more)
            AtlasUi.styleButton(more, false)
            more.setOnClickListener { appendPage() }
            appendPage()
        }
        button(getString(R.string.close)) { finish() }
        val scroll = ScrollView(this).apply { addView(body) }
        UiInsets.apply(scroll, 0, 0, 0, 0)
        setContentView(scroll)
    }

    private fun segmentActions(segment: MovementStore.Segment) {
        AtlasUi.DialogBuilder(this)
            .setTitle(label(this, segment.type))
            .setItems(
                arrayOf(getString(R.string.motion_correct), getString(R.string.motion_confirm))
            ) { _, action ->
                if (action == 0) chooseInterval(segment)
                else
                    change {
                        val accepted = it.confirm(segment)
                        if (accepted) undo = null
                        ui.post {
                            if (!isDestroyed)
                                toast(
                                    if (accepted) R.string.motion_confirmed
                                    else R.string.motion_sample_rejected
                                )
                        }
                    }
            }
            .show()
    }

    private fun chooseInterval(segment: MovementStore.Segment) {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val range = TextView(this)
        val first = SeekBar(this).apply { max = segment.points.lastIndex }
        val last =
            SeekBar(this).apply {
                max = segment.points.lastIndex
                progress = max
            }
        fun describe() {
            range.text = getString(R.string.motion_interval, first.progress + 1, last.progress + 1)
        }
        val listener =
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) =
                    describe()

                override fun onStartTrackingTouch(bar: SeekBar?) {}

                override fun onStopTrackingTouch(bar: SeekBar?) {}
            }
        first.setOnSeekBarChangeListener(listener)
        last.setOnSeekBarChangeListener(listener)
        first.contentDescription = getString(R.string.motion_interval_start)
        last.contentDescription = getString(R.string.motion_interval_end)
        panel.addView(range)
        panel.addView(first)
        panel.addView(last)
        describe()
        AtlasUi.DialogBuilder(this)
            .setTitle(R.string.motion_correct)
            .setView(panel)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (first.progress > last.progress) return@setPositiveButton
                val selected =
                    MovementStore.Segment(
                        segment.points.subList(first.progress, last.progress + 1).toMutableList()
                    )
                val types =
                    listOf(
                        MovementType.UNKNOWN,
                        MovementType.WALK,
                        MovementType.RUN,
                        MovementType.VEHICLE,
                    )
                AtlasUi.DialogBuilder(this)
                    .setItems(types.map { label(this, it) }.toTypedArray()) { _, index ->
                        change { undo = it.correct(selected, types[index]) }
                    }
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun change(action: (MovementStore) -> Unit) {
        worker.execute {
            try {
                MovementStore(this).use(action)
                ui.post {
                    if (!isDestroyed) {
                        MotionRuntime.reloadProfile(this)
                        reload()
                    }
                }
            } catch (_: Exception) {
                ui.post { if (!isDestroyed) toast(R.string.motion_error) }
            }
        }
    }

    private fun toast(resource: Int) = Toast.makeText(this, resource, Toast.LENGTH_LONG).show()

    override fun onRequestPermissionsResult(
        code: Int,
        permissions: Array<out String>,
        results: IntArray,
    ) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 130) {
            if (WalkService.running) MotionRuntime.start(this)
            reload()
        }
    }

    override fun onDestroy() {
        generation++
        ui.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        @JvmStatic
        fun label(context: Context, type: MovementType): String =
            context.getString(
                when (type) {
                    MovementType.WALK -> R.string.motion_walk
                    MovementType.RUN -> R.string.motion_run
                    MovementType.VEHICLE -> R.string.motion_vehicle
                    MovementType.ON_FOOT -> R.string.motion_foot
                    MovementType.UNKNOWN -> R.string.motion_unknown
                }
            )

        fun color(type: MovementType): Int =
            when (type) {
                MovementType.WALK -> 0xffdec38d.toInt()
                MovementType.RUN -> 0xffef926b.toInt()
                MovementType.VEHICLE -> 0xff82b8e5.toInt()
                MovementType.ON_FOOT -> 0xffadd8a0.toInt()
                MovementType.UNKNOWN -> 0xffb8b8b8.toInt()
            }
    }

    private class RouteSchematic(
        context: Context,
        private val segments: List<MovementStore.Segment>,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 5f }
        private val points = segments.flatMap { it.points }
        private val origin = points.firstOrNull()

        private fun x(point: MovementStore.Point): Double {
            val delta = (point.lon - (origin?.lon ?: 0.0) + 540.0) % 360.0 - 180.0
            return delta * cos(Math.toRadians(origin?.lat ?: 0.0))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (points.isEmpty()) return
            val left = points.minOf { x(it) }
            val right = points.maxOf { x(it) }
            val bottom = points.minOf { it.lat }
            val top = points.maxOf { it.lat }
            val scale =
                minOf(
                    (width - 40) / max(right - left, 0.00001),
                    (height - 40) / max(top - bottom, 0.00001),
                )
            var previous: MovementStore.Point? = null
            for (point in points) {
                val a = previous
                paint.color = color(point.type)
                val px = (20 + (x(point) - left) * scale).toFloat()
                val py = (height - 20 - (point.lat - bottom) * scale).toFloat()
                if (a != null && MovementStore.edgeMs(a, point) > 0) {
                    canvas.drawLine(
                        (20 + (x(a) - left) * scale).toFloat(),
                        (height - 20 - (a.lat - bottom) * scale).toFloat(),
                        px,
                        py,
                        paint,
                    )
                } else canvas.drawCircle(px, py, 5f, paint)
                previous = point
            }
        }
    }
}

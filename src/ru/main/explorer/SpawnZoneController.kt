package ru.main.explorer

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.ln

/**
 * Activity-owned editor: off-thread SQLite, immutable map snapshots, no GPS or network requests.
 */
class SpawnZoneController(private val activity: Activity, private val map: MapView) :
    AutoCloseable {
    private val application = activity.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val dialogs = mutableSetOf<AlertDialog>()
    @Volatile private var closed = false

    init {
        map.setZoneLongPressListener { latitude, longitude -> createAt(latitude, longitude) }
        map.setZoneTapListener { zones -> choose(zones) }
        reload()
    }

    fun reload() =
        work({ SpawnZoneStore(application).use { it.list() } }) { zones ->
            map.setSpawnZones(zones)
        }

    fun show() =
        work({ SpawnZoneStore(application).use { it.list() } }) { zones ->
            val builder =
                AtlasUi.DialogBuilder(activity)
                    .setTitle(R.string.zones_title)
                    .setPositiveButton(R.string.zone_add_center) { _, _ ->
                        createAt(map.centerLatitude(), map.centerLongitude())
                    }
                    .setNeutralButton(R.string.zone_help) { _, _ ->
                        showDialog(
                            AtlasUi.DialogBuilder(activity)
                                .setTitle(R.string.zone_help)
                                .setMessage(R.string.zone_help_message)
                                .setPositiveButton(android.R.string.ok, null)
                                .create()
                        )
                    }
                    .setNegativeButton(android.R.string.cancel, null)
            if (zones.isEmpty()) builder.setMessage(R.string.zone_empty)
            else
                builder.setItems(zones.map { label(it) }.toTypedArray()) { _, index ->
                    actions(zones[index])
                }
            showDialog(builder.create())
        }

    fun createAt(latitude: Double, longitude: Double) = edit(null, latitude, longitude)

    fun shapeResult(data: Intent?) {
        if (data == null || closed) return
        try {
            val draft = ZoneShapeActivity.decode(data)
            edit(draft, draft.latitude, draft.longitude)
        } catch (_: IllegalArgumentException) {
            toast(R.string.zone_invalid_shape)
        }
    }

    private fun choose(zones: List<SpawnZone>) {
        if (zones.isEmpty()) return
        if (zones.size == 1) actions(zones.first())
        else
            showDialog(
                AtlasUi.DialogBuilder(activity)
                    .setTitle(R.string.zones_title)
                    .setItems(zones.map { label(it) }.toTypedArray()) { _, index ->
                        actions(zones[index])
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
            )
    }

    private fun actions(zone: SpawnZone) {
        showDialog(
            AtlasUi.DialogBuilder(activity)
                .setTitle(label(zone))
                .setItems(
                    arrayOf(
                        activity.getString(R.string.zone_show),
                        activity.getString(R.string.zone_edit),
                        activity.getString(R.string.zone_delete),
                    )
                ) { _, index ->
                    when (index) {
                        0 -> showOnMap(zone)
                        1 -> edit(zone, zone.latitude, zone.longitude)
                        2 ->
                            showDialog(
                                AtlasUi.DialogBuilder(activity)
                                    .setMessage(
                                        activity.getString(R.string.zone_delete_question, zone.name)
                                    )
                                    .setPositiveButton(R.string.zone_delete) { _, _ ->
                                        work({
                                            SpawnZoneStore(application).use {
                                                check(it.delete(zone.id)) { "missing" }
                                                it.list()
                                            }
                                        }) {
                                            map.setSpawnZones(it)
                                            toast(R.string.zone_deleted)
                                        }
                                    }
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .create()
                            )
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        )
    }

    private fun showOnMap(zone: SpawnZone) {
        if (zone.isPolygon) {
            ZoneProjection.fit(map, zone.points)
            return
        }
        val size = minOf(map.width, map.height).coerceAtLeast(160)
        val metersAtZoomZero = 156543.03392 * kotlin.math.cos(Math.toRadians(zone.latitude))
        val target =
            floor(ln(metersAtZoomZero * (size - 80) / (2 * zone.radiusMeters)) / ln(2.0))
                .toInt()
                .coerceIn(1, 22)
        map.follow = false
        map.moveViewport(MapView.x(zone.longitude), MapView.y(zone.latitude), target)
    }

    private fun edit(zone: SpawnZone?, latitude: Double, longitude: Double) {
        if (closed || activity.isFinishing || activity.isDestroyed) return
        val body =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 12, 24, 12)
            }
        fun field(resource: Int, value: String, numeric: Boolean): EditText {
            body.addView(
                TextView(activity).apply {
                    text = activity.getString(resource)
                    setTextColor(AtlasUi.CREAM)
                    setPadding(0, 12, 0, 0)
                }
            )
            val input =
                EditText(activity).apply {
                    setSingleLine(true)
                    setText(value)
                    hint = activity.getString(resource)
                    contentDescription = hint
                    setTextColor(AtlasUi.CREAM)
                    inputType =
                        if (numeric)
                            InputType.TYPE_CLASS_NUMBER or
                                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                                InputType.TYPE_NUMBER_FLAG_SIGNED
                        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                }
            body.addView(input)
            return input
        }
        val name =
            field(
                R.string.zone_name,
                zone?.name ?: activity.getString(R.string.zone_default_name),
                false,
            )
        name.filters = arrayOf(InputFilter.LengthFilter(SpawnZone.MAX_NAME_LENGTH))
        val shape = RadioGroup(activity)
        val circle =
            RadioButton(activity).apply {
                id = View.generateViewId()
                setText(R.string.zone_circle)
                setTextColor(AtlasUi.CREAM)
            }
        val polygon =
            RadioButton(activity).apply {
                id = View.generateViewId()
                setText(if (zone?.isPolygon == true) R.string.zone_polygon else R.string.zone_quad)
                setTextColor(AtlasUi.CREAM)
            }
        shape.addView(circle)
        shape.addView(polygon)
        shape.check(if (zone?.isPolygon == true) polygon.id else circle.id)
        body.addView(shape)
        if (zone?.isPolygon == true)
            body.addView(
                TextView(activity).apply {
                    setText(R.string.zone_polygon_hint)
                    setTextColor(AtlasUi.MUTED)
                }
            )
        val radius = field(R.string.zone_radius, (zone?.radiusMeters ?: 100.0).toString(), true)
        val lat = field(R.string.zone_latitude, String.format(Locale.US, "%.7f", latitude), true)
        val lon = field(R.string.zone_longitude, String.format(Locale.US, "%.7f", longitude), true)
        val radiusLabel = body.getChildAt(body.indexOfChild(radius) - 1) as TextView
        fun reflectType() {
            val keepContour = zone?.isPolygon == true && shape.checkedRadioButtonId == polygon.id
            radius.isEnabled = !keepContour
            lat.isEnabled = !keepContour
            lon.isEnabled = !keepContour
            val label =
                if (shape.checkedRadioButtonId == circle.id) R.string.zone_radius
                else R.string.zone_half_size
            radiusLabel.setText(label)
            radius.contentDescription = activity.getString(label)
            if (keepContour) {
                radius.setText(zone!!.radiusMeters.toString())
                lat.setText(String.format(Locale.US, "%.7f", zone.latitude))
                lon.setText(String.format(Locale.US, "%.7f", zone.longitude))
            }
        }
        shape.setOnCheckedChangeListener { _, _ -> reflectType() }
        reflectType()
        val dialog =
            AtlasUi.DialogBuilder(activity)
                .setTitle(R.string.zones_title)
                .setView(ScrollView(activity).apply { addView(body) })
                .setPositiveButton(R.string.zone_save, null)
                .setNeutralButton(R.string.zone_edit_contour, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        showDialog(dialog)
        fun readDraft(): SpawnZone? {
            name.error = null
            radius.error = null
            lat.error = null
            lon.error = null
            val draft =
                try {
                    SpawnZone(
                        zone?.id ?: 0,
                        name.text.toString().trim(),
                        parse(lat),
                        parse(lon),
                        parse(radius),
                        if (shape.checkedRadioButtonId == circle.id) emptyList()
                        else if (zone?.isPolygon == true) zone.points
                        else SpawnZone.quadrilateral(parse(lat), parse(lon), parse(radius)),
                    )
                } catch (error: IllegalArgumentException) {
                    when (error.message) {
                        "name" -> name.error = activity.getString(R.string.zone_name_error)
                        "radius" -> radius.error = activity.getString(R.string.zone_radius_error)
                        else -> {
                            lat.error = activity.getString(R.string.zone_coordinate_error)
                            lon.error = activity.getString(R.string.zone_coordinate_error)
                        }
                    }
                    return null
                }
            return draft
        }
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            val draft = readDraft() ?: return@setOnClickListener
            val request =
                ZoneShapeActivity.payload(draft)
                    .setClass(activity, ZoneShapeActivity::class.java)
                    .putExtra("zone_online", map.online)
            activity.startActivityForResult(request, ZoneShapeActivity.REQUEST)
            dialog.dismiss()
        }
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val draft = readDraft() ?: return@setOnClickListener
            val save = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            save.isEnabled = false
            // The editor remains open on validation/storage failure. Cancel never writes a draft.
            work(
                {
                    SpawnZoneStore(application).use { repository ->
                        repository.save(draft)
                        repository.list()
                    }
                },
                failed = { error ->
                    save.isEnabled = true
                    failure(error)
                },
            ) { zones ->
                dialog.dismiss()
                map.setSpawnZones(zones)
                toast(R.string.zone_saved)
            }
        }
    }

    private fun parse(field: EditText): Double =
        field.text.toString().trim().replace(',', '.').toDoubleOrNull() ?: Double.NaN

    private fun label(zone: SpawnZone): String =
        if (zone.isPolygon)
            activity.getString(R.string.zone_polygon_label, zone.name, zone.points.size)
        else activity.getString(R.string.zone_label, zone.name, zone.radiusMeters)

    private fun showDialog(dialog: AlertDialog) {
        if (closed || activity.isFinishing || activity.isDestroyed) return
        dialogs.add(dialog)
        dialog.setOnDismissListener { dialogs.remove(dialog) }
        AtlasUi.show(dialog)
    }

    private fun <T> work(
        block: () -> T,
        failed: (Exception) -> Unit = ::failure,
        done: (T) -> Unit,
    ) {
        if (closed) return
        worker.execute {
            try {
                val value = block()
                ui.post {
                    if (!closed && !activity.isFinishing && !activity.isDestroyed) done(value)
                }
            } catch (error: Exception) {
                ui.post {
                    if (!closed && !activity.isFinishing && !activity.isDestroyed) failed(error)
                }
            }
        }
    }

    private fun failure(error: Exception) =
        toast(
            when (error.message) {
                "limit" -> R.string.zone_limit
                "missing" -> R.string.zone_missing
                else -> R.string.zone_error
            }
        )

    private fun toast(resource: Int) = Toast.makeText(activity, resource, Toast.LENGTH_LONG).show()

    override fun close() {
        closed = true
        ui.removeCallbacksAndMessages(null)
        dialogs.toList().forEach { it.dismiss() }
        dialogs.clear()
        map.setZoneLongPressListener(null)
        map.setZoneTapListener(null)
        worker.shutdownNow()
    }
}

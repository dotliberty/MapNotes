package ru.main.explorer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import org.json.JSONArray
import ru.main.explorer.motion.ConfirmedMotionSample
import ru.main.explorer.motion.MovementType
import ru.main.explorer.motion.PersonalMotionProfile

/** Point metadata is additive: legacy coordinates/photos and their IDs are never rewritten. */
class MovementStore(context: Context) : AutoCloseable {
    private val store = Store(context)

    data class Point(
        val id: Long,
        val lat: Double,
        val lon: Double,
        val session: Long,
        val time: Long,
        val elapsed: Long,
        val accuracy: Double,
        val speed: Double?,
        val type: MovementType,
        val source: String,
        val stopped: Boolean,
    )

    class Segment(val points: MutableList<Point>) {
        val first: Point
            get() = points.first()

        val last: Point
            get() = points.last()

        val type: MovementType
            get() = first.type

        val source: String
            get() = first.source

        var meters = 0.0
        var movingMs = 0L
        var stoppedMs = 0L
        val durationMs: Long
            get() = movingMs + stoppedMs
    }

    data class Correction(val points: List<Point>, val samples: List<ConfirmedMotionSample>)

    fun points(walk: Long): List<Point> {
        val result = mutableListOf<Point>()
        store.readableDatabase
            .rawQuery(
                "SELECT p.id,p.lat,p.lon,p.session,p.t,p.acc,m.elapsed,m.speed,m.type,m.source,m.stopped " +
                    "FROM points p JOIN walk_sessions w ON p.session=-w.session " +
                    "LEFT JOIN motion_points m ON p.id=m.point WHERE w.walk=? ORDER BY p.id",
                arrayOf(walk.toString()),
            )
            .use { cursor ->
                while (cursor.moveToNext()) {
                    val rawType = cursor.getString(8)
                    val type =
                        MovementType.values().firstOrNull { it.name == rawType }
                            ?: MovementType.UNKNOWN
                    result.add(
                        Point(
                            cursor.getLong(0),
                            cursor.getDouble(1),
                            cursor.getDouble(2),
                            cursor.getLong(3),
                            cursor.getLong(4),
                            cursor.getLong(6),
                            cursor.getDouble(5),
                            if (cursor.isNull(7)) null else cursor.getDouble(7),
                            type,
                            cursor.getString(9) ?: "legacy",
                            cursor.getInt(10) != 0,
                        )
                    )
                }
            }
        return result
    }

    fun segments(walk: Long): List<Segment> = split(points(walk))

    fun profile(): PersonalMotionProfile {
        val result = PersonalMotionProfile()
        val samples = mutableListOf<ConfirmedMotionSample>()
        store.readableDatabase
            .rawQuery(
                "SELECT id,type,speeds,duration,accuracy FROM motion_samples ORDER BY first_point",
                null,
            )
            .use { cursor ->
                while (cursor.moveToNext()) {
                    val values = JSONArray(cursor.getString(2))
                    samples.add(
                        ConfirmedMotionSample(
                            cursor.getString(0),
                            MovementType.valueOf(cursor.getString(1)),
                            (0 until values.length()).map { values.getDouble(it) },
                            cursor.getLong(3),
                            cursor.getDouble(4),
                        )
                    )
                }
            }
        check(result.importSamples(samples)) { "Invalid confirmed profile data" }
        return result
    }

    fun correct(segment: Segment, type: MovementType): Correction {
        require(type != MovementType.ON_FOOT)
        val db = store.writableDatabase
        val before = Correction(segment.points.toList(), profile().exportSamples())
        db.beginTransaction()
        try {
            val values =
                ContentValues().apply {
                    put("type", type.name)
                    put("source", "manual")
                }
            for (point in segment.points) {
                db.insertWithOnConflict(
                    "motion_points",
                    null,
                    metadata(point).apply { putAll(values) },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.delete(
                "motion_samples",
                "first_point<=? AND last_point>=?",
                arrayOf(segment.last.id.toString(), segment.first.id.toString()),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return before
    }

    fun undo(correction: Correction) {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            for (point in correction.points) {
                if (point.source == "legacy") {
                    db.delete("motion_points", "point=?", arrayOf(point.id.toString()))
                } else {
                    db.insertWithOnConflict(
                        "motion_points",
                        null,
                        metadata(point),
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
            }
            db.delete("motion_samples", null, null)
            for (sample in correction.samples) {
                val ids = sample.id.split(":")
                saveSample(db, sample, ids[0].toLong(), ids[1].toLong())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun confirm(segment: Segment): Boolean {
        if (
            segment.points.size < 3 ||
                segment.source == "legacy" ||
                segment.points.any { it.stopped } ||
                segment.durationMs < 60_000L
        )
            return false
        val speeds = segment.points.mapNotNull { it.speed }
        if (speeds.size < 3 || speeds.size != segment.points.size) return false
        val accuracy = segment.points.maxOf { it.accuracy }
        val id = "${segment.first.id}:${segment.last.id}"
        val profile = profile()
        if (!profile.confirm(id, segment.type, speeds, segment.durationMs, accuracy)) return false
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            db.rawQuery(
                    "SELECT id FROM motion_samples WHERE first_point<=? AND last_point>=? AND id!=?",
                    arrayOf(segment.last.id.toString(), segment.first.id.toString(), id),
                )
                .use { if (it.moveToFirst()) return false }
            val sample = profile.exportSamples().first { it.id == id }
            // Splitting one journey must not manufacture independent learning examples.
            db.delete(
                "motion_samples",
                "journey=? AND type=?",
                arrayOf(journey(db, segment.first.id).toString(), segment.type.name),
            )
            saveSample(db, sample, segment.first.id, segment.last.id)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    fun resetProfile() {
        store.writableDatabase.delete("motion_samples", null, null)
    }

    override fun close() = store.close()

    companion object {
        @JvmStatic
        fun createSchema(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS motion_points(" +
                    "point INTEGER PRIMARY KEY,type TEXT NOT NULL,source TEXT NOT NULL," +
                    "elapsed INTEGER NOT NULL,speed REAL,stopped INTEGER NOT NULL DEFAULT 0)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS motion_samples(" +
                    "id TEXT PRIMARY KEY,type TEXT NOT NULL,speeds TEXT NOT NULL," +
                    "duration INTEGER NOT NULL,accuracy REAL NOT NULL," +
                    "first_point INTEGER NOT NULL,last_point INTEGER NOT NULL," +
                    "journey INTEGER NOT NULL,UNIQUE(journey,type))"
            )
        }

        @JvmStatic
        fun insert(
            db: SQLiteDatabase,
            point: Long,
            location: Location,
            type: String,
            source: String,
            stopped: Boolean,
        ) {
            val values =
                ContentValues().apply {
                    put("point", point)
                    put("type", MovementType.valueOf(type).name)
                    put("source", source)
                    put("elapsed", location.elapsedRealtimeNanos / 1_000_000L)
                    if (location.hasSpeed() && location.speed.isFinite() && location.speed >= 0) {
                        put("speed", location.speed.toDouble())
                    }
                    put("stopped", if (stopped) 1 else 0)
                }
            db.insertOrThrow("motion_points", null, values)
        }

        fun edgeMs(a: Point, b: Point): Long {
            if (a.session != b.session) return 0
            val dt = if (a.elapsed > 0 && b.elapsed > 0) b.elapsed - a.elapsed else b.time - a.time
            if (dt <= 0 || dt > 10_000L) return 0
            val distance = GpsFilter.distance(a.lat, a.lon, b.lat, b.lon)
            return if (distance <= 120.0 * dt / 1000.0 + a.accuracy + b.accuracy) dt else 0
        }

        @JvmStatic
        fun totalDistance(context: Context, walk: Long): Double =
            MovementStore(context).use { data ->
                val segments = data.segments(walk)
                if (segments.all { it.source == "legacy" }) {
                    Store(context).use { Store.distance(it.walkPoints(walk)) }
                } else segments.sumOf { it.meters }
            }

        fun split(points: List<Point>): List<Segment> {
            val result = mutableListOf<Segment>()
            var previous: Point? = null
            for (point in points) {
                val dt = previous?.let { edgeMs(it, point) } ?: 0L
                var current = result.lastOrNull()
                if (
                    current == null ||
                        current.type != point.type ||
                        current.source != point.source ||
                        dt == 0L
                ) {
                    current = Segment(mutableListOf())
                    result.add(current)
                }
                current.points.add(point)
                if (dt > 0 && previous != null) {
                    if (point.stopped) {
                        current.stoppedMs += dt
                    } else {
                        current.meters +=
                            GpsFilter.distance(previous.lat, previous.lon, point.lat, point.lon)
                        current.movingMs += dt
                    }
                }
                previous = point
            }
            return result
        }

        private fun journey(db: SQLiteDatabase, point: Long): Long =
            db.rawQuery(
                    "SELECT w.walk FROM points p JOIN walk_sessions w ON p.session=-w.session WHERE p.id=?",
                    arrayOf(point.toString()),
                )
                .use {
                    check(it.moveToFirst())
                    it.getLong(0)
                }

        private fun metadata(point: Point) =
            ContentValues().apply {
                put("point", point.id)
                put("type", point.type.name)
                put("source", point.source)
                put("elapsed", point.elapsed)
                if (point.speed != null) put("speed", point.speed)
                put("stopped", if (point.stopped) 1 else 0)
            }

        private fun saveSample(
            db: SQLiteDatabase,
            sample: ConfirmedMotionSample,
            first: Long,
            last: Long,
        ) {
            val values =
                ContentValues().apply {
                    put("id", sample.id)
                    put("type", sample.type.name)
                    put("speeds", JSONArray(sample.speeds).toString())
                    put("duration", sample.durationMs)
                    put("accuracy", sample.accuracy)
                    put("first_point", first)
                    put("last_point", last)
                    put("journey", journey(db, first))
                }
            db.insertWithOnConflict("motion_samples", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }
}

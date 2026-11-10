package ru.main.explorer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONTokener

class SpawnZoneStore(context: Context) : AutoCloseable {
    private val store = Store(context.applicationContext)

    fun list(): List<SpawnZone> {
        val zones = mutableListOf<SpawnZone>()
        store.readableDatabase
            .rawQuery("SELECT id,name,lat,lon,radius,polygon FROM spawn_zones ORDER BY id", null)
            .use { cursor ->
                while (cursor.moveToNext()) {
                    zones.add(
                        SpawnZone(
                            cursor.getLong(0),
                            cursor.getString(1),
                            cursor.getDouble(2),
                            cursor.getDouble(3),
                            cursor.getDouble(4),
                            parsePoints(cursor.getString(5)),
                        )
                    )
                }
            }
        return zones
    }

    /** Updates require an existing ID; a stale editor cannot silently insert another zone. */
    fun save(zone: SpawnZone): SpawnZone {
        val db = store.writableDatabase
        val values =
            ContentValues().apply {
                put("name", zone.name)
                put("lat", zone.latitude)
                put("lon", zone.longitude)
                put("radius", zone.radiusMeters)
                put(
                    "polygon",
                    if (zone.isPolygon)
                        JSONArray()
                            .apply {
                                zone.points.forEach {
                                    put(JSONArray().put(it.latitude).put(it.longitude))
                                }
                            }
                            .toString()
                    else "",
                )
            }
        db.beginTransaction()
        try {
            val id =
                if (zone.id == 0L) {
                    db.rawQuery("SELECT COUNT(*) FROM spawn_zones", null).use {
                        check(it.moveToFirst() && it.getInt(0) < MAX_ZONES) { "limit" }
                    }
                    db.insertOrThrow("spawn_zones", null, values)
                } else {
                    check(
                        db.update("spawn_zones", values, "id=?", arrayOf(zone.id.toString())) == 1
                    ) {
                        "missing"
                    }
                    zone.id
                }
            db.setTransactionSuccessful()
            return zone.copy(id = id)
        } finally {
            db.endTransaction()
        }
    }

    fun delete(id: Long): Boolean {
        require(id > 0)
        return store.writableDatabase.delete("spawn_zones", "id=?", arrayOf(id.toString())) == 1
    }

    override fun close() = store.close()

    companion object {
        const val MAX_ZONES = 500

        private fun parsePoints(text: String): List<ZonePoint> {
            if (text.isEmpty()) return emptyList()
            // JSONTokener also accepts JavaScript extensions. The storage format does not.
            val number = "-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"
            val pair = "\\[\\s*$number\\s*,\\s*$number\\s*\\]"
            require(Regex("\\s*\\[\\s*$pair(?:\\s*,\\s*$pair)*\\s*\\]\\s*").matches(text)) {
                "polygon JSON"
            }
            val tokenizer = JSONTokener(text)
            val array = tokenizer.nextValue()
            require(array is JSONArray && tokenizer.nextClean() == '\u0000') { "polygon JSON" }
            require(array.length() in 3..SpawnZone.MAX_VERTICES) { "vertices" }
            return (0 until array.length()).map { index ->
                val point = array.get(index)
                require(point is JSONArray && point.length() == 2) { "point JSON" }
                val latitude = point.get(0)
                val longitude = point.get(1)
                require(latitude is Number && longitude is Number) { "point numbers" }
                ZonePoint(latitude.toDouble(), longitude.toDouble())
            }
        }

        @JvmStatic
        fun createSchema(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS spawn_zones(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT NOT NULL CHECK(length(trim(name)) BETWEEN 1 AND 80)," +
                    "lat REAL NOT NULL CHECK(lat BETWEEN -85.05112878 AND 85.05112878)," +
                    "lon REAL NOT NULL CHECK(lon BETWEEN -180 AND 180)," +
                    "radius REAL NOT NULL CHECK(radius BETWEEN 1 AND 100000)," +
                    "polygon TEXT NOT NULL DEFAULT '')"
            )
            val hasPolygon =
                db.rawQuery("PRAGMA table_info(spawn_zones)", null).use { cursor ->
                    var found = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "polygon")
                            found = true
                    }
                    found
                }
            if (!hasPolygon)
                db.execSQL("ALTER TABLE spawn_zones ADD COLUMN polygon TEXT NOT NULL DEFAULT ''")
        }
    }
}

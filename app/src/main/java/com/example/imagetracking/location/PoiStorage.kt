package com.example.imagetracking.location

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Локальная база меток (SQLite, встроенная в Android — без внешних зависимостей). */
class PoiStorage(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                "id TEXT PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "lat REAL NOT NULL, " +
                "lon REAL NOT NULL, " +
                "radius REAL NOT NULL, " +
                "content_id TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun loadAll(): MutableList<Poi> {
        val result = ArrayList<Poi>()
        readableDatabase.query(TABLE, null, null, null, null, null, "rowid ASC").use { c ->
            val iId = c.getColumnIndexOrThrow("id")
            val iName = c.getColumnIndexOrThrow("name")
            val iLat = c.getColumnIndexOrThrow("lat")
            val iLon = c.getColumnIndexOrThrow("lon")
            val iRadius = c.getColumnIndexOrThrow("radius")
            val iContent = c.getColumnIndexOrThrow("content_id")
            while (c.moveToNext()) {
                result.add(
                    Poi(
                        id = c.getString(iId),
                        name = c.getString(iName),
                        lat = c.getDouble(iLat),
                        lon = c.getDouble(iLon),
                        radius = c.getFloat(iRadius),
                        contentId = c.getString(iContent)
                    )
                )
            }
        }
        return result
    }

    fun insert(poi: Poi) {
        val v = ContentValues().apply {
            put("id", poi.id)
            put("name", poi.name)
            put("lat", poi.lat)
            put("lon", poi.lon)
            put("radius", poi.radius)
            put("content_id", poi.contentId)
        }
        writableDatabase.insertWithOnConflict(TABLE, null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun delete(id: String) {
        writableDatabase.delete(TABLE, "id = ?", arrayOf(id))
    }

    fun deleteAll() {
        writableDatabase.delete(TABLE, null, null)
    }

    private companion object {
        const val DB_NAME = "pois.db"
        const val DB_VERSION = 1
        const val TABLE = "poi"
    }
}

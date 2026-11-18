package ar.edu.examen.iiot.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ar.edu.examen.iiot.model.PlantRules
import ar.edu.examen.iiot.model.Reading

class PlantDatabase(context: Context, name: String? = DATABASE_NAME) :
    SQLiteOpenHelper(context, name, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE readings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                machine_id TEXT NOT NULL,
                machine_name TEXT NOT NULL,
                temperature REAL NOT NULL,
                humidity REAL NOT NULL,
                vibration REAL NOT NULL,
                captured_at INTEGER NOT NULL,
                source TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            "CREATE TABLE configuration (config_key TEXT PRIMARY KEY, config_value TEXT NOT NULL)"
        )
        createIndexes(db)
    }

    /**
     * Migración incremental: antes se borraban todas las tablas en cada cambio de
     * versión, perdiendo el historial y la configuración del usuario.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createIndexes(db)
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_readings_machine_time " +
                "ON readings(machine_id, captured_at)"
        )
    }

    fun insert(reading: Reading) {
        val values = ContentValues().apply {
            put("machine_id", reading.machineId)
            put("machine_name", reading.machineName)
            put("temperature", reading.temperature)
            put("humidity", reading.humidity)
            put("vibration", reading.vibration)
            put("captured_at", reading.timestamp)
            put("source", reading.source)
        }
        writableDatabase.insertOrThrow("readings", null, values)
    }

    /** Inserta un lote completo en una sola transacción. */
    fun insertAll(readings: List<Reading>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            readings.forEach(::insert)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun latest(): List<Reading> =
        readableDatabase.rawQuery("$LATEST_SQL ORDER BY r.machine_name", null).use(::readAll)

    /** El texto del usuario viaja como parámetro (?), nunca concatenado en el SQL. */
    fun findLatestByName(filter: String): List<Reading> {
        val sql = "$LATEST_SQL WHERE r.machine_name LIKE ? ESCAPE '\\' ORDER BY r.machine_name"
        val pattern = "%" + escapeLike(filter) + "%"
        return readableDatabase.rawQuery(sql, arrayOf(pattern)).use(::readAll)
    }

    /** Últimas [limit] lecturas de un equipo, desde la más reciente. */
    fun history(machineId: String, limit: Int = PlantRules.HISTORY_SIZE): List<Reading> {
        val sql = """SELECT id, machine_id, machine_name, temperature, humidity,
                     vibration, captured_at, source
                     FROM readings
                     WHERE machine_id = ?
                     ORDER BY captured_at DESC, id DESC
                     LIMIT ?""".trimIndent()
        return readableDatabase.rawQuery(sql, arrayOf(machineId, limit.toString()))
            .use(::readAll)
    }

    fun readThreshold(): Double {
        val stored = readableDatabase.rawQuery(
            "SELECT config_value FROM configuration WHERE config_key = ?",
            arrayOf(KEY_THRESHOLD)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        return stored?.toDoubleOrNull()
            ?.takeIf(PlantRules::isValidThreshold)
            ?: PlantRules.DEFAULT_TEMPERATURE_LIMIT
    }

    fun saveThreshold(value: Double) {
        require(PlantRules.isValidThreshold(value)) { "Umbral fuera de rango: $value" }
        val values = ContentValues().apply {
            put("config_key", KEY_THRESHOLD)
            put("config_value", value.toString())
        }
        writableDatabase.insertWithOnConflict(
            "configuration", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun escapeLike(text: String): String =
        text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun readAll(cursor: Cursor): List<Reading> {
        val items = mutableListOf<Reading>()
        while (cursor.moveToNext()) {
            items += Reading(
                id = cursor.getLong(0),
                machineId = cursor.getString(1),
                machineName = cursor.getString(2),
                temperature = cursor.getDouble(3),
                humidity = cursor.getDouble(4),
                vibration = cursor.getDouble(5),
                timestamp = cursor.getLong(6),
                source = cursor.getString(7)
            )
        }
        return items
    }

    companion object {
        const val DATABASE_NAME = "planta_iiot.db"
        const val DATABASE_VERSION = 2
        private const val KEY_THRESHOLD = "temperature_threshold"

        private val LATEST_SQL = """SELECT r.id, r.machine_id, r.machine_name,
                   r.temperature, r.humidity, r.vibration, r.captured_at, r.source
                   FROM readings r
                   INNER JOIN (
                       SELECT machine_id, MAX(id) AS latest_id
                       FROM readings GROUP BY machine_id
                   ) x ON r.id = x.latest_id""".trimIndent()
    }
}

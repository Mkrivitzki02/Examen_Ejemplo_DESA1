package ar.edu.examen.iiot.data

import android.content.Context
import ar.edu.examen.iiot.model.Reading
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Contrato que usa el ViewModel. Permite probar la lógica de pantalla con un
 * repositorio falso, sin Android, red ni SQLite.
 */
interface PlantDataSource {
    suspend fun localReadings(): List<Reading>
    suspend fun search(query: String): List<Reading>
    /** Sincroniza y devuelve el mensaje de estado para mostrar. */
    suspend fun sync(): String
    suspend fun history(machineId: String): List<Reading>
    suspend fun threshold(): Double
    suspend fun saveThreshold(value: Double)
}

class PlantRepository(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : PlantDataSource {
    // applicationContext: el repositorio vive tanto como el ViewModel y no debe
    // retener la Activity (que se destruye, por ejemplo, al rotar la pantalla).
    private val database = PlantDatabase(context.applicationContext)
    private val api = Retrofit.Builder()
        .baseUrl("https://api.thingspeak.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ThingSpeakApi::class.java)

    override suspend fun localReadings(): List<Reading> = withContext(io) {
        val rows = database.latest()
        if (rows.isNotEmpty()) return@withContext rows
        database.insertAll(sampleReadings(null, null, false))
        database.latest()
    }

    override suspend fun search(query: String): List<Reading> = withContext(io) {
        if (query.isBlank()) database.latest() else database.findLatestByName(query.trim())
    }

    override suspend fun sync(): String = withContext(io) {
        var remoteTemperature: Double? = null
        var remoteHumidity: Double? = null

        val message = try {
            val feed = api.getLatest().feeds.lastOrNull()
            remoteTemperature = feed?.temperature?.toDoubleOrNull()
                ?.takeIf { it in -20.0..150.0 }
            remoteHumidity = feed?.humidity?.toDoubleOrNull()
                ?.takeIf { it in 0.0..100.0 }
            if (remoteTemperature != null) {
                "Sincronizado con ThingSpeak (canal público de demostración)"
            } else {
                "Sin datos remotos válidos: usando datos simulados"
            }
        } catch (exception: Exception) {
            "Sin conexión a API: usando datos simulados"
        }

        database.insertAll(
            sampleReadings(remoteTemperature, remoteHumidity, remoteTemperature != null)
        )
        message
    }

    override suspend fun history(machineId: String): List<Reading> = withContext(io) {
        database.history(machineId)
    }

    override suspend fun threshold(): Double = withContext(io) { database.readThreshold() }

    override suspend fun saveThreshold(value: Double) = withContext(io) {
        database.saveThreshold(value)
    }

    private fun sampleReadings(
        remoteTemperature: Double?,
        remoteHumidity: Double?,
        remoteOk: Boolean
    ): List<Reading> {
        val now = System.currentTimeMillis()
        val variation = ((now / 60_000) % 4).toDouble()
        return listOf(
            Reading(
                machineId = "CMP-01",
                machineName = "Compresor A",
                temperature = remoteTemperature ?: (56.2 + variation),
                humidity = remoteHumidity ?: 42.0,
                vibration = 1.6,
                timestamp = now,
                source = if (remoteOk) "ThingSpeak (demo externo)" else "Simulación local"
            ),
            Reading(
                machineId = "PMP-02",
                machineName = "Bomba B",
                temperature = 47.3 + variation / 2,
                humidity = 36.0,
                vibration = 0.8,
                timestamp = now,
                source = "Simulación local"
            ),
            Reading(
                machineId = "OVN-03",
                machineName = "Horno C",
                temperature = 69.5 + variation,
                humidity = 24.0,
                vibration = 0.3,
                timestamp = now,
                source = "Simulación local"
            )
        )
    }
}

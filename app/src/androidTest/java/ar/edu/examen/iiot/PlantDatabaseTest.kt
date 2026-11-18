package ar.edu.examen.iiot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ar.edu.examen.iiot.data.PlantDatabase
import ar.edu.examen.iiot.model.Reading
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Prueba SQLite real en el dispositivo/emulador, con una base aparte de la de la app. */
@RunWith(AndroidJUnit4::class)
class PlantDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "planta_iiot_test.db"
    private lateinit var db: PlantDatabase

    @Before fun setUp() {
        context.deleteDatabase(dbName)
        db = PlantDatabase(context, dbName)
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    private fun reading(machineId: String, name: String, temp: Double, time: Long) = Reading(
        machineId = machineId, machineName = name, temperature = temp, humidity = 40.0,
        vibration = 1.0, timestamp = time, source = "Test $time"
    )

    @Test fun newInstallUsesDefaultThreshold() {
        assertEquals(65.0, db.readThreshold(), 0.0)
    }

    @Test fun thresholdSurvivesReopeningTheDatabase() {
        db.saveThreshold(82.5)
        db.close()
        db = PlantDatabase(context, dbName) // simula cerrar y volver a abrir la app
        assertEquals(82.5, db.readThreshold(), 0.0)
    }

    @Test fun historyReturnsTenMostRecentNewestFirstForThatMachineOnly() {
        (1L..15L).forEach { t -> db.insert(reading("CMP-01", "Compresor A", 50.0 + t, t * 1000)) }
        db.insert(reading("PMP-02", "Bomba B", 99.0, 99_000))

        val history = db.history("CMP-01")

        assertEquals(10, history.size)
        assertEquals((15L downTo 6L).map { it * 1000 }, history.map { it.timestamp })
        assertTrue(history.all { it.machineId == "CMP-01" })
        assertEquals("Test 15000", history.first().source)
    }

    @Test fun historyOfMachineWithoutReadingsIsEmpty() {
        assertTrue(db.history("NADA-00").isEmpty())
    }

    @Test fun searchIsSafeAgainstSqlInjectionAndWildcards() {
        db.insert(reading("CMP-01", "Compresor A", 50.0, 1))
        db.insert(reading("OVN-03", "Horno C", 70.0, 1))

        // Antes, una comilla rompía la consulta (SQLiteException) y esto devolvía todo.
        assertTrue(db.findLatestByName("' OR '1'='1").isEmpty())
        assertTrue(db.findLatestByName("'").isEmpty())
        assertTrue(db.findLatestByName("%").isEmpty())
        assertEquals(listOf("Horno C"), db.findLatestByName("horno").map { it.machineName })
    }
}

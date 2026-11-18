package ar.edu.examen.iiot

import ar.edu.examen.iiot.data.PlantDataSource
import ar.edu.examen.iiot.model.Reading
import ar.edu.examen.iiot.ui.PlantViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Repositorio en memoria: sin Android, red ni SQLite. */
private class FakeDataSource(initialThreshold: Double = 65.0) : PlantDataSource {
    var storedThreshold = initialThreshold
    var saveCalls = 0
    var syncCalls = 0
    val rows = mutableListOf(
        reading("CMP-01", "Compresor A", 58.0, 1),
        reading("PMP-02", "Bomba B", 47.0, 1),
        reading("OVN-03", "Horno C", 70.0, 1)
    )

    override suspend fun localReadings() = latest()
    override suspend fun search(query: String) =
        latest().filter { it.machineName.contains(query, ignoreCase = true) }

    override suspend fun sync(): String {
        syncCalls++
        rows += reading("CMP-01", "Compresor A", 60.0 + syncCalls, 1L + syncCalls)
        return "Sin conexión a API: usando datos simulados"
    }

    override suspend fun history(machineId: String) =
        rows.filter { it.machineId == machineId }.sortedByDescending { it.timestamp }.take(10)

    override suspend fun threshold() = storedThreshold
    override suspend fun saveThreshold(value: Double) {
        saveCalls++
        storedThreshold = value
    }

    private fun latest() = rows.groupBy { it.machineId }.map { (_, list) -> list.last() }

    companion object {
        private var nextId = 1L
        fun reading(id: String, name: String, temp: Double, time: Long) = Reading(
            id = nextId++, machineId = id, machineName = name, temperature = temp,
            humidity = 40.0, vibration = 1.0, timestamp = time, source = "Simulación local"
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlantViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun loadsPersistedThresholdOnStart() {
        val vm = PlantViewModel(FakeDataSource(initialThreshold = 50.0))
        assertEquals(50.0, vm.state.value.threshold, 0.0)
        assertEquals(2, vm.state.value.alertCount) // 58 y 70 >= 50
    }

    @Test fun savingThresholdUpdatesAlertsImmediatelyWithoutSync() {
        val repo = FakeDataSource()
        val vm = PlantViewModel(repo)
        assertEquals(1, vm.state.value.alertCount) // solo el Horno (70)

        vm.configureThreshold()
        vm.onThresholdInputChanged("55")
        vm.saveThreshold()

        assertEquals(55.0, repo.storedThreshold, 0.0)
        assertEquals(55.0, vm.state.value.threshold, 0.0)
        assertEquals(2, vm.state.value.alertCount) // Compresor (58) y Horno (70)
        assertNull(vm.state.value.thresholdEditor)
        assertEquals(0, repo.syncCalls)
    }

    @Test fun invalidThresholdShowsErrorAndIsNotSaved() {
        val repo = FakeDataSource()
        val vm = PlantViewModel(repo)

        vm.configureThreshold()
        vm.onThresholdInputChanged("150")
        vm.saveThreshold()

        val editor = vm.state.value.thresholdEditor
        assertNotNull(editor)
        assertTrue(editor!!.error!!.contains("entre 30 y 120"))
        assertEquals(0, repo.saveCalls)
        assertEquals(65.0, vm.state.value.threshold, 0.0)
    }

    @Test fun historyShowsNewReadingsAfterTwoSyncsAndKeepsFilter() {
        val vm = PlantViewModel(FakeDataSource())
        vm.filter("compre")
        vm.refresh()
        vm.refresh()

        vm.showHistory("CMP-01")
        val history = vm.state.value.history!!
        assertEquals("Compresor A", history.machineName)
        assertEquals(listOf(62.0, 61.0, 58.0), history.items.map { it.temperature })

        vm.closeHistory()
        assertNull(vm.state.value.history)
        assertEquals("compre", vm.state.value.filter)
        assertEquals(listOf("CMP-01"), vm.state.value.readings.map { it.machineId })
    }

    @Test fun emptyHistoryIsNotLoadingAndHasNoItems() {
        val vm = PlantViewModel(FakeDataSource())
        vm.showHistory("XXX-99")
        val history = vm.state.value.history!!
        assertEquals(false, history.loading)
        assertTrue(history.items.isEmpty())
    }
}

package ar.edu.examen.iiot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ar.edu.examen.iiot.data.PlantDataSource
import ar.edu.examen.iiot.model.PlantRules
import ar.edu.examen.iiot.model.Reading
import ar.edu.examen.iiot.model.ThresholdValidation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Diálogo de edición del umbral (null = cerrado). */
data class ThresholdEditor(
    val input: String,
    val error: String? = null,
    val saving: Boolean = false
)

/** Historial abierto de un equipo (null = cerrado). */
data class HistoryState(
    val machineId: String,
    val machineName: String,
    val items: List<Reading> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null
)

data class DashboardState(
    val readings: List<Reading> = emptyList(),
    val filter: String = "",
    val loading: Boolean = false,
    val syncMessage: String = "Lecturas locales",
    val notification: String? = null,
    val threshold: Double = PlantRules.DEFAULT_TEMPERATURE_LIMIT,
    val thresholdEditor: ThresholdEditor? = null,
    val history: HistoryState? = null
) {
    /** Derivado: siempre coherente con las lecturas y el umbral vigentes. */
    val alertCount: Int get() = PlantRules.countAlerts(readings, threshold)
}

class PlantViewModel(private val repository: PlantDataSource) : ViewModel() {
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val threshold = repository.threshold()
                val readings = repository.localReadings()
                _state.update { it.copy(threshold = threshold, readings = readings) }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                _state.update { it.copy(notification = "No se pudieron leer los datos locales") }
            }
        }
    }

    fun refresh() {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, notification = null) }
            try {
                val message = repository.sync()
                val items = repository.search(_state.value.filter)
                _state.update { it.copy(readings = items, syncMessage = message) }
                _state.value.history?.let { reloadHistory(it.machineId) }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                _state.update { it.copy(syncMessage = "No se pudo actualizar la telemetría") }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun filter(text: String) {
        _state.update { it.copy(filter = text) }
        // Si el usuario sigue escribiendo, se descarta la búsqueda anterior para
        // que una respuesta vieja no pise a la más nueva.
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            try {
                val items = repository.search(text)
                _state.update { it.copy(readings = items) }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                _state.update { it.copy(notification = "La búsqueda no se pudo completar") }
            }
        }
    }

    // ---- Umbral ----

    fun configureThreshold() {
        _state.update {
            it.copy(thresholdEditor = ThresholdEditor(PlantRules.formatThreshold(it.threshold)))
        }
    }

    fun onThresholdInputChanged(text: String) {
        _state.update { current ->
            current.copy(thresholdEditor = current.thresholdEditor?.copy(input = text, error = null))
        }
    }

    fun dismissThresholdEditor() {
        _state.update { it.copy(thresholdEditor = null) }
    }

    fun saveThreshold() {
        val editor = _state.value.thresholdEditor ?: return
        if (editor.saving) return
        when (val result = PlantRules.validateThreshold(editor.input)) {
            is ThresholdValidation.Invalid -> _state.update {
                it.copy(thresholdEditor = editor.copy(error = result.message))
            }
            is ThresholdValidation.Valid -> viewModelScope.launch {
                _state.update { it.copy(thresholdEditor = editor.copy(saving = true)) }
                try {
                    repository.saveThreshold(result.value)
                    // alertCount y los estados NORMAL/ALERTA se derivan del umbral,
                    // así que cambian en este mismo instante, sin sincronizar.
                    _state.update {
                        it.copy(
                            threshold = result.value,
                            thresholdEditor = null,
                            notification = "Umbral actualizado a " +
                                "${PlantRules.formatThreshold(result.value)} °C"
                        )
                    }
                } catch (exception: Exception) {
                    if (exception is CancellationException) throw exception
                    _state.update {
                        it.copy(
                            thresholdEditor = editor.copy(
                                saving = false,
                                error = "No se pudo guardar el umbral. Probá de nuevo."
                            )
                        )
                    }
                }
            }
        }
    }

    // ---- Historial ----

    fun showHistory(machineId: String) {
        val name = _state.value.readings.firstOrNull { it.machineId == machineId }
            ?.machineName ?: machineId
        _state.update { it.copy(history = HistoryState(machineId, name)) }
        viewModelScope.launch { reloadHistory(machineId) }
    }

    private suspend fun reloadHistory(machineId: String) {
        try {
            val items = repository.history(machineId)
            _state.update { current ->
                val open = current.history?.takeIf { it.machineId == machineId }
                    ?: return@update current
                current.copy(history = open.copy(items = items, loading = false, error = null))
            }
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            _state.update { current ->
                val open = current.history?.takeIf { it.machineId == machineId }
                    ?: return@update current
                current.copy(
                    history = open.copy(loading = false, error = "No se pudo leer el historial")
                )
            }
        }
    }

    /** Cierra el historial; el filtro de búsqueda no se toca. */
    fun closeHistory() {
        _state.update { it.copy(history = null) }
    }

    fun dismissNotification() {
        _state.update { it.copy(notification = null) }
    }

    class Factory(private val repository: PlantDataSource) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PlantViewModel::class.java))
            return PlantViewModel(repository) as T
        }
    }
}

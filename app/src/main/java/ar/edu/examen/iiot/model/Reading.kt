package ar.edu.examen.iiot.model

import java.util.Locale

data class Reading(
    val id: Long = 0,
    val machineId: String,
    val machineName: String,
    val temperature: Double,
    val humidity: Double,
    val vibration: Double,
    val timestamp: Long,
    val source: String
)

/** Resultado de validar el texto ingresado como umbral. */
sealed interface ThresholdValidation {
    data class Valid(val value: Double) : ThresholdValidation
    data class Invalid(val message: String) : ThresholdValidation
}

/**
 * Reglas de negocio de la planta. Es la única fuente de verdad del umbral:
 * la pantalla, el contador y el ViewModel consultan estas funciones en lugar
 * de repetir el valor 65 en varios lugares.
 */
object PlantRules {
    const val DEFAULT_TEMPERATURE_LIMIT = 65.0
    const val MIN_TEMPERATURE_LIMIT = 30.0
    const val MAX_TEMPERATURE_LIMIT = 120.0
    const val HISTORY_SIZE = 10

    fun requiresAttention(
        reading: Reading,
        threshold: Double = DEFAULT_TEMPERATURE_LIMIT
    ): Boolean = reading.temperature >= threshold

    fun countAlerts(readings: List<Reading>, threshold: Double): Int =
        readings.count { requiresAttention(it, threshold) }

    fun isValidThreshold(value: Double): Boolean =
        value.isFinite() && value in MIN_TEMPERATURE_LIMIT..MAX_TEMPERATURE_LIMIT

    /** Acepta coma o punto decimal ("72,5" o "72.5"). */
    fun validateThreshold(input: String): ThresholdValidation {
        val value = input.trim().replace(',', '.').toDoubleOrNull()
        return when {
            value == null || !value.isFinite() ->
                ThresholdValidation.Invalid("Ingresá un número, por ejemplo 70.")
            !isValidThreshold(value) ->
                ThresholdValidation.Invalid(
                    "El umbral debe estar entre ${formatThreshold(MIN_TEMPERATURE_LIMIT)} " +
                        "y ${formatThreshold(MAX_TEMPERATURE_LIMIT)} °C."
                )
            else -> ThresholdValidation.Valid(value)
        }
    }

    /** 70.0 -> "70", 72.5 -> "72.5" */
    fun formatThreshold(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString()
        else String.format(Locale.US, "%.1f", value)
}

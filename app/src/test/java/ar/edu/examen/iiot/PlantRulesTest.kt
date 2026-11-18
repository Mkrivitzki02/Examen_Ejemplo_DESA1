package ar.edu.examen.iiot

import ar.edu.examen.iiot.model.PlantRules
import ar.edu.examen.iiot.model.Reading
import ar.edu.examen.iiot.model.ThresholdValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlantRulesTest {
    private fun sample(temp: Double) = Reading(
        machineId = "TEST-01",
        machineName = "Equipo de prueba",
        temperature = temp,
        humidity = 50.0,
        vibration = 1.0,
        timestamp = 1L,
        source = "Test"
    )

    @Test fun belowThresholdIsNormal() {
        assertFalse(PlantRules.requiresAttention(sample(64.9)))
    }

    @Test fun thresholdIsAlert() {
        assertTrue(PlantRules.requiresAttention(sample(65.0)))
    }

    @Test fun customThresholdIsInclusive() {
        assertTrue(PlantRules.requiresAttention(sample(80.0), threshold = 80.0))
        assertFalse(PlantRules.requiresAttention(sample(79.9), threshold = 80.0))
    }

    @Test fun countAlertsUsesGivenThreshold() {
        val readings = listOf(sample(50.0), sample(70.0), sample(90.0))
        assertEquals(2, PlantRules.countAlerts(readings, 65.0))
        assertEquals(1, PlantRules.countAlerts(readings, 80.0))
        assertEquals(3, PlantRules.countAlerts(readings, 30.0))
    }

    @Test fun acceptsRangeLimitsAndDecimalComma() {
        assertEquals(ThresholdValidation.Valid(30.0), PlantRules.validateThreshold("30"))
        assertEquals(ThresholdValidation.Valid(120.0), PlantRules.validateThreshold("120"))
        assertEquals(ThresholdValidation.Valid(72.5), PlantRules.validateThreshold(" 72,5 "))
    }

    @Test fun rejectsOutOfRangeAndNonNumeric() {
        listOf("29.9", "120.1", "-5", "", "abc", "NaN", "Infinity", "70°").forEach { input ->
            assertTrue(
                "Debería rechazar '$input'",
                PlantRules.validateThreshold(input) is ThresholdValidation.Invalid
            )
        }
    }

    @Test fun formatsThreshold() {
        assertEquals("70", PlantRules.formatThreshold(70.0))
        assertEquals("72.5", PlantRules.formatThreshold(72.5))
    }
}

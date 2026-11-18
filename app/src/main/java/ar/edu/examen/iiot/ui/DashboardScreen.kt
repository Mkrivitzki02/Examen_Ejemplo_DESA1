package ar.edu.examen.iiot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ar.edu.examen.iiot.model.PlantRules
import ar.edu.examen.iiot.model.Reading
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AlertRed = Color(0xFFB3261E)
private val NormalGreen = Color(0xFF238147)
private val Muted = Color(0xFF64748B)

@Composable
fun DashboardScreen(
    state: DashboardState,
    onFilterChanged: (String) -> Unit,
    onThresholdClick: () -> Unit,
    onThresholdInputChanged: (String) -> Unit,
    onThresholdSave: () -> Unit,
    onThresholdDismiss: () -> Unit,
    onHistoryClick: (String) -> Unit,
    onHistoryDismiss: () -> Unit,
    onDismissNotification: () -> Unit
) {
    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Equipos monitoreados",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Alertas: ${state.alertCount}",
                    color = if (state.alertCount > 0) AlertRed else Color(0xFF256D41),
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.filter,
                onValueChange = onFilterChanged,
                label = { Text("Buscar equipo") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(onClick = onThresholdClick) {
                    Text("Umbral: ${PlantRules.formatThreshold(state.threshold)} °C")
                }
                if (state.loading) {
                    Text("Sincronizando...", modifier = Modifier.padding(top = 12.dp))
                }
            }
            if (state.notification != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    color = Color(0xFFFFF3D9),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(state.notification, color = Color(0xFF5F4500))
                        Button(onClick = onDismissNotification) { Text("Cerrar aviso") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (state.readings.isEmpty()) {
                Text(
                    "No hay equipos para mostrar.",
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.readings, key = { it.machineId }) { reading ->
                    EquipmentCard(
                        reading = reading,
                        alert = PlantRules.requiresAttention(reading, state.threshold),
                        onHistoryClick = onHistoryClick
                    )
                }
                item {
                    Text(
                        "Datos de demostración: ThingSpeak aporta una medición " +
                            "pública al Compresor A; el resto es simulado. " +
                            "Los sensores físicos corresponden al teléfono.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
        }

        state.thresholdEditor?.let { editor ->
            ThresholdDialog(
                editor = editor,
                onInputChanged = onThresholdInputChanged,
                onSave = onThresholdSave,
                onDismiss = onThresholdDismiss
            )
        }
        state.history?.let { history ->
            HistoryDialog(history = history, threshold = state.threshold, onDismiss = onHistoryDismiss)
        }
    }
}

@Composable
private fun EquipmentCard(reading: Reading, alert: Boolean, onHistoryClick: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(reading.machineName, fontWeight = FontWeight.Bold)
                    Text(reading.machineId, style = MaterialTheme.typography.labelSmall)
                }
                StatusLabel(alert)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${"%.1f".format(reading.temperature)} °C",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Humedad ${"%.0f".format(reading.humidity)} %  ·  " +
                    "Vibración ${"%.1f".format(reading.vibration)} mm/s",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "${formatTime(reading.timestamp, "dd/MM HH:mm")} · ${reading.source}",
                color = Muted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            OutlinedButton(
                onClick = { onHistoryClick(reading.machineId) },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("Ver historial")
            }
        }
    }
}

@Composable
private fun StatusLabel(alert: Boolean) {
    Text(
        if (alert) "ALERTA" else "NORMAL",
        color = if (alert) AlertRed else NormalGreen,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelMedium
    )
}

@Composable
private fun ThresholdDialog(
    editor: ThresholdEditor,
    onInputChanged: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Umbral de alarma") },
        text = {
            Column {
                Text(
                    "Un equipo pasa a ALERTA cuando su temperatura es mayor o igual al umbral. " +
                        "Rango permitido: ${PlantRules.formatThreshold(PlantRules.MIN_TEMPERATURE_LIMIT)} " +
                        "a ${PlantRules.formatThreshold(PlantRules.MAX_TEMPERATURE_LIMIT)} °C.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = editor.input,
                    onValueChange = onInputChanged,
                    label = { Text("Temperatura (°C)") },
                    singleLine = true,
                    isError = editor.error != null,
                    supportingText = editor.error?.let { message -> { Text(message) } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSave() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = !editor.saving) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun HistoryDialog(history: HistoryState, threshold: Double, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Historial · ${history.machineName}")
                Text(
                    "${history.machineId} · últimas ${PlantRules.HISTORY_SIZE} lecturas",
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted
                )
            }
        },
        text = {
            when {
                history.loading -> Text("Cargando historial...")
                history.error != null -> Text(history.error, color = AlertRed)
                history.items.isEmpty() -> Text(
                    "Este equipo todavía no tiene lecturas guardadas. " +
                        "Tocá \"Sincronizar telemetría\" para registrar una."
                )
                else -> LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(history.items, key = { it.id }) { reading ->
                        HistoryRow(reading, PlantRules.requiresAttention(reading, threshold))
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        }
    )
}

@Composable
private fun HistoryRow(reading: Reading, alert: Boolean) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                formatTime(reading.timestamp, "dd/MM/yyyy HH:mm:ss"),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "${"%.1f".format(reading.temperature)} °C",
                fontWeight = FontWeight.SemiBold,
                color = if (alert) AlertRed else Color.Unspecified
            )
        }
        Text(reading.source, style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

private fun formatTime(timestamp: Long, pattern: String): String =
    SimpleDateFormat(pattern, Locale("es", "AR")).format(Date(timestamp))

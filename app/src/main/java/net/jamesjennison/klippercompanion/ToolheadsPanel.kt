package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.util.Locale

@Composable fun ToolheadsPanel(address: String, connected: Boolean, close: () -> Unit, factory: (String) -> ToolheadReader = { Moonraker(it) }) {
    var readings by remember(address) { mutableStateOf<List<ToolheadTemperature>?>(null) }
    var note by remember(address) { mutableStateOf("Loading toolhead temperatures…") }
    var epoch by remember(address) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun load() {
        if (!connected) { readings = null; note = "Disconnected. Connect to read toolhead temperatures."; return }
        val ticket = ++epoch
        note = "Loading toolhead temperatures…"
        scope.launch {
            val reader = try { factory(address) } catch (_: Exception) { if (ticket == epoch) note = "Toolhead temperatures unavailable."; return@launch }
            try {
                val result = withContext(Dispatchers.IO) { reader.toolheadTemperatures() }
                if (ticket != epoch) return@launch
                readings = result
                note = if (result.isEmpty()) "No toolheads detected." else "Loaded."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == epoch) { readings = null; note = e.message ?: "Toolhead temperatures unavailable." } }
            finally { reader.close() }
        }
    }
    LaunchedEffect(address, connected, lifecycle) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) load()
    }
    AlertDialog(onDismissRequest = close, title = { Text("Toolhead temperatures") }, confirmButton = { TextButton(close) { Text("Close") } }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Printer: $address")
            Text("Read-only current and target temperature for every detected toolhead, not just the active one.")
            Text(note, modifier = Modifier.testTag("toolheads-status"))
            fun temperature(value: Double?) = value?.takeIf { it.isFinite() }?.let { "${"%.0f".format(Locale.ROOT, it)}°C" } ?: "—"
            readings?.forEach { toolhead ->
                Row(Modifier.fillMaxWidth().testTag("toolhead-${toolhead.name}"), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompanionIcon(CompanionSymbol.NOZZLE, Modifier.size(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, description = "Toolhead")
                        Text(toolhead.name)
                    }
                    Text("${temperature(toolhead.temperature)} / target ${temperature(toolhead.target)}")
                }
            }
            TextButton({ load() }, enabled = connected, modifier = Modifier.testTag("refresh-toolheads")) { Text("Refresh") }
        }
    })
}

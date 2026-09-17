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

@Composable fun FanStatusPanel(address: String, connected: Boolean, close: () -> Unit, factory: (String) -> FanReadoutReader = { Moonraker(it) }) {
    var readouts by remember(address) { mutableStateOf<List<FanReadout>?>(null) }
    var note by remember(address) { mutableStateOf("Loading fan status…") }
    var epoch by remember(address) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun load() {
        if (!connected) { readouts = null; note = "Disconnected. Connect to read fan status."; return }
        val ticket = ++epoch
        note = "Loading fan status…"
        scope.launch {
            val reader = try { factory(address) } catch (_: Exception) { if (ticket == epoch) note = "Fan status unavailable."; return@launch }
            try {
                val result = withContext(Dispatchers.IO) { reader.fanReadouts() }
                if (ticket != epoch) return@launch
                readouts = result
                note = if (result.isEmpty()) "No manual fans detected." else "Loaded."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == epoch) { readouts = null; note = e.message ?: "Fan status unavailable." } }
            finally { reader.close() }
        }
    }
    LaunchedEffect(address, connected, lifecycle) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) load()
    }
    AlertDialog(onDismissRequest = close, title = { Text("Fan status") }, confirmButton = { TextButton(close) { Text("Close") } }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Printer: $address")
            Text("Read-only speed and, where the firmware reports it, tachometer RPM for every configured fan.")
            Text(note, modifier = Modifier.testTag("fanstatus-status"))
            readouts?.forEach { fan ->
                Row(Modifier.fillMaxWidth().testTag("fanstatus-${fan.fan}"), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(fan.fan)
                    val speedText = fan.speed?.let { "${(it * 100).toInt()}%" } ?: "—"
                    val rpmText = fan.rpm?.let { "${"%.0f".format(Locale.ROOT, it)} RPM" }
                    Text(if (rpmText != null) "$speedText · $rpmText" else speedText)
                }
            }
            TextButton({ load() }, enabled = connected, modifier = Modifier.testTag("refresh-fanstatus")) { Text("Refresh") }
        }
    })
}

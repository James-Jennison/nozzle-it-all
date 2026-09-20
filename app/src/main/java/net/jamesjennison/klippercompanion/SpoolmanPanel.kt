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

@Composable fun SpoolmanPanel(address: String, connected: Boolean, close: () -> Unit, factory: (String) -> SpoolmanReader = { Moonraker(it) }) {
    var inventory by remember(address) { mutableStateOf<SpoolmanInventory?>(null) }
    var note by remember(address) { mutableStateOf("Loading Spoolman inventory…") }
    var epoch by remember(address) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun load() {
        if (!connected) { inventory = null; note = "Disconnected. Connect to read Spoolman inventory."; return }
        val ticket = ++epoch
        note = "Loading Spoolman inventory…"
        scope.launch {
            val reader = try { factory(address) } catch (_: Exception) { if (ticket == epoch) note = "Spoolman unavailable."; return@launch }
            try {
                val result = withContext(Dispatchers.IO) { reader.spoolmanInventory() }
                if (ticket != epoch) return@launch
                inventory = result
                note = when {
                    !result.available -> "Spoolman not found. This requires the moonraker-spoolman component configured with a reachable Spoolman server."
                    result.spools.isEmpty() -> "Spoolman is connected but no spools are recorded yet."
                    else -> "Loaded."
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == epoch) { inventory = null; note = e.message ?: "Spoolman unavailable." } }
            finally { reader.close() }
        }
    }
    LaunchedEffect(address, connected, lifecycle) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) load()
    }
    AlertDialog(onDismissRequest = close, title = { Text("Spoolman") }, confirmButton = { TextButton(close) { Text("Close") } }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Printer: $address")
            Text("Read-only filament inventory. Selecting a spool or editing filaments/vendors is not built here.")
            Text(note, modifier = Modifier.testTag("spoolman-status"))
            inventory?.spools?.filterNot { it.archived }?.forEach { spool ->
                Row(Modifier.fillMaxWidth().testTag("spoolman-spool-${spool.id}"), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(Spoolman.displayName(spool) + if (spool.id == inventory?.activeSpoolId) " · Active" else "")
                        Text(listOfNotNull(spool.material, spool.colorHex?.let { "#$it" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                    val remaining = spool.remainingWeight
                    val total = spool.totalWeight
                    Text(if (remaining != null) "${"%.0f".format(Locale.ROOT, remaining)}g" + (total?.let { " / ${"%.0f".format(Locale.ROOT, it)}g" } ?: "") else "—")
                }
            }
            TextButton({ load() }, enabled = connected, modifier = Modifier.testTag("refresh-spoolman")) { Text("Refresh") }
        }
    })
}

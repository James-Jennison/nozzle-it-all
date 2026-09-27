package net.jamesjennison.klippercompanion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*

/** "#RRGGBB" to a Compose colour; null for anything else. */
internal fun slotColor(hex: String?): Color? = hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }

/**
 * Read-only view of a printer's filament slots: a Klipper filament changer's lanes (the Elegoo CANVAS on COSMOS, Box
 * Turtle, Happy Hare - FilamentLanes) or an Elegoo printer's CANVAS trays. Slot N feeds T(N-1), the same numbering as a
 * project's "Tool N" and the sliced file.
 */
@Composable fun FilamentSlotsPanel(address: String, connected: Boolean, close: () -> Unit, factory: (String) -> FilamentSlotReader) {
    var status by remember(address) { mutableStateOf<FilamentSlotStatus?>(null) }
    var note by remember(address) { mutableStateOf("Reading filament slots…") }
    var epoch by remember(address) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun load() {
        if (!connected) { status = null; note = "Disconnected. Connect to read filament slots."; return }
        val ticket = ++epoch
        note = "Reading filament slots…"
        scope.launch {
            val reader = try { withContext(Dispatchers.IO) { factory(address) } } catch (e: Exception) { if (ticket == epoch) note = e.message ?: "Filament slots unavailable."; return@launch }
            try {
                val result = withContext(Dispatchers.IO) { reader.filamentSlots() }
                if (ticket != epoch) return@launch
                status = result
                note = if (result.slots.isEmpty()) "No filament changer reported slots." else result.source
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == epoch) { status = null; note = e.message ?: "Filament slots unavailable." } }
            finally { withContext(NonCancellable + Dispatchers.IO) { runCatching { reader.close() } } }
        }
    }
    LaunchedEffect(address, connected, lifecycle) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) load()
    }
    AlertDialog(onDismissRequest = close, title = { Text("Filament slots") }, confirmButton = { TextButton(close) { Text("Close") } }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("What the printer reports is loaded in each slot. Slot 1 prints what a project assigns to Tool 1, and so on.", style = MaterialTheme.typography.bodySmall)
            Text(note, modifier = Modifier.testTag("filament-slots-status"))
            status?.slots?.forEach { slot ->
                Row(Modifier.fillMaxWidth().testTag("filament-slot-${slot.tool + 1}"), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(16.dp).background(slotColor(slot.colorHex) ?: MaterialTheme.colorScheme.surfaceVariant, CircleShape))
                        Text("Slot ${slot.tool + 1}" + if (slot.active) " · feeding" else "")
                    }
                    Text(if (slot.loaded) slot.label + (slot.nozzleTempC?.let { " · $it°C" } ?: "") else "Empty")
                }
            }
            TextButton({ load() }, enabled = connected, modifier = Modifier.testTag("refresh-filament-slots")) { Text("Refresh") }
        }
    })
}

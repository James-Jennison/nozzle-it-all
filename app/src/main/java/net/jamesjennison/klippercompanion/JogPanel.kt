package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

// Phase 7 (Consumer Slicer Plan §16): real relative-move jog + homing, the control gap the
// audit (§2.7) found - "No jog/movement controls ... found anywhere in the control surface".
// Real G-code (G91/G1/G90, G28), sent via printer/gcode/script exactly the way Console.kt's own
// raw-command entry and Moonraker.macro() already do - not a new protocol.
//
// Deliberately does NOT use this app's usual review-then-confirm two-step (HeaterPanel/LedPanel's
// own pattern): those panels need that step because they first fetch live server state (current
// temperature, the configured light list) to validate the request before it can even be built.
// A jog move needs no such round trip, and every mainstream Klipper UI (Mainsail, Fluidd,
// KlipperScreen) treats jogging as immediate, repeated taps, not a per-tap confirmation - a
// confirm dialog on every direction tap would make jogging actually unusable. The real safety
// gate here is instead: only enabled on an idle, ready printer (HeaterControls.idleStates, the
// same real gate this codebase already uses for the other genuinely physical action, heating),
// and Klipper's own firmware-side kinematic limits reject an out-of-range move regardless.
@Composable fun JogPanel(state: ScreenState, execute: (PrinterCommand, Int) -> Unit, close: () -> Unit) {
    var stepMm by remember { mutableStateOf(10.0) }
    val enabled = state.connected && state.snapshot?.ready == true && state.snapshot.state in HeaterControls.idleStates && !state.busy
    fun move(axis: String, delta: Double) {
        val signed = if (delta >= 0) "+${"%.2f".format(delta)}" else "%.2f".format(delta)
        execute(PrinterCommand("Jog $axis$signed", "printer/gcode/script",
            mapOf("script" to "G91\nG1 $axis${"%.2f".format(delta)} F3000\nG90")), state.generation)
    }
    fun home(axis: String?) {
        val script = if (axis == null) "G28" else "G28 $axis"
        execute(PrinterCommand(if (axis == null) "Home all axes" else "Home $axis", "printer/gcode/script", mapOf("script" to script)), state.generation)
    }
    AlertDialog(onDismissRequest = close, title = { Text("Jog controls") }, confirmButton = { TextButton(close) { Text("Close") } }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Printer: ${state.address}")
            if (!enabled) Text("Jogging requires an idle, connected printer.", color = MaterialTheme.colorScheme.error)
            Text("Step size", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.1 to "0.1mm", 1.0 to "1mm", 10.0 to "10mm", 50.0 to "50mm").forEach { (mm, label) ->
                    FilterChip(stepMm == mm, { stepMm = mm }, label = { Text(label) }, enabled = enabled, modifier = Modifier.testTag("jog-step-$mm"))
                }
            }
            Text("X / Y", style = MaterialTheme.typography.labelMedium)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton({ move("Y", stepMm) }, enabled = enabled, modifier = Modifier.testTag("jog-y-plus")) { Text("Y+") }
                Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                    OutlinedButton({ move("X", -stepMm) }, enabled = enabled, modifier = Modifier.testTag("jog-x-minus")) { Text("X-") }
                    OutlinedButton({ move("X", stepMm) }, enabled = enabled, modifier = Modifier.testTag("jog-x-plus")) { Text("X+") }
                }
                OutlinedButton({ move("Y", -stepMm) }, enabled = enabled, modifier = Modifier.testTag("jog-y-minus")) { Text("Y-") }
            }
            Text("Z", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ move("Z", stepMm) }, enabled = enabled, modifier = Modifier.testTag("jog-z-plus")) { Text("Z+") }
                OutlinedButton({ move("Z", -stepMm) }, enabled = enabled, modifier = Modifier.testTag("jog-z-minus")) { Text("Z-") }
            }
            Text("Homing", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button({ home(null) }, enabled = enabled, modifier = Modifier.testTag("jog-home-all")) { Text("Home all") }
                OutlinedButton({ home("X") }, enabled = enabled, modifier = Modifier.testTag("jog-home-x")) { Text("Home X") }
                OutlinedButton({ home("Y") }, enabled = enabled, modifier = Modifier.testTag("jog-home-y")) { Text("Home Y") }
                OutlinedButton({ home("Z") }, enabled = enabled, modifier = Modifier.testTag("jog-home-z")) { Text("Home Z") }
            }
        }
    })
}

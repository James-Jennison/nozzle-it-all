package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*

// Drying presets mirror Helix's own PANDA_DRY_PRESETS (components/dashboard/parts/Modules.tsx).
private val dryPresets = listOf(Triple("PLA 55°C 12h", "55", "12"), Triple("PETG 60°C 12h", "60", "12"))

@Composable fun PandaBreathPanel(state: ScreenState, execute: (PrinterCommand, Int) -> Unit, close: () -> Unit,
    factory: (String) -> PandaBreathReader = { a -> state.moonrakerFor(a) }) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reader = remember(state.address, state.generation) { factory(state.address) }
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var targetValue by remember { mutableStateOf("45") }
    var notice by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<PrinterCommand?>(null) }
    var preparedAt by remember { mutableLongStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var readEpoch by remember { mutableIntStateOf(0) }
    var lastStatus by remember { mutableStateOf<PandaBreathStatus?>(null) }
    fun invalidate() { readEpoch++; pending = null; notice = "" }
    DisposableEffect(reader, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_STOP) { readEpoch++; job?.cancel(); reader.close(); pending = null; notice = ""; busy = false }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); job?.cancel(); reader.close() }
    }
    LaunchedEffect(state.connected, state.generation, state.snapshot?.state) { readEpoch++; job?.cancel(); pending = null; notice = ""; busy = false }
    val enabled = foreground && state.connected && state.snapshot?.ready == true && state.snapshot.state in PandaBreathControls.idleStates && !state.busy && !busy
    fun prepare(build: (PandaBreathStatus) -> PrinterCommand) {
        invalidate(); busy = true
        val ticket = readEpoch
        job = scope.launch {
            try {
                val observed = withContext(Dispatchers.IO) { reader.pandaBreathStatus() }
                ensureActive()
                if (ticket != readEpoch) return@launch
                lastStatus = observed
                pending = build(observed)
                preparedAt = System.nanoTime() / 1_000_000
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (ticket == readEpoch) notice = e.message ?: "Cannot verify Panda Breath." }
            finally { if (ticket == readEpoch) busy = false }
        }
    }
    AlertDialog(onDismissRequest = close, title = { Text("Panda Breath") }, confirmButton = { TextButton(close) { Text("Close") } }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Printer: ${state.address}")
            Text("A chamber-heater/filament-dryer accessory. Not every printer has one - this is feature-detected, not assumed.")
            val status = lastStatus
            if (status != null) Text(
                if (status.detected) "Detected · ${status.temperature?.let { "%.0f".format(it) } ?: "?"}°C, target ${status.target?.let { "%.0f".format(it) } ?: "0"}°C · ${status.mode}"
                else "Not detected on this printer.",
                modifier = Modifier.testTag("panda-status"),
            )
            OutlinedTextField(targetValue, { targetValue = it; invalidate() }, enabled = !busy, label = { Text("Target °C (0 turns off)") }, singleLine = true, modifier = Modifier.testTag("panda-value"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button({ prepare { PandaBreathControls.prepareSetTarget(PandaBreathRequest.SetTarget(targetValue), it) } }, enabled = enabled, modifier = Modifier.testTag("prepare-panda-set")) { Text(if (busy) "Checking…" else "Set") }
                if (status?.supportsAuto != false) OutlinedButton({ prepare { PandaBreathControls.prepareAuto(PandaBreathRequest.SetAuto(status?.autoOn != true, targetValue), it) } }, enabled = enabled, modifier = Modifier.testTag("prepare-panda-auto")) { Text(if (status?.autoOn == true) "Turn off Auto" else "Auto") }
                if (status?.dryCommand != "") dryPresets.forEach { (label, temp, hours) ->
                    OutlinedButton({ prepare { PandaBreathControls.prepareDry(PandaBreathRequest.Dry(temp, hours), it) } }, enabled = enabled) { Text(label) }
                }
                TextButton({ prepare { PandaBreathControls.prepareStop(it) } }, enabled = enabled, modifier = Modifier.testTag("prepare-panda-stop")) { Text(if (status?.dryActive == true) "Stop" else "Off") }
            }
            if (notice.isNotEmpty()) Text(notice, modifier = Modifier.testTag("panda-notice"))
            pending?.let { command ->
                Text(command.title)
                Text(command.arguments.getValue("script"), modifier = Modifier.testTag("panda-script"))
                Text("The heater and printer state will be checked again before sending. Stay with the printer.")
                Button({
                    pending = null
                    if (System.nanoTime() / 1_000_000 - preparedAt !in 0..5000) notice = "Review the command again; this confirmation expired."
                    else execute(command, state.generation)
                }, enabled = enabled, modifier = Modifier.testTag("confirm-panda")) { Text("Confirm") }
            }
        }
    })
}

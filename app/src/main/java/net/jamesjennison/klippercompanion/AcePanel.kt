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

@Composable fun AcePanel(state: ScreenState, execute: (PrinterCommand, Int) -> Unit, close: () -> Unit,
    factory: (String) -> AceReader = { a -> state.moonrakerFor(a) }) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reader = remember(state.address, state.generation) { factory(state.address) }
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var notice by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<PrinterCommand?>(null) }
    var preparedAt by remember { mutableLongStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var readEpoch by remember { mutableIntStateOf(0) }
    var lastStatus by remember { mutableStateOf<AceStatus?>(null) }
    fun invalidate() { readEpoch++; pending = null; notice = "" }
    DisposableEffect(reader, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_STOP) { readEpoch++; job?.cancel(); reader.close(); pending = null; notice = ""; busy = false }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); job?.cancel(); reader.close() }
    }
    fun refresh() {
        val ticket = readEpoch
        job = scope.launch {
            try { val observed = withContext(Dispatchers.IO) { reader.aceStatus() }; if (ticket == readEpoch) lastStatus = observed }
            catch (e: CancellationException) { throw e } catch (e: Exception) { if (ticket == readEpoch) notice = e.message ?: "multiACE status unavailable." }
        }
    }
    LaunchedEffect(state.connected, state.generation, state.snapshot?.state) { readEpoch++; job?.cancel(); pending = null; notice = ""; busy = false; if (state.connected) refresh() }
    val enabled = foreground && state.connected && state.snapshot?.ready == true && state.snapshot.state in AceControls.idleStates && !state.busy && !busy
    fun prepare(build: (AceStatus) -> PrinterCommand) {
        invalidate(); busy = true
        val ticket = readEpoch
        job = scope.launch {
            try {
                val observed = withContext(Dispatchers.IO) { reader.aceStatus() }
                ensureActive()
                if (ticket != readEpoch) return@launch
                lastStatus = observed
                pending = build(observed)
                preparedAt = System.nanoTime() / 1_000_000
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (ticket == readEpoch) notice = e.message ?: "Cannot verify multiACE." }
            finally { if (ticket == readEpoch) busy = false }
        }
    }
    AlertDialog(onDismissRequest = close, title = { Text("multiACE") }, confirmButton = { TextButton(close) { Text("Close") } }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Printer: ${state.address}")
            Text("PAXX filament system. Not every U1/PAXX has this hardware - this is feature-detected, not assumed.")
            val status = lastStatus
            if (status == null) Text("Loading…", modifier = Modifier.testTag("ace-status"))
            else if (!status.hardwareDetected) Text("No multiACE hardware detected on this printer.", modifier = Modifier.testTag("ace-status"))
            else status.units.forEach { unit ->
                Column(Modifier.testTag("ace-unit-${unit.aceIndex}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("ACE ${unit.aceIndex}" + (if (unit.active) " · Active" else "") + (if (!unit.connected) " · Disconnected" else ""))
                    Text("${unit.temperature?.let { "%.0f".format(it) } ?: "?"}°C" + (unit.humidity?.let { " · %.0f%% humidity".format(it) } ?: "") +
                        (if (unit.dryer.active) " · Drying" + (unit.dryer.remainingMinutes?.let { " (${it.toInt()}m left)" } ?: "") else ""))
                    unit.lanes.forEach { lane ->
                        Text("  Lane ${lane.index}: ${lane.status}" + listOfNotNull(lane.brand, lane.material).let { if (it.isNotEmpty()) " (${it.joinToString(" ")})" else "" })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        unit.lanes.filter { it.status == "empty" || it.status == "loaded" }.forEach { lane ->
                            if (lane.status == "empty") TextButton({ prepare { AceControls.prepareLoad(AceRequest.Load(unit.aceIndex, lane.index), it) } }, enabled = enabled) { Text("Load ${lane.index}") }
                            else TextButton({ prepare { AceControls.prepareUnload(AceRequest.Unload(lane.index), it) } }, enabled = enabled) { Text("Unload ${lane.index}") }
                        }
                        if (!unit.dryer.active) TextButton({ prepare { AceControls.prepareDryStart(AceRequest.DryStart(unit.aceIndex, "55", "720"), it) } }, enabled = enabled) { Text("Dry (55°C, 12h)") }
                        else TextButton({ prepare { AceControls.prepareDryStop(AceRequest.DryStop(unit.aceIndex), it) } }, enabled = enabled) { Text("Stop drying") }
                        if (!unit.active) TextButton({ prepare { AceControls.prepareSwitch(AceRequest.Switch(unit.aceIndex), it) } }, enabled = enabled) { Text("Switch to this ACE") }
                    }
                }
            }
            if (status?.hardwareDetected == true) TextButton({ prepare { AceControls.prepareUnloadAll(it) } }, enabled = enabled, modifier = Modifier.testTag("prepare-ace-unload-all")) { Text("Unload all lanes") }
            if (notice.isNotEmpty()) Text(notice, modifier = Modifier.testTag("ace-notice"))
            pending?.let { command ->
                Text(command.title)
                Text(command.arguments.getValue("script"), modifier = Modifier.testTag("ace-script"))
                Text("The printer state will be checked again before sending. Stay with the printer.")
                Button({
                    pending = null
                    if (System.nanoTime() / 1_000_000 - preparedAt !in 0..5000) notice = "Review the command again; this confirmation expired."
                    else execute(command, state.generation)
                }, enabled = enabled, modifier = Modifier.testTag("confirm-ace")) { Text("Confirm") }
            }
            TextButton({ refresh() }, enabled = state.connected, modifier = Modifier.testTag("refresh-ace")) { Text("Refresh") }
        }
    })
}

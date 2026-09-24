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

@Composable fun SpeedFlowPanel(state: ScreenState, execute: (PrinterCommand, Int) -> Unit, close: () -> Unit,
    factory: (String) -> SpeedFlowReader = { Moonraker(it) }) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reader = remember(state.address, state.generation) { factory(state.address) }
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var kind by remember { mutableStateOf("speed") }
    var value by remember { mutableStateOf("100") }
    var notice by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<PrinterCommand?>(null) }
    var preparedAt by remember { mutableLongStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var readEpoch by remember { mutableIntStateOf(0) }
    fun invalidate() { readEpoch++; pending = null; notice = "" }
    DisposableEffect(reader, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_STOP) { readEpoch++; job?.cancel(); reader.close(); pending = null; notice = ""; busy = false }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); job?.cancel(); reader.close() }
    }
    LaunchedEffect(state.connected, state.generation, state.snapshot?.state) {
        readEpoch++; job?.cancel(); pending = null; notice = ""; busy = false
    }
    val enabled = foreground && state.connected && state.snapshot?.ready == true && state.snapshot.state in SpeedFlowControls.idleStates && !state.busy && !busy
    AlertDialog(onDismissRequest = close, title = { Text("Speed / flow") }, confirmButton = { TextButton(close) { Text("Close") } }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Printer: ${state.address}")
            Text("Choose speed or flow, review the exact M220/M221 command, then confirm. Only idle-printer changes are supported here.")
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(kind == "speed", { kind = "speed"; invalidate() }, enabled = !busy, label = { Text("Speed factor") }, modifier = Modifier.testTag("speedflow-speed"))
                FilterChip(kind == "flow", { kind = "flow"; invalidate() }, enabled = !busy, label = { Text("Flow (extrude) factor") }, modifier = Modifier.testTag("speedflow-flow"))
            }
            OutlinedTextField(value, { value = it; invalidate() }, enabled = !busy, label = { Text(if (kind == "speed") "Speed % (10-200)" else "Flow % (50-150)") }, singleLine = true, modifier = Modifier.testTag("speedflow-value"))
            TextButton({ value = "100"; invalidate() }, enabled = !busy) { Text("Reset to 100%") }
            Button({
                invalidate(); busy = true
                val ticket = readEpoch
                val request = SpeedFlowRequest(kind, value)
                job = scope.launch {
                    try {
                        val observed = withContext(Dispatchers.IO) { reader.speedFlowStatus() }
                        ensureActive()
                        if (ticket != readEpoch) return@launch
                        pending = SpeedFlowControls.prepare(request, observed)
                        preparedAt = System.nanoTime() / 1_000_000
                        val current = if (kind == "speed") observed.speedFactor else observed.extrudeFactor
                        notice = "Current factor: ${current?.times(100)?.toInt()}%."
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { if (ticket == readEpoch) notice = e.message ?: "Cannot verify speed/flow." }
                    finally { if (ticket == readEpoch) busy = false }
                }
            }, enabled = enabled, modifier = Modifier.testTag("review-speedflow")) { Text(if (busy) "Checking…" else "Review command") }
            if (notice.isNotEmpty()) Text(notice, modifier = Modifier.testTag("speedflow-notice"))
            pending?.let { command ->
                Text(command.title)
                Text(command.arguments.getValue("script"), modifier = Modifier.testTag("speedflow-script"))
                Text("Speed/flow and printer state will be checked again before sending. Stay with the printer.")
                Button({
                    pending = null
                    if (System.nanoTime() / 1_000_000 - preparedAt !in 0..5000) notice = "Review the command again; this confirmation expired."
                    else execute(command, state.generation)
                }, enabled = enabled, modifier = Modifier.testTag("confirm-speedflow")) { Text("Confirm command") }
            }
        }
    })
}

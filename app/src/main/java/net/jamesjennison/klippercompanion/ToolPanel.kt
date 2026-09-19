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

@Composable fun ToolPanel(state: ScreenState, execute: (PrinterCommand, Int) -> Unit, close: () -> Unit,
    factory: (String) -> ToolReader = { a -> state.moonrakerFor(a) }, clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reader = remember(state.address, state.generation) { factory(state.address) }
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var tools by remember { mutableStateOf<List<String>>(emptyList()) }
    var activeTool by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<PrinterCommand?>(null) }
    var preparedAt by remember { mutableLongStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var epoch by remember { mutableIntStateOf(0) }
    fun invalidate() { epoch++; pending = null; notice = "" }
    fun reset() { invalidate(); job?.cancel(); reader.close(); busy = false; tools = emptyList(); activeTool = "" }
    DisposableEffect(reader, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_STOP) reset()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); job?.cancel(); reader.close() }
    }
    LaunchedEffect(state.connected, state.generation, state.snapshot?.activeExtruder, state.snapshot?.state) { reset() }
    val enabled = foreground && state.connected && state.snapshot?.ready == true && state.snapshot.state in ToolControls.idleStates && !state.busy && !busy
    AlertDialog(onDismissRequest = close, title = { Text("Tool controls") }, confirmButton = { TextButton(close) { Text("Close") } }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Printer: ${state.address}")
            Text("On a toolchanger, only the active tool is physically on the carriage. Switching sends a T-command and takes a moment to complete mechanically - stay with the printer.")
            Button({
                invalidate(); activeTool = ""; tools = emptyList(); busy = true; val ticket = epoch
                job = scope.launch {
                    try {
                        val found = withContext(Dispatchers.IO) { reader.toolStatus() }
                        ensureActive(); if (ticket != epoch) return@launch
                        require(found.tools.size <= 32 && found.tools.all(ToolControls::validTool)) { "Unsupported tool list." }
                        tools = found.tools; activeTool = found.activeTool
                        notice = if (tools.isEmpty()) "No tools found." else "Active tool: ${found.activeTool.ifEmpty { "unknown" }}."
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { if (ticket == epoch) notice = e.message ?: "Cannot load tools." }
                    finally { if (ticket == epoch) busy = false }
                }
            }, enabled = enabled, modifier = Modifier.testTag("load-tools")) { Text("Load tools") }
            tools.forEach { name ->
                FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name)
                    if (name == activeTool) Text("Active (on carriage)", style = MaterialTheme.typography.bodySmall)
                    else OutlinedButton({
                        invalidate(); busy = true; val ticket = epoch
                        val request = ToolRequest(name)
                        job = scope.launch {
                            try {
                                val observed = withContext(Dispatchers.IO) { reader.toolStatus() }
                                ensureActive(); if (ticket != epoch) return@launch
                                pending = ToolControls.prepare(request, observed); preparedAt = clock()
                            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (ticket == epoch) notice = e.message ?: "Cannot verify this tool." }
                            finally { if (ticket == epoch) busy = false }
                        }
                    }, enabled = enabled, modifier = Modifier.testTag("review-tool-$name")) { Text(if (busy) "Checking…" else "Switch to this tool") }
                }
            }
            if (notice.isNotEmpty()) Text(notice, modifier = Modifier.testTag("tool-notice"))
            pending?.let { command ->
                Text(command.title); Text(command.arguments.getValue("script"), modifier = Modifier.testTag("tool-script"))
                Text("This performs a real mechanical tool change. State will be checked again before sending. Stay with the printer.")
                Button({
                    pending = null
                    if (clock() - preparedAt !in 0..5000) notice = "Review the command again; this confirmation expired."
                    else execute(command, state.generation)
                }, enabled = enabled, modifier = Modifier.testTag("confirm-tool")) { Text("Confirm tool switch") }
            }
        }
    })
}

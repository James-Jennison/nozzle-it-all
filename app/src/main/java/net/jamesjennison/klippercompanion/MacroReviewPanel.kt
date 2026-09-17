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

@Composable fun MacroReviewPanel(command: PrinterCommand, state: ScreenState, execute: (PrinterCommand, Int) -> Unit, close: () -> Unit,
    factory: (String) -> MacroReader = { a -> Moonraker(a, state.profiles.find{it.address==a}?.apiKey.orEmpty()) }) {
    val request = command.macroRequest ?: return
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
    fun check() {
        notice = ""; pending = null; busy = true
        val ticket = ++readEpoch
        job = scope.launch {
            try {
                val observed = withContext(Dispatchers.IO) { reader.macroStatus(request.name) }
                ensureActive()
                if (ticket != readEpoch) return@launch
                pending = MacroTools.prepare(request, observed)
                preparedAt = System.nanoTime() / 1_000_000
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (ticket == readEpoch) notice = e.message ?: "Cannot verify this macro." }
            finally { if (ticket == readEpoch) busy = false }
        }
    }
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
    LaunchedEffect(reader) { check() }
    val enabled = foreground && state.connected && state.snapshot?.ready == true && state.snapshot.state in MacroTools.allowedStates && !state.busy && !busy
    AlertDialog(onDismissRequest = close, title = { Text("Run ${request.name}") }, confirmButton = { TextButton(close) { Text("Close") } }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Printer: ${state.address}")
            Text(if (busy) "Checking the printer is idle and this macro is still available…" else "Review the exact command, then confirm. Only idle-printer execution is supported here.")
            TextButton({ check() }, enabled = enabled, modifier = Modifier.testTag("recheck-macro")) { Text("Check again") }
            if (notice.isNotEmpty()) Text(notice, modifier = Modifier.testTag("macro-notice"))
            pending?.let { checked ->
                Text(checked.title)
                Text(checked.arguments.getValue("script"), modifier = Modifier.testTag("macro-script"))
                Text("Printer state will be checked again before sending. Stay with the printer.")
                Button({
                    pending = null
                    if (System.nanoTime() / 1_000_000 - preparedAt !in 0..5000) notice = "Review the command again; this confirmation expired."
                    else execute(checked, state.generation)
                }, enabled = enabled, modifier = Modifier.testTag("confirm-macro")) { Text("Confirm command") }
            }
        }
    })
}

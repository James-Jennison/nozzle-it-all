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

@Composable fun LedPanel(state: ScreenState, execute: (PrinterCommand, Int) -> Unit, close: () -> Unit,
    factory: (String) -> LedReader = { a -> state.moonrakerFor(a) }, clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reader = remember(state.address, state.generation) { factory(state.address) }
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var leds by remember { mutableStateOf<List<String>>(emptyList()) }
    var led by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("100") }
    var notice by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<PrinterCommand?>(null) }
    var preparedAt by remember { mutableLongStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var epoch by remember { mutableIntStateOf(0) }
    fun invalidate() { epoch++; pending = null; notice = "" }
    fun reset() { invalidate(); job?.cancel(); reader.close(); busy = false; leds = emptyList(); led = "" }
    DisposableEffect(reader, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_STOP) reset()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); job?.cancel(); reader.close() }
    }
    LaunchedEffect(state.connected, state.generation) { reset() }
    // Lights are cosmetic and safe in any print state, so this only requires a ready printer.
    val enabled = foreground && state.connected && state.snapshot?.ready == true && !state.busy && !busy
    AlertDialog(onDismissRequest = close, title = { Text("Light controls") }, confirmButton = { TextButton(close) { Text("Close") } }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Printer: ${state.address}")
            Text("Select the exact configured light. Dimming is supported wherever the firmware exposes a white channel.")
            Button({
                invalidate(); led = ""; leds = emptyList(); busy = true; val ticket = epoch
                job = scope.launch {
                    try {
                        val found = withContext(Dispatchers.IO) { reader.leds() }
                        ensureActive(); if (ticket != epoch) return@launch
                        require(found.size <= 32 && found.all(LedControls::validLed)) { "Unsupported light list." }
                        leds = found.distinct(); notice = if (leds.isEmpty()) "No supported lights found." else "Select a light before reviewing a command."
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { if (ticket == epoch) notice = e.message ?: "Cannot load lights." }
                    finally { if (ticket == epoch) busy = false }
                }
            }, enabled = enabled, modifier = Modifier.testTag("load-leds")) { Text("Load lights") }
            leds.forEach { name -> FilterChip(led == name, { led = name; invalidate() }, enabled = !busy, label = { Text(name) }, modifier = Modifier.testTag("led-option-$name")) }
            if (led.isNotEmpty()) Text("Selected: $led")
            OutlinedTextField(value, { value = it; invalidate() }, enabled = !busy, label = { Text("Brightness % (0 turns off)") }, singleLine = true, modifier = Modifier.testTag("led-value"))
            TextButton({ value = "0"; invalidate() }, enabled = !busy) { Text("Off") }
            Button({
                invalidate(); busy = true; val ticket = epoch
                val request = LedRequest(led, value)
                job = scope.launch {
                    try {
                        val observed = withContext(Dispatchers.IO) { reader.ledStatus(request.led) }
                        ensureActive(); if (ticket != epoch) return@launch
                        pending = LedControls.prepare(request, observed); preparedAt = clock()
                        notice = "Current brightness: ${observed.brightness?.times(100)?.toInt()}%."
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { if (ticket == epoch) notice = e.message ?: "Cannot verify this light." }
                    finally { if (ticket == epoch) busy = false }
                }
            }, enabled = enabled && led in leds, modifier = Modifier.testTag("review-led")) { Text(if (busy) "Checking…" else "Review light command") }
            if (notice.isNotEmpty()) Text(notice, modifier = Modifier.testTag("led-notice"))
            pending?.let { command ->
                Text(command.title); Text(command.arguments.getValue("script"), modifier = Modifier.testTag("led-script"))
                Text("Light state will be checked again before sending.")
                Button({
                    pending = null
                    if (clock() - preparedAt !in 0..5000) notice = "Review the command again; this confirmation expired."
                    else execute(command, state.generation)
                }, enabled = enabled, modifier = Modifier.testTag("confirm-led")) { Text("Confirm light command") }
            }
        }
    })
}

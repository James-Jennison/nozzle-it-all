package net.jamesjennison.klippercompanion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF81E6CF), secondary = Color(0xFFB3C7FF), background = Color(0xFF10171D), surface = Color(0xFF19242D))) {
                val model: PrinterModel = viewModel(factory = viewModelFactory {
                    initializer {
                        val prefs = getSharedPreferences("printer", 0)
                        PrinterModel(prefs.getString("address", "") ?: "", { prefs.edit().putString("address", it).apply() })
                    }
                })
                val state by model.state.collectAsStateWithLifecycle()
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                DisposableEffect(lifecycle) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_START) model.foreground(true)
                        if (event == Lifecycle.Event.ON_STOP) model.foreground(false)
                    }
                    lifecycle.addObserver(observer)
                    model.foreground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                    onDispose { lifecycle.removeObserver(observer); model.foreground(false) }
                }
                CompanionScreen(state, model::connect, model::disconnect, model::refreshCatalog, model::execute)
            }
        }
    }
}

@Composable
fun CompanionScreen(state: ScreenState, connect: (String)->Unit, disconnect: ()->Unit, refresh: ()->Unit, execute: (PrinterCommand, Int)->Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var address by rememberSaveable(state.address) { mutableStateOf(state.address) }
    var pending by remember { mutableStateOf<Pair<PrinterCommand,Int>?>(null) }
    val enabled = state.connected && state.snapshot?.ready == true && !state.busy
    LaunchedEffect(state.generation, state.connected) { if(!state.connected || pending?.second != state.generation) pending = null }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                listOf("Monitor", "Macros", "Files", "Connect").forEachIndexed { index, title ->
                    NavigationBarItem(modifier = Modifier.testTag("nav-$index"), selected = tab == index, onClick = { tab = index }, icon = { Text(listOf("◉", "⌘", "▤", "⌁")[index], Modifier.clearAndSetSemantics {}) }, label = { Text(title) })
                }
            }
        }) { padding ->
        LazyColumn(Modifier.testTag("screen-list").fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
            item {
                Text("KLIPPER COMPANION", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(listOf("Your printer, at a glance", "Make it your workflow", "Ready for the next print", "Connect your printer")[tab], style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if(state.connected) "CONNECTED" else "OFFLINE", style = MaterialTheme.typography.labelLarge, color = if(state.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                        Text(state.message, style = MaterialTheme.typography.bodyMedium)
                        if(state.commandNotice.isNotBlank()) Text(state.commandNotice, color = MaterialTheme.colorScheme.secondary)
                        if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            if(tab == 3 || state.address.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("Moonraker or frontend address") }, placeholder = { Text("http://192.168.1.110") }, singleLine = true, enabled = !state.busy)
                        Text("Use your local Mainsail / Fluidd address, or Moonraker with port 7125. No account required.", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button({ connect(address); tab = 0 }, modifier = Modifier.testTag("connect-printer"), enabled = !state.busy && address.isNotBlank()) { Text("Connect") }
                            OutlinedButton(disconnect, enabled = !state.busy && state.address.isNotBlank()) { Text("Disconnect") }
                        }
                    }
                }
            } else when(tab) {
                0 -> {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(state.snapshot?.state?.replaceFirstChar { it.uppercase() } ?: "Awaiting printer", style = MaterialTheme.typography.titleLarge)
                                Text(state.snapshot?.filename?.ifBlank { "No active file" } ?: "Connect to see print status", style = MaterialTheme.typography.bodyMedium)
                                LinearProgressIndicator(progress = { state.snapshot?.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                                Text("${((state.snapshot?.progress ?: 0f)*100).toInt()}%", style = MaterialTheme.typography.headlineSmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton({ pending = PrinterCommand("Pause print", "printer/print/pause", allowedStates = setOf("printing")) to state.generation }, enabled = enabled && state.snapshot?.state == "printing") { Text("Pause") }
                                    OutlinedButton({ pending = PrinterCommand("Resume print", "printer/print/resume", allowedStates = setOf("paused")) to state.generation }, enabled = enabled && state.snapshot?.state == "paused") { Text("Resume") }
                                }
                                TextButton({ pending = PrinterCommand("Cancel print", "printer/print/cancel", allowedStates = setOf("printing", "paused")) to state.generation }, enabled = enabled && state.snapshot?.state in setOf("printing", "paused")) { Text("Cancel print", color = if(enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) }
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Temperature("NOZZLE", state.snapshot?.nozzle, state.snapshot?.nozzleTarget, Modifier.weight(1f))
                            Temperature("BED", state.snapshot?.bed, state.snapshot?.bedTarget, Modifier.weight(1f))
                        }
                    }
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Camera", style = MaterialTheme.typography.titleMedium)
                                val camera = state.catalog.cameras.firstOrNull()
                                if(state.connected && camera != null && camera.stream.isNotBlank()) LiveCamera(state.address, camera)
                                state.camera?.let { Image(it.asImageBitmap(), "Current printer camera snapshot", Modifier.fillMaxWidth().heightIn(max = 300.dp)) }
                                if(camera?.stream.isNullOrBlank()) Text(state.cameraNote.ifEmpty { "Camera snapshots appear after connection." }, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                1 -> {
                    item { Text("Macros run as configured on your printer. They may move axes or heat the nozzle. This version runs macros without parameters."); TextButton(refresh) { Text("Refresh lists") } }
                    if(state.catalog.macros.isEmpty()) item { Text("No available macros. Connect to a ready printer, then refresh.") }
                    items(state.catalog.macros, key = { it }) { macro ->
                        Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(macro, Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.bodyLarge)
                            OutlinedButton({ pending = Moonraker.macro(macro) to state.generation }, enabled = enabled) { Text("Run") }
                        } }
                    }
                }
                2 -> {
                    item { Text("G-code files stored on your printer."); TextButton(refresh) { Text("Refresh lists") } }
                    if(state.catalog.files.isEmpty()) item { Text("No G-code files available. Upload a file through your slicer or web interface.") }
                    items(state.catalog.files, key = { it }) { file ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(file, style = MaterialTheme.typography.titleSmall)
                            OutlinedButton({ pending = Moonraker.start(file) to state.generation }, enabled = enabled && state.snapshot?.state in setOf("standby", "complete", "cancelled", "error")) { Text("Start print") }
                        } }
                    }
                }
            }
            items(state.catalog.warnings) { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
            item { Text("LOCAL NETWORK  ·  ANDROID  ·  0.1.0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    pending?.let { (command, epoch) ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text(command.title + "?") },
            text = { Text("This sends a command to ${state.address}. It may move or heat your printer. Confirm only when the printer is safe and ready.") },
            confirmButton = { Button({ pending = null; execute(command, epoch) }, enabled = enabled) { Text("Confirm") } },
            dismissButton = { TextButton({ pending = null }) { Text("Go back") } })
    }
}
@Composable private fun Temperature(label: String, actual: Double?, target: Double?, modifier: Modifier) {
    Card(modifier) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(actual?.let { String.format(Locale.US, "%.1f°", it) } ?: "—", style = MaterialTheme.typography.headlineMedium)
        Text(target?.let { "Target ${it.toInt()}°C" } ?: "No reading", style = MaterialTheme.typography.bodySmall)
    } }
}

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.font.FontFamily
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
            CompanionTheme {
                val model: PrinterModel = viewModel(factory = viewModelFactory {
                    initializer {
                        val prefs = getSharedPreferences("printer", 0)
                        PrinterModel(PrinterPreferences.address(prefs), { address, printers -> PrinterPreferences.save(prefs, address, printers) },
                            initialPrinters = PrinterPreferences.printers(prefs))
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
                CompanionScreen(state, model::connect, model::disconnect, model::refreshCatalog, model::execute, model::forgetPrinter)
            }
        }
    }
}

@Composable
fun CompanionScreen(state: ScreenState, connect: (String)->Unit, disconnect: ()->Unit, refresh: ()->Unit, execute: (PrinterCommand, Int)->Unit, forgetPrinter: (String)->Unit = {}) {
    val listState = rememberLazyListState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(tab, state.generation) { listState.scrollToItem(0) }
    var address by rememberSaveable(state.address) { mutableStateOf(state.address) }
    var pending by remember { mutableStateOf<Pair<PrinterCommand,Int>?>(null) }
    var expandedCamera by remember(state.generation, state.connected) { mutableStateOf(false) }
    BackHandler(expandedCamera) { expandedCamera = false }
    val enabled = state.connected && state.snapshot?.ready == true && !state.busy
    LaunchedEffect(state.generation, state.connected) { if(!state.connected || pending?.second != state.generation) pending = null }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            if(state.commandNotice.isNotBlank()) Snackbar(Modifier.padding(12.dp).testTag("command-notice")) { Text(state.commandNotice) }
        },
        bottomBar = {
            if (!expandedCamera) NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                listOf("Dashboard", "Control", "Files", "Printers").forEachIndexed { index, title ->
                    NavigationBarItem(modifier = Modifier.testTag("nav-$index"), selected = tab == index, onClick = { tab = index }, icon = { CompanionIcon(CompanionSymbol.entries[index], color = if(tab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }, label = { Text(title) })
                }
            }
        }) { padding ->
        if (expandedCamera) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.Center) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Camera", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton({ expandedCamera = false }, Modifier.testTag("close-camera").semantics { contentDescription = "Close full screen camera" }) { CompanionIcon(CompanionSymbol.CLOSE) }
                }
                CameraContent(state)
            }
        } else LazyColumn(state = listState, modifier = Modifier.testTag("screen-list").fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CompanionIcon(CompanionSymbol.PRINTER, color = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text("Klipper Companion", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(listOf("Dashboard", "Control · Macros", "Files", "Printers")[tab], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ tab = 3 }) { Text("Switch") }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.address.ifBlank { "Add your first printer" }, style = MaterialTheme.typography.titleMedium)
                    Text(if(state.connected) "CONNECTED" else "OFFLINE", style = MaterialTheme.typography.labelMedium, color = if(state.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    if(!state.connected || state.snapshot?.ready != true || tab == 3) Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if(tab == 3 || state.address.isEmpty()) {
                if(state.savedPrinters.isNotEmpty()) {
                    item { Text("Saved printers", style = MaterialTheme.typography.titleMedium)
                        Text("One printer is monitored at a time. Connecting saves its address on this phone.", style = MaterialTheme.typography.bodyMedium) }
                    items(state.savedPrinters, key = { "saved:$it" }) { saved ->
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)) {
                            Column(Modifier.padding(16.dp)) {
                                Text(saved, style = MaterialTheme.typography.bodyLarge)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton({ connect(saved); tab = 0 }, enabled = !state.busy && !(state.connected && saved == state.address), modifier = Modifier.testTag("saved-connect:$saved")) { Text(if(state.connected && saved == state.address) "Connected" else "Connect") }
                                    TextButton({ forgetPrinter(saved) }, enabled = !state.busy, modifier = Modifier.testTag("saved-forget:$saved")) { Text("Forget") }
                                }
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("Moonraker or frontend address") }, placeholder = { Text("http://192.168.1.110") }, singleLine = true, enabled = !state.busy)
                        Text("Use your local Mainsail / Fluidd address, or Moonraker with port 7125. No account required.", style = MaterialTheme.typography.bodyMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button({ connect(address); tab = 0 }, modifier = Modifier.testTag("connect-printer"), enabled = !state.busy && address.isNotBlank()) { Text("Connect") }
                            OutlinedButton(disconnect, enabled = !state.busy && state.address.isNotBlank()) { Text("Disconnect") }
                        }
                    }
                }
            } else when(tab) {
                0 -> {
                    item {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Camera", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    IconButton({ expandedCamera = true }, Modifier.testTag("expand-camera").semantics { contentDescription = "Open full screen camera" }, enabled = state.connected && (state.catalog.cameras.firstOrNull()?.stream?.isNotBlank() == true || state.camera != null)) { CompanionIcon(CompanionSymbol.EXPAND) }
                                }
                                CameraContent(state)
                            }
                        }
                    }
                    item {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(state.snapshot?.state?.replaceFirstChar { it.uppercase() } ?: "Awaiting printer", style = MaterialTheme.typography.titleLarge)
                                Text(state.snapshot?.filename?.ifBlank { "No active file" } ?: "Connect to see print status", style = MaterialTheme.typography.bodyMedium)
                                LinearProgressIndicator(progress = { state.snapshot?.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                                Text(state.snapshot?.let { "${(it.progress*100).toInt()}%" } ?: "—", style = MaterialTheme.typography.headlineLarge, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton({ pending = PrinterCommand("Pause print", "printer/print/pause", allowedStates = setOf("printing")) to state.generation }, enabled = enabled && state.snapshot?.state == "printing") { Text("Pause") }
                                    OutlinedButton({ pending = PrinterCommand("Resume print", "printer/print/resume", allowedStates = setOf("paused")) to state.generation }, enabled = enabled && state.snapshot?.state == "paused") { Text("Resume") }
                                TextButton({ pending = PrinterCommand("Cancel print", "printer/print/cancel", allowedStates = setOf("printing", "paused")) to state.generation }, enabled = enabled && state.snapshot?.state in setOf("printing", "paused")) { Text("Cancel print", color = if(enabled && state.snapshot?.state in setOf("printing", "paused")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) }
                                }
                            }
                        }
                    }
                    item {
                        if (LocalDensity.current.fontScale > 1.3f) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Temperature("NOZZLE", state.snapshot?.nozzle, state.snapshot?.nozzleTarget, Modifier.fillMaxWidth())
                            Temperature("BED", state.snapshot?.bed, state.snapshot?.bedTarget, Modifier.fillMaxWidth())
                        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Temperature("NOZZLE", state.snapshot?.nozzle, state.snapshot?.nozzleTarget, Modifier.weight(1f))
                            Temperature("BED", state.snapshot?.bed, state.snapshot?.bedTarget, Modifier.weight(1f))
                        }
                    }
                    item {
                        Text("Quick tools", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ tab = 1 }) { Text("Macros") }
                            OutlinedButton({ tab = 2 }) { Text("Browse files") }
                            OutlinedButton({ tab = 3 }) { Text("Printers") }
                        }
                    }
                }
                1 -> {
                    item { Text("Macros run as configured on your printer. They may move axes or heat the nozzle. This version runs macros without parameters."); TextButton(refresh) { Text("Refresh lists") } }
                    if(state.catalog.macros.isEmpty()) item { Text("No available macros. Connect to a ready printer, then refresh.") }
                    items(state.catalog.macros, key = { it }) { macro ->
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)) { Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(macro, Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.bodyLarge)
                            OutlinedButton({ pending = Moonraker.macro(macro) to state.generation }, enabled = enabled) { Text("Run") }
                        } }
                    }
                }
                2 -> {
                    item { Text("G-code files stored on your printer."); TextButton(refresh) { Text("Refresh lists") } }
                    if(state.catalog.files.isEmpty()) item { Text("No G-code files available. Upload a file through your slicer or web interface.") }
                    items(state.catalog.files, key = { it }) { file ->
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CompanionIcon(if(label == "NOZZLE") CompanionSymbol.NOZZLE else CompanionSymbol.BED, color = if(label == "NOZZLE") Color(0xFFF3BC81) else Color(0xFF93C8ED))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(actual?.let { String.format(Locale.US, "%.1f°", it) } ?: "—", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
        Text(target?.let { "Target ${it.toInt()}°C" } ?: "No reading", style = MaterialTheme.typography.bodySmall)
    } }
}

@Composable private fun CameraContent(state: ScreenState) {
    val camera = state.catalog.cameras.firstOrNull()
    if(state.connected && camera != null && camera.stream.isNotBlank()) {
        key(state.generation, state.address, camera) { LiveCamera(state.address, camera) }
    } else if(state.connected && state.camera != null) {
        Image(state.camera.asImageBitmap(), "Current printer camera snapshot", Modifier.fillMaxWidth().aspectRatio(16f/9f))
        Text(state.cameraNote.ifBlank { "Refreshed snapshots" }, style = MaterialTheme.typography.bodySmall)
    } else {
        Column(Modifier.fillMaxWidth().aspectRatio(16f/9f).background(MaterialTheme.colorScheme.background, RoundedCornerShape(12.dp)), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CompanionIcon(CompanionSymbol.CAMERA)
            Text(if(!state.connected) "Connect to view camera" else "No camera available", Modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium)
        }
        if(state.cameraNote.isNotBlank()) Text(state.cameraNote, style = MaterialTheme.typography.bodySmall)
    }
}

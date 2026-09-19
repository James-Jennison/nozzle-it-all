package net.jamesjennison.klippercompanion

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import kotlinx.coroutines.launch
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

/** Paused while active development is monitoring-only; heater/fan panels are built and tested
 * but not physically re-accepted (see docs/M2_BED_RETEST_PROCEDURE.md). Flip back on when that
 * work resumes. */
const val LIVE_HEATER_FAN_CONTROLS_ENABLED = false

class MainActivity : ComponentActivity() {
    private var sharedFile by mutableStateOf<Uri?>(null)
    private fun receiveShare(value: Intent?) {
        if(value?.action==Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val uri=value.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            sharedFile=uri?.takeIf {it.scheme=="content"}
        }
    }
    override fun onNewIntent(intent:Intent) {super.onNewIntent(intent);setIntent(intent);receiveShare(intent)}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);receiveShare(intent); enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            val appearancePrefs = remember { getSharedPreferences("appearance", 0) }
            var appearance by remember { mutableStateOf(DashboardOptions.decode(runCatching { appearancePrefs.getString("options", null) }.getOrNull())) }
            val dark = appearance.mode == "Dark" || (appearance.mode == "System" && androidx.compose.foundation.isSystemInDarkTheme())
            SideEffect {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            CompanionTheme(dark = dark, accent = appearance.accent) {
                val model: PrinterModel = viewModel(factory = viewModelFactory {
                    initializer {
                        val prefs = getSharedPreferences("printer", 0)
                        val secrets = CredentialStore.open(applicationContext)
                        PrinterModel(PrinterPreferences.address(prefs), initialPrinters = PrinterPreferences.printers(prefs), initialProfiles=PrinterPreferences.profiles(prefs,secrets),
                            saveProfiles={ address, profiles -> PrinterPreferences.saveProfiles(prefs,secrets,address,profiles) })
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
                CompanionScreen(state, model::connect, model::disconnect, model::refreshCatalog, model::execute, model::forgetPrinter, model::updateProfile, model::favoriteProfile, model::moveProfile, model::selectCamera, model::selectFile, model::loadHistory, sharedFile=sharedFile, consumeShare={sharedFile=null}, appearance=appearance, saveAppearance={ appearance=it; appearancePrefs.edit().putString("options", it.encode()).apply() })
            }
        }
    }
}

@Composable
fun CompanionScreen(state: ScreenState, connect: (String)->Unit, disconnect: ()->Unit, refresh: ()->Unit, execute: (PrinterCommand, Int)->Unit, forgetPrinter: (String)->Unit = {}, updateProfile: (String,String,String,String)->String? = {_,_,_,_->null}, favoriteProfile: (String)->Unit = {},
    moveProfile: (String,Int)->Unit = {_,_->}, selectCamera: (String)->Unit = {}, selectFile: (String)->Unit = {}, loadHistory: (Int)->Unit = {}, sharedFile:Uri?=null,consumeShare:()->Unit={}, appearance:DashboardOptions=DashboardOptions(), saveAppearance:(DashboardOptions)->Unit={},
    consoleFactory:(String)->ConsoleReader={ a -> state.moonrakerFor(a) }, meshFactory:(String)->MeshReader={ a -> state.moonrakerFor(a) },
    toolheadsFactory:(String)->ToolheadReader={ a -> state.moonrakerFor(a) }, fanStatusFactory:(String)->FanReadoutReader={ a -> state.moonrakerFor(a) },
    configFactory:(String)->ConfigFileReader={ a -> state.moonrakerFor(a) }, configWriterFactory:(String)->ConfigWriter={ a -> state.moonrakerFor(a) },
    speedFlowFactory:(String)->SpeedFlowReader={ a -> state.moonrakerFor(a) },
    timelapseFactory:(String)->TimelapseReader={ a -> state.moonrakerFor(a) }, tileCamera: @Composable (PrinterTile)->Unit={PrinterTileCamera(it)}) {
    var consoleOpen by remember(state.address,state.generation) { mutableStateOf(false) }
    if(consoleOpen) ConsolePanel(state.address,state.connected,{consoleOpen=false},consoleFactory,
        ready=state.snapshot?.ready==true,execute=if(LIVE_HEATER_FAN_CONTROLS_ENABLED) execute else null,generation=state.generation)
    var meshOpen by remember(state.address,state.generation) { mutableStateOf(false) }
    if(meshOpen) BedMeshPanel(state.address,state.connected,{meshOpen=false},meshFactory)
    var toolheadsOpen by remember(state.address,state.generation) { mutableStateOf(false) }
    if(toolheadsOpen) ToolheadsPanel(state.address,state.connected,{toolheadsOpen=false},toolheadsFactory)
    var fanStatusOpen by remember(state.address,state.generation) { mutableStateOf(false) }
    if(fanStatusOpen) FanStatusPanel(state.address,state.connected,{fanStatusOpen=false},fanStatusFactory)
    var configOpen by remember(state.address,state.generation) { mutableStateOf(false) }
    if(configOpen) ConfigFilePanel(state.address,state.connected,{configOpen=false},configFactory,
        ready=state.snapshot?.ready==true,printState=state.snapshot?.state.orEmpty(),
        writerFactory=if(LIVE_HEATER_FAN_CONTROLS_ENABLED) configWriterFactory else null,
        execute=if(LIVE_HEATER_FAN_CONTROLS_ENABLED) execute else null,generation=state.generation)
    var heaterOpen by remember(state.address,state.generation) { mutableStateOf(false) }
    if(heaterOpen) HeaterPanel(state,execute,{heaterOpen=false})
    var fanOpen by remember(state.address,state.generation) { mutableStateOf(false) }
    if(fanOpen) FanPanel(state,execute,{fanOpen=false})
    var ledOpen by remember(state.address,state.generation) { mutableStateOf(false) }
    if(ledOpen) LedPanel(state,execute,{ledOpen=false})
    var speedFlowOpen by remember(state.address,state.generation) { mutableStateOf(false) }
    if(speedFlowOpen) SpeedFlowPanel(state,execute,{speedFlowOpen=false},speedFlowFactory)
    var timelapseOpen by remember(state.address,state.generation) { mutableStateOf(false) }
    if(timelapseOpen) TimelapsePanel(state.address,state.connected,{timelapseOpen=false},timelapseFactory)
    var controlPreview by rememberSaveable { mutableStateOf(false) }
    if(controlPreview) ControlPreviewPanel { controlPreview=false }
    var customize by rememberSaveable { mutableStateOf(false) }
    if(customize) DashboardEditor(appearance, saveAppearance) { customize=false }
    val listState = rememberLazyListState()
    val uiScope = rememberCoroutineScope()
    val context=LocalContext.current
    val workspace=remember(state.address,state.generation) {FileWorkspace(context.applicationContext,uiScope)}
    DisposableEffect(workspace) {onDispose {workspace.close()}}
    val macroPrefs=remember {context.getSharedPreferences("macro-options",0)}
    val macroKey=remember(state.address) {java.security.MessageDigest.getInstance("SHA-256").digest(state.address.toByteArray()).joinToString("") {"%02x".format(it)}}
    var macroOptions by remember(macroKey) {mutableStateOf(MacroTools.decode(runCatching {macroPrefs.getString(macroKey,"{}")} .getOrNull()?:"{}"))}
    fun saveMacro(name:String,options:MacroOptions) {macroOptions=macroOptions+(name to options);macroPrefs.edit().putString(macroKey,MacroTools.encode(macroOptions)).apply()}
    var editingMacro by remember(state.generation) {mutableStateOf<String?>(null)}
    var preparingMacro by remember(state.generation) {mutableStateOf<String?>(null)}
    var runningMacro by remember(state.generation) {mutableStateOf<PrinterCommand?>(null)}
    var macroFilter by remember(state.address) {mutableStateOf("")}
    val hostView=LocalView.current
    var cameraVisible by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detailAddress by rememberSaveable { mutableStateOf<String?>(null) }
    val overview = tab == 0 && detailAddress == null
    BackHandler(tab == 0 && detailAddress != null) { detailAddress = null }
    fun openPrinter(selected: String) {
        if(state.busy) return
        detailAddress = selected
        connect(selected)
        tab = 0
    }
    LaunchedEffect(tab, detailAddress, state.generation) { listState.scrollToItem(0) }
    var address by rememberSaveable(state.address) { mutableStateOf(state.address) }
    var editingProfile by remember(state.generation) { mutableStateOf<PrinterProfile?>(null) }
    var fileQuery by rememberSaveable(state.address) { mutableStateOf("") }
    var folder by rememberSaveable(state.address) { mutableStateOf("") }
    var newestFirst by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
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
                    NavigationBarItem(modifier = Modifier.testTag("nav-$index"), selected = tab == index, onClick = { tab = index; if(index == 0) detailAddress = null }, icon = { CompanionIcon(CompanionSymbol.entries[index], color = if(tab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }, label = { Text(title) })
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
                        Text("Nozzle It All", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(listOf("Dashboard", "Control · Macros", "Files", "Printers")[tab], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(tab == 0 && detailAddress != null) TextButton({ detailAddress = null }, Modifier.testTag("all-printers")) { Text("All printers") }
                    else TextButton({ tab = 3 }) { Text("Manage printers") }
                }
            }
            if(!overview) item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.profiles.firstOrNull { it.address==state.address }?.label ?: state.address.ifBlank { "Add your first printer" }, style = MaterialTheme.typography.titleMedium)
                    Text(if(state.connected) "CONNECTED" else "OFFLINE", style = MaterialTheme.typography.labelMedium, color = if(state.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    if(!state.connected || state.snapshot?.ready != true || tab == 3) Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if((tab == 0 && !overview) || tab == 3) item { TextButton({customize=true}, Modifier.testTag("customize-dashboard")) { Text("Customize dashboard") } }
            if(tab == 3 || (state.address.isEmpty() && tab != 2 && !overview)) {
                if(state.savedPrinters.isNotEmpty()) {
                    item { Text("Saved printers", style = MaterialTheme.typography.titleMedium)
                        Text("Saved printers connect automatically while the app is open. Select a printer to view its dashboard and controls.", style = MaterialTheme.typography.bodyMedium) }
                    items(state.savedPrinters, key = { "saved:$it" }) { saved ->
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)) {
                            Column(Modifier.padding(16.dp)) {
                                val profile=state.profiles.firstOrNull { it.address==saved } ?: PrinterProfile(saved)
                                Text(profile.label, style = MaterialTheme.typography.titleMedium)
                                if(profile.name.isNotBlank()) Text(saved, style = MaterialTheme.typography.bodySmall)
                                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    TextButton({editingProfile=profile},enabled=!state.busy,modifier=Modifier.testTag("edit-profile:$saved")){Text("Edit")}
                                    TextButton({favoriteProfile(saved)},enabled=!state.busy){Text(if(profile.favorite) "Unfavorite" else "Favorite")}
                                    TextButton({moveProfile(saved,-1)},enabled=!state.busy&&state.savedPrinters.indexOf(saved)>0){Text("Move up")}
                                    TextButton({moveProfile(saved,1)},enabled=!state.busy&&state.savedPrinters.indexOf(saved)<state.savedPrinters.lastIndex){Text("Move down")}
                                }
                                val connection = state.printerConnections[saved] ?: if (saved == state.address) PrinterConnection(state.connected,
                                    if(state.connected) state.snapshot?.state.orEmpty() else state.message)
                                    else null
                                Text(connection?.let { "${if(it.connected) "Connected" else "Offline"} • ${it.state}" }
                                    ?: "Monitoring paused", style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("saved-status:$saved"))
                                if(profile.favorite) Text("Favorite", style = MaterialTheme.typography.bodyLarge)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton({ openPrinter(saved) }, enabled = !state.busy && !(state.connected && saved == state.address), modifier = Modifier.testTag("saved-connect:$saved")) { Text(if(state.connected && saved == state.address) "Selected" else "Select / connect") }
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
                            Button({ openPrinter(address) }, modifier = Modifier.testTag("connect-printer"), enabled = !state.busy && address.isNotBlank()) { Text("Connect") }
                            OutlinedButton(disconnect, enabled = !state.busy && state.address.isNotBlank()) { Text("Disconnect") }
                        }
                    }
                }
            } else when(tab) {
                0 -> {
                    if(overview) {
                        val tiles = state.connectedPrinterTiles()
                        item { Text("Connected printers", style = MaterialTheme.typography.titleLarge) }
                        if(tiles.isEmpty()) item {
                            Text("No printers connected. Saved printers reconnect while the app is open.")
                            OutlinedButton({ tab = 3 }) { Text("View saved printers") }
                        } else item { PrinterTiles(tiles, !state.busy, ::openPrinter, tileCamera) }
                    } else if(detailAddress != state.address) {
                        item { Text("Waiting for the selected printer. Return to All printers to choose another.") }
                    } else items(appearance.order.filterNot { it in appearance.hidden }, key={"dashboard:$it"}) { card ->
                        when(card) {
                            "Camera" -> {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)) {
                            Column(Modifier.padding(12.dp).onGloballyPositioned { coordinates ->
                                val bounds=coordinates.boundsInWindow()
                                cameraVisible=bounds.bottom>0 && bounds.top<hostView.height && bounds.right>0 && bounds.left<hostView.width
                            }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Camera", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    IconButton({ expandedCamera = true }, Modifier.testTag("expand-camera").semantics { contentDescription = "Open full screen camera" }, enabled = state.connected && (state.selectedCamera()?.stream?.isNotBlank() == true || state.camera != null)) { CompanionIcon(CompanionSymbol.EXPAND) }
                                }
                                if(state.catalog.cameras.size>1 || (state.catalog.cameras.isNotEmpty() && state.selectedCamera()==null)) FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    state.catalog.cameras.forEach { camera -> FilterChip(selected=state.selectedCamera()?.id==camera.id,onClick={selectCamera(camera.id)},label={Text(camera.name)},modifier=Modifier.testTag("camera:${camera.id}")) }
                                }
                                CameraContent(state,cameraVisible)
                            }
                        }
                    }
                            "Print" -> {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(state.snapshot?.displayState?.replaceFirstChar { it.uppercase() } ?: "Awaiting printer", style = MaterialTheme.typography.titleLarge)
                                Text(state.snapshot?.activeFilename?.ifBlank { "No active file" } ?: "Connect to see print status", style = MaterialTheme.typography.bodyMedium)
                                Text("Elapsed ${formatDuration(state.snapshot?.printDuration)} · Remaining ${formatDuration(estimatedRemaining(state.snapshot,state.activeMetadata))}", style=MaterialTheme.typography.bodySmall)
                                if(estimatedRemaining(state.snapshot,state.activeMetadata)!=null) Text("Remaining time is a slicer-based estimate.",style=MaterialTheme.typography.labelSmall)
                                Text("Layer ${state.snapshot?.currentLayer ?: "Unknown"} / ${state.snapshot?.totalLayers ?: state.activeMetadata?.layers ?: "Unknown"}",style=MaterialTheme.typography.bodySmall)
                                LinearProgressIndicator(progress = { state.snapshot?.activeProgress ?: 0f }, modifier = Modifier.fillMaxWidth())
                                Text(state.snapshot?.let { "${(it.activeProgress*100).toInt()}%" } ?: "—", style = MaterialTheme.typography.headlineLarge, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton({ pending = PrinterCommand("Pause print", "printer/print/pause", allowedStates = setOf("printing")) to state.generation }, enabled = enabled && state.snapshot?.state == "printing") { Text("Pause") }
                                    OutlinedButton({ pending = PrinterCommand("Resume print", "printer/print/resume", allowedStates = setOf("paused")) to state.generation }, enabled = enabled && state.snapshot?.state == "paused") { Text("Resume") }
                                TextButton({ pending = PrinterCommand("Cancel print", "printer/print/cancel", allowedStates = setOf("printing", "paused")) to state.generation }, enabled = enabled && state.snapshot?.state in setOf("printing", "paused")) { Text("Cancel print", color = if(enabled && state.snapshot?.state in setOf("printing", "paused")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) }
                                }
                            }
                        }
                    }
                            "Temperatures" -> {
                        if (LocalDensity.current.fontScale > 1.3f) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Temperature(state.snapshot?.nozzleLabel ?: "NOZZLE · unknown tool", state.snapshot?.nozzle, state.snapshot?.nozzleTarget, Modifier.fillMaxWidth(), isNozzle=true)
                            Temperature("BED", state.snapshot?.bed, state.snapshot?.bedTarget, Modifier.fillMaxWidth())
                        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Temperature(state.snapshot?.nozzleLabel ?: "NOZZLE · unknown tool", state.snapshot?.nozzle, state.snapshot?.nozzleTarget, Modifier.weight(1f), isNozzle=true)
                            Temperature("BED", state.snapshot?.bed, state.snapshot?.bedTarget, Modifier.weight(1f))
                        }
                    }
                            "Quick tools" -> {
                        Text("Quick tools", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ tab = 1 }) { Text("Macros") }
                            OutlinedButton({ tab = 2 }) { Text("Browse files") }
                            OutlinedButton({ tab = 3 }) { Text("Printers") }
                        }
                    }
                        }
                    }
                }
                1 -> {
                    if(LIVE_HEATER_FAN_CONTROLS_ENABLED) {
                        item { OutlinedButton({heaterOpen=true},enabled=state.connected && state.snapshot?.ready==true,modifier=Modifier.testTag("open-heaters")){Text("Heater controls")} }
                        item { OutlinedButton({fanOpen=true},enabled=state.connected && state.snapshot?.ready==true,modifier=Modifier.testTag("open-fans")){Text("Fan controls")} }
                        item { OutlinedButton({ledOpen=true},enabled=state.connected && state.snapshot?.ready==true,modifier=Modifier.testTag("open-leds")){Text("Light controls")} }
                        item { OutlinedButton({speedFlowOpen=true},enabled=state.connected && state.snapshot?.ready==true,modifier=Modifier.testTag("open-speedflow")){Text("Speed / flow")} }
                    }
                    item { OutlinedButton({meshOpen=true},enabled=state.connected,modifier=Modifier.testTag("open-mesh")){Text("Bed mesh")} }
                    item { OutlinedButton({toolheadsOpen=true},enabled=state.connected,modifier=Modifier.testTag("open-toolheads")){Text("Toolhead temperatures")} }
                    item { OutlinedButton({fanStatusOpen=true},enabled=state.connected,modifier=Modifier.testTag("open-fanstatus")){Text("Fan status")} }
                    item { OutlinedButton({configOpen=true},enabled=state.connected,modifier=Modifier.testTag("open-config")){Text("Configuration")} }
                    item { OutlinedButton({timelapseOpen=true},enabled=state.connected,modifier=Modifier.testTag("open-timelapse")){Text("Timelapses")} }
                    item { OutlinedButton({consoleOpen=true},enabled=state.connected,modifier=Modifier.testTag("open-console")){Text(if(LIVE_HEATER_FAN_CONTROLS_ENABLED) "Console" else "Read-only console")} }
                    item { OutlinedButton({controlPreview=true},modifier=Modifier.testTag("advanced-control-preview")) {Text("Preview advanced controls")} }
                    item {
                        Text("Organize and prepare macros locally. Execution requires an idle printer and confirmation.")
                        OutlinedTextField(macroFilter,{macroFilter=it},label={Text("Search macros or groups")},modifier=Modifier.fillMaxWidth())
                        TextButton(refresh) {Text("Refresh lists")}
                    }
                    if(state.catalog.macros.isEmpty()) item {Text("No available macros. Connect to a ready printer, then refresh.")}
                    val macros=state.catalog.macros.filter {it.contains(macroFilter,true)||(macroOptions[it]?.group?:"").contains(macroFilter,true)}.sortedWith(compareByDescending<String> {macroOptions[it]?.favorite==true}.thenBy {macroOptions[it]?.group?:""}.thenBy {it})
                    items(macros,key={it}) {macro ->
                        val options=macroOptions[macro]?:MacroOptions()
                        Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp)) {
                            Text(macro,style=MaterialTheme.typography.titleMedium)
                            if(options.group.isNotBlank())Text(options.group)
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                TextButton({saveMacro(macro,options.copy(favorite=!options.favorite))}){Text(if(options.favorite)"Unfavorite" else "Favorite")}
                                TextButton({editingMacro=macro}){Text("Organize")}
                                if(LIVE_HEATER_FAN_CONTROLS_ENABLED) OutlinedButton({preparingMacro=macro}){Text("Run")}
                            }
                        }}
                    }
                }
                2 -> {
                    item {
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            FilterChip(!showHistory,{showHistory=false},label={Text("Files")})
                            FilterChip(showHistory,{showHistory=true;loadHistory(0)},label={Text("History")},modifier=Modifier.testTag("show-history"))
                        }
                    }
                    if(showHistory) {
                        item { HistoryHeader(state,loadHistory) }
                        items(state.history, key={"job:${it.id}"}) { job ->
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                Text(job.filename,style=MaterialTheme.typography.titleSmall)
                                Text("${job.status} · ${formatDuration(job.duration)} · ${formatMaterial(job.filamentMm)}")
                                job.started?.takeIf { it < 253402300799.0 }?.let { Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date((it*1000).toLong()))) }
                            } }
                        }
                    } else {
                        item {
                            OutlinedTextField(fileQuery,{fileQuery=it},label={Text("Search files")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                TextButton(refresh){Text("Refresh lists")}
                                TextButton({newestFirst=!newestFirst}){Text(if(newestFirst) "Sort: newest" else "Sort: name")}
                                if(folder.isNotBlank()) TextButton({folder=folder.substringBeforeLast('/',"")}){Text("Up a folder")}
                            }
                            Text(folder.ifBlank { "All files" })
                            FileWorkspacePanel(workspace,state,refresh)
                            FileDetails(state)
                        }
                        val prefix=if(folder.isBlank()) "" else "$folder/"
                        val candidates=state.catalog.files.filter { it.startsWith(prefix) && it.contains(fileQuery,true) }
                        val folders=if(fileQuery.isBlank()) candidates.map { it.removePrefix(prefix) }.filter { '/' in it }.map { it.substringBefore('/') }.distinct().sorted() else emptyList()
                        items(folders,key={"folder:$it"}) { name -> OutlinedButton({folder=prefix+name}){Text("Folder: $name")} }
                        val files=candidates.filter { fileQuery.isNotBlank() || '/' !in it.removePrefix(prefix) }.let { list ->
                            if(newestFirst) list.sortedByDescending { path -> state.catalog.fileInfo.firstOrNull { it.path==path }?.modified ?: -1.0 } else list.sorted()
                        }
                        if(files.isEmpty() && folders.isEmpty()) item { Text("No matching G-code files. Connect to read the printer's files.") }
                        items(files, key={"file:$it"}) { file ->
                            Card(Modifier.fillMaxWidth(), colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(12.dp)) {
                                Text(file,style=MaterialTheme.typography.titleSmall)
                                state.catalog.fileInfo.firstOrNull { it.path==file }?.size?.let { Text("${it/1024} KiB",style=MaterialTheme.typography.bodySmall) }
                                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton({workspace.download(state.address,file);uiScope.launch {listState.scrollToItem(0)}},enabled=state.connected&&!workspace.loading){Text("Download / preview")}
                                    OutlinedButton({selectFile(file);uiScope.launch {listState.scrollToItem(0)}},enabled=state.connected,modifier=Modifier.testTag("details:$file")){Text("Details")}
                                    OutlinedButton({pending=Moonraker.start(file) to state.generation},enabled=enabled&&state.snapshot?.state in setOf("standby","complete","cancelled","error")){Text("Start print")}
                                }
                            } }
                        }
                    }
                }

            }
            items(state.catalog.warnings) { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
            item { Text("LOCAL NETWORK  ·  ANDROID  ·  0.1.0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    sharedFile?.let {uri -> AlertDialog(onDismissRequest=consumeShare,title={Text("Import shared G-code?")},text={Text("Copy this document into the local workspace. It will not be uploaded or printed.")},confirmButton={TextButton({workspace.import(uri);tab=2;consumeShare()}){Text("Import")}},dismissButton={TextButton(consumeShare){Text("Cancel")}}) }
    editingMacro?.let {name->MacroEditor(name,macroOptions[name]?:MacroOptions(),{editingMacro=null}){saveMacro(name,it)}}
    preparingMacro?.let {name->MacroForm(name,macroOptions[name]?:MacroOptions(),{preparingMacro=null}){preparingMacro=null;runningMacro=it}}
    runningMacro?.let {command->MacroReviewPanel(command,state,execute,{runningMacro=null})}
    editingProfile?.let { ProfileEditor(it,{editingProfile=null},updateProfile) }
    pending?.let { (command, epoch) ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text(command.title + "?") },
            text = { Column { Text("This sends a command to ${state.address}. It may move or heat your printer. Confirm only when the printer is safe and ready.");command.arguments["script"]?.let {Text("Command: $it")};if(command.allowedStates.isNotEmpty() && state.snapshot?.state !in command.allowedStates)Text("Unavailable in the current print state.") } },
            confirmButton = { Button({ pending = null; execute(command, epoch) }, enabled = enabled && (command.allowedStates.isEmpty() || state.snapshot?.state in command.allowedStates)) { Text("Confirm") } },
            dismissButton = { TextButton({ pending = null }) { Text("Go back") } })
    }
}
@Composable private fun Temperature(label: String, actual: Double?, target: Double?, modifier: Modifier, isNozzle:Boolean=false) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CompanionIcon(if(isNozzle) CompanionSymbol.NOZZLE else CompanionSymbol.BED, color = if(isNozzle) Color(0xFFF3BC81) else Color(0xFF93C8ED))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(actual?.let { String.format(Locale.US, "%.1f°", it) } ?: "—", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
        Text(target?.let { "Target ${it.toInt()}°C" } ?: "No reading", style = MaterialTheme.typography.bodySmall)
    } }
}

@Composable private fun CameraContent(state: ScreenState, visible: Boolean = true) {
    if(!visible) { Spacer(Modifier.fillMaxWidth().aspectRatio(16f/9f));return }
    val camera = state.selectedCamera()
    if(state.connected && camera != null && camera.stream.isNotBlank()) {
        key(state.generation, state.cameraGeneration, state.address, camera) {
            when(camera.service) {
                "webrtc-camerastreamer" -> LiveCamera(state.address,camera)
                "mjpegstreamer", "mjpegstreamer-adaptive" -> MjpegCamera(state.address,camera)
                else -> Text("Unsupported live camera format: ${camera.service.ifBlank { "unknown" }}")
            }
        }
    } else if(state.connected && camera != null && state.camera != null) {
        Image(state.camera.asImageBitmap(), "Current printer camera snapshot", Modifier.fillMaxWidth().aspectRatio(16f/9f))
        Text(state.cameraNote.ifBlank { "Refreshed snapshots" }, style = MaterialTheme.typography.bodySmall)
    } else {
        Column(Modifier.fillMaxWidth().aspectRatio(16f/9f).background(MaterialTheme.colorScheme.background, RoundedCornerShape(12.dp)), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CompanionIcon(CompanionSymbol.CAMERA)
            Text(if(!state.connected) "Connect to view camera" else if(state.profiles.firstOrNull { it.address==state.address }?.cameraId?.isNotBlank()==true) "Selected camera unavailable. Choose an available camera." else "No camera available", Modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium)
        }
        if(state.cameraNote.isNotBlank()) Text(state.cameraNote, style = MaterialTheme.typography.bodySmall)
    }
}

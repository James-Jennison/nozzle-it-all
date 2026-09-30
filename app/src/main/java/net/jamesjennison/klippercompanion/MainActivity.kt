package net.jamesjennison.klippercompanion

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

/** Physical acceptance in progress (owner decision, 2026-09-19): live category-by-category
 * testing against the Snapmaker U1, owner watching throughout. See
 * docs/FEATURE_PARITY_ROADMAP.md's Phase 1 section for what this gates. */
const val LIVE_HEATER_FAN_CONTROLS_ENABLED = true

/** A real stop-sign octagon, not just a red pill - per council-design's review of the Control
 * tab redesign, color alone didn't sufficiently distinguish Emergency Stop from ordinary
 * controls. Only regular (equal-edge) when its own bounding box is square, which is why the
 * button this is applied to is always sized with Modifier.size(...), never fillMaxWidth(). The
 * 0.2929 corner-cut fraction (1 - 1/sqrt(2)) is the one that makes all eight edges equal length. */
val StopOctagonShape = GenericShape { size, _ ->
    val cut = minOf(size.width, size.height) * 0.2929f
    moveTo(cut, 0f)
    lineTo(size.width - cut, 0f)
    lineTo(size.width, cut)
    lineTo(size.width, size.height - cut)
    lineTo(size.width - cut, size.height)
    lineTo(cut, size.height)
    lineTo(0f, size.height - cut)
    lineTo(0f, cut)
    close()
}

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_STAGE_ADDRESS = "stage_address"
        const val EXTRA_STAGE_ACTION = "stage_action"
    }
    private var sharedFile by mutableStateOf<Uri?>(null)
    // A notification action (see PrintMonitorService.stageActionIntent) or the widget's own open
    // action (see NozzlePrinterWidget) opens the app with these set. Neither ever sends a command
    // itself - CompanionScreen's own LaunchedEffect below stages it into the existing
    // review/confirm flow, same rule every other mutating command in this app already follows.
    private var stagedAddress by mutableStateOf<String?>(null)
    private var stagedAction by mutableStateOf<String?>(null)
    private fun receiveMmfRedirect(value: Intent?) {
        val d = value?.data ?: return
        if (d.scheme == "nozzleitall" && d.host == "mmf-auth") { MmfRedirects.pending = d.toString() }
    }

    private fun receiveShare(value: Intent?) {
        if(value?.action==Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val uri=value.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            sharedFile=uri?.takeIf {it.scheme=="content"}
        }
        val address = value?.getStringExtra(EXTRA_STAGE_ADDRESS)
        if(address != null) { stagedAddress = address; stagedAction = value.getStringExtra(EXTRA_STAGE_ACTION) }
    }
    override fun onNewIntent(intent:Intent) {super.onNewIntent(intent);setIntent(intent);receiveShare(intent);receiveMmfRedirect(intent)}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);receiveShare(intent);if (savedInstanceState == null) receiveMmfRedirect(intent); enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        NozzleLog.sink = { level, tag, message -> if (level == 'w') android.util.Log.w(tag, message) else android.util.Log.i(tag, message) }
        setContent {
            val appearancePrefs = remember { getSharedPreferences("appearance", 0) }
            var appearance by remember { mutableStateOf(DashboardOptions.decode(runCatching { appearancePrefs.getString("options", null) }.getOrNull())) }
            val alertsPrefs = remember { getSharedPreferences("alerts", 0) }
            var backgroundAlertsEnabled by remember { mutableStateOf(alertsPrefs.getBoolean("enabled", false)) }
            val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) { backgroundAlertsEnabled = true; alertsPrefs.edit().putBoolean("enabled", true).apply(); PrintMonitorService.start(this) }
            }
            fun setBackgroundAlertsEnabled(enabled: Boolean) {
                if (!enabled) { backgroundAlertsEnabled = false; alertsPrefs.edit().putBoolean("enabled", false).apply(); PrintMonitorService.stop(this); return }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else { backgroundAlertsEnabled = true; alertsPrefs.edit().putBoolean("enabled", true).apply(); PrintMonitorService.start(this) }
            }
            // A previously-enabled service survives process death on its own (START_STICKY), but
            // this keeps it running after e.g. an app update replaces the process outright.
            LaunchedEffect(Unit) { if (backgroundAlertsEnabled) PrintMonitorService.start(this@MainActivity) }
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
                // First-run welcome: only for a brand-new install (no printers yet); finishing or skipping it is remembered forever.
                var onboardingDone by remember { mutableStateOf(OnboardingPrefs.isDone(this@MainActivity)) }
                var addPrinterNow by remember { mutableStateOf(!OnboardingPrefs.wizardSuppressed(this@MainActivity)) }
                if (!onboardingDone && state.profiles.isEmpty() && state.address.isBlank()) {
                    OnboardingScreen { addPrinter -> OnboardingPrefs.markDone(this@MainActivity, addPrinter); addPrinterNow = addPrinter; onboardingDone = true }
                } else {
                CompanionScreen(state, model::connect, model::disconnect, model::refreshCatalog, model::execute, model::forgetPrinter, model::updateProfile, model::favoriteProfile, model::moveProfile, model::selectCamera, model::selectFile, model::loadHistory, sharedFile=sharedFile, setCustomMachine=model::setCustomMachine, consumeShare={sharedFile=null}, appearance=appearance, saveAppearance={ appearance=it; appearancePrefs.edit().putString("options", it.encode()).apply() },
                    backgroundAlertsEnabled=backgroundAlertsEnabled, setBackgroundAlertsEnabled=::setBackgroundAlertsEnabled, emergencyStop=model::emergencyStop,
                    stagedAddress=stagedAddress, stagedAction=stagedAction, consumeStagedAction={stagedAddress=null;stagedAction=null}, detectFirmware=model::detectFirmware, detectLanes=model::detectFilamentLanes, addProfile=model::addProfile,
                    dismissCommandNotice=model::dismissCommandNotice, autoOpenWizard=addPrinterNow)
                }
            }
        }
    }
}

private const val MAX_CONTENT_WIDTH_DP = 720

private enum class BackupStep { NONE, EXPORT_PASSPHRASE, IMPORT_PASSPHRASE }

@Composable
fun CompanionScreen(state: ScreenState, connect: (String)->Unit, disconnect: ()->Unit, refresh: ()->Unit, execute: (PrinterCommand, Int)->Unit, forgetPrinter: (String)->Unit = {}, updateProfile: (String,String,String,String,PrinterKind,String,SlicingPrinterModel?)->String? = {_,_,_,_,_,_,_->null}, favoriteProfile: (String)->Unit = {},
    moveProfile: (String,Int)->Unit = {_,_->}, selectCamera: (String)->Unit = {}, selectFile: (String)->Unit = {}, loadHistory: (Int)->Unit = {}, sharedFile:Uri?=null,consumeShare:()->Unit={}, appearance:DashboardOptions=DashboardOptions(), saveAppearance:(DashboardOptions)->Unit={},
    backgroundAlertsEnabled:Boolean=false, setBackgroundAlertsEnabled:(Boolean)->Unit={}, emergencyStop:()->Unit={}, detectFirmware:((String, (Result<FirmwareIdentity>)->Unit)->Unit)?=null, detectLanes:((String, (Result<Int>)->Unit)->Unit)?=null, addProfile:(PrinterProfile)->String?={null},
    stagedAddress:String?=null, stagedAction:String?=null, consumeStagedAction:()->Unit={}, dismissCommandNotice:()->Unit={}, autoOpenWizard:Boolean=true, setCustomMachine:(String, CustomMachine?)->Unit={_,_->},
    consoleFactory:(String)->ConsoleReader={ a -> state.moonrakerFor(a) }, meshFactory:(String)->MeshReader={ a -> state.moonrakerFor(a) },
    toolheadsFactory:(String)->ToolheadReader={ a -> state.moonrakerFor(a) }, fanStatusFactory:(String)->FanReadoutReader={ a -> state.moonrakerFor(a) },
    configFactory:(String)->ConfigFileReader={ a -> state.moonrakerFor(a) }, configWriterFactory:(String)->ConfigWriter={ a -> state.moonrakerFor(a) },
    speedFlowFactory:(String)->SpeedFlowReader={ a -> state.moonrakerFor(a) },
    timelapseFactory:(String)->TimelapseReader={ a -> state.moonrakerFor(a) },
    pandaBreathFactory:(String)->PandaBreathReader={ a -> state.moonrakerFor(a) },
    spoolmanFactory:(String)->SpoolmanReader={ a -> state.moonrakerFor(a) },
    aceFactory:(String)->AceReader={ a -> state.moonrakerFor(a) },
    filamentSlotsFactory:(String)->FilamentSlotReader={ a -> state.filamentSlotReaderFor(a) },
    tileCamera: @Composable (PrinterTile)->Unit={PrinterTileCamera(it)}) {
    // Phase 7 (§16): hoisted up from further below (it's a pure derived value, no side effects)
    // so the panel-open blocks right below can pass real per-printer control-gate flags
    // (supportsJog/supportsBedLevelingTrigger/supportsTimelapseTrigger/supportsFilamentLoadUnload)
    // into BedMeshPanel/TimelapsePanel/JogPanel rather than those panels guessing.
    val capabilities = state.capabilitiesFor(state.address)
    var consoleOpen by remember(state.address,state.generation) { mutableStateOf(false) }
    if(consoleOpen) ConsolePanel(state.address,state.connected,{consoleOpen=false},consoleFactory,
        ready=state.snapshot?.ready==true,execute=if(LIVE_HEATER_FAN_CONTROLS_ENABLED) execute else null,generation=state.generation)
    var meshOpen by remember(state.address,state.generation) { mutableStateOf(false) }
    if(meshOpen) BedMeshPanel(state.address,state.connected,{meshOpen=false},meshFactory,
        execute=if(LIVE_HEATER_FAN_CONTROLS_ENABLED) execute else null,generation=state.generation,
        canCalibrate=LIVE_HEATER_FAN_CONTROLS_ENABLED && capabilities.supportsBedLevelingTrigger,
        printReady=state.snapshot?.ready==true && state.snapshot.state in HeaterControls.idleStates)
    var jogOpen by remember(state.address,state.generation) { mutableStateOf(false) }
    if(jogOpen && LIVE_HEATER_FAN_CONTROLS_ENABLED) JogPanel(state,execute,{jogOpen=false})
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
    var bespok3dOpen by remember(state.address,state.generation) { mutableStateOf(false) }
    if(bespok3dOpen) Bespok3dPanel(state,{bespok3dOpen=false})
    var aceOpen by remember(state.address,state.generation) { mutableStateOf(false) }
    if(aceOpen) AcePanel(state,execute,{aceOpen=false},aceFactory)
    var filamentSlotsOpen by remember(state.address,state.generation) { mutableStateOf(false) }
    if(filamentSlotsOpen) FilamentSlotsPanel(state.address,state.connected,{filamentSlotsOpen=false},filamentSlotsFactory)
    var ledOpen by remember(state.address,state.generation) { mutableStateOf(false) }
    if(ledOpen) LedPanel(state,execute,{ledOpen=false})
    var toolOpen by remember(state.address,state.generation) { mutableStateOf(false) }
    if(toolOpen) ToolPanel(state,execute,{toolOpen=false})
    var speedFlowOpen by remember(state.address,state.generation) { mutableStateOf(false) }
    if(speedFlowOpen) SpeedFlowPanel(state,execute,{speedFlowOpen=false},speedFlowFactory)
    var pandaBreathOpen by remember(state.address,state.generation) { mutableStateOf(false) }
    if(pandaBreathOpen) PandaBreathPanel(state,execute,{pandaBreathOpen=false},pandaBreathFactory)
    var timelapseOpen by remember(state.address,state.generation) { mutableStateOf(false) }
    if(timelapseOpen) TimelapsePanel(state.address,state.connected,{timelapseOpen=false},timelapseFactory,
        canRender=LIVE_HEATER_FAN_CONTROLS_ENABLED && capabilities.supportsTimelapseTrigger)
    var spoolmanOpen by remember(state.address,state.generation) { mutableStateOf(false) }
    if(spoolmanOpen) SpoolmanPanel(state.address,state.connected,{spoolmanOpen=false},spoolmanFactory)
    var controlPreview by rememberSaveable { mutableStateOf(false) }
    if(controlPreview) ControlPreviewPanel { controlPreview=false }
    var customize by rememberSaveable { mutableStateOf(false) }
    if(customize) DashboardEditor(appearance, saveAppearance) { customize=false }
    val listState = rememberLazyListState()
    val uiScope = rememberCoroutineScope()
    val context=LocalContext.current
    val workspace=remember(state.address,state.generation) {FileWorkspace(context.applicationContext,uiScope)}
    DisposableEffect(workspace) {onDispose {workspace.close()}}
    // WO-17 (Phase 1): the Files tab's "Projects" section reads this - always collected (cheap,
    // matches `state` itself always being collected above) rather than only while that section
    // is visible, since a LazyListScope's own body isn't a @Composable context and can't call
    // remember/collectAsStateWithLifecycle itself.
    val projectDb = remember { net.jamesjennison.klippercompanion.project.AppDatabase.get(context.applicationContext) }
    val projects by projectDb.projectDao().observeProjects().collectAsStateWithLifecycle(initialValue = emptyList())
    // Phase 1 (Consumer Slicer Plan §16, "Project save/load/rename/drafts"): rename/delete reuse
    // ProjectViewModel's own real logic (renameProject/deleteProject, the latter also cleaning up
    // ProjectFileStore's persisted object files) rather than calling projectDao directly here and
    // duplicating that cleanup - a fresh, short-lived instance per action, loaded with the target
    // project first since ProjectViewModel's own methods act on "the currently loaded project."
    var renamingProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameDraft by rememberSaveable { mutableStateOf("") }
    var deletingProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    val macroPrefs=remember {context.getSharedPreferences("macro-options",0)}
    val macroKey=remember(state.address) {java.security.MessageDigest.getInstance("SHA-256").digest(state.address.toByteArray()).joinToString("") {"%02x".format(it)}}
    var macroOptions by remember(macroKey) {mutableStateOf(MacroTools.decode(runCatching {macroPrefs.getString(macroKey,"{}")} .getOrNull()?:"{}"))}
    fun saveMacro(name:String,options:MacroOptions) {macroOptions=macroOptions+(name to options);macroPrefs.edit().putString(macroKey,MacroTools.encode(macroOptions)).apply()}
    var editingMacro by remember(state.generation) {mutableStateOf<String?>(null)}
    var preparingMacro by remember(state.generation) {mutableStateOf<String?>(null)}
    var runningMacro by remember(state.generation) {mutableStateOf<PrinterCommand?>(null)}
    var macrosOpen by remember(state.address) {mutableStateOf(false)}
    if(macrosOpen) MacrosBrowserPanel(state.catalog.macros, macroOptions, ::saveMacro, refresh, {editingMacro=it}, {preparingMacro=it}) {macrosOpen=false}
    val hostView=LocalView.current
    var cameraVisible by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detailAddress by rememberSaveable { mutableStateOf<String?>(null) }
    val overview = tab == 0 && detailAddress == null
    // WO-17 (Phase 1): the Files tab's "Projects" section - a saved multi-object build plate,
    // separate from the single-object share-intent/Prepare-tab flow above. editingProjectId
    // opens an existing project; editingNewProjectName creates one on open (see
    // ProjectEditorScreen's own remember(projectId, newProjectName) key). Exactly one of the two
    // is ever non-null at a time.
    var editingProjectId by remember { mutableStateOf<String?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(MmfRedirects.pending) { if (MmfRedirects.pending != null) tab = 5 }
    var selfCheckRunning by remember { mutableStateOf(false) }
    var creditsOpen by remember { mutableStateOf(false) }
    // Test Mode (Nozzle Test Grid): a separate full-screen window; it never shares the dashboard's connection or pending command.
    var testModeOpen by rememberSaveable { mutableStateOf(false) }
    // Hidden until an invited tester turns it on (TestModeAccess): 7 quick taps on a version line (Settings → Diagnostics or About & credits).
    var testModeEnabled by remember { mutableStateOf(net.jamesjennison.klippercompanion.testgrid.TestModeAccess.isEnabled(context)) }
    val versionTaps = remember { net.jamesjennison.klippercompanion.testgrid.TestModeAccess.TapCounter() }
    var testModeNote by remember { mutableStateOf<String?>(null) }
    // Either version line (Settings → Diagnostics, or About & credits) counts toward the same 7 taps.
    fun tapVersion() {
        if(versionTaps.tap()) {
            testModeEnabled = !testModeEnabled
            net.jamesjennison.klippercompanion.testgrid.TestModeAccess.setEnabled(context, testModeEnabled)
            testModeNote = if(testModeEnabled) "Test Mode is on: it's in Settings, next to About & credits." else "Test Mode is off."
            android.widget.Toast.makeText(context, testModeNote, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    var backupStep by remember { mutableStateOf(BackupStep.NONE) }
    var backupPassphrase by remember { mutableStateOf("") }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pass = backupPassphrase.toCharArray(); backupPassphrase = ""; backupStep = BackupStep.NONE
        if(uri != null) uiScope.launch {
            backupMessage = try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { it.write(SettingsBackup.encode(state.profiles, pass)) } }; "Saved a backup of ${state.profiles.size} printer(s)." }
            catch(e: BackupException) { e.message } catch(e: Exception) { "Could not save the backup: ${e.message}" }
        }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri != null) { restoreUri = uri; backupStep = BackupStep.IMPORT_PASSPHRASE; backupMessage = null } }
    var selfCheckResults by remember { mutableStateOf<List<SelfCheckResult>>(emptyList()) }
    var interruptedSliceNotice by remember { mutableStateOf(SlicingCoordinator.consumeInterruptedSlice(context.applicationContext)) }
    var calibrationDialog by remember { mutableStateOf(false) }
    var calibrationKind by remember { mutableStateOf(CalibrationKind.TEMPERATURE) }
    var calStart by remember { mutableStateOf("230") }
    var calStep by remember { mutableStateOf("-5") }
    var calSections by remember { mutableStateOf("5") }
    val importProject = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uiScope.launch {
            try {
                val imported = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val importer = net.jamesjennison.klippercompanion.project.ProjectViewModel(context.applicationContext, projectDb.projectDao())
                    context.contentResolver.openInputStream(uri)?.use { importer.importArchive(it) } ?: error("Cannot open that file.")
                }
                importMessage = null; editingProjectId = imported.id
            } catch (e: Exception) { importMessage = "Import failed: ${e.message}" }
        }
    }
    var editingNewProjectName by remember { mutableStateOf<String?>(null) }
    var newProjectNameDraft by rememberSaveable { mutableStateOf<String?>(null) }
    // WO-30 (owner request, 2026-09-23: "the Prepare tab should default right to the in-app
    // slicer" - no landing pill/placeholder first): entering the Prepare tab with no project
    // already open auto-creates one and opens the real editor immediately, the same
    // ProjectEditorScreen Files > Projects' own "Open" already uses. Auto-named (no upfront
    // naming prompt) since this is the quick-start entry point - it can be renamed later from
    // Files > Projects. Guarded on both editing* being null so switching tabs away and back
    // doesn't spawn a second project on top of one already open.
    LaunchedEffect(tab) {
        if (tab == 3 && editingProjectId == null && editingNewProjectName == null) {
            editingNewProjectName = "New print ${java.text.SimpleDateFormat("MMM d, HH:mm").format(java.util.Date())}"
        }
    }
    BackHandler(tab == 0 && detailAddress != null) { detailAddress = null }
    fun openPrinter(selected: String) {
        if(state.busy) return
        detailAddress = selected
        connect(selected)
        tab = 0
    }
    LaunchedEffect(tab, detailAddress, state.generation) { listState.scrollToItem(0) }
    var editingProfile by remember(state.generation) { mutableStateOf<PrinterProfile?>(null) }
    var addingPrinter by remember { mutableStateOf(false) }
    // First run (or every profile forgotten) used to land on a bare dashboard - "No printers
    // connected" plus a "View saved printers" button that only jumped to the Settings tab, where
    // "Add printer" still had to be found and tapped. AddPrinterWizard is the app's own real
    // first-run setup flow; this just opens it automatically instead of requiring a scavenger
    // hunt to find it. Gated on address also being blank, not just profiles being empty - a
    // still-connected-but-unsaved session (state.address set without a matching saved profile)
    // is a real, different state and shouldn't be interrupted by a first-run dialog. rememberSaveable
    // (not remember) so it only auto-opens once per app install, not on every recomposition while
    // the wizard's still on screen; a user who cancels out of it, or later forgets every printer
    // again, isn't forced back in against their will.
    var autoOpenedWizard by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.profiles.isEmpty(), state.address.isBlank()) {
        if (autoOpenWizard && state.profiles.isEmpty() && state.address.isBlank() && !autoOpenedWizard) { addingPrinter = true; autoOpenedWizard = true }
    }
    var fileQuery by rememberSaveable(state.address) { mutableStateOf("") }
    var folder by rememberSaveable(state.address) { mutableStateOf("") }
    var newestFirst by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showProjects by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<PrinterCommand,Int>?>(null) }
    // A notification action or the widget's open action (see MainActivity.stagedAddress/
    // stagedAction) lands here. Navigates to the printer as soon as the address arrives; once
    // it's actually connected, stages the command into the same pending?.let confirm dialog every
    // other mutating command already uses - never dispatched directly. Matches openPrinter's own
    // busy-guard implicitly: execute() itself re-checks freshness before sending regardless.
    LaunchedEffect(stagedAddress) { stagedAddress?.let { openPrinter(it) } }
    LaunchedEffect(stagedAddress, stagedAction, state.address, state.connected) {
        val target = stagedAddress ?: return@LaunchedEffect
        if(state.address == target && state.connected) {
            when(stagedAction) {
                "resume" -> pending = PrinterCommand("Resume print", "printer/print/resume", allowedStates = setOf("paused")) to state.generation
                "cancel" -> pending = PrinterCommand("Cancel print", "printer/print/cancel", allowedStates = setOf("printing", "paused")) to state.generation
            }
            consumeStagedAction()
        }
    }
    var estopConfirm by rememberSaveable { mutableStateOf(false) }
    var expandedCamera by remember(state.generation, state.connected) { mutableStateOf(false) }
    BackHandler(expandedCamera) { expandedCamera = false }
    val enabled = state.connected && state.snapshot?.ready == true && !state.busy
    // Phase 2 (Consumer Slicer Plan §10): one real capability object, replacing the old
    // bambu/prusa/nonKlipper flags below - see PrinterCapabilities.kt for what each field means
    // and why Bambu/Prusa (both non-Moonraker) share `supportsKlipperExtras = false` while
    // `supportsPauseResumeCancel` differs (PrusaLinkPrinterService's command() does support
    // pause/resume/cancel; BambuPrinterService's does not). (Declared near the top of this
    // function now - see its own comment there.)
    LaunchedEffect(state.generation, state.connected) { if(!state.connected || pending?.second != state.generation) pending = null }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            // Used to just sit there forever once set (ScreenState.commandNotice has no other
            // owner to clear it) until the next command happened to overwrite it - effectively a
            // permanent banner pinned over the bottom nav for anything the user didn't
            // immediately act on again. Now a real dismissible notice: an explicit close action,
            // plus an auto-dismiss timer so an unattended notice doesn't linger indefinitely.
            if(state.commandNotice.isNotBlank()) {
                LaunchedEffect(state.commandNotice) { delay(6000); dismissCommandNotice() }
                Snackbar(Modifier.padding(12.dp).testTag("command-notice"),
                    action = { TextButton({ dismissCommandNotice() }, Modifier.testTag("command-notice-dismiss")) { Text("Dismiss") } }) { Text(state.commandNotice) }
            }
        },
        bottomBar = {
            if (!expandedCamera) NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                // Home/Control/Files/Prepare/Settings - reordered from an earlier draft that
                // happened to match Helix's own "Home, Files, Slice, Tools, Settings" nav
                // word-for-word; Control keeps this app's own pre-existing tab name (Tools folds
                // in the same content under Helix's naming), Prepare (a placeholder for the
                // not-yet-built slicing phase) reads broader than "Slice", and this last tab is
                // labeled Settings - its content is still printer management, "Printers" as a
                // label read as confusing next to a tab bar that's otherwise about what you do,
                // not what you're looking at.
                // Settings is shown last, but each tab keeps its own index (and nav-N test tag).
                listOf(Triple(0, "Home", CompanionSymbol.DASHBOARD), Triple(1, "Control", CompanionSymbol.CONTROL), Triple(2, "Files", CompanionSymbol.FILES),
                    Triple(3, "Prepare", CompanionSymbol.SLICE), Triple(5, "Discover", CompanionSymbol.DISCOVER), Triple(4, "Settings", CompanionSymbol.SETTINGS)).forEach { (index, title, symbol) ->
                    NavigationBarItem(modifier = Modifier.testTag("nav-$index"), selected = tab == index, onClick = { tab = index; if(index == 0) detailAddress = null }, icon = { CompanionIcon(symbol, color = if(tab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }, label = { Text(title) })
                }
            }
        }) { padding ->
        // On a tablet or unfolded screen the single column would stretch edge to edge; keep it a readable width, centred.
        val wideGutter = (((androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - MAX_CONTENT_WIDTH_DP).coerceAtLeast(0)) / 2 + 16).dp
        if (expandedCamera) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.Center) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Camera", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton({ expandedCamera = false }, Modifier.testTag("close-camera").semantics { contentDescription = "Close full screen camera" }) { CompanionIcon(CompanionSymbol.CLOSE) }
                }
                CameraContent(state)
            }
        } else if (tab == 5) {
            Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = wideGutter - 16.dp)) {
                DiscoverScreen(settings = remember { MmfSettings(context.applicationContext) }, dao = projectDb.projectDao(), signInRedirect = MmfRedirects.pending, onRedirectConsumed = { MmfRedirects.pending = null },
                    onStartSignIn = { url -> runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } },
                    onOpenProject = { id -> editingProjectId = id })
            }
        } else LazyColumn(state = listState, modifier = Modifier.testTag("screen-list").fillMaxSize().padding(padding).padding(horizontal = wideGutter), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            // The title block (app icon/name + tab subtitle) and its trailing Manage-printers
            // button were redundant everywhere: the bottom nav already labels every tab
            // (including a direct one-tap Settings entry), and a printer's detail view already
            // has two ways back (system back, via BackHandler above, and tapping Home again).
            if(!overview) item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.profiles.firstOrNull { it.address==state.address }?.label ?: state.address.ifBlank { "Add your first printer" }, style = MaterialTheme.typography.titleMedium)
                    Text(if(state.connected) "CONNECTED" else "OFFLINE", style = MaterialTheme.typography.labelMedium, color = if(state.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    if(state.kindFor(state.address).unverifiedOnRealHardware) Text("Not verified on real hardware yet - built against the vendor's own published protocol/reference engineering, but the owner has no matching printer to physically test against.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.testTag("unverified-hardware-detail"))
                    if(!state.connected || state.snapshot?.ready != true || tab == 4) Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if((tab == 0 && !overview) || tab == 4) item { TextButton({customize=true}, Modifier.testTag("customize-dashboard")) { Text("Customize dashboard") } }
            if(tab == 4) item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton({ selfCheckRunning = true; uiScope.launch { selfCheckResults = SelfCheck.run(context); selfCheckRunning = false } }, enabled = !selfCheckRunning, modifier = Modifier.testTag("run-self-check")) { Text(if(selfCheckRunning) "Checking…" else "Run self-check", maxLines = 1) }
                        OutlinedButton({ creditsOpen = true }, modifier = Modifier.testTag("open-credits")) { Text("About & credits", maxLines = 1) }
                        if(testModeEnabled) OutlinedButton({ testModeOpen = true }, modifier = Modifier.testTag("open-test-mode")) { Text("Test Mode", maxLines = 1) }
                        OutlinedButton({ backupStep = BackupStep.EXPORT_PASSPHRASE; backupMessage = null }, modifier = Modifier.testTag("backup-printers")) { Text("Back up printers", maxLines = 1) }
                        OutlinedButton({ restorePicker.launch(arrayOf("*/*")) }, modifier = Modifier.testTag("restore-printers")) { Text("Restore printers", maxLines = 1) }
                    }
                    selfCheckResults.forEachIndexed { i, r -> Text((if(r.ok) "PASS  " else "FAIL  ") + r.name + " - " + r.detail, style = MaterialTheme.typography.bodySmall, color = if(r.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error, modifier = Modifier.testTag("self-check-$i")) }
                }
            }
            if(backupStep != BackupStep.NONE) item {
                val exporting = backupStep == BackupStep.EXPORT_PASSPHRASE
                AlertDialog(onDismissRequest = { backupStep = BackupStep.NONE; backupPassphrase = "" }, modifier = Modifier.testTag("backup-dialog"),
                    title = { Text(if(exporting) "Back up printers" else "Restore printers") },
                    text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if(exporting) "The backup includes each printer's API key or access code, so it is encrypted with a passphrase you choose. Keep the passphrase: there is no way to recover a backup without it." else "Enter the passphrase used when the backup was made. Printers already saved here are left as they are.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(backupPassphrase, { backupPassphrase = it }, label = { Text("Passphrase") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.testTag("backup-passphrase"))
                    } },
                    confirmButton = { TextButton({
                        if(exporting) backupPicker.launch("nozzle-printers.nozzlebackup")
                        else { val uri = restoreUri; val pass = backupPassphrase.toCharArray(); backupPassphrase = ""; backupStep = BackupStep.NONE
                            if(uri != null) uiScope.launch {
                                backupMessage = try {
                                    val restored = withContext(Dispatchers.IO) { SettingsBackup.decode(context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }, pass) }
                                    val added = restored.count { addProfile(it) == null }
                                    "Restored $added printer(s)" + if(added < restored.size) ", skipped ${restored.size - added} already saved." else "."
                                } catch(e: BackupException) { e.message } catch(e: Exception) { "Could not read the backup: ${e.message}" }
                            }
                        }
                    }, enabled = backupPassphrase.length >= (if(exporting) SettingsBackup.MIN_PASSPHRASE else 1), modifier = Modifier.testTag("backup-continue")) { Text(if(exporting) "Choose where to save" else "Restore") } },
                    dismissButton = { TextButton({ backupStep = BackupStep.NONE; backupPassphrase = "" }) { Text("Cancel") } })
            }
            backupMessage?.let { msg -> if(tab == 4) item { Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("backup-message")) } }
            if(creditsOpen) item {
                AlertDialog(onDismissRequest = { creditsOpen = false }, title = { Text("About & credits") }, modifier = Modifier.testTag("credits-dialog"),
                    text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Models and images in Discover are provided by MyMiniFactory and the designers who publish them. The designer's credit is saved with every project you start from a model; please respect each model's license when you print, share or sell.", style = MaterialTheme.typography.bodySmall)
                        Text("Searches are sent to MyMiniFactory. Nothing else leaves your device.", style = MaterialTheme.typography.bodySmall)
                        Text("Open source", style = MaterialTheme.typography.titleSmall)
                        Text(OpenSourceNotice.statement, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("source-offer-statement"))
                        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                        OpenSourceNotice.links.forEachIndexed { i, (label, url) ->
                            TextButton({ runCatching { uriHandler.openUri(url) } }, modifier = Modifier.testTag("source-link-$i")) { Text(label, style = MaterialTheme.typography.bodySmall) }
                        }
                        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("about-version").clickable { tapVersion() })
                        testModeNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("test-mode-note")) }
                    } },
                    confirmButton = { TextButton({ creditsOpen = false }, modifier = Modifier.testTag("credits-close")) { Text("Close") } })
            }
            if(tab == 4) item { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(backgroundAlertsEnabled, {setBackgroundAlertsEnabled(!backgroundAlertsEnabled)}, label={Text("Background print alerts")}, modifier=Modifier.testTag("background-alerts-toggle"))
                Text("Notifies you when a saved printer finishes, errors or goes offline while the app isn't open. Shows a persistent low-priority notification while active.", style = MaterialTheme.typography.bodySmall)
                val alertPrefs = remember { context.getSharedPreferences("alerts", 0) }
                var extendedAlerts by remember { mutableStateOf(alertPrefs.getBoolean("extended", false)) }
                FilterChip(extendedAlerts, { extendedAlerts = !extendedAlerts; alertPrefs.edit().putBoolean("extended", extendedAlerts).apply() }, label={Text("Also: print started and nearly done", maxLines = 1)}, modifier=Modifier.testTag("extended-alerts-toggle"))
            } }
            // tab 3 (Prepare) always falls through to its own placeholder below, never this
            // printer-connect prompt, even before any printer is selected.
            if(tab == 4 || (state.address.isEmpty() && tab != 2 && tab != 3 && !overview)) {
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
                                Text(connection?.let { familyStateLabel(it.state, it.connected) }
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
                    // Replaced the old single-field "type an address, tap Connect" flow (owner
                    // request, 2026-09-22): that path created a bare, unconfigured profile
                    // (PrinterModel.connect's own fallback) with no name, kind, slicing profile
                    // or live verification - every one of those then needed a separate trip to
                    // Edit printer. AddPrinterWizard folds type/address/credentials, slicing
                    // profile, firmware confirmation and a real connectivity test into one guided
                    // flow, and is now the only way to add a printer.
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button({ addingPrinter = true }, modifier = Modifier.fillMaxWidth().testTag("open-add-printer-wizard"), enabled = !state.busy) { Text("Add printer") }
                        OutlinedButton(disconnect, enabled = !state.busy && state.address.isNotBlank()) { Text("Disconnect") }
                    }
                }
            } else when(tab) {
                0 -> {
                    if(overview) {
                        val tiles = state.connectedPrinterTiles()
                        item { Text("Connected printers", style = MaterialTheme.typography.titleLarge) }
                        if(tiles.isEmpty()) item {
                            Text("No printers connected. Saved printers reconnect while the app is open.")
                            OutlinedButton({ tab = 4 }) { Text("View saved printers") }
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
                        // The hero card from the redesign concept: a tinted gradient while
                        // printing (the same treatment PrinterTiles gives a printing tile in the
                        // Home list), flat otherwise, so "something is happening" reads at a glance.
                        val printing = state.snapshot?.state in setOf("printing", "paused")
                        val heroShape = RoundedCornerShape(24.dp)
                        val heroDot = when { state.snapshot?.state in setOf("error", "not ready") -> MaterialTheme.colorScheme.error; printing -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant }
                        Box(Modifier.fillMaxWidth().clip(heroShape)
                            .background(if(printing) Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), MaterialTheme.colorScheme.surface)) else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface)))
                            .then(if(printing) Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), heroShape) else Modifier)) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Box(Modifier.size(8.dp).background(heroDot, CircleShape))
                                        Text(familyStateLabel(state.snapshot?.state ?: "awaiting printer").uppercase(), style = MaterialTheme.typography.labelMedium, color = heroDot)
                                    }
                                    val remaining = estimatedRemaining(state.snapshot,state.activeMetadata)
                                    if(remaining!=null) Text("${formatDuration(remaining)} left" + (estimatedFinishClockTime(remaining)?.let { " · Done at $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = PlexMono)
                                }
                                Text(state.snapshot?.activeFilename?.ifBlank { "No active file" } ?: "Connect to see print status", style = MaterialTheme.typography.titleMedium)
                                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(state.snapshot?.let { "${(it.activeProgress*100).toInt()}%" } ?: "—", style = MaterialTheme.typography.displaySmall, fontFamily = PlexMono, color = MaterialTheme.colorScheme.primary)
                                    Text("layer ${state.snapshot?.currentLayer ?: "?"} / ${state.snapshot?.totalLayers ?: state.activeMetadata?.layers ?: "?"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                                }
                                LinearProgressIndicator(progress = { state.snapshot?.activeProgress ?: 0f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
                                Text("Elapsed ${formatDuration(state.snapshot?.printDuration)}" + (estimatedRemaining(state.snapshot,state.activeMetadata)?.let { " · Remaining time is a slicer-based estimate." } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                // BambuPrinterService's command() only carries print requests (no
                                // pause/resume/cancel); PrusaLinkPrinterService's does support all
                                // three, same as Moonraker - hence capabilities.supportsPauseResumeCancel,
                                // not a printer-kind check.
                                if(capabilities.supportsPauseResumeCancel) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            OutlinedButton({ tab = 4 }) { Text("Printers") }
                        }
                    }
                        }
                    }
                }
                1 -> {
                    // Redesigned 2026-09-22 after a council-of-reviewers consultation (owner
                    // request) unanimously flagged the previous version - 13+ visually identical
                    // full-width OutlinedButton pills stacked with no grouping - as a real
                    // "one long page of pills" usability problem. Every existing testTag below
                    // is unchanged; only layout/shape/grouping changed.
                    //
                    // Emergency Stop moved first (owner: "easily accessible", not buried after a
                    // scroll) and reshaped into an actual stop-sign octagon (owner's own idea,
                    // addressing the one point the reviewers disagreed on - council-design argued
                    // color alone wasn't enough shape differentiation from ordinary controls).
                    // "Can't accidentally tap it" now comes from three independent things, not
                    // position-in-scroll: a shape found nowhere else in this app, generous
                    // isolating padding, and the pre-existing confirm dialog (estopConfirm) that
                    // still gates the actual command - a stray tap here was never one tap away
                    // from actually estopping, and still isn't.
                    if(LIVE_HEATER_FAN_CONTROLS_ENABLED && capabilities.supportsKlipperExtras) item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                            Button({estopConfirm=true}, enabled=state.connected,
                                modifier=Modifier.size(140.dp).testTag("emergency-stop"),
                                shape = StopOctagonShape, contentPadding = PaddingValues(0.dp),
                                border = BorderStroke(4.dp, MaterialTheme.colorScheme.onError),
                                colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error, contentColor=MaterialTheme.colorScheme.onError)) {
                                Text("STOP", style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Bold, textAlign=TextAlign.Center,
                                    modifier=Modifier.semantics { contentDescription = "Emergency stop" })
                            }
                        }
                    }
                    if(LIVE_HEATER_FAN_CONTROLS_ENABLED && capabilities.supportsKlipperExtras) item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Hardware controls", style=MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton({heaterOpen=true},enabled=state.connected && state.snapshot?.ready==true,modifier=Modifier.testTag("open-heaters")){Text("Heater controls")}
                                FilledTonalButton({fanOpen=true},enabled=state.connected && state.snapshot?.ready==true,modifier=Modifier.testTag("open-fans")){Text("Fan controls")}
                                FilledTonalButton({ledOpen=true},enabled=state.connected && state.snapshot?.ready==true,modifier=Modifier.testTag("open-leds")){Text("Light controls")}
                                FilledTonalButton({toolOpen=true},enabled=state.connected && state.snapshot?.ready==true,modifier=Modifier.testTag("open-tools")){Text("Tool controls")}
                                FilledTonalButton({speedFlowOpen=true},enabled=state.connected && state.snapshot?.ready==true,modifier=Modifier.testTag("open-speedflow")){Text("Speed / flow")}
                                // Phase 7 (§16): real relative-move jog + homing - see JogPanel.kt
                                // for why this is gated on an idle printer inside the panel
                                // itself rather than here (the panel needs to react live to the
                                // printer going busy mid-session, not just at open time).
                                if(capabilities.supportsJog) FilledTonalButton({jogOpen=true},enabled=state.connected,modifier=Modifier.testTag("open-jog")){Text("Jog controls")}
                                // Feature-detected, not gated to a printer kind: shows "not
                                // detected" rather than being hidden, matching Helix's own
                                // honest-empty-state panel.
                                FilledTonalButton({pandaBreathOpen=true},enabled=state.connected && state.snapshot?.ready==true,modifier=Modifier.testTag("open-panda")){Text("Panda Breath (chamber/dryer)")}
                                if(capabilities.hasBespok3d) FilledTonalButton({bespok3dOpen=true},enabled=state.connected,modifier=Modifier.testTag("open-bespok3d")){Text("Bespok3d / remote screen")}
                                // PAXX-specific hardware (unlike Panda Breath/Spoolman above,
                                // which are generic-Klipper feature-detected) - gated to printer
                                // capability, not owned by the owner, built at their request for
                                // other PAXX owners.
                                if(capabilities.hasMultiAce) FilledTonalButton({aceOpen=true},enabled=state.connected && state.snapshot?.ready==true,modifier=Modifier.testTag("open-ace")){Text("multiACE")}
                            }
                        }
                    }
                    // Phase 7 (§16): real, but only as real as the printer's own config - Klipper
                    // ships no built-in filament load/unload command, so this is gated on the
                    // printer's own live macro catalog actually defining one of these common
                    // names (LOAD_FILAMENT/UNLOAD_FILAMENT, the near-universal Klipper macro
                    // convention; M701/M702, the Marlin-style gcode some configs alias) - not
                    // shown at all otherwise (§20, no dead buttons). Reuses the exact same real
                    // macro-run pipeline (MacroForm -> MacroReviewPanel -> execute) "Favorite
                    // macros" below already uses, rather than inventing a second command path.
                    if(LIVE_HEATER_FAN_CONTROLS_ENABLED && capabilities.supportsFilamentLoadUnload) {
                        val loadNames = setOf("LOAD_FILAMENT", "M701"); val unloadNames = setOf("UNLOAD_FILAMENT", "M702")
                        val load = state.catalog.macros.firstOrNull { it.uppercase() in loadNames }
                        val unload = state.catalog.macros.firstOrNull { it.uppercase() in unloadNames }
                        if(load != null || unload != null) item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Filament", style=MaterialTheme.typography.titleMedium)
                                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    load?.let { name -> FilledTonalButton({preparingMacro=name},enabled=state.connected && state.snapshot?.ready==true,modifier=Modifier.testTag("open-load-filament")){Text("Load filament")} }
                                    unload?.let { name -> FilledTonalButton({preparingMacro=name},enabled=state.connected && state.snapshot?.ready==true,modifier=Modifier.testTag("open-unload-filament")){Text("Unload filament")} }
                                }
                            }
                        }
                    }
                    if(capabilities.supportsKlipperExtras) item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Diagnostics & status", style=MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                OutlinedButton({meshOpen=true},enabled=state.connected,modifier=Modifier.testTag("open-mesh")){Text("Bed mesh")}
                                OutlinedButton({toolheadsOpen=true},enabled=state.connected,modifier=Modifier.testTag("open-toolheads")){Text("Toolhead temperatures")}
                                // Feature-detected like Spoolman: a filament changer's lanes (CANVAS on COSMOS, Box Turtle, Happy Hare).
                                OutlinedButton({filamentSlotsOpen=true},enabled=state.connected,modifier=Modifier.testTag("open-filament-slots")){Text("Filament slots")}
                                OutlinedButton({fanStatusOpen=true},enabled=state.connected,modifier=Modifier.testTag("open-fanstatus")){Text("Fan status")}
                                OutlinedButton({configOpen=true},enabled=state.connected,modifier=Modifier.testTag("open-config")){Text("Configuration")}
                                OutlinedButton({timelapseOpen=true},enabled=state.connected,modifier=Modifier.testTag("open-timelapse")){Text("Timelapses")}
                                // Feature-detected (honest "not found" rather than hidden) like
                                // Panda Breath above - not gated to the owner actually running
                                // Spoolman.
                                OutlinedButton({spoolmanOpen=true},enabled=state.connected,modifier=Modifier.testTag("open-spoolman")){Text("Spoolman")}
                                OutlinedButton({consoleOpen=true},enabled=state.connected,modifier=Modifier.testTag("open-console")){Text(if(LIVE_HEATER_FAN_CONTROLS_ENABLED) "Console" else "Read-only console")}
                                OutlinedButton({controlPreview=true},modifier=Modifier.testTag("advanced-control-preview")) {Text("Preview advanced controls")}
                                // The full macro list lives behind this now (owner request,
                                // 2026-09-22) - see MacrosBrowserPanel. This tab only shows
                                // whichever macros have been favorited there.
                                OutlinedButton({macrosOpen=true},modifier=Modifier.testTag("open-macros")) {Text("Advanced macros")}
                            }
                        }
                    }
                    if(capabilities.transport == PrinterTransport.BAMBU_MQTT) item { KilnFrame { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("LAN mode limitations", style=MaterialTheme.typography.titleSmall)
                        Text("A Bambu Lab printer in LAN mode exposes no macros, console or configuration; its temperatures, fans and lights are not remotely controllable over this protocol.")
                        // Read-only: the AMS trays from the printer's status report (BambuAmsTrays).
                        OutlinedButton({filamentSlotsOpen=true},enabled=state.connected,modifier=Modifier.testTag("open-filament-slots")){Text("AMS slots")}
                    } } }
                    else if(capabilities.transport == PrinterTransport.OCTOPRINT) item { KilnFrame { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("OctoPrint limitations", style=MaterialTheme.typography.titleSmall)
                        Text("Here an OctoPrint printer offers live status, temperatures (read-only), starting a print from a sliced file, and pause, resume and cancel. Macros, console, configuration, camera and file previews are not available for this printer kind.")
                    } } }
                    else if(capabilities.transport == PrinterTransport.ELEGOO) item { KilnFrame { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Elegoo printer", style=MaterialTheme.typography.titleSmall)
                        Text("Here an Elegoo Centauri Carbon or Centauri Carbon 2 offers live status and temperatures (read-only), its CANVAS slots, sending and starting a sliced file, and cancel. Pause and resume are offered on the Centauri Carbon only: the Centauri Carbon 2's network protocol has no resume. Macros, console, configuration, camera and file previews are not available for this printer kind.")
                        OutlinedButton({filamentSlotsOpen=true},enabled=state.connected,modifier=Modifier.testTag("open-filament-slots")){Text("CANVAS slots")}
                    } } }
                    else if(capabilities.transport == PrinterTransport.CREALITY || capabilities.transport == PrinterTransport.FLASHFORGE) item { KilnFrame { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val creality = capabilities.transport == PrinterTransport.CREALITY
                        Text(if(creality) "Creality printer" else "Flashforge printer", style=MaterialTheme.typography.titleSmall)
                        Text("Here a ${if(creality) "Creality K1, K2 or Hi" else "Flashforge AD5X or Adventurer 5M"} offers live status and temperatures (read-only), its ${if(creality) "CFS" else "IFS"} slots, and uploading a sliced file. Start the print on the printer's screen: starting, pausing and cancelling from Nozzle It All aren't verified on a real printer yet. Macros, console, configuration, camera and file previews are not available for this printer kind.")
                        if(!creality) Text("An older Flashforge saved without a serial number and access code (Adventurer 3 / 4, Creator, Guider) uses its port-8899 connection instead: Nozzle It All only checks it answers, and doesn't read its state yet.")
                        OutlinedButton({filamentSlotsOpen=true},enabled=state.connected,modifier=Modifier.testTag("open-filament-slots")){Text(if(creality) "CFS slots" else "IFS slots")}
                    } } }
                    else if(capabilities.transport == PrinterTransport.DUET) item { KilnFrame { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Duet printer", style=MaterialTheme.typography.titleSmall)
                        Text("Here a Duet on RepRapFirmware offers a connection check and uploading a sliced file to 0:/gcodes. Nozzle It All doesn't read its state, temperatures or progress yet (the slicer code this connection is ported from doesn't). Start the print on the printer's screen or in Duet Web Control: starting, pausing and cancelling from Nozzle It All aren't verified on a real printer yet.")
                    } } }
                    else if(capabilities.transport == PrinterTransport.ULTIMAKER) item { KilnFrame { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("UltiMaker printer", style=MaterialTheme.typography.titleSmall)
                        Text("Here a networked UltiMaker offers its state and job progress (read-only; no temperatures over this connection). Sending, pausing and aborting prints from Nozzle It All aren't verified on a real printer yet, and an UltiMaker prints every job it is sent, so nothing is sent: print from the printer's screen. Pair it in Edit printer.")
                    } } }
                    else if(capabilities.transport == PrinterTransport.REPETIER) item { KilnFrame { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Repetier-Server printer", style=MaterialTheme.typography.titleSmall)
                        Text("Here a printer behind Repetier-Server offers a connection check and uploading a sliced file to the server's model library (which doesn't print it). Nozzle It All doesn't read its state, temperatures or progress yet (the slicer code this connection is ported from doesn't). Start the print from Repetier-Server or the printer's screen: starting, pausing and stopping from Nozzle It All aren't verified on a real printer yet.")
                    } } }
                    else if(capabilities.transport == PrinterTransport.ANYCUBIC_LAN) item { KilnFrame { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Anycubic printer", style=MaterialTheme.typography.titleSmall)
                        Text("Here an Anycubic Kobra 3, Kobra S1 or Kobra X in LAN mode offers live status and temperatures (read-only), its ACE slots, and uploading a sliced file. Start the print on the printer's screen: starting, pausing and cancelling, temperatures, homing and the ACE's feed and dryer from Nozzle It All aren't verified on a real printer yet. Macros, console, configuration, camera and file previews are not available for this printer kind.")
                        OutlinedButton({filamentSlotsOpen=true},enabled=state.connected,modifier=Modifier.testTag("open-filament-slots")){Text("ACE slots")}
                    } } }
                    else if(capabilities.transport == PrinterTransport.SNAPMAKER_SSTP || capabilities.transport == PrinterTransport.SNAPMAKER_SACP) item { KilnFrame { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val sacp = capabilities.transport == PrinterTransport.SNAPMAKER_SACP
                        Text(if(sacp) "Snapmaker J1 / Artisan" else "Snapmaker 2.0", style=MaterialTheme.typography.titleSmall)
                        Text("Here a ${if(sacp) "Snapmaker J1 or Artisan" else "Snapmaker 2.0 with a 3D printing module"} offers ${if(sacp) "its temperatures and job progress" else "its state, temperatures and job progress"} (read-only) and uploading a sliced file. Start the print on the printer's screen: starting, pausing and stopping, temperatures, nozzle switching and homing from Nozzle It All aren't verified on a real printer yet. Laser and CNC work isn't offered. Macros, console, configuration, camera and file previews are not available for this printer kind. Tap Connect in Edit printer and accept on the printer's screen first.")
                    } } }
                    else if(capabilities.transport == PrinterTransport.PRUSA_LINK) item { KilnFrame { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("PrusaLink API limitations", style=MaterialTheme.typography.titleSmall)
                        Text("A Prusa Link printer exposes no macros, console or configuration over this API; its temperatures are read-only here and file browsing is the top-level folder only.")
                    } } }
                    else {
                        // Owner request, 2026-09-22: the Control tab should show the operations
                        // someone needs to operate their printer - dedicated controls above, not
                        // a raw dump of whatever arbitrary macros the printer's config happens to
                        // define. Only macros explicitly favorited in MacrosBrowserPanel show
                        // here, and the whole section is omitted (not an empty placeholder) when
                        // there are none - no guessing at which macro names are "essential".
                        val favoriteMacros=state.catalog.macros.filter {macroOptions[it]?.favorite==true}.sorted()
                        if(favoriteMacros.isNotEmpty()) item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Favorite macros", style=MaterialTheme.typography.titleMedium)
                                favoriteMacros.forEach { macro -> Card(Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(macro, style=MaterialTheme.typography.titleMedium, modifier=Modifier.weight(1f))
                                        if(LIVE_HEATER_FAN_CONTROLS_ENABLED) Button({preparingMacro=macro}){Text("Run")}
                                    }
                                } }
                            }
                        }
                    }
                }
                2 -> {
                    // Bambu keeps its print history on its own screen and in Bambu Studio, and
                    // Prusa Link's API has no history endpoint at all; this app can only ask
                    // Moonraker for one, so the toggle is hidden rather than left empty for either.
                    item {
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            FilterChip(!showHistory && !showProjects,{showHistory=false;showProjects=false},label={Text("Files")})
                            if(capabilities.supportsKlipperExtras) FilterChip(showHistory,{showHistory=true;showProjects=false;loadHistory(0)},label={Text("History")},modifier=Modifier.testTag("show-history"))
                            // WO-17 (Phase 1): saved multi-object build plates, independent of
                            // any printer connection - unlike Files/History above, this never
                            // needs `state.connected` since it's purely local storage.
                            FilterChip(showProjects,{showHistory=false;showProjects=true},label={Text("Projects")},modifier=Modifier.testTag("show-projects"))
                        }
                    }
                    if(showProjects) {
                        item {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button({ newProjectNameDraft = "" }, modifier = Modifier.testTag("new-project")) { Text("New project", maxLines = 1) }
                                OutlinedButton({ importProject.launch(arrayOf("*/*")) }, modifier = Modifier.testTag("import-project")) { Text("Import project", maxLines = 1) }
                                OutlinedButton({ calibrationDialog = true }, modifier = Modifier.testTag("new-calibration")) { Text("Calibration", maxLines = 1) }
                            }
                            importMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("import-project-message")) }
                        }
                        if(projects.isEmpty()) item { Text("No saved projects yet.", style = MaterialTheme.typography.bodySmall) }
                        items(projects, key = { "project:${it.id}" }) { p ->
                            Card(Modifier.fillMaxWidth().testTag("project:${p.id}"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    TextButton({ renamingProjectId = p.id; renameDraft = p.name }, modifier = Modifier.testTag("rename-project:${p.id}")) { Text("Rename") }
                                    TextButton({ deletingProjectId = p.id }, modifier = Modifier.testTag("delete-project:${p.id}")) { Text("Delete") }
                                    TextButton({ editingProjectId = p.id }) { Text("Open") }
                                }
                            }
                        }
                    } else if(showHistory && capabilities.supportsKlipperExtras) {
                        item { HistoryHeader(state,loadHistory) }
                        items(state.history, key={"job:${it.id}"}) { job ->
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                Text(job.filename,style=MaterialTheme.typography.titleSmall)
                                Text("${job.status} · ${formatDuration(job.duration)} · ${formatMaterial(job.filamentMm)}")
                                job.started?.takeIf { it < 253402300799.0 }?.let { Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date((it*1000).toLong()))) }
                                val again = Reprint.resolve(job.filename, state.catalog.files)
                                if(again != null) OutlinedButton({pending=Moonraker.start(again, state.kindFor(state.address)) to state.generation},enabled=enabled&&state.snapshot?.state in setOf("standby","complete","cancelled","error"),modifier=Modifier.testTag("reprint:${job.id}")){Text("Reprint",maxLines=1)}
                                else Text("File no longer on the printer",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    OutlinedButton({workspace.download(state.address,file,state.apiKeyFor(state.address));uiScope.launch {listState.scrollToItem(0)}},enabled=state.connected&&!workspace.loading){Text("Download / preview")}
                                    OutlinedButton({selectFile(file);uiScope.launch {listState.scrollToItem(0)}},enabled=state.connected,modifier=Modifier.testTag("details:$file")){Text("Details")}
                                    OutlinedButton({pending=Moonraker.start(file, state.kindFor(state.address)) to state.generation},enabled=enabled&&state.snapshot?.state in setOf("standby","complete","cancelled","error")){Text("Start print")}
                                }
                            } }
                        }
                    }
                }
                3 -> {
                    // WO-30: the LaunchedEffect(tab) above opens the real editor
                    // (ProjectEditorScreen, a full-screen overlay below) the moment this tab
                    // becomes active - this is only the one-frame gap before that overlay
                    // appears, not a real landing page.
                    item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator() }
                    }
                }
            }
            items(state.catalog.warnings) { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
            // The old "LOCAL NETWORK · ANDROID · 0.1.0" footer repeated on every tab; moved here,
            // Settings-only, and expanded into real diagnostics instead of one static line.
            if(tab == 4) item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
                    Text("Nozzle It All ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("diagnostics-version").clickable { tapVersion() })
                    Text("Android ${Build.VERSION.RELEASE} · ${Build.MODEL}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Local network only — no cloud account, no telemetry.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    // A Bambu printer has no local workspace worth importing into and no Moonraker to upload to;
    // a share aimed at one is a print request instead. A model file (STL/3MF/OBJ, WO-13) is a
    // slice-and-print request regardless of printer kind (Bambu included, though that path
    // currently ends in an honest "not supported yet" - see SliceAndPrintPanel.kt). Every other
    // shared file keeps the plain import dialog.
    sharedFile?.let {uri ->
        val sharedName = remember(uri) { runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if(it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() }
        when {
            sliceableModelName(sharedName).isNotEmpty() -> SliceAndPrintPanel(uri,state,execute,consumeShare)
            capabilities.supportsNativePrintFileFlow -> BambuPrintPanel(uri,state,execute,consumeShare)
            else -> AlertDialog(onDismissRequest=consumeShare,title={Text("Import shared G-code?")},text={Text("Copy this document into the local workspace. It will not be uploaded or printed.")},confirmButton={TextButton({workspace.import(uri);tab=2;consumeShare()}){Text("Import")}},dismissButton={TextButton(consumeShare){Text("Cancel")}})
        }
    }
    // WO-17 (Phase 1): the Files tab's "Projects" section above stages a name here before the
    // real ProjectViewModel.newProject() call happens inside ProjectEditorScreen itself (kept
    // there, not here, so the create-and-persist step and the editor that immediately follows it
    // share one real code path rather than two).
    if(interruptedSliceNotice) {
        AlertDialog(onDismissRequest = { interruptedSliceNotice = false }, title = { Text("Slicing was interrupted") },
            text = { Text("The last slice didn't finish - Android most likely stopped the app to free memory. Your projects are saved. Try fewer or smaller objects, a larger layer height, or close other apps.", modifier = Modifier.testTag("interrupted-slice-text")) },
            confirmButton = { TextButton({ interruptedSliceNotice = false }, modifier = Modifier.testTag("interrupted-slice-ok")) { Text("OK") } })
    }
    if(calibrationDialog) {
        val spec = if(calibrationKind == CalibrationKind.TEMPERATURE) {
            val start = calStart.trim().toIntOrNull(); val step = calStep.trim().toIntOrNull(); val sections = calSections.trim().toIntOrNull()
            if(start != null && step != null && sections != null) CalibrationSpec(calibrationKind, start, step, sections).takeIf { it.valid() } else null
        } else CalibrationSpec(calibrationKind)
        AlertDialog(onDismissRequest = { calibrationDialog = false }, title = { Text("Calibration print") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CalibrationKind.entries.forEach { k -> FilterChip(calibrationKind == k, { calibrationKind = k }, label = { Text(k.label) }, modifier = Modifier.testTag("calibration-kind-${k.code}")) }
                    }
                    Text(calibrationKind.help, style = MaterialTheme.typography.bodySmall)
                    if(calibrationKind == CalibrationKind.TEMPERATURE) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(calStart, { calStart = it }, label = { Text("Start °C") }, singleLine = true, modifier = Modifier.weight(1f).testTag("calibration-start"))
                            OutlinedTextField(calStep, { calStep = it }, label = { Text("Step °C") }, singleLine = true, modifier = Modifier.weight(1f).testTag("calibration-step"))
                            OutlinedTextField(calSections, { calSections = it }, label = { Text("Sections") }, singleLine = true, modifier = Modifier.weight(1f).testTag("calibration-sections"))
                        }
                        if(spec == null) Text("Every section must be between 150 and 320 °C (2-12 sections).", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton({
                val chosen = spec ?: return@TextButton
                calibrationDialog = false
                uiScope.launch {
                    try {
                        val created = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            net.jamesjennison.klippercompanion.project.ProjectViewModel(context.applicationContext, projectDb.projectDao()).newCalibrationProject(chosen)
                        }
                        editingProjectId = created.id
                    } catch (e: Exception) { importMessage = "Could not create the calibration project: ${e.message}" }
                }
            }, enabled = spec != null, modifier = Modifier.testTag("calibration-create")) { Text("Create") } },
            dismissButton = { TextButton({ calibrationDialog = false }) { Text("Cancel") } })
    }
    newProjectNameDraft?.let { draft ->
        AlertDialog(onDismissRequest = { newProjectNameDraft = null }, title = { Text("New project") },
            text = { OutlinedTextField(draft, { newProjectNameDraft = it }, label = { Text("Project name") }, singleLine = true, modifier = Modifier.testTag("new-project-name")) },
            confirmButton = { TextButton({ if(draft.isNotBlank()) { editingNewProjectName = draft; newProjectNameDraft = null } }, enabled = draft.isNotBlank(), modifier = Modifier.testTag("new-project-confirm")) { Text("Create") } },
            dismissButton = { TextButton({ newProjectNameDraft = null }) { Text("Cancel") } })
    }
    if(editingProjectId != null || editingNewProjectName != null) {
        ProjectEditorScreen(editingProjectId, editingNewProjectName, state, execute) {
            editingProjectId = null; editingNewProjectName = null
            // WO-30: closing the Prepare tab's own auto-opened editor returns to Home rather than
            // this tab's LaunchedEffect(tab) immediately spawning another fresh project the instant
            // this one closes - Home is a real destination; re-entering Prepare later starts a new
            // print same as today. Closing a project opened from Files > Projects (tab == 2) still
            // just drops back to that same list, unchanged.
            if(tab == 3) tab = 0
        }
    }
    renamingProjectId?.let { id ->
        AlertDialog(onDismissRequest = { renamingProjectId = null }, title = { Text("Rename project") },
            text = { OutlinedTextField(renameDraft, { renameDraft = it }, label = { Text("Project name") }, singleLine = true, modifier = Modifier.testTag("rename-project-name")) },
            confirmButton = { TextButton({
                val name = renameDraft
                if(name.isNotBlank()) uiScope.launch {
                    val vm = net.jamesjennison.klippercompanion.project.ProjectViewModel(context.applicationContext, projectDb.projectDao())
                    if(vm.loadProject(id)) vm.renameProject(name)
                }
                renamingProjectId = null
            }, enabled = renameDraft.isNotBlank(), modifier = Modifier.testTag("rename-project-confirm")) { Text("Rename") } },
            dismissButton = { TextButton({ renamingProjectId = null }) { Text("Cancel") } })
    }
    deletingProjectId?.let { id ->
        val name = projects.find { it.id == id }?.name ?: "this project"
        AlertDialog(onDismissRequest = { deletingProjectId = null }, title = { Text("Delete \"$name\"?") },
            text = { Text("This removes the project and every object on its build plate. This can't be undone.") },
            confirmButton = { TextButton({
                uiScope.launch {
                    val vm = net.jamesjennison.klippercompanion.project.ProjectViewModel(context.applicationContext, projectDb.projectDao())
                    if(vm.loadProject(id)) vm.deleteProject()
                }
                deletingProjectId = null
            }, modifier = Modifier.testTag("delete-project-confirm")) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ deletingProjectId = null }) { Text("Cancel") } })
    }
    editingMacro?.let {name->MacroEditor(name,macroOptions[name]?:MacroOptions(),{editingMacro=null}){saveMacro(name,it)}}
    preparingMacro?.let {name->MacroForm(name,macroOptions[name]?:MacroOptions(),{preparingMacro=null}){preparingMacro=null;runningMacro=it}}
    runningMacro?.let {command->MacroReviewPanel(command,state,execute,{runningMacro=null})}
    editingProfile?.let { ProfileEditor(it,{editingProfile=null},updateProfile,detectFirmware,setCustomMachine,detectLanes) }
    if(addingPrinter) AddPrinterWizard(state.savedPrinters, addProfile, ::openPrinter) { addingPrinter = false }
    pending?.let { (command, epoch) ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text(command.title + "?") },
            text = { Column { Text("This sends a command to ${state.address}. It may move or heat your printer. Confirm only when the printer is safe and ready.");command.arguments["script"]?.let {Text("Command: $it")};if(command.allowedStates.isNotEmpty() && state.snapshot?.state !in command.allowedStates)Text("Unavailable in the current print state.") } },
            confirmButton = { Button({ pending = null; execute(command, epoch) }, enabled = enabled && (command.allowedStates.isEmpty() || state.snapshot?.state in command.allowedStates)) { Text("Confirm") } },
            dismissButton = { TextButton({ pending = null }) { Text("Go back") } })
    }
    if(testModeOpen) net.jamesjennison.klippercompanion.testgrid.TestModeScreen(state.profiles) { testModeOpen = false }
    // Separate from the pending?.let dialog above: that one disables Confirm once the printer's
    // state drifts from what was reviewed, which is exactly backwards for an emergency stop.
    if(estopConfirm) AlertDialog(onDismissRequest = { estopConfirm = false }, title = { Text("Emergency stop?") },
        text = { Text("Halts the printer immediately and cancels any running print. The firmware needs a restart afterwards.") },
        confirmButton = { Button({ estopConfirm = false; emergencyStop() }, modifier=Modifier.testTag("confirm-emergency-stop"),
            colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error, contentColor=MaterialTheme.colorScheme.onError)) { Text("Stop the printer") } },
        dismissButton = { TextButton({ estopConfirm = false }) { Text("Cancel") } })
}
@Composable private fun Temperature(label: String, actual: Double?, target: Double?, modifier: Modifier, isNozzle:Boolean=false) {
    // Ember for the nozzle, teal for the bed - the same duotone the redesign concept uses
    // everywhere else heat is shown, replacing the old hardcoded one-off hex colors.
    val accent = if(isNozzle) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val fraction = if(target != null && target > 0 && actual != null) (actual / target).toFloat().coerceIn(0f, 1f) else 0f
    Card(modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CompanionIcon(if(isNozzle) CompanionSymbol.NOZZLE else CompanionSymbol.BED, Modifier.size(16.dp), color = accent)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(actual?.let { String.format(Locale.US, "%.1f°", it) } ?: "—", style = MaterialTheme.typography.headlineSmall, fontFamily = PlexMono, color = accent)
        Text(target?.let { "Target ${it.toInt()}°C" } ?: "No reading", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), color = accent, trackColor = accent.copy(alpha = 0.15f))
    } }
}

@Composable private fun CameraContent(state: ScreenState, visible: Boolean = true) {
    if(!visible) { Spacer(Modifier.fillMaxWidth().aspectRatio(16f/9f));return }
    val camera = state.selectedCamera()
    if(state.connected && camera != null && camera.stream.isNotBlank()) {
        key(state.generation, state.cameraGeneration, state.address, camera) {
            // A Bambu chamber camera is re-served on loopback by this app, so it renders against its
            // own origin rather than the printer's host; Moonraker cameras leave address blank.
            val origin = camera.address.ifBlank { state.address }
            when(camera.service) {
                "webrtc-camerastreamer" -> LiveCamera(origin,camera)
                "mjpegstreamer", "mjpegstreamer-adaptive", "bambu-chamber" -> MjpegCamera(origin,camera)
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

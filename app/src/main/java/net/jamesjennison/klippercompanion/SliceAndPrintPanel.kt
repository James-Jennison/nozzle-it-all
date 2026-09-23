package net.jamesjennison.klippercompanion

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.orcaslicer.engine.NativeEngine
import java.io.File

/**
 * WO-13/WO-14: a full-screen, EasyPrint-inspired visual slicer (owner request, 2026-09-22 - "a
 * visual, on-device, in-app slicer", real PrusaSlicer EasyPrint screenshots as the reference).
 * Shares a model file (STL/3MF/OBJ) in, shows it in a real rotatable 3D view (ModelViewer.kt)
 * alongside a small, deliberately bounded settings surface (SliceCustomization.kt - not
 * OrcaSlicer's full surface), slices it on-device, shows the real sliced toolpath in 3D
 * (SlicedPreview.kt) with real stats (GcodeStats.kt), confirms the printer/bed is ready, then
 * stages the resulting G-code into the same review-then-confirm machinery every other mutating
 * command already uses - nothing prints without that final, explicit tap.
 *
 * Deliberately targets the currently selected printer (state.address), the same convention
 * BambuPrintPanel already uses for its own share-intent flow, rather than adding a separate
 * printer-picker UI - the "Printer" tab below is a real summary of that choice, not a picker.
 */
@Composable fun SliceAndPrintPanel(uri: Uri, state: ScreenState, execute: (PrinterCommand, Int)->Unit, close: ()->Unit) {
    val context = LocalContext.current
    val name = remember(uri) { sliceableModelName(runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if(it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()) }
    val profile = remember(state.address, state.profiles) { state.profiles.find { it.address == state.address } }
    var stage by remember(uri) { mutableStateOf("") }
    var working by remember(uri) { mutableStateOf(false) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    var sliced by remember(uri) { mutableStateOf<File?>(null) }
    var stagedFilename by remember(uri) { mutableStateOf<String?>(null) }
    // Customization is reviewed before anything runs - see SliceCustomization.kt for exactly
    // what this covers and why it's this specific short list, not OrcaSlicer's full settings.
    var customizing by remember(uri) { mutableStateOf(true) }
    var tab by remember(uri) { mutableIntStateOf(0) }
    var layerHeightText by remember(uri) { mutableStateOf(DEFAULT_SLICE_CUSTOMIZATION.layerHeightMm.toString()) }
    var infillText by remember(uri) { mutableStateOf(DEFAULT_SLICE_CUSTOMIZATION.infillPercent.toString()) }
    var supportsEnabled by remember(uri) { mutableStateOf(DEFAULT_SLICE_CUSTOMIZATION.supportsEnabled) }
    var customizeError by remember(uri) { mutableStateOf<String?>(null) }
    var customization by remember(uri) { mutableStateOf<SliceCustomization?>(null) }
    // Copied as soon as the panel opens (not inside the slicing effect) - both the pre-slice 3D
    // preview and the eventual slice call need the same local file.
    var localModel by remember(uri) { mutableStateOf<File?>(null) }
    var copyError by remember(uri) { mutableStateOf<String?>(null) }
    // Post-slice toolpath review (owner request: "a visual, on-device, in-app slicer" -
    // PrusaSlicer's EasyPrint as the reference point). Reuses GcodePreview.parse() verbatim -
    // the same real parser already used for Bambu-shared-file preview and live print tracking -
    // rendered in 3D (SlicedPreview.kt) instead of the flat 2D LayerPreview used elsewhere.
    var reviewedLayers by remember(uri) { mutableStateOf(false) }
    var slicedToolpath by remember(uri) { mutableStateOf<Toolpath?>(null) }
    var toolpathError by remember(uri) { mutableStateOf<String?>(null) }
    // WO-14 part D: real support painting on the Model tab. Owned here (not inside ModelViewer)
    // so the slicing effect below can see whether painting actually happened - see
    // SlicingCoordinator.slice()'s own paintSessionHandle parameter.
    val paintState = remember(uri) { PaintUiState() }
    // WO-15 part E: real object placement (move/rotate/scale) set on the Model tab. Owned here
    // for the same reason paintState is - the slicing effect below needs to read it, and it must
    // survive ModelViewer unmounting when the flow moves past "customizing".
    val transformState = remember(uri) { ModelTransformUiState() }
    // WO-15 part E follow-up: the real bed size/shape for whichever printer is targeted, read
    // straight from the same machine.json every slice already applies (SlicingProfilePacks.kt) -
    // not an invented bed size. CosmosProfileGeneration.CURRENT is used unconditionally here (not
    // the live-firmware-confirmed value SlicingCoordinator.slice() itself requires) because only
    // that generation has a bundled pack at all right now, and the bed *shape* doesn't differ by
    // firmware generation for the same physical printer - this is a UI aid for fitting the model
    // on the bed, not the real firmware safety gate, which is still enforced at slice time.
    var bedShape by remember(uri) { mutableStateOf<BedShape?>(null) }
    LaunchedEffect(profile?.slicingModel) {
        val model = profile?.slicingModel ?: return@LaunchedEffect
        bedShape = try {
            withContext(Dispatchers.IO) { bedShapeFor(model, CosmosProfileGeneration.CURRENT, context.applicationContext) }
        } catch (e: Exception) { null }
    }
    // Owned here, not by ModelViewer (which only lives during the "customizing" step and would
    // otherwise close this out from under SlicingCoordinator.slice()'s later use of it) - see
    // ModelViewer.kt's own comment on why it deliberately doesn't close this itself. Closed once,
    // when this whole panel closes (a new uri, or the panel is dismissed) - not on every
    // recomposition, so re-entering the customize step (e.g. after Cancel) doesn't need to
    // reopen a session that's already open.
    DisposableEffect(uri) {
        onDispose { paintState.handle?.let { runCatching { NativeEngine.nativeClosePaintSession(it) } } }
    }
    var gcodeStats by remember(uri) { mutableStateOf<GcodeStats?>(null) }
    // A last, lightweight human-attention gate before the real "Start print" confirm below -
    // matches the EasyPrint reference's own "is the bed ready?" step. Purely a UI gate; nothing
    // here sends a command.
    var printerReady by remember(uri) { mutableStateOf(false) }

    if(name.isEmpty()) {
        AlertDialog(onDismissRequest=close,title={Text("Unsupported file")},
            text={Text("On-device slicing accepts STL, 3MF or OBJ model files.")},
            confirmButton={TextButton(close){Text("Close")}})
        return
    }
    if(profile == null) {
        AlertDialog(onDismissRequest=close,title={Text("No printer selected")},
            text={Text("Select a saved printer before sharing a model to slice.")},
            confirmButton={TextButton(close){Text("Close")}})
        return
    }
    if(!capabilitiesFor(profile.kind).acceptsOnDeviceSlicedGcode) {
        // Real, honest gap (see SlicingCoordinator.kt / docs/WORK_ORDER.md's WO-13 entry): the
        // engine's export_gcode() produces plain .gcode, not the .gcode.3mf bundle
        // BambuPrintRequest/bambuPrintName require - Bambu Lab is blocked for that reason.
        // Phase 2 real bug fix: Prusa Link is blocked here too now - this step below
        // (`LiveFileChanges`/`Moonraker.start`) always spoke Moonraker's own upload/start
        // protocol unconditionally, which a real PrusaLink printer doesn't implement
        // (PrusaLinkPrinterService's command() only ever sends print-control requests, never a
        // generic file upload) - slicing would have "succeeded" and then silently failed (or
        // worse, hit the wrong endpoint) at the upload step against real hardware.
        val vendorName = if(profile.kind == PrinterKind.BAMBU_LAB) "Bambu Lab" else "Prusa Link"
        AlertDialog(onDismissRequest=close,title={Text("Not yet supported for $vendorName")},
            text={Text(if(profile.kind == PrinterKind.BAMBU_LAB)
                "On-device slicing for Bambu Lab printers isn't wired up yet - it needs a .gcode.3mf bundle, and this app's slicing engine currently only produces plain .gcode. Slice in Bambu Studio or Orca and share the exported .gcode.3mf instead."
            else "On-device slicing for Prusa Link printers isn't wired up yet - the upload step needs a Prusa Link-specific file transfer this app doesn't implement. Slice in PrusaSlicer and upload from there instead.")},
            confirmButton={TextButton(close){Text("Close")}})
        return
    }

    LaunchedEffect(uri) {
        copyError = null
        localModel = try {
            withContext(Dispatchers.IO) { copySharedModel(context.applicationContext, uri, name) }
        } catch(e: Exception) { copyError = e.message ?: "Could not read the shared file."; null }
    }

    LaunchedEffect(localModel, profile.address, customization) {
        val chosen = customization ?: return@LaunchedEffect
        val model = localModel ?: return@LaunchedEffect
        working = true; stage = "Slicing…"; error = null
        // Only actually route through the paint session if something was really painted -
        // opening Paint mode and never dragging must produce the exact same output as if
        // painting didn't exist (paintState.painted, not just paintState.handle != null).
        val paintHandle = paintState.handle.takeIf { paintState.painted }
        when(val outcome = SlicingCoordinator.slice(context.applicationContext, model, profile, chosen.toOverrides(), paintHandle, transformState.transform)) {
            is SliceOutcome.Success -> { sliced = outcome.gcode; working = false }
            is SliceOutcome.FirmwareBlocked -> { working = false; error = outcome.reason }
            is SliceOutcome.Failed -> { working = false; error = outcome.message }
        }
    }
    // Toolpath + stats parsing, both off the main thread the same way slicing itself is
    // dispatched. A parse failure doesn't block printing - the review is a visualization aid,
    // not a correctness gate; the actual G-code was already produced successfully.
    LaunchedEffect(sliced) {
        val gcode = sliced ?: return@LaunchedEffect
        toolpathError = null
        try {
            slicedToolpath = withContext(Dispatchers.Default) { gcode.inputStream().buffered().use { GcodePreview.parse(it) } }
        } catch(e: Exception) { toolpathError = e.message ?: "Could not build a layer preview of the sliced G-code." }
        gcodeStats = runCatching { withContext(Dispatchers.Default) { GcodeStatsParser.parse(gcode) } }.getOrNull()
    }
    // The upload step needs a live, connected printer (LiveFileChanges.ready() requires it), so
    // it only starts once slicing has succeeded and the owner has reviewed the sliced layers,
    // and re-runs independently if the user backgrounds/returns.
    LaunchedEffect(sliced, reviewedLayers, state.connected) {
        val gcode = sliced ?: return@LaunchedEffect
        if(!reviewedLayers) return@LaunchedEffect
        if(!state.connected) { working = false; error = "Connect to ${state.address} to upload the sliced file."; return@LaunchedEffect }
        working = true; stage = "Uploading…"
        val backend = LiveFileChanges(state.address, File(context.cacheDir, "live-file"), rawApiKey = state.apiKeyFor(state.address))
        try {
            val requested = gcode.name
            val draft = withContext(Dispatchers.IO) { backend.prepare(LiveFileChanges.Operation.UPLOAD, "", requested, gcode) }
            withContext(Dispatchers.IO) { backend.confirm(draft.id) }
            stagedFilename = draft.destination
            working = false
        } catch(e: Exception) { working = false; error = e.message ?: "Could not upload the sliced file." }
        finally { backend.close() }
    }

    BackHandler(enabled = !working) { close() }

    // This panel renders outside Scaffold's own inset-aware padding (it's a full-screen takeover,
    // not Scaffold content), so it has to handle the status/navigation bar insets itself - caught
    // live: the bottom "Slice" action rendered underneath the system navigation bar/buttons.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton({ if(!working) close() }, Modifier.testTag("slicer-close")) { CompanionIcon(CompanionSymbol.CLOSE) }
            }
            when {
                customizing -> {
                    TabRow(tab) {
                        Tab(tab == 0, { tab = 0 }, text = { Text("Model") }, modifier = Modifier.testTag("slicer-tab-model"))
                        Tab(tab == 1, { tab = 1 }, text = { Text("Settings") }, modifier = Modifier.testTag("slicer-tab-settings"))
                        Tab(tab == 2, { tab = 2 }, text = { Text("Printer") }, modifier = Modifier.testTag("slicer-tab-printer"))
                    }
                    Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when(tab) {
                            0 -> {
                                ModelViewer(localModel, paintState = paintState, transformState = transformState, bedShape = bedShape)
                                copyError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            }
                            1 -> {
                                Text("A small, deliberately short list - not every OrcaSlicer setting, just the ones that most change how a print turns out.", style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(layerHeightText, {layerHeightText=it}, label={Text("Layer height (mm)")}, singleLine=true, modifier=Modifier.testTag("slice-layer-height"))
                                OutlinedTextField(infillText, {infillText=it}, label={Text("Infill (%)")}, singleLine=true, modifier=Modifier.testTag("slice-infill"))
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Checkbox(supportsEnabled, {supportsEnabled=it}, modifier=Modifier.testTag("slice-supports"))
                                    Text("Print supports")
                                }
                                customizeError?.let { Text(it, color=MaterialTheme.colorScheme.error) }
                            }
                            2 -> {
                                Text(profile.label, style = MaterialTheme.typography.titleSmall)
                                Text(state.address, style = MaterialTheme.typography.bodySmall)
                                Text("Slicing always targets whichever printer is currently selected on the Home tab - there's no separate picker here.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Button({
                        val layerHeight = validateLayerHeight(layerHeightText)
                        val infill = validateInfillPercent(infillText)
                        if(layerHeight == null) { customizeError = "Enter a layer height between 0.04 and 0.6mm."; tab = 1; return@Button }
                        if(infill == null) { customizeError = "Enter an infill percentage between 0 and 100."; tab = 1; return@Button }
                        if(transformState.outOfBounds) { tab = 0; return@Button }
                        customization = SliceCustomization(layerHeight, infill, supportsEnabled)
                        customizing = false
                    }, enabled = !transformState.outOfBounds, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("slice-customize-next")) { Text("Slice") }
                }
                sliced == null -> {
                    Column(Modifier.weight(1f).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if(working) { CircularProgressIndicator(); Text(stage) }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
                !reviewedLayers -> {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sliced result", style = MaterialTheme.typography.titleSmall)
                        gcodeStats?.let { s ->
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("slice-stats")) {
                                s.printTime?.let { Text(it) }
                                s.filamentUsedGrams?.let { Text("${it}g") }
                                s.filamentUsedMm?.let { Text("${(it/1000).let{m->"%.2f".format(m)}}m") }
                            }
                        }
                        slicedToolpath?.let { SlicedPreview(it) } ?: toolpathError?.let { Text(it, color=MaterialTheme.colorScheme.error) } ?: Text("Building layer preview…")
                    }
                    Button({ reviewedLayers = true }, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("slice-review-continue")) { Text("Continue") }
                }
                !printerReady -> {
                    Column(Modifier.weight(1f).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Is ${profile.label} ready to print?", style = MaterialTheme.typography.titleMedium)
                        Text("Have you cleared the bed and removed the previous print?", style = MaterialTheme.typography.bodyMedium)
                    }
                    Button({ printerReady = true }, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("slice-printer-ready")) { Text("Printer is ready") }
                }
                else -> {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        KilnFrame(accent=true) { Column(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(name, style=MaterialTheme.typography.titleSmall)
                            Text("Slices on-device for ${profile.label}, uploads the result to ${state.address}, then waits for you to confirm the print separately.")
                            customization?.let { Text("${it.layerHeightMm}mm layers · ${it.infillPercent}% infill · supports ${if(it.supportsEnabled) "on" else "off"}", style=MaterialTheme.typography.bodySmall) }
                        } }
                        if(working) Text(stage)
                        error?.let { Text(it, color=MaterialTheme.colorScheme.error) }
                        stagedFilename?.let { Text("Ready: $it", style=MaterialTheme.typography.bodySmall) }
                    }
                    Button({
                        val filename = stagedFilename ?: return@Button
                        execute(Moonraker.start(filename), state.generation)
                        close()
                    }, enabled=!working && error==null && stagedFilename!=null, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("slice-and-print-confirm")) { Text("Start print") }
                }
            }
        }
    }
}

private fun copySharedModel(context: android.content.Context, uri: Uri, name: String): File {
    val dir = File(context.cacheDir, "slice-inputs")
    check(dir.isDirectory || dir.mkdirs()) { "Cannot create local storage for the shared model." }
    dir.listFiles()?.forEach { it.delete() }
    val target = File(dir, name)
    val input = context.contentResolver.openInputStream(uri) ?: throw ApiFailure("Cannot open the shared file.")
    input.use { FileTransfer.copyBounded(it, target) }
    return target
}

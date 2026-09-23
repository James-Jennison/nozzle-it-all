package net.jamesjennison.klippercompanion

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jamesjennison.klippercompanion.project.AppDatabase
import net.jamesjennison.klippercompanion.project.ProjectObject
import net.jamesjennison.klippercompanion.project.ProjectViewModel
import net.jamesjennison.klippercompanion.project.material
import net.jamesjennison.klippercompanion.project.transform
import java.io.File

/**
 * Phase 1 (Consumer Slicer Plan §16): the real saved-project editor - opens an existing project
 * (or a freshly created one, [newProjectName]) via [ProjectViewModel], lets the owner add/
 * duplicate/remove objects on the build plate, edit their placement through [ProjectWorkspace]
 * (tap to select, drag/pinch/rotate the selected object), then slice the whole plate at once
 * (`SlicingCoordinator.sliceProject`, `engine::slice_multi_object`) and carry the result through
 * the same real review-then-confirm pipeline every other mutating command already uses (sliced
 * toolpath preview + stats, a printer-ready confirmation, upload, then an explicit Start print
 * tap). Deliberately does NOT touch the existing single-object share-intent flow
 * (SliceAndPrintPanel.kt) - this is a separate, additive entry point (a "Projects" section under
 * the Files tab), matching this session's own owner-confirmed choice to keep that already-tested
 * flow untouched rather than retrofit it.
 */
private enum class ProjectEditorStage { EDIT, SLICING, REVIEW, PRINTER_READY, STAGED }

@Composable fun ProjectEditorScreen(projectId: String?, newProjectName: String?, state: ScreenState, execute: (PrinterCommand, Int) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // remember(projectId) matches this codebase's existing per-screen-instance state convention
    // (SliceAndPrintPanel's own remember(uri)) rather than an androidx ViewModel scoped to the
    // Activity, which would otherwise retain state across switching between different projects.
    val vm = remember(projectId, newProjectName) { ProjectViewModel(context.applicationContext, AppDatabase.get(context.applicationContext).projectDao()) }
    var selectedId by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    var geometry by remember(projectId, newProjectName) { mutableStateOf<Map<String, MeshGeometry>>(emptyMap()) }
    var addError by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    var loadError by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    var ready by remember(projectId, newProjectName) { mutableStateOf(false) }

    LaunchedEffect(projectId, newProjectName) {
        ready = false
        loadError = null
        if (projectId != null) {
            if (!vm.loadProject(projectId)) loadError = "This project could not be found."
        } else if (newProjectName != null) {
            vm.newProject(newProjectName)
        }
        ready = true
    }

    val project by vm.project.collectAsState()
    val objects by vm.objects.collectAsState()

    // Loads each object's mesh geometry exactly once per source file - a transform-only change
    // to `objects` re-triggers this effect but every already-loaded id is skipped, so it never
    // re-parses a file just because its placement moved (ProjectGLRenderer separately relies on
    // this same "same geometry reference" stability to skip re-uploading its own VBO).
    LaunchedEffect(objects) {
        val missing = objects.filter { it.id !in geometry }
        if (missing.isEmpty()) return@LaunchedEffect
        val loaded = HashMap<String, MeshGeometry>()
        for (obj in missing) {
            val path = Uri.parse(obj.sourceFileUri).path ?: continue
            try { loaded[obj.id] = MeshLoader.load(path) } catch (_: Exception) { /* surfaced per-object below via a missing entry */ }
        }
        if (loaded.isNotEmpty()) geometry = geometry + loaded
    }

    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            addError = null
            try { selectedId = vm.addObject(uri).id } catch (e: Exception) { addError = e.message ?: "Could not add that file." }
        }
    }

    // Targets whichever printer is currently selected on the Home tab - same convention
    // SliceAndPrintPanel/BambuPrintPanel already use, no separate picker here.
    val profile = remember(state.address, state.profiles) { state.profiles.find { it.address == state.address } }
    // The real per-printer bed size/shape (same source SliceAndPrintPanel's own single-object
    // flow already reads) - used both for ProjectWorkspace's out-of-bounds tinting and as
    // Auto-arrange's real target width, not an invented default.
    var bedShape by remember(projectId, newProjectName) { mutableStateOf<BedShape?>(null) }
    // Phase 5 (§16): real slice-time validation data - this printer's own declared layer-height
    // range and filament temperature range, read from the same bundled profile pack every slice
    // already applies (not invented separately). Loaded alongside bedShape since both come from
    // the same asset read.
    var machineLimits by remember(projectId, newProjectName) { mutableStateOf<MachineLimits?>(null) }
    var filamentRange by remember(projectId, newProjectName) { mutableStateOf<FilamentTemperatureRange?>(null) }
    LaunchedEffect(profile?.slicingModel) {
        val model = profile?.slicingModel ?: return@LaunchedEffect
        try {
            bedShape = withContext(Dispatchers.IO) { bedShapeFor(model, CosmosProfileGeneration.CURRENT, context.applicationContext) }
            machineLimits = withContext(Dispatchers.IO) { machineLimitsFor(model, CosmosProfileGeneration.CURRENT, context.applicationContext) }
            filamentRange = withContext(Dispatchers.IO) { filamentTemperatureRangeFor(model, CosmosProfileGeneration.CURRENT, context.applicationContext) }
        } catch (e: Exception) { bedShape = null; machineLimits = null; filamentRange = null }
    }
    var stage by remember(projectId, newProjectName) { mutableStateOf(ProjectEditorStage.EDIT) }
    // Phase 4 (§4/§16, "basic-mode settings... beginner tier"): replaces the old raw
    // layer-height-in-mm text field with named Draft/Standard/Fine presets, and adds a real
    // geometry-driven "Auto" support decision - see BasicSlicing.kt.
    var quality by remember(projectId, newProjectName) { mutableStateOf(QualityPreset.STANDARD) }
    var infillText by remember(projectId, newProjectName) { mutableStateOf(DEFAULT_SLICE_CUSTOMIZATION.infillPercent.toString()) }
    var supportMode by remember(projectId, newProjectName) { mutableStateOf(SupportMode.AUTO) }
    var adhesionBrim by remember(projectId, newProjectName) { mutableStateOf(true) }
    var customizeError by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    var working by remember(projectId, newProjectName) { mutableStateOf(false) }
    var sliceStageLabel by remember(projectId, newProjectName) { mutableStateOf("") }
    var sliceError by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    var sliced by remember(projectId, newProjectName) { mutableStateOf<File?>(null) }
    var slicedToolpath by remember(projectId, newProjectName) { mutableStateOf<Toolpath?>(null) }
    var toolpathError by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    var gcodeStats by remember(projectId, newProjectName) { mutableStateOf<GcodeStats?>(null) }
    var stagedFilename by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    // Phase 3 (§11): the project's single, shared material (ProjectViewModel.setProjectMaterial
    // keeps every object's own denormalized snapshot in sync) - derived from `objects` itself,
    // not separate state, so it's always exactly what would actually get sliced.
    val currentMaterial = objects.firstOrNull()?.material()
    var materialPickerOpen by remember(projectId, newProjectName) { mutableStateOf(false) }
    var copiesText by remember(projectId, newProjectName) { mutableStateOf("1") }
    var spoolmanSpools by remember(projectId, newProjectName) { mutableStateOf<List<SpoolmanSpool>?>(null) }
    var spoolmanError by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    LaunchedEffect(materialPickerOpen, state.address, state.connected) {
        if (!materialPickerOpen) return@LaunchedEffect
        val target = profile ?: return@LaunchedEffect
        if (capabilitiesFor(target.kind).transport != PrinterTransport.MOONRAKER) { spoolmanError = null; spoolmanSpools = emptyList(); return@LaunchedEffect }
        if (!state.connected) { spoolmanError = "Connect to ${target.label} to read its Spoolman inventory."; spoolmanSpools = emptyList(); return@LaunchedEffect }
        spoolmanError = null
        try {
            val inventory = withContext(Dispatchers.IO) { state.moonrakerFor(target.address).use { it.spoolmanInventory() } }
            spoolmanSpools = if (inventory.available) inventory.spools.filterNot { it.archived } else emptyList()
            if (!inventory.available) spoolmanError = "Spoolman not found for ${target.label}."
        } catch (e: Exception) { spoolmanSpools = emptyList(); spoolmanError = e.message ?: "Could not read Spoolman inventory." }
    }

    // Real bed width, not an invented default - falls back to the widest current layout extent
    // (not a fixed 200mm) when no bed shape is known yet, so this never silently no-ops before a
    // printer/profile is selected. Shared by the Auto-arrange button and "Copies" below, so
    // adding N copies and resolving their placement is always the same one real code path.
    suspend fun runAutoArrange() {
        val objs = objects.mapNotNull { obj -> geometry[obj.id]?.let { WorkspaceObject(obj, it) } }
        if (objs.size <= 1) return
        val bedWidth = bedShape?.points?.let { pts -> (pts.maxOf { it.first } - pts.minOf { it.first }).takeIf { it > 0f } } ?: 200f
        val items = objs.map { wo -> ArrangeItem(wo.projectObject.id, (wo.geometry.maxX - wo.geometry.minX) / 2f * wo.projectObject.transform().scale, (wo.geometry.maxY - wo.geometry.minY) / 2f * wo.projectObject.transform().scale) }
        val placements = autoArrange(items, bedWidth)
        for ((id, placement) in placements) {
            val current = objects.find { it.id == id }?.transform() ?: continue
            vm.updateObjectTransform(id, current.copy(offsetXMm = placement.offsetXMm, offsetYMm = placement.offsetYMm, rotationZDeg = placement.rotationZDeg))
        }
    }

    fun startSlicing() {
        val infill = validateInfillPercent(infillText)
        if (infill == null) { customizeError = "Enter an infill percentage between 0 and 100."; return }
        // Phase 5 (§16): a genuinely invalid configuration is caught here, before the native
        // engine ever sees it - a blocking issue (a real machine limit) stops slicing outright;
        // a non-blocking one (the bundled profile's own declared-but-not-hard-limit range) is
        // still shown to the owner but doesn't stop them from proceeding.
        val blockingIssue = validateSliceConfiguration(machineLimits, quality.layerHeightMm, filamentRange, vm.currentMaterial()).firstOrNull { it.blocking }
        if (blockingIssue != null) { customizeError = blockingIssue.message; return }
        customizeError = null
        sliced = null; sliceError = null; stagedFilename = null
        stage = ProjectEditorStage.SLICING
        val basicSettings = BasicSliceSettings(quality, infill, supportMode, adhesionBrim)
        // Real geometry-driven decision (AUTO mode only consults this) - true if *any* object on
        // the plate has a real overhang, not just the selected one, since the whole plate slices
        // together.
        val needsSupport = objects.any { obj -> geometry[obj.id]?.let { meshNeedsSupport(it) } == true }
        scope.launch {
            working = true; sliceStageLabel = "Slicing…"
            val target = profile ?: run { working = false; sliceError = "Select a printer first."; return@launch }
            val objectsToSlice = objects.mapNotNull { obj ->
                Uri.parse(obj.sourceFileUri).path?.let { File(it) to obj.transform() }
            }
            // Phase 3 (§11): the project's material (if one was picked) overrides the same real
            // nozzle_temperature/bed-plate-temperature config keys the basic settings already
            // use this mechanism for - applied on top of, not instead of, layer height/infill/
            // supports, so picking a material never silently resets the rest of these settings.
            val overrides = basicSettings.toOverrides(needsSupport) + (vm.currentMaterial()?.toOverrides() ?: emptyMap())
            when (val outcome = SlicingCoordinator.sliceProject(context.applicationContext, objectsToSlice, target, overrides)) {
                is SliceOutcome.Success -> { sliced = outcome.gcode; working = false }
                is SliceOutcome.FirmwareBlocked -> { working = false; sliceError = outcome.reason }
                is SliceOutcome.Failed -> { working = false; sliceError = outcome.message }
            }
        }
    }

    // Toolpath + stats parsing, off the main thread - a parse failure doesn't block printing,
    // the review is a visualization aid, not a correctness gate (matches SliceAndPrintPanel's
    // own convention).
    LaunchedEffect(sliced) {
        val gcode = sliced ?: return@LaunchedEffect
        toolpathError = null
        try {
            slicedToolpath = withContext(Dispatchers.Default) { gcode.inputStream().buffered().use { GcodePreview.parse(it) } }
        } catch (e: Exception) { toolpathError = e.message ?: "Could not build a layer preview of the sliced G-code." }
        gcodeStats = runCatching { withContext(Dispatchers.Default) { GcodeStatsParser.parse(gcode) } }.getOrNull()
    }

    // Upload runs once the owner has reviewed the sliced layers and confirmed the printer is
    // ready - the same two-gate sequence SliceAndPrintPanel already uses before touching the
    // network with a real file write.
    LaunchedEffect(sliced, stage, state.connected) {
        val gcode = sliced ?: return@LaunchedEffect
        if (stage != ProjectEditorStage.STAGED) return@LaunchedEffect
        val target = profile ?: return@LaunchedEffect
        if (!state.connected) { working = false; sliceError = "Connect to ${target.address} to upload the sliced file."; return@LaunchedEffect }
        working = true; sliceStageLabel = "Uploading…"
        val backend = LiveFileChanges(target.address, File(context.cacheDir, "live-file"), rawApiKey = state.apiKeyFor(target.address))
        try {
            val requested = gcode.name
            val draft = withContext(Dispatchers.IO) { backend.prepare(LiveFileChanges.Operation.UPLOAD, "", requested, gcode) }
            withContext(Dispatchers.IO) { backend.confirm(draft.id) }
            stagedFilename = draft.destination
            working = false
        } catch (e: Exception) { working = false; sliceError = e.message ?: "Could not upload the sliced file." }
        finally { backend.close() }
    }

    // Real bug fix (owner-reported, 2026-09-23: "several screens... especially around the
    // slicer" had hard-to-read text): a plain Box + background() never sets LocalContentColor,
    // so every Text() here without its own explicit color fell back to LocalContentColor's own
    // top-level default (Color.Black in Compose Material3) - nearly invisible against this
    // theme's near-black background. MainActivity's own screen reads fine because it renders
    // inside Scaffold, which is a Surface under the hood and sets LocalContentColor correctly;
    // this screen and SliceAndPrintPanel.kt are separate full-screen overlays that never got
    // that for free. Surface sets it automatically via contentColorFor(color) - the real,
    // minimal fix, not a per-Text color patch that the next new Text() here would need to
    // remember too.
    Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(project?.name ?: "Project", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).testTag("project-editor-title"))
                // Mid-slice (SLICING/PRINTER_READY/STAGED), this backs out to Edit instead of
                // closing the whole screen outright - a real sliced-but-not-yet-started result
                // shouldn't be one tap from losing the review entirely.
                IconButton({ if (stage == ProjectEditorStage.EDIT) close() else stage = ProjectEditorStage.EDIT }, Modifier.testTag("project-editor-close")) {
                    CompanionIcon(CompanionSymbol.CLOSE)
                }
            }
            when {
                !ready -> Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator() }
                loadError != null -> Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) { Text(loadError!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("project-editor-error")) }
                stage == ProjectEditorStage.EDIT -> Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val workspaceObjects = objects.mapNotNull { obj -> geometry[obj.id]?.let { WorkspaceObject(obj, it) } }
                    // Real pairwise collision check (footprintsOverlap, the same rotate-about-
                    // pivot SAT math auto-arrange's own sizing uses) - recomputed from current
                    // state each recomposition, not cached, so a drag in ProjectWorkspace above
                    // updates this immediately. A small safety margin (not zero) matches typical
                    // nozzle/extrusion clearance, not just "don't literally intersect."
                    val collidingIds = remember(workspaceObjects) {
                        val footprints = workspaceObjects.associate { it.projectObject.id to footprintOf(it.geometry, it.projectObject.transform()) }
                        val colliding = mutableSetOf<String>()
                        val ids = footprints.keys.toList()
                        for (i in ids.indices) for (j in i + 1 until ids.size) {
                            if (footprintsOverlap(footprints.getValue(ids[i]), footprints.getValue(ids[j]), marginMm = 2f)) {
                                colliding += ids[i]; colliding += ids[j]
                            }
                        }
                        colliding
                    }
                    ProjectWorkspace(
                        objects = workspaceObjects,
                        selectedId = selectedId,
                        onSelect = { selectedId = it },
                        onTransformChange = { id, transform -> scope.launch { vm.updateObjectTransform(id, transform) } },
                        bedShape = bedShape,
                        collidingIds = collidingIds,
                    )
                    val missingGeometryCount = objects.size - workspaceObjects.size
                    if (missingGeometryCount > 0) Text("Loading $missingGeometryCount model(s)…", style = MaterialTheme.typography.bodySmall)
                    if (collidingIds.isNotEmpty()) Text(
                        "${collidingIds.size} object(s) overlap - move them apart before slicing, or use Auto-arrange.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("project-collision-warning"),
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ pickModel.launch(arrayOf("*/*")) }, modifier = Modifier.testTag("project-add-object")) { Text("Add model") }
                        OutlinedButton({ selectedId?.let { id -> scope.launch { selectedId = vm.duplicateObject(id)?.id } } }, enabled = selectedId != null, modifier = Modifier.testTag("project-duplicate-object")) { Text("Duplicate") }
                        OutlinedButton({ selectedId?.let { id -> scope.launch { vm.removeObject(id); selectedId = null } } }, enabled = selectedId != null, modifier = Modifier.testTag("project-remove-object")) { Text("Remove") }
                    }
                    OutlinedButton({ scope.launch { runAutoArrange() } }, enabled = objects.size > 1, modifier = Modifier.testTag("project-auto-arrange")) { Text("Auto-arrange") }
                    // Phase 4 (§4/§16): "copies" - sets the selected object's total count on the
                    // plate at once (duplicating up or removing down to N), then resolves
                    // placement through the same real auto-arrange path above rather than
                    // leaving N copies stacked on top of each other.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(copiesText, { copiesText = it }, label = { Text("Copies of selected") }, singleLine = true, modifier = Modifier.weight(1f).testTag("project-copies"))
                        Button({
                            val base = objects.find { it.id == selectedId } ?: return@Button
                            val n = copiesText.trim().toIntOrNull()?.coerceIn(1, 20) ?: return@Button
                            scope.launch {
                                val siblings = objects.filter { it.sourceFileUri == base.sourceFileUri }
                                if (n > siblings.size) repeat(n - siblings.size) { vm.duplicateObject(base.id) }
                                else if (n < siblings.size) siblings.filter { it.id != base.id }.take(siblings.size - n).forEach { vm.removeObject(it.id) }
                                runAutoArrange()
                            }
                        }, enabled = selectedId != null, modifier = Modifier.testTag("project-apply-copies")) { Text("Set") }
                    }
                    addError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                    Text("Objects on this plate", style = MaterialTheme.typography.titleSmall)
                    if (objects.isEmpty()) Text("No objects yet - add an STL, 3MF or OBJ model to start this project's build plate.", style = MaterialTheme.typography.bodySmall)
                    objects.forEach { obj -> ProjectObjectRow(obj, selected = obj.id == selectedId, onClick = { selectedId = obj.id }) }

                    Text("Material", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(currentMaterial?.displayName ?: "None selected - using the printer's default profile", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).testTag("project-material-current"))
                        OutlinedButton({ materialPickerOpen = true }, enabled = objects.isNotEmpty(), modifier = Modifier.testTag("project-choose-material")) { Text("Choose") }
                    }

                    Text("Slicing settings", style = MaterialTheme.typography.titleSmall)
                    Text("Quality", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QualityPreset.entries.forEach { preset ->
                            FilterChip(quality == preset, { quality = preset }, label = { Text("${preset.label} (${preset.layerHeightMm}mm)") }, modifier = Modifier.testTag("project-quality-${preset.name}"))
                        }
                    }
                    OutlinedTextField(infillText, { infillText = it }, label = { Text("Strength - infill (%)") }, singleLine = true, modifier = Modifier.testTag("project-infill"))
                    // Real geometry-driven default (Phase 4's own "intelligent defaulting"): Auto
                    // reads whether any object on the plate actually has a real overhang
                    // (meshNeedsSupport) rather than asking the owner to already know.
                    val autoNeedsSupport = objects.any { obj -> geometry[obj.id]?.let { meshNeedsSupport(it) } == true }
                    Text("Supports" + if (supportMode == SupportMode.AUTO) " · detected: ${if (autoNeedsSupport) "this model needs support" else "no support needed"}" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(supportMode == SupportMode.OFF, { supportMode = SupportMode.OFF }, label = { Text("Off") }, modifier = Modifier.testTag("project-support-off"))
                        FilterChip(supportMode == SupportMode.AUTO, { supportMode = SupportMode.AUTO }, label = { Text("Auto") }, modifier = Modifier.testTag("project-support-auto"))
                        FilterChip(supportMode == SupportMode.ON, { supportMode = SupportMode.ON }, label = { Text("On") }, modifier = Modifier.testTag("project-support-on"))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(adhesionBrim, { adhesionBrim = it }, modifier = Modifier.testTag("project-adhesion-brim"))
                        Text("Brim (bed adhesion)")
                    }
                    // Phase 5 (§16): real slice-time validation, shown live (not only after
                    // tapping Slice) so the owner sees a problem while still adjusting settings.
                    val validationIssues = validateSliceConfiguration(machineLimits, quality.layerHeightMm, filamentRange, currentMaterial)
                    validationIssues.forEach { issue ->
                        Text(
                            issue.message,
                            color = if (issue.blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag(if (issue.blocking) "project-validation-error" else "project-validation-warning"),
                        )
                    }
                    customizeError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (profile == null) Text("Select a printer on the Home tab first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Phase 2 real bug fix: this screen's own upload step (LiveFileChanges/
                    // Moonraker.start, below) always assumed Moonraker unconditionally - Prusa
                    // Link would silently fail that way, since PrusaLinkPrinterService has no
                    // generic file-upload endpoint this app implements. Phase 6 note: Bambu Lab's
                    // own acceptsOnDeviceSlicedGcode is true now (see PrinterCapabilities.kt), but
                    // that's SlicingCoordinator.slice()'s single-object .gcode.3mf bundle path
                    // (SliceAndPrintPanel.kt) - this screen's multi-object plate still only slices
                    // through sliceProject()/nativeSliceMultiObject, which produces plain .gcode,
                    // not a bundle, so Bambu Lab is deliberately excluded here too until multi-
                    // object Bambu bundle export is built.
                    val acceptsSlicedGcode = profile?.let { it.kind != PrinterKind.BAMBU_LAB && capabilitiesFor(it.kind).acceptsOnDeviceSlicedGcode } ?: true
                    if (profile != null && !acceptsSlicedGcode) Text(
                        "On-device slicing isn't wired up yet for ${profile.label} - its printer type needs an upload/print path this app doesn't implement.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("project-slice-unsupported-printer"),
                    )
                    Button({ startSlicing() }, enabled = objects.isNotEmpty() && profile != null && collidingIds.isEmpty() && acceptsSlicedGcode && validationIssues.none { it.blocking }, modifier = Modifier.fillMaxWidth().testTag("project-slice")) { Text("Slice") }
                }
                stage == ProjectEditorStage.SLICING && sliced == null -> {
                    Column(Modifier.weight(1f).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (working) { CircularProgressIndicator(); Text(sliceStageLabel) }
                        sliceError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("project-slice-error")) }
                    }
                }
                stage == ProjectEditorStage.SLICING -> {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sliced result", style = MaterialTheme.typography.titleSmall)
                        gcodeStats?.let { s ->
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("project-slice-stats")) {
                                s.printTime?.let { Text(it) }
                                s.filamentUsedGrams?.let { Text("${it}g") }
                                s.filamentUsedMm?.let { Text("${(it / 1000).let { m -> "%.2f".format(m) }}m") }
                            }
                        }
                        slicedToolpath?.let { SlicedPreview(it, materialColorHex = currentMaterial?.colorHex) } ?: toolpathError?.let { Text(it, color = MaterialTheme.colorScheme.error) } ?: Text("Building layer preview…")
                    }
                    Button({ stage = ProjectEditorStage.PRINTER_READY }, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("project-slice-review-continue")) { Text("Continue") }
                }
                stage == ProjectEditorStage.PRINTER_READY -> {
                    val target = profile
                    Column(Modifier.weight(1f).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Is ${target?.label ?: state.address} ready to print?", style = MaterialTheme.typography.titleMedium)
                        Text("Have you cleared the bed and removed the previous print?", style = MaterialTheme.typography.bodyMedium)
                    }
                    Button({ stage = ProjectEditorStage.STAGED }, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("project-printer-ready")) { Text("Printer is ready") }
                }
                else -> {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        KilnFrame(accent = true) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(project?.name ?: "Project", style = MaterialTheme.typography.titleSmall)
                                Text("Slices this project's whole plate on-device for ${profile?.label ?: state.address}, uploads the result to ${state.address}, then waits for you to confirm the print separately.")
                                Text("${objects.size} object(s) · ${quality.label} (${quality.layerHeightMm}mm) · $infillText% infill · supports ${supportMode.name.lowercase()}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (working) Text(sliceStageLabel)
                        sliceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        stagedFilename?.let { Text("Ready: $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    Button({
                        val filename = stagedFilename ?: return@Button
                        execute(Moonraker.start(filename), state.generation)
                        close()
                    }, enabled = !working && sliceError == null && stagedFilename != null, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("project-slice-and-print-confirm")) { Text("Start print") }
                }
            }
        }
    }
    if (materialPickerOpen) {
        AlertDialog(
            onDismissRequest = { materialPickerOpen = false }, title = { Text("Choose a material") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ scope.launch { vm.setProjectMaterial(null) }; materialPickerOpen = false }, modifier = Modifier.testTag("material-none")) { Text("None - use the printer's default profile") }
                    Text("Bundled", style = MaterialTheme.typography.labelMedium)
                    BUNDLED_MATERIAL_PROFILES.forEach { m ->
                        TextButton({ scope.launch { vm.setProjectMaterial(m) }; materialPickerOpen = false }, modifier = Modifier.testTag("material-${m.id}")) {
                            Text("${m.displayName} · ${m.tempNozzleC}°C / ${m.tempBedC}°C bed")
                        }
                    }
                    Text("From Spoolman", style = MaterialTheme.typography.labelMedium)
                    spoolmanError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    val spools = spoolmanSpools
                    if (spools != null && spools.isEmpty() && spoolmanError == null) Text("No spools found.", style = MaterialTheme.typography.bodySmall)
                    spools?.forEach { spool ->
                        val m = spool.toMaterialProfile()
                        TextButton({ scope.launch { vm.setProjectMaterial(m) }; materialPickerOpen = false }, modifier = Modifier.testTag("material-${m.id}")) {
                            Text(m.displayName + (m.tempNozzleC?.let { " · ${it}°C" } ?: " · no temperature set"))
                        }
                    }
                }
            },
            confirmButton = { TextButton({ materialPickerOpen = false }) { Text("Close") } },
        )
    }
}

@Composable private fun ProjectObjectRow(obj: ProjectObject, selected: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().testTag("project-object-${obj.id}"),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Uri.parse(obj.sourceFileUri).lastPathSegment ?: obj.id, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick) { Text(if (selected) "Selected" else "Select") }
        }
    }
}

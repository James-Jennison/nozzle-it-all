package net.jamesjennison.klippercompanion

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
    LaunchedEffect(profile?.slicingModel) {
        val model = profile?.slicingModel ?: return@LaunchedEffect
        bedShape = try {
            withContext(Dispatchers.IO) { bedShapeFor(model, CosmosProfileGeneration.CURRENT, context.applicationContext) }
        } catch (e: Exception) { null }
    }
    var stage by remember(projectId, newProjectName) { mutableStateOf(ProjectEditorStage.EDIT) }
    var layerHeightText by remember(projectId, newProjectName) { mutableStateOf(DEFAULT_SLICE_CUSTOMIZATION.layerHeightMm.toString()) }
    var infillText by remember(projectId, newProjectName) { mutableStateOf(DEFAULT_SLICE_CUSTOMIZATION.infillPercent.toString()) }
    var supportsEnabled by remember(projectId, newProjectName) { mutableStateOf(DEFAULT_SLICE_CUSTOMIZATION.supportsEnabled) }
    var customizeError by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    var working by remember(projectId, newProjectName) { mutableStateOf(false) }
    var sliceStageLabel by remember(projectId, newProjectName) { mutableStateOf("") }
    var sliceError by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    var sliced by remember(projectId, newProjectName) { mutableStateOf<File?>(null) }
    var slicedToolpath by remember(projectId, newProjectName) { mutableStateOf<Toolpath?>(null) }
    var toolpathError by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    var gcodeStats by remember(projectId, newProjectName) { mutableStateOf<GcodeStats?>(null) }
    var stagedFilename by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }

    fun startSlicing() {
        val layerHeight = validateLayerHeight(layerHeightText)
        val infill = validateInfillPercent(infillText)
        if (layerHeight == null) { customizeError = "Enter a layer height between 0.04 and 0.6mm."; return }
        if (infill == null) { customizeError = "Enter an infill percentage between 0 and 100."; return }
        customizeError = null
        sliced = null; sliceError = null; stagedFilename = null
        stage = ProjectEditorStage.SLICING
        val customization = SliceCustomization(layerHeight, infill, supportsEnabled)
        scope.launch {
            working = true; sliceStageLabel = "Slicing…"
            val target = profile ?: run { working = false; sliceError = "Select a printer first."; return@launch }
            val objectsToSlice = objects.mapNotNull { obj ->
                Uri.parse(obj.sourceFileUri).path?.let { File(it) to obj.transform() }
            }
            when (val outcome = SlicingCoordinator.sliceProject(context.applicationContext, objectsToSlice, target, customization.toOverrides())) {
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

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
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
                    // Real bed width, not an invented default - falls back to the widest current
                    // layout extent (not a fixed 200mm) when no bed shape is known yet, so
                    // Auto-arrange never silently no-ops before a printer/profile is selected.
                    OutlinedButton(
                        {
                            val bedWidth = bedShape?.points?.let { pts -> (pts.maxOf { it.first } - pts.minOf { it.first }).takeIf { it > 0f } } ?: 200f
                            val items = workspaceObjects.map { wo -> ArrangeItem(wo.projectObject.id, (wo.geometry.maxX - wo.geometry.minX) / 2f * wo.projectObject.transform().scale, (wo.geometry.maxY - wo.geometry.minY) / 2f * wo.projectObject.transform().scale) }
                            val placements = autoArrange(items, bedWidth)
                            scope.launch {
                                for ((id, placement) in placements) {
                                    val current = objects.find { it.id == id }?.transform() ?: continue
                                    vm.updateObjectTransform(id, current.copy(offsetXMm = placement.offsetXMm, offsetYMm = placement.offsetYMm, rotationZDeg = placement.rotationZDeg))
                                }
                            }
                        },
                        enabled = objects.size > 1, modifier = Modifier.testTag("project-auto-arrange"),
                    ) { Text("Auto-arrange") }
                    addError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                    Text("Objects on this plate", style = MaterialTheme.typography.titleSmall)
                    if (objects.isEmpty()) Text("No objects yet - add an STL, 3MF or OBJ model to start this project's build plate.", style = MaterialTheme.typography.bodySmall)
                    objects.forEach { obj -> ProjectObjectRow(obj, selected = obj.id == selectedId, onClick = { selectedId = obj.id }) }

                    Text("Slicing settings", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(layerHeightText, { layerHeightText = it }, label = { Text("Layer height (mm)") }, singleLine = true, modifier = Modifier.testTag("project-layer-height"))
                    OutlinedTextField(infillText, { infillText = it }, label = { Text("Infill (%)") }, singleLine = true, modifier = Modifier.testTag("project-infill"))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(supportsEnabled, { supportsEnabled = it }, modifier = Modifier.testTag("project-supports"))
                        Text("Print supports")
                    }
                    customizeError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (profile == null) Text("Select a printer on the Home tab first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button({ startSlicing() }, enabled = objects.isNotEmpty() && profile != null && collidingIds.isEmpty(), modifier = Modifier.fillMaxWidth().testTag("project-slice")) { Text("Slice") }
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
                        slicedToolpath?.let { SlicedPreview(it) } ?: toolpathError?.let { Text(it, color = MaterialTheme.colorScheme.error) } ?: Text("Building layer preview…")
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
                                Text("${objects.size} object(s) · $layerHeightText mm layers · $infillText% infill · supports ${if (supportsEnabled) "on" else "off"}", style = MaterialTheme.typography.bodySmall)
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

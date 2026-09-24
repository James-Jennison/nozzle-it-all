package net.jamesjennison.klippercompanion

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
private fun fmtMm(v: Float): String = if (v == v.toLong().toFloat()) v.toLong().toString() else "%.1f".format(v)

private enum class PickMode { FACE, MEASURE, PAINT, REGION }

// Editable form state of one modifier/blocker region (all text, validated on save). Positions are mm from the object's centre.
private data class RegionDraft(
    val index: Int, val kind: VolumeKind = VolumeKind.MODIFIER, val shape: VolumeShape = VolumeShape.BOX,
    val x: String = "0", val y: String = "0", val z: String = "0", val sx: String = "10", val sy: String = "10", val sz: String = "10",
    val infill: String = "", val walls: String = "",
)
private enum class ProjectEditorStage { EDIT, SLICING, REVIEW, PRINTER_READY, STAGED }

@Composable fun ProjectEditorScreen(projectId: String?, newProjectName: String?, state: ScreenState, execute: (PrinterCommand, Int) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // remember(projectId) matches this codebase's existing per-screen-instance state convention
    // (SliceAndPrintPanel's own remember(uri)) rather than an androidx ViewModel scoped to the
    // Activity, which would otherwise retain state across switching between different projects.
    val vm = remember(projectId, newProjectName) { ProjectViewModel(context.applicationContext, AppDatabase.get(context.applicationContext).projectDao()) }
    var selectedId by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    // WO-30 (owner request: "Desktop-like power adapted to mobile" - a real EasyPrint-style plate
    // toolbar): which objects are currently hidden from the workspace. Local, UI-only state, not
    // persisted on ProjectObject - hiding is a workspace visibility aid, not a print-time
    // decision, so a hidden object is still sliced exactly as if it were shown (matches "what you
    // see is what gets edited, not necessarily what gets printed" - an explicit, honest scope
    // choice, not a half-built exclude-from-slice feature).
    var hiddenIds by remember(projectId, newProjectName) { mutableStateOf(setOf<String>()) }
    // WO-30 follow-up (owner: "I should be able to rotate the model just by swiping around the
    // box, not having to necessarily pinch and rotate"): which real action a one-finger drag on
    // the selected object performs - see WorkspaceInteractionMode's own doc comment.
    var interactionMode by remember(projectId, newProjectName) { mutableStateOf(WorkspaceInteractionMode.MOVE) }
    var geometry by remember(projectId, newProjectName) { mutableStateOf<Map<String, MeshGeometry>>(emptyMap()) }
    val geometrySource = remember(projectId, newProjectName) { HashMap<String, String>() }
    var pickMode by remember(projectId, newProjectName) { mutableStateOf<PickMode?>(null) }
    var measurePointA by remember(projectId, newProjectName) { mutableStateOf<FloatArray?>(null) }
    var toolMessage by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    var cutDialogFor by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    var paintKind by remember(projectId, newProjectName) { mutableStateOf(PaintKind.SUPPORT_ENFORCER) }
    var paintTool by remember(projectId, newProjectName) { mutableStateOf(2) }
    var brushRadiusMm by remember(projectId, newProjectName) { mutableStateOf(3f) }
    var lastDab by remember(projectId, newProjectName) { mutableStateOf<FloatArray?>(null) }
    var regionDialog by remember(projectId, newProjectName) { mutableStateOf<RegionDraft?>(null) }
    var movingRegion by remember(projectId, newProjectName) { mutableStateOf<Int?>(null) }
    var regionGrab by remember(projectId, newProjectName) { mutableStateOf<FloatArray?>(null) }
    var overlays by remember(projectId, newProjectName) { mutableStateOf<Map<String, List<OverlayGroup>>>(emptyMap()) }
    val overlayCache = remember(projectId, newProjectName) { HashMap<String, Triple<Pair<String?, String?>, MeshGeometry, List<OverlayGroup>>>() }
    val discCache = remember(projectId, newProjectName) { HashMap<String, Pair<MeshGeometry, List<PaintDisc?>>>() }
    val triMeshes = remember(projectId, newProjectName) { java.util.IdentityHashMap<MeshGeometry, TriMesh>() }
    var cutFraction by remember(projectId, newProjectName) { mutableStateOf(0.5f) }
    var keepUpper by remember(projectId, newProjectName) { mutableStateOf(true) }
    var keepLower by remember(projectId, newProjectName) { mutableStateOf(true) }
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
    val undoState by vm.undoState.collectAsState()
    val plates by vm.plates.collectAsState()
    val allProjectObjects by vm.allObjects.collectAsState()
    val activePlateId by vm.activePlateId.collectAsState()
    var moveMenuOpen by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            exportMessage = try {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { vm.exportArchive(it) } ?: error("Cannot write to that location.") }
                "Project exported."
            } catch (e: Exception) { "Export failed: ${e.message}" }
        }
    }

    // Loads each object's mesh geometry exactly once per source file - a transform-only change
    // to `objects` re-triggers this effect but every already-loaded id is skipped, so it never
    // re-parses a file just because its placement moved (ProjectGLRenderer separately relies on
    // this same "same geometry reference" stability to skip re-uploading its own VBO).
    LaunchedEffect(objects) {
        // Reloaded when an edit (mirror, lay flat, cut) swapped the object's model file.
        val missing = objects.filter { it.id !in geometry || geometrySource[it.id] != it.sourceFileUri }
        if (missing.isEmpty()) return@LaunchedEffect
        val loaded = HashMap<String, MeshGeometry>()
        for (obj in missing) {
            val path = Uri.parse(obj.sourceFileUri).path ?: continue
            try { loaded[obj.id] = MeshLoader.load(path); geometrySource[obj.id] = obj.sourceFileUri } catch (_: Exception) { /* surfaced per-object below via a missing entry */ }
        }
        if (loaded.isNotEmpty()) geometry = geometry + loaded
    }

    // Paint dabs and region outlines shown on each object. Dabs are ray-cast once and cached (a drag appends strokes, an
    // undo only ever drops the tail), so painting never re-casts the whole history.
    LaunchedEffect(objects.map { Triple(it.id, it.paintJson, it.volumesJson) }, geometry) {
        val result = withContext(Dispatchers.Default) {
            val out = HashMap<String, List<OverlayGroup>>()
            for (obj in objects) {
                val g = geometry[obj.id] ?: continue
                val key = obj.paintJson to obj.volumesJson
                val cached = overlayCache[obj.id]
                if (cached != null && cached.first == key && cached.second === g) { out[obj.id] = cached.third; continue }
                val strokes = PaintCodec.decode(obj.paintJson)
                val previous = discCache[obj.id]?.takeIf { it.first === g }?.second ?: emptyList()
                val mesh by lazy { triMeshes.getOrPut(g) { MeshEdit.fromGeometry(g) } }
                val discs: List<PaintDisc?> = when {
                    strokes.isEmpty() -> emptyList()
                    strokes.size <= previous.size -> previous.take(strokes.size)
                    else -> previous + strokes.drop(previous.size).map { OverlayBuilders.discsFor(listOf(it), mesh, g.origin).firstOrNull() }
                }
                discCache[obj.id] = g to discs
                val center = floatArrayOf(0f, 0f, 0f)
                val groups = OverlayBuilders.paintGroups(discs.filterNotNull()) +
                    VolumeCodec.decode(obj.volumesJson).map { v -> OverlayBuilders.volumeOutline(v, floatArrayOf(v.center[0] + g.origin[0], v.center[1] + g.origin[1], v.center[2] + g.origin[2])) }
                overlayCache[obj.id] = Triple(key, g, groups)
                out[obj.id] = groups
            }
            out
        }
        overlays = result
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
    // Phase 8 follow-up (§11, §16, WO-28): the real per-object material/tool assignment UI below
    // only appears when the target's own bundled machine.json actually declares more than one
    // extruder (ToolSlots.kt's own parseToolCount - today, only Snapmaker U1) - every other
    // printer keeps exactly today's single-material-per-project UX unchanged, not a hidden
    // no-op control (§20).
    var toolCount by remember(projectId, newProjectName) { mutableIntStateOf(1) }
    LaunchedEffect(profile?.slicingModel) {
        val model = profile?.slicingModel ?: return@LaunchedEffect
        try {
            bedShape = withContext(Dispatchers.IO) { bedShapeFor(model, CosmosProfileGeneration.CURRENT, context.applicationContext) }
            machineLimits = withContext(Dispatchers.IO) { machineLimitsFor(model, CosmosProfileGeneration.CURRENT, context.applicationContext) }
            filamentRange = withContext(Dispatchers.IO) { filamentTemperatureRangeFor(model, CosmosProfileGeneration.CURRENT, context.applicationContext) }
            toolCount = withContext(Dispatchers.IO) { toolCountFor(model, CosmosProfileGeneration.CURRENT, context.applicationContext) } ?: 1
        } catch (e: Exception) { bedShape = null; machineLimits = null; filamentRange = null; toolCount = 1 }
    }
    var stage by remember(projectId, newProjectName) { mutableStateOf(ProjectEditorStage.EDIT) }
    // WO-30 follow-up (owner: "too much text/settings visible at once"): the EDIT stage's plate,
    // slicing settings and printer summary now live behind their own tabs (mirroring
    // SliceAndPrintPanel.kt's own Model/Settings/Printer split) instead of one long scrolling
    // page - every existing testTag inside each section is unchanged, only which tab shows it.
    var editorTab by remember(projectId, newProjectName) { mutableIntStateOf(0) }
    // Phase 4 (§4/§16, "basic-mode settings... beginner tier"): replaces the old raw
    // layer-height-in-mm text field with named Draft/Standard/Fine presets, and adds a real
    // geometry-driven "Auto" support decision - see BasicSlicing.kt.
    var quality by remember(projectId, newProjectName) { mutableStateOf(QualityPreset.STANDARD) }
    var infillText by remember(projectId, newProjectName) { mutableStateOf(DEFAULT_SLICE_CUSTOMIZATION.infillPercent.toString()) }
    var supportMode by remember(projectId, newProjectName) { mutableStateOf(SupportMode.AUTO) }
    var adhesionBrim by remember(projectId, newProjectName) { mutableStateOf(true) }
    var advancedOverrides by remember(projectId, newProjectName) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var customizeError by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    var working by remember(projectId, newProjectName) { mutableStateOf(false) }
    var sliceStageLabel by remember(projectId, newProjectName) { mutableStateOf("") }
    var sliceProgress by remember { mutableStateOf(0) }
    LaunchedEffect(working, sliceStageLabel) {
        sliceProgress = 0
        while (working && sliceStageLabel == "Slicing…") { sliceProgress = SlicingCoordinator.progress().coerceIn(0, 100); kotlinx.coroutines.delay(200) }
    }
    var sliceError by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    var sliced by remember(projectId, newProjectName) { mutableStateOf<File?>(null) }
    var plateResults by remember(projectId, newProjectName) { mutableStateOf<Map<String, File>>(emptyMap()) }
    var plateProgressLabel by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    var slicedToolpath by remember(projectId, newProjectName) { mutableStateOf<Toolpath?>(null) }
    var toolpathError by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    var gcodeStats by remember(projectId, newProjectName) { mutableStateOf<GcodeStats?>(null) }
    var stagedFilename by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    // Phase 3 (§11): the project's single, shared material (ProjectViewModel.setProjectMaterial
    // keeps every object's own denormalized snapshot in sync) - derived from `objects` itself,
    // not separate state, so it's always exactly what would actually get sliced.
    val currentMaterial = objects.firstOrNull()?.material()
    var materialPickerOpen by remember(projectId, newProjectName) { mutableStateOf(false) }
    // Phase 8 follow-up (§11, §16, WO-28): the real per-object material+tool picker, only ever
    // opened when toolCount > 1 (see the "Objects on this plate" section below) - holds the
    // object id being assigned, reusing the exact same Bundled/Spoolman material list the
    // single-material picker already loads below (materialPickerOpen and this are never both
    // non-null/true at once - each printer target uses exactly one of the two real pickers).
    var perObjectPickerFor by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    var copiesText by remember(projectId, newProjectName) { mutableStateOf("1") }
    var spoolmanSpools by remember(projectId, newProjectName) { mutableStateOf<List<SpoolmanSpool>?>(null) }
    var spoolmanError by remember(projectId, newProjectName) { mutableStateOf<String?>(null) }
    LaunchedEffect(materialPickerOpen, perObjectPickerFor, state.address, state.connected) {
        if (!materialPickerOpen && perObjectPickerFor == null) return@LaunchedEffect
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
        vm.updateTransforms(placements.mapNotNull { (id, placement) ->
            val current = objects.find { it.id == id }?.transform() ?: return@mapNotNull null
            id to current.copy(offsetXMm = placement.offsetXMm, offsetYMm = placement.offsetYMm, rotationZDeg = placement.rotationZDeg)
        }.toMap())
    }

    // Nearest model hit under a world-space ray, using each object's real triangles (not its bounding sphere).
    fun pickTriangle(origin: FloatArray, dir: FloatArray): Triple<ProjectObject, TriMesh, MeshHit>? {
        var best: Triple<ProjectObject, TriMesh, MeshHit>? = null; var bestDist = Float.MAX_VALUE
        for (obj in objects) {
            val g = geometry[obj.id] ?: continue
            val frame = PlacedFrame(obj.transform(), g.origin)
            val (lo, ld) = frame.rayToLocal(origin, dir)
            val mesh = MeshEdit.fromGeometry(g)
            val hit = MeshEdit.rayHit(mesh, lo, ld) ?: continue
            val worldDist = MeshEdit.distance(origin, frame.toWorld(hit.point))
            if (worldDist < bestDist) { bestDist = worldDist; best = Triple(obj, mesh, hit) }
        }
        return best
    }

    fun handlePaintDrag(origin: FloatArray, dir: FloatArray, start: Boolean) {
        if (start) lastDab = null
        val id = selectedId ?: run { toolMessage = "Select an object to paint on."; return }
        val obj = objects.find { it.id == id } ?: return
        val g = geometry[id] ?: return
        val (lo, ld) = PlacedFrame(obj.transform(), g.origin).rayToLocal(origin, dir)
        val len = kotlin.math.sqrt(ld[0] * ld[0] + ld[1] * ld[1] + ld[2] * ld[2]); if (len < 1e-9f) return
        val nd = floatArrayOf(ld[0] / len, ld[1] / len, ld[2] / len)
        val hit = MeshEdit.rayHit(triMeshes.getOrPut(g) { MeshEdit.fromGeometry(g) }, lo, nd) ?: return
        val localRadius = brushRadiusMm / obj.scale
        lastDab?.let { if (MeshEdit.distance(it, hit.point) < localRadius * 0.5f) return }
        lastDab = hit.point
        val from = floatArrayOf(hit.point[0] - nd[0] * 5f, hit.point[1] - nd[1] * 5f, hit.point[2] - nd[2] * 5f)
        scope.launch { vm.addPaintStrokes(id, listOf(PaintStroke(paintKind, g.objectFrame(from), nd, localRadius, if (paintKind == PaintKind.MATERIAL) paintTool.coerceIn(1, toolCount.coerceAtLeast(1)) else 0))) }
    }

    // Drag a region across the selected model: the ray is intersected with the horizontal plane through the region's
    // centre (in the object's frame, where a Z rotation and uniform scale keep that plane horizontal), and the grab
    // point keeps its offset from the centre so the region does not jump under the finger.
    fun handleRegionDrag(origin: FloatArray, dir: FloatArray, start: Boolean) {
        val id = selectedId ?: return; val index = movingRegion ?: return
        val obj = objects.find { it.id == id } ?: return; val g = geometry[id] ?: return
        val volumes = VolumeCodec.decode(obj.volumesJson); val v = volumes.getOrNull(index) ?: return
        val (lo, ld) = PlacedFrame(obj.transform(), g.origin).rayToLocal(origin, dir)
        if (kotlin.math.abs(ld[2]) < 1e-6f) return
        val zPlane = v.center[2] + g.origin[2]
        val t = (zPlane - lo[2]) / ld[2]; if (t <= 0f) return
        val hitX = lo[0] + ld[0] * t - g.origin[0]; val hitY = lo[1] + ld[1] * t - g.origin[1]
        if (start || regionGrab == null) regionGrab = floatArrayOf(hitX - v.center[0], hitY - v.center[1])
        val grab = regionGrab ?: return
        val moved = v.copy(center = floatArrayOf(hitX - grab[0], hitY - grab[1], v.center[2]))
        scope.launch { vm.setVolumes(id, volumes.mapIndexed { i, x -> if (i == index) moved else x }) }
    }

    fun handleRayTap(origin: FloatArray, dir: FloatArray) {
        val mode = pickMode ?: return
        val hit = pickTriangle(origin, dir)
        if (hit == null) { toolMessage = "Tap directly on a model."; return }
        val (obj, mesh, h) = hit
        when (mode) {
            PickMode.FACE -> {
                pickMode = null; selectedId = obj.id
                scope.launch { vm.replaceObjectMesh(obj.id, MeshEdit.layOnFace(mesh, MeshEdit.triangleNormal(mesh, h.triangle))); toolMessage = "Laid the tapped face flat on the bed." }
            }
            PickMode.PAINT, PickMode.REGION -> {}
            PickMode.MEASURE -> {
                val world = PlacedFrame(obj.transform(), geometry.getValue(obj.id).origin).toWorld(h.point)
                val a = measurePointA
                if (a == null) { measurePointA = world; toolMessage = "Point A set - tap point B." }
                else {
                    val d = MeshEdit.distance(a, world)
                    toolMessage = "Distance %.2f mm  (Δx %.2f, Δy %.2f, Δz %.2f)".format(d, kotlin.math.abs(world[0] - a[0]), kotlin.math.abs(world[1] - a[1]), kotlin.math.abs(world[2] - a[2]))
                    measurePointA = null
                }
            }
        }
    }

    // Slices one plate's objects; shared by "Slice" (the active plate) and "Slice all plates". [tag] keeps the
    // output next to other plates' results instead of replacing them.
    suspend fun sliceOnePlate(plateObjects: List<ProjectObject>, tag: String?): SliceOutcome {
        val infill = validateInfillPercent(infillText) ?: return SliceOutcome.Failed("Enter an infill percentage between 0 and 100.")
        val target = profile ?: return SliceOutcome.Failed("Select a printer first.")
        val basicSettings = BasicSliceSettings(quality, infill, supportMode, adhesionBrim)
        // AUTO support consults every object on this plate, since the whole plate slices together.
        val needsSupport = plateObjects.any { obj -> geometry[obj.id]?.let { meshNeedsSupport(it) } == true }
        // Kept as triples so a dropped (unparseable-URI) object can never desync the file list from the per-object
        // tool assignments and extras below.
        val slicableObjects = plateObjects.mapNotNull { obj -> Uri.parse(obj.sourceFileUri).path?.let { path -> Triple(obj, File(path), obj.transform()) } }
        val objectsToSlice = slicableObjects.map { (_, file, transform) -> file to transform }
        val (toolSlotIndices, slotMaterials) = multiToolSliceInputsFor(slicableObjects.map { (obj, _, _) -> obj }, toolCount)
        // The project's material overrides temperatures on top of (not instead of) the basic settings; skipped for a
        // multi-tool target, where slotMaterials carries each object's own material.
        val overrides = basicSettings.toOverrides(needsSupport) + (if (toolCount > 1) emptyMap() else (vm.currentMaterial()?.toOverrides() ?: emptyMap())) + SettingsCatalog.sanitize(advancedOverrides) + (CalibrationSpec.decode(project?.calibration)?.let(Calibration::overrides) ?: emptyMap())
        val outcome = SlicingCoordinator.sliceProject(context.applicationContext, objectsToSlice, target, overrides, toolSlotIndices, slotMaterials,
            slicableObjects.map { (obj, _, _) -> ObjectExtrasText(obj.paintJson.orEmpty(), obj.volumesJson.orEmpty()) }, outputTag = tag)
        if (outcome is SliceOutcome.Success) {
            // Calibration towers change a machine setting with height: patch the sliced plain G-code (not a Bambu bundle).
            val cal = CalibrationSpec.decode(project?.calibration)
            if (cal != null && target.kind != PrinterKind.BAMBU_LAB) withContext(Dispatchers.IO) { Calibration.applyToFile(outcome.gcode, cal) }
        }
        return outcome
    }

    fun validateBeforeSlicing(): Boolean {
        val infill = validateInfillPercent(infillText)
        if (infill == null) { customizeError = "Enter an infill percentage between 0 and 100."; return false }
        // A blocking issue (a real machine limit) stops slicing outright; a non-blocking one is shown but allowed.
        val blockingIssue = validateSliceConfiguration(machineLimits, quality.layerHeightMm, filamentRange, vm.currentMaterial()).firstOrNull { it.blocking }
        if (blockingIssue != null) { customizeError = blockingIssue.message; return false }
        customizeError = null
        sliced = null; sliceError = null; stagedFilename = null; plateResults = emptyMap()
        stage = ProjectEditorStage.SLICING
        return true
    }

    fun startSlicing() {
        if (!validateBeforeSlicing()) return
        scope.launch {
            working = true; sliceStageLabel = "Slicing…"
            when (val outcome = sliceOnePlate(objects, null)) {
                is SliceOutcome.Success -> { sliced = outcome.gcode; working = false }
                is SliceOutcome.FirmwareBlocked -> { working = false; sliceError = outcome.reason }
                is SliceOutcome.Failed -> { working = false; sliceError = outcome.message }
                SliceOutcome.Cancelled -> { working = false; stage = ProjectEditorStage.EDIT }
            }
        }
    }

    // Slices every plate that has objects, one after another, keeping each plate's result; the review then lets the
    // owner pick which plate's result to look at and print.
    fun startSlicingAllPlates() {
        if (!validateBeforeSlicing()) return
        val originalPlate = activePlateId
        scope.launch {
            working = true; sliceStageLabel = "Slicing…"
            val results = LinkedHashMap<String, File>()
            withContext(Dispatchers.IO) { SlicingCoordinator.clearOutputs(context.applicationContext) }
            val queue = plates.filter { p -> vm.allObjects.value.any { vm.plateIdOf(it) == p.id } }
            for ((i, plate) in queue.withIndex()) {
                vm.selectPlate(plate.id)
                sliceStageLabel = "Slicing…"
                plateProgressLabel = "${plate.name} (${i + 1}/${queue.size})"
                when (val outcome = sliceOnePlate(vm.objects.value, plate.id)) {
                    is SliceOutcome.Success -> results[plate.id] = outcome.gcode
                    is SliceOutcome.FirmwareBlocked -> { working = false; sliceError = "${plate.name}: ${outcome.reason}"; plateProgressLabel = null; return@launch }
                    is SliceOutcome.Failed -> { working = false; sliceError = "${plate.name}: ${outcome.message}"; plateProgressLabel = null; return@launch }
                    SliceOutcome.Cancelled -> { working = false; plateProgressLabel = null; stage = ProjectEditorStage.EDIT; return@launch }
                }
            }
            plateProgressLabel = null
            originalPlate?.let { vm.selectPlate(it) }
            plateResults = results
            sliced = results.values.firstOrNull(); working = false
        }
    }

    // Phase 6 follow-up (WO-23): mirrors SliceAndPrintPanel.kt's own bambuTarget/prusaTarget
    // flags - a Bambu Lab target's `sliced` is a real .gcode.3mf bundle (zip), not plain
    // G-code (see SlicingCoordinator.sliceProject()'s own branch), and neither Bambu nor Prusa
    // Link need this screen's Moonraker-only LiveFileChanges upload step below.
    val bambuTarget = profile?.kind == PrinterKind.BAMBU_LAB
    val prusaTarget = profile?.kind == PrinterKind.PRUSA_LINK
    // Toolpath + stats parsing, off the main thread - a parse failure doesn't block printing,
    // the review is a visualization aid, not a correctness gate (matches SliceAndPrintPanel's
    // own convention).
    LaunchedEffect(sliced) {
        val gcode = sliced ?: return@LaunchedEffect
        toolpathError = null
        try {
            val plainGcode = withContext(Dispatchers.IO) { if (bambuTarget) extractBambuBundleGcode(context.applicationContext, gcode) else gcode }
            slicedToolpath = withContext(Dispatchers.Default) { plainGcode.inputStream().buffered().use { GcodePreview.parse(it) } }
            gcodeStats = runCatching { withContext(Dispatchers.Default) { GcodeStatsParser.parse(plainGcode) } }.getOrNull()
        } catch (e: Exception) { toolpathError = e.message ?: "Could not build a layer preview of the sliced G-code." }
    }

    // Upload runs once the owner has reviewed the sliced layers and confirmed the printer is
    // ready - the same two-gate sequence SliceAndPrintPanel already uses before touching the
    // network with a real file write. Bambu Lab and Prusa Link skip this entirely - both real
    // upload+print through one single command below (BambuPrinterService.startPrint,
    // PrusaLinkPrinterService.uploadAndPrint), not a separate Moonraker-shaped upload step.
    LaunchedEffect(sliced, stage, state.connected) {
        val gcode = sliced ?: return@LaunchedEffect
        if (stage != ProjectEditorStage.STAGED) return@LaunchedEffect
        val target = profile ?: return@LaunchedEffect
        if (bambuTarget || prusaTarget) { stagedFilename = gcode.name; return@LaunchedEffect }
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
                Column(Modifier.weight(1f)) {
                    Text(project?.name ?: "Project", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("project-editor-title"))
                    // Credit for a model downloaded from MyMiniFactory (their guidelines require attribution).
                    project?.attribution?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("project-attribution")) }
                }
                // Mid-slice (SLICING/PRINTER_READY/STAGED), this backs out to Edit instead of
                // closing the whole screen outright - a real sliced-but-not-yet-started result
                // shouldn't be one tap from losing the review entirely.
                IconButton({ if (stage == ProjectEditorStage.EDIT) close() else { if (working && sliceStageLabel == "Slicing…") SlicingCoordinator.cancel(); stage = ProjectEditorStage.EDIT } }, Modifier.testTag("project-editor-close")) {
                    CompanionIcon(CompanionSymbol.CLOSE)
                }
            }
            when {
                !ready -> Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator() }
                loadError != null -> Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) { Text(loadError!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("project-editor-error")) }
                stage == ProjectEditorStage.EDIT -> Column(Modifier.weight(1f).fillMaxWidth()) {
                    val allWorkspaceObjects = objects.mapNotNull { obj -> geometry[obj.id]?.let { WorkspaceObject(obj, it, overlays[obj.id] ?: emptyList()) } }
                    // WO-30: a hidden object never reaches ProjectWorkspace at all - it can't be
                    // rendered, picked, or counted as colliding while hidden. If the currently
                    // selected object is the one just hidden, the selection is cleared too (below,
                    // where hiddenIds is toggled) so the toolbar's per-selection buttons don't sit
                    // enabled against an object nothing on screen can point back to.
                    val workspaceObjects = allWorkspaceObjects.filter { it.projectObject.id !in hiddenIds }
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
                    // Computed once, above the tabs, so the persistent Slice button's own enabled
                    // check below is correct regardless of which tab happens to be showing.
                    val autoNeedsSupport = objects.any { obj -> geometry[obj.id]?.let { meshNeedsSupport(it) } == true }
                    // Phase 5 (§16): real slice-time validation, shown live (not only after
                    // tapping Slice) so the owner sees a problem while still adjusting settings.
                    val validationIssues = validateSliceConfiguration(machineLimits, quality.layerHeightMm, filamentRange, currentMaterial)
                    // Phase 2 real bug fix, still real: this screen's own upload step (LiveFileChanges/
                    // Moonraker.start, below) always assumed Moonraker unconditionally - Prusa Link
                    // would silently fail that way. Phase 6 follow-up (WO-23): SlicingCoordinator.
                    // sliceProject() now has a real Bambu-bundle branch of its own
                    // (nativeSliceMultiObjectBambuBundle) and PrusaLinkPrinterService has a real
                    // generic upload+print endpoint, so every printer kind's own
                    // acceptsOnDeviceSlicedGcode is trusted directly here now, same as
                    // SliceAndPrintPanel.kt.
                    val acceptsSlicedGcode = profile?.let { capabilitiesFor(it.kind).acceptsOnDeviceSlicedGcode } ?: true

                    TabRow(editorTab) {
                        Tab(editorTab == 0, { editorTab = 0 }, text = { Text("Model") }, modifier = Modifier.testTag("project-tab-model"))
                        Tab(editorTab == 1, { editorTab = 1 }, text = { Text("Settings") }, modifier = Modifier.testTag("project-tab-settings"))
                        Tab(editorTab == 2, { editorTab = 2 }, text = { Text("Printer") }, modifier = Modifier.testTag("project-tab-printer"))
                    }
                    Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (editorTab) {
                            0 -> {
                                // WO-30 (owner request, 2026-09-23 - "Desktop-like power adapted
                                // to mobile", real EasyPrint-style reference screenshots, later
                                // "make these our own, do not blatantly copy them"): a left-hand
                                // plate toolbar next to the real 3D workspace, grouped into one
                                // real panel (Surface, not floating loose over the background -
                                // owner: "the icons are too light and hard to read" /
                                // "overall visual design") - Select is already implicit in
                                // ProjectWorkspace's own tap-to-select/drag-to-transform gestures
                                // (no separate mode toggle needed), so this toolbar covers what
                                // wasn't already reachable: Duplicate/Remove (moved here from the
                                // old flat button row), Hide (new), Reset transform (new), and
                                // Auto Layout (renamed from "Auto-arrange", same real
                                // runAutoArrange() underneath).
                                exportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("project-export-message")) }
                                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().testTag("project-plates"), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    plates.forEachIndexed { index, plate ->
                                        FilterChip(plate.id == activePlateId, { selectedId = null; vm.selectPlate(plate.id) }, label = { Text(plate.name) }, modifier = Modifier.testTag("project-plate-$index"))
                                    }
                                    OutlinedButton({ scope.launch { selectedId = null; vm.addPlate() } }, modifier = Modifier.testTag("project-plate-add")) { Text("+ Plate") }
                                    OutlinedButton({ exportLauncher.launch("${project?.name ?: "project"}.nozzleproj") }, enabled = objects.isNotEmpty() || plates.size > 1, modifier = Modifier.testTag("project-export")) { Text("Export") }
                                    if (plates.size > 1) OutlinedButton({ activePlateId?.let { id -> scope.launch { vm.removePlate(id) } } }, enabled = objects.isEmpty(), modifier = Modifier.testTag("project-plate-remove")) { Text("Remove plate") }
                                }
                                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Column(Modifier.testTag("project-toolbar"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            // WO-30 follow-up: Move/Rotate is a persistent
                                            // interaction-mode toggle (which one is "on" changes
                                            // what a one-finger drag on the workspace below does),
                                            // not a momentary action like the buttons under it -
                                            // `active` gives each a highlighted background so
                                            // that's visible at a glance, always enabled (no
                                            // selection required, unlike the object-specific
                                            // actions below).
                                            PlateToolbarButton("Move", CompanionSymbol.MOVE, enabled = true, active = interactionMode == WorkspaceInteractionMode.MOVE, testTag = "project-mode-move") {
                                                interactionMode = WorkspaceInteractionMode.MOVE
                                            }
                                            PlateToolbarButton("Rotate", CompanionSymbol.ROTATE, enabled = true, active = interactionMode == WorkspaceInteractionMode.ROTATE, testTag = "project-mode-rotate") {
                                                interactionMode = WorkspaceInteractionMode.ROTATE
                                            }
                                            PlateToolbarButton("Duplicate", CompanionSymbol.DUPLICATE, enabled = selectedId != null, testTag = "project-duplicate-object") {
                                                selectedId?.let { id -> scope.launch { selectedId = vm.duplicateObject(id)?.id } }
                                            }
                                            PlateToolbarButton("Hide", CompanionSymbol.HIDE, enabled = selectedId != null, testTag = "project-hide-object") {
                                                selectedId?.let { id -> hiddenIds = hiddenIds + id; selectedId = null }
                                            }
                                            PlateToolbarButton("Reset", CompanionSymbol.RESET, enabled = selectedId != null, testTag = "project-reset-object") {
                                                selectedId?.let { id -> scope.launch { vm.updateObjectTransform(id, ModelTransform()) } }
                                            }
                                            PlateToolbarButton("Remove", CompanionSymbol.CLOSE, enabled = selectedId != null, testTag = "project-remove-object") {
                                                selectedId?.let { id -> scope.launch { vm.removeObject(id); selectedId = null } }
                                            }
                                            Box {
                                                PlateToolbarButton("To plate", CompanionSymbol.NEXT, enabled = selectedId != null && plates.size > 1, testTag = "project-move-to-plate") { moveMenuOpen = true }
                                                DropdownMenu(moveMenuOpen, { moveMenuOpen = false }) {
                                                    plates.filter { it.id != activePlateId }.forEach { plate ->
                                                        DropdownMenuItem({ Text(plate.name) }, { moveMenuOpen = false; selectedId?.let { id -> scope.launch { vm.moveObjectToPlate(id, plate.id); selectedId = null } } }, modifier = Modifier.testTag("project-move-to-${plate.name}"))
                                                    }
                                                }
                                            }
                                            PlateToolbarButton("Undo", CompanionSymbol.UNDO, enabled = undoState.first, testTag = "project-undo") { scope.launch { vm.undo(); selectedId = selectedId?.takeIf { id -> vm.objects.value.any { it.id == id } } } }
                                            PlateToolbarButton("Redo", CompanionSymbol.REDO, enabled = undoState.second, testTag = "project-redo") { scope.launch { vm.redo(); selectedId = selectedId?.takeIf { id -> vm.objects.value.any { it.id == id } } } }
                                            PlateToolbarButton("Layout", CompanionSymbol.LAYOUT, enabled = objects.size > 1, testTag = "project-auto-arrange") {
                                                scope.launch { runAutoArrange() }
                                            }
                                        }
                                        Column(Modifier.weight(1f)) {
                                            ProjectWorkspace(
                                                objects = workspaceObjects,
                                                selectedId = selectedId,
                                                onSelect = { selectedId = it },
                                                onTransformChange = { id, transform -> scope.launch { vm.updateObjectTransform(id, transform) } },
                                                bedShape = bedShape,
                                                collidingIds = collidingIds,
                                                interactionMode = interactionMode,
                                                onRayTap = if (pickMode == PickMode.FACE || pickMode == PickMode.MEASURE) { o, d -> handleRayTap(o, d) } else null,
                                                onRayDrag = when (pickMode) { PickMode.PAINT -> { o, d, start -> handlePaintDrag(o, d, start) }; PickMode.REGION -> { o, d, start -> handleRegionDrag(o, d, start) }; else -> null },
                                            )
                                            // WO-30: the selected object's real, current bounding
                                            // box in mm - (max-min) per axis on its loaded
                                            // geometry, scaled by its own live transform.scale
                                            // (the same scale ProjectWorkspace's own drag-to-
                                            // scale gesture already writes) - not a static
                                            // "model size" read once at import, so this stays
                                            // correct after a Scale-mode drag or a Reset.
                                            val selectedWorkspaceObject = workspaceObjects.find { it.projectObject.id == selectedId }
                                            val dimensionsText = selectedWorkspaceObject?.let { wo ->
                                                val t = wo.projectObject.transform()
                                                val g = wo.geometry
                                                "%.1f x %.1f x %.1f mm".format((g.maxX - g.minX) * t.scale, (g.maxY - g.minY) * t.scale, (g.maxZ - g.minZ) * t.scale)
                                            } ?: "Select an object to see its size"
                                            Text(dimensionsText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp).testTag("project-object-dimensions"))
                                            // WO-30: "Models N/N" - cycles selection through the
                                            // plate's own visible object order (the same order
                                            // the "Objects on this plate" list below renders),
                                            // matching the reference's own model-switcher
                                            // affordance rather than requiring a tap on the 3D
                                            // view or the list.
                                            if (workspaceObjects.isNotEmpty()) {
                                                val currentIndex = workspaceObjects.indexOfFirst { it.projectObject.id == selectedId }
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    IconButton({
                                                        val i = if (currentIndex <= 0) workspaceObjects.size - 1 else currentIndex - 1
                                                        selectedId = workspaceObjects[i].projectObject.id
                                                    }, Modifier.testTag("project-model-prev")) { CompanionIcon(CompanionSymbol.PREV, color = MaterialTheme.colorScheme.onSurface) }
                                                    Text("Models ${if (currentIndex >= 0) currentIndex + 1 else 0}/${workspaceObjects.size}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("project-model-index"))
                                                    IconButton({
                                                        val i = if (currentIndex < 0 || currentIndex >= workspaceObjects.size - 1) 0 else currentIndex + 1
                                                        selectedId = workspaceObjects[i].projectObject.id
                                                    }, Modifier.testTag("project-model-next")) { CompanionIcon(CompanionSymbol.NEXT, color = MaterialTheme.colorScheme.onSurface) }
                                                }
                                            }
                                        }
                                    }
                                }
                                val missingGeometryCount = objects.size - allWorkspaceObjects.size
                                if (missingGeometryCount > 0) Text("Loading $missingGeometryCount model(s)…", style = MaterialTheme.typography.bodySmall)
                                if (hiddenIds.isNotEmpty()) Text("${hiddenIds.size} object(s) hidden - still included when slicing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (collidingIds.isNotEmpty()) Text(
                                    "${collidingIds.size} object(s) overlap - move them apart before slicing, or use Layout.",
                                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("project-collision-warning"),
                                )

                                Button({ pickModel.launch(arrayOf("*/*")) }, modifier = Modifier.testTag("project-add-object")) { Text("Add models") }
                                // Phase 4 (§4/§16): "copies" - sets the selected object's total
                                // count on the plate at once (duplicating up or removing down to
                                // N), then resolves placement through the same real auto-arrange
                                // path above rather than leaving N copies stacked on top of
                                // each other.
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

                                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().testTag("project-edit-tools"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    PlateToolbarButton("Lay flat", CompanionSymbol.FLAT, enabled = objects.isNotEmpty(), active = pickMode == PickMode.FACE, testTag = "project-tool-face") {
                                        measurePointA = null; pickMode = if (pickMode == PickMode.FACE) null else PickMode.FACE
                                        toolMessage = if (pickMode == PickMode.FACE) "Tap the face you want on the bed." else null
                                    }
                                    PlateToolbarButton("Orient", CompanionSymbol.ORIENT, enabled = selectedId != null, testTag = "project-tool-orient") {
                                        val id = selectedId; val g = id?.let { geometry[it] }
                                        if (id != null && g != null) scope.launch {
                                            val mesh = MeshEdit.fromGeometry(g)
                                            val best = withContext(Dispatchers.Default) { MeshEdit.autoOrient(mesh) }
                                            if (best == null) toolMessage = "This orientation is already good."
                                            else { vm.replaceObjectMesh(id, MeshEdit.applyOrientation(mesh, best)); toolMessage = "Reoriented to reduce supports." }
                                        }
                                    }
                                    for ((label, axis) in listOf("Mirror X" to 0, "Mirror Y" to 1)) {
                                        PlateToolbarButton(label, CompanionSymbol.MIRROR, enabled = selectedId != null, testTag = "project-tool-mirror-${label.last().lowercaseChar()}") {
                                            val id = selectedId; val g = id?.let { geometry[it] }
                                            if (id != null && g != null) scope.launch { vm.replaceObjectMesh(id, MeshEdit.mirror(MeshEdit.fromGeometry(g), axis)); toolMessage = "$label applied (across the model's own axis)." }
                                        }
                                    }
                                    PlateToolbarButton("Cut", CompanionSymbol.CUT, enabled = selectedId != null, testTag = "project-tool-cut") { cutDialogFor = selectedId; cutFraction = 0.5f; keepUpper = true; keepLower = true }
                                    PlateToolbarButton("Paint", CompanionSymbol.PAINT, enabled = selectedId != null, active = pickMode == PickMode.PAINT, testTag = "project-tool-paint") {
                                        measurePointA = null; pickMode = if (pickMode == PickMode.PAINT) null else PickMode.PAINT
                                        toolMessage = if (pickMode == PickMode.PAINT) "Drag on the selected model to paint." else null
                                    }
                                    PlateToolbarButton("Measure", CompanionSymbol.MEASURE, enabled = objects.isNotEmpty(), active = pickMode == PickMode.MEASURE, testTag = "project-tool-measure") {
                                        measurePointA = null; pickMode = if (pickMode == PickMode.MEASURE) null else PickMode.MEASURE
                                        toolMessage = if (pickMode == PickMode.MEASURE) "Tap point A on a model." else null
                                    }
                                }
                                toolMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("project-tool-message")) }
                                if (pickMode == PickMode.PAINT) {
                                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("project-paint-controls")) {
                                        PaintKind.entries.filter { it != PaintKind.MATERIAL || toolCount > 1 }.forEach { k -> FilterChip(paintKind == k, { paintKind = k }, label = { Text(k.label) }, modifier = Modifier.testTag("project-paint-kind-${k.name.lowercase()}")) }
                                    }
                                    if (paintKind == PaintKind.MATERIAL) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("project-paint-tools")) {
                                        Text("Print painted areas with:", style = MaterialTheme.typography.bodySmall)
                                        (1..toolCount).forEach { t -> FilterChip(paintTool == t, { paintTool = t }, label = { Text("T$t") }, modifier = Modifier.testTag("project-paint-tool-$t")) }
                                    }
                                    Text("Brush %.1f mm".format(brushRadiusMm), style = MaterialTheme.typography.bodySmall)
                                    Slider(brushRadiusMm, { brushRadiusMm = it }, valueRange = 1f..15f, modifier = Modifier.testTag("project-paint-radius"))
                                    OutlinedButton({ selectedId?.let { id -> scope.launch { vm.clearPaint(id) } } }, enabled = selectedId != null && objects.find { it.id == selectedId }?.paintJson != null, modifier = Modifier.testTag("project-paint-clear")) { Text("Clear paint on this model") }
                                }
                                selectedId?.let { id ->
                                    val obj = objects.find { it.id == id }
                                    val volumes = VolumeCodec.decode(obj?.volumesJson)
                                    val g = geometry[id]
                                    Text("Regions on this model", style = MaterialTheme.typography.titleSmall)
                                    volumes.forEachIndexed { i, v ->
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("project-region-$i")) {
                                            Text("${v.kind.label} · ${v.shape.label} ${v.size[0].toInt()}×${v.size[1].toInt()}×${v.size[2].toInt()} mm" + if (v.overrides.isNotEmpty()) " · ${v.overrides.size} setting(s)" else "", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                            TextButton({
                                                val c = g?.let { floatArrayOf(it.center[0] - it.origin[0], it.center[1] - it.origin[1], it.center[2] - it.origin[2]) } ?: floatArrayOf(0f, 0f, 0f)
                                                regionDialog = RegionDraft(i, v.kind, v.shape, fmtMm(v.center[0] - c[0]), fmtMm(v.center[1] - c[1]), fmtMm(v.center[2] - c[2]), fmtMm(v.size[0]), fmtMm(v.size[1]), fmtMm(v.size[2]),
                                                    v.overrides["sparse_infill_density"]?.removeSuffix("%").orEmpty(), v.overrides["wall_loops"].orEmpty())
                                            }, modifier = Modifier.testTag("project-region-edit-$i")) { Text("Edit") }
                                            TextButton({
                                                regionGrab = null
                                                if (pickMode == PickMode.REGION && movingRegion == i) { pickMode = null; movingRegion = null; toolMessage = null }
                                                else { pickMode = PickMode.REGION; movingRegion = i; toolMessage = "Drag on the model to move this region." }
                                            }, modifier = Modifier.testTag("project-region-move-$i")) { Text(if (pickMode == PickMode.REGION && movingRegion == i) "Done" else "Move") }
                                            for ((label, factor, tag) in listOf(Triple("−", 1f / 1.2f, "shrink"), Triple("+", 1.2f, "grow"))) {
                                                TextButton({
                                                    val resized = v.copy(size = floatArrayOf((v.size[0] * factor).coerceIn(0.5f, 1000f), (v.size[1] * factor).coerceIn(0.5f, 1000f), (v.size[2] * factor).coerceIn(0.5f, 1000f)))
                                                    scope.launch { vm.setVolumes(id, volumes.mapIndexed { j, x -> if (j == i) resized else x }) }
                                                }, modifier = Modifier.testTag("project-region-$tag-$i")) { Text(label) }
                                            }
                                            TextButton({ if (movingRegion == i) { pickMode = null; movingRegion = null }; scope.launch { vm.setVolumes(id, volumes.filterIndexed { j, _ -> j != i }) } }, modifier = Modifier.testTag("project-region-delete-$i")) { Text("Delete") }
                                        }
                                    }
                                    OutlinedButton({ regionDialog = RegionDraft(-1) }, enabled = g != null && volumes.size < VolumeCodec.MAX_VOLUMES, modifier = Modifier.testTag("project-region-add")) { Text("+ Add region") }
                                }
                                Text("Objects on this plate", style = MaterialTheme.typography.titleSmall)
                                if (objects.isEmpty()) Text("No objects yet - add an STL, 3MF or OBJ model to start this project's build plate.", style = MaterialTheme.typography.bodySmall)
                                objects.forEach { obj ->
                                    ProjectObjectRow(obj, selected = obj.id == selectedId,
                                        // Selecting a hidden row un-hides it too - a "Select" tap
                                        // that quietly did nothing visible on the workspace above
                                        // would be a confusing dead end, not a real toggle.
                                        onClick = { hiddenIds = hiddenIds - obj.id; selectedId = obj.id },
                                        hidden = obj.id in hiddenIds,
                                        onToggleHidden = { hiddenIds = if (obj.id in hiddenIds) hiddenIds - obj.id else hiddenIds + obj.id; if (selectedId == obj.id) selectedId = null },
                                        // toolCount > 1 (§11, WO-28): real per-object assignment -
                                        // each row shows its own material + tool slot and opens
                                        // the per-object picker, instead of the single project-
                                        // wide row below (mutually exclusive, matching the real
                                        // capability the target printer actually has).
                                        toolAssignment = if (toolCount > 1) (obj.material()?.displayName ?: "No material") + " · Tool ${obj.toolSlotIndex ?: 1}" else null,
                                        onAssign = if (toolCount > 1) { { perObjectPickerFor = obj.id } } else null)
                                }

                                if (toolCount <= 1) {
                                    Text("Material", style = MaterialTheme.typography.titleSmall)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(currentMaterial?.displayName ?: "None selected - using the printer's default profile", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).testTag("project-material-current"))
                                        OutlinedButton({ materialPickerOpen = true }, enabled = objects.isNotEmpty(), modifier = Modifier.testTag("project-choose-material")) { Text("Choose") }
                                    }
                                } else {
                                    // Real multi-tool target (§11, WO-25/26/27/28): no single
                                    // project-wide material makes sense here - each object above
                                    // already carries its own real material + tool assignment,
                                    // tap "Assign" on a row to change it.
                                    Text("$toolCount real tool slots on ${profile?.label ?: "this printer"} - tap \"Assign\" on an object above to pick its material and tool.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("project-multitool-hint"))
                                    val family = multiToolFamily(profile?.slicingModel, toolCount)
                                    val assigned = objects.mapNotNull { it.material() }
                                    val toolWarnings = MaterialCompatibility.warnings(assigned, family)
                                    val primeTowerOn = advancedOverrides["enable_prime_tower"]?.let { it == "1" }
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("project-multimaterial")) {
                                        Text(family.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("project-multimaterial-family"))
                                        Text(family.explanation, style = MaterialTheme.typography.bodySmall)
                                        if (family == MultiToolFamily.FILAMENT_SWAP) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Switch(primeTowerOn ?: true, { advancedOverrides = advancedOverrides + ("enable_prime_tower" to if (it) "1" else "0") }, modifier = Modifier.testTag("project-prime-tower"))
                                                Text(" Prime tower" + if (primeTowerOn == null) " (profile default)" else "", style = MaterialTheme.typography.bodyMedium)
                                            }
                                            Text("Flush amount and where to flush: Settings > Multi-material.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        } else {
                                            Text("No prime tower or flush settings here: this engine does not purge on independent-tool machines (checked on the Snapmaker U1 profile - turning the tower on changes nothing).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("project-no-purge-note"))
                                        }
                                        toolWarnings.forEachIndexed { k, w -> Text("⚠ $w", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.testTag("project-multimaterial-warning-$k")) }
                                    }
                                }
                            }
                            1 -> {
                                Text("Quality", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    QualityPreset.entries.forEach { preset ->
                                        val on = quality == preset
                                        Surface(
                                            selected = on, onClick = { quality = preset }, shape = MaterialTheme.shapes.small,
                                            color = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                                            border = androidx.compose.foundation.BorderStroke(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                            modifier = Modifier.weight(1f).testTag("project-quality-${preset.name}"),
                                        ) {
                                            Column(Modifier.padding(vertical = 10.dp, horizontal = 6.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(preset.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                                Text("${preset.layerHeightMm}mm", style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                                OutlinedTextField(infillText, { infillText = it }, label = { Text("Strength - infill (%)") }, singleLine = true, modifier = Modifier.testTag("project-infill"))
                                // Real geometry-driven default (Phase 4's own "intelligent
                                // defaulting"): Auto reads whether any object on the plate
                                // actually has a real overhang (meshNeedsSupport) rather than
                                // asking the owner to already know.
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
                                AdvancedSettingsPanel(advancedOverrides, profile?.slicingModel?.name ?: "", multiToolFamily(profile?.slicingModel, toolCount), { advancedOverrides = it })
                                validationIssues.forEach { issue ->
                                    Text(
                                        issue.message,
                                        color = if (issue.blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.testTag(if (issue.blocking) "project-validation-error" else "project-validation-warning"),
                                    )
                                }
                                customizeError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            }
                            2 -> {
                                if (profile != null) {
                                    Text(profile.label, style = MaterialTheme.typography.titleSmall)
                                    Text(state.address, style = MaterialTheme.typography.bodySmall)
                                    Text("Slicing always targets whichever printer is currently selected on the Home tab - there's no separate picker here.", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Text("Select a printer on the Home tab first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (profile != null && !acceptsSlicedGcode) Text(
                                    "On-device slicing isn't wired up yet for ${profile.label} - its printer type needs an upload/print path this app doesn't implement.",
                                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("project-slice-unsupported-printer"),
                                )
                            }
                        }
                    }
                    if (plates.count { p -> allProjectObjects.any { vm.plateIdOf(it) == p.id } } > 1) OutlinedButton({ startSlicingAllPlates() }, enabled = profile != null && acceptsSlicedGcode && collidingIds.isEmpty(), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("project-slice-all")) { Text("Slice all plates") }
                    Button({ startSlicing() }, enabled = objects.isNotEmpty() && profile != null && collidingIds.isEmpty() && acceptsSlicedGcode && validationIssues.none { it.blocking }, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("project-slice")) { Text(if (plates.size > 1) "Slice ${plates.firstOrNull { it.id == activePlateId }?.name ?: "plate"}" else "Slice") }
                }
                stage == ProjectEditorStage.SLICING && sliced == null -> {
                    Column(Modifier.weight(1f).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (working) {
                            if (sliceStageLabel == "Slicing…") {
                                LinearProgressIndicator(progress = { sliceProgress / 100f }, modifier = Modifier.fillMaxWidth().testTag("project-slice-progress"))
                                Text("$sliceStageLabel $sliceProgress%" + (plateProgressLabel?.let { " · $it" } ?: ""), modifier = Modifier.testTag("project-slice-status"))
                                OutlinedButton({ SlicingCoordinator.cancel() }, modifier = Modifier.testTag("project-slice-cancel")) { Text("Cancel") }
                            } else { CircularProgressIndicator(); Text(sliceStageLabel) }
                        }
                        sliceError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("project-slice-error")) }
                    }
                }
                stage == ProjectEditorStage.SLICING -> {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sliced result", style = MaterialTheme.typography.titleSmall)
                        if (plateResults.size > 1) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("project-plate-results")) {
                            plates.filter { it.id in plateResults }.forEachIndexed { i, p ->
                                FilterChip(sliced == plateResults[p.id], { sliced = plateResults[p.id]; stagedFilename = null }, label = { Text(p.name) }, modifier = Modifier.testTag("project-plate-result-$i"))
                            }
                        }
                        gcodeStats?.let { s ->
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("project-slice-stats")) {
                                s.printTime?.let { Text(it) }
                                s.filamentUsedGrams?.let { Text("${it}g") }
                                s.filamentUsedMm?.let { Text("${(it / 1000).let { m -> "%.2f".format(m) }}m") }
                            }
                        }
                        gcodeStats?.takeIf { (it.toolchanges ?: 0) > 0 || it.toolsUsed.size > 1 }?.let { st ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.testTag("project-slice-multimaterial")) {
                                Text("Toolchanges: ${st.toolchanges ?: slicedToolpath?.toolChanges?.size ?: 0}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("project-slice-toolchanges"))
                                Text(st.toolsUsed.joinToString("  ·  ") { t -> "T${t + 1} ${"%.1f".format(st.perToolGrams.getOrElse(t) { 0.0 })} g" }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("project-slice-pertool"))
                                st.estimatedPurgeGrams()?.takeIf { multiToolFamily(profile?.slicingModel, toolCount) != MultiToolFamily.TOOLCHANGER }?.let { Text("Estimated purge waste: ${"%.1f".format(it)} g (estimate)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("project-slice-purge")) }
                                st.purgeNote(multiToolFamily(profile?.slicingModel, toolCount))?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.testTag("project-slice-purge-note")) }
                            }
                        }
                        val toolMaterials = (1..toolCount.coerceAtLeast(1)).map { slot -> objects.firstOrNull { (it.toolSlotIndex ?: 1) == slot }?.material() }
                        slicedToolpath?.let { SlicedPreview(it, materialColorHex = currentMaterial?.colorHex, toolColorHexes = toolMaterials.mapIndexed { i, m -> toolColorHex(i, m) }, toolLabels = toolMaterials.mapIndexed { i, m -> m?.displayName ?: "Tool ${i + 1}" }) } ?: toolpathError?.let { Text(it, color = MaterialTheme.colorScheme.error) } ?: Text("Building layer preview…")
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
                        when {
                            bambuTarget -> {
                                val bundle = sliced ?: return@Button
                                execute(PrinterCommand("Print $filename", "", bambuPrintRequest = BambuPrintRequest(bundle, filename),
                                    allowedStates = setOf("standby", "complete", "cancelled", "error")), state.generation)
                            }
                            prusaTarget -> {
                                val gcode = sliced ?: return@Button
                                execute(PrinterCommand("Print $filename", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(gcode, filename),
                                    allowedStates = setOf("standby", "complete", "cancelled", "error")), state.generation)
                            }
                            else -> execute(Moonraker.start(filename), state.generation)
                        }
                        close()
                    }, enabled = !working && sliceError == null && stagedFilename != null, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("project-slice-and-print-confirm")) { Text("Start print") }
                }
            }
        }
    }
    regionDialog?.let { draft ->
        val id = selectedId; val g = id?.let { geometry[it] }
        if (id == null || g == null) { regionDialog = null } else {
            fun num(t: String) = t.trim().toFloatOrNull()?.takeIf { it.isFinite() }
            val sizes = listOf(num(draft.sx), num(draft.sy), num(draft.sz))
            val pos = listOf(num(draft.x), num(draft.y), num(draft.z))
            val infill = draft.infill.trim().takeIf { it.isNotEmpty() }?.let { it.toIntOrNull()?.takeIf { v -> v in 0..100 } ?: -1 }
            val walls = draft.walls.trim().takeIf { it.isNotEmpty() }?.let { it.toIntOrNull()?.takeIf { v -> v in 1..20 } ?: -1 }
            val valid = sizes.all { it != null && it > 0.1f && it < 2000f } && pos.all { it != null && kotlin.math.abs(it) < 2000f } && infill != -1 && walls != -1 &&
                (draft.kind != VolumeKind.MODIFIER || infill != null || walls != null)
            AlertDialog(onDismissRequest = { regionDialog = null }, title = { Text(if (draft.index < 0) "Add region" else "Edit region") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            VolumeKind.entries.forEach { k -> FilterChip(draft.kind == k, { regionDialog = draft.copy(kind = k) }, label = { Text(k.label) }, modifier = Modifier.testTag("region-kind-${k.code}")) }
                        }
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            VolumeShape.entries.forEach { sh -> FilterChip(draft.shape == sh, { regionDialog = draft.copy(shape = sh) }, label = { Text(sh.label) }, modifier = Modifier.testTag("region-shape-${sh.code}")) }
                        }
                        Text("Position from the model's centre (mm)", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(draft.x, { regionDialog = draft.copy(x = it) }, label = { Text("X") }, singleLine = true, modifier = Modifier.weight(1f).testTag("region-x"))
                            OutlinedTextField(draft.y, { regionDialog = draft.copy(y = it) }, label = { Text("Y") }, singleLine = true, modifier = Modifier.weight(1f).testTag("region-y"))
                            OutlinedTextField(draft.z, { regionDialog = draft.copy(z = it) }, label = { Text("Z") }, singleLine = true, modifier = Modifier.weight(1f).testTag("region-z"))
                        }
                        Text("Size (mm)", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(draft.sx, { regionDialog = draft.copy(sx = it) }, label = { Text("W") }, singleLine = true, modifier = Modifier.weight(1f).testTag("region-sx"))
                            OutlinedTextField(draft.sy, { regionDialog = draft.copy(sy = it) }, label = { Text("D") }, singleLine = true, modifier = Modifier.weight(1f).testTag("region-sy"))
                            OutlinedTextField(draft.sz, { regionDialog = draft.copy(sz = it) }, label = { Text("H") }, singleLine = true, modifier = Modifier.weight(1f).testTag("region-sz"))
                        }
                        if (draft.kind == VolumeKind.MODIFIER) {
                            Text("Settings inside the region (leave blank to keep)", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(draft.infill, { regionDialog = draft.copy(infill = it) }, label = { Text("Infill %") }, singleLine = true, isError = infill == -1, modifier = Modifier.weight(1f).testTag("region-infill"))
                                OutlinedTextField(draft.walls, { regionDialog = draft.copy(walls = it) }, label = { Text("Walls") }, singleLine = true, isError = walls == -1, modifier = Modifier.weight(1f).testTag("region-walls"))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton({
                        val current = VolumeCodec.decode(objects.find { it.id == id }?.volumesJson)
                        val centerOffset = floatArrayOf(g.center[0] - g.origin[0] + pos[0]!!, g.center[1] - g.origin[1] + pos[1]!!, g.center[2] - g.origin[2] + pos[2]!!)
                        val overrides = if (draft.kind == VolumeKind.MODIFIER) buildMap { infill?.let { put("sparse_infill_density", "$it%") }; walls?.let { put("wall_loops", "$it") } } else emptyMap()
                        val volume = ShapeVolume(draft.kind, draft.shape, centerOffset, floatArrayOf(sizes[0]!!, sizes[1]!!, sizes[2]!!), overrides)
                        val updated = if (draft.index in current.indices) current.mapIndexed { i, v -> if (i == draft.index) volume else v } else current + volume
                        regionDialog = null
                        scope.launch { vm.setVolumes(id, updated) }
                    }, enabled = valid, modifier = Modifier.testTag("region-save")) { Text("Save") }
                },
                dismissButton = { TextButton({ regionDialog = null }) { Text("Cancel") } })
        }
    }

    cutDialogFor?.let { id ->
        val g = geometry[id]; val obj = objects.find { it.id == id }
        if (g == null || obj == null) { cutDialogFor = null } else {
            val heightMm = (g.maxZ - g.minZ) * obj.scale
            AlertDialog(onDismissRequest = { cutDialogFor = null }, title = { Text("Cut model") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Cut at %.1f mm of %.1f mm".format(cutFraction * heightMm, heightMm), modifier = Modifier.testTag("project-cut-readout"))
                        Slider(cutFraction, { cutFraction = it }, valueRange = 0.02f..0.98f, modifier = Modifier.testTag("project-cut-height"))
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(keepLower, { keepLower = it }); Text("Keep lower part") }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(keepUpper, { keepUpper = it }); Text("Keep upper part") }
                    }
                },
                confirmButton = {
                    TextButton({
                        cutDialogFor = null
                        val z = g.minZ + cutFraction * (g.maxZ - g.minZ)
                        scope.launch {
                            try {
                                val (upper, lower) = withContext(Dispatchers.Default) { MeshEdit.cut(MeshEdit.fromGeometry(g), z) }
                                val gap = (g.maxX - g.minX) * obj.scale + 5f
                                val parts = vm.cutObject(id, if (keepLower) lower else null, if (keepUpper) upper else null, gap)
                                selectedId = parts.firstOrNull()?.id
                                toolMessage = if (parts.isEmpty()) "Nothing left after the cut." else "Cut into ${parts.size} part(s) - lay each flat with Lay flat."
                            } catch (e: Exception) { toolMessage = "Cut failed: ${e.message}" }
                        }
                    }, enabled = keepLower || keepUpper, modifier = Modifier.testTag("project-cut-apply")) { Text("Cut") }
                },
                dismissButton = { TextButton({ cutDialogFor = null }) { Text("Cancel") } })
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
    // Phase 8 follow-up (§11, §16, WO-28): the real per-object material+tool picker - opened only
    // for a multi-tool target (toolCount > 1 above). Reuses the exact same Bundled/Spoolman
    // material list the single-material picker already loads (same LaunchedEffect trigger),
    // adding a real tool-slot chooser (1..toolCount, ToolSlots.kt's own real per-model count) -
    // both are set together via vm.setObjectMaterial, the genuine per-object assignment method
    // WO-25 built (independent of setProjectMaterial's lockstep-every-object behavior).
    perObjectPickerFor?.let { objectId ->
        val target = objects.find { it.id == objectId }
        var pendingToolSlot by remember(objectId) { mutableIntStateOf(target?.toolSlotIndex ?: 1) }
        AlertDialog(
            onDismissRequest = { perObjectPickerFor = null }, title = { Text("Assign material and tool") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tool slot", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..toolCount).forEach { slot ->
                            FilterChip(pendingToolSlot == slot, { pendingToolSlot = slot }, label = { Text("Tool $slot") }, modifier = Modifier.testTag("project-object-tool-$slot"))
                        }
                    }
                    Text("Material", style = MaterialTheme.typography.labelMedium)
                    TextButton({ scope.launch { vm.setObjectMaterial(objectId, null, pendingToolSlot) }; perObjectPickerFor = null }, modifier = Modifier.testTag("object-material-none")) { Text("None - use the printer's default profile") }
                    Text("Bundled", style = MaterialTheme.typography.labelMedium)
                    BUNDLED_MATERIAL_PROFILES.forEach { m ->
                        TextButton({ scope.launch { vm.setObjectMaterial(objectId, m, pendingToolSlot) }; perObjectPickerFor = null }, modifier = Modifier.testTag("object-material-${m.id}")) {
                            Text("${m.displayName} · ${m.tempNozzleC}°C / ${m.tempBedC}°C bed")
                        }
                    }
                    Text("From Spoolman", style = MaterialTheme.typography.labelMedium)
                    spoolmanError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    val spools = spoolmanSpools
                    if (spools != null && spools.isEmpty() && spoolmanError == null) Text("No spools found.", style = MaterialTheme.typography.bodySmall)
                    spools?.forEach { spool ->
                        val m = spool.toMaterialProfile()
                        TextButton({ scope.launch { vm.setObjectMaterial(objectId, m, pendingToolSlot) }; perObjectPickerFor = null }, modifier = Modifier.testTag("object-material-${m.id}")) {
                            Text(m.displayName + (m.tempNozzleC?.let { " · ${it}°C" } ?: " · no temperature set"))
                        }
                    }
                }
            },
            confirmButton = { TextButton({ perObjectPickerFor = null }) { Text("Close") } },
        )
    }
}

// toolAssignment/onAssign (§11, WO-28): both null for every single-tool target (today's existing
// behavior, unchanged) - non-null only when the target printer's own bundled profile declares
// more than one real tool slot, in which case this row shows that object's own real material +
// tool assignment and a way to change it, instead of relying on the single project-wide picker.
// hidden/onToggleHidden (WO-30): mirrors the plate toolbar's own Hide button - a hidden object is
// dropped from the workspace but this row is the only place left to bring it back.
@Composable private fun ProjectObjectRow(obj: ProjectObject, selected: Boolean, onClick: () -> Unit, hidden: Boolean = false, onToggleHidden: (() -> Unit)? = null, toolAssignment: String? = null, onAssign: (() -> Unit)? = null) {
    Card(
        Modifier.fillMaxWidth().testTag("project-object-${obj.id}"),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(Uri.parse(obj.sourceFileUri).lastPathSegment ?: obj.id, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), color = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                if (onToggleHidden != null) TextButton(onToggleHidden, modifier = Modifier.testTag("project-object-toggle-hidden-${obj.id}")) { Text(if (hidden) "Show" else "Hide") }
                TextButton(onClick) { Text(if (selected) "Selected" else "Select") }
            }
            if (toolAssignment != null && onAssign != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(toolAssignment, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).testTag("project-object-assignment-${obj.id}"))
                    TextButton(onAssign, modifier = Modifier.testTag("project-object-assign-${obj.id}")) { Text("Assign") }
                }
            }
        }
    }
}

// WO-30 follow-up (owner: "the icons are too light and hard to read" - onSurfaceVariant, #9AA5AA
// in this theme's dark scheme, reads as washed-out at a 24dp icon's 1.7dp stroke width): one
// entry in the plate's own left-hand toolbar, now drawn at full onSurface contrast (near-white)
// when enabled, matching this app's existing thin-stroke CompanionIcon set rather than the
// reference screenshots' own icon glyphs. `enabled = false` still renders (dimmed) rather than
// disappearing, so the toolbar's shape doesn't shift as selection changes. `active` (WO-30
// follow-up: the Move/Rotate interaction-mode toggle) gives a persistent-mode button its own
// highlighted background, distinct from a momentary action like Duplicate/Reset that has no
// "on" state to show.
@Composable private fun PlateToolbarButton(label: String, symbol: CompanionSymbol, enabled: Boolean, testTag: String, active: Boolean = false, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent, RoundedCornerShape(8.dp))
            .testTag(testTag),
    ) {
        IconButton(onClick, enabled = enabled) { CompanionIcon(symbol, color = if (active) MaterialTheme.colorScheme.primary else color) }
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (active) MaterialTheme.colorScheme.primary else color)
    }
}

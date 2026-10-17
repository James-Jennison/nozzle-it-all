package com.nozzleitall.desktop.prepare

import com.nozzleitall.printer.ext.MixedFilamentFormat
import net.jamesjennison.klippercompanion.FlushVolumes
import androidx.compose.runtime.*
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.PrinterEntry
import com.nozzleitall.printer.*
import com.nozzleitall.project.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.*

/** The engine key the user's own flushing-volume matrix is kept under in the project's settings. */
private const val FLUSH_KEY = "flush_volumes_matrix"

/**
 * An object on the plate. [slot] is what it prints with where it isn't painted. A painted model keeps the file's own
 * filament numbers in its paint; [paintSlots] (entry N-1 for the file's filament N) says which slot each one prints with,
 * and [sources] describes those filaments as the file had them.
 */
class PrepItem(val id: Int, name: String, val mesh: Mesh, x: Float, y: Float, rotZ: Float = 0f, scale: Float = 1f, slot: Int = 1,
               paintSlots: List<Int> = emptyList(), val sources: List<SourceFilament> = emptyList(), settings: Map<String, String> = emptyMap(),
               plate: Int = 0) {
    /** Per-axis size on top of [scale] (Orca's unlocked scale fields); a negative one mirrors that axis. */
    var sx by mutableStateOf(1f); var sy by mutableStateOf(1f); var sz by mutableStateOf(1f)
    /** Extra volumes: negative volumes, modifiers, support blockers / enforcers, parts (coordinates from the mesh's bounding-box centre). */
    val parts = androidx.compose.runtime.mutableStateListOf<ProjectManifest.ObjectPart>()
    /** Height range modifiers: settings for layers between two heights. */
    val ranges = androidx.compose.runtime.mutableStateListOf<ProjectManifest.HeightRange>()
    /** A variable layer height profile, z / height pairs; empty = the process's layer height. */
    val layerProfile = androidx.compose.runtime.mutableStateListOf<Double>()
    val ex get() = scale * sx; val ey get() = scale * sy; val ez get() = scale * sz
    /** Takes [from]'s size, mirroring, parts, height ranges and layer profile (a copy or a re-meshed object keeps them). */
    fun copyShapeFrom(from: PrepItem) {
        sx = from.sx; sy = from.sy; sz = from.sz
        parts.clear(); parts.addAll(from.parts); ranges.clear(); ranges.addAll(from.ranges); layerProfile.clear(); layerProfile.addAll(from.layerProfile)
    }
    /** Whether the object has a variable layer height, by profile or by height ranges (the mixed sub-layer warning's question). */
    val hasVariableLayer get() = layerProfile.size > 4 || ranges.isNotEmpty()
    /** The plate it's on (0-based); x and y are on that plate. */
    var plate by mutableStateOf(plate)
    /** The object's own print settings (Orca's per-object settings), engine keys to serialized values. */
    val settings = androidx.compose.runtime.mutableStateMapOf<String, String>().apply { putAll(settings) }
    var name by mutableStateOf(name); var x by mutableStateOf(x); var y by mutableStateOf(y)
    var rotZ by mutableStateOf(rotZ); var scale by mutableStateOf(scale); var slot by mutableStateOf(slot)
    val paintSlots = androidx.compose.runtime.mutableStateListOf<Int>().apply { addAll(paintSlots) }
    /** The file's filament for unpainted areas, when the file said (a model from another slicer). */
    var ownFilament: Int? = null
    /** The object this is an instance of (Orca's instances), or null for an object in its own right. */
    var instanceOf by mutableStateOf<Int?>(null)
    /** The file's filament numbers this model's paint uses, in order. */
    val painted: List<Int> by lazy { MeshIO.paintedFilaments(mesh) }
    /** The slot the file's filament [n] prints with. */
    fun slotFor(n: Int): Int = if (n == 0) slot else paintSlots.getOrNull(n - 1) ?: slot
    val bounds = mesh.bounds()
    /** The outline seen from above (convex hull, centred on the mesh's footprint centre), so rotation is measured exactly. */
    private val outline: FloatArray by lazy { hull2d(mesh.vertices, (bounds[0] + bounds[3]) / 2, (bounds[1] + bounds[4]) / 2) }
    /** How far the object reaches from (x, y) at its current turn and scale: min x, max x, min y, max y. */
    val reach: FloatArray get() {
        val cr = cos(Math.toRadians(rotZ.toDouble())).toFloat(); val sr = sin(Math.toRadians(rotZ.toDouble())).toFloat()
        val r = floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in outline.indices step 2) { val ox = outline[i] * ex; val oy = outline[i + 1] * ey; val px = ox * cr - oy * sr; val py = ox * sr + oy * cr
            r[0] = min(r[0], px); r[1] = max(r[1], px); r[2] = min(r[2], py); r[3] = max(r[3], py) }
        return r
    }
    val footprintW get() = reach.let { it[1] - it[0] }
    val footprintD get() = reach.let { it[3] - it[2] }
    val height get() = (bounds[5] - bounds[2]) * kotlin.math.abs(ez)

    /** The mesh's volume in mm³ before scaling (signed tetrahedra; a closed mesh's is exact). */
    val rawVolume: Double by lazy {
        val v = mesh.vertices; val t = mesh.triangles; var sum = 0.0
        for (i in t.indices step 3) {
            val a = t[i] * 3; val b = t[i + 1] * 3; val c = t[i + 2] * 3
            sum += (v[a] * (v[b + 1] * v[c + 2] - v[b + 2] * v[c + 1]) - v[a + 1] * (v[b] * v[c + 2] - v[b + 2] * v[c]) + v[a + 2] * (v[b] * v[c + 1] - v[b + 1] * v[c])).toDouble()
        }
        kotlin.math.abs(sum / 6.0)
    }
    val volume get() = rawVolume * kotlin.math.abs(ex * ey * ez)

    /** Edges used by only one triangle (vertices at the same place counted as one): 0 for a closed, printable surface. */
    val openEdges: Int by lazy {
        val canonical = HashMap<Triple<Float, Float, Float>, Int>()
        val same = IntArray(mesh.vertexCount) { i -> canonical.getOrPut(Triple(mesh.vertices[i * 3], mesh.vertices[i * 3 + 1], mesh.vertices[i * 3 + 2])) { i } }
        val count = HashMap<Long, Int>()
        val t = mesh.triangles
        for (f in 0 until mesh.triangleCount) for (e in 0..2) {
            val a = same[t[f * 3 + e]]; val b = same[t[f * 3 + (e + 1) % 3]]
            val k = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
            count[k] = (count[k] ?: 0) + 1
        }
        count.values.count { it == 1 }
    }

    /** Placement as a 3MF transform: centre the mesh on its own footprint, drop it onto the bed, rotate about Z, scale, move. */
    fun placement(): Transform {
        val mx = (bounds[0] + bounds[3]) / 2.0; val my = (bounds[1] + bounds[4]) / 2.0; val mz = bounds[2].toDouble()
        val cr = cos(Math.toRadians(rotZ.toDouble())); val sr = sin(Math.toRadians(rotZ.toDouble()))
        val kx = ex.toDouble(); val ky = ey.toDouble(); val kz = ez.toDouble()
        val tx = -(mx * kx * cr - my * ky * sr) + x; val ty = -(mx * kx * sr + my * ky * cr) + y
        val tz = -(if (kz >= 0) mz else bounds[5].toDouble()) * kz   // a mirrored height sits on its other end
        return Transform(doubleArrayOf(cr * kx, sr * kx, 0.0, -sr * ky, cr * ky, 0.0, 0.0, 0.0, kz, tx, ty, tz))
    }
}

sealed class SliceState {
    object Idle : SliceState()
    data class Running(val progress: Float, val stage: String) : SliceState()
    data class Done(val result: SliceOutcome.Done, val preview: GcodePreview?) : SliceState()
    data class Failed(val message: String) : SliceState()
    object Cancelled : SliceState()
}

/** Everything the Prepare workflow edits. Saved as a canonical 3MF + manifest, so Android and the Web App open it. */
class PrepareState(private val app: AppState) {
    val items = mutableStateListOf<PrepItem>()
    var selected by mutableStateOf<Int?>(null)
    var file by mutableStateOf<File?>(null)
    var manifest by mutableStateOf<ProjectManifest?>(null)
    var name by mutableStateOf("Untitled project")
    var dirty by mutableStateOf(false)
    var passthrough: Map<String, ByteArray> = emptyMap()
    var metadata: Map<String, String> = emptyMap()
    var printerId by mutableStateOf<String?>(null)
    /** The slicing profile. Independent of any printer connection: every bundled profile can be sliced and exported. */
    var profileId by mutableStateOf(ProfileCatalog.DEFAULT_ID)
    var preset by mutableStateOf(QualityPreset.STANDARD)
    var supports by mutableStateOf(false)
    var infill by mutableStateOf(15)
    /** Settings changed in All settings, in the engine's serialized form; applied after the preset and quick controls. */
    val overrides = androidx.compose.runtime.mutableStateMapOf<String, String>()
    /** Which settings the panel shows: the print (process), a material, or the printer. */
    var settingsScope by mutableStateOf(com.nozzleitall.desktop.settings.Scope.PROCESS)
    /** Everything (on) or only the essential settings (off). */
    var advancedSettings by mutableStateOf(false)

    /** What a setting is before any change for this project: the quality preset's value, else the printer profile's. */
    fun baseSetting(key: String, profile: com.nozzleitall.desktop.settings.ProfileValues): String? = preset.overrides[key] ?: profile[key]

    /** What a setting is for this project. Infill density and supports are the quick controls' own values. */
    fun setting(key: String, profile: com.nozzleitall.desktop.settings.ProfileValues): String? = when (key) {
        "sparse_infill_density" -> "$infill%"
        "enable_support" -> if (supports) "1" else "0"
        else -> overrides[key] ?: baseSetting(key, profile)
    }

    fun isChanged(key: String, profile: com.nozzleitall.desktop.settings.ProfileValues) = when (key) {
        "sparse_infill_density" -> infill != 15
        "enable_support" -> supports
        else -> key in overrides
    }

    /** Sets (or with null, resets) a setting; a value equal to the base clears the change rather than storing a copy. */
    fun setSetting(key: String, value: String?, profile: com.nozzleitall.desktop.settings.ProfileValues) {
        when (key) {
            "sparse_infill_density" -> infill = value?.removeSuffix("%")?.trim()?.toDoubleOrNull()?.toInt()?.coerceIn(0, 100) ?: 15
            "enable_support" -> supports = value == "1"
            else -> if (value == null || value == baseSetting(key, profile)) overrides.remove(key) else overrides[key] = value
        }
        changed()
    }
    var slice by mutableStateOf<SliceState>(SliceState.Idle)
    var previewLayer by mutableStateOf(0)
    var showPreview by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
    var uploadProgress by mutableStateOf<Float?>(null)
    /** Material slots when no printer reports its toolheads (1-based). */
    // Enough slots for any bundled profile (the Prusa XL has five toolheads); a profile shows as many as it has tools.
    val manualSlots = mutableStateListOf(ProjectManifest.MaterialSlot(1, "PLA", colorHex = "#A78BFA"), ProjectManifest.MaterialSlot(2, "PLA", colorHex = "#F2754E"),
        ProjectManifest.MaterialSlot(3, "PLA", colorHex = "#EEF2F4"), ProjectManifest.MaterialSlot(4, "PLA", colorHex = "#1E2429"),
        ProjectManifest.MaterialSlot(5, "PLA", colorHex = "#4CAF50"), ProjectManifest.MaterialSlot(6, "PLA", colorHex = "#29B6F6"),
        ProjectManifest.MaterialSlot(7, "PLA", colorHex = "#FFB300"), ProjectManifest.MaterialSlot(8, "PLA", colorHex = "#8D6E63"))
    private var engine: SliceEngine? = null
    private var nextId = 1

    val profile: PrinterProfileInfo? get() = ProfileCatalog.byId(profileId)
    /** The chosen profile's printable area. */
    val bed: Pair<Float, Float> get() = profile?.let { it.bedW to it.bedD } ?: (270f to 270f)

    // --- Plates (Orca's plate list): names in order, the one shown, and the objects on it.
    val plateNames = androidx.compose.runtime.mutableStateListOf("Plate 1")
    var currentPlate by mutableStateOf(0)
    fun plateItems(plate: Int = currentPlate): List<PrepItem> = items.filter { it.plate == plate }

    /** Shows plate [i]; a slice result belongs to the plate it was made for. */
    fun showPlate(i: Int) { if (i in plateNames.indices && i != currentPlate) { currentPlate = i; selected = null; invalidateSlice() } }

    fun addPlate() { plateNames += "Plate ${plateNames.size + 1}"; currentPlate = plateNames.lastIndex; selected = null; changed() }
    fun renamePlate(i: Int, name: String) { if (i in plateNames.indices) { plateNames[i] = name; changed() } }
    /** Removes an empty plate; the plates after it move up one. */
    fun removePlate(i: Int) {
        if (plateNames.size <= 1 || plateItems(i).isNotEmpty()) return
        plateNames.removeAt(i); items.forEach { if (it.plate > i) it.plate -= 1 }
        currentPlate = currentPlate.coerceAtMost(plateNames.lastIndex).let { if (it > i) it - 1 else it }.coerceAtLeast(0); changed()
    }
    fun moveToPlate(item: PrepItem, plate: Int) {
        if (plate !in plateNames.indices || plate == item.plate) return
        item.plate = plate; selected = null; changed()
    }

    fun printer(): PrinterEntry? = printerId?.let { app.fleet.printers[it] }

    /** Choosing a saved printer also chooses its slicing profile; choosing "no printer" keeps the profile. */
    fun choosePrinter(id: String?) {
        printerId = id
        app.fleet.printers[id]?.config?.identity?.profileId?.let { profileId = it }
        changed()
    }

    /** Filament profiles chosen on slots of a printer that reports what's loaded (slot → library id). */
    val headProfiles = androidx.compose.runtime.mutableStateMapOf<Int, String>()

    /** The filament profiles this printer profile can slice with, one per slot. */
    fun filamentLibrary(): List<FilamentLibrary.Entry> = FilamentLibrary.forProfile(filamentKey())

    // --- The printer's profile family (PrinterLibrary), where it has one: nozzle size and process preset.
    /** The nozzle diameter chosen on the printer card ("0.4"), or null for the family's 0.4 mm default. */
    var nozzle by mutableStateOf<String?>(null)
    /** The process preset chosen in the Print header (a library id), or null for the machine's default. */
    var processId by mutableStateOf<String?>(null)
    fun library(): PrinterLibrary? = PrinterLibrary.of(profileId)
    fun machineVariant(): PrinterLibrary.Machine? = library()?.machineFor(nozzle)
    fun processes(): List<PrinterLibrary.Process> = library()?.let { lib -> machineVariant()?.let { lib.processesFor(it) } }.orEmpty()
    fun currentProcess(): PrinterLibrary.Process? = machineVariant()?.let { m ->
        library()?.processes?.get(processId?.takeIf { it in m.processes } ?: m.defaultProcess) }
    /** Switches every nozzle to [diameter]: the family's machine for it, its default process, and each slot's filament
     *  kept by family where that size has it (else the printer default). */
    fun chooseNozzle(diameter: String) {
        val lib = library() ?: return
        val next = lib.machineFor(diameter)
        val nextFilaments = lib.filamentsFor(next)
        fun carry(id: String?): String? = id?.let { old -> lib.filaments[old]?.family?.let { fam -> nextFilaments.firstOrNull { it.family.equals(fam, true) }?.id } }
        for (i in manualSlots.indices) manualSlots[i] = manualSlots[i].copy(filamentProfile = carry(manualSlots[i].filamentProfile))
        headProfiles.keys.toList().forEach { k -> carry(headProfiles[k])?.let { headProfiles[k] = it } ?: headProfiles.remove(k) }
        nozzle = next.nozzle; processId = null; changed()
    }

    private fun filamentKey(): String = machineVariant()?.let { "$profileId@${it.id}" } ?: profileId

    /** Sets slot [slot]'s filament profile (and, for a slot Nozzle keeps itself, its material type and colour). */
    fun setSlotFilament(slot: Int, entry: FilamentLibrary.Entry?, colourHex: String? = null) {
        if (printer()?.status?.value?.toolheads.isNullOrEmpty()) {
            val i = manualSlots.indexOfFirst { it.slot == slot }
            if (i >= 0) manualSlots[i] = manualSlots[i].copy(filamentProfile = entry?.id, type = entry?.type ?: manualSlots[i].type,
                vendor = entry?.vendor?.takeIf { it.isNotBlank() }, colorHex = colourHex ?: manualSlots[i].colorHex)
        } else if (entry != null) headProfiles[slot] = entry.id else headProfiles.remove(slot)
        changed()
    }

    /** Materials in slot order: the printer's loaded toolheads when it reports them, otherwise the manual slots. */
    fun materials(): List<ProjectManifest.MaterialSlot> {
        val heads = printer()?.status?.value?.toolheads.orEmpty()
        // A loaded filament slices with the printer's own filament profile for it, matched by vendor and type (the choice
        // made on the slot overrides that).
        if (heads.isNotEmpty()) return heads.map { t ->
            val chosen = headProfiles[t.index + 1]
            ProjectManifest.MaterialSlot(t.index + 1, t.material?.type ?: "PLA", t.material?.vendor, t.material?.subType,
                t.material?.colorHex ?: manualSlots.getOrNull(t.index)?.colorHex, t.index,
                filamentProfile = chosen ?: FilamentLibrary.bestFor(filamentKey(), t.material?.vendor, t.material?.type ?: "PLA", t.material?.subType)?.id)
        }
        // No printer report: one slot per tool the profile has (a U1 has four, most printers one).
        val tools = (profile?.tools ?: 1).coerceIn(1, manualSlots.size)
        return manualSlots.take(tools)
    }

    /** The engine's profile folder: the family's machine and process when the printer has a library, else its pack. */
    fun profileDir(): File = library()?.let { lib -> lib.materialize(app.paths.cache, lib.machineFor(nozzle), processId) }
        ?: ProfileCatalog.materialize(app.paths.cache, profileId)

    // --- Flushing volumes (Snapmaker Orca's Flushing volumes dialog, FlushVolumes). Worked out from the slot colours unless the
    // user edited the matrix; an edited matrix is kept with the project's settings, and a slot whose colour changes later is
    // worked out again (its row and column), as upstream does.
    private var flushColours: List<String>? = null

    private fun slotColours() = materials().map { it.colorHex?.takeIf(String::isNotBlank) ?: "#FFFFFF" }
    fun flushSetup() = SliceEngine.flushSetup(profileDir(), materials())

    fun autoFlush(): List<Int> = flushSetup().matrix(slotColours())

    /** The user's matrix (row = from slot, column = to), or null when it is worked out from the colours. */
    fun editedFlush(): List<Int>? {
        val colours = slotColours(); val n = colours.size
        val saved = overrides[FLUSH_KEY]?.split(',')?.mapNotNull { it.trim().toDoubleOrNull()?.toInt() }?.takeIf { it.size == n * n } ?: return null
        val before = flushColours ?: return saved
        val changedSlots = colours.indices.filter { before.getOrNull(it) != colours[it] }
        if (changedSlots.isEmpty()) return saved
        val setup = flushSetup()
        return changedSlots.fold(saved) { m, i -> setup.recalcSlot(m, i, colours) }
    }

    fun flushMatrix(): List<Int> = editedFlush() ?: autoFlush()

    fun setFlush(matrix: List<Int>?) {
        if (matrix == null) overrides.remove(FLUSH_KEY) else overrides[FLUSH_KEY] = matrix.joinToString(",")
        flushColours = if (matrix == null) null else slotColours()
        changed()
    }

    /** The engine multiplies every flushing volume by this (Snapmaker Orca: 0-3). */
    fun flushMultiplier(): Float = (overrides["flush_multiplier"] ?: runCatching { JSONObject(File(profileDir(), "process.json").readText()).optString("flush_multiplier") }.getOrNull())
        ?.toFloatOrNull() ?: FlushVolumes.ENGINE_DEFAULT_MULTIPLIER

    fun setFlushMultiplier(v: Float) {
        overrides["flush_multiplier"] = "%.2f".format(java.util.Locale.ROOT, v.coerceIn(FlushVolumes.MIN_MULTIPLIER, FlushVolumes.MAX_MULTIPLIER)); changed()
    }

    // --- Colour mixing: OrcaSlicer 2.5.0's mixed filaments. Each row is a filament slot after the loaded ones, made of loaded
    // filaments in set proportions (optionally a gradient up the object); the engine (Orca's own code) blends its colour and
    // resolves it when slicing. They are saved with the project as the same filament_mixed_* settings Orca writes.
    val mixed = mutableStateListOf<MixedFilamentFormat.Row>()
    var mixProblem by mutableStateOf<String?>(null)
    /** Whether mixed filaments split each layer into sub-layers (Orca's default) rather than alternating whole layers. */
    var mixSublayers by mutableStateOf(true)
    /** The slice settings for [mixed] as the engine last worked them out, kept so saving the project needs no engine call. */
    private var mixedSettings: Map<String, String> = emptyMap()

    /** The slot number of mixed row [index]: after the loaded filaments. */
    fun mixedSlot(index: Int): Int = materials().size + index + 1

    /** The mixed rows this printer prints: none where it offers no mixing (CANVAS, a single-slot printer). */
    fun offeredMixed(): List<MixedFilamentFormat.Row> = if (MixedFilamentFormat.FEATURE in features()) mixed.toList() else emptyList()

    private fun typedPhysical() = materials().map { (it.colorHex ?: "#FFFFFF") to it.type }

    /** Re-reads the rows through the engine for the current loaded filaments (colours, problems) and refreshes the saved settings. */
    fun refreshMixes(scope: kotlinx.coroutines.CoroutineScope) {
        if (mixed.isEmpty()) { mixedSettings = emptyMap(); return }
        val physical = typedPhysical(); val rows = mixed.toList(); val sublayer = mixSublayers
        scope.launch { runCatching { withContext(Dispatchers.IO) { MixedFilament.display(physical, rows) to runCatching { MixedFilament.sliceOverrides(physical, rows, sublayer) }.getOrDefault(emptyMap()) } }
            .onSuccess { (display, settings) -> if (display != mixed.toList()) { mixed.clear(); mixed.addAll(display) }; mixedSettings = settings; mixProblem = null }
            .onFailure { mixProblem = it.message } }
    }

    private fun edit(scope: kotlinx.coroutines.CoroutineScope, op: (List<Pair<String, String?>>, List<MixedFilamentFormat.Row>) -> List<MixedFilamentFormat.Row>?) {
        val physical = typedPhysical(); val rows = mixed.toList()
        scope.launch { runCatching { withContext(Dispatchers.IO) { op(physical, rows) } }
            .onSuccess { result -> if (result == null) mixProblem = "That mix is already in the list." else { mixed.clear(); mixed.addAll(result); mixProblem = null; changed(); refreshMixes(scope) } }
            .onFailure { mixProblem = it.message } }
    }

    fun addMixed(row: MixedFilamentFormat.Row, scope: kotlinx.coroutines.CoroutineScope) = edit(scope) { phys, rows -> MixedFilament.add(phys, rows, row) }
    fun updateMixed(index: Int, row: MixedFilamentFormat.Row, scope: kotlinx.coroutines.CoroutineScope) = edit(scope) { phys, rows -> MixedFilament.update(phys, rows, index, row) }

    /** Removes mixed row [index]; objects and painted areas on it go back to the default slot, and later mixes move down one slot. */
    fun removeMixed(index: Int, scope: kotlinx.coroutines.CoroutineScope) {
        val gone = mixedSlot(index)
        edit(scope) { phys, rows -> MixedFilament.remove(phys, rows, index) }
        items.forEach { item ->
            if (item.slot == gone) item.slot = 1 else if (item.slot > gone) item.slot--
            for (i in item.paintSlots.indices) {
                val s = item.paintSlots[i]
                item.paintSlots[i] = if (s == gone) item.slot else if (s > gone) s - 1 else s
            }
        }
    }

    // --- Printer card: bed type and each nozzle's flow (Snapmaker Orca's rules, PrinterSetup).
    /** The chosen bed type (curr_bed_type), or null for the printer's own default. */
    var bedType by mutableStateOf<String?>(null)
    /** Each nozzle's flow type ("standard" / "high_flow"); missing entries are standard. */
    val nozzleFlows = mutableStateListOf<String>()

    private var machineCache: Pair<String, JSONObject>? = null
    fun machineJson(): JSONObject { val key = "$profileId@${nozzle.orEmpty()}"
        return machineCache?.takeIf { it.first == key }?.second
            ?: runCatching { JSONObject(File(profileDir(), "machine.json").readText()) }.getOrDefault(JSONObject()).also { machineCache = key to it } }
    fun beds(): PrinterSetup.Beds = PrinterSetup.beds(machineJson())
    /** The bed type slicing uses: the choice when it's valid for this printer, else the printer's default. */
    fun effectiveBedType(): String { val b = beds(); return bedType?.takeIf { b.enabled && b.choices.any { c -> c.value == it } } ?: b.default }

    /** Printer settings the card controls, as engine keys: the bed type, and nozzle flows where high flow exists. */
    fun printerOverrides(): Map<String, String> = buildMap {
        put("curr_bed_type", effectiveBedType())
        val m = machineJson()
        if (PrinterSetup.supportsHighFlow(m)) {
            val n = PrinterSetup.nozzles(m).size
            put("nozzle_volume_type", List(n) { nozzleFlows.getOrElse(it) { "standard" } }.joinToString(","))
        }
    }

    /** Colour-mixing features on offer: the connected printer's own report, else what the chosen profile offers. */
    fun features(): Set<String> = printer()?.capabilities?.value?.vendorExtensions?.let { com.nozzleitall.printer.ext.ProfileFeatures.ofPrinter(it, materials().size, profile?.id) }
        ?: profile?.let { com.nozzleitall.printer.ext.ProfileFeatures.of(it.familyHint, it.tools, it.id) } ?: emptySet()

    fun physicalColours(): List<String> = materials().map { it.colorHex ?: "#FFFFFF" }

    /** The colour of slot [id]: a loaded filament, or a mixed filament's blended colour. */
    fun slotHex(id: Int): String? = materials().firstOrNull { it.slot == id }?.colorHex ?: mixed.getOrNull(id - materials().size - 1)?.hex

    /** Every slot a model can print with: the loaded filaments, then the mixed filaments. */
    fun allSlots(): List<Pair<Int, String>> = materials().map { it.slot to listOfNotNull(it.vendor, it.type).joinToString(" ").ifBlank { "Filament" } } +
        offeredMixed().mapIndexed { i, r -> mixedSlot(i) to "Mix ${r.label}" }

    /** The slots the project uses: every object's own slot and every slot its colours print in. */
    fun usedSlots(): Set<Int> = items.flatMapTo(HashSet()) { item -> listOf(item.slot) + item.painted.map { item.slotFor(it) } }

    /**
     * Matches each of [item]'s file colours to the closest loaded filament or a new mix of loaded filaments (the engine's
     * recipe search), adds the mixes and sets the slots. Returns the number of mixes added.
     */
    fun matchWithMixing(item: PrepItem, targets: List<Pair<String, List<Int>>>, scope: kotlinx.coroutines.CoroutineScope) {
        val physical = typedPhysical()
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                var rows = mixed.toList()
                val slotFor = HashMap<String, Int>()
                for ((hex, _) in targets) {
                    val m = MixedFilament.match(physical, hex)
                    val single = m.parts.singleOrNull()
                    if (single != null || m.row == null) { slotFor[hex] = m.parts.firstOrNull()?.filament ?: 1; continue }
                    val row = m.row!!
                    // The engine says an identical fixed blend is already there by returning nothing; reuse that row.
                    val existing = rows.indexOfFirst { !it.gradient.enabled && it.components == row.components && it.weights == row.weights }
                    if (existing < 0) rows = MixedFilament.add(physical, rows, row) ?: rows
                    slotFor[hex] = physical.size + (if (existing >= 0) existing else rows.size - 1) + 1
                }
                rows to slotFor
            } }.onSuccess { (rows, slotFor) ->
                mixed.clear(); mixed.addAll(rows)
                targets.forEach { (hex, ids) -> val slot = slotFor[hex] ?: return@forEach
                    ids.forEach { n -> if (n == item.ownFilament) item.slot = slot
                        if (n >= 1) { while (item.paintSlots.size < n) item.paintSlots.add(item.slot); item.paintSlots[n - 1] = slot } } }
                mixProblem = null; changed(); refreshMixes(scope)
            }.onFailure { mixProblem = it.message }
        }
    }

    fun newProject() {
        bedType = null; nozzleFlows.clear(); nozzle = null; processId = null
        overrides.clear(); mixed.clear(); mixedSettings = emptyMap(); mixSublayers = true; mixProblem = null; headProfiles.clear()
        items.clear(); selected = null; file = null; manifest = null; name = "Untitled project"; dirty = false; passthrough = emptyMap(); metadata = emptyMap()
        plateNames.clear(); plateNames += "Plate 1"; currentPlate = 0
        slice = SliceState.Idle; showPreview = false; nextId = 1
    }

    fun open(f: File) {
        val p = ThreeMf.read(f)
        newProject()
        file = f; manifest = p.manifest; name = p.manifest?.name ?: p.metadata["Title"] ?: f.nameWithoutExtension
        passthrough = p.passthrough; metadata = p.metadata
        val entries = p.manifest?.plates?.flatMap { it.objects }?.associateBy { it.objectId } ?: emptyMap()
        // Plates: Nozzle's own from the manifest, another slicer's from its plate list. Objects are stored where that
        // slicer puts its plates side by side (Plates.origin), so each is brought back onto its own plate.
        val ownPlates = p.manifest?.plates?.sortedBy { it.index }?.takeIf { it.size > 1 || it.firstOrNull()?.name?.isNotBlank() == true }
        val plateOf: (ModelObject) -> Int = if (ownPlates != null) { o -> ownPlates.indexOfFirst { pl -> pl.objects.any { it.objectId == o.id } }.coerceAtLeast(0) } else { o -> o.plate ?: 0 }
        val names = ownPlates?.map { it.name } ?: p.plateNames
        val count = maxOf(names.size, (p.objects.maxOfOrNull(plateOf) ?: 0) + 1, 1)
        plateNames.clear(); plateNames.addAll((0 until count).map { i -> names.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "Plate ${i + 1}" })
        val layoutBed = (if (ownPlates != null) p.manifest?.printer?.profileId?.let { ProfileCatalog.byId(it) }?.let { it.bedW.toDouble() to it.bedD.toDouble() } else p.sourceBed)
            ?: bed.let { it.first.toDouble() to it.second.toDouble() }
        p.objects.forEach { o ->
            val plate = plateOf(o)
            val (ox, oy) = Plates.origin(plate, count, layoutBed.first, layoutBed.second)
            val local = if (ox == 0.0 && oy == 0.0) o else o.copy(placement = Transform(o.placement.m.copyOf().also { it[9] -= ox; it[10] -= oy }))
            addFromPlacement(local, entries[o.id]?.materialSlot ?: 1, entries[o.id]?.paintSlots.orEmpty(), p.filaments,
                ObjectSettings.usable(entries[o.id]?.settings?.takeIf { it.isNotEmpty() } ?: o.settings), plate)
            entries[o.id]?.let { e -> items.lastOrNull()?.let { it.parts.addAll(e.parts); it.ranges.addAll(e.heightRanges); it.layerProfile.addAll(e.layerProfile); it.instanceOf = e.instanceOf } }
        }
        p.manifest?.settings?.preset?.let { key ->
            if (key.startsWith("process:")) processId = key.removePrefix("process:")
            else QualityPreset.entries.firstOrNull { it.name.equals(key, true) }?.let { preset = it } }
        p.manifest?.printer?.nozzleDiameters?.firstOrNull()?.let { d -> nozzle = if (d == Math.floor(d)) d.toInt().toString() else d.toString() }
        p.manifest?.settings?.overrides?.let { ov ->
            ov["sparse_infill_density"]?.removeSuffix("%")?.toIntOrNull()?.let { infill = it }; ov["enable_support"]?.let { supports = it == "1" }
            // Everything else the project changed (from any platform) shows up in All settings.
            mixed.addAll(MixedFilamentFormat.rowsFromSettings(ov, p.manifest?.materials?.size ?: materials().size))
            ov["enable_mixed_color_sublayer"]?.let { mixSublayers = it == "1" }
            ov["curr_bed_type"]?.let { bedType = it }
            ov["nozzle_volume_type"]?.let { v -> nozzleFlows.clear(); nozzleFlows.addAll(v.split(',').map { it.trim() }) }
            ov.filter { (k, v) -> k != "sparse_infill_density" && k != "enable_support" && k != "filament_colour" && k !in MixedFilamentFormat.SETTING_KEYS && k != "curr_bed_type" &&
                k != "nozzle_volume_type" && preset.overrides[k] != v }.forEach { (k, v) -> overrides[k] = v }
        }
        p.manifest?.printer?.profileId?.takeIf { ProfileCatalog.byId(it) != null }?.let { profileId = it }
        // The project's slots: what a printer doesn't report comes back as saved (colour, material, filament profile).
        p.manifest?.materials?.forEach { m ->
            val i = manualSlots.indexOfFirst { it.slot == m.slot }
            if (i >= 0) manualSlots[i] = manualSlots[i].copy(type = m.type ?: manualSlots[i].type, vendor = m.vendor, subType = m.subType,
                colorHex = m.colorHex ?: manualSlots[i].colorHex, filamentProfile = m.filamentProfile)
            m.filamentProfile?.let { headProfiles[m.slot] = it }
        }
        p.manifest?.printer?.printerId?.takeIf { it in app.fleet.printers }?.let { printerId = it }
        notice = p.manifestProblem
        dirty = false
    }

    /** Reverses [PrepItem.placement] for a mesh loaded from a 3MF: recovers bed position, Z rotation and uniform scale. */
    private fun addFromPlacement(o: ModelObject, slot: Int, paintSlots: List<Int>, sources: List<SourceFilament>, settings: Map<String, String> = emptyMap(), plate: Int = 0) {
        val m = o.placement.m
        val scale = sqrt(m[0] * m[0] + m[1] * m[1]).toFloat().takeIf { it > 0 } ?: 1f
        val rot = Math.toDegrees(atan2(m[1], m[0])).toFloat()
        // The other axes relative to the first: Y's length and handedness (a left-handed turn mirrors Y), Z's length and sign.
        val yLen = sqrt(m[3] * m[3] + m[4] * m[4]).toFloat().takeIf { it > 0 } ?: scale
        val mirrorY = m[0] * m[4] - m[1] * m[3] < 0
        val relY = (if (mirrorY) -yLen else yLen) / scale; val relZ = (m[8].toFloat().takeIf { it != 0f } ?: scale) / scale
        val b = o.mesh.bounds(); val cx = (b[0] + b[3]) / 2.0; val cy = (b[1] + b[4]) / 2.0
        val p = o.placement.apply(cx, cy, 0.0)
        val item = PrepItem(o.id, o.name, o.mesh, p[0].toFloat(), p[1].toFloat(), rot, scale, slot, paintSlots, sources, settings, plate)
        item.sy = relY; item.sz = relZ
        // A painted model from another slicer (or saved before colours were mapped) gets its colours matched to the slots.
        if (item.painted.isNotEmpty() && item.paintSlots.size < item.painted.max()) matchColours(item, o.filament)
        items += item
        nextId = maxOf(nextId, o.id + 1)
    }

    fun importModel(f: File) {
        val loaded = MeshIO.load(f)
        val (bw, bd) = bed
        val item = PrepItem(nextId++, f.nameWithoutExtension, loaded.mesh, bw / 2, bd / 2, sources = loaded.filaments, plate = currentPlate)
        if (item.painted.isNotEmpty() || loaded.filament != null) matchColours(item, loaded.filament)
        items += item
        dirty = true; invalidateSlice(); arrange()
        if (name == "Untitled project") name = f.nameWithoutExtension
    }

    /**
     * Gives the file's filaments slots the way Snapmaker Orca opens a file: filament N in slot N (wrapping when the file
     * has more filaments than the printer has slots). The object's own filament sets its slot.
     */
    fun matchColours(item: PrepItem, own: Int? = item.ownFilament) {
        item.ownFilament = own
        assign(item, FilamentSync.byNumber(filamentCount(item), materials()))
    }

    /** "Match": each of the file's filaments to the nearest loaded colour, same material first (Snapmaker Orca's filament sync). */
    fun matchByColour(item: PrepItem) = assign(item, FilamentSync.byColour(filamentCount(item), item.sources, materials())).also { changed() }

    private fun filamentCount(item: PrepItem) = maxOf(item.painted.maxOrNull() ?: 0, item.ownFilament ?: 0, item.sources.size)
    private fun assign(item: PrepItem, slots: List<Int>) {
        item.paintSlots.clear()
        item.paintSlots.addAll(slots)
        item.ownFilament?.let { f -> slots.getOrNull(f - 1)?.let { item.slot = it } }
    }

    /** Sets which slot the file's filament [n] of [item] prints with. */
    fun setPaintSlot(item: PrepItem, n: Int, slot: Int) {
        while (item.paintSlots.size < n) item.paintSlots.add(item.slot)
        item.paintSlots[n - 1] = slot; changed()
    }

    fun duplicateSelected() { items.firstOrNull { it.id == selected }?.let { s -> nextId = maxOf(nextId, (items.maxOfOrNull { it.id } ?: 0) + 1); items += PrepItem(nextId++, s.name + " copy", s.mesh, s.x + 10, s.y + 10, s.rotZ, s.scale, s.slot, s.paintSlots.toList(), s.sources, s.settings.toMap(), s.plate).also { it.copyShapeFrom(s) }; changed(); arrange() } }
    fun removeSelected() { items.removeAll { it.id == selected }; selected = null; changed() }
    fun changed() { syncInstances(); dirty = true; invalidateSlice(); refreshRules() }

    /**
     * Orca's instances: another copy of the same object. It shares the object's mesh, filament, settings, parts, height ranges, layer
     * profile and paint, so a change to the object reaches every instance; its place, turn and size are its own. An instance whose
     * object is gone becomes an object in its own right.
     */
    fun addInstance() {
        val s = items.firstOrNull { it.id == selected } ?: return
        val root = s.instanceOf ?: s.id
        nextId = maxOf(nextId, (items.maxOfOrNull { it.id } ?: 0) + 1)
        items += PrepItem(nextId++, s.name.removeSuffix(" (instance)") + " (instance)", s.mesh, s.x + 10, s.y + 10, s.rotZ, s.scale, s.slot, s.paintSlots.toList(), s.sources, s.settings.toMap(), s.plate)
            .also { it.copyShapeFrom(s); it.instanceOf = root; it.ownFilament = s.ownFilament }
        changed(); arrange()
    }
    fun syncInstances() {
        val byId = items.associateBy { it.id }
        items.forEach { i ->
            val root = i.instanceOf ?: return@forEach
            val src = byId[root]?.takeIf { it !== i } ?: run { i.instanceOf = null; return@forEach }
            if (i.slot != src.slot) i.slot = src.slot
            if (i.paintSlots.toList() != src.paintSlots.toList()) { i.paintSlots.clear(); i.paintSlots.addAll(src.paintSlots) }
            if (i.settings.toMap() != src.settings.toMap()) { i.settings.clear(); i.settings.putAll(src.settings) }
            if (i.parts.toList() != src.parts.toList()) { i.parts.clear(); i.parts.addAll(src.parts) }
            if (i.ranges.toList() != src.ranges.toList()) { i.ranges.clear(); i.ranges.addAll(src.ranges) }
            if (i.layerProfile.toList() != src.layerProfile.toList()) { i.layerProfile.clear(); i.layerProfile.addAll(src.layerProfile) }
        }
    }
    private fun invalidateSlice() { if (slice !is SliceState.Running) { slice = SliceState.Idle; showPreview = false } }

    /** Shelf packing by footprint, largest first, with a 6 mm gap, centred on the bed. Objects that can't fit are reported. */
    // --- Plate tools (PlateOps): Orca's Arrange, Auto orient, Split and Cut, run on the engine off the UI thread.
    /** What the plate tools are doing now ("Arranging"...), or null. */
    var toolBusy by mutableStateOf<String?>(null)

    private fun plateWork() = File(app.paths.cache, "plate").apply { mkdirs() }

    private fun runTool(label: String, block: suspend () -> Unit) {
        if (toolBusy != null) return
        toolBusy = label
        // On the UI thread: the plate's state changes there; the engine work inside runs on IO.
        app.scope.launch(Dispatchers.Main) {
            try { block() } catch (e: Exception) { notice = "$label didn't work: ${e.message ?: e.javaClass.simpleName}" }
            finally { toolBusy = null }
        }
    }

    /** The same object with a new mesh (its turn, size, slot, colours and settings kept). */
    private fun withMesh(old: PrepItem, mesh: Mesh, id: Int = old.id, name: String = old.name, x: Float = old.x, y: Float = old.y) =
        PrepItem(id, name, mesh, x, y, old.rotZ, old.scale, old.slot, old.paintSlots.toList(), old.sources, old.settings.toMap(), old.plate).also { it.ownFilament = old.ownFilament; it.copyShapeFrom(old) }

    private fun replace(old: PrepItem, vararg new: PrepItem) {
        val i = items.indexOfFirst { it.id == old.id }.takeIf { it >= 0 } ?: return
        items.removeAt(i); items.addAll(i, new.toList())
    }

    /**
     * Orca's Arrange (engine: ModelArrange/Arrange with the printer's clearances and the print's brim/skirt spacing).
     * Objects that don't fit stay where they are and are named.
     */
    fun arrange() { if (plateItems().isNotEmpty()) runTool("Arrange") { arrangeNow() } }

    private suspend fun arrangeNow() {
            val snapshot = plateItems()
            val (bw, bd) = bed
            val dir = profileDir()
            val placed = withContext(Dispatchers.IO) {
                PlateOps.arrange(listOf("machine.json", "process.json", "filament.json").map { File(dir, it) },
                    (printerOverrides() + overrides.toMap() - FLUSH_KEY), snapshot, bw, bd, work = plateWork())
            }
            // What doesn't fit goes onto new plates after the last, as Orca's arrange fills further plates.
            val firstNew = plateNames.size
            var moved = 0
            snapshot.zip(placed).forEach { (item, p) ->
                item.x += p.dx.toFloat(); item.y += p.dy.toFloat(); item.rotZ = (item.rotZ + p.rotationDeg.toFloat()) % 360f
                if (p.plate > 0) {
                    val target = firstNew + p.plate - 1
                    while (plateNames.size <= target) plateNames += "Plate ${plateNames.size + 1}"
                    item.plate = target; moved++
                }
            }
            if (moved > 0) notice = "$moved object${if (moved == 1) "" else "s"} didn't fit, so Arrange put ${if (moved == 1) "it" else "them"} on another plate."
            changed()
    }

    // --- Object tools beyond the plate ops: parts, height ranges, variable layer height, size and mirror (the engine takes them as slice request lines).
    /** Adds an extra volume to [item], centred on its bounding box (coordinates are from that centre) at a quarter of its largest side. */
    fun addPart(item: PrepItem, kind: String, shape: String) {
        val b = item.bounds; val side = maxOf(b[3] - b[0], b[4] - b[1], b[5] - b[2]) / 4.0
        item.parts += ProjectManifest.ObjectPart(kind, shape, listOf(0.0, 0.0, 0.0), listOf(side, side, side)); changed()
    }
    fun updatePart(item: PrepItem, index: Int, part: ProjectManifest.ObjectPart) { if (index in item.parts.indices) { item.parts[index] = part; changed() } }
    fun removePart(item: PrepItem, index: Int) { if (index in item.parts.indices) { item.parts.removeAt(index); changed() } }
    /** Adds a height range from 20% to 40% of the object's height that prints with a finer layer, the setting Orca's range starts with. */
    fun addRange(item: PrepItem) {
        val h = item.height.toDouble()
        item.ranges += ProjectManifest.HeightRange(Math.round(h * 20) / 100.0, Math.round(h * 40) / 100.0, mapOf("layer_height" to (overrides["layer_height"] ?: preset.overrides["layer_height"] ?: "0.2")))
        changed()
    }
    fun updateRange(item: PrepItem, index: Int, range: ProjectManifest.HeightRange) { if (index in item.ranges.indices) { item.ranges[index] = range; changed() } }
    fun removeRange(item: PrepItem, index: Int) { if (index in item.ranges.indices) { item.ranges.removeAt(index); changed() } }
    fun clearLayerProfile(item: PrepItem) { item.layerProfile.clear(); changed() }
    /** Orca's adaptive layer height for [item] (finer where the surface is shallow), at [quality] 0..1, from the engine. */
    fun adaptiveLayers(item: PrepItem, quality: Double) = runTool("Adaptive layers") {
        val dir = profileDir()
        val profile = withContext(Dispatchers.IO) {
            PlateOps.adaptiveLayers(listOf("machine.json", "process.json", "filament.json").map { File(dir, it) }, printerOverrides() + overrides.toMap() - FLUSH_KEY, item.mesh,
                Triple(kotlin.math.abs(item.ex.toDouble()), kotlin.math.abs(item.ey.toDouble()), kotlin.math.abs(item.ez.toDouble())), quality, plateWork())
        }
        item.layerProfile.clear(); item.layerProfile.addAll(profile); changed()
    }

    // --- Orca's settings rules (OptionRules): greyed-out and hidden settings, the Setting Overrides page, the IDEX layout choices and the checks.
    var rules by mutableStateOf<OptionRules.Result?>(null)
    var ruleChecks by mutableStateOf<OptionRules.Result?>(null)
    private var rulesJob: kotlinx.coroutines.Job? = null
    /** Asks the engine for the rules of the current configuration (off the UI thread; a newer change supersedes an older answer). */
    fun refreshRules() {
        rulesJob?.cancel()
        val dir = runCatching { profileDir() }.getOrNull() ?: return
        val files = listOf("machine.json", "filament.json", "process.json").map { File(dir, it) }
        val settings = printerOverrides() + overrides.toMap() - FLUSH_KEY
        val variable = items.any { it.hasVariableLayer }
        val filaments = maxOf(1, allSlots().size)
        rulesJob = app.scope.launch(Dispatchers.IO) {
            val states = runCatching { OptionRules.states(files, settings, filamentCount = filaments, printerName = profileId) }.getOrNull()
            val checks = runCatching { OptionRules.checks(files, settings, variable, filaments, profileId) }.getOrNull()
            withContext(Dispatchers.Main) { rules = states; ruleChecks = checks }
        }
    }

    // --- AI auto-paint (AutoPaint): the engine segments the model; the user's own provider says which slot goes where.
    var autoPaintSettings by mutableStateOf(AutoPaint.load(app.paths.config.resolve("auto-paint.json")))
    fun saveAutoPaintSettings(s: AutoPaint.Settings) { autoPaintSettings = s; AutoPaint.save(app.paths.config.resolve("auto-paint.json"), s) }

    /** Paints the selected object with the plate's slots as [hint] asks; the colours print in the same slot numbers they are offered under. */
    fun autoPaintSelected(hint: String) {
        val item = items.firstOrNull { it.id == selected } ?: return
        val provider = autoPaintSettings.provider ?: run { notice = "Choose how to ask first: a command, or an OpenAI-compatible server."; return }
        val slots = allSlots()
        if (slots.size < 2) { notice = "AI auto-paint needs at least two filaments to choose between."; return }
        val filaments = slots.map { (id, label) -> com.nozzleitall.printer.ext.AutoPaintFormat.Filament(id, label, slotHex(id) ?: "#808080") }
        runTool("AI auto-paint") {
            val mesh = withContext(Dispatchers.IO) { AutoPaint.paint(item.mesh, item.name, filaments, hint, provider, plateWork()) }
            val painted = withMesh(item, mesh)
            val top = filaments.maxOf { it.index }
            painted.paintSlots.clear(); painted.paintSlots.addAll(1..top)   // paint state N prints in slot N
            replace(item, painted); selected = painted.id
            changed()
        }
    }

    // --- Paint tools (PaintOps): Orca's support, seam, colour and fuzzy-skin painting, run on the engine's TriangleSelector.
    /** The kind being painted on the selected object, or null when painting is off. */
    var paintKind by mutableStateOf<PaintOps.Kind?>(null)
    /** What a stroke paints: support/seam 1 = enforcer and 2 = blocker, fuzzy skin 1; colour: the slot. 0 erases. */
    var paintState by mutableStateOf(1)
    var paintMode by mutableStateOf(PaintOps.Mode.BRUSH)
    /** Brush radius in millimetres on the plate (the engine gets it in the model's own units). */
    var paintRadius by mutableStateOf(3f)
    var paintBusy by mutableStateOf(false)
    private val paintQueue = ArrayDeque<Pair<Int, List<PaintRay>>>()
    private class PaintStep(val id: Int, val kind: PaintOps.Kind, val before: Mesh, val slots: List<Int>)
    private val paintHistory = ArrayDeque<PaintStep>()
    val canUndoPaint: Boolean get() = paintHistory.isNotEmpty()

    /** The file's filament number that prints in [slot] for [item]: an existing one, or a new one added to its paint slots. */
    private fun colourStateForSlot(item: PrepItem, slot: Int): Int {
        val at = item.paintSlots.indexOf(slot)
        if (at >= 0) return at + 1
        item.paintSlots.add(slot)
        return item.paintSlots.size
    }

    /** Applies the strokes the viewer collected to the object [id], one batch at a time so each builds on the last. */
    fun paintStroke(id: Int, rays: List<PaintRay>) {
        if (paintKind == null || rays.isEmpty()) return
        paintQueue.addLast(id to rays)
        if (paintBusy) return
        paintBusy = true
        app.scope.launch(Dispatchers.Main) {
            try {
                while (paintQueue.isNotEmpty()) {
                    val (target, batch) = paintQueue.removeFirst()
                    val kind = paintKind ?: break
                    val item = items.firstOrNull { it.id == target } ?: continue
                    val slotsBefore = item.paintSlots.toList()
                    val state = if (kind == PaintOps.Kind.COLOUR && paintState > 0) colourStateForSlot(item, paintState) else paintState
                    val strokes = batch.map { PaintOps.Stroke(it.origin, it.dir, it.radius, state) }
                    val mode = paintMode
                    val mesh = withContext(Dispatchers.IO) { PaintOps.stroke(item.mesh, kind, strokes, mode) }
                    val fresh = withMesh(item, mesh)
                    paintHistory.addLast(PaintStep(target, kind, item.mesh, slotsBefore)); while (paintHistory.size > 30) paintHistory.removeFirst()
                    replace(item, fresh); changed()
                }
            } catch (e: Exception) { notice = "Painting didn't work: ${e.message ?: e.javaClass.simpleName}" }
            finally { paintBusy = false }
        }
    }

    /** Takes back the last paint stroke (or clear). */
    fun undoPaint() {
        val step = paintHistory.removeLastOrNull() ?: return
        val item = items.firstOrNull { it.id == step.id } ?: return
        val fresh = withMesh(item, step.before)
        fresh.paintSlots.clear(); fresh.paintSlots.addAll(step.slots)
        replace(item, fresh); changed()
    }

    /** Removes all paint of [kind] from the selected object. */
    fun clearPaint(kind: PaintOps.Kind) {
        val item = items.firstOrNull { it.id == selected } ?: return
        if (kind.of(item.mesh) == null) return
        paintHistory.addLast(PaintStep(item.id, kind, item.mesh, item.paintSlots.toList())); while (paintHistory.size > 30) paintHistory.removeFirst()
        replace(item, withMesh(item, kind.with(item.mesh, null))); changed()
    }

    fun stopPainting() { paintKind = null; paintQueue.clear() }

    /** Orca's Auto orient for the selected object, or every object when none is selected. */
    fun orient() {
        val targets = plateItems().filter { selected == null || it.id == selected }.ifEmpty { return }
        runTool("Auto orient") {
            targets.forEach { item ->
                val mesh = withContext(Dispatchers.IO) { PlateOps.orient(item.mesh, plateWork()) }
                replace(item, withMesh(item, mesh))
            }
            changed()
        }
    }

    /** Orca's Split to objects: each separate part of the selected object becomes its own object, where it was. */
    fun splitSelected() {
        val item = items.firstOrNull { it.id == selected } ?: return
        runTool("Split") {
            val parts = withContext(Dispatchers.Default) { PlateOps.split(item.mesh) }
            if (parts.size < 2) { notice = "${item.name} is one piece, so there's nothing to split."; return@runTool }
            val place = item.placement()
            val made = parts.mapIndexed { i, m ->
                val b = m.bounds(); val c = place.apply((b[0] + b[3]) / 2.0, (b[1] + b[4]) / 2.0, 0.0)
                withMesh(item, m, if (i == 0) item.id else nextId++, "${item.name} ${i + 1}", c[0].toFloat(), c[1].toFloat())
            }
            replace(item, *made.toTypedArray())
            selected = made.first().id
            changed()
        }
    }

    /**
     * Orca's Cut with a horizontal plane [heightMm] above the selected object's base: both halves are kept, each on the
     * bed, then the plate is arranged. Painted colours don't carry over to the halves.
     */
    fun cutSelected(heightMm: Float) {
        val item = items.firstOrNull { it.id == selected } ?: return
        if (heightMm <= 0f || heightMm >= item.height) { notice = "Cut between the object's base and its top (0-${"%.1f".format(item.height)} mm)."; return }
        runTool("Cut") {
            val z = item.bounds[2] + heightMm / kotlin.math.abs(item.ez)
            val (upper, lower) = withContext(Dispatchers.IO) { PlateOps.cut(item.mesh, z, plateWork()) }
            val halves = listOfNotNull(lower?.let { withMesh(item, it, item.id, "${item.name} (lower)") },
                upper?.let { withMesh(item, it, nextId++, "${item.name} (upper)", item.x + item.footprintW + 5f) })
            if (halves.isEmpty()) return@runTool
            // A half has no paint of its own; its colours follow the object's material slot.
            halves.forEach { it.paintSlots.clear() }
            if (item.mesh.isPainted) notice = "The cut halves print in ${item.name}'s material; its painted colours aren't kept."
            replace(item, *halves.toTypedArray())
            changed()
            arrangeNow()
        }
    }

    fun outOfBounds(): List<PrepItem> { val (bw, bd) = bed
        return plateItems().filter { val r = it.reach; it.x + r[0] < 0 || it.y + r[2] < 0 || it.x + r[1] > bw || it.y + r[3] > bd || it.height > (profile?.height ?: 250f) } }

    /**
     * The project as saved (every plate, objects placed where Orca lays plates side by side, plates recorded for other
     * slicers too), or [forSlice]: the current plate alone, on its own bed.
     */
    fun toProject(forSlice: Boolean = false): Project3mf {
        val (bw, bd) = bed
        val plates = if (forSlice) listOf(currentPlate) else plateNames.indices.toList()
        // Sliced for a printer that offers no mixing, an object or painted area on a mixed filament prints with the
        // default slot.
        val unoffered = if (forSlice && offeredMixed().isEmpty()) mixed.indices.map { mixedSlot(it) }.toSet() else emptySet()
        fun sliceSlot(slot: Int) = if (slot in unoffered) 1 else slot
        val m = (manifest ?: app.library.newManifest(name, app.version)).let { base ->
            val p = printer()
            base.copy(name = name, revision = base.revision + if (dirty || manifest == null) 1 else 0,
                modifiedBy = ProjectManifest.Producer("Nozzle It All", "desktop", app.version), modifiedAtMillis = System.currentTimeMillis(),
                printer = ProjectManifest.PrinterTarget(profile?.model ?: p?.config?.identity?.model ?: "Unknown printer", base.printer?.firmware, p?.config?.identity?.id,
                    unknown = base.printer?.unknown ?: org.json.JSONObject(), profileId = profileId,
                    family = p?.config?.identity?.family?.id ?: profile?.familyHint,
                    nozzleDiameters = machineVariant()?.let { m -> PrinterSetup.nozzles(machineJson()).map { it.toDoubleOrNull() ?: 0.4 } } ?: base.printer?.nozzleDiameters.orEmpty()),
                plates = plates.mapIndexed { n, i -> ProjectManifest.PlateEntry(n + 1, plateNames[i], plateItems(i).map {
                    ProjectManifest.ObjectEntry(it.id, it.name, sliceSlot(it.slot), paintSlots = it.paintSlots.map { s -> if (s in unoffered) sliceSlot(it.slot) else s }, settings = it.settings.toMap(), parts = it.parts.toList(), heightRanges = it.ranges.toList(), layerProfile = it.layerProfile.toList(), instanceOf = it.instanceOf) },
                    base.plates.getOrNull(i)?.unknown ?: org.json.JSONObject()) },
                materials = materials(),
                settings = ProjectManifest.SettingsChoice(currentProcess()?.let { "process:${it.id}" } ?: preset.name.lowercase(),
                    mapOf("sparse_infill_density" to "$infill%", "enable_support" to if (supports) "1" else "0") + (if (library() == null) preset.overrides else emptyMap()) + overrides +
                    (if (mixed.isEmpty()) emptyMap() else mixedSettings) + printerOverrides(),
                    base.settings.unknown))
        }
        val files = passthrough
        val objects = plates.flatMap { i ->
            val (ox, oy) = if (forSlice) 0.0 to 0.0 else Plates.origin(i, plateNames.size, bw.toDouble(), bd.toDouble())
            plateItems(i).map { ModelObject(it.id, it.name, it.mesh, Transform(it.placement().m.copyOf().also { t -> t[9] += ox; t[10] += oy }),
                sliceSlot(it.slot), it.settings.toMap(), if (forSlice) 0 else i) }
        }
        return Project3mf(objects, metadata + ("Title" to name) + ("Application" to "Nozzle It All ${app.version}"),
            m, files, plateNames = if (forSlice || plateNames.size < 2) emptyList() else plateNames.toList())
    }

    fun save(): File {
        val target = file ?: app.library.fileFor(name)
        val project = toProject()
        app.library.save(project, target)
        file = target; manifest = project.manifest; dirty = false
        return target
    }

    fun slice(scope: kotlinx.coroutines.CoroutineScope) {
        val bin = SliceEngine.locateEngine() ?: run { slice = SliceState.Failed("The slicing engine isn't installed with this copy of Nozzle It All. Reinstall the Desktop package."); return }
        if (plateItems().isEmpty()) { slice = SliceState.Failed("Add a model to ${plateNames.getOrNull(currentPlate) ?: "the plate"} first."); return }
        outOfBounds().takeIf { it.isNotEmpty() }?.let { slice = SliceState.Failed("${it.joinToString { o -> o.name }} is off the plate. Move it or use Arrange."); return }
        val e = SliceEngine(bin, app.paths.slices).also { engine = it }
        val mixRows = offeredMixed(); val physical = typedPhysical(); val sublayer = mixSublayers
        slice = SliceState.Running(0f, "Starting")
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                // The engine works out the per-filament settings of the mixed filaments (colours, components, ratios, gradients).
                val mixSettings = if (mixRows.isEmpty()) Result.success(emptyMap()) else runCatching { MixedFilament.sliceOverrides(physical, mixRows, sublayer) }
                mixSettings.fold({ settings ->
                    val req = SliceRequest(toProject(forSlice = true), profileDir(), preset, supports, infill, materials(), applyPreset = library() == null, flushMatrix = editedFlush(),
                        extraOverrides = printerOverrides() + (overrides.toMap() - FLUSH_KEY) + settings)
                    e.slice(req) { p, s -> slice = SliceState.Running(p, s) }
                }, { SliceOutcome.Failed(it.message ?: "Mixed filaments could not be set up.", "") })
            }
            slice = when (outcome) {
                is SliceOutcome.Done -> SliceState.Done(outcome, withContext(Dispatchers.IO) { runCatching { GcodePreview.parse(outcome.gcode) }.getOrNull() })
                    .also { previewLayer = (it.preview?.layers?.size ?: 1) - 1; showPreview = true }
                is SliceOutcome.Failed -> SliceState.Failed(outcome.message)
                SliceOutcome.Cancelled -> SliceState.Cancelled
            }
        }
    }

    fun cancelSlice() { engine?.cancel() }

    /** Uploads the sliced plate to the printer. Starting the print is a separate, confirmed action. */
    suspend fun upload(entry: PrinterEntry): UploadResult {
        val done = slice as? SliceState.Done ?: return UploadResult.Failed("Slice the plate first.")
        val session = entry.session ?: return UploadResult.Failed("${entry.config.identity.displayName} isn't connected.")
        val remote = "nozzle/" + name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60) + ".gcode"
        uploadProgress = 0f
        val r = withContext(Dispatchers.IO) { session.upload(done.result.gcode, remote) { s, t -> uploadProgress = if (t > 0) s.toFloat() / t else null } }
        uploadProgress = null
        return r
    }
}

/** The 2D convex hull (monotone chain) of a mesh's vertices seen from above, relative to ([cx], [cy]), as x,y pairs. */
fun hull2d(v: FloatArray, cx: Float, cy: Float): FloatArray {
    val n = v.size / 3
    if (n == 0) return FloatArray(0)
    val idx = (0 until n).sortedWith(compareBy<Int>({ v[it * 3] }, { v[it * 3 + 1] }))
    fun cross(o: Int, a: Int, b: Int) = (v[a * 3] - v[o * 3]) * (v[b * 3 + 1] - v[o * 3 + 1]) - (v[a * 3 + 1] - v[o * 3 + 1]) * (v[b * 3] - v[o * 3])
    val h = IntArray(2 * n); var k = 0
    for (i in idx) { while (k >= 2 && cross(h[k - 2], h[k - 1], i) <= 0) k--; h[k++] = i }
    val lower = k + 1
    for (i in idx.asReversed()) { while (k >= lower && cross(h[k - 2], h[k - 1], i) <= 0) k--; h[k++] = i }
    val out = FloatArray(2 * maxOf(k - 1, 1))
    for (j in 0 until maxOf(k - 1, 1)) { out[j * 2] = v[h[j] * 3] - cx; out[j * 2 + 1] = v[h[j] * 3 + 1] - cy }
    return out
}

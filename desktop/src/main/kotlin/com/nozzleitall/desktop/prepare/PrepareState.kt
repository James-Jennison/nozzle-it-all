package com.nozzleitall.desktop.prepare

import com.nozzleitall.printer.ext.PrusaColorMixFormat
import com.nozzleitall.printer.ext.FullSpectrumFormat
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
    /** The plate it's on (0-based); x and y are on that plate. */
    var plate by mutableStateOf(plate)
    /** The object's own print settings (Orca's per-object settings), engine keys to serialized values. */
    val settings = androidx.compose.runtime.mutableStateMapOf<String, String>().apply { putAll(settings) }
    var name by mutableStateOf(name); var x by mutableStateOf(x); var y by mutableStateOf(y)
    var rotZ by mutableStateOf(rotZ); var scale by mutableStateOf(scale); var slot by mutableStateOf(slot)
    val paintSlots = androidx.compose.runtime.mutableStateListOf<Int>().apply { addAll(paintSlots) }
    /** The file's filament for unpainted areas, when the file said (a model from another slicer). */
    var ownFilament: Int? = null
    /** The file's filament numbers this model's paint uses, in order. */
    val painted: List<Int> by lazy { MeshIO.paintedFilaments(mesh) }
    /** The slot the file's filament [n] prints with. */
    fun slotFor(n: Int): Int = if (n == 0) slot else paintSlots.getOrNull(n - 1) ?: slot
    val bounds = mesh.bounds()
    /** The outline seen from above (convex hull, centred on the mesh's footprint centre), so rotation is measured exactly. */
    private val outline: FloatArray by lazy { hull2d(mesh.vertices, (bounds[0] + bounds[3]) / 2, (bounds[1] + bounds[4]) / 2) }
    /** How far the object reaches from (x, y) at its current turn and scale: min x, max x, min y, max y. */
    val reach: FloatArray get() {
        val c = cos(Math.toRadians(rotZ.toDouble())).toFloat() * scale; val s = sin(Math.toRadians(rotZ.toDouble())).toFloat() * scale
        val r = floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in outline.indices step 2) { val px = outline[i] * c - outline[i + 1] * s; val py = outline[i] * s + outline[i + 1] * c
            r[0] = min(r[0], px); r[1] = max(r[1], px); r[2] = min(r[2], py); r[3] = max(r[3], py) }
        return r
    }
    val footprintW get() = reach.let { it[1] - it[0] }
    val footprintD get() = reach.let { it[3] - it[2] }
    val height get() = (bounds[5] - bounds[2]) * scale

    /** The mesh's volume in mm³ before scaling (signed tetrahedra; a closed mesh's is exact). */
    val rawVolume: Double by lazy {
        val v = mesh.vertices; val t = mesh.triangles; var sum = 0.0
        for (i in t.indices step 3) {
            val a = t[i] * 3; val b = t[i + 1] * 3; val c = t[i + 2] * 3
            sum += (v[a] * (v[b + 1] * v[c + 2] - v[b + 2] * v[c + 1]) - v[a + 1] * (v[b] * v[c + 2] - v[b + 2] * v[c]) + v[a + 2] * (v[b] * v[c + 1] - v[b + 1] * v[c])).toDouble()
        }
        kotlin.math.abs(sum / 6.0)
    }
    val volume get() = rawVolume * scale * scale * scale

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
        val c = cos(Math.toRadians(rotZ.toDouble())) * scale; val s = sin(Math.toRadians(rotZ.toDouble())) * scale
        val tx = -(mx * c - my * s) + x; val ty = -(mx * s + my * c) + y; val tz = -mz * scale
        return Transform(doubleArrayOf(c, s, 0.0, -s, c, 0.0, 0.0, 0.0, scale.toDouble(), tx, ty, tz))
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

    // --- Colour mixing (Snapmaker Full Spectrum). The mixes are Snapmaker's own mixed_filament_definitions string; the
    // engine (Snapmaker Orca's MixedFilamentManager) turns it into rows with virtual slot numbers after the physical ones.
    var mixDefinitions by mutableStateOf("")
    val mixes = mutableStateListOf<FullSpectrumFormat.Mix>()
    var mixProblem by mutableStateOf<String?>(null)

    // --- PrusaSlicer ColorMix (virtual extruders), for multi-slot printers without Full Spectrum. Stored as PrusaSlicer's
    // own 3MF sidecar, so a project opens in PrusaSlicer with the same virtual extruders and paint.
    val colorMix = mutableStateListOf<PrusaColorMixFormat.Virtual>()

    fun setColorMix(list: List<PrusaColorMixFormat.Virtual>) { colorMix.clear(); colorMix.addAll(list.sortedBy { it.id }) }

    /** Re-reads the virtual extruders through PrusaSlicer's own normalising for the current slots (colours, cycles). */
    fun refreshColorMix(scope: kotlinx.coroutines.CoroutineScope) {
        if (colorMix.isEmpty()) return
        val physical = materials().map { (it.colorHex ?: "#FFFFFF") to it.type }; val list = colorMix.toList()
        scope.launch { runCatching { withContext(Dispatchers.IO) { PrusaColorMix.normalize(physical, list) } }
            .onSuccess { setColorMix(it) }.onFailure { mixProblem = it.message } }
    }

    /** Adds or replaces a virtual extruder, then re-reads the list through PrusaSlicer's normalising. */
    fun saveVirtualExtruder(v: PrusaColorMixFormat.Virtual, scope: kotlinx.coroutines.CoroutineScope) {
        setColorMix(colorMix.filter { it.id != v.id } + v); changed(); refreshColorMix(scope)
    }

    /** Removes a virtual extruder; objects and painted areas on it go back to the default extruder, as in PrusaSlicer. */
    fun removeVirtualExtruder(id: Int) {
        setColorMix(colorMix.filter { it.id != id })
        items.forEach { item ->
            if (item.slot == id) item.slot = 1
            for (i in item.paintSlots.indices) if (item.paintSlots[i] == id) item.paintSlots[i] = item.slot
        }
        changed()
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
    fun features(): Set<String> = printer()?.capabilities?.value?.vendorExtensions?.let { com.nozzleitall.printer.ext.ProfileFeatures.ofPrinter(it, materials().size) }
        ?: profile?.let { com.nozzleitall.printer.ext.ProfileFeatures.of(it.familyHint, it.tools) } ?: emptySet()

    fun physicalColours(): List<String> = materials().map { it.colorHex ?: "#FFFFFF" }

    /** The colour of slot [id]: a loaded filament, or a mix's (or virtual extruder's) blended display colour. */
    fun slotHex(id: Int): String? = materials().firstOrNull { it.slot == id }?.colorHex ?: mixes.firstOrNull { it.id == id }?.displayHex
        ?: colorMix.firstOrNull { it.id == id }?.let { it.colorOverride ?: it.effectiveHex }

    /** Every slot a model can print with: the loaded filaments, then the mixes. */
    fun allSlots(): List<Pair<Int, String>> = materials().map { it.slot to listOfNotNull(it.vendor, it.type).joinToString(" ").ifBlank { "Filament" } } +
        mixes.filter { it.enabled }.map { it.id to it.label } + colorMix.map { it.id to "[V] Extruder ${it.id}" }

    private fun setMixes(m: FullSpectrumFormat.Mixes) { mixDefinitions = m.definitions; mixes.clear(); mixes.addAll(m.rows); mixProblem = null }

    /** Re-reads the mixes for the current slots (their colours and numbers follow the loaded filaments). */
    fun refreshMixes(scope: kotlinx.coroutines.CoroutineScope) {
        val defs = mixDefinitions; val physical = physicalColours()
        if (defs.isBlank()) { mixes.clear(); return }
        scope.launch { runCatching { withContext(Dispatchers.IO) { FullSpectrum.display(physical, defs) } }
            .onSuccess { setMixes(it) }.onFailure { mixProblem = it.message } }
    }

    fun editMixes(scope: kotlinx.coroutines.CoroutineScope, op: (List<String>, String) -> FullSpectrumFormat.Mixes) {
        val defs = mixDefinitions; val physical = physicalColours()
        scope.launch { runCatching { withContext(Dispatchers.IO) { op(physical, defs) } }
            .onSuccess { setMixes(it); followRemap(it.remap); changed() }.onFailure { mixProblem = it.message } }
    }

    /**
     * Every model follows Snapmaker's renumbering after mixes are deleted: a colour on a mix that moved follows it; one
     * on a deleted mix (mapped to 0) goes back to the object's own slot, and an object on a deleted mix to slot 1.
     */
    fun followRemap(remap: Map<Int, Int>) {
        if (remap.isEmpty()) return
        items.forEach { item ->
            item.slot = remap[item.slot]?.let { if (it == 0) 1 else it } ?: item.slot
            for (i in item.paintSlots.indices) remap[item.paintSlots[i]]?.let { item.paintSlots[i] = if (it == 0) item.slot else it }
        }
    }

    /** The slots the project uses: every object's own slot and every slot its colours print in. */
    fun usedSlots(): Set<Int> = items.flatMapTo(HashSet()) { item -> listOf(item.slot) + item.painted.map { item.slotFor(it) } }

    /** Applies a Color Mixing Match to [item]: its colours print in the matched slots or new mixes. */
    fun applyMatch(item: PrepItem, result: FullSpectrumFormat.MatchResult, scope: kotlinx.coroutines.CoroutineScope) {
        mixDefinitions = result.definitions
        // Auto mode matched against Snapmaker's recommended filaments: with no printer reporting what's loaded, the slots
        // become those filaments (as Snapmaker Orca's apply step sets them); a connected printer's slots are what's loaded.
        result.palette.forEach { (slot, hex) ->
            // Each palette slot slices with its Full Spectrum family's filament profile for this printer
            // (find_selectable_full_spectrum_family_preset: the family's profile by match name).
            val family = result.families[slot]
            val entry = family?.let { f -> filamentLibrary().firstOrNull { it.family.equals(f, true) } }
            if (printer()?.status?.value?.toolheads.isNullOrEmpty()) {
                val i = manualSlots.indexOfFirst { it.slot == slot }
                if (i >= 0) manualSlots[i] = manualSlots[i].copy(type = entry?.type ?: "PLA", vendor = entry?.vendor?.takeIf { it.isNotBlank() },
                    colorHex = hex, filamentProfile = entry?.id ?: manualSlots[i].filamentProfile)
            } else entry?.let { headProfiles[slot] = it.id }
        }
        result.results.forEach { m -> m.sourceIds.forEach { n ->
            if (n == item.ownFilament) item.slot = m.slot
            if (n >= 1) { while (item.paintSlots.size < n) item.paintSlots.add(item.slot); item.paintSlots[n - 1] = m.slot }
        } }
        // Snapmaker Orca then deletes mixes the project no longer uses (cleanup_unused_filaments_after_batch_match).
        val physical = physicalColours(); val defs = mixDefinitions; val used = usedSlots()
        changed()
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { FullSpectrum.cleanup(physical, defs, used) } }
                .onSuccess { setMixes(it); followRemap(it.remap) }.onFailure { mixProblem = it.message; refreshMixes(scope) }
        }
    }

    fun newProject() {
        bedType = null; nozzleFlows.clear(); nozzle = null; processId = null
        overrides.clear(); mixDefinitions = ""; mixes.clear(); mixProblem = null; headProfiles.clear(); colorMix.clear()
        items.clear(); selected = null; file = null; manifest = null; name = "Untitled project"; dirty = false; passthrough = emptyMap(); metadata = emptyMap()
        plateNames.clear(); plateNames += "Plate 1"; currentPlate = 0
        slice = SliceState.Idle; showPreview = false; nextId = 1
    }

    fun open(f: File) {
        val p = ThreeMf.read(f)
        newProject()
        file = f; manifest = p.manifest; name = p.manifest?.name ?: p.metadata["Title"] ?: f.nameWithoutExtension
        passthrough = p.passthrough; metadata = p.metadata
        // PrusaSlicer's virtual extruders, when the project has them (its own sidecar, kept as it is).
        p.passthrough[PrusaColorMix.SIDECAR]?.let { PrusaColorMix.readSidecar(it) }?.let { (_, list) -> setColorMix(list) }
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
        }
        p.manifest?.settings?.preset?.let { key ->
            if (key.startsWith("process:")) processId = key.removePrefix("process:")
            else QualityPreset.entries.firstOrNull { it.name.equals(key, true) }?.let { preset = it } }
        p.manifest?.printer?.nozzleDiameters?.firstOrNull()?.let { d -> nozzle = if (d == Math.floor(d)) d.toInt().toString() else d.toString() }
        p.manifest?.settings?.overrides?.let { ov ->
            ov["sparse_infill_density"]?.removeSuffix("%")?.toIntOrNull()?.let { infill = it }; ov["enable_support"]?.let { supports = it == "1" }
            // Everything else the project changed (from any platform) shows up in All settings.
            ov[FullSpectrum.DEFINITIONS_KEY]?.let { mixDefinitions = it }
            ov["curr_bed_type"]?.let { bedType = it }
            ov["nozzle_volume_type"]?.let { v -> nozzleFlows.clear(); nozzleFlows.addAll(v.split(',').map { it.trim() }) }
            ov.filter { (k, v) -> k != "sparse_infill_density" && k != "enable_support" && k != FullSpectrum.DEFINITIONS_KEY && k != "curr_bed_type" &&
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
        val b = o.mesh.bounds(); val cx = (b[0] + b[3]) / 2.0; val cy = (b[1] + b[4]) / 2.0
        val p = o.placement.apply(cx, cy, 0.0)
        val item = PrepItem(o.id, o.name, o.mesh, p[0].toFloat(), p[1].toFloat(), rot, scale, slot, paintSlots, sources, settings, plate)
        // A painted model from another slicer (or saved before colours were mapped) gets its colours matched to the slots.
        if (item.painted.isNotEmpty() && item.paintSlots.size < item.painted.max()) matchColours(item, o.filament)
        items += item
        nextId = maxOf(nextId, o.id + 1)
    }

    fun importModel(f: File) {
        val loaded = MeshIO.load(f)
        val (bw, bd) = bed
        val item = PrepItem(nextId++, f.nameWithoutExtension, loaded.mesh, bw / 2, bd / 2, sources = loaded.filaments, plate = currentPlate)
        // A PrusaSlicer file's virtual extruders come with it (into an empty list; the first file's win), their ids moved
        // clear of this printer's extruders by PrusaSlicer's own import remap; the paint follows.
        var virtualRemap = emptyMap<Int, Int>()
        if (loaded.colorMix.isNotEmpty() && colorMix.isEmpty()) {
            val remapped = loaded.colorMixSidecar?.let { runCatching { PrusaColorMix.remapImport(materials().size, it) }.getOrNull() }
            setColorMix(remapped?.first ?: loaded.colorMix); virtualRemap = remapped?.second.orEmpty()
        }
        if (item.painted.isNotEmpty() || loaded.filament != null) matchColours(item, loaded.filament)
        virtualRemap.forEach { (old, new) -> if (old in 1..item.paintSlots.size) item.paintSlots[old - 1] = new }
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
        // A virtual extruder in the file prints as that virtual extruder (PrusaSlicer paints with its id).
        item.paintSlots.addAll(slots.mapIndexed { i, s -> if (colorMix.any { it.id == i + 1 }) i + 1 else s })
        item.ownFilament?.let { f -> slots.getOrNull(f - 1)?.let { item.slot = it } }
    }

    /** Sets which slot the file's filament [n] of [item] prints with. */
    fun setPaintSlot(item: PrepItem, n: Int, slot: Int) {
        while (item.paintSlots.size < n) item.paintSlots.add(item.slot)
        item.paintSlots[n - 1] = slot; changed()
    }

    fun duplicateSelected() { items.firstOrNull { it.id == selected }?.let { s -> items += PrepItem(nextId++, s.name + " copy", s.mesh, s.x + 10, s.y + 10, s.rotZ, s.scale, s.slot, s.paintSlots.toList(), s.sources, s.settings.toMap(), s.plate); changed(); arrange() } }
    fun removeSelected() { items.removeAll { it.id == selected }; selected = null; changed() }
    fun changed() { dirty = true; invalidateSlice() }
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
        PrepItem(id, name, mesh, x, y, old.rotZ, old.scale, old.slot, old.paintSlots.toList(), old.sources, old.settings.toMap(), old.plate).also { it.ownFilament = old.ownFilament }

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
                    (printerOverrides() + overrides.toMap() - FLUSH_KEY).filterKeys { it != FullSpectrum.DEFINITIONS_KEY }, snapshot, bw, bd, work = plateWork())
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
            val z = item.bounds[2] + heightMm / item.scale
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
        val m = (manifest ?: app.library.newManifest(name, app.version)).let { base ->
            val p = printer()
            base.copy(name = name, revision = base.revision + if (dirty || manifest == null) 1 else 0,
                modifiedBy = ProjectManifest.Producer("Nozzle It All", "desktop", app.version), modifiedAtMillis = System.currentTimeMillis(),
                printer = ProjectManifest.PrinterTarget(profile?.model ?: p?.config?.identity?.model ?: "Unknown printer", base.printer?.firmware, p?.config?.identity?.id,
                    unknown = base.printer?.unknown ?: org.json.JSONObject(), profileId = profileId,
                    family = p?.config?.identity?.family?.id ?: profile?.familyHint,
                    nozzleDiameters = machineVariant()?.let { m -> PrinterSetup.nozzles(machineJson()).map { it.toDoubleOrNull() ?: 0.4 } } ?: base.printer?.nozzleDiameters.orEmpty()),
                plates = plates.mapIndexed { n, i -> ProjectManifest.PlateEntry(n + 1, plateNames[i], plateItems(i).map {
                    ProjectManifest.ObjectEntry(it.id, it.name, it.slot, paintSlots = it.paintSlots.toList(), settings = it.settings.toMap()) },
                    base.plates.getOrNull(i)?.unknown ?: org.json.JSONObject()) },
                materials = materials(),
                settings = ProjectManifest.SettingsChoice(currentProcess()?.let { "process:${it.id}" } ?: preset.name.lowercase(),
                    mapOf("sparse_infill_density" to "$infill%", "enable_support" to if (supports) "1" else "0") + (if (library() == null) preset.overrides else emptyMap()) + overrides +
                    (if (mixDefinitions.isNotBlank()) mapOf(FullSpectrum.DEFINITIONS_KEY to mixDefinitions) else emptyMap()) + printerOverrides(),
                    base.settings.unknown))
        }
        // PrusaSlicer's ColorMix sidecar is written from the current virtual extruders (and dropped when there are none).
        val files = if (colorMix.isEmpty()) passthrough - PrusaColorMix.SIDECAR
            else passthrough + (PrusaColorMix.SIDECAR to PrusaColorMix.sidecar(materials().map { it.colorHex ?: "#FFFFFF" }, colorMix.toList()).toByteArray())
        val objects = plates.flatMap { i ->
            val (ox, oy) = if (forSlice) 0.0 to 0.0 else Plates.origin(i, plateNames.size, bw.toDouble(), bd.toDouble())
            plateItems(i).map { ModelObject(it.id, it.name, it.mesh, Transform(it.placement().m.copyOf().also { t -> t[9] += ox; t[10] += oy }),
                it.slot, it.settings.toMap(), if (forSlice) 0 else i) }
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
        val req = SliceRequest(toProject(forSlice = true), profileDir(), preset, supports, infill, materials(), applyPreset = library() == null, flushMatrix = editedFlush(),
            extraOverrides = printerOverrides() + (overrides.toMap() - FLUSH_KEY) +
            (if (mixDefinitions.isNotBlank()) mapOf(FullSpectrum.DEFINITIONS_KEY to mixDefinitions) else emptyMap()),
            virtualExtruders = colorMix.takeIf { it.isNotEmpty() }?.let { PrusaColorMix.sidecar(materials().map { m -> m.colorHex ?: "#FFFFFF" }, it.toList()) })
        slice = SliceState.Running(0f, "Starting")
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { e.slice(req) { p, s -> slice = SliceState.Running(p, s) } }
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

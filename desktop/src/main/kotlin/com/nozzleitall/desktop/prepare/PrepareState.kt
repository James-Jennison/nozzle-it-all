package com.nozzleitall.desktop.prepare

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

/**
 * An object on the plate. [slot] is what it prints with where it isn't painted. A painted model keeps the file's own
 * filament numbers in its paint; [paintSlots] (entry N-1 for the file's filament N) says which slot each one prints with,
 * and [sources] describes those filaments as the file had them.
 */
class PrepItem(val id: Int, name: String, val mesh: Mesh, x: Float, y: Float, rotZ: Float = 0f, scale: Float = 1f, slot: Int = 1,
               paintSlots: List<Int> = emptyList(), val sources: List<SourceFilament> = emptyList()) {
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
    val manualSlots = mutableStateListOf(ProjectManifest.MaterialSlot(1, "PLA", colorHex = "#A78BFA"), ProjectManifest.MaterialSlot(2, "PLA", colorHex = "#F2754E"),
        ProjectManifest.MaterialSlot(3, "PLA", colorHex = "#EEF2F4"), ProjectManifest.MaterialSlot(4, "PLA", colorHex = "#1E2429"))
    private var engine: SliceEngine? = null
    private var nextId = 1

    val profile: PrinterProfileInfo? get() = ProfileCatalog.byId(profileId)
    /** The chosen profile's printable area. */
    val bed: Pair<Float, Float> get() = profile?.let { it.bedW to it.bedD } ?: (270f to 270f)

    fun printer(): PrinterEntry? = printerId?.let { app.fleet.printers[it] }

    /** Choosing a saved printer also chooses its slicing profile; choosing "no printer" keeps the profile. */
    fun choosePrinter(id: String?) {
        printerId = id
        app.fleet.printers[id]?.config?.identity?.profileId?.let { profileId = it }
        changed()
    }

    /** Materials in slot order: the printer's loaded toolheads when it reports them, otherwise the manual slots. */
    fun materials(): List<ProjectManifest.MaterialSlot> {
        val heads = printer()?.status?.value?.toolheads.orEmpty()
        if (heads.isNotEmpty()) return heads.map { t -> ProjectManifest.MaterialSlot(t.index + 1, t.material?.type ?: "PLA", t.material?.vendor, t.material?.subType,
            t.material?.colorHex ?: manualSlots.getOrNull(t.index)?.colorHex, t.index) }
        // No printer report: one slot per tool the profile has (a U1 has four, most printers one).
        val tools = (profile?.tools ?: 1).coerceIn(1, manualSlots.size)
        return manualSlots.take(tools)
    }

    fun profileDir(): File = ProfileCatalog.materialize(app.paths.cache, profileId)

    // --- Colour mixing (Snapmaker Full Spectrum). The mixes are Snapmaker's own mixed_filament_definitions string; the
    // engine (Snapmaker Orca's MixedFilamentManager) turns it into rows with virtual slot numbers after the physical ones.
    var mixDefinitions by mutableStateOf("")
    val mixes = mutableStateListOf<FullSpectrum.Mix>()
    var mixProblem by mutableStateOf<String?>(null)

    /** Colour-mixing features on offer: the connected printer's own report, else what the chosen profile offers. */
    fun features(): Set<String> = printer()?.capabilities?.value?.vendorExtensions
        ?: profile?.let { com.nozzleitall.printer.ext.ProfileFeatures.of(it.familyHint, it.tools) } ?: emptySet()

    fun physicalColours(): List<String> = materials().map { it.colorHex ?: "#FFFFFF" }

    /** The colour of slot [id]: a loaded filament, or a mix's blended display colour. */
    fun slotHex(id: Int): String? = materials().firstOrNull { it.slot == id }?.colorHex ?: mixes.firstOrNull { it.id == id }?.displayHex

    /** Every slot a model can print with: the loaded filaments, then the mixes. */
    fun allSlots(): List<Pair<Int, String>> = materials().map { it.slot to listOfNotNull(it.vendor, it.type).joinToString(" ").ifBlank { "Filament" } } +
        mixes.filter { it.enabled }.map { it.id to it.label }

    private fun setMixes(m: FullSpectrum.Mixes) { mixDefinitions = m.definitions; mixes.clear(); mixes.addAll(m.rows); mixProblem = null }

    /** Re-reads the mixes for the current slots (their colours and numbers follow the loaded filaments). */
    fun refreshMixes(scope: kotlinx.coroutines.CoroutineScope) {
        val defs = mixDefinitions; val physical = physicalColours()
        if (defs.isBlank()) { mixes.clear(); return }
        scope.launch { runCatching { withContext(Dispatchers.IO) { FullSpectrum.display(physical, defs) } }
            .onSuccess { setMixes(it) }.onFailure { mixProblem = it.message } }
    }

    fun editMixes(scope: kotlinx.coroutines.CoroutineScope, op: (List<String>, String) -> FullSpectrum.Mixes) {
        val defs = mixDefinitions; val physical = physicalColours()
        scope.launch { runCatching { withContext(Dispatchers.IO) { op(physical, defs) } }
            .onSuccess { setMixes(it); changed() }.onFailure { mixProblem = it.message } }
    }

    /** Applies a Color Mixing Match to [item]: its colours print in the matched slots or new mixes. */
    fun applyMatch(item: PrepItem, result: FullSpectrum.MatchResult, scope: kotlinx.coroutines.CoroutineScope) {
        mixDefinitions = result.definitions
        // Auto mode matched against Snapmaker's recommended filaments: with no printer reporting what's loaded, the slots
        // become those filaments (as Snapmaker Orca's apply step sets them); a connected printer's slots are what's loaded.
        if (printer()?.status?.value?.toolheads.isNullOrEmpty()) result.palette.forEach { (slot, hex) ->
            val i = manualSlots.indexOfFirst { it.slot == slot }
            if (i >= 0) manualSlots[i] = manualSlots[i].copy(type = "PLA", colorHex = hex)
        }
        result.results.forEach { m -> m.sourceIds.forEach { n ->
            if (n == item.ownFilament) item.slot = m.slot
            if (n >= 1) { while (item.paintSlots.size < n) item.paintSlots.add(item.slot); item.paintSlots[n - 1] = m.slot }
        } }
        changed(); refreshMixes(scope)
    }

    fun newProject() {
        overrides.clear(); mixDefinitions = ""; mixes.clear(); mixProblem = null
        items.clear(); selected = null; file = null; manifest = null; name = "Untitled project"; dirty = false; passthrough = emptyMap(); metadata = emptyMap()
        slice = SliceState.Idle; showPreview = false; nextId = 1
    }

    fun open(f: File) {
        val p = ThreeMf.read(f)
        newProject()
        file = f; manifest = p.manifest; name = p.manifest?.name ?: p.metadata["Title"] ?: f.nameWithoutExtension
        passthrough = p.passthrough; metadata = p.metadata
        val entries = p.manifest?.plates?.flatMap { it.objects }?.associateBy { it.objectId } ?: emptyMap()
        p.objects.forEach { o -> addFromPlacement(o, entries[o.id]?.materialSlot ?: 1, entries[o.id]?.paintSlots.orEmpty(), p.filaments) }
        p.manifest?.settings?.preset?.let { key -> QualityPreset.entries.firstOrNull { it.name.equals(key, true) }?.let { preset = it } }
        p.manifest?.settings?.overrides?.let { ov ->
            ov["sparse_infill_density"]?.removeSuffix("%")?.toIntOrNull()?.let { infill = it }; ov["enable_support"]?.let { supports = it == "1" }
            // Everything else the project changed (from any platform) shows up in All settings.
            ov[FullSpectrum.DEFINITIONS_KEY]?.let { mixDefinitions = it }
            ov.filter { (k, v) -> k != "sparse_infill_density" && k != "enable_support" && k != FullSpectrum.DEFINITIONS_KEY && preset.overrides[k] != v }.forEach { (k, v) -> overrides[k] = v }
        }
        p.manifest?.printer?.profileId?.takeIf { ProfileCatalog.byId(it) != null }?.let { profileId = it }
        p.manifest?.printer?.printerId?.takeIf { it in app.fleet.printers }?.let { printerId = it }
        notice = p.manifestProblem
        dirty = false
    }

    /** Reverses [PrepItem.placement] for a mesh loaded from a 3MF: recovers bed position, Z rotation and uniform scale. */
    private fun addFromPlacement(o: ModelObject, slot: Int, paintSlots: List<Int>, sources: List<SourceFilament>) {
        val m = o.placement.m
        val scale = sqrt(m[0] * m[0] + m[1] * m[1]).toFloat().takeIf { it > 0 } ?: 1f
        val rot = Math.toDegrees(atan2(m[1], m[0])).toFloat()
        val b = o.mesh.bounds(); val cx = (b[0] + b[3]) / 2.0; val cy = (b[1] + b[4]) / 2.0
        val p = o.placement.apply(cx, cy, 0.0)
        val item = PrepItem(o.id, o.name, o.mesh, p[0].toFloat(), p[1].toFloat(), rot, scale, slot, paintSlots, sources)
        // A painted model from another slicer (or saved before colours were mapped) gets its colours matched to the slots.
        if (item.painted.isNotEmpty() && item.paintSlots.size < item.painted.max()) matchColours(item, o.filament)
        items += item
        nextId = maxOf(nextId, o.id + 1)
    }

    fun importModel(f: File) {
        val loaded = MeshIO.load(f)
        val (bw, bd) = bed
        val item = PrepItem(nextId++, f.nameWithoutExtension, loaded.mesh, bw / 2, bd / 2, sources = loaded.filaments)
        if (item.painted.isNotEmpty() || loaded.filament != null) matchColours(item, loaded.filament)
        items += item
        arrange(); dirty = true; invalidateSlice()
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
        item.paintSlots.clear(); item.paintSlots.addAll(slots)
        item.ownFilament?.let { f -> slots.getOrNull(f - 1)?.let { item.slot = it } }
    }

    /** Sets which slot the file's filament [n] of [item] prints with. */
    fun setPaintSlot(item: PrepItem, n: Int, slot: Int) {
        while (item.paintSlots.size < n) item.paintSlots.add(item.slot)
        item.paintSlots[n - 1] = slot; changed()
    }

    fun duplicateSelected() { items.firstOrNull { it.id == selected }?.let { s -> items += PrepItem(nextId++, s.name + " copy", s.mesh, s.x + 10, s.y + 10, s.rotZ, s.scale, s.slot, s.paintSlots.toList(), s.sources); arrange(); changed() } }
    fun removeSelected() { items.removeAll { it.id == selected }; selected = null; changed() }
    fun changed() { dirty = true; invalidateSlice() }
    private fun invalidateSlice() { if (slice !is SliceState.Running) { slice = SliceState.Idle; showPreview = false } }

    /** Shelf packing by footprint, largest first, with a 6 mm gap, centred on the bed. Objects that can't fit are reported. */
    fun arrange(): Boolean {
        val (bw, bd) = bed; val gap = 6f
        val order = items.sortedByDescending { it.footprintD }
        var x = gap; var y = gap; var row = 0f; var fits = true
        order.forEach { it ->
            if (x + it.footprintW > bw - gap) { x = gap; y += row + gap; row = 0f }
            val r = it.reach; it.x = x - r[0]; it.y = y - r[2]
            x += it.footprintW + gap; row = max(row, it.footprintD)
            if (y + it.footprintD > bd - gap || it.footprintW > bw - 2 * gap) fits = false
        }
        val usedW = items.maxOfOrNull { it.x + it.reach[1] } ?: 0f; val usedD = items.maxOfOrNull { it.y + it.reach[3] } ?: 0f
        val dx = (bw - usedW - gap) / 2; val dy = (bd - usedD - gap) / 2
        if (dx > 0 && dy > 0) items.forEach { it.x += dx; it.y += dy }
        if (!fits) notice = "Not everything fits on one plate. Remove or scale down an object before slicing."
        changed()
        return fits
    }

    fun outOfBounds(): List<PrepItem> { val (bw, bd) = bed
        return items.filter { val r = it.reach; it.x + r[0] < 0 || it.y + r[2] < 0 || it.x + r[1] > bw || it.y + r[3] > bd || it.height > (profile?.height ?: 250f) } }

    fun toProject(): Project3mf {
        val m = (manifest ?: app.library.newManifest(name, app.version)).let { base ->
            val p = printer()
            base.copy(name = name, revision = base.revision + if (dirty || manifest == null) 1 else 0,
                modifiedBy = ProjectManifest.Producer("Nozzle It All", "desktop", app.version), modifiedAtMillis = System.currentTimeMillis(),
                printer = ProjectManifest.PrinterTarget(profile?.model ?: p?.config?.identity?.model ?: "Unknown printer", base.printer?.firmware, p?.config?.identity?.id,
                    unknown = base.printer?.unknown ?: org.json.JSONObject(), profileId = profileId,
                    family = p?.config?.identity?.family?.id ?: profile?.familyHint),
                plates = listOf(ProjectManifest.PlateEntry(1, base.plates.firstOrNull()?.name ?: "Plate 1", items.map { ProjectManifest.ObjectEntry(it.id, it.name, it.slot, paintSlots = it.paintSlots.toList()) },
                    base.plates.firstOrNull()?.unknown ?: org.json.JSONObject())),
                materials = materials(),
                settings = ProjectManifest.SettingsChoice(preset.name.lowercase(), mapOf("sparse_infill_density" to "$infill%", "enable_support" to if (supports) "1" else "0") + preset.overrides + overrides +
                    (if (mixDefinitions.isNotBlank()) mapOf(FullSpectrum.DEFINITIONS_KEY to mixDefinitions) else emptyMap()),
                    base.settings.unknown))
        }
        return Project3mf(items.map { ModelObject(it.id, it.name, it.mesh, it.placement()) }, metadata + ("Title" to name) + ("Application" to "Nozzle It All ${app.version}"),
            m, passthrough)
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
        if (items.isEmpty()) { slice = SliceState.Failed("Add a model to the plate first."); return }
        outOfBounds().takeIf { it.isNotEmpty() }?.let { slice = SliceState.Failed("${it.joinToString { o -> o.name }} is off the plate. Move it or use Arrange."); return }
        val e = SliceEngine(bin, app.paths.slices).also { engine = it }
        val req = SliceRequest(toProject(), profileDir(), preset, supports, infill, materials(), extraOverrides = overrides.toMap() +
            (if (mixDefinitions.isNotBlank()) mapOf(FullSpectrum.DEFINITIONS_KEY to mixDefinitions) else emptyMap()))
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

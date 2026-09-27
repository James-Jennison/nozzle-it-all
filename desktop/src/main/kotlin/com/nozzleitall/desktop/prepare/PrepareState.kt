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

class PrepItem(val id: Int, name: String, val mesh: Mesh, x: Float, y: Float, rotZ: Float = 0f, scale: Float = 1f, slot: Int = 1) {
    var name by mutableStateOf(name); var x by mutableStateOf(x); var y by mutableStateOf(y)
    var rotZ by mutableStateOf(rotZ); var scale by mutableStateOf(scale); var slot by mutableStateOf(slot)
    val bounds = mesh.bounds()
    val footprintW get() = (bounds[3] - bounds[0]) * scale
    val footprintD get() = (bounds[4] - bounds[1]) * scale
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

/** Everything the Prepare workflow edits. Saved as a canonical 3MF + manifest, so Android, Web and the Advanced Workspace open it. */
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
    var preset by mutableStateOf(QualityPreset.STANDARD)
    var supports by mutableStateOf(false)
    var infill by mutableStateOf(15)
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

    /** The U1's printable area. Read from the bundled machine profile when present. */
    val bed: Pair<Float, Float> by lazy {
        runCatching {
            val area = JSONObject(File(profileDir(), "machine.json").readText()).getJSONArray("printable_area")
            val pts = (0 until area.length()).map { area.getString(it).split('x').map(String::toFloat) }
            (pts.maxOf { it[0] } - pts.minOf { it[0] }) to (pts.maxOf { it[1] } - pts.minOf { it[1] })
        }.getOrDefault(270f to 270f)
    }

    fun printer(): PrinterEntry? = (printerId ?: app.fleet.order.value.firstOrNull())?.let { app.fleet.printers[it] }

    /** Materials in slot order: the printer's loaded toolheads when it reports them, otherwise the manual slots. */
    fun materials(): List<ProjectManifest.MaterialSlot> {
        val heads = printer()?.status?.value?.toolheads.orEmpty()
        if (heads.isNotEmpty()) return heads.map { t -> ProjectManifest.MaterialSlot(t.index + 1, t.material?.type ?: "PLA", t.material?.vendor, t.material?.subType,
            t.material?.colorHex ?: manualSlots.getOrNull(t.index)?.colorHex, t.index) }
        return manualSlots.toList()
    }

    fun profileDir(): File = ProfileProvisioner.ensure(app.paths.cache, "snapmaker_u1")

    fun newProject() {
        items.clear(); selected = null; file = null; manifest = null; name = "Untitled project"; dirty = false; passthrough = emptyMap(); metadata = emptyMap()
        slice = SliceState.Idle; showPreview = false; nextId = 1
    }

    fun open(f: File) {
        val p = ThreeMf.read(f)
        newProject()
        file = f; manifest = p.manifest; name = p.manifest?.name ?: p.metadata["Title"] ?: f.nameWithoutExtension
        passthrough = p.passthrough; metadata = p.metadata
        val slots = p.manifest?.plates?.flatMap { it.objects }?.associate { it.objectId to (it.materialSlot ?: 1) } ?: emptyMap()
        p.objects.forEach { o -> addFromPlacement(o, slots[o.id] ?: 1) }
        p.manifest?.settings?.preset?.let { key -> QualityPreset.entries.firstOrNull { it.name.equals(key, true) }?.let { preset = it } }
        p.manifest?.settings?.overrides?.let { ov -> ov["sparse_infill_density"]?.removeSuffix("%")?.toIntOrNull()?.let { infill = it }; ov["enable_support"]?.let { supports = it == "1" } }
        p.manifest?.printer?.printerId?.takeIf { it in app.fleet.printers }?.let { printerId = it }
        notice = p.manifestProblem
        dirty = false
    }

    /** Reverses [PrepItem.placement] for a mesh loaded from a 3MF: recovers bed position, Z rotation and uniform scale. */
    private fun addFromPlacement(o: ModelObject, slot: Int) {
        val m = o.placement.m
        val scale = sqrt(m[0] * m[0] + m[1] * m[1]).toFloat().takeIf { it > 0 } ?: 1f
        val rot = Math.toDegrees(atan2(m[1], m[0])).toFloat()
        val b = o.mesh.bounds(); val cx = (b[0] + b[3]) / 2.0; val cy = (b[1] + b[4]) / 2.0
        val p = o.placement.apply(cx, cy, 0.0)
        items += PrepItem(o.id, o.name, o.mesh, p[0].toFloat(), p[1].toFloat(), rot, scale, slot)
        nextId = maxOf(nextId, o.id + 1)
    }

    fun importModel(f: File) {
        val mesh = MeshIO.read(f)
        val (bw, bd) = bed
        items += PrepItem(nextId++, f.nameWithoutExtension, mesh, bw / 2, bd / 2)
        arrange(); dirty = true; invalidateSlice()
        if (name == "Untitled project") name = f.nameWithoutExtension
    }

    fun duplicateSelected() { items.firstOrNull { it.id == selected }?.let { s -> items += PrepItem(nextId++, s.name + " copy", s.mesh, s.x + 10, s.y + 10, s.rotZ, s.scale, s.slot); arrange(); changed() } }
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
            it.x = x + it.footprintW / 2; it.y = y + it.footprintD / 2
            x += it.footprintW + gap; row = max(row, it.footprintD)
            if (y + it.footprintD > bd - gap || it.footprintW > bw - 2 * gap) fits = false
        }
        val usedW = items.maxOfOrNull { it.x + it.footprintW / 2 } ?: 0f; val usedD = items.maxOfOrNull { it.y + it.footprintD / 2 } ?: 0f
        val dx = (bw - usedW - gap) / 2; val dy = (bd - usedD - gap) / 2
        if (dx > 0 && dy > 0) items.forEach { it.x += dx; it.y += dy }
        if (!fits) notice = "Not everything fits on one plate. Remove or scale down an object before slicing."
        changed()
        return fits
    }

    fun outOfBounds(): List<PrepItem> { val (bw, bd) = bed
        return items.filter { it.x - it.footprintW / 2 < 0 || it.y - it.footprintD / 2 < 0 || it.x + it.footprintW / 2 > bw || it.y + it.footprintD / 2 > bd || it.height > 270f } }

    fun toProject(): Project3mf {
        val m = (manifest ?: app.library.newManifest(name, app.version)).let { base ->
            val p = printer()
            base.copy(name = name, revision = base.revision + if (dirty || manifest == null) 1 else 0,
                modifiedBy = ProjectManifest.Producer("Nozzle It All", "desktop", app.version), modifiedAtMillis = System.currentTimeMillis(),
                printer = ProjectManifest.PrinterTarget(p?.config?.identity?.model ?: "Snapmaker U1", p?.config?.identity?.firmware?.name ?: "PAXX", p?.config?.identity?.id,
                    unknown = base.printer?.unknown ?: org.json.JSONObject()),
                plates = listOf(ProjectManifest.PlateEntry(1, base.plates.firstOrNull()?.name ?: "Plate 1", items.map { ProjectManifest.ObjectEntry(it.id, it.name, it.slot) },
                    base.plates.firstOrNull()?.unknown ?: org.json.JSONObject())),
                materials = materials(),
                settings = ProjectManifest.SettingsChoice(preset.name.lowercase(), mapOf("sparse_infill_density" to "$infill%", "enable_support" to if (supports) "1" else "0") + preset.overrides,
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
        val bin = SliceEngine.locate() ?: run { slice = SliceState.Failed("The slicing engine isn't installed with this copy of Nozzle It All. Reinstall the Desktop package."); return }
        if (items.isEmpty()) { slice = SliceState.Failed("Add a model to the plate first."); return }
        outOfBounds().takeIf { it.isNotEmpty() }?.let { slice = SliceState.Failed("${it.joinToString { o -> o.name }} is off the plate. Move it or use Arrange."); return }
        val e = SliceEngine(bin, app.paths.workspaceProfile, app.paths.slices).also { engine = it }
        val req = SliceRequest(toProject(), profileDir(), preset, supports, infill, materials())
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

/** Copies the bundled printer profiles out of the app's resources so the engine can read them as files. */
object ProfileProvisioner {
    fun ensure(cache: File, model: String): File {
        val dir = File(cache, "profiles/$model").apply { mkdirs() }
        listOf("machine.json", "process.json", "filament.json").forEach { name ->
            val target = File(dir, name)
            val res = ProfileProvisioner::class.java.getResourceAsStream("/profiles/$model/$name") ?: return@forEach
            res.use { input -> val bytes = input.readBytes(); if (!target.exists() || !target.readBytes().contentEquals(bytes)) target.writeBytes(bytes) }
        }
        return dir
    }
}

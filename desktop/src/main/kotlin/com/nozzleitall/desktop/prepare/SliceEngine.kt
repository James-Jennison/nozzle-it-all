package com.nozzleitall.desktop.prepare

import net.jamesjennison.klippercompanion.FlushVolumes
import com.nozzleitall.project.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Guided print settings. Each maps to explicit engine keys so the same choice slices the same way on every platform. */
enum class QualityPreset(val label: String, val detail: String, val overrides: Map<String, String>) {
    DRAFT("Draft", "0.28 mm layers. Fastest.", mapOf("layer_height" to "0.28")),
    STANDARD("Standard", "0.20 mm layers. The everyday choice.", mapOf("layer_height" to "0.2")),
    FINE("Fine", "0.12 mm layers. Smoother, slower.", mapOf("layer_height" to "0.12")),
}

data class SliceRequest(
    val project: Project3mf,
    val profileDir: File,
    val preset: QualityPreset,
    val supports: Boolean,
    val infillPercent: Int,
    /** One entry per material slot (1-based order), "#RRGGBB" and a type, from the printer's loaded toolheads or the user. */
    val materials: List<ProjectManifest.MaterialSlot>,
    val extraOverrides: Map<String, String> = emptyMap(),
    /** Whether [preset]'s layer height applies (false when the printer's own process preset is in use). */
    val applyPreset: Boolean = true,
    /** The user's own flushing-volume matrix over every material slot (row = from), or null to work it out from the colours. */
    val flushMatrix: List<Int>? = null,
    /** PrusaSlicer ColorMix virtual extruders (its sidecar JSON), or null. */
    val virtualExtruders: String? = null,
)

data class SliceStats(val layers: Int?, val seconds: Double?, val grams: Double?, val metres: Double?, val toolChanges: Int)
sealed class SliceOutcome {
    data class Done(val gcode: File, val stats: SliceStats, val warnings: List<String>) : SliceOutcome()
    data class Failed(val message: String, val log: String) : SliceOutcome()
    object Cancelled : SliceOutcome()
}

/**
 * Slices headless, as a separate process: no window, no network. A crash or cancellation ends only that process.
 *
 * Preferred engine: `nozzle-engine`, the native build of the same patched OrcaSlicer source (engine/ENGINE_PIN.json) and
 * the same shared bridge (app/src/main/cpp/bridge) that Android and the Web App use, built by engine/native. It takes the
 * Web App's request format (profiles, overrides, objects relative to the bed centre), so a plate slices identically on
 * all three platforms.
 */
class SliceEngine(private val binary: File, private val workDir: File) {
    @Volatile private var running: Process? = null
    @Volatile private var cancelled = false

    companion object {
        const val NATIVE_ENGINE_NAME = "nozzle-engine"
        /** Where engine/native/scripts/build_engine_snapmaker.sh puts the binary on a development machine. */
        const val NATIVE_BUILD_OUTPUT = "/mnt/faststorage/build-work/nozzle-native-sm/dist/nozzle-engine"

        /**
         * The native engine: next to the app (the packaged resources directory, where build.gradle.kts bundles it), then
         * the NOZZLE_ENGINE environment variable or the nozzle.engine / NOZZLE_ENGINE system property, then the build output.
         */
        fun locateNative(env: Map<String, String> = System.getenv(), props: (String) -> String? = System::getProperty): File? {
            val resources = props("compose.application.resources.dir")?.let { File(it) }
            val candidates = listOfNotNull(
                resources?.let { File(it, NATIVE_ENGINE_NAME) },
                resources?.parentFile?.parentFile?.let { File(it, "lib/engine/$NATIVE_ENGINE_NAME") },
                env["NOZZLE_ENGINE"]?.takeIf { it.isNotBlank() }?.let { File(it) },
                props("nozzle.engine")?.takeIf { it.isNotBlank() }?.let { File(it) },
                props("NOZZLE_ENGINE")?.takeIf { it.isNotBlank() }?.let { File(it) },
                File(NATIVE_BUILD_OUTPUT),
            )
            return candidates.firstNotNullOfOrNull { f -> if (f.isFile) runnable(f, env) else null }
        }

        /**
         * [f] itself when it can be executed. Packaged app resources lose the executable bit, and a system-wide install is
         * read-only, so a bundled engine that can't be made executable in place is copied to Nozzle's own cache first.
         */
        private fun runnable(f: File, env: Map<String, String>): File? {
            if (f.canExecute() || runCatching { f.setExecutable(true) }.getOrDefault(false)) return f
            return runCatching {
                val copy = File(com.nozzleitall.desktop.AppPaths.resolve(env).cache, "engine/$NATIVE_ENGINE_NAME")
                if (!copy.isFile || copy.length() != f.length() || copy.lastModified() < f.lastModified()) {
                    copy.parentFile.mkdirs()
                    val tmp = File(copy.parentFile, "$NATIVE_ENGINE_NAME.tmp")
                    f.copyTo(tmp, overwrite = true)
                    tmp.setExecutable(true)
                    java.nio.file.Files.move(tmp.toPath(), copy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                }
                copy.takeIf { it.canExecute() }
            }.getOrNull()
        }

        /** The engine Prepare slices with. */
        fun locateEngine(env: Map<String, String> = System.getenv()): File? = locateNative(env)


        const val FLUSH_UNLOAD_LOAD_MM3 = 140

        /**
         * The multi-material override recipe, identical to the Web App's multiToolOverrides (web/src/project/slicing.ts)
         * and Android's MultiToolFilamentConfig; schemas/fixtures/multitool.json pins the expected output.
         */
        fun multiToolOverrides(baseFilamentDiameterMm: Double, slots: List<ProjectManifest.MaterialSlot>, nozzleC: (ProjectManifest.MaterialSlot) -> Int? = { null },
                               fallbackNozzleC: Int = 210, flush: FlushVolumes.Setup = FlushVolumes.Setup()): Map<String, String> {
            require(slots.isNotEmpty()) { "At least one tool slot is required." }
            val n = slots.size
            fun num(v: Double) = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
            return linkedMapOf(
                "filament_diameter" to slots.joinToString(",") { num(baseFilamentDiameterMm) },
                "filament_colour" to slots.joinToString(";") { it.colorHex?.takeIf(String::isNotBlank) ?: "#FFFFFF" },
                "filament_type" to slots.joinToString(";") { it.type?.takeIf(String::isNotBlank) ?: "PLA" },
                "nozzle_temperature" to slots.joinToString(",") { (nozzleC(it) ?: fallbackNozzleC).toString() },
                "nozzle_temperature_initial_layer" to slots.joinToString(",") { (nozzleC(it) ?: fallbackNozzleC).toString() },
                // Worked out from the slot colours as the printer's own slicer does (FlushVolumes); a matrix the user edited overrides it.
                "flush_volumes_matrix" to flush.matrix(slots.map { it.colorHex?.takeIf(String::isNotBlank) ?: "#FFFFFF" }).joinToString(","),
                "flush_volumes_vector" to (0 until n * 2).joinToString(",") { FLUSH_UNLOAD_LOAD_MM3.toString() },
            )
        }

        /** The flushing-volume calculation for this printer and these slots' filament profiles (FlushVolumes.setup). */
        fun flushSetup(profileDir: File, slots: List<ProjectManifest.MaterialSlot>): FlushVolumes.Setup {
            fun json(f: File) = runCatching { JSONObject(f.readText()) }.getOrNull()
            val base = json(File(profileDir, "filament.json"))
            val filaments = slots.map { s -> s.filamentProfile?.let { FilamentLibrary.profileJson(FilamentLibrary.keyForDir(profileDir.name), it) } ?: base }
            return FlushVolumes.setup(json(File(profileDir, "machine.json")), filaments)
        }

        /** The printable area's width and depth from machine.json, as the Web App's bedOf() reads it. */
        fun bedOf(machineJson: String): Pair<Double, Double> = runCatching {
            val a = JSONObject(machineJson).getJSONArray("printable_area")
            val pts = (0 until a.length()).map { a.getString(it).split('x').map(String::toDouble) }
            (pts.maxOf { it[0] } - pts.minOf { it[0] }) to (pts.maxOf { it[1] } - pts.minOf { it[1] })
        }.getOrDefault(270.0 to 270.0)

        /** A binary STL of the object's own (untransformed) mesh, as the Web App's writeStl(). */
        fun stlBytes(mesh: Mesh): ByteArray {
            val n = mesh.triangleCount
            val b = java.nio.ByteBuffer.allocate(84 + n * 50).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.position(80); b.putInt(n)
            for (i in 0 until n) {
                b.position(84 + i * 50 + 12)
                for (c in 0..2) { val vi = mesh.triangles[i * 3 + c] * 3; for (a in 0..2) b.putFloat(mesh.vertices[vi + a]) }
            }
            return b.array()
        }

        /**
         * The engine request, in the Web App's format and with its choices (web/src/app/state.ts slice()): the three profile
         * files unmodified, then guided preset, infill, supports, the multi-material recipe when more than one slot is in use,
         * and explicit overrides last; each object as its own mesh placed by footprint centre relative to the bed centre.
         */
        fun nativeRequest(req: SliceRequest, job: File, gcode: File): String {
            val lines = mutableListOf("out\t${gcode.absolutePath}")
            val entries = req.project.manifest?.plates?.flatMap { it.objects }?.associateBy { it.objectId } ?: emptyMap()
            val objectSlots = entries.mapValues { it.value.materialSlot ?: 1 }
            // Painted objects print their colours in the slots chosen for them; the highest slot in use sets the tool count.
            val paint = req.project.objects.associate { o -> o.id to (if (o.mesh.isPainted) paintInSlots(o.mesh, entries[o.id]?.paintSlots.orEmpty(), objectSlots[o.id] ?: 1) else null) }
            val usedSlots = req.project.objects.maxOfOrNull { o -> maxOf(objectSlots[o.id] ?: 1, paint[o.id]?.second ?: 1) } ?: 1
            val materials = req.materials.sortedBy { it.slot }.ifEmpty { listOf(ProjectManifest.MaterialSlot(1, "PLA", colorHex = "#FFFFFF")) }
            // Full Spectrum mixes name physical slots by number, so with any mixes every loaded slot is passed to the engine.
            val mixing = !req.extraOverrides[FullSpectrum.DEFINITIONS_KEY].isNullOrBlank() || req.virtualExtruders != null
            val slots = if (mixing) materials else materials.take(maxOf(usedSlots, 1))
            // Each slot slices with its own filament profile when it has one (one combined per-slot filament file, as a
            // slicer combines its filament presets); otherwise the printer profile's own filament.
            val filaments = FilamentLibrary.writeCombined(FilamentLibrary.keyForDir(req.profileDir.name), req.profileDir, slots, job) ?: File(req.profileDir, "filament.json")
            listOf(File(req.profileDir, "machine.json"), File(req.profileDir, "process.json"), filaments).forEach { lines += "profile\t${it.absolutePath}" }
            val baseNozzleC = runCatching { JSONObject(File(req.profileDir, "filament.json").readText()).optJSONArray("nozzle_temperature")?.optString(0)?.toIntOrNull() }.getOrNull()
            fun slotNozzleC(s: ProjectManifest.MaterialSlot): Int? = (s.filamentProfile?.let { FilamentLibrary.profileJson(FilamentLibrary.keyForDir(req.profileDir.name), it) }
                ?.optJSONArray("nozzle_temperature")?.optString(0)?.toIntOrNull()) ?: baseNozzleC
            val overrides = LinkedHashMap<String, String>()
            if (req.applyPreset) overrides += req.preset.overrides
            overrides["sparse_infill_density"] = "${req.infillPercent.coerceIn(0, 100)}%"
            overrides["enable_support"] = if (req.supports) "1" else "0"
            if (slots.size > 1) {
                overrides += multiToolOverrides(1.75, slots, ::slotNozzleC, flush = flushSetup(req.profileDir, slots))
            }
            overrides += req.extraOverrides
            // The user's matrix covers every slot; the engine gets the slots in use (the first n), so its top-left n x n.
            req.flushMatrix?.let { m -> val all = sqrt(m.size.toDouble()).roundToInt(); val n = slots.size
                if (n > 1 && all * all == m.size && n <= all) overrides["flush_volumes_matrix"] = (0 until n * n).joinToString(",") { i -> m[(i / n) * all + i % n].toString() } }
            overrides.forEach { (k, v) ->
                require(!Regex("[\t\n\r]").containsMatchIn(k + v)) { "Settings may not contain tabs or line breaks." }
                lines += "set\t$k\t$v"
            }
            // PrusaSlicer ColorMix: the virtual extruders, in PrusaSlicer's own file format, for the engine to print.
            req.virtualExtruders?.let { json -> lines += "virtual_extruders\t" + File(job, "virtual_extruders.json").apply { writeText(json) }.absolutePath }
            val (bedW, bedD) = bedOf(File(req.profileDir, "machine.json").readText())
            req.project.objects.forEachIndexed { i, o ->
                // A painted object goes as a 3MF, which the engine's own importer reads paint from; others as STL.
                val stl = paint[o.id]?.let { (painted, _) -> File(job, "object-$i.3mf").apply { writeBytes(ThreeMf.write(Project3mf(listOf(ModelObject(1, o.name, painted))))) } }
                    ?: File(job, "object-$i.stl").apply { writeBytes(stlBytes(o.mesh)) }
                val m = o.placement.m
                val scale = Math.hypot(m[0], m[1]).takeIf { it > 0 } ?: 1.0
                val rot = Math.toDegrees(Math.atan2(m[1], m[0]))
                val b = o.mesh.bounds(); val cx = (b[0] + b[3]) / 2.0; val cy = (b[1] + b[4]) / 2.0
                val x = cx * m[0] + cy * m[3] + m[9]; val y = cx * m[1] + cy * m[4] + m[10]
                val tool = if (slots.size > 1) objectSlots[o.id] ?: 1 else 0
                lines += "object\t${stl.absolutePath}\t${x - bedW / 2}\t${y - bedD / 2}\t$rot\t$scale\t$tool"
                // The object's own settings (Orca's per-object settings), after the whole-plate ones.
                entries[o.id]?.settings?.toSortedMap()?.forEach { (k, v) ->
                    require(!Regex("[\t\n\r]").containsMatchIn(k + v)) { "Settings may not contain tabs or line breaks." }
                    lines += "object_set\t${i + 1}\t$k\t$v"
                }
            }
            return lines.joinToString("\n") + "\n"
        }

        /**
         * [mesh] with its paint renumbered from the file's filaments to slots ([paintSlots] entry N-1 for filament N; state 0,
         * the object's own, stays 0), and the highest slot it uses.
         */
        fun paintInSlots(mesh: Mesh, paintSlots: List<Int>, objectSlot: Int): Pair<Mesh, Int> {
            var highest = objectSlot
            val cache = HashMap<String, String?>()
            val out = Array(mesh.triangleCount) { i ->
                mesh.paint?.get(i)?.let { s -> cache.getOrPut(s) {
                    runCatching { Paint.remap(s) { n -> if (n == 0) 0 else (paintSlots.getOrNull(n - 1) ?: objectSlot).also { highest = maxOf(highest, it) } } }.getOrNull()
                } }
            }
            return Mesh(mesh.vertices, mesh.triangles, out) to highest
        }

        fun parseStats(gcode: File): SliceStats {
            var layers: Int? = null; var seconds: Double? = null; var grams: Double? = null; var mm: Double? = null; var tools = 0
            // A change is a switch to a different tool. The first selection, and start G-code re-selecting the current tool,
            // aren't (same rule as the Web App's gcodeStats in web/src/project/slicing.ts).
            var tool: Int? = null
            var bambuGrams = false
            // "T1", or with parameters as Prusa's XL writes it ("T1 S1 L0 D0"); comments after ';' are ignored.
            val select = Regex("^T(\\d{1,2})(?:\\s+[A-Z][^;]*)?\\s*(?:;.*)?$")
            gcode.useLines { lines -> lines.forEach { l ->
                when {
                    l.startsWith("; total layer number:") -> layers = l.substringAfter(':').trim().toIntOrNull()
                    l.startsWith("; total filament used [g] =") -> grams = l.substringAfter('=').trim().toDoubleOrNull()
                    // Bambu's G-code layout writes per-filament weights without a total (and its time in the header block).
                    l.startsWith("; filament used [g] =") -> if (grams == null || bambuGrams) { bambuGrams = true
                        grams = l.substringAfter('=').split(',').mapNotNull { it.trim().toDoubleOrNull() }.sum() }
                    l.startsWith("; model printing time:") && l.contains("total estimated time:") ->
                        if (seconds == null) seconds = parseDuration(l.substringAfter("total estimated time:").trim())
                    l.startsWith("; filament used [mm] =") -> mm = l.substringAfter('=').split(',').mapNotNull { it.trim().toDoubleOrNull() }.sum()
                    l.startsWith("; estimated printing time (normal mode) =") -> seconds = parseDuration(l.substringAfter('=').trim())
                    l.startsWith("T") -> select.find(l)?.let { m -> val t = m.groupValues[1].toInt(); if (tool != null && t != tool) tools++; tool = t }
                }
            } }
            return SliceStats(layers, seconds, grams, mm?.div(1000), tools)
        }

        fun parseDuration(s: String): Double? {
            var total = 0.0; var any = false
            Regex("(\\d+)([dhms])").findAll(s).forEach { m -> any = true
                total += m.groupValues[1].toDouble() * when (m.groupValues[2]) { "d" -> 86400; "h" -> 3600; "m" -> 60; else -> 1 } }
            return if (any) total else null
        }
    }

    fun cancel() {
        cancelled = true
        running?.let { p ->
            // nozzle-engine stops at its next checkpoint on SIGTERM and removes partial output; force it if it doesn't.
            p.destroy()
            Thread { if (!p.waitFor(5, TimeUnit.SECONDS)) { p.descendants().forEach { d -> d.destroyForcibly() }; p.destroyForcibly() } }.apply { isDaemon = true }.start()
        }
    }

    fun slice(req: SliceRequest, onProgress: (Float, String) -> Unit): SliceOutcome {
        cancelled = false
        val engine = binary.takeIf { it.canExecute() } ?: locateNative()
            ?: return SliceOutcome.Failed("The slicing engine isn't installed with this copy of Nozzle It All. Reinstall the package.", "")
        return sliceNative(engine, req, onProgress)
    }

    private val nativeStages = listOf(0 to "Preparing the plate", 10 to "Slicing layers", 40 to "Generating walls and infill", 70 to "Generating supports and paths", 90 to "Writing instructions")

    private fun sliceNative(engine: File, req: SliceRequest, onProgress: (Float, String) -> Unit): SliceOutcome {
        val job = File(workDir, "slice-${System.currentTimeMillis()}").apply { mkdirs() }
        onProgress(0.02f, "Preparing the plate")
        val gcode = File(job, "plate.gcode")
        val request = File(job, "request.txt")
        try { request.writeText(nativeRequest(req, job, gcode)) } catch (e: IllegalArgumentException) { return SliceOutcome.Failed(e.message ?: "The slice request is invalid.", "") }
        val log = File(job, "engine.log")
        val pb = ProcessBuilder(engine.absolutePath, request.absolutePath).directory(job).redirectError(log)
        pb.environment().apply { remove("DISPLAY"); remove("WAYLAND_DISPLAY") }
        val p = pb.start(); running = p
        p.inputStream.bufferedReader().useLines { lines -> lines.forEach { l ->
            l.removePrefix("progress ").trim().toIntOrNull()?.takeIf { l.startsWith("progress ") }?.let { pct ->
                onProgress(0.05f + 0.93f * pct.coerceIn(0, 100) / 100f, nativeStages.last { pct >= it.first }.second)
            }
        } }
        val exit = p.waitFor()
        running = null
        val text = runCatching { log.readText() }.getOrDefault("")
        if (cancelled || exit == 3) { gcode.delete(); return SliceOutcome.Cancelled }
        if (exit != 0 || !gcode.isFile || gcode.length() == 0L) {
            val reason = text.lines().map(String::trim).lastOrNull { it.isNotEmpty() }?.takeIf { exit != 0 } ?: "The engine stopped (code $exit)."
            return SliceOutcome.Failed(reason, text.takeLast(8000))
        }
        onProgress(1f, "Done")
        return SliceOutcome.Done(gcode, parseStats(gcode), emptyList())
    }

    /** Fallback only (nozzle-engine missing): the Orca-fork CLI, fed a 3MF with per-object slots. */
}

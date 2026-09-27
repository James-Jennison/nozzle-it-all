package com.nozzleitall.desktop.prepare

import com.nozzleitall.project.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

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
)

data class SliceStats(val layers: Int?, val seconds: Double?, val grams: Double?, val metres: Double?, val toolChanges: Int)
sealed class SliceOutcome {
    data class Done(val gcode: File, val stats: SliceStats, val warnings: List<String>) : SliceOutcome()
    data class Failed(val message: String, val log: String) : SliceOutcome()
    object Cancelled : SliceOutcome()
}

/**
 * Slices with the Advanced Workspace's own engine, run headless as a separate process: no window, no network, and its
 * data folder is Nozzle's (never ~/.config/OrcaSlicer or Snapmaker_Orca). A crash or cancellation ends only that process.
 */
class SliceEngine(private val binary: File, private val engineDataDir: File, private val workDir: File) {
    @Volatile private var running: Process? = null
    @Volatile private var cancelled = false

    companion object {
        fun locate(env: Map<String, String> = System.getenv()): File? {
            env["NOZZLE_WORKSPACE_BIN"]?.let { File(it) }?.takeIf { it.canExecute() }?.let { return it }
            val install = System.getProperty("compose.application.resources.dir")?.let { File(it).parentFile?.parentFile }
            return listOfNotNull(install?.let { File(it, "lib/advanced-workspace/bin/nozzle-advanced-workspace") },
                File("/opt/nozzle-it-all/lib/advanced-workspace/bin/nozzle-advanced-workspace")).firstOrNull { it.canExecute() }
        }

        /** Orca's per-object settings file: the material slot ("extruder") for each object, keyed by the 3MF object id. */
        fun modelSettings(project: Project3mf, slots: Map<Int, Int>): String = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<config>\n")
            project.objects.forEach { o ->
                append("  <object id=\"${o.id}\">\n    <metadata key=\"name\" value=\"${o.name.replace("\"", "'")}\"/>\n")
                append("    <metadata key=\"extruder\" value=\"${slots[o.id] ?: 1}\"/>\n  </object>\n")
            }
            append("</config>\n")
        }

        fun parseStats(gcode: File): SliceStats {
            var layers: Int? = null; var seconds: Double? = null; var grams: Double? = null; var mm: Double? = null; var tools = 0
            gcode.useLines { lines -> lines.forEach { l ->
                when {
                    l.startsWith("; total layer number:") -> layers = l.substringAfter(':').trim().toIntOrNull()
                    l.startsWith("; total filament used [g] =") -> grams = l.substringAfter('=').trim().toDoubleOrNull()
                    l.startsWith("; filament used [mm] =") -> mm = l.substringAfter('=').split(',').mapNotNull { it.trim().toDoubleOrNull() }.sum()
                    l.startsWith("; estimated printing time (normal mode) =") -> seconds = parseDuration(l.substringAfter('=').trim())
                    l.length in 2..3 && l[0] == 'T' && l.substring(1).all(Char::isDigit) -> tools++
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

    fun cancel() { cancelled = true; running?.let { it.descendants().forEach { d -> d.destroyForcibly() }; it.destroyForcibly() } }

    fun slice(req: SliceRequest, onProgress: (Float, String) -> Unit): SliceOutcome {
        cancelled = false
        val job = File(workDir, "slice-${System.currentTimeMillis()}").apply { mkdirs() }
        val out = File(job, "out").apply { mkdirs() }
        onProgress(0.02f, "Preparing the plate")
        // Guided choices become an explicit process profile copy; the user's own profile files are never modified.
        val process = JSONObject(File(req.profileDir, "process.json").readText())
        (req.preset.overrides + mapOf("sparse_infill_density" to "${req.infillPercent.coerceIn(0, 100)}%", "enable_support" to if (req.supports) "1" else "0") + req.extraOverrides)
            .forEach { (k, v) -> process.put(k, v) }
        File(job, "process.json").writeText(process.toString())
        val baseFilament = JSONObject(File(req.profileDir, "filament.json").readText())
        val slots = req.materials.ifEmpty { listOf(ProjectManifest.MaterialSlot(1, "PLA", colorHex = "#FFFFFF")) }
        val filamentFiles = slots.sortedBy { it.slot }.map { m ->
            val f = JSONObject(baseFilament.toString())
            m.colorHex?.let { f.put("default_filament_colour", JSONArray().put(it)); f.put("filament_colour", JSONArray().put(it)) }
            m.type?.let { f.put("filament_type", JSONArray().put(it)) }
            File(job, "filament-${m.slot}.json").apply { writeText(f.toString()) }
        }
        val objectSlots = req.project.manifest?.plates?.flatMap { it.objects }?.associate { it.objectId to (it.materialSlot ?: 1) } ?: emptyMap()
        val input = File(job, "plate.3mf")
        input.writeBytes(ThreeMf.write(req.project.copy(passthrough = req.project.passthrough + ("Metadata/model_settings.config" to modelSettings(req.project, objectSlots).toByteArray()))))
        val cmd = listOf(binary.absolutePath, "--datadir", engineDataDir.absolutePath,
            "--load-settings", "${File(req.profileDir, "machine.json").absolutePath};${File(job, "process.json").absolutePath}",
            "--load-filaments", filamentFiles.joinToString(";") { it.absolutePath },
            "--slice", "0", "--outputdir", out.absolutePath, input.absolutePath)
        val log = File(job, "engine.log")
        val pb = ProcessBuilder(cmd).directory(job).redirectErrorStream(true).redirectOutput(log)
        pb.environment().apply { remove("DISPLAY"); remove("WAYLAND_DISPLAY") }
        val p = pb.start(); running = p
        val stages = listOf("Initializing" to "Loading the engine", "load_from" to "Reading the plate", "arrange" to "Placing objects",
            "slicing" to "Slicing layers", "generating" to "Generating supports and paths", "export" to "Writing instructions")
        var progress = 0.05f
        while (!p.waitFor(250, TimeUnit.MILLISECONDS)) {
            val tail = runCatching { log.readText().takeLast(4000).lowercase() }.getOrDefault("")
            val stage = stages.indexOfLast { tail.contains(it.first.lowercase()) }
            val target = if (stage < 0) 0.1f else 0.1f + 0.8f * (stage + 1) / stages.size
            progress = maxOf(progress, minOf(target, progress + 0.02f))
            onProgress(progress, if (stage < 0) "Loading the engine" else stages[stage].second)
        }
        running = null
        val exit = p.exitValue()
        val text = runCatching { log.readText() }.getOrDefault("")
        if (cancelled) return SliceOutcome.Cancelled
        val result = runCatching { JSONObject(File(out, "result.json").readText()) }.getOrNull()
        val gcode = out.listFiles { f -> f.name.endsWith(".gcode") }?.firstOrNull()
        if (exit != 0 || gcode == null || result?.optInt("return_code", -1) != 0) {
            val reason = result?.optString("error_string")?.takeIf { it.isNotBlank() && it != "Success." } ?: "The engine stopped (code $exit)."
            return SliceOutcome.Failed(reason, text.takeLast(8000))
        }
        onProgress(1f, "Done")
        val warnings = result.optJSONArray("sliced_plates")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("warning_message")?.takeIf(String::isNotBlank) } } ?: emptyList()
        return SliceOutcome.Done(gcode, parseStats(gcode), warnings)
    }
}

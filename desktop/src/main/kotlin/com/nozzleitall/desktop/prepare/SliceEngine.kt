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
 * Slices headless, as a separate process: no window, no network. A crash or cancellation ends only that process.
 *
 * Preferred engine: `nozzle-engine`, the native build of the same patched OrcaSlicer source (engine/ENGINE_PIN.json) and
 * the same shared bridge (app/src/main/cpp/bridge) that Android and the Web App use, built by engine/native. It takes the
 * Web App's request format (profiles, overrides, objects relative to the bed centre), so a plate slices identically on
 * all three platforms.
 *
 * Fallback only: when nozzle-engine is missing, [binary] is the Advanced Workspace's own CLI (the Snapmaker Orca fork),
 * driven with --load-settings/--slice and its data folder set to Nozzle's (never ~/.config/OrcaSlicer or
 * Snapmaker_Orca). That fork predates the bundled profiles' source and cannot slice some of them (for example
 * bambu_generic's start G-code), so it is kept only so an install without the native engine can still slice.
 */
class SliceEngine(private val binary: File, private val engineDataDir: File, private val workDir: File) {
    @Volatile private var running: Process? = null
    @Volatile private var cancelled = false

    companion object {
        const val NATIVE_ENGINE_NAME = "nozzle-engine"
        /** Where engine/native/scripts/build_engine.sh puts the binary on a development machine. */
        const val NATIVE_BUILD_OUTPUT = "/mnt/faststorage/build-work/nozzle-native/dist/nozzle-engine"

        /** The Advanced Workspace binary (the Orca fork): the GUI workspace, and the slicing fallback. */
        fun locate(env: Map<String, String> = System.getenv()): File? {
            env["NOZZLE_WORKSPACE_BIN"]?.let { File(it) }?.takeIf { it.canExecute() }?.let { return it }
            val install = System.getProperty("compose.application.resources.dir")?.let { File(it).parentFile?.parentFile }
            return listOfNotNull(install?.let { File(it, "lib/advanced-workspace/bin/nozzle-advanced-workspace") },
                File("/opt/nozzle-it-all/lib/advanced-workspace/bin/nozzle-advanced-workspace")).firstOrNull { it.canExecute() }
        }

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

        /** The engine Prepare slices with: the native engine, or the Orca-fork CLI only if the native engine is missing. */
        fun locateEngine(env: Map<String, String> = System.getenv()): File? = locateNative(env) ?: locate(env)

        fun isNative(f: File) = f.name.startsWith(NATIVE_ENGINE_NAME)

        /** Orca's per-object settings file: the material slot ("extruder") for each object, keyed by the 3MF object id. */
        fun modelSettings(project: Project3mf, slots: Map<Int, Int>): String = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<config>\n")
            project.objects.forEach { o ->
                append("  <object id=\"${o.id}\">\n    <metadata key=\"name\" value=\"${o.name.replace("\"", "'")}\"/>\n")
                append("    <metadata key=\"extruder\" value=\"${slots[o.id] ?: 1}\"/>\n  </object>\n")
            }
            append("</config>\n")
        }

        const val FLUSH_BETWEEN_TOOLS_MM3 = 84
        const val FLUSH_UNLOAD_LOAD_MM3 = 140

        /**
         * The multi-material override recipe, identical to the Web App's multiToolOverrides (web/src/project/slicing.ts)
         * and Android's MultiToolFilamentConfig; schemas/fixtures/multitool.json pins the expected output.
         */
        fun multiToolOverrides(baseFilamentDiameterMm: Double, slots: List<ProjectManifest.MaterialSlot>, nozzleC: (ProjectManifest.MaterialSlot) -> Int? = { null },
                               fallbackNozzleC: Int = 210): Map<String, String> {
            require(slots.isNotEmpty()) { "At least one tool slot is required." }
            val n = slots.size
            fun num(v: Double) = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
            return linkedMapOf(
                "filament_diameter" to slots.joinToString(",") { num(baseFilamentDiameterMm) },
                "filament_colour" to slots.joinToString(";") { it.colorHex?.takeIf(String::isNotBlank) ?: "#FFFFFF" },
                "filament_type" to slots.joinToString(";") { it.type?.takeIf(String::isNotBlank) ?: "PLA" },
                "nozzle_temperature" to slots.joinToString(",") { (nozzleC(it) ?: fallbackNozzleC).toString() },
                "nozzle_temperature_initial_layer" to slots.joinToString(",") { (nozzleC(it) ?: fallbackNozzleC).toString() },
                "flush_volumes_matrix" to (0 until n * n).joinToString(",") { i -> if (i / n == i % n) "0" else FLUSH_BETWEEN_TOOLS_MM3.toString() },
                "flush_volumes_vector" to (0 until n * 2).joinToString(",") { FLUSH_UNLOAD_LOAD_MM3.toString() },
            )
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
            listOf("machine.json", "process.json", "filament.json").forEach { lines += "profile\t${File(req.profileDir, it).absolutePath}" }
            val objectSlots = req.project.manifest?.plates?.flatMap { it.objects }?.associate { it.objectId to (it.materialSlot ?: 1) } ?: emptyMap()
            val usedSlots = req.project.objects.maxOfOrNull { objectSlots[it.id] ?: 1 } ?: 1
            val materials = req.materials.sortedBy { it.slot }.ifEmpty { listOf(ProjectManifest.MaterialSlot(1, "PLA", colorHex = "#FFFFFF")) }
            val slots = materials.take(maxOf(usedSlots, 1))
            val overrides = LinkedHashMap<String, String>()
            overrides += req.preset.overrides
            overrides["sparse_infill_density"] = "${req.infillPercent.coerceIn(0, 100)}%"
            overrides["enable_support"] = if (req.supports) "1" else "0"
            if (slots.size > 1) overrides += multiToolOverrides(1.75, slots)
            overrides += req.extraOverrides
            overrides.forEach { (k, v) ->
                require(!Regex("[\t\n\r]").containsMatchIn(k + v)) { "Settings may not contain tabs or line breaks." }
                lines += "set\t$k\t$v"
            }
            val (bedW, bedD) = bedOf(File(req.profileDir, "machine.json").readText())
            req.project.objects.forEachIndexed { i, o ->
                val stl = File(job, "object-$i.stl").apply { writeBytes(stlBytes(o.mesh)) }
                val m = o.placement.m
                val scale = Math.hypot(m[0], m[1]).takeIf { it > 0 } ?: 1.0
                val rot = Math.toDegrees(Math.atan2(m[1], m[0]))
                val b = o.mesh.bounds(); val cx = (b[0] + b[3]) / 2.0; val cy = (b[1] + b[4]) / 2.0
                val x = cx * m[0] + cy * m[3] + m[9]; val y = cx * m[1] + cy * m[4] + m[10]
                val tool = if (slots.size > 1) objectSlots[o.id] ?: 1 else 0
                lines += "object\t${stl.absolutePath}\t${x - bedW / 2}\t${y - bedD / 2}\t$rot\t$scale\t$tool"
            }
            return lines.joinToString("\n") + "\n"
        }

        fun parseStats(gcode: File): SliceStats {
            var layers: Int? = null; var seconds: Double? = null; var grams: Double? = null; var mm: Double? = null; var tools = 0
            // A change is a switch to a different tool. The first selection, and start G-code re-selecting the current tool,
            // aren't (same rule as the Web App's gcodeStats in web/src/project/slicing.ts).
            var tool: Int? = null
            val select = Regex("^T(\\d{1,2})\\s*$")
            gcode.useLines { lines -> lines.forEach { l ->
                when {
                    l.startsWith("; total layer number:") -> layers = l.substringAfter(':').trim().toIntOrNull()
                    l.startsWith("; total filament used [g] =") -> grams = l.substringAfter('=').trim().toDoubleOrNull()
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
        val native = if (isNative(binary)) binary.takeIf { it.canExecute() } ?: locateNative() else locateNative()
        return if (native != null) sliceNative(native, req, onProgress) else sliceWithWorkspaceCli(req, onProgress)
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
    private fun sliceWithWorkspaceCli(req: SliceRequest, onProgress: (Float, String) -> Unit): SliceOutcome {
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

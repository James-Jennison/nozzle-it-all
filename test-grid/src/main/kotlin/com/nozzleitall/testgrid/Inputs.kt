package com.nozzleitall.testgrid

import net.jamesjennison.klippercompanion.ElegooProfiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A bundled slicer profile pack (app/src/main/assets/slicer_profiles/<id>), identified by content, not by name. */
data class ProfileInfo(
    val id: String, val name: String, val sha256: String, val files: Map<String, String>,
    /** Printable-area bounding box (minX, minY, maxX, maxY) in mm. */
    val bed: List<Double>?, val height: Double?, val startGcode: String, val endGcode: String,
    val nozzleDiameters: List<Double>, val filamentTypes: List<String>,
) {
    companion object {
        val PACK_FILES = listOf("machine.json", "process.json", "filament.json")

        /**
         * [read] returns a pack file's bytes, or null if absent. The digest is SHA-256 over the canonical map of each
         * file's own SHA-256, so Android (assets) and the JVM (the repository copy) compute the same value.
         */
        fun load(id: String, read: (String) -> ByteArray?): ProfileInfo {
            val bytes = PACK_FILES.associateWith { read(it) }
            val machineBytes = bytes["machine.json"] ?: throw IllegalArgumentException("Profile pack \"$id\" has no machine.json.")
            val hashes = bytes.filterValues { it != null }.mapValues { Canon.sha256(it.value!!) }
            val machine = JSONObject(String(machineBytes, Charsets.UTF_8))
            val filament = bytes["filament.json"]?.let { JSONObject(String(it, Charsets.UTF_8)) }
            val area = machine.optJSONArray("printable_area")?.let { a -> (0 until a.length()).mapNotNull { point(a.optString(it)) } }.orEmpty()
            val bed = if (area.isEmpty()) null else listOf(area.minOf { it.first }, area.minOf { it.second }, area.maxOf { it.first }, area.maxOf { it.second })
            return ProfileInfo(id, machine.optString("name", id), Canon.sha256(Canon.bytes(hashes)), hashes, bed,
                machine.opt("printable_height")?.toString()?.toDoubleOrNull(), text(machine.opt("machine_start_gcode")), text(machine.opt("machine_end_gcode")),
                numbers(machine.opt("nozzle_diameter")), strings(filament?.opt("filament_type")))
        }

        private fun point(s: String): Pair<Double, Double>? {
            val parts = s.split('x'); if (parts.size != 2) return null
            val x = parts[0].trim().toDoubleOrNull() ?: return null; val y = parts[1].trim().toDoubleOrNull() ?: return null
            return x to y
        }
        private fun text(v: Any?): String = when (v) { is JSONArray -> (0 until v.length()).joinToString("\n") { v.optString(it) }; null -> ""; else -> v.toString() }
        private fun strings(v: Any?): List<String> = when (v) { is JSONArray -> (0 until v.length()).map { v.optString(it) }; null -> emptyList(); else -> listOf(v.toString()) }
        private fun numbers(v: Any?): List<Double> = strings(v).flatMap { it.split(',') }.mapNotNull { it.trim().toDoubleOrNull() }
    }
}

/** One acceptance model: its files (one per material part) and the published dimensions an operator measures. */
data class ModelEntry(val id: String, val title: String, val scope: MaterialScope, val parts: List<ModelPart>, val dimensions: JSONObject, val features: List<String>)
data class ModelPart(val file: String, val sha256: String, val bytes: Long, val tool: Int)

/**
 * The versioned acceptance models bundled under testgrid/models (see [AcceptanceModel] for how they are generated and
 * verified). [materialize] writes a part to [workDir] and refuses bytes whose SHA-256 differs from models.json.
 */
class ModelLibrary(private val manifest: JSONObject, private val read: (String) -> ByteArray?) {
    val entries: List<ModelEntry> = manifest.objects("models").map { m ->
        ModelEntry(m.optString("id"), m.optString("title"), MaterialScope.parse(m.optString("scope")) ?: MaterialScope.SINGLE,
            m.objects("parts").map { ModelPart(it.optString("file"), it.optString("sha256"), it.optLong("bytes"), it.optInt("tool", 1)) },
            m.optJSONObject("dimensions") ?: JSONObject(), m.strings("features"))
    }

    fun entry(id: String): ModelEntry = entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown acceptance model \"$id\".")

    fun materialize(id: String, workDir: File): List<Pair<File, ModelPart>> {
        val e = entry(id)
        workDir.mkdirs()
        return e.parts.map { part ->
            val data = read(part.file) ?: throw IllegalStateException("Acceptance model file ${part.file} is missing.")
            val actual = Canon.sha256(data)
            check(actual == part.sha256) { "Acceptance model file ${part.file} has SHA-256 $actual, expected ${part.sha256}." }
            File(workDir, part.file).also { it.writeBytes(data) } to part
        }
    }

    companion object {
        const val RESOURCE_DIR = "testgrid/models/"
        fun fromResources(): ModelLibrary {
            val loader = ModelLibrary::class.java.classLoader
            fun res(name: String): ByteArray? = loader.getResourceAsStream(RESOURCE_DIR + name)?.use { it.readBytes() }
            val manifest = res("models.json") ?: throw IllegalStateException("testgrid/models/models.json is not on the classpath.")
            return ModelLibrary(JSONObject(String(manifest, Charsets.UTF_8)), ::res)
        }
    }
}

/** What a G-code file does, as far as acceptance checks need: extents, tools, macros and forbidden commands. */
data class GcodeSummary(
    val sha256: String, val bytes: Long, val lines: Int, val extrusionMoves: Int,
    /** Extents of extruding moves on the model itself (object feature types), excluding purge/prime lines. */
    val objectExtents: List<Double>?,
    val allExtents: List<Double>?,
    val toolsUsed: Set<Int>, val macros: Set<String>, val stockElegooCommand: String?, val maxZ: Double?,
) {
    fun toJson(): Map<String, Any?> = mapOf("sha256" to sha256, "bytes" to bytes, "lines" to lines, "extrusionMoves" to extrusionMoves,
        "objectExtents" to objectExtents, "allExtents" to allExtents, "toolsUsed" to toolsUsed.sorted(), "maxZ" to maxZ,
        "macros" to macros.sorted().take(40), "stockElegooCommand" to stockElegooCommand)
}

object GcodeScan {
    /** Orca's ";TYPE:" feature names that are the object itself, not skirt, brim, prime tower or custom purge. */
    private val OBJECT_TYPES = setOf("outer wall", "inner wall", "overhang wall", "sparse infill", "internal solid infill", "solid infill",
        "top surface", "bottom surface", "bridge", "internal bridge", "gap infill", "perimeter", "external perimeter", "support", "support interface")
    private val TOOL = Regex("""^T(\d{1,2})\b""")
    private val WORD = Regex("""^([A-Za-z_][A-Za-z0-9_]*)""")

    fun scan(file: File): GcodeSummary {
        var abs = true; var relE = false
        var x = 0.0; var y = 0.0; var z = 0.0; var e = 0.0
        var type: String? = null; var sawType = false
        var lines = 0; var moves = 0
        // Moves before the first ";TYPE:" marker (start G-code purge and prime lines) are not the model. When a file has
        // type markers, only object feature types count; a file without any falls back to every extruding move.
        val obj = Extents(); val untyped = Extents(); val all = Extents()
        val tools = sortedSetOf<Int>(); val macros = sortedSetOf<String>()
        var maxZ: Double? = null
        file.bufferedReader().useLines { seq ->
            seq.forEach { raw ->
                lines++
                val trimmed = raw.trim()
                if (trimmed.startsWith(";TYPE:", ignoreCase = true)) { type = trimmed.substring(6).trim().lowercase(); sawType = true; return@forEach }
                val code = trimmed.substringBefore(';').trim()
                if (code.isEmpty()) return@forEach
                TOOL.find(code)?.let { tools += it.groupValues[1].toInt(); return@forEach }
                val word = code.split(Regex("\\s+"))[0].uppercase()
                when {
                    word == "G90" -> abs = true
                    word == "G91" -> abs = false
                    word == "M82" -> relE = false
                    word == "M83" -> relE = true
                    word == "G92" -> { arg(code, 'E')?.let { e = it }; arg(code, 'X')?.let { x = it }; arg(code, 'Y')?.let { y = it }; arg(code, 'Z')?.let { z = it } }
                    word in setOf("G0", "G1", "G2", "G3") -> {
                        val nx = arg(code, 'X')?.let { if (abs) it else x + it } ?: x
                        val ny = arg(code, 'Y')?.let { if (abs) it else y + it } ?: y
                        val nz = arg(code, 'Z')?.let { if (abs) it else z + it } ?: z
                        val ev = arg(code, 'E')
                        val extruding = ev != null && (if (relE || !abs) ev > 0 else ev > e)
                        if (ev != null) e = if (relE || !abs) e + ev else ev
                        if (extruding && (nx != x || ny != y)) {
                            moves++
                            all.add(x, y); all.add(nx, ny)
                            if (!sawType) { untyped.add(x, y); untyped.add(nx, ny) } else if (type in OBJECT_TYPES) { obj.add(x, y); obj.add(nx, ny) }
                            maxZ = maxOf(maxZ ?: nz, nz)
                        }
                        x = nx; y = ny; z = nz
                    }
                    !Regex("^[GMT]\\d").containsMatchIn(word) -> WORD.find(word)?.let { macros += it.groupValues[1].uppercase() }
                }
            }
        }
        val stock = file.bufferedReader().useLines { ElegooProfiles.stockElegooCommand(it) }
        return GcodeSummary(Canon.sha256(file), file.length(), lines, moves, if (sawType) obj.box() else untyped.box(), all.box(), tools, macros, stock, maxZ)
    }

    private fun arg(code: String, letter: Char): Double? =
        Regex("""(?:^|\s)$letter(-?\d*\.?\d+)""", RegexOption.IGNORE_CASE).find(code)?.groupValues?.get(1)?.toDoubleOrNull()

    private class Extents {
        var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY; var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        fun add(x: Double, y: Double) { minX = minOf(minX, x); minY = minOf(minY, y); maxX = maxOf(maxX, x); maxY = maxOf(maxY, y) }
        fun box(): List<Double>? = if (minX.isInfinite()) null else listOf(minX, minY, maxX, maxY).map { Math.round(it * 1000) / 1000.0 }
    }

    /** Evaluates one scan_gcode check. Returns null when it passes, or why it fails. */
    fun evaluate(check: JSONObject, s: GcodeSummary, profile: ProfileInfo?): String? = when (check.optString("check")) {
        "non_empty" -> if (s.extrusionMoves > 0) null else "The G-code has no extruding moves."
        "no_stock_elegoo_commands" -> s.stockElegooCommand?.let { "The G-code contains ${it}, an Elegoo stock-firmware command. Klipper lacks it and COSMOS 26.07+ emergency-stops on it: this file must never be sent to a Moonraker printer." }
        "requires_macro" -> check.optString("macro").uppercase().let { m -> if (m in s.macros) null else "The start/end G-code never calls $m, which this firmware's profile requires (legacy or wrong start G-code)." }
        "within_bed" -> {
            val bed = profile?.bed; val ext = s.allExtents
            when {
                bed == null -> "The profile has no printable area to check against."
                ext == null -> "No extruding moves to check."
                ext[0] < bed[0] - 0.01 || ext[1] < bed[1] - 0.01 || ext[2] > bed[2] + 0.01 || ext[3] > bed[3] + 0.01 ->
                    "Extrusion reaches $ext, outside the printable area $bed."
                else -> null
            }
        }
        "centered" -> {
            val bed = profile?.bed; val ext = s.objectExtents; val tol = check.optDouble("toleranceMm", 5.0)
            if (bed == null || ext == null) "Can't check placement: missing ${if (bed == null) "printable area" else "object extents"}."
            else {
                val dx = (ext[0] + ext[2]) / 2 - (bed[0] + bed[2]) / 2; val dy = (ext[1] + ext[3]) / 2 - (bed[1] + bed[3]) / 2
                if (kotlin.math.abs(dx) <= tol && kotlin.math.abs(dy) <= tol) null
                else "The model's centre is offset by (%.1f, %.1f) mm from the bed centre (tolerance %.1f mm).".format(java.util.Locale.ROOT, dx, dy, tol)
            }
        }
        "max_tool_index" -> check.optInt("max", 0).let { max -> s.toolsUsed.filter { it > max }.takeIf { it.isNotEmpty() }?.let { "The G-code selects T${it.joinToString(", T")}, beyond T$max." } }
        "uses_tools" -> check.optInt("count", 1).let { n -> if (s.toolsUsed.size >= n) null else "The G-code selects ${s.toolsUsed.size} tool(s); at least $n are expected." }
        else -> "Unknown check \"${check.optString("check")}\"."
    }
}

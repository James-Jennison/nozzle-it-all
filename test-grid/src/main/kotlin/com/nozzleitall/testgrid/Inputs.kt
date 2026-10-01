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
    /** Every bed temperature the filament profile declares (hot/cool/eng/textured/supertack plate, first and other layers), in °C. */
    val plateTemperatures: Set<Int> = emptySet(),
    /** The nozzle temperatures the filament profile declares (first and other layers), in °C. */
    val nozzleTemperatures: Set<Int> = emptySet(),
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
                numbers(machine.opt("nozzle_diameter")), strings(filament?.opt("filament_type")),
                celsius(filament) { it.endsWith("plate_temp") || it.endsWith("plate_temp_initial_layer") },
                celsius(filament) { it == "nozzle_temperature" || it == "nozzle_temperature_initial_layer" })
        }

        private fun celsius(filament: JSONObject?, key: (String) -> Boolean): Set<Int> =
            filament?.keys()?.asSequence()?.filter(key)?.flatMap { numbers(filament.opt(it)).map { t -> t.toInt() } }?.toSet().orEmpty()

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
    /** Extents of everything the slicer prints (skirt, prime tower, model), after the printer's own start G-code. */
    val printExtents: List<Double>? = allExtents,
    val toolsUsed: Set<Int>, val macros: Set<String>, val stockElegooCommand: String?, val maxZ: Double?,
    /**
     * Per ";LAYER_CHANGE" layer, the tools that print the model itself (object feature types) on it; prime tower and
     * purge moves don't count. Empty when the file has no layer markers.
     */
    val layerTools: List<Set<Int>> = emptyList(),
    /** Bed temperatures the file asks for (M140/M190 S, SET_HEATER_TEMPERATURE HEATER=heater_bed), in °C, rounded. */
    val bedTargets: Set<Int> = emptySet(),
    /** Nozzle temperatures the file asks for (M104/M109 S or R, SET_HEATER_TEMPERATURE HEATER=extruder*), in °C, rounded. */
    val nozzleTargets: Set<Int> = emptySet(),
) {
    /** Layers (after the first) whose model tools differ from the layer below: a 50/50 colour mix changes on every one. */
    val alternatingLayers: Int get() = layerTools.zipWithNext().count { (a, b) -> a != b }

    fun toJson(): Map<String, Any?> = mapOf("sha256" to sha256, "bytes" to bytes, "lines" to lines, "extrusionMoves" to extrusionMoves,
        "objectExtents" to objectExtents, "allExtents" to allExtents, "printExtents" to printExtents, "toolsUsed" to toolsUsed.sorted(), "maxZ" to maxZ,
        "layers" to layerTools.size, "alternatingLayers" to alternatingLayers, "bedTargets" to bedTargets.sorted(), "nozzleTargets" to nozzleTargets.sorted(),
        "macros" to macros.sorted().take(40), "stockElegooCommand" to stockElegooCommand)
}

object GcodeScan {
    /** Orca's ";TYPE:" feature names that are the object itself, not skirt, brim, prime tower or custom purge. */
    private val OBJECT_TYPES = setOf("outer wall", "inner wall", "overhang wall", "sparse infill", "internal solid infill", "solid infill",
        "top surface", "bottom surface", "bridge", "internal bridge", "gap infill", "perimeter", "external perimeter", "support", "support interface")
    private val TOOL = Regex("""^T(\d{1,2})\b""")
    private val WORD = Regex("""^([A-Za-z_][A-Za-z0-9_]*)""")

    /** A Bambu `.gcode.3mf` bundle is a zip; its plate G-code (Metadata/plate_N.gcode) is what gets checked. */
    private fun readable(file: File): java.io.Reader {
        val magic = file.inputStream().use { i -> ByteArray(2).also { i.read(it) } }
        if (magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte()) return file.bufferedReader()
        val zip = java.util.zip.ZipFile(file)
        val entry = zip.entries().asSequence().firstOrNull { Regex("""Metadata/plate_\d+\.gcode""").matches(it.name) }
            ?: run { zip.close(); throw IllegalArgumentException("The bundle has no plate G-code (Metadata/plate_N.gcode).") }
        return object : java.io.InputStreamReader(zip.getInputStream(entry), Charsets.UTF_8) { override fun close() { super.close(); zip.close() } }
    }

    fun scan(file: File): GcodeSummary {
        var abs = true; var relE = false
        var x = 0.0; var y = 0.0; var z = 0.0; var e = 0.0
        var type: String? = null; var sawType = false
        var lines = 0; var moves = 0
        // Moves before the first ";TYPE:" marker (start G-code purge and prime lines) are not the model. When a file has
        // type markers, only object feature types count; a file without any falls back to every extruding move.
        val obj = Extents(); val untyped = Extents(); val all = Extents(); val printed = Extents()
        val tools = sortedSetOf<Int>(); val macros = sortedSetOf<String>()
        val bedTargets = sortedSetOf<Int>(); val nozzleTargets = sortedSetOf<Int>()
        var maxZ: Double? = null
        // The tool that is active when each move runs; before any T command the printer is on T0.
        var tool = 0
        val layers = mutableListOf<MutableSet<Int>>()
        readable(file).buffered().useLines { seq ->
            seq.forEach { raw ->
                lines++
                val trimmed = raw.trim()
                if (trimmed.startsWith(";TYPE:", ignoreCase = true)) { type = trimmed.substring(6).trim().lowercase(); sawType = true; return@forEach }
                if (trimmed.equals(";LAYER_CHANGE", ignoreCase = true)) { layers += mutableSetOf<Int>(); return@forEach }
                val code = trimmed.substringBefore(';').trim()
                if (code.isEmpty()) return@forEach
                TOOL.find(code)?.let { tool = it.groupValues[1].toInt(); tools += tool; return@forEach }
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
                            if (!sawType) { untyped.add(x, y); untyped.add(nx, ny) } else { printed.add(x, y); printed.add(nx, ny); if (type in OBJECT_TYPES) { obj.add(x, y); obj.add(nx, ny); layers.lastOrNull()?.add(tool) } }
                            maxZ = maxOf(maxZ ?: nz, nz)
                        }
                        x = nx; y = ny; z = nz
                    }
                    word == "M140" || word == "M190" -> (arg(code, 'S') ?: arg(code, 'R'))?.let { bedTargets += Math.round(it).toInt() }
                    word == "M104" || word == "M109" -> (arg(code, 'S') ?: arg(code, 'R'))?.let { nozzleTargets += Math.round(it).toInt() }
                    // Klipper's macro form of the same requests (a profile's PRINT_START may use it directly).
                    word == "SET_HEATER_TEMPERATURE" -> {
                        val heater = Regex("""HEATER=(\S+)""", RegexOption.IGNORE_CASE).find(code)?.groupValues?.get(1)?.lowercase()
                        val target = Regex("""TARGET=(-?\d*\.?\d+)""", RegexOption.IGNORE_CASE).find(code)?.groupValues?.get(1)?.toDoubleOrNull()
                        if (heater != null && target != null) { if (heater == "heater_bed") bedTargets += Math.round(target).toInt() else if (heater.startsWith("extruder")) nozzleTargets += Math.round(target).toInt() }
                        macros += word
                    }
                    !Regex("^[GMT]\\d").containsMatchIn(word) -> WORD.find(word)?.let { macros += it.groupValues[1].uppercase() }
                }
            }
        }
        val stock = file.bufferedReader().useLines { ElegooProfiles.stockElegooCommand(it) }
        return GcodeSummary(Canon.sha256(file), file.length(), lines, moves, if (sawType) obj.box() else untyped.box(), all.box(), if (sawType) printed.box() else all.box(), tools, macros, stock, maxZ,
            layers.filter { it.isNotEmpty() }, bedTargets, nozzleTargets)
    }

    private fun arg(code: String, letter: Char): Double? =
        Regex("""(?:^|\s)$letter(-?\d*\.?\d+)""", RegexOption.IGNORE_CASE).find(code)?.groupValues?.get(1)?.toDoubleOrNull()

    private class Extents {
        var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY; var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        fun add(x: Double, y: Double) { minX = minOf(minX, x); minY = minOf(minY, y); maxX = maxOf(maxX, x); maxY = maxOf(maxY, y) }
        fun box(): List<Double>? = if (minX.isInfinite()) null else listOf(minX, minY, maxX, maxY).map { Math.round(it * 1000) / 1000.0 }
    }

    /** Evaluates one scan_gcode check. Returns null when it passes, or why it fails. */
    /** [slots]: tool or filament slots the printer's saved profile declares (CFS, CANVAS, an MMU: one nozzle, several slots). */
    fun evaluate(check: JSONObject, s: GcodeSummary, profile: ProfileInfo?, slots: Int = 1): String? = when (check.optString("check")) {
        "non_empty" -> if (s.extrusionMoves > 0) null else "The G-code has no extruding moves."
        "no_stock_elegoo_commands" -> s.stockElegooCommand?.let { "The G-code contains ${it}, an Elegoo stock-firmware command. Klipper lacks it and COSMOS 26.07+ emergency-stops on it: this file must never be sent to a Moonraker printer." }
        "requires_macro" -> check.optString("macro").uppercase().let { m -> if (m in s.macros) null else "The start/end G-code never calls $m, which this firmware's profile requires (legacy or wrong start G-code)." }
        "within_bed" -> {
            // The printer's own start G-code may purge just off the declared area (a Prusa MK4S purges at Y -4); what
            // the slicer prints (skirt, tower, model) must stay inside it.
            val bed = profile?.bed; val ext = s.printExtents
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
        // Without an explicit "max", the limit is the printer's own slot count: its nozzles (Prusa XL 5T: five) or the
        // filament slots one nozzle is fed from (CFS, CANVAS, MMU), whichever is larger.
        "max_tool_index" -> (if (check.has("max")) check.optInt("max", 0) else (maxOf(profile?.nozzleDiameters?.size ?: 1, slots).coerceAtLeast(1) - 1)).let { max -> s.toolsUsed.filter { it > max }.takeIf { it.isNotEmpty() }?.let { "The G-code selects T${it.joinToString(", T")}, beyond T$max." } }
        // A 50/50 colour mix (Full Spectrum or ColorMix) prints the model with its two filaments in turn, one layer each.
        "alternates_tools" -> {
            val minLayers = check.optInt("minLayers", 10); val fraction = check.optDouble("minFraction", 0.8)
            val layers = s.layerTools.size; val changes = s.alternatingLayers
            when {
                layers == 0 -> "The G-code has no layer markers (;LAYER_CHANGE) to check the alternation against."
                layers < minLayers -> "The model prints on $layers layer(s); at least $minLayers are needed to judge the alternation."
                changes < fraction * (layers - 1) -> "The model's filament changes between $changes of ${layers - 1} layer pairs; a 50/50 mix should change on at least %.0f%% of them.".format(java.util.Locale.ROOT, fraction * 100)
                else -> null
            }
        }
        // Every heater target must be one the printer can reach and, for the bed, one the filament profile declares. A
        // per-extruder lookup that reads past the end of the profile's list shows up here: the Snapmaker U1 refused
        // `M140 S32769` from a Full Spectrum slice whose engine build had that bug (2026-09-30).
        "heater_targets" -> {
            val maxBed = check.optInt("maxBedC", 150); val maxNozzle = check.optInt("maxNozzleC", 350)
            val declared = profile?.plateTemperatures.orEmpty()
            val bedOutside = s.bedTargets.filter { it < 0 || it > maxBed }
            val bedUndeclared = if (declared.isEmpty() || !check.optBoolean("declaredPlateTemperatures", true)) emptyList() else s.bedTargets.filter { it != 0 && it !in declared && it !in bedOutside }
            val nozzleOutside = s.nozzleTargets.filter { it < 0 || it > maxNozzle }
            when {
                bedOutside.isNotEmpty() -> "The G-code asks the bed for ${bedOutside.joinToString(", ")} °C, outside 0 to $maxBed °C (a per-extruder value read from beyond the profile's list, as in the U1's M140 S32769)."
                bedUndeclared.isNotEmpty() -> "The G-code asks the bed for ${bedUndeclared.joinToString(", ")} °C, which the filament profile does not declare (it has ${declared.sorted().joinToString(", ")} °C)."
                nozzleOutside.isNotEmpty() -> "The G-code asks the nozzle for ${nozzleOutside.joinToString(", ")} °C, outside 0 to $maxNozzle °C."
                else -> null
            }
        }
        "uses_tools" -> check.optInt("count", 1).let { n -> if (s.toolsUsed.size >= n) null else "The G-code selects ${s.toolsUsed.size} tool(s); at least $n are expected." }
        else -> "Unknown check \"${check.optString("check")}\"."
    }
}

/** Geometry read straight from a binary STL: what the slicers need to keep a multi-part model's parts in place. */
object StlGeometry {
    /** minX, minY, minZ, maxX, maxY, maxZ. */
    fun bounds(b: ByteArray): DoubleArray {
        val buf = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        require(b.size >= 84) { "Not a binary STL." }
        val n = buf.getInt(80)
        require(n > 0 && b.size >= 84 + 50L * n) { "Not a binary STL." }
        val r = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        for (t in 0 until n) for (v in 0 until 3) for (a in 0 until 3) {
            val f = buf.getFloat(84 + t * 50 + 12 + v * 12 + a * 4).toDouble()
            r[a] = minOf(r[a], f); r[a + 3] = maxOf(r[a + 3], f)
        }
        return r
    }

    /**
     * Each part's XY offset from the centre of all parts together. The engine centres every object on the bed and then
     * applies its offset (found slicing the multi-material model on a real device: with no offsets, both parts landed
     * on the same spot), so these offsets keep the parts where the model file put them relative to each other.
     */
    fun partOffsets(bounds: List<DoubleArray>): List<Pair<Double, Double>> {
        val cx = (bounds.minOf { it[0] } + bounds.maxOf { it[3] }) / 2; val cy = (bounds.minOf { it[1] } + bounds.maxOf { it[4] }) / 2
        return bounds.map { (it[0] + it[3]) / 2 - cx to (it[1] + it[4]) / 2 - cy }
    }
}

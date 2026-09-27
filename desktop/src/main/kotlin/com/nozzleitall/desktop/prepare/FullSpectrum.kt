package com.nozzleitall.desktop.prepare

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Snapmaker Full Spectrum colour mixing, computed by Snapmaker Orca's own code inside the engine
 * (`nozzle-engine --full-spectrum`, engine/native/bridge): mixed filaments, their virtual slot numbers, display colours
 * and labels (libslic3r's MixedFilamentManager), and "Color Mixing Match" (MixedFilamentBatchDialog /
 * MixedColorMatchHelpers). Nozzle only draws the results; the mixes themselves are Snapmaker's
 * `mixed_filament_definitions` string, which is saved with the project and sent to the engine when slicing.
 */
object FullSpectrum {
    /** The project setting that carries the mixes, exactly as Snapmaker Orca stores it. */
    const val DEFINITIONS_KEY = "mixed_filament_definitions"

    /** One mixed filament: printed as virtual slot [id] (after the physical slots), from [components] in [weights] percent. */
    data class Mix(val id: Int, val a: Int, val b: Int, val mixBPercent: Int, val components: List<Int>, val weights: List<Int>,
                   val displayHex: String, val label: String, val enabled: Boolean, val uiMode: Int = -1, val pattern: String = "")

    /**
     * The mixes after an edit. [remap] is Snapmaker's old → new slot number for every mix that moved (a deleted one maps
     * to 0, "the object's own"), which everything referring to those slots must follow.
     */
    data class Mixes(val definitions: String, val rows: List<Mix>, val addedId: Int? = null, val remap: Map<Int, Int> = emptyMap(), val warning: String? = null)

    /** One model colour's match: a loaded slot ([pure]) or a new mix, with its preview and how close it is. */
    data class Match(val targetHex: String, val sourceIds: List<Int>, val pure: Boolean, val slot: Int, val components: List<Int>,
                     val weights: List<Int>, val previewHex: String?, val deltaE: Double?, val quality: String?)

    /** [families] names each palette slot's Full Spectrum filament family (Auto mode), e.g. "Snapmaker PLA Full Spectrum @U1". */
    data class MatchResult(val palette: List<Pair<Int, String>>, val results: List<Match>, val definitions: String, val families: Map<Int, String> = emptyMap())

    class EngineError(message: String) : Exception(message)

    /** Snapmaker Orca's filament colour library (engine/snapmaker/resources), copied out of the app so the engine can read it. */
    private fun colourLibrary(): File? = runCatching {
        val bytes = FullSpectrum::class.java.getResourceAsStream("/fullspectrum/filaments_colours.json")?.readBytes() ?: return null
        File.createTempFile("filaments_colours", ".json").apply { deleteOnExit(); writeBytes(bytes) }
    }.getOrNull()

    private fun run(request: JSONObject): JSONObject {
        val engine = SliceEngine.locateEngine() ?: throw EngineError("The slicing engine isn't installed with this copy of Nozzle It All.")
        val req = File.createTempFile("nozzle-fs", ".json").apply { deleteOnExit(); writeText(request.toString()) }
        try {
            val p = ProcessBuilder(engine.absolutePath, "--full-spectrum", req.absolutePath).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); throw EngineError("Colour mixing took too long.") }
            val json = runCatching { JSONObject(out) }.getOrElse { throw EngineError("This engine doesn't support Full Spectrum yet.") }
            json.optString("error").takeIf { it.isNotBlank() }?.let { throw EngineError(it) }
            return json
        } finally { req.delete() }
    }

    private fun ints(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.getInt(i) } } ?: emptyList()

    private fun mixes(o: JSONObject): Mixes {
        val rows = o.optJSONArray("rows")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { r ->
            val gIds = r.optString("gradient_ids").let { g -> if (g.contains('/')) g.split('/').mapNotNull { it.toIntOrNull() } else g.mapNotNull { it.digitToIntOrNull() } }
            val gW = r.optString("gradient_weights").split('/').mapNotNull { it.toIntOrNull() }
            val a1 = r.getInt("a"); val b1 = r.getInt("b"); val mixB = r.optInt("mix_b_percent", 50)
            Mix(r.getInt("id"), a1, b1, mixB, gIds.ifEmpty { listOf(a1, b1) }, gW.ifEmpty { listOf(100 - mixB, mixB) },
                r.optString("display", "#26A69A"), r.optString("label"), r.optBoolean("enabled", true), r.optInt("ui_mode", -1), r.optString("manual_pattern"))
        } } ?: emptyList()
        val remap = o.optJSONObject("remap")?.let { r -> r.keySet().mapNotNull { k -> k.toIntOrNull()?.let { it to r.getInt(k) } }.toMap() } ?: emptyMap()
        return Mixes(o.optString("definitions"), rows, o.optInt("added_id", -1).takeIf { it > 0 }, remap, o.optString("warning").ifBlank { null })
    }

    private fun base(op: String, physical: List<String>, definitions: String) =
        JSONObject().put("op", op).put("physical", JSONArray(physical)).put("definitions", definitions)

    fun display(physical: List<String>, definitions: String): Mixes = mixes(run(base("display", physical, definitions)))
    fun add(physical: List<String>, definitions: String, a: Int, b: Int, mixBPercent: Int): Mixes =
        mixes(run(base("add", physical, definitions).put("a", a).put("b", b).put("mix_b_percent", mixBPercent)))
    fun update(physical: List<String>, definitions: String, id: Int, a: Int, b: Int, mixBPercent: Int): Mixes =
        mixes(run(base("update", physical, definitions).put("id", id).put("a", a).put("b", b).put("mix_b_percent", mixBPercent)))
    fun remove(physical: List<String>, definitions: String, id: Int): Mixes = mixes(run(base("remove", physical, definitions).put("id", id)))

    private fun typed(op: String, physical: List<Pair<String, String?>>, definitions: String = "") = JSONObject().put("op", op).put("definitions", definitions)
        .put("physical", JSONArray(physical.map { (c, t) -> JSONObject().put("color", c).put("type", t ?: "PLA") }))

    /** What the mix editor would produce, without keeping it: its colour, label and any advisory (Snapmaker's preview). */
    fun preview(physical: List<Pair<String, String?>>, dialog: JSONObject): Mixes {
        val req = typed("add", physical); dialog.keySet().forEach { req.put(it, dialog.get(it)) }
        return mixes(run(req))
    }

    /** A recommendation swatch: the mix it makes, its colour and Snapmaker's tooltip; [visible] false below Min Mix Ratio. */
    data class Preset(val dialog: JSONObject, val previewHex: String, val tooltip: String, val visible: Boolean)

    /** The editor's "Mixing Recommendations" for [mode] (match, ratio, ratio3, gradient): build_color_match_presets and kin. */
    fun presets(physical: List<Pair<String, String?>>, mode: String, minPercent: Int = 15): List<Preset> {
        val o = run(typed("presets", physical).put("mode", mode).put("min_percent", minPercent))
        return o.optJSONArray("presets")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            Preset(it.getJSONObject("dialog"), it.optString("preview"), it.optString("tooltip"), it.optBoolean("visible", true)) } } ?: emptyList()
    }

    /** Match mode's search for one colour (build_best_color_match_recipe): the mix to store, its colour and closeness. */
    data class OneMatch(val dialog: JSONObject, val previewHex: String, val deltaE: Double, val quality: String, val warning: String?)

    fun matchOne(physical: List<Pair<String, String?>>, target: String, minPercent: Int): OneMatch {
        val o = run(typed("match_one", physical).put("target", target).put("min_percent", minPercent))
        return OneMatch(o.getJSONObject("dialog"), o.optString("preview"), o.optDouble("delta_e"), o.optString("quality"), o.optString("warning").ifBlank { null })
    }

    /** Snapmaker's clean-up after Color Mixing Match: mixes nothing uses any more are deleted (and the rest renumbered). */
    fun cleanup(physical: List<String>, definitions: String, usedIds: Collection<Int>): Mixes =
        mixes(run(base("cleanup", physical, definitions).put("used_ids", JSONArray(usedIds.sorted()))))

    /** A mix from the editor, in one of Snapmaker's four modes ([dialog] holds that mode's fields); [id] edits an existing one. */
    fun save(physical: List<Pair<String, String?>>, definitions: String, dialog: JSONObject, id: Int?): Mixes {
        val req = JSONObject().put("op", if (id == null) "add" else "update").put("definitions", definitions)
            .put("physical", JSONArray(physical.map { (c, t) -> JSONObject().put("color", c).put("type", t ?: "PLA") }))
        dialog.keySet().forEach { req.put(it, dialog.get(it)) }
        id?.let { req.put("id", it) }
        return mixes(run(req))
    }

    /**
     * Snapmaker's "Color Mixing Match": [targets] are the model's colours (hex to the model filament numbers that use it).
     * "auto" matches against Snapmaker's recommended Full Spectrum palette; "manual" against [manualSlots] of [physical].
     */
    fun match(mode: String, physical: List<Pair<String, String?>>, targets: List<Pair<String, List<Int>>>, definitions: String,
              manualSlots: List<Int> = emptyList()): MatchResult {
        val req = JSONObject().put("op", "match").put("mode", mode).put("definitions", definitions)
            .put("physical", JSONArray(physical.map { (c, t) -> JSONObject().put("color", c).put("type", t ?: "PLA") }))
            // Model colours go without ids: Snapmaker reads ids as project filament numbers (above the loaded slots, existing
            // mixes), while a model's colours are numbered by the file's own filaments. Results are mapped back by colour.
            .put("targets", JSONArray(targets.map { (c, _) -> JSONObject().put("color", c).put("ids", JSONArray()) }))
        if (mode == "manual") req.put("manual_slots", JSONArray(manualSlots))
        colourLibrary()?.let { req.put("colour_library", it.absolutePath) }
        val o = run(req)
        val palette = o.optJSONArray("palette")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { it.getInt("slot") to it.getString("color") } } ?: emptyList()
        val results = o.optJSONArray("results")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { r ->
            val pure = r.optBoolean("pure")
            val target = r.getString("target")
            Match(target, targets.firstOrNull { it.first.equals(target, true) }?.second ?: emptyList(), pure, if (pure) r.getInt("slot") else r.getInt("id"),
                ints(r.optJSONArray("components")), ints(r.optJSONArray("weights")), r.optString("preview").ifBlank { null },
                if (r.has("delta_e")) r.getDouble("delta_e") else null, r.optString("quality").ifBlank { null })
        } } ?: emptyList()
        val families = o.optJSONArray("palette")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }
            .filter { it.optString("family").isNotBlank() }.associate { it.getInt("slot") to it.getString("family") } } ?: emptyMap()
        return MatchResult(palette, results, o.optString("definitions", definitions), families)
    }
}

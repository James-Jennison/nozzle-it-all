package com.nozzleitall.printer.ext

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure request/response shapes for Snapmaker Full Spectrum and PrusaSlicer ColorMix, shared by Desktop (which drives
 * them through the `nozzle-engine --full-spectrum` / `--color-mix` CLI, see desktop's FullSpectrum.kt/PrusaColorMix.kt)
 * and Android (which drives the same engine logic through the `nativeFullSpectrum`/`nativeColorMix` JNI calls). Neither
 * platform's transport (process I/O vs. JNI) lives here - only the JSON building/parsing both need, so the two stay
 * byte-for-byte compatible with what the engine actually accepts.
 */
object FullSpectrumFormat {
    /** The project setting that carries the mixes, exactly as Snapmaker Orca stores it. */
    const val DEFINITIONS_KEY = "mixed_filament_definitions"

    /** One mixed filament: printed as virtual slot [id] (after the physical slots), from [components] in [weights] percent. */
    data class Mix(val id: Int, val a: Int, val b: Int, val mixBPercent: Int, val components: List<Int>, val weights: List<Int>,
                   val displayHex: String, val label: String, val enabled: Boolean, val uiMode: Int = -1, val pattern: String = "")

    /**
     * The mixes after an edit. [remap] is Snapmaker's old -> new slot number for every mix that moved (a deleted one
     * maps to 0, "the object's own"), which everything referring to those slots must follow.
     */
    data class Mixes(val definitions: String, val rows: List<Mix>, val addedId: Int? = null, val remap: Map<Int, Int> = emptyMap(), val warning: String? = null)

    /** One model colour's match: a loaded slot ([pure]) or a new mix, with its preview and how close it is. */
    data class Match(val targetHex: String, val sourceIds: List<Int>, val pure: Boolean, val slot: Int, val components: List<Int>,
                     val weights: List<Int>, val previewHex: String?, val deltaE: Double?, val quality: String?)

    /** [families] names each palette slot's Full Spectrum filament family (Auto mode), e.g. "Snapmaker PLA Full Spectrum @U1". */
    data class MatchResult(val palette: List<Pair<Int, String>>, val results: List<Match>, val definitions: String, val families: Map<Int, String> = emptyMap())

    private fun ints(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.getInt(i) } } ?: emptyList()

    fun parseMixes(o: JSONObject): Mixes {
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

    fun baseRequest(op: String, physical: List<String>, definitions: String): JSONObject =
        JSONObject().put("op", op).put("physical", JSONArray(physical)).put("definitions", definitions)

    fun typedRequest(op: String, physical: List<Pair<String, String?>>, definitions: String = ""): JSONObject =
        JSONObject().put("op", op).put("definitions", definitions)
            .put("physical", JSONArray(physical.map { (c, t) -> JSONObject().put("color", c).put("type", t ?: "PLA") }))

    fun parsePresets(o: JSONObject): List<Pair<JSONObject, Triple<String, String, Boolean>>> =
        o.optJSONArray("presets")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            it.getJSONObject("dialog") to Triple(it.optString("preview"), it.optString("tooltip"), it.optBoolean("visible", true)) } } ?: emptyList()

    fun parseMatchResult(o: JSONObject, targets: List<Pair<String, List<Int>>>, requestedDefinitions: String): MatchResult {
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
        return MatchResult(palette, results, o.optString("definitions", requestedDefinitions), families)
    }

    /** The engine's `{"error":"..."}` failure shape, common to every op of both --full-spectrum and --color-mix. */
    fun errorOf(o: JSONObject): String? = o.optString("error").takeIf { it.isNotBlank() }
}

/**
 * PrusaSlicer's ColorMix ("virtual extruders"): a numbered slot after the physical ones that prints as a repeating
 * layer cycle of 2-3 loaded filaments (a blend) or changes along Z (a gradient). Stored exactly as PrusaSlicer stores
 * it, in the 3MF's [PrusaColorMixFormat.SIDECAR], so projects move between Nozzle and PrusaSlicer unchanged, and
 * fed to a slice as the engine's `virtual_extruders` request-line file (see [PrusaColorMixFormat.sliceRequestJson]).
 */
object PrusaColorMixFormat {
    const val SIDECAR = "Metadata/Prusa_Slicer_full_spectrum.json"

    /** The `virtual_extruders` slice request file's own version (`nozzle_cm::check_virtual_extruders_file`). */
    const val SLICE_REQUEST_VERSION = 1

    data class Component(val extruder: Int, val value: Double) // a blend's ratio (0-1) or a gradient stop's position (0-1)

    /** One virtual extruder: [kind] "fullspectrum" (a blend) or "gradient"; [colorOverride] the user's display colour. */
    data class Virtual(val id: Int, val kind: String, val components: List<Component>, val colorOverride: String? = null,
                       val minZ: Double? = null, val maxZ: Double? = null,
                       /** Filled in by the engine: the colour it reads as and its layer cycle (extruder per layer). */
                       val effectiveHex: String? = null, val cycle: List<Int> = emptyList()) {
        val isBlend get() = kind != "gradient"
        fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind).apply {
            colorOverride?.let { put("color", it) }
            minZ?.let { put("minz", it) }; maxZ?.let { put("maxz", it) }
            put("components", JSONArray(components.map { c -> JSONObject().put("extruder", c.extruder).put(if (isBlend) "ratio" else "position", c.value) }))
        }
        /** "E1 67% + E2 33%", or "E1 -> E3" for a gradient. */
        val summary: String get() = if (isBlend) components.joinToString(" + ") { "E${it.extruder} ${Math.round(it.value * 100)}%" }
            else components.joinToString(" -> ") { "E${it.extruder}" }
    }

    fun parse(entry: JSONObject): Virtual {
        val kind = entry.optString("kind").ifBlank { if (entry.optJSONArray("components")?.optJSONObject(0)?.has("position") == true) "gradient" else "fullspectrum" }
        val comps = entry.optJSONArray("components")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            Component(it.getInt("extruder"), if (kind == "gradient") it.optDouble("position") else it.optDouble("ratio")) } } ?: emptyList()
        // In a file, "color" is the effective colour PrusaSlicer wrote (an override can't be told apart), so it is shown but
        // not kept as an override; the engine's normalised entries say which is which (effective_color, color_override).
        val fromEngine = entry.has("effective_color")
        val override = if (fromEngine && entry.optBoolean("color_override")) entry.optString("color").ifBlank { null } else null
        val effective = if (fromEngine) entry.optString("effective_color").ifBlank { null } else entry.optString("color").ifBlank { null }?.takeUnless { it.startsWith("g:") }
        return Virtual(entry.getInt("id"), kind, comps, override,
            if (entry.has("minz")) entry.getDouble("minz") else null, if (entry.has("maxz")) entry.getDouble("maxz") else null,
            effective, entry.optJSONArray("cycle")?.let { c -> (0 until c.length()).map { c.getInt(it) } } ?: emptyList())
    }

    /** The sidecar file as PrusaSlicer writes it (version 1, the physical extruders' colours, the virtual extruders). */
    fun sidecar(physical: List<String>, virtual: List<Virtual>): String = JSONObject().put("version", 1)
        .put("physical_extruders", JSONArray(physical.mapIndexed { i, c -> JSONObject().put("id", i + 1).put("color", c) }))
        .put("virtual_extruders", JSONArray(virtual.map { it.toJson() })).toString(2)

    fun readSidecar(bytes: ByteArray): Pair<Int, List<Virtual>>? = runCatching {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        if (o.optInt("version", 1) > 1) return null // PrusaSlicer rejects newer versions too
        (o.optJSONArray("physical_extruders")?.length() ?: 0) to (o.optJSONArray("virtual_extruders")?.let { a -> (0 until a.length()).map { parse(a.getJSONObject(it)) } } ?: emptyList())
    }.getOrNull()

    /**
     * The `virtual_extruders` request-line file a slice actually reads (`nozzle_cm::check_virtual_extruders_file`):
     * `{"version":1,"virtual_extruders":[...]}` - the entries a slice cares about (id/kind/components), no engine-filled
     * fields (effective_color, cycle) which a slice request never needs and the engine ignores on input anyway.
     */
    fun sliceRequestJson(virtual: List<Virtual>): String =
        JSONObject().put("version", SLICE_REQUEST_VERSION).put("virtual_extruders", JSONArray(virtual.map { it.toJson() })).toString()

    fun physicalJson(physical: List<Pair<String, String?>>): JSONArray = JSONArray(physical.map { (c, t) -> JSONObject().put("color", c).put("type", t ?: "PLA") })

    data class Preset(val components: List<Component>, val hex: String)

    fun parsePresets(o: JSONObject): List<Preset> = listOf("two_color", "three_color").flatMap { key ->
        o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { p ->
            val ids = p.optJSONArray("extruders")?.let { e -> (0 until e.length()).map { e.getInt(it) } } ?: emptyList()
            val pct = p.optJSONArray("ratios_percent")?.let { r -> (0 until r.length()).map { r.getDouble(it) / 100.0 } } ?: emptyList()
            Preset(ids.zip(pct).map { (e, r) -> Component(e, r) }, p.optString("color"))
        } } ?: emptyList()
    }

    fun parseVirtualList(o: JSONObject): List<Virtual> = o.optJSONArray("virtual")?.let { a -> (0 until a.length()).map { parse(a.getJSONObject(it)) } } ?: emptyList()

    fun parseRemap(o: JSONObject): Map<Int, Int> = o.optJSONObject("remap")?.let { r -> r.keySet().mapNotNull { k -> k.toIntOrNull()?.let { it to r.getInt(k) } }.toMap() } ?: emptyMap()

    /** The engine's `{"error":"..."}` failure shape, common to every --color-mix op. */
    fun errorOf(o: JSONObject): String? = o.optString("error").takeIf { it.isNotBlank() }
}

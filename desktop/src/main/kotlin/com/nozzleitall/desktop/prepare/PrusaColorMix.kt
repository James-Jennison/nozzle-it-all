package com.nozzleitall.desktop.prepare

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * PrusaSlicer's ColorMix ("virtual extruders"), for Prusa printers with two or more filament slots. A virtual extruder
 * is a numbered slot after the physical ones that prints as a repeating layer cycle of 2-3 loaded filaments (a blend),
 * or changes along Z (a gradient). Everything about them is PrusaSlicer 2.9.6's own code, run by the engine
 * (`nozzle-engine --color-mix`): normalising, colour prediction (prusa_fdm_mixer), layer cycles, presets, id remapping.
 * They are stored exactly as PrusaSlicer stores them, in the 3MF's [SIDECAR], so projects move between Nozzle and
 * PrusaSlicer unchanged.
 */
object PrusaColorMix {
    const val SIDECAR = "Metadata/Prusa_Slicer_full_spectrum.json"

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
        /** "E1 67% + E2 33%", or "E1 → E3" for a gradient. */
        val summary: String get() = if (isBlend) components.joinToString(" + ") { "E${it.extruder} ${Math.round(it.value * 100)}%" }
            else components.joinToString(" → ") { "E${it.extruder}" }
    }

    fun parse(entry: JSONObject): Virtual {
        val kind = entry.optString("kind").ifBlank { if (entry.optJSONArray("components")?.optJSONObject(0)?.has("position") == true) "gradient" else "fullspectrum" }
        val comps = entry.optJSONArray("components")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            Component(it.getInt("extruder"), if (kind == "gradient") it.optDouble("position") else it.optDouble("ratio")) } } ?: emptyList()
        // In a file, "color" is the effective colour PrusaSlicer wrote (an override can't be told apart), so it is shown but
        // not kept as an override; the engine's normalised entries say which is which (effective_color, color_override).
        val fromEngine = entry.has("effective_color")
        // color_override is a flag: when set, "color" is the user's display colour.
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

    class EngineError(message: String) : Exception(message)

    private fun run(request: JSONObject): JSONObject {
        val engine = SliceEngine.locateEngine() ?: throw EngineError("The slicing engine isn't installed with this copy of Nozzle It All.")
        val req = File.createTempFile("nozzle-cm", ".json").apply { deleteOnExit(); writeText(request.toString()) }
        try {
            val p = ProcessBuilder(engine.absolutePath, "--color-mix", req.absolutePath).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); throw EngineError("Colour mixing took too long.") }
            val json = runCatching { JSONObject(out) }.getOrElse { throw EngineError("This engine doesn't support Prusa colour mixing yet.") }
            json.optString("error").takeIf { it.isNotBlank() }?.let { throw EngineError(it) }
            return json
        } finally { req.delete() }
    }

    private fun physicalJson(physical: List<Pair<String, String?>>) = JSONArray(physical.map { (c, t) -> JSONObject().put("color", c).put("type", t ?: "PLA") })

    /** The virtual extruders as the engine keeps them for [physical] (invalid ones dropped), with colours and cycles. */
    fun normalize(physical: List<Pair<String, String?>>, virtual: List<Virtual>): List<Virtual> {
        val o = run(JSONObject().put("op", "normalize").put("physical", physicalJson(physical)).put("virtual", JSONArray(virtual.map { it.toJson() })))
        return o.optJSONArray("virtual")?.let { a -> (0 until a.length()).map { parse(a.getJSONObject(it)) } } ?: emptyList()
    }

    /** The id PrusaSlicer's "Add blend" gives the next virtual extruder. */
    fun nextId(physicalCount: Int, virtual: List<Virtual>): Int =
        run(JSONObject().put("op", "next_id").put("physical_count", physicalCount).put("virtual", JSONArray(virtual.map { it.toJson() }))).getInt("id")

    /** prusa_fdm_mixer's prediction of how [colors] in [ratios] read when printed in alternating layers. */
    fun mix(colors: List<String>, ratios: List<Double>): String =
        run(JSONObject().put("op", "mix").put("colors", JSONArray(colors)).put("ratios", JSONArray(ratios))).getString("color")

    data class Preset(val components: List<Component>, val hex: String)

    /** PrusaSlicer's preset palette for the loaded filaments (same material only, near-duplicates removed, by hue). */
    fun presets(physical: List<Pair<String, String?>>): List<Preset> {
        val o = run(JSONObject().put("op", "presets").put("physical", physicalJson(physical)))
        return listOf("two_color", "three_color").flatMap { key -> o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { p ->
            val ids = p.optJSONArray("extruders")?.let { e -> (0 until e.length()).map { e.getInt(it) } } ?: emptyList()
            val pct = p.optJSONArray("ratios_percent")?.let { r -> (0 until r.length()).map { r.getDouble(it) / 100.0 } } ?: emptyList()
            Preset(ids.zip(pct).map { (e, r) -> Component(e, r) }, p.optString("color"))
        } } ?: emptyList() }
    }

    /**
     * PrusaSlicer's import remap (remap_full_spectrum_on_import): a file saved on a printer with fewer extruders has its
     * virtual ids shifted clear of this printer's; returns the moved list and old → new ids for the paint that uses them.
     */
    fun remapImport(physicalCount: Int, sidecar: ByteArray): Pair<List<Virtual>, Map<Int, Int>> {
        val o = run(JSONObject().put("op", "remap_import").put("physical_count", physicalCount).put("sidecar", JSONObject(String(sidecar, Charsets.UTF_8))))
        val list = o.optJSONArray("virtual")?.let { a -> (0 until a.length()).map { parse(a.getJSONObject(it)) } } ?: emptyList()
        val remap = o.optJSONObject("remap")?.let { r -> r.keySet().mapNotNull { k -> k.toIntOrNull()?.let { it to r.getInt(k) } }.toMap() } ?: emptyMap()
        return list to remap
    }
}

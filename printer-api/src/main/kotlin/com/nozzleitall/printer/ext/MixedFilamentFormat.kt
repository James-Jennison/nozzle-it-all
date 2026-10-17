package com.nozzleitall.printer.ext

import org.json.JSONArray
import org.json.JSONObject

/**
 * Mixed filaments, the way OrcaSlicer 2.5.0 has them and the engine runs them (`nozzle-engine --mixed-filament`, JNI
 * `nativeMixedFilament`; nozzle-engine's nozzle/bridge/native/mixed_filament.cpp). A mixed filament is a slot after the
 * loaded ones that no extruder holds: the slicer prints it as the filaments in [Row.components], in the proportions of
 * [Row.weights], by splitting each layer into sub-layers (or alternating layers), and a gradient moves the proportions up
 * the object. Only JSON building and parsing live here, shared by Desktop (engine subprocess) and Android (JNI).
 */
object MixedFilamentFormat {
    /** The feature a printer profile offers when it can slice mixed filaments (any printer with two or more filament slots). */
    const val FEATURE = "orca.mixed-filament"

    /** A gradient on a two-filament row: [range] "high,low" proportions of the first filament (empty: the engine's default),
     *  [curve] the engine's PCHIP control points (empty: linear), [perPart] restarts it for every part. */
    data class Gradient(val enabled: Boolean = false, val range: String = "", val curve: String = "", val perPart: Boolean = false)

    /** One mixed filament. [filament] is the slot number it prints as (after the loaded ones); the rest comes from the engine. */
    data class Row(val components: List<Int>, val weights: List<Int>, val gradient: Gradient = Gradient(),
                   val filament: Int = 0, val hex: String = "#808080", val problems: List<String> = emptyList()) {
        /** "F1 70% + F2 30%", from the weights. */
        val label: String get() { val total = weights.sum().takeIf { it > 0 } ?: 1
            return components.indices.joinToString(" + ") { "F${components[it]} ${Math.round(weights[it] * 100.0 / total)}%" } }
        fun toJson(): JSONObject = JSONObject().put("components", JSONArray(components)).put("weights", JSONArray(weights))
            .put("gradient", JSONObject().put("enabled", gradient.enabled).put("range", gradient.range).put("curve", gradient.curve).put("per_part", gradient.perPart))
    }

    /** One recipe component of a [Match]. */
    data class Part(val filament: Int, val hex: String, val ratio: Int)

    /** The mix closest to a target colour; [row] is null when the best recipe is a single filament. */
    data class Match(val target: String, val matchedHex: String, val deltaE: Double, val parts: List<Part>, val row: Row?)

    private fun ints(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.getInt(i) } } ?: emptyList()

    fun physicalJson(physical: List<Pair<String, String?>>): JSONArray =
        JSONArray(physical.map { (c, t) -> JSONObject().put("color", c).put("type", t ?: "PLA") })

    fun request(op: String, physical: List<Pair<String, String?>>, rows: List<Row> = emptyList()): JSONObject =
        JSONObject().put("op", op).put("physical", physicalJson(physical)).put("mixed", JSONArray(rows.map { it.toJson() }))

    fun parseRow(r: JSONObject): Row {
        val g = r.optJSONObject("gradient")
        return Row(ints(r.optJSONArray("components")), ints(r.optJSONArray("weights")),
            Gradient(g?.optBoolean("enabled") ?: false, g?.optString("range").orEmpty(), g?.optString("curve").orEmpty(), g?.optBoolean("per_part") ?: false),
            r.optInt("filament"), r.optString("color", "#808080"),
            r.optJSONArray("problems")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList())
    }

    fun parseRows(o: JSONObject): List<Row> = o.optJSONArray("mixed")?.let { a -> (0 until a.length()).map { parseRow(a.getJSONObject(it)) } } ?: emptyList()

    fun parsePresets(o: JSONObject): List<Row> = o.optJSONArray("presets")?.let { a -> (0 until a.length()).map { parseRow(a.getJSONObject(it)) } } ?: emptyList()

    fun parseMatch(o: JSONObject): Match {
        val parts = o.optJSONArray("components")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Part(it.getInt("filament"), it.optString("color"), it.getInt("ratio")) } } ?: emptyList()
        return Match(o.getString("target"), o.optString("matched_color"), o.optDouble("delta_e"), parts,
            o.optJSONObject("row")?.let { r -> parseRow(r.put("color", o.optString("row_color", "#808080"))) })
    }

    /** The config values a slice needs for the rows (`slice_overrides`): pass them as the slice's settings. */
    fun parseOverrides(o: JSONObject): Map<String, String> = o.optJSONObject("overrides")?.let { r -> r.keySet().associateWith { r.getString(it) } } ?: emptyMap()

    /** The settings a project stores so OrcaSlicer reads the same mixed filaments (these keys, one entry per slot). */
    val SETTING_KEYS = listOf("filament_is_mixed", "filament_mixed_components", "filament_mixed_sublayer_ratios", "filament_mixed_gradient",
        "filament_mixed_gradient_range", "filament_mixed_gradient_curve", "filament_mixed_gradient_per_part", "enable_mixed_color_sublayer")

    /** The rows a project's settings describe, or none. [physicalCount] is the number of loaded filaments (the rows follow them). */
    fun rowsFromSettings(settings: Map<String, String>, physicalCount: Int): List<Row> {
        val isMixed = settings["filament_is_mixed"]?.split(',') ?: return emptyList()
        val comps = settings["filament_mixed_components"]?.split(';').orEmpty()
        val ratios = settings["filament_mixed_sublayer_ratios"]?.split(';').orEmpty()
        val grad = settings["filament_mixed_gradient"]?.split(',').orEmpty()
        val range = settings["filament_mixed_gradient_range"]?.split(';').orEmpty()
        val curve = settings["filament_mixed_gradient_curve"]?.split(';').orEmpty()
        val perPart = settings["filament_mixed_gradient_per_part"]?.split(',').orEmpty()
        return isMixed.indices.filter { it >= physicalCount && isMixed[it].trim() == "1" }.mapNotNull { i ->
            val c = comps.getOrNull(i)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
            if (c.size < 2) return@mapNotNull null
            val r = ratios.getOrNull(i)?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }.orEmpty()
            val w = if (r.size == c.size) r.map { Math.round(it * 100).toInt().coerceAtLeast(0) } else List(c.size) { 1 }
            Row(c, w, Gradient(grad.getOrNull(i)?.trim() == "1", range.getOrNull(i).orEmpty(), curve.getOrNull(i).orEmpty(), perPart.getOrNull(i)?.trim() == "1"),
                filament = i + 1)
        }
    }

    /** The engine's `{"error":"..."}` failure shape. */
    fun errorOf(o: JSONObject): String? = o.optString("error").takeIf { it.isNotBlank() }
}

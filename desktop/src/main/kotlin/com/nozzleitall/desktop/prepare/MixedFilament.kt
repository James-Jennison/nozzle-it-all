package com.nozzleitall.desktop.prepare

import com.nozzleitall.printer.ext.MixedFilamentFormat
import com.nozzleitall.printer.ext.MixedFilamentFormat.Match
import com.nozzleitall.printer.ext.MixedFilamentFormat.Row
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Mixed filaments, computed by OrcaSlicer 2.5.0's own code inside the engine (`nozzle-engine --mixed-filament`): the
 * blended colour of a row, recipes that match a colour, preset swatches, renumbering when a loaded filament goes, and the
 * per-filament settings a slice needs. Nozzle keeps the rows ([Row]) and draws the results. The request and response
 * shapes are shared with Android in [MixedFilamentFormat]; this object is Desktop's transport (an engine subprocess).
 */
object MixedFilament {
    class EngineError(message: String) : Exception(message)

    private fun run(request: JSONObject): JSONObject {
        val engine = SliceEngine.locateEngine() ?: throw EngineError("The slicing engine isn't installed with this copy of Nozzle It All.")
        val req = File.createTempFile("nozzle-mf", ".json").apply { deleteOnExit(); writeText(request.toString()) }
        try {
            val p = ProcessBuilder(engine.absolutePath, "--mixed-filament", req.absolutePath).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); throw EngineError("Colour mixing took too long.") }
            val json = runCatching { JSONObject(out) }.getOrElse { throw EngineError("This engine doesn't support mixed filaments.") }
            MixedFilamentFormat.errorOf(json)?.let { throw EngineError(it) }
            return json
        } finally { req.delete() }
    }

    private fun typed(physical: List<com.nozzleitall.project.ProjectManifest.MaterialSlot>) = physical.map { (it.colorHex ?: "#FFFFFF") to it.type }

    /** The rows with their slot numbers, blended colours and problems for the current loaded filaments. */
    fun display(physical: List<Pair<String, String?>>, rows: List<Row>): List<Row> = MixedFilamentFormat.parseRows(run(MixedFilamentFormat.request("display", physical, rows)))

    /** [rows] with one added; null when that fixed blend is already there. */
    fun add(physical: List<Pair<String, String?>>, rows: List<Row>, row: Row): List<Row>? {
        val o = run(MixedFilamentFormat.request("add", physical, rows).put("components", org.json.JSONArray(row.components)).put("weights", org.json.JSONArray(row.weights))
            .put("gradient", row.toJson().getJSONObject("gradient")))
        return if (o.optBoolean("added")) MixedFilamentFormat.parseRows(o) else null
    }

    fun update(physical: List<Pair<String, String?>>, rows: List<Row>, index: Int, row: Row): List<Row> =
        MixedFilamentFormat.parseRows(run(MixedFilamentFormat.request("update", physical, rows).put("index", index)
            .put("components", org.json.JSONArray(row.components)).put("weights", org.json.JSONArray(row.weights)).put("gradient", row.toJson().getJSONObject("gradient"))))

    fun remove(physical: List<Pair<String, String?>>, rows: List<Row>, index: Int): List<Row> =
        MixedFilamentFormat.parseRows(run(MixedFilamentFormat.request("remove", physical, rows).put("index", index)))

    /** The rows after loaded filament [filament] (1-based) is removed: components renumber, and one that used it is flagged. */
    fun deletePhysical(physical: List<Pair<String, String?>>, rows: List<Row>, filament: Int): List<Row> =
        MixedFilamentFormat.parseRows(run(MixedFilamentFormat.request("delete_physical", physical, rows).put("filament", filament)))

    /** The recipe from the loaded filaments (or the standard CMYW / RYBW bases) that best matches [target]. */
    fun match(physical: List<Pair<String, String?>>, target: String, mode: String = "materials", material: String = ""): Match =
        MixedFilamentFormat.parseMatch(run(MixedFilamentFormat.request("match", physical).put("target", target).put("mode", mode).put("material", material)))

    /** Every pair of loaded filaments at 25 / 50 / 75 %, with the colour it makes. */
    fun presets(physical: List<Pair<String, String?>>): List<Row> = MixedFilamentFormat.parsePresets(run(MixedFilamentFormat.request("presets", physical)))

    /** The settings that make a slice use [rows] (and the project saves): filament_colour, filament_is_mixed, filament_mixed_* ... */
    fun sliceOverrides(physical: List<Pair<String, String?>>, rows: List<Row>, sublayer: Boolean = true): Map<String, String> =
        MixedFilamentFormat.parseOverrides(run(MixedFilamentFormat.request("slice_overrides", physical, rows).put("sublayer", sublayer)))
}

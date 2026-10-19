package net.jamesjennison.klippercompanion

import com.nozzleitall.printer.ext.MixedFilamentFormat
import com.nozzleitall.printer.ext.MixedFilamentFormat.Match
import com.nozzleitall.printer.ext.MixedFilamentFormat.Row
import org.json.JSONArray
import org.json.JSONObject
import org.orcaslicer.engine.NativeEngine

/**
 * Android's own transport for mixed filaments (OrcaSlicer 2.5.0's): the exact same request/response JSON Desktop's
 * MixedFilament.kt builds and parses ([MixedFilamentFormat], :printer-api), sent to the engine through the in-process
 * `nativeMixedFilament` JNI call instead of Desktop's subprocess - no separate parsing logic, so the two platforms can
 * never drift on what the engine accepts.
 */
class ColourMixEngineError(message: String) : Exception(message)

object AndroidMixedFilament {
    private fun run(request: JSONObject): JSONObject {
        val out = NativeEngine.nativeMixedFilament(request.toString())
        val json = runCatching { JSONObject(out) }.getOrElse { throw ColourMixEngineError("This engine doesn't support mixed filaments.") }
        MixedFilamentFormat.errorOf(json)?.let { throw ColourMixEngineError(it) }
        return json
    }

    /** The rows with their slot numbers, blended colours and problems for the loaded filaments [physical] (colour to type). */
    fun display(physical: List<Pair<String, String?>>, rows: List<Row>): List<Row> =
        MixedFilamentFormat.parseRows(run(MixedFilamentFormat.request("display", physical, rows)))

    /** [rows] with [row] added; null when that fixed blend is already there. */
    fun add(physical: List<Pair<String, String?>>, rows: List<Row>, row: Row): List<Row>? {
        val o = run(MixedFilamentFormat.request("add", physical, rows).put("components", JSONArray(row.components)).put("weights", JSONArray(row.weights))
            .put("gradient", row.toJson().getJSONObject("gradient")))
        return if (o.optBoolean("added")) MixedFilamentFormat.parseRows(o) else null
    }

    fun remove(physical: List<Pair<String, String?>>, rows: List<Row>, index: Int): List<Row> =
        MixedFilamentFormat.parseRows(run(MixedFilamentFormat.request("remove", physical, rows).put("index", index)))

    fun match(physical: List<Pair<String, String?>>, target: String, mode: String = "materials", material: String = ""): Match =
        MixedFilamentFormat.parseMatch(run(MixedFilamentFormat.request("match", physical).put("target", target).put("mode", mode).put("material", material)))

    /** The per-filament settings that make a slice use [rows]; passed as the slice's overrides. */
    fun sliceOverrides(physical: List<Pair<String, String?>>, rows: List<Row>, sublayer: Boolean = true): Map<String, String> =
        MixedFilamentFormat.parseOverrides(run(MixedFilamentFormat.request("slice_overrides", physical, rows).put("sublayer", sublayer)))
}

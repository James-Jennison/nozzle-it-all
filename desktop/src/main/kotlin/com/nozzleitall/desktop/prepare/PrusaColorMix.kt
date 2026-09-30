package com.nozzleitall.desktop.prepare

import com.nozzleitall.printer.ext.PrusaColorMixFormat
import com.nozzleitall.printer.ext.PrusaColorMixFormat.Component
import com.nozzleitall.printer.ext.PrusaColorMixFormat.Preset
import com.nozzleitall.printer.ext.PrusaColorMixFormat.Virtual
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * PrusaSlicer's ColorMix ("virtual extruders"), for any printer with two or more filament slots except Full Spectrum ones. A virtual extruder
 * is a numbered slot after the physical ones that prints as a repeating layer cycle of 2-3 loaded filaments (a blend),
 * or changes along Z (a gradient). Everything about them is PrusaSlicer 2.9.6's own code, run by the engine
 * (`nozzle-engine --color-mix`): normalising, colour prediction (prusa_fdm_mixer), layer cycles, presets, id remapping.
 * They are stored exactly as PrusaSlicer stores them, in the 3MF's [SIDECAR], so projects move between Nozzle and
 * PrusaSlicer unchanged.
 *
 * The virtual-extruder format (parsing, sidecar (de)serialisation, and the `virtual_extruders` slice-request JSON) is
 * shared with Android in [PrusaColorMixFormat] (:printer-api) - Android feeds the same JSON to a slice via the
 * `*Mix` JNI calls instead of this object's own CLI process. This object is Desktop's transport plus editor ops.
 */
object PrusaColorMix {
    const val SIDECAR = PrusaColorMixFormat.SIDECAR


    fun parse(entry: JSONObject): Virtual = PrusaColorMixFormat.parse(entry)

    /** The sidecar file as PrusaSlicer writes it (version 1, the physical extruders' colours, the virtual extruders). */
    fun sidecar(physical: List<String>, virtual: List<Virtual>): String = PrusaColorMixFormat.sidecar(physical, virtual)

    fun readSidecar(bytes: ByteArray): Pair<Int, List<Virtual>>? = PrusaColorMixFormat.readSidecar(bytes)

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

    private fun physicalJson(physical: List<Pair<String, String?>>) = PrusaColorMixFormat.physicalJson(physical)

    /** The virtual extruders as the engine keeps them for [physical] (invalid ones dropped), with colours and cycles. */
    fun normalize(physical: List<Pair<String, String?>>, virtual: List<Virtual>): List<Virtual> {
        val o = run(JSONObject().put("op", "normalize").put("physical", physicalJson(physical)).put("virtual", JSONArray(virtual.map { it.toJson() })))
        return PrusaColorMixFormat.parseVirtualList(o)
    }

    /** The id PrusaSlicer's "Add blend" gives the next virtual extruder. */
    fun nextId(physicalCount: Int, virtual: List<Virtual>): Int =
        run(JSONObject().put("op", "next_id").put("physical_count", physicalCount).put("virtual", JSONArray(virtual.map { it.toJson() }))).getInt("id")

    /** prusa_fdm_mixer's prediction of how [colors] in [ratios] read when printed in alternating layers. */
    fun mix(colors: List<String>, ratios: List<Double>): String =
        run(JSONObject().put("op", "mix").put("colors", JSONArray(colors)).put("ratios", JSONArray(ratios))).getString("color")


    /** PrusaSlicer's preset palette for the loaded filaments (same material only, near-duplicates removed, by hue). */
    fun presets(physical: List<Pair<String, String?>>): List<Preset> {
        val o = run(JSONObject().put("op", "presets").put("physical", physicalJson(physical)))
        return PrusaColorMixFormat.parsePresets(o)
    }

    /**
     * PrusaSlicer's import remap (remap_full_spectrum_on_import): a file saved on a printer with fewer extruders has its
     * virtual ids shifted clear of this printer's; returns the moved list and old → new ids for the paint that uses them.
     */
    fun remapImport(physicalCount: Int, sidecar: ByteArray): Pair<List<Virtual>, Map<Int, Int>> {
        val o = run(JSONObject().put("op", "remap_import").put("physical_count", physicalCount).put("sidecar", JSONObject(String(sidecar, Charsets.UTF_8))))
        return PrusaColorMixFormat.parseVirtualList(o) to PrusaColorMixFormat.parseRemap(o)
    }
}

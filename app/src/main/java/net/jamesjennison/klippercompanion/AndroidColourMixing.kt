package net.jamesjennison.klippercompanion

import com.nozzleitall.printer.ext.FullSpectrumFormat
import com.nozzleitall.printer.ext.PrusaColorMixFormat
import org.json.JSONObject
import org.orcaslicer.engine.NativeEngine

/**
 * Android's own transport for Snapmaker Full Spectrum and PrusaSlicer ColorMix: the exact same request/response JSON
 * Desktop's FullSpectrum.kt/PrusaColorMix.kt build and parse (com.nozzleitall.printer.ext.FullSpectrumFormat /
 * PrusaColorMixFormat, :printer-api) but sent to the engine through the in-process `nativeFullSpectrum`/`nativeColorMix`
 * JNI calls instead of Desktop's own subprocess - no separate parsing logic, so the two platforms can never drift on
 * what the engine actually accepts. Whichever screen offers colour mixing (SliceAndPrintPanel/ProjectEditorScreen)
 * and AndroidTestSlicer's own slice option both go through these two objects.
 */
class ColourMixEngineError(message: String) : Exception(message)

object AndroidFullSpectrum {
    private fun run(request: JSONObject): JSONObject {
        val out = NativeEngine.nativeFullSpectrum(request.toString())
        val json = runCatching { JSONObject(out) }.getOrElse { throw ColourMixEngineError("This engine doesn't support Full Spectrum yet.") }
        FullSpectrumFormat.errorOf(json)?.let { throw ColourMixEngineError(it) }
        return json
    }

    fun display(physical: List<String>, definitions: String): FullSpectrumFormat.Mixes =
        FullSpectrumFormat.parseMixes(run(FullSpectrumFormat.baseRequest("display", physical, definitions)))

    /** [a] and [b] are physical filament ids, 1-based (Tool 1 is 1), as Snapmaker's MixedFilamentManager numbers them. */
    fun add(physical: List<String>, definitions: String, a: Int, b: Int, mixBPercent: Int): FullSpectrumFormat.Mixes =
        FullSpectrumFormat.parseMixes(run(FullSpectrumFormat.baseRequest("add", physical, definitions).put("a", a).put("b", b).put("mix_b_percent", mixBPercent)))

    fun remove(physical: List<String>, definitions: String, id: Int): FullSpectrumFormat.Mixes =
        FullSpectrumFormat.parseMixes(run(FullSpectrumFormat.baseRequest("remove", physical, definitions).put("id", id)))
}

object AndroidColorMix {
    private fun run(request: JSONObject): JSONObject {
        val out = NativeEngine.nativeColorMix(request.toString())
        val json = runCatching { JSONObject(out) }.getOrElse { throw ColourMixEngineError("This engine doesn't support Prusa colour mixing yet.") }
        PrusaColorMixFormat.errorOf(json)?.let { throw ColourMixEngineError(it) }
        return json
    }

    /** The virtual extruders as the engine keeps them for [physical] (invalid ones dropped), with colours and cycles. */
    fun normalize(physical: List<Pair<String, String?>>, virtual: List<PrusaColorMixFormat.Virtual>): List<PrusaColorMixFormat.Virtual> {
        val o = run(JSONObject().put("op", "normalize").put("physical", PrusaColorMixFormat.physicalJson(physical))
            .put("virtual", org.json.JSONArray(virtual.map { it.toJson() })))
        return PrusaColorMixFormat.parseVirtualList(o)
    }

    /** The id PrusaSlicer's "Add blend" gives the next virtual extruder. */
    fun nextId(physicalCount: Int, virtual: List<PrusaColorMixFormat.Virtual>): Int =
        run(JSONObject().put("op", "next_id").put("physical_count", physicalCount).put("virtual", org.json.JSONArray(virtual.map { it.toJson() }))).getInt("id")
}

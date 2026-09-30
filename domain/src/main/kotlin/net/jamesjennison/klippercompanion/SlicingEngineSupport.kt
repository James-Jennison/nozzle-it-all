package net.jamesjennison.klippercompanion

import org.json.JSONObject

/**
 * Bundled printer profiles Nozzle It All doesn't offer yet (engine/profiles/unsupported-profiles.json, the same list
 * Desktop and the Web App use). The engine slices them (P-0023), but none has been printed on a real machine, and the
 * two-extruder models need a filament-to-extruder choice the app doesn't make yet. Owner decision 2026-09-27: not offered
 * until tested on a real machine.
 */
object SlicingEngineSupport {
    /** Profile folder (SlicingModelInfo.assetDir) to the reason it isn't offered yet. */
    val unsupported: Map<String, String> by lazy {
        val text = SlicingEngineSupport::class.java.getResourceAsStream("unsupported-profiles.json")?.use { it.readBytes().decodeToString() }
            ?: return@lazy emptyMap()
        JSONObject(text).optJSONObject("profiles")?.let { o -> o.keySet().associateWith { o.getString(it) } } ?: emptyMap()
    }

    fun isSupported(model: SlicingPrinterModel): Boolean = SlicingModelCatalog.info(model).assetDir !in unsupported

    /** The models that can be offered for slicing, in catalog order. */
    val offered: List<SlicingModelInfo> by lazy { SlicingModelCatalog.all.filter { it.assetDir !in unsupported } }

    /** Why [model] isn't offered yet, or null when it is. */
    fun unsupportedReason(model: SlicingPrinterModel): String? {
        val info = SlicingModelCatalog.info(model)
        return unsupported[info.assetDir]?.let { "${info.label} isn't offered by this version yet: slicing for it hasn't been tested on a real printer. Choose another printer model." }
    }
}

package net.jamesjennison.klippercompanion

import org.json.JSONObject

/**
 * Bundled printer profiles the slicing engine can't slice yet (engine/snapmaker/unsupported-profiles.json, the same list
 * Desktop and the Web App use). The engine is Snapmaker Orca's libslic3r on every platform; these profiles' start G-code
 * needs upstream OrcaSlicer's multi-nozzle system, which that engine doesn't have. Owner decision 2026-09-27: they are
 * not offered until that system is ported, never sliced with a single-nozzle stand-in.
 */
object SlicingEngineSupport {
    /** Profile folder (SlicingModelInfo.assetDir) to the reason it can't be sliced yet. */
    val unsupported: Map<String, String> by lazy {
        val text = SlicingEngineSupport::class.java.getResourceAsStream("unsupported-profiles.json")?.use { it.readBytes().decodeToString() }
            ?: return@lazy emptyMap()
        JSONObject(text).optJSONObject("profiles")?.let { o -> o.keySet().associateWith { o.getString(it) } } ?: emptyMap()
    }

    fun isSupported(model: SlicingPrinterModel): Boolean = SlicingModelCatalog.info(model).assetDir !in unsupported

    /** The models that can be offered for slicing, in catalog order. */
    val offered: List<SlicingModelInfo> by lazy { SlicingModelCatalog.all.filter { it.assetDir !in unsupported } }

    /** Why [model] can't be sliced yet, or null when it can. */
    fun unsupportedReason(model: SlicingPrinterModel): String? {
        val info = SlicingModelCatalog.info(model)
        return unsupported[info.assetDir]?.let { "${info.label} can't be sliced by this version yet: its printer profile needs multi-nozzle support the slicing engine doesn't have. Choose another printer model." }
    }
}

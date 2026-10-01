package net.jamesjennison.klippercompanion

import org.json.JSONObject

/**
 * One print profile (an OrcaSlicer process preset) a printer pack offers, from its slicer_profiles/<pack>/processes.json
 * (scripts/bundle_process_presets.py). [name] is the full Orca preset name, which is what projects store and what
 * SlicingCoordinator.slice/sliceProject take; [file] is the flattened preset's asset path.
 * [infillPercent] is null when the preset leaves sparse_infill_density to the engine default.
 * [madeBy] is set for presets Nozzle It All derived itself (engine/profiles/derived), not the vendor's.
 */
data class ProcessPreset(
    val name: String,
    val label: String,
    val layerHeightMm: Double?,
    val infillPercent: Int?,
    val file: String,
    val madeBy: String? = null,
    val colorMixing: Boolean = false,
) {
    /** The picker's label: Nozzle It All's own presets are marked, as Desktop marks them. */
    val displayLabel get() = madeBy?.let { "$label ($it)" } ?: label
}

data class ProcessPresets(val defaultName: String, val presets: List<ProcessPreset>) {
    val default: ProcessPreset get() = presets.first { it.name == defaultName }
    fun find(name: String?): ProcessPreset? = name?.let { n -> presets.firstOrNull { it.name == n } }
    /** [name]'s preset, the pack default for null; null when the pack doesn't offer [name]. */
    fun resolve(name: String?): ProcessPreset? = if (name == null) default else find(name)
    /** More than the pack's own process: Prepare shows the Print profile picker instead of the Quality chips. */
    val hasChoice get() = presets.size > 1
    val colorMixingPreset: ProcessPreset? get() = presets.firstOrNull { it.colorMixing }

    companion object {
        /** A pack without processes.json offers its own process.json only. */
        fun single(name: String, file: String, layerHeightMm: Double?, infillPercent: Int?) =
            ProcessPresets(name, listOf(ProcessPreset(name, name.substringBefore(" @").trim(), layerHeightMm, infillPercent, file)))

        fun parse(text: String): ProcessPresets {
            val o = JSONObject(text)
            val a = o.getJSONArray("presets")
            val presets = (0 until a.length()).map { i ->
                val p = a.getJSONObject(i)
                ProcessPreset(
                    name = p.getString("name"),
                    label = p.optString("label").ifBlank { p.getString("name") },
                    layerHeightMm = p.optString("layer_height").toDoubleOrNull(),
                    infillPercent = percent(p.optString("infill")),
                    file = p.getString("file"),
                    madeBy = p.optString("made_by").ifBlank { null },
                    colorMixing = p.optBoolean("color_mixing", false),
                )
            }
            val default = o.getString("default")
            require(presets.any { it.name == default }) { "processes.json: default \"$default\" is not one of its presets" }
            return ProcessPresets(default, presets)
        }

        /** Orca's sparse_infill_density: "15%", or a bare "15" some vendor presets use. */
        fun percent(value: String?): Int? = value?.trim()?.removeSuffix("%")?.trim()?.toDoubleOrNull()?.let { Math.round(it).toInt() }
    }
}

package net.jamesjennison.klippercompanion

// The deliberately small set of slicing settings this app's own UI exposes - not OrcaSlicer's
// full settings surface (hundreds of options), but the handful that actually change print
// outcomes for a typical print: layer height (speed vs. quality), infill density (strength vs.
// material/time), and whether supports print at all. Real OrcaSlicer config keys (verified
// against the bundled profile packs' own process.json files - see PROVENANCE.md), passed through
// SlicingCoordinator.slice()'s generic override mechanism (originally added for the
// use_relative_e_distances fix), not a separate/new code path.
data class SliceCustomization(val layerHeightMm: Double, val infillPercent: Int, val supportsEnabled: Boolean) {
    fun toOverrides(): Map<String, String> = mapOf(
        "layer_height" to layerHeightFormatted(),
        "sparse_infill_density" to "${infillPercent}%",
        "enable_support" to if (supportsEnabled) "1" else "0",
    )
    // OrcaSlicer's own profiles store this as a plain decimal string ("0.2", "0.28") - avoids a
    // trailing ".0" for a whole-number height like 1.0mm, which some config parsers are stricter
    // about than others.
    private fun layerHeightFormatted(): String {
        val rounded = Math.round(layerHeightMm * 100) / 100.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }
}

// A conservative, always-safe starting point - matches every bundled pack's own default (see
// PROVENANCE.md), not a guess: 0.2mm/15%/no supports is standard-quality, no-supports-needed
// territory for a simple print, which is the safest thing to default to sight-unseen.
val DEFAULT_SLICE_CUSTOMIZATION = SliceCustomization(0.2, 15, false)

// 0.04mm is close to the finest layer height any FDM nozzle can realistically resolve; 0.6mm is
// close to the coarsest sane height even for a large 0.8mm nozzle. Outside that range is far more
// likely a typo than an intentional choice.
fun validateLayerHeight(raw: String): Double? = raw.trim().toDoubleOrNull()?.takeIf { it in 0.04..0.6 }
fun validateInfillPercent(raw: String): Int? = raw.trim().toIntOrNull()?.takeIf { it in 0..100 }

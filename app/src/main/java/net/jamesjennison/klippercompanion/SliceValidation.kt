package net.jamesjennison.klippercompanion

import android.content.Context
import org.json.JSONObject

// Phase 5 (Consumer Slicer Plan §16): "slice-time validation (profile/material/nozzle
// compatibility)" - real checks against this printer's own bundled profile pack data, run before
// the native engine ever sees the config, so a genuinely invalid combination surfaces as an
// actionable message instead of either a raw native exception or (worse) a slice that "succeeds"
// but is quietly wrong. Continuous bounds/collision validation for multi-object already exists
// (Phase 1, ProjectWorkspace's own real-time out-of-bounds/collision checks) - this phase's real
// remaining scope is the slice-time half.

// min/maxLayerHeightMm: real per-printer machine limits (machine.json's own
// min_layer_height/max_layer_height) - a genuine hardware/firmware bound, not a UI-invented one;
// printing outside it is a real failure mode, not just an unusual choice.
data class MachineLimits(val minLayerHeightMm: Double?, val maxLayerHeightMm: Double?)

// lowC/highC: real per-profile-pack filament temperature hints (filament.json's own
// nozzle_temperature_range_low/high) - the bundled profile's own declared safe range for the
// material it ships with, not a hard hardware limit (a real printer can often run hotter/cooler
// safely for a different material) - see SliceValidationIssue's own comment on why this is a
// warning, not a block.
data class FilamentTemperatureRange(val lowC: Int?, val highC: Int?)

sealed class SliceValidationIssue(val message: String, val blocking: Boolean) {
    // A genuine hardware/firmware bound (machine.json's own declared range) - printing outside
    // it is a real failure mode (under/over-extrusion, or a value libslic3r itself may not
    // validate), so this blocks slicing rather than just warning.
    data class LayerHeightOutOfRange(val layerHeightMm: Double, val limits: MachineLimits) : SliceValidationIssue(
        "Layer height ${layerHeightMm}mm is outside this printer's real supported range" +
            (limits.minLayerHeightMm?.let { lo -> limits.maxLayerHeightMm?.let { hi -> " (${lo}mm-${hi}mm)" } } ?: "") +
            " - choose a different quality preset.",
        blocking = true,
    )
    // The bundled profile's own declared range is real but not a hard limit - PETG/ABS commonly
    // print hotter than a PLA-centric default range on real hardware without issue, so this
    // informs rather than blocks (matches this app's existing "explain, don't silently guess"
    // discipline rather than second-guessing a real, deliberate owner choice).
    data class MaterialTemperatureOutsideProfileRange(val material: MaterialProfile, val range: FilamentTemperatureRange) : SliceValidationIssue(
        "${material.displayName}'s nozzle temperature (${material.tempNozzleC}°C) is outside this printer's bundled profile's declared range" +
            (range.lowC?.let { lo -> range.highC?.let { hi -> " (${lo}°C-${hi}°C)" } } ?: "") +
            " - double check this is intentional before printing.",
        blocking = false,
    )
}

fun validateSliceConfiguration(
    limits: MachineLimits?,
    layerHeightMm: Double,
    filamentRange: FilamentTemperatureRange?,
    material: MaterialProfile?,
): List<SliceValidationIssue> {
    val issues = mutableListOf<SliceValidationIssue>()
    if (limits != null) {
        val tooLow = limits.minLayerHeightMm?.let { layerHeightMm < it - 1e-9 } ?: false
        val tooHigh = limits.maxLayerHeightMm?.let { layerHeightMm > it + 1e-9 } ?: false
        if (tooLow || tooHigh) issues += SliceValidationIssue.LayerHeightOutOfRange(layerHeightMm, limits)
    }
    if (filamentRange != null && material?.tempNozzleC != null) {
        val tooLow = filamentRange.lowC?.let { material.tempNozzleC < it } ?: false
        val tooHigh = filamentRange.highC?.let { material.tempNozzleC > it } ?: false
        if (tooLow || tooHigh) issues += SliceValidationIssue.MaterialTemperatureOutsideProfileRange(material, filamentRange)
    }
    return issues
}

// machine.json's arrays are single-element ("['0.4']") the same shape BedShape.kt's own
// printable_area parsing already handles - first element only, real per-printer data.
internal fun parseMachineLimits(machineJson: String): MachineLimits {
    val obj = JSONObject(machineJson)
    fun firstDouble(key: String): Double? = obj.optJSONArray(key)?.takeIf { it.length() > 0 }?.optString(0)?.toDoubleOrNull()
    return MachineLimits(firstDouble("min_layer_height"), firstDouble("max_layer_height"))
}

internal fun parseFilamentTemperatureRange(filamentJson: String): FilamentTemperatureRange {
    val obj = JSONObject(filamentJson)
    fun firstInt(key: String): Int? = obj.optJSONArray(key)?.takeIf { it.length() > 0 }?.optString(0)?.toDoubleOrNull()?.toInt()
    return FilamentTemperatureRange(firstInt("nozzle_temperature_range_low"), firstInt("nozzle_temperature_range_high"))
}

internal fun SlicingProfilePack.readMachineLimits(context: Context): MachineLimits =
    parseMachineLimits(context.assets.open(machinePath).use { it.reader().readText() })

internal fun SlicingProfilePack.readFilamentTemperatureRange(context: Context): FilamentTemperatureRange =
    parseFilamentTemperatureRange(context.assets.open(filamentPath).use { it.reader().readText() })

internal fun machineLimitsFor(model: SlicingPrinterModel, cosmosGeneration: CosmosProfileGeneration?, context: Context): MachineLimits? =
    slicingProfilePack(model, cosmosGeneration)?.readMachineLimits(context)

internal fun filamentTemperatureRangeFor(model: SlicingPrinterModel, cosmosGeneration: CosmosProfileGeneration?, context: Context): FilamentTemperatureRange? =
    slicingProfilePack(model, cosmosGeneration)?.readFilamentTemperatureRange(context)

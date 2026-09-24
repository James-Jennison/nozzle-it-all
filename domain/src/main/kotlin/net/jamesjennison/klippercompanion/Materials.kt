package net.jamesjennison.klippercompanion

// Phase 3 (Consumer Slicer Plan §11): promotes material from "three slicer settings" to a real,
// slicing-connected entity. Deliberately v1-scoped to match the plan's own Phase 3 boundary
// ("single-material-per-project UI - multi-tool UI is Phase 8"): one MaterialProfile per project,
// not per-object/per-tool assignment (MaterialAssignment/ToolSlot, both real §11 concepts, stay
// unbuilt until Phase 8 actually needs multiple simultaneous materials - every printer
// integration in this codebase is single-extruder today, so building that machinery now would be
// speculative, not load-bearing).
enum class MaterialSource { BUNDLED, SPOOLMAN, CUSTOM }

// tempNozzleC/tempBedC are nullable - a Spoolman filament with neither settings_extruder_temp nor
// settings_bed_temp set (real, common: not every filament entry has them configured) still
// produces a usable MaterialProfile, it just doesn't override that specific temperature at slice
// time (see toOverrides() below) - the bundled per-printer profile's own default stays in effect
// rather than a value this app invented.
data class MaterialProfile(
    val id: String,
    val displayName: String,
    val type: String,
    val manufacturer: String = "",
    val colorHex: String? = null,
    val tempNozzleC: Int? = null,
    val tempBedC: Int? = null,
    val source: MaterialSource,
)

// Real, standard FDM starting temperatures - safe, widely-used defaults for each material family,
// not tuned to any specific vendor's spec sheet. A Spoolman- or custom-sourced profile with more
// specific numbers always takes precedence when one is picked instead.
val BUNDLED_MATERIAL_PROFILES: List<MaterialProfile> = listOf(
    MaterialProfile("bundled-pla", "PLA", "PLA", tempNozzleC = 210, tempBedC = 60, source = MaterialSource.BUNDLED),
    MaterialProfile("bundled-petg", "PETG", "PETG", tempNozzleC = 240, tempBedC = 80, source = MaterialSource.BUNDLED),
    MaterialProfile("bundled-abs", "ABS", "ABS", tempNozzleC = 250, tempBedC = 100, source = MaterialSource.BUNDLED),
    MaterialProfile("bundled-tpu", "TPU", "TPU", tempNozzleC = 220, tempBedC = 50, source = MaterialSource.BUNDLED),
)

// Spoolman becomes a *source* for MaterialProfile (§11's own framing) rather than a disconnected
// inventory viewer - this doesn't change what SpoolmanPanel/Spoolman.kt themselves do, it's a
// pure conversion at the boundary.
fun SpoolmanSpool.toMaterialProfile(): MaterialProfile = MaterialProfile(
    id = "spoolman-$id",
    displayName = Spoolman.displayName(this),
    type = material ?: "Unknown",
    manufacturer = vendorName ?: "",
    colorHex = colorHex,
    tempNozzleC = tempNozzleC,
    tempBedC = tempBedC,
    source = MaterialSource.SPOOLMAN,
)

// Real OrcaSlicer config keys (verified against this app's own bundled profile packs' filament.json
// files, e.g. slicer_profiles/prusa_generic/filament.json), passed through SlicingCoordinator's
// existing generic override mechanism - the same one SliceCustomization already uses, not a new
// code path. nozzle_temperature/nozzle_temperature_initial_layer are the real, single keys for
// hotend temperature. Bed temperature is keyed by *which plate type* the machine profile is
// configured for (cool/eng/hot/textured "_plate_temp", each with its own "_initial_layer"
// variant - OrcaSlicer's own machine.json picks one via bed_type/curr_bed_type) - rather than
// re-deriving which single plate type each bundled profile happens to use, this overrides every
// plate-type key to the same value, so whichever one the machine's own start-gcode actually
// references gets the real material temperature regardless.
fun MaterialProfile.toOverrides(): Map<String, String> {
    val overrides = mutableMapOf<String, String>()
    tempNozzleC?.let {
        overrides["nozzle_temperature"] = it.toString()
        overrides["nozzle_temperature_initial_layer"] = it.toString()
    }
    tempBedC?.let { bed ->
        for (plate in listOf("cool_plate_temp", "eng_plate_temp", "hot_plate_temp", "textured_plate_temp")) {
            overrides[plate] = bed.toString()
            overrides["${plate}_initial_layer"] = bed.toString()
        }
    }
    return overrides
}

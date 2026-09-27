package net.jamesjennison.klippercompanion

// Phase 8 follow-up (§11, §16, WO-27): the real, general-purpose version of the multi-slot
// filament config WO-26 proved end to end (ToolAssignmentSlicingDeviceTest, hardcoded there for
// one fixed 4-color case). Builds the same real override keys from an arbitrary, real per-slot
// MaterialProfile assignment instead.
//
// Only overrides filament_diameter (the one *structural* key - its array LENGTH is what
// libslic3r actually uses to compute how many extruders exist, confirmed by reading
// PrintApply.cpp directly, not machine.json's declared count - WO-26) plus the real identity-
// defining keys (filament_colour/filament_type/nozzle_temperature*). Every other filament_*
// setting deliberately stays at the base bundled profile's own single value for every slot: real,
// direct confirmation from Config.hpp - ConfigOptionVector::get_at(i) clamps to index 0
// ("values.front()") whenever i is past the array's real length, rather than reading out of
// bounds - so an unmentioned key safely and correctly falls back to slot 1's own bundled value
// for every other slot, with no need to replicate it here.
object MultiToolFilamentConfig {
    const val FLUSH_UNLOAD_LOAD_MM3 = 140

    // slots: index i is real tool slot (i+1) - a null entry means "no material assigned to this
    // slot", which falls back to `fallback` (typically the printer's own bundled default
    // material) so every slot still has a real, valid identity even when a project has only
    // assigned some objects/slots a specific material. Real per-slot values come from each
    // MaterialProfile's own colorHex/type/tempNozzleC - the same fields MaterialProfile.
    // toOverrides() already reads for the single-material case, not new/invented data.
    // flush: how the printer's own slicer works out flushing volumes, with its nozzle volume and support filaments
    // (FlushVolumes.setup); without it, Snapmaker Orca's calculation from a minimum of 0.
    fun overridesFor(baseFilamentDiameterMm: Double, slots: List<MaterialProfile?>, fallback: MaterialProfile,
                     flush: FlushVolumes.Setup = FlushVolumes.Setup()): Map<String, String> {
        require(slots.isNotEmpty()) { "At least one tool slot is required." }
        val resolved = slots.map { it ?: fallback }
        val overrides = mutableMapOf<String, String>()
        overrides["filament_diameter"] = resolved.joinToString(",") { formatMm(baseFilamentDiameterMm) }
        overrides["filament_colour"] = resolved.joinToString(";") { (it.colorHex?.takeIf(String::isNotBlank) ?: "#FFFFFF") }
        overrides["filament_type"] = resolved.joinToString(";") { it.type.ifBlank { "PLA" } }
        // tempNozzleC is nullable (a real Spoolman/custom profile might not declare one - see
        // MaterialProfile's own header comment) - falls back to fallback's own temperature, then
        // a real, safe PLA-family default, matching toOverrides()'s own null-tolerant approach
        // rather than writing an invalid/zero temperature into the config.
        val defaultNozzleC = fallback.tempNozzleC ?: 210
        overrides["nozzle_temperature"] = resolved.joinToString(",") { (it.tempNozzleC ?: defaultNozzleC).toString() }
        overrides["nozzle_temperature_initial_layer"] = resolved.joinToString(",") { (it.tempNozzleC ?: defaultNozzleC).toString() }
        // libslic3r's Print::validate() rejects a slice whose flush matrix is not N x N for N filaments ("Flush volumes
        // matrix do not match to the correct size!"). The Snapmaker U1 profile ships a 4x4 one; the Prusa XL 5T profile
        // ships none for five tools, so it is always generated here for the real slot count, from the slot colours as
        // the printer's own slicer works it out (FlushVolumes), plus the per-filament unload/load pair.
        val n = resolved.size
        overrides["flush_volumes_matrix"] = flush.matrix(resolved.map { it.colorHex?.takeIf(String::isNotBlank) ?: "#FFFFFF" }).joinToString(",")
        overrides["flush_volumes_vector"] = List(n * 2) { FLUSH_UNLOAD_LOAD_MM3.toString() }.joinToString(",")
        return overrides
    }

    private fun formatMm(mm: Double): String {
        // Trims a trailing ".0" (e.g. "1.75" stays "1.75", but a base value that happened to be a
        // whole number prints as "2", not "2.0") - cosmetic only, ConfigOptionFloats' own
        // deserialize (Config.hpp, comma-split + istringstream >> double) accepts either form.
        return if (mm == mm.toLong().toDouble()) mm.toLong().toString() else mm.toString()
    }
}

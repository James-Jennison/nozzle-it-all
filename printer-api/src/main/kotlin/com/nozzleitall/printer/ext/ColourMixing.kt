package com.nozzleitall.printer.ext

import com.nozzleitall.printer.PrinterFamily

/**
 * Colour-mixing features a printer profile offers when no live printer says otherwise, so Prepare can show them for a
 * profile alone ("None (export only)"). Vendor knowledge stays here: screens only ask for a feature key.
 *
 * Mixed filaments are OrcaSlicer 2.5.0's own: the slicer resolves each one into the loaded filaments, so any printer
 * with two or more filament slots can print them (owner decision 2026-10-16: one mixing system, Orca's).
 *
 * Owner rule (2026-10-01): no color mixing on Elegoo's CANVAS ([CANVAS_PROFILES]). CANVAS feeds one nozzle, so a mix
 * changes filament on every layer, and each change is a cut, a purge and most of a minute: 71 changes for two 15 x 4 mm
 * test tiles on the owner's Centauri Carbon. Not worth the purge waste or the time.
 */
object ProfileFeatures {
    /** The bundled CANVAS printer profiles (slicer_profiles/index.json ids): four lanes into one nozzle. */
    val CANVAS_PROFILES = setOf("elegoo_centauri_carbon_canvas", "elegoo_centauri_carbon_2_canvas", "elegoo_centauri_carbon_cosmos_afc")

    /** [profileId]: the bundled printer profile being sliced with, when one is chosen. */
    @Suppress("UNUSED_PARAMETER")
    fun of(family: String, tools: Int, profileId: String? = null): Set<String> =
        if (tools < 2 || profileId in CANVAS_PROFILES) emptySet() else setOf(MixedFilamentFormat.FEATURE)

    /** What a connected printer offers: its own reported extensions, plus mixed filaments when it has two or more slots (unless sliced with a CANVAS profile). */
    fun ofPrinter(reported: Set<String>, slots: Int, profileId: String? = null): Set<String> =
        if (slots >= 2 && profileId !in CANVAS_PROFILES) reported + MixedFilamentFormat.FEATURE else reported
}

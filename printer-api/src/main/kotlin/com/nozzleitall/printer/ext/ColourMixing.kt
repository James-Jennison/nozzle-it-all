package com.nozzleitall.printer.ext

import com.nozzleitall.printer.PrinterFamily

/** Prusa vendor extensions, named the same way as [Snapmaker]'s. */
object Prusa {
    /** PrusaSlicer's ColorMix ("virtual extruders"): mixes of 2-3 loaded filaments printed layer by layer. Offered on any
     *  multi-slot printer that doesn't have Full Spectrum (see [ProfileFeatures]). */
    const val COLOR_MIX = "prusa.color-mix"
}

/**
 * Colour-mixing features a printer profile offers when no live printer says otherwise, so Prepare can show them for a
 * profile alone ("None (export only)"). Vendor knowledge stays here: screens only ask for a feature key.
 *
 * Owner rules (2026-09-27): Snapmaker's Full Spectrum on the U1 families; PrusaSlicer's ColorMix on every other printer
 * with two or more filament slots, as PrusaSlicer offers it (any multi-slot FFF printer). One mixing system per printer:
 * a Full Spectrum printer doesn't also get ColorMix.
 *
 * Owner rule (2026-10-01): no color mixing on Elegoo's CANVAS ([CANVAS_PROFILES]). CANVAS feeds one nozzle, so a mix
 * changes filament on every layer, and each change is a cut, a purge and most of a minute: 71 changes for two 15 x 4 mm
 * test tiles on the owner's Centauri Carbon. Not worth the purge waste or the time.
 */
object ProfileFeatures {
    /** The bundled CANVAS printer profiles (slicer_profiles/index.json ids): four lanes into one nozzle. */
    val CANVAS_PROFILES = setOf("elegoo_centauri_carbon_canvas", "elegoo_centauri_carbon_2_canvas", "elegoo_centauri_carbon_cosmos_afc")

    /** [profileId]: the bundled printer profile being sliced with, when one is chosen. */
    fun of(family: String, tools: Int, profileId: String? = null): Set<String> = buildSet {
        if (tools < 2 || profileId in CANVAS_PROFILES) return@buildSet
        if (family == PrinterFamily.PAXX_U1.id || family == PrinterFamily.STOCK_U1.id) add(Snapmaker.FULL_SPECTRUM)
        else add(Prusa.COLOR_MIX)
    }

    /**
     * What a connected printer offers: its own reported extensions, plus ColorMix when it has two or more slots and
     * doesn't offer Full Spectrum (ColorMix is a slicing feature, so any multi-slot printer can print it), unless it is
     * sliced with a CANVAS profile.
     */
    fun ofPrinter(reported: Set<String>, slots: Int, profileId: String? = null): Set<String> =
        if (slots >= 2 && Snapmaker.FULL_SPECTRUM !in reported && profileId !in CANVAS_PROFILES) reported + Prusa.COLOR_MIX else reported
}

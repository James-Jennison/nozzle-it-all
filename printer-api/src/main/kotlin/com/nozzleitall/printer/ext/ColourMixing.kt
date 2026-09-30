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
 */
object ProfileFeatures {
    fun of(family: String, tools: Int): Set<String> = buildSet {
        if (tools < 2) return@buildSet
        if (family == PrinterFamily.PAXX_U1.id || family == PrinterFamily.STOCK_U1.id) add(Snapmaker.FULL_SPECTRUM)
        else add(Prusa.COLOR_MIX)
    }

    /**
     * What a connected printer offers: its own reported extensions, plus ColorMix when it has two or more slots and
     * doesn't offer Full Spectrum (ColorMix is a slicing feature, so any multi-slot printer can print it).
     */
    fun ofPrinter(reported: Set<String>, slots: Int): Set<String> =
        if (slots >= 2 && Snapmaker.FULL_SPECTRUM !in reported) reported + Prusa.COLOR_MIX else reported
}

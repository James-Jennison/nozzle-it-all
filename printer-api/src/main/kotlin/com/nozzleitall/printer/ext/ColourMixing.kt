package com.nozzleitall.printer.ext

import com.nozzleitall.printer.PrinterFamily

/** Prusa vendor extensions, named the same way as [Snapmaker]'s. */
object Prusa {
    /** PrusaSlicer's ColorMix ("virtual extruders"): mixes of 2-3 loaded filaments printed layer by layer. */
    const val COLOR_MIX = "prusa.color-mix"
}

/**
 * Colour-mixing features a printer profile offers when no live printer says otherwise, so Prepare can show them for a
 * profile alone ("None (export only)"). Vendor knowledge stays here: screens only ask for a feature key.
 *
 * Owner rules (2026-09-27): Snapmaker's Full Spectrum for the U1 families; Prusa's colour mixing only on Prusa
 * profiles with at least two filament slots (PrusaSlicer itself offers it on any multi-slot printer; limiting it to
 * Prusa is Nozzle's rule, recorded in docs/upstream/PROVENANCE.md).
 */
object ProfileFeatures {
    fun of(family: String, tools: Int): Set<String> = buildSet {
        if (tools >= 2 && (family == PrinterFamily.PAXX_U1.id || family == PrinterFamily.STOCK_U1.id)) add(Snapmaker.FULL_SPECTRUM)
        if (tools >= 2 && family == PrinterFamily.PRUSA.id) add(Prusa.COLOR_MIX)
    }
}

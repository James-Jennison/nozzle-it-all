package com.nozzleitall.testgrid

import net.jamesjennison.klippercompanion.SlicingModelCatalog

/** Slicing-profile rules shared by the runner and the instructions. */
object SuiteProfiles {
    /** A slice step's `profile` that means "the profile saved for this printer", for suites that meet any model. */
    const val PRINTER = "@printer"

    /** The bundled profile directory for a saved printer's slicing model (its enum name), or null when none is chosen. */
    fun forPrinter(slicingModel: String?): String? =
        slicingModel?.takeIf { it.isNotBlank() }?.let { name -> SlicingModelCatalog.all.firstOrNull { it.model.name == name }?.assetDir }

    /**
     * Whether a printer's reported job name is the file Nozzle sent: printers report it differently (a Bambu reports its
     * subtask name without `.gcode.3mf`; some add a folder).
     */
    fun sameFile(reported: String, sent: String): Boolean {
        fun base(s: String) = s.substringAfterLast('/').removeSuffix(".gcode.3mf").removeSuffix(".3mf").removeSuffix(".gcode").removeSuffix(".gco").lowercase()
        return reported.isNotBlank() && base(reported) == base(sent)
    }
}

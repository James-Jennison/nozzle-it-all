package net.jamesjennison.klippercompanion

/**
 * Bambu Lab's AMS (and AMS lite): one nozzle fed from several spools. The bundled machine.json only declares the
 * extruders, so the single-extruder Bambu printers that take an AMS get their filament slots here, the way the Elegoo
 * CANVAS packs do (ElegooProfiles.filamentSlots): one AMS, four slots. Slot n is filament n in the sliced file (T n-1).
 * The two-extruder models (H2D, H2D Pro, X2D, H2C) keep one slot per extruder.
 */
object BambuAms {
    const val SLOTS_PER_UNIT = 4

    private val SINGLE_EXTRUDER_AMS_MODELS = setOf(
        SlicingPrinterModel.BAMBU_X1, SlicingPrinterModel.BAMBU_X1_CARBON, SlicingPrinterModel.BAMBU_X1E,
        SlicingPrinterModel.BAMBU_P1P, SlicingPrinterModel.BAMBU_P1S, SlicingPrinterModel.BAMBU_P2S,
        SlicingPrinterModel.BAMBU_GENERIC, SlicingPrinterModel.BAMBU_A1_MINI, SlicingPrinterModel.BAMBU_H2S,
    )

    /** The printer's filament slot count when it takes an AMS; null for every other model. */
    fun filamentSlots(model: SlicingPrinterModel): Int? = if (model in SINGLE_EXTRUDER_AMS_MODELS) SLOTS_PER_UNIT else null
}

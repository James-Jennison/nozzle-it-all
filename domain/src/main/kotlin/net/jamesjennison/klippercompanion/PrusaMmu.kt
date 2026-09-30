package net.jamesjennison.klippercompanion

/**
 * Prusa's MMU3: one nozzle fed from five spools. Like the Elegoo CANVAS and Bambu AMS packs, the machine.json declares
 * one extruder (upstream OrcaSlicer's CORE One MMU3 does the same), so the MMU3 packs get their filament slots here.
 * Slot n is filament n in the sliced file (T n-1); the printer's firmware drives the MMU3 on each T command.
 */
object PrusaMmu {
    const val MMU3_SLOTS = 5

    private val MMU3_MODELS = setOf(
        SlicingPrinterModel.PRUSA_CORE_ONE_MMU3, SlicingPrinterModel.PRUSA_MK4S_MMU3,
        SlicingPrinterModel.PRUSA_MK3_9_MMU3, SlicingPrinterModel.PRUSA_MK3_5_MMU3,
    )

    /** The printer's filament slot count when it has an MMU3; null for every other model. */
    fun filamentSlots(model: SlicingPrinterModel): Int? = if (model in MMU3_MODELS) MMU3_SLOTS else null
}

package net.jamesjennison.klippercompanion

/**
 * Single-nozzle filament changers whose printer firmware does the swap on a plain T<n> (P-0032): Creality's CFS,
 * Anycubic's ACE / ACE Pro, Flashforge's IFS, and the Qidi Box (P-0034, whose packs' own change G-code cuts, swaps and
 * flushes into the chute; it slices correctly once the engine gives them their type-1 wipe tower). Like the Bambu AMS and Prusa MMU3 packs, the machine.json declares one
 * extruder, so the printers that take one of these get their filament slots here: one unit, four slots. Slot n is
 * filament n in the sliced file (T n-1).
 *
 * Slicing only: which physical slot each filament uses is chosen on the printer (or, later, sent at print start).
 */
object FilamentChangers {
    enum class Changer(val label: String) { CREALITY_CFS("Creality CFS"), ANYCUBIC_ACE("Anycubic ACE"), FLASHFORGE_IFS("Flashforge IFS"), QIDI_BOX("Qidi Box") }

    const val SLOTS_PER_UNIT = 4

    private val MODELS: Map<SlicingPrinterModel, Changer> = buildMap {
        for (m in listOf(SlicingPrinterModel.CREALITY_K2, SlicingPrinterModel.CREALITY_K2_PLUS, SlicingPrinterModel.CREALITY_K2_PRO,
                SlicingPrinterModel.CREALITY_K2_SE, SlicingPrinterModel.CREALITY_HI, SlicingPrinterModel.CREALITY_K1_CFS_C,
                SlicingPrinterModel.CREALITY_K1C_CFS_C, SlicingPrinterModel.CREALITY_K1_SE_CFS_C, SlicingPrinterModel.CREALITY_K1_MAX_CFS_C))
            put(m, Changer.CREALITY_CFS)
        for (m in listOf(SlicingPrinterModel.ANYCUBIC_KOBRA_3, SlicingPrinterModel.ANYCUBIC_KOBRA_3_MAX, SlicingPrinterModel.ANYCUBIC_KOBRA_S1,
                SlicingPrinterModel.ANYCUBIC_KOBRA_S1_MAX, SlicingPrinterModel.ANYCUBIC_KOBRA_X))
            put(m, Changer.ANYCUBIC_ACE)
        put(SlicingPrinterModel.FLASHFORGE_AD5X, Changer.FLASHFORGE_IFS)
        for (m in listOf(SlicingPrinterModel.QIDI_Q2, SlicingPrinterModel.QIDI_Q2C, SlicingPrinterModel.QIDI_X_PLUS_4,
                SlicingPrinterModel.QIDI_X_MAX_4, SlicingPrinterModel.QIDI_X_PLUS_5))
            put(m, Changer.QIDI_BOX)
    }

    /** The changer a printer model takes, or null. */
    fun changerFor(model: SlicingPrinterModel): Changer? = MODELS[model]

    /** The printer's filament slot count when it takes one of these changers; null for every other model. */
    fun filamentSlots(model: SlicingPrinterModel): Int? = if (model in MODELS) SLOTS_PER_UNIT else null
}

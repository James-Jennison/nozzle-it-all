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

    /**
     * Whether starting a multi-filament print with an AMS mapping has been confirmed on a real Bambu printer. Until then
     * the print command refuses such a file (BambuPrintProtocol.MULTI_MATERIAL_NOT_SUPPORTED); the mapping it would send
     * is built and unit-tested (Helix's wire format), but a wrong mapping feeds the wrong spool, so it stays off until a
     * tester with an AMS has printed with it. Flip only with that evidence recorded (docs/upstream/PROVENANCE.md).
     */
    const val AMS_PRINT_VERIFIED = false

    /** One filament a sliced bundle uses (slice_info's <filament> row): [tool] is its zero-based T number. */
    data class FileFilament(val tool: Int, val type: String, val colorHex: String?)

    sealed class TrayMatch {
        /** Zero-based file tool -> global AMS lane, for BambuPrintRequest.toolToLane. */
        data class Mapped(val toolToLane: Map<Int, Int>) : TrayMatch()
        /** Filaments no loaded AMS tray can feed (no loaded tray of that type left). */
        data class Missing(val filaments: List<FileFilament>) : TrayMatch()
    }

    /**
     * Matches each filament the file uses to a loaded AMS tray of the same material, preferring the same colour, one tray
     * per filament (the external spool is never picked for a multi-filament print). The same rule Bambu Studio's
     * automatic mapping starts from; the user can still see and change it before printing.
     */
    fun matchTrays(filaments: List<FileFilament>, trays: List<FilamentSlot>): TrayMatch {
        val free = trays.filter { it.loaded && it.tool != BambuAmsTrays.EXTERNAL_TRAY }.toMutableList()
        val mapping = LinkedHashMap<Int, Int>()
        val missing = ArrayList<FileFilament>()
        fun sameType(slot: FilamentSlot, f: FileFilament) = slot.material.orEmpty().trim().equals(f.type.trim(), ignoreCase = true)
        for (f in filaments) {
            val pick = free.firstOrNull { sameType(it, f) && f.colorHex != null && it.colorHex.equals(f.colorHex, ignoreCase = true) }
                ?: free.firstOrNull { sameType(it, f) }
            if (pick == null) missing += f else { mapping[f.tool] = pick.tool; free.remove(pick) }
        }
        return if (missing.isEmpty()) TrayMatch.Mapped(mapping) else TrayMatch.Missing(missing)
    }
}

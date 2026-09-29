package net.jamesjennison.klippercompanion

import net.jamesjennison.klippercompanion.BambuAmsTrays.BambuTray

/**
 * Bambu Lab's AMS (and AMS lite): one nozzle fed from several spools. The bundled machine.json only declares the
 * extruders, so the single-extruder Bambu printers that take an AMS get their filament slots here, the way the Elegoo
 * CANVAS packs do (ElegooProfiles.filamentSlots): one AMS, four slots. Slot n is filament n in the sliced file (T n-1).
 * The two-extruder models (H2D, H2D Pro, X2D, H2C) keep one slot per extruder.
 *
 * Follow-up, not done here: more AMS units (up to four AMS plus eight AMS HT) allow more filaments, and on a two-nozzle
 * printer each filament's nozzle is the sliced file's `filament_map` (1 left, 2 right), which has to be written to match
 * where the matching trays are (Bambu Studio writes `filament_map_mode` Manual for that; see BambuPrintProtocol.SlicePlate).
 * Until the slicing side writes that map, the slot counts stay as they are.
 */
object BambuAms {
    const val SLOTS_PER_UNIT = 4

    // BAMBU_GENERIC is the Bambu Lab A1 in SlicingModelCatalog, so the A1 (AMS lite, N2S.json use_ams_type "f1") is here.
    private val SINGLE_EXTRUDER_AMS_MODELS = setOf(
        SlicingPrinterModel.BAMBU_X1, SlicingPrinterModel.BAMBU_X1_CARBON, SlicingPrinterModel.BAMBU_X1E,
        SlicingPrinterModel.BAMBU_P1P, SlicingPrinterModel.BAMBU_P1S, SlicingPrinterModel.BAMBU_P2S,
        SlicingPrinterModel.BAMBU_GENERIC, SlicingPrinterModel.BAMBU_A1_MINI, SlicingPrinterModel.BAMBU_H2S,
    )

    /** The printer's filament slot count when it takes an AMS; null for every other model. */
    fun filamentSlots(model: SlicingPrinterModel): Int? = if (model in SINGLE_EXTRUDER_AMS_MODELS) SLOTS_PER_UNIT else null

    /**
     * Whether starting a print with an AMS mapping has been confirmed on a real Bambu printer. Until then the print
     * command refuses a multi-filament file (BambuPrintProtocol.MULTI_MATERIAL_NOT_SUPPORTED) and starts a one-filament
     * file from the external spool as it always has; the mapping it would send is built and unit-tested (Bambu Studio's
     * values), but a wrong mapping feeds the wrong spool, so it stays off until a tester with an AMS has printed with it.
     * Flip only with that evidence recorded (docs/upstream/PROVENANCE.md).
     */
    const val AMS_PRINT_VERIFIED = false

    /**
     * Bambu's `physical_extruder_map` on the two-nozzle printers (H2D, H2D Pro, H2C, X2D): [1, 0]
     * (BambuStudio resources/profiles/BBL/machine/fdm_bbl_3dp_002_common.json:266-269). The file's `filament_map` value
     * n (1 left, 2 right) feeds physical extruder `PHYSICAL_EXTRUDER_MAP[n-1]`: 1 is DEPUTY (left), 2 is MAIN (right)
     * (PartPlate.cpp:1529-1569).
     */
    val PHYSICAL_EXTRUDER_MAP = listOf(BambuAmsTrays.DEPUTY_EXTRUDER, BambuAmsTrays.MAIN_EXTRUDER)

    /**
     * One filament a sliced bundle uses (slice_info's <filament> row): [tool] is its zero-based T number. [filamentId] is
     * its `tray_info_idx` (Bambu's filament id, e.g. GFA00), [groupId] its `group_id` (the nozzle group on a two-nozzle
     * file).
     */
    data class FileFilament(val tool: Int, val type: String, val colorHex: String?, val filamentId: String? = null, val groupId: Int? = null)

    sealed class TrayMatch {
        /** Zero-based file tool -> the tray that feeds it, for BambuPrintRequest.amsMapping. */
        data class Mapped(val trays: Map<Int, BambuTray>) : TrayMatch() {
            /** Zero-based file tool -> Bambu's global tray index (255/254 for the external holders). */
            val toolToLane: Map<Int, Int> get() = trays.mapValues { it.value.trayIndex }
        }
        /** Filaments no loaded tray on their nozzle's side can feed (no loaded tray of that type left). */
        data class Missing(val filaments: List<FileFilament>) : TrayMatch()
    }

    /**
     * The printer extruder that must feed [tool]: null on a one-nozzle printer (any tray will do); on a two-nozzle printer
     * the file's `filament_maps` entry through [PHYSICAL_EXTRUDER_MAP], or -1 when the file doesn't say (no tray is on
     * that side, so the filament can't be matched rather than being fed from a guessed nozzle).
     */
    fun extruderFor(tool: Int, filamentMaps: List<Int>, dualNozzle: Boolean): Int? {
        if (!dualNozzle) return null
        return filamentMaps.getOrNull(tool)?.let { PHYSICAL_EXTRUDER_MAP.getOrNull(it - 1) } ?: -1
    }

    /** Whether [tray] can feed a filament that must go through [extruder] (null: any). */
    fun onSide(tray: BambuTray, extruder: Int?): Boolean = extruder == null || tray.extruder == extruder

    /**
     * Matches each filament the file uses to a loaded tray of the same material, preferring the same colour, the way
     * Bambu Studio's automatic mapping does (DevMapping.cpp:133-433, SelectMachine.cpp:1272-1375); the user can still see
     * and change it before printing.
     * - Type is a hard match (with Bambu's support-filament aliases, BambuAmsTrays.materialKey); an exact colour wins.
     * - On a two-nozzle printer ([dualNozzle]) a filament only takes trays on its own side: units bound to the extruder
     *   its `filament_maps` entry names, or that side's external holder (SelectMachine.cpp:1296-1341, DevMapping.cpp:197-238).
     * - An external holder is picked automatically only when its side has no AMS at all (SelectMachine.cpp:1331-1336,
     *   1364); [external] lists the file tools the user chose to feed from their side's external holder instead.
     * - Each tray feeds one filament, except that a filament may reuse an already-picked tray of the exact same type and
     *   colour (DevMapping.cpp:327-338).
     * - Between equally good trays, one whose filament id is the file's and has less (known, non-zero) filament left
     *   wins, so partly used spools go first (_prefer_tray_by_remain, DevMapping.cpp:113-131).
     */
    fun matchTrays(
        filaments: List<FileFilament>, trays: List<BambuTray>, dualNozzle: Boolean = false,
        filamentMaps: List<Int> = emptyList(), external: Set<Int> = emptySet(),
    ): TrayMatch {
        val picked = LinkedHashMap<Int, BambuTray>()
        val used = HashSet<BambuTray>()
        val missing = ArrayList<FileFilament>()
        fun sameType(tray: BambuTray, f: FileFilament) = tray.loaded && BambuAmsTrays.materialKey(tray.material) == BambuAmsTrays.materialKey(f.type)
        fun sameColour(tray: BambuTray, f: FileFilament) = f.colorHex != null && tray.colorHex.equals(f.colorHex, ignoreCase = true)
        fun best(candidates: List<BambuTray>, f: FileFilament): BambuTray? = candidates.fold(null as BambuTray?) { chosen, c ->
            if (chosen == null) c
            else if (c.trayInfoIdx != null && c.trayInfoIdx == f.filamentId && (c.remain ?: -1) > 0 && ((chosen.remain ?: -1) <= 0 || c.remain!! < chosen.remain!!)) c
            else chosen
        }
        for (f in filaments) {
            val side = extruderFor(f.tool, filamentMaps, dualNozzle)
            val onSide = trays.filter { onSide(it, side) }
            val ext = onSide.filter { it.external }
            if (f.tool in external) {
                // The user's choice: their side's holder, main first on a one-nozzle printer.
                val holder = ext.firstOrNull()
                if (holder == null) missing += f else { picked[f.tool] = holder; used += holder }
                continue
            }
            val hasAms = onSide.any { !it.external }
            val candidates = onSide.filter { (!it.external || !hasAms) && sameType(it, f) }
            val free = candidates.filter { it !in used }
            val pick = best(free.filter { sameColour(it, f) }, f)
                ?: candidates.firstOrNull { it in used && sameColour(it, f) }
                ?: best(free, f)
            if (pick == null) missing += f else { picked[f.tool] = pick; used += pick }
        }
        return if (missing.isEmpty()) TrayMatch.Mapped(picked) else TrayMatch.Missing(missing)
    }

    /**
     * Why [mapping] (file tool -> tray) can't be sent for a file with [filamentMaps], or null when it can: every tray must
     * exist and, on a two-nozzle printer, sit on its filament's side (DevMapping.cpp:17-67; AmsMappingPopup.cpp:1174-1179).
     */
    fun mappingProblem(mapping: Map<Int, BambuTray>, filamentMaps: List<Int>, dualNozzle: Boolean): String? {
        for ((tool, tray) in mapping.toSortedMap()) {
            if (!tray.exists) return "Filament ${tool + 1} is matched to ${tray.name.ifEmpty { "a tray" }}, which the printer reports empty."
            val side = extruderFor(tool, filamentMaps, dualNozzle)
            if (!onSide(tray, side)) {
                val wanted = when (side) { BambuAmsTrays.DEPUTY_EXTRUDER -> "left"; BambuAmsTrays.MAIN_EXTRUDER -> "right"; else -> "unknown" }
                return "Filament ${tool + 1} is sliced for the $wanted nozzle, but ${tray.name.ifEmpty { "its tray" }} feeds the other one. " +
                    "Match it to a tray on the $wanted side, or re-slice with that filament on the other nozzle."
            }
        }
        return null
    }
}

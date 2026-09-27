package net.jamesjennison.klippercompanion

// Which Elegoo firmware each Centauri Carbon slicer profile is written for, and the rules that keep a profile on its own
// firmware. Pure; no I/O.
//
// The two firmwares are not interchangeable (docs.opencentauri.cc, troubleshooting/printing): Elegoo's own firmware
// profiles use Elegoo's commands (M6211 swap-and-purge on both printers; the Centauri Carbon's start G-code calls M729,
// and older profiles M8213), which Klipper lacks and OpenCentauri COSMOS 26.07+ deliberately emergency-stops on (M729/M8213),
// and COSMOS's profiles call Klipper macros (PRINT_START, AFC's T<n> PURGE_LENGTH=) that Elegoo's firmware lacks. So:
//  - a COSMOS profile is only ever used with a printer reached through Moonraker whose live firmware reads as COSMOS
//    (FirmwareIdentity.kt's checkCentauriCarbonFirmwareMatch, unchanged);
//  - an Elegoo-firmware profile is only ever used with a printer connected as an Elegoo printer (SDCP or the Centauri
//    Carbon 2's MQTT, protocols COSMOS doesn't speak), never with a Moonraker printer;
//  - and independently of the profile, a Moonraker upload refuses any file carrying M729/M8213 (stockElegooCommand).

/** The firmware a Centauri Carbon slicer profile targets. */
enum class ElegooProfileFirmware { COSMOS, ELEGOO_STOCK }

object ElegooProfiles {
    /** CANVAS has four lanes; the three CANVAS packs list four filament slots (slicer_profiles/index.json "tools": 4). */
    const val CANVAS_SLOTS = 4

    private val COSMOS_MODELS = setOf(SlicingPrinterModel.ELEGOO_CENTAURI_CARBON, SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_COSMOS_CANVAS)
    private val STOCK_MODELS = setOf(SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_CANVAS, SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_2_CANVAS)
    private val CANVAS_MODELS = setOf(SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_COSMOS_CANVAS, SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_CANVAS,
        SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_2_CANVAS)

    /** Null for every profile that isn't tied to one Elegoo firmware. */
    fun firmwareFor(model: SlicingPrinterModel?): ElegooProfileFirmware? = when (model) {
        in COSMOS_MODELS -> ElegooProfileFirmware.COSMOS
        in STOCK_MODELS -> ElegooProfileFirmware.ELEGOO_STOCK
        else -> null
    }

    fun isCosmos(model: SlicingPrinterModel?): Boolean = firmwareFor(model) == ElegooProfileFirmware.COSMOS

    /**
     * Filament slots a profile offers when its machine.json doesn't say (CANVAS feeds one nozzle, so the packs declare a
     * single extruder, as ElegooSlicer's and OpenCentauri's do). Null: use the machine.json count.
     */
    fun filamentSlots(model: SlicingPrinterModel?): Int? = if (model in CANVAS_MODELS) CANVAS_SLOTS else null

    /** Moonraker-reached printer kinds: the only ones a COSMOS printer can be, and never an Elegoo-firmware printer. */
    private val MOONRAKER_KINDS = setOf(PrinterKind.GENERIC_KLIPPER, PrinterKind.SNAPMAKER_U1_PAXX, PrinterKind.SNAPMAKER_U1)

    /**
     * Why [model] can't be used for a printer connected as [kind], or null when it can. Checked when a printer is saved
     * and again before every slice.
     */
    fun connectionProblem(model: SlicingPrinterModel?, kind: PrinterKind): String? = when (firmwareFor(model)) {
        ElegooProfileFirmware.ELEGOO_STOCK -> if (kind == PrinterKind.ELEGOO) null else
            "This profile is for Elegoo's own firmware: its G-code uses Elegoo's commands (M6211, and M729 on the Centauri Carbon, which COSMOS stops the printer on) that Klipper doesn't have. " +
                "Connect the printer as an Elegoo printer, or choose the COSMOS profile for a printer running COSMOS."
        ElegooProfileFirmware.COSMOS -> if (kind in MOONRAKER_KINDS) null else
            "This profile is for OpenCentauri COSMOS, which is reached through Moonraker. For a Centauri Carbon on Elegoo's own firmware, " +
                "choose the Elegoo firmware profile."
        null -> null
    }

    private val STOCK_COMMAND = Regex("""^\s*(M729|M8213)\b""", RegexOption.IGNORE_CASE)

    /**
     * The first Elegoo stock-firmware command in a G-code file, if any (M729 or M8213). Klipper has neither; OpenCentauri
     * COSMOS 26.07+ deliberately emergency-stops on both, so such a file must never reach a Moonraker printer. Same rule
     * as the desktop's U1Protocol.stockElegooCommand.
     */
    fun stockElegooCommand(lines: Sequence<String>): String? = lines.firstNotNullOfOrNull { STOCK_COMMAND.find(it)?.groupValues?.get(1)?.uppercase() }

    private val TOOL_SELECT = Regex("""^\s*T(\d{1,2})\s*(?:;.*)?$""")

    /**
     * The slot map to start a sliced file with on an Elegoo printer with [printerSlots] CANVAS slots (PrinterAction.StartJob's
     * toolheadMap: entry i is the slot feeding the file's T<i>, -1 unmapped). Each filament the file selects with a bare
     * T<n> line goes to slot n, the same numbering the project's "Tool N" uses; filaments the file never selects are left
     * unmapped, so an empty tray the print doesn't need can't block it. A single-filament file, or a printer without CANVAS
     * slots, gets an empty map, which the printer takes as "print from what is loaded".
     */
    fun toolheadMap(lines: Sequence<String>, printerSlots: Int): List<Int> {
        if (printerSlots <= 1) return emptyList()
        val used = sortedSetOf<Int>()
        lines.forEach { line -> TOOL_SELECT.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it < 32) used += it } }
        if (used.size <= 1 && (used.isEmpty() || used.first() == 0)) return emptyList()
        return (0..used.last()).map { if (it in used) it else -1 }
    }

    fun stockElegooRefusal(command: String): String =
        "This file was sliced for Elegoo's stock firmware (it uses $command), which Klipper printers don't have; COSMOS stops the printer on it. " +
            "Slice again with this printer's own profile."
}

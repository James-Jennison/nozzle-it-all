package net.jamesjennison.klippercompanion

/**
 * Which bundled slicing profiles go with the two Snapmaker connection kinds (P-0037). A Snapmaker 2.0 A-series printer
 * (PrinterKind.SNAPMAKER_A_SERIES) talks the touchscreen's HTTP API; a J1 or Artisan (PrinterKind.SNAPMAKER_SACP) talks
 * SACP. Luban splits them the same way: HTTP for the A-series and SACP over TCP for the J1 and Artisan
 * (luban/ProtocolDetector.ts:103-116 tries SACP over TCP and falls back to HTTP; luban/channels/SacpTcpChannel.ts:106-117
 * reports a J1 or Artisan).
 * The catalogue has no A150 profile, so an A150 connects with no slicing profile.
 */
object SnapmakerModels {
    val A_SERIES: Set<SlicingPrinterModel> = setOf(
        SlicingPrinterModel.SNAPMAKER_A250, SlicingPrinterModel.SNAPMAKER_A250_BKIT, SlicingPrinterModel.SNAPMAKER_A250_DUAL,
        SlicingPrinterModel.SNAPMAKER_A250_DUAL_BKIT, SlicingPrinterModel.SNAPMAKER_A250_DUAL_QS_B_KIT, SlicingPrinterModel.SNAPMAKER_A250_DUAL_QSKIT,
        SlicingPrinterModel.SNAPMAKER_A250_QS_B_KIT, SlicingPrinterModel.SNAPMAKER_A250_QSKIT,
        SlicingPrinterModel.SNAPMAKER_A350, SlicingPrinterModel.SNAPMAKER_A350_BKIT, SlicingPrinterModel.SNAPMAKER_A350_DUAL,
        SlicingPrinterModel.SNAPMAKER_A350_DUAL_BKIT, SlicingPrinterModel.SNAPMAKER_A350_DUAL_QS_B_KIT, SlicingPrinterModel.SNAPMAKER_A350_DUAL_QSKIT,
        SlicingPrinterModel.SNAPMAKER_A350_QS_B_KIT, SlicingPrinterModel.SNAPMAKER_A350_QSKIT,
    )
    val SACP: Set<SlicingPrinterModel> = setOf(SlicingPrinterModel.SNAPMAKER_J1, SlicingPrinterModel.SNAPMAKER_ARTISAN)

    /** The profiles a printer connected as [kind] may use, or null when [kind] isn't one of these two. */
    fun modelsFor(kind: PrinterKind): Set<SlicingPrinterModel>? = when (kind) {
        PrinterKind.SNAPMAKER_A_SERIES -> A_SERIES
        PrinterKind.SNAPMAKER_SACP -> SACP
        else -> null
    }

    /**
     * Why [model] can't be used for a printer connected as [kind], or null when it can. Like ElegooProfiles.connectionProblem,
     * it is checked when a printer is saved and again before every slice. No profile at all is fine: that printer is
     * never sliced for on the device.
     */
    fun connectionProblem(model: SlicingPrinterModel?, kind: PrinterKind): String? {
        val allowed = modelsFor(kind) ?: return null
        if (model == null || model in allowed) return null
        return when (kind) {
            PrinterKind.SNAPMAKER_SACP -> if (model in A_SERIES)
                "This is a Snapmaker 2.0 profile. Connect a Snapmaker 2.0 as \"Snapmaker 2.0 (A250 / A350)\", or choose the J1 or Artisan profile."
            else "A Snapmaker J1 or Artisan connection needs the Snapmaker J1 or Snapmaker Artisan slicing profile."
            else -> if (model in SACP)
                "This is a Snapmaker J1 / Artisan profile. Connect a J1 or Artisan as \"Snapmaker J1 / Artisan\", or choose a Snapmaker A250 or A350 profile."
            else "A Snapmaker 2.0 connection needs a Snapmaker A250 or A350 slicing profile (any kit variant)."
        }
    }
}

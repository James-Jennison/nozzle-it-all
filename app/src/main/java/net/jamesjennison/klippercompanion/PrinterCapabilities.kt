package net.jamesjennison.klippercompanion

// Phase 2 (Consumer Slicer Plan §10): replaces the `PrinterKind`-shaped `when(kind)`/
// `bambu`/`prusa`/`nonKlipper`-style conditionals scattered across the UI (MainActivity.kt,
// FilePanels.kt, SliceAndPrintPanel.kt, PrinterTiles.kt) with one real capability object, resolved
// once per printer and read everywhere a UI path needs to know what a printer can actually do.
// Deliberately does NOT fold in `SlicingPrinterModel`/bed shape/the bundled slicer profile pack -
// those are a real, separate, already-async concern (`SlicingCoordinator`'s own
// `resolveProfilePaths`, `bedShapeFor`) about *which slicer profile*, not *which transport/vendor
// add-ons* - conflating them here would just recreate the "2 conflicting enums" smell this phase
// is meant to remove. `PrinterKind` itself is kept (it's still the real, correct signal for
// "which transport"/"which vendor add-ons", and `printerServiceFor`/`normalizedAddress` in
// PrinterModel.kt already select the transport correctly from it) - `PrinterCapabilities` is a
// derived, UI-facing view over it, not a replacement data model or a second source of truth.
enum class PrinterTransport { MOONRAKER, BAMBU_MQTT, PRUSA_LINK }

data class PrinterCapabilities(
    val transport: PrinterTransport,
    val supportsPauseResumeCancel: Boolean,
    val supportsCamera: Boolean,
    // The real, current shape of "every Klipper-only panel" - heaters/fans/LEDs/tools/speed-flow,
    // macros/console/config/bed-mesh-read/firmware-identity, print history, timelapse playback,
    // Spoolman, Panda Breath, ACE. Kept as one flag, not fifteen, because nothing in this
    // codebase differentiates within that group today - every Moonraker-backed integration here
    // implements the same reader interfaces, and no printer reports "no heaters" independently of
    // "no macros". Splitting it further would invent precision the app doesn't actually have.
    val supportsKlipperExtras: Boolean,
    // Bambu's own .gcode.3mf bundle upload/print flow (BambuPrintPanel) - a vendor-specific
    // print-request shape, not a general slicing capability.
    val supportsNativePrintFileFlow: Boolean,
    // Phase 6 (Consumer Slicer Plan §16): Bambu Lab now produces its own real .gcode.3mf bundle
    // on-device (engine::slice_bambu_bundle, store_bbs_3mf - see slic3r_engine.cpp/.hpp and
    // BambuBundleDeviceTest) and uploads+prints it through the same real FTPS+MQTT flow
    // BambuPrintPanel's share-intent path already uses (BambuPrinterService.startPrint) - see
    // SlicingCoordinator.slice()'s own branch on PrinterKind.BAMBU_LAB. Prusa Link still has no
    // generic file-upload endpoint this app implements (PrusaLinkPrinterService only ever sends
    // print-control commands) - slicing would succeed but the upload step would silently hit
    // Moonraker-shaped endpoints a real PrusaLink printer doesn't have, so it's still honestly
    // blocked rather than attempted and left to fail against real hardware.
    val acceptsOnDeviceSlicedGcode: Boolean,
    val hasBespok3d: Boolean,
    val hasMultiAce: Boolean,
    // Built against each vendor's own published protocol/reference engineering, but the owner
    // has no matching physical unit to test against (PrinterTiles.kt's own real disclosure).
    val verifiedOnRealHardware: Boolean,
    // Real capabilities named in the plan's own §10 spec that no transport in this codebase
    // implements yet - always false today, not omitted, so a future real implementation only has
    // to flip one value here rather than add a new field and re-audit every call site.
    val hasFilamentSensor: Boolean = false,
    val supportsJog: Boolean = false,
    // Bed mesh is already real, but read-only (BedMeshPanel visualizes it) - *triggering* a new
    // level is a different, unbuilt capability.
    val supportsBedLevelingTrigger: Boolean = false,
    // Timelapse *playback/download* is already real (TimelapsePanel) - *triggering* a render is
    // a different, unbuilt capability (RENDER_TIMELAPSE is not implemented anywhere).
    val supportsTimelapseTrigger: Boolean = false,
)

fun capabilitiesFor(kind: PrinterKind): PrinterCapabilities = when (kind) {
    PrinterKind.GENERIC_KLIPPER -> PrinterCapabilities(
        transport = PrinterTransport.MOONRAKER, supportsPauseResumeCancel = true, supportsCamera = true,
        supportsKlipperExtras = true, supportsNativePrintFileFlow = false, acceptsOnDeviceSlicedGcode = true,
        hasBespok3d = false, hasMultiAce = false, verifiedOnRealHardware = true,
    )
    PrinterKind.SNAPMAKER_U1_PAXX -> PrinterCapabilities(
        transport = PrinterTransport.MOONRAKER, supportsPauseResumeCancel = true, supportsCamera = true,
        supportsKlipperExtras = true, supportsNativePrintFileFlow = false, acceptsOnDeviceSlicedGcode = true,
        hasBespok3d = true, hasMultiAce = true, verifiedOnRealHardware = true,
    )
    PrinterKind.BAMBU_LAB -> PrinterCapabilities(
        transport = PrinterTransport.BAMBU_MQTT, supportsPauseResumeCancel = false, supportsCamera = true,
        supportsKlipperExtras = false, supportsNativePrintFileFlow = true, acceptsOnDeviceSlicedGcode = true,
        hasBespok3d = false, hasMultiAce = false, verifiedOnRealHardware = false,
    )
    PrinterKind.PRUSA_LINK -> PrinterCapabilities(
        transport = PrinterTransport.PRUSA_LINK, supportsPauseResumeCancel = true, supportsCamera = false,
        supportsKlipperExtras = false, supportsNativePrintFileFlow = false, acceptsOnDeviceSlicedGcode = false,
        hasBespok3d = false, hasMultiAce = false, verifiedOnRealHardware = false,
    )
}

fun ScreenState.capabilitiesFor(address: String): PrinterCapabilities = capabilitiesFor(kindFor(address))

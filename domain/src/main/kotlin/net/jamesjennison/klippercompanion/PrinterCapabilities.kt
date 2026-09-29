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
enum class PrinterTransport { MOONRAKER, BAMBU_MQTT, PRUSA_LINK, OCTOPRINT, ELEGOO, CREALITY, FLASHFORGE, DUET, ULTIMAKER }

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
    // Phase 6 (Consumer Slicer Plan §16): Bambu Lab produces its own real .gcode.3mf bundle
    // on-device (engine::slice_bambu_bundle, store_bbs_3mf - see slic3r_engine.cpp/.hpp and
    // BambuBundleDeviceTest) and uploads+prints it through the same real FTPS+MQTT flow
    // BambuPrintPanel's share-intent path already uses (BambuPrinterService.startPrint) - see
    // SlicingCoordinator.slice()'s own branch on PrinterKind.BAMBU_LAB. WO-23: Prusa Link now
    // has a real generic upload endpoint too (PrusaLinkPrinterService.uploadAndPrint - a real
    // PUT with Print-After-Upload against the printer's actual writable storage, resolved via
    // GET /api/v1/storage rather than a hardcoded "local") - this app's plain .gcode output needs
    // no bundle for Prusa, unlike Bambu.
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
    // Phase 7 (Consumer Slicer Plan §16): real relative-move G-code (G91/G1/G90) plus G28 homing,
    // sent via printer/gcode/script (JogPanel.kt) - a Moonraker/Klipper-only capability. Neither
    // Bambu's MQTT protocol nor Prusa Link's documented v1 API (prusa3d/Prusa-Link-Web's own
    // openapi.yaml, read directly - no jog/move endpoint exists there) offers an equivalent.
    val supportsJog: Boolean = false,
    // Bed mesh is already real, but read-only (BedMeshPanel visualizes it) - *triggering* a new
    // level (BED_MESH_CALIBRATE, a real Klipper gcode command registered only when `[bed_mesh]`
    // is configured - klippy/extras/bed_mesh.py, confirmed by reading it directly) is a Klipper-
    // only capability; BedMeshPanel itself still further gates the real "Calibrate now" button on
    // that printer's own live printer/objects/list actually registering bed_mesh, since not every
    // Klipper printer has it configured even when the transport supports it in principle.
    val supportsBedLevelingTrigger: Boolean = false,
    // Timelapse *playback/download* is already real (TimelapsePanel) - *triggering* a render
    // (a real POST /machine/timelapse/render, moonraker-timelapse's own documented endpoint,
    // confirmed by reading mainsail-crew/moonraker-timelapse's component source directly) is a
    // Moonraker-only capability, further gated on that component actually being installed (same
    // graceful-degradation TimelapsePanel already has for playback).
    val supportsTimelapseTrigger: Boolean = false,
    // Phase 7: LOAD_FILAMENT/UNLOAD_FILAMENT are real, common Klipper macro names (not a built-in
    // gcode command - Klipper itself ships neither), so this is a transport-level "this kind of
    // macro-driven control is possible here" signal, not a guarantee any specific printer has
    // defined them. FilamentLoadUnloadControls.kt further gates the actual buttons on that
    // printer's own live macro catalog (state.catalog.macros) genuinely containing one of those
    // names, case-insensitively - same "no dead buttons" discipline this app's existing
    // "Favorite macros" section already uses, not a static assumption.
    val supportsFilamentLoadUnload: Boolean = false,
    // False for a connection whose upstream print host reads no printer state at all (Duet: OrcaSlicer's Duet.cpp only
    // checks the board answers), so its snapshot is "reachable, state unknown". See sendAllowedStates.
    val readsPrinterState: Boolean = true,
)

fun capabilitiesFor(kind: PrinterKind): PrinterCapabilities = when (kind) {
    PrinterKind.GENERIC_KLIPPER -> PrinterCapabilities(
        transport = PrinterTransport.MOONRAKER, supportsPauseResumeCancel = true, supportsCamera = true,
        supportsKlipperExtras = true, supportsNativePrintFileFlow = false, acceptsOnDeviceSlicedGcode = true,
        hasBespok3d = false, hasMultiAce = false, verifiedOnRealHardware = true,
        supportsJog = true, supportsBedLevelingTrigger = true, supportsTimelapseTrigger = true, supportsFilamentLoadUnload = true,
    )
    // PAXX/extended firmware: multiACE, but NO Bespok3d - Bespok3d targets stock firmware and the
    // enrollment preflight refuses extended firmware (verified against the owner's PAXX U1).
    PrinterKind.SNAPMAKER_U1_PAXX -> PrinterCapabilities(
        transport = PrinterTransport.MOONRAKER, supportsPauseResumeCancel = true, supportsCamera = true,
        supportsKlipperExtras = true, supportsNativePrintFileFlow = false, acceptsOnDeviceSlicedGcode = true,
        hasBespok3d = false, hasMultiAce = true, verifiedOnRealHardware = true,
        supportsJog = true, supportsBedLevelingTrigger = true, supportsTimelapseTrigger = true, supportsFilamentLoadUnload = true,
    )
    // Stock firmware: Bespok3d, no multiACE (a PAXX add-on). Same Moonraker feature set, but it has not been
    // run on a stock-firmware U1 (the owner's is PAXX), so it is honestly reported as unverified.
    PrinterKind.SNAPMAKER_U1 -> PrinterCapabilities(
        transport = PrinterTransport.MOONRAKER, supportsPauseResumeCancel = true, supportsCamera = true,
        supportsKlipperExtras = true, supportsNativePrintFileFlow = false, acceptsOnDeviceSlicedGcode = true,
        hasBespok3d = true, hasMultiAce = false, verifiedOnRealHardware = false,
        supportsJog = true, supportsBedLevelingTrigger = true, supportsTimelapseTrigger = true, supportsFilamentLoadUnload = true,
    )
    PrinterKind.BAMBU_LAB -> PrinterCapabilities(
        transport = PrinterTransport.BAMBU_MQTT, supportsPauseResumeCancel = true, supportsCamera = true,
        supportsKlipperExtras = false, supportsNativePrintFileFlow = true, acceptsOnDeviceSlicedGcode = true,
        hasBespok3d = false, hasMultiAce = false, verifiedOnRealHardware = false,
    )
    PrinterKind.OCTOPRINT -> PrinterCapabilities(
        transport = PrinterTransport.OCTOPRINT, supportsPauseResumeCancel = true, supportsCamera = false,
        supportsKlipperExtras = false, supportsNativePrintFileFlow = false, acceptsOnDeviceSlicedGcode = true,
        hasBespok3d = false, hasMultiAce = false, verifiedOnRealHardware = false,
    )
    // Elegoo Centauri Carbon (stock firmware, SDCP) / Centauri Carbon 2 (MQTT) through :adapter-elegoo: status, CANVAS
    // slots, send-and-start, cancel; pause/resume on the Centauri Carbon only (ElegooPrinterService refuses them on a CC2,
    // whose LAN protocol has no resume). No camera yet. Built against fakes only.
    PrinterKind.ELEGOO -> PrinterCapabilities(
        transport = PrinterTransport.ELEGOO, supportsPauseResumeCancel = true, supportsCamera = false,
        supportsKlipperExtras = false, supportsNativePrintFileFlow = false, acceptsOnDeviceSlicedGcode = true,
        hasBespok3d = false, hasMultiAce = false, verifiedOnRealHardware = false,
    )
    PrinterKind.PRUSA_LINK -> PrinterCapabilities(
        transport = PrinterTransport.PRUSA_LINK, supportsPauseResumeCancel = true, supportsCamera = false,
        supportsKlipperExtras = false, supportsNativePrintFileFlow = false, acceptsOnDeviceSlicedGcode = true,
        hasBespok3d = false, hasMultiAce = false, verifiedOnRealHardware = false,
    )
    // Creality K1 / K2 / Hi (CrealityPrinterService) and Flashforge AD5X / 5M (FlashforgePrinterService): live status, the
    // CFS / IFS slots and uploading a sliced file. Starting it is refused until CrealityCfs.START_VERIFIED /
    // FlashforgeIfs.START_VERIFIED (nobody has checked the start on a printer yet), and pause / resume / cancel aren't built.
    // Built from OrcaSlicer's and CrealityPrint's sources only.
    PrinterKind.CREALITY -> PrinterCapabilities(
        transport = PrinterTransport.CREALITY, supportsPauseResumeCancel = false, supportsCamera = false,
        supportsKlipperExtras = false, supportsNativePrintFileFlow = false, acceptsOnDeviceSlicedGcode = true,
        hasBespok3d = false, hasMultiAce = false, verifiedOnRealHardware = false,
    )
    PrinterKind.FLASHFORGE -> PrinterCapabilities(
        transport = PrinterTransport.FLASHFORGE, supportsPauseResumeCancel = false, supportsCamera = false,
        supportsKlipperExtras = false, supportsNativePrintFileFlow = false, acceptsOnDeviceSlicedGcode = true,
        hasBespok3d = false, hasMultiAce = false, verifiedOnRealHardware = false,
    )
    // Duet / RepRapFirmware (DuetPrinterService): a reachability check (upstream OrcaSlicer's Duet host reads no printer
    // state, so the state is unknown) and uploading a sliced file. Starting it is refused until DuetRrf.START_VERIFIED;
    // pause / resume / cancel aren't built. Built from OrcaSlicer's source only.
    PrinterKind.DUET -> PrinterCapabilities(
        transport = PrinterTransport.DUET, supportsPauseResumeCancel = false, supportsCamera = false,
        supportsKlipperExtras = false, supportsNativePrintFileFlow = false, acceptsOnDeviceSlicedGcode = true,
        hasBespok3d = false, hasMultiAce = false, verifiedOnRealHardware = false, readsPrinterState = false,
    )
    // Networked UltiMaker (UltiMakerPrinterService): live state and job progress from the cluster API (Cura's reads), and
    // pairing. An UltiMaker prints every job it is sent, so sending is refused outright until UltiMakerApi.START_VERIFIED;
    // pause / resume / abort aren't built. Built from OrcaSlicer's and Cura's sources only.
    PrinterKind.ULTIMAKER -> PrinterCapabilities(
        transport = PrinterTransport.ULTIMAKER, supportsPauseResumeCancel = false, supportsCamera = false,
        supportsKlipperExtras = false, supportsNativePrintFileFlow = false, acceptsOnDeviceSlicedGcode = true,
        hasBespok3d = false, hasMultiAce = false, verifiedOnRealHardware = false,
    )
}

/**
 * Whether Nozzle It All may start a print on this kind of printer. False while a kind's start is gated off until it has
 * been checked on a real printer (CrealityCfs.START_VERIFIED, FlashforgeIfs.START_VERIFIED, DuetRrf.START_VERIFIED, UltiMakerApi.START_VERIFIED): sending a
 * sliced file to one then uploads it (an UltiMaker: sends nothing), and the person starts it on the printer's screen.
 */
fun startVerifiedFor(kind: PrinterKind): Boolean = when (kind) {
    PrinterKind.CREALITY -> CrealityCfs.START_VERIFIED
    PrinterKind.FLASHFORGE -> FlashforgeIfs.START_VERIFIED
    PrinterKind.DUET -> DuetRrf.START_VERIFIED
    PrinterKind.ULTIMAKER -> UltiMakerApi.START_VERIFIED
    else -> true
}

/**
 * The printer states "send to printer" may go out in. A kind that reads no printer state (readsPrinterState false) reports
 * "unknown"; while its start is gated off (startVerifiedFor false) a send only uploads the file, never starts it, so
 * "unknown" is allowed then and only then. Once such a kind's start is verified, a send needs a real idle state again.
 */
fun sendAllowedStates(kind: PrinterKind): Set<String> {
    val idle = setOf("standby", "complete", "cancelled", "error")
    return if (!capabilitiesFor(kind).readsPrinterState && !startVerifiedFor(kind)) idle + "unknown" else idle
}

package com.nozzleitall.testgrid

import net.jamesjennison.klippercompanion.ElegooProfiles
import net.jamesjennison.klippercompanion.FirmwareIdentity
import net.jamesjennison.klippercompanion.PrinterKind
import net.jamesjennison.klippercompanion.PrinterTransport
import net.jamesjennison.klippercompanion.capabilitiesFor
import net.jamesjennison.klippercompanion.SlicingPrinterModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Time for simulated runs: [sleep] advances the clock instead of waiting, so a simulated four-hour print finishes at
 * once. Share one instance between the Environment and the SimulatedPrinter so their readings agree.
 */
class VirtualClock(@Volatile private var t: Long = System.currentTimeMillis()) {
    @Synchronized fun now(): Long = t
    @Synchronized fun sleep(ms: Long) { t += ms.coerceAtLeast(1) }
}

/**
 * An in-process printer for exercising the runner, Test Mode and the evidence pipeline without hardware. Evidence it
 * produces is marked simulated and never grades a printer. Its identity strings are the ones real printers reported
 * (see FirmwareIdentity.kt); its address, hostname and key are deliberately realistic so redaction is exercised.
 *
 * [faults]: "lost_ack:<step kind>" performs the action but reports an unknown outcome (a reply lost after the printer
 * "job_cleared": a finished print's live state is cleared (a restart) but the job history keeps it; "job_forgotten": both are gone.
 * acted); "lost_ack_noeffect:<step kind>" reports an unknown outcome without acting (the request never arrived);
 * "reject:<step kind>" refuses it; "guard_disabled" makes uploadPreflight accept anything (a broken guard).
 */
class SimulatedPrinter(val preset: Preset, private val clock: () -> Long = System::currentTimeMillis, val faults: MutableSet<String> = mutableSetOf()) : TestTarget {
    enum class Preset(val printerKind: PrinterKind, val manufacturer: String, val model: String, val app: String, val version: String,
                      val paxx: Boolean, val afc: Boolean, val slicingModel: SlicingPrinterModel, val toolSlots: Int = 1, val legacyFlashforge: Boolean = false) {
        PAXX_U1(PrinterKind.SNAPMAKER_U1_PAXX, "Snapmaker", "U1", "", "1.6.0.267_20260815150420", true, false, SlicingPrinterModel.SNAPMAKER_U1),
        STOCK_U1(PrinterKind.SNAPMAKER_U1, "Snapmaker", "U1", "", "1.6.0.267_20260815150420", false, false, SlicingPrinterModel.SNAPMAKER_U1),
        COSMOS_CC(PrinterKind.GENERIC_KLIPPER, "Elegoo", "Centauri Carbon", "OpenCentauri Cosmos", "Release - 26.08.0", false, false, SlicingPrinterModel.ELEGOO_CENTAURI_CARBON),
        COSMOS_CC_CANVAS(PrinterKind.GENERIC_KLIPPER, "Elegoo", "Centauri Carbon", "OpenCentauri Cosmos", "Release - 26.08.0", false, true, SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_COSMOS_CANVAS),
        COSMOS_CC_LEGACY(PrinterKind.GENERIC_KLIPPER, "Elegoo", "Centauri Carbon", "OpenCentauri Cosmos", "Release - 26.06.2", false, false, SlicingPrinterModel.ELEGOO_CENTAURI_CARBON),
        GENERIC_KLIPPER(PrinterKind.GENERIC_KLIPPER, "Generic", "Klipper printer", "", "v0.12.0-437-g5b3c6c5b", false, false, SlicingPrinterModel.GENERIC_KLIPPER),
        // Printers that take a file only together with a print start (send_and_start); no heaters, homing or moves via Nozzle.
        BAMBU_P1S(PrinterKind.BAMBU_LAB, "Bambu Lab", "P1S", "", "", false, false, SlicingPrinterModel.BAMBU_P1S),
        PRUSA_MK4S(PrinterKind.PRUSA_LINK, "Prusa", "MK4S", "", "", false, false, SlicingPrinterModel.PRUSA_MK4S),
        OCTOPRINT(PrinterKind.OCTOPRINT, "Generic", "OctoPrint printer", "", "", false, false, SlicingPrinterModel.GENERIC_KLIPPER),
        ELEGOO_CC_STOCK(PrinterKind.ELEGOO, "Elegoo", "Centauri Carbon", "", "", false, true, SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_CANVAS, 4),
        PRUSA_XL_5T(PrinterKind.PRUSA_LINK, "Prusa", "XL 5T", "", "", false, false, SlicingPrinterModel.PRUSA_XL_5T, 5),
        // Start is gated off for these kinds in the app (startVerifiedFor), so their suites' print tests are blocked.
        CREALITY_K2(PrinterKind.CREALITY, "Creality", "K2", "", "", false, false, SlicingPrinterModel.CREALITY_K2, 4),
        FLASHFORGE_AD5X(PrinterKind.FLASHFORGE, "Flashforge", "AD5X", "", "", false, false, SlicingPrinterModel.FLASHFORGE_AD5X, 4),
        // Ported from upstream's print hosts; start gated off. Duet and Repetier-Server don't read printer state.
        DUET(PrinterKind.DUET, "Generic", "RepRapFirmware printer", "", "", false, false, SlicingPrinterModel.GENERIC_KLIPPER),
        ULTIMAKER_S5(PrinterKind.ULTIMAKER, "UltiMaker", "S5", "", "", false, false, SlicingPrinterModel.ULTIMAKER_S5, 2),
        REPETIER(PrinterKind.REPETIER, "Generic", "Repetier-Server printer", "", "", false, false, SlicingPrinterModel.GENERIC_KLIPPER),
        FLASHFORGE_ADVENTURER_4(PrinterKind.FLASHFORGE, "Flashforge", "Adventurer 4", "", "", false, false, SlicingPrinterModel.FLASHFORGE_ADVENTURER_4_SERIES, legacyFlashforge = true),
        ANYCUBIC_KOBRA_3(PrinterKind.ANYCUBIC_LAN, "Anycubic", "Kobra 3", "", "", false, false, SlicingPrinterModel.ANYCUBIC_KOBRA_3, 4),
        // Snapmaker 2.0 on its touchscreen API (reads state) and the J1 over SACP (doesn't yet); both dual-nozzle profiles, start gated off.
        SNAPMAKER_A350_DUAL(PrinterKind.SNAPMAKER_A_SERIES, "Snapmaker", "A350 Dual", "", "", false, false, SlicingPrinterModel.SNAPMAKER_A350_DUAL, 2),
        SNAPMAKER_J1(PrinterKind.SNAPMAKER_SACP, "Snapmaker", "J1", "", "", false, false, SlicingPrinterModel.SNAPMAKER_J1, 2),
        // A Marlin printer on a USB cable (reads temperatures and SD progress; start, upload and controls gated off), and a
        // Prusa MK3.5 with an MMU3 on one, for the multi-material slicing tests.
        USB_ENDER_3_V2(PrinterKind.USB_SERIAL, "Creality", "Ender-3 V2", "", "", false, false, SlicingPrinterModel.CREALITY_ENDER_3_V2),
        USB_PRUSA_MK3_5_MMU3(PrinterKind.USB_SERIAL, "Prusa", "MK3.5 MMU3", "", "", false, false, SlicingPrinterModel.PRUSA_MK3_5_MMU3, 5);

        companion object { fun parse(s: String) = entries.firstOrNull { it.name.equals(s.replace('-', '_'), ignoreCase = true) } }
    }

    private val address = "http://192.168.50.23:7125"
    private val hostname = "workshop-printer.local"
    private val apiKey = "sim-4f9c2e71d8a3b6f05e1a"

    override val description = TargetDescription(TargetKind.SIMULATED, preset.manufacturer, preset.model, preset.printerKind, "simulated", "simulated-moonraker",
        "Simulated ${preset.model} ($hostname)", address, preset.slicingModel, toolSlots = preset.toolSlots)
        .let { if (preset.legacyFlashforge) it.copy(protocol = FirmwareFamilies.LEGACY_FLASHFORGE_PROTOCOL) else it }

    private var state = "standby"
    private var nozzle = 24.0; private var nozzleTarget = 0.0
    private var bed = 23.0; private var bedTarget = 0.0
    private var progress = 0f
    private var loaded = ""
    private val jobs = mutableMapOf<String, String>()
    private var uploads = 0
    private var homedAxes = ""
    private val position = doubleArrayOf(0.0, 0.0, 0.0)
    private val files = sortedSetOf("benchy.gcode")
    /** Like the real connections for these printers: a file goes over only with a print start, and there is no file list. */
    private val sendOnly = capabilitiesFor(preset.printerKind).transport != PrinterTransport.MOONRAKER

    override fun identity() = LiveIdentity(FirmwareIdentity(preset.app, preset.version), hostname, preset.paxx, preset.afc)

    @Synchronized override fun status(): StatusReading {
        if (state == "printing") { progress = (progress + 0.1f).coerceAtMost(1f); if (progress >= 1f) {
            state = "complete"; nozzleTarget = 0.0; bedTarget = 0.0
            if ("job_forgotten" !in faults) jobs[loaded] = "completed"
            // The printer restarts while the app can't reach it: the live job state is cleared, only its history remains.
            if ("job_cleared" in faults || "job_forgotten" in faults) { state = "standby"; loaded = "" }
        } }
        nozzle = if (nozzleTarget > 0) nozzleTarget else 24.0
        bed = if (bedTarget > 0) bedTarget else 23.0
        return StatusReading(state, true, nozzle, nozzleTarget, bed, bedTarget, progress, loaded, clock(), homedAxes, position.toList())
    }

    override fun declaredCapabilities() = CapabilityNames.declared(preset.printerKind, FirmwareFamilies.classify(description, identity()).hardware, preset.legacyFlashforge)
    override fun cameras() = listOf(CameraInfo("Toolhead", "$address/webcam/webrtc?token=$apiKey"))
    override fun cameraSnapshot(camera: CameraInfo): ByteArray = ByteArray(2048).also { b ->
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()).copyInto(b); b[2046] = 0xFF.toByte(); b[2047] = 0xD9.toByte()
    }
    override fun files(): List<String> = synchronized(this) { if (sendOnly) throw UnsupportedByTarget("This printer connection lists no files."); files.toList() }
    override fun materialSlots(): List<String> = when {
        preset.afc -> listOf("lane1: PLA white", "lane2: PLA black", "lane3: PETG orange", "lane4: empty")
        preset.printerKind == PrinterKind.BAMBU_LAB -> listOf("AMS 1 slot 1: PLA white", "AMS 1 slot 2: PLA black")
        preset.printerKind == PrinterKind.CREALITY || preset.printerKind == PrinterKind.FLASHFORGE || preset.printerKind == PrinterKind.ANYCUBIC_LAN -> listOf("slot 1: PLA white", "slot 2: PLA black", "slot 3: empty", "slot 4: empty")
        preset.printerKind == PrinterKind.SNAPMAKER_U1_PAXX || preset.printerKind == PrinterKind.SNAPMAKER_U1 -> listOf("T0: PLA", "T1: PLA", "T2: PLA", "T3: PLA")
        else -> throw UnsupportedByTarget("This printer reports no material slots.")
    }

    override fun uploadPreflight(file: File): String? =
        if ("guard_disabled" in faults) null else file.bufferedReader().useLines { ElegooProfiles.stockElegooCommand(it) }?.let { ElegooProfiles.stockElegooRefusal(it) }

    @Synchronized override fun sendAndStart(file: File, requestedName: String): TransferOutcome {
        if (!sendOnly) throw UnsupportedByTarget("This printer connection takes files through upload and start steps instead.")
        if (state !in StatusReading.IDLE_STATES) return TransferOutcome.Refused("The printer is $state; start a print when it is ready.")
        if ("reject:send_and_start" in faults) return TransferOutcome.Refused("The printer rejected the print command.", sent = true)
        files += requestedName; state = "printing"; progress = 0f; loaded = requestedName
        if ("lost_ack:send_and_start" in faults) return TransferOutcome.Unknown("The printer did not acknowledge the print command.")
        if ("lost_ack_noeffect:send_and_start" in faults) { state = "standby"; loaded = ""; return TransferOutcome.Unknown("The printer did not acknowledge the print command.") }
        return TransferOutcome.Verified(requestedName, Canon.sha256(file))
    }

    @Synchronized override fun upload(file: File, requestedName: String): TransferOutcome {
        if (sendOnly) throw UnsupportedByTarget("This printer takes a file only together with a print start.")
        uploadPreflight(file)?.let { return TransferOutcome.Refused(it) }
        if (state !in StatusReading.IDLE_STATES) return TransferOutcome.Refused("File changes require an idle, ready printer.")
        if ("reject:upload" in faults) return TransferOutcome.Refused("HTTP 400 from $address/server/files/upload", sent = true)
        val path = requestedName.removeSuffix(".gcode") + "-sim${++uploads}.gcode"
        files += path
        if ("lost_ack:upload" in faults) return TransferOutcome.Unknown("Outcome requires inspection; no retry sent. Read timed out waiting for $address/server/files/upload (X-Api-Key: $apiKey)")
        return TransferOutcome.Verified(path, Canon.sha256(file))
    }

    @Synchronized override fun delete(remotePath: String): TransferOutcome {
        if (remotePath !in files) return TransferOutcome.Refused("Source no longer exists.")
        // Like the app's own file path: a file the printer has loaded (a finished print keeps it) is never deleted.
        if (remotePath == loaded) return TransferOutcome.Refused("$remotePath is loaded on the printer; not deleted.", sent = false)
        files -= remotePath
        if ("lost_ack:delete_uploaded" in faults) return TransferOutcome.Unknown("Connection reset by $hostname while deleting")
        return TransferOutcome.Verified(remotePath, "")
    }

    @Synchronized override fun jobResult(remotePath: String): String? = jobs[remotePath]

    @Synchronized override fun perform(action: ControlAction): CommandOutcome {
        val kind = when (action) {
            is ControlAction.SetTemperature -> "set_temperature"; ControlAction.Home -> "home"; is ControlAction.Jog -> "jog"
            is ControlAction.StartPrint -> "start_print"; ControlAction.Pause -> "pause"; ControlAction.Resume -> "resume"; ControlAction.Cancel -> "cancel"
        }
        if (sendOnly && action !is ControlAction.Pause && action !is ControlAction.Resume && action !is ControlAction.Cancel)
            return CommandOutcome.Rejected("Not available for this printer connection through Nozzle.", sent = false)
        if ("reject:$kind" in faults) return CommandOutcome.Rejected("Printer answered HTTP 400 for $kind at $address", sent = true)
        if ("lost_ack_noeffect:$kind" in faults) return CommandOutcome.Unknown("No reply from $address/printer/gcode/script within 10 s (Authorization: Bearer $apiKey)")
        when (action) {
            is ControlAction.SetTemperature -> if (action.heater == "bed") bedTarget = action.celsius.toDouble() else nozzleTarget = action.celsius.toDouble()
            ControlAction.Home -> { homedAxes = "xyz"; position.fill(0.0) }
            is ControlAction.Jog -> { val i = "XYZ".indexOf(action.axis); if (i >= 0) position[i] += action.mm }
            is ControlAction.StartPrint -> { if (action.remotePath !in files) return CommandOutcome.Rejected("File not found: ${action.remotePath}", sent = true); state = "printing"; progress = 0f; loaded = action.remotePath }
            ControlAction.Pause -> state = "paused"
            ControlAction.Resume -> state = "printing"
            ControlAction.Cancel -> { state = "cancelled"; nozzleTarget = 0.0; bedTarget = 0.0 }
        }
        if ("lost_ack:$kind" in faults) return CommandOutcome.Unknown("No reply from $address/printer/gcode/script within 10 s (Authorization: Bearer $apiKey)")
        return CommandOutcome.Accepted
    }

    override fun localSecrets(): Set<String> = setOf(address, "192.168.50.23", hostname, apiKey, description.label)
}

/**
 * A stand-in slicer: writes deterministic G-code that carries the profile's real start/end G-code and traces the
 * model's footprint, centred on the profile's bed. It lets the pipeline run where the native engine can't; its output
 * is labelled simulated and grades nothing.
 */
class SimulatedSlicer(private val readProfileFile: (String, String) -> ByteArray?, private val outDir: File) : TestSlicer {
    override val simulated = true
    override fun profile(id: String): ProfileInfo {
        require(Regex("""^[a-z0-9_]{1,80}$""").matches(id)) { "Bad profile id." }
        return ProfileInfo.load(id) { readProfileFile(id, it) }
    }

    override fun slice(request: SliceRequest): SliceResult {
        val bed = request.profile.bed ?: return SliceResult.Failed("Profile ${request.profile.id} has no printable area.")
        val boxes = request.parts.map { (f, part) -> part to StlGeometry.bounds(f.readBytes()) }
        val minX = boxes.minOf { it.second[0] }; val minY = boxes.minOf { it.second[1] }; val maxX = boxes.maxOf { it.second[3] }; val maxY = boxes.maxOf { it.second[4] }
        val maxZ = boxes.maxOf { it.second[5] }
        val dx = (bed[0] + bed[2]) / 2 - (minX + maxX) / 2; val dy = (bed[1] + bed[3]) / 2 - (minY + maxY) / 2
        val sb = StringBuilder()
        sb.append("; Nozzle Test Grid SIMULATED slice: not produced by a slicing engine\n; model ${request.modelId}\n; profile ${request.profile.id} ${request.profile.sha256}\n")
        sb.append(request.profile.startGcode).append("\nG90\nM83\n")
        var z = 0.2
        while (z <= maxZ + 1e-6) {
            sb.append(";LAYER_CHANGE\nG1 Z%.2f F600\n".format(java.util.Locale.ROOT, z))
            boxes.forEach { (part, b) ->
                if (boxes.size > 1) sb.append("T${part.tool - 1}\n")
                sb.append(";TYPE:Outer wall\n")
                val x0 = b[0] + dx; val y0 = b[1] + dy; val x1 = b[3] + dx; val y1 = b[4] + dy
                sb.append("G0 X%.3f Y%.3f\n".format(java.util.Locale.ROOT, x0, y0))
                listOf(x1 to y0, x1 to y1, x0 to y1, x0 to y0).forEach { (x, y) -> sb.append("G1 X%.3f Y%.3f E0.5\n".format(java.util.Locale.ROOT, x, y)) }
            }
            z += if (maxZ > 5) 2.0 else 1.0
        }
        sb.append(request.profile.endGcode).append('\n')
        outDir.mkdirs()
        val out = File(outDir, "${request.outputName}.gcode")
        out.writeText(sb.toString())
        return SliceResult.Success(out)
    }
}

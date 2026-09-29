package com.nozzleitall.testgrid

import net.jamesjennison.klippercompanion.ElegooProfiles
import net.jamesjennison.klippercompanion.FirmwareIdentity
import net.jamesjennison.klippercompanion.PrinterKind
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
 * acted); "lost_ack_noeffect:<step kind>" reports an unknown outcome without acting (the request never arrived);
 * "reject:<step kind>" refuses it; "guard_disabled" makes uploadPreflight accept anything (a broken guard).
 */
class SimulatedPrinter(val preset: Preset, private val clock: () -> Long = System::currentTimeMillis, val faults: MutableSet<String> = mutableSetOf()) : TestTarget {
    enum class Preset(val printerKind: PrinterKind, val manufacturer: String, val model: String, val app: String, val version: String,
                      val paxx: Boolean, val afc: Boolean, val slicingModel: SlicingPrinterModel) {
        PAXX_U1(PrinterKind.SNAPMAKER_U1_PAXX, "Snapmaker", "U1", "", "1.6.0.267_20260815150420", true, false, SlicingPrinterModel.SNAPMAKER_U1),
        STOCK_U1(PrinterKind.SNAPMAKER_U1, "Snapmaker", "U1", "", "1.6.0.267_20260815150420", false, false, SlicingPrinterModel.SNAPMAKER_U1),
        COSMOS_CC(PrinterKind.GENERIC_KLIPPER, "Elegoo", "Centauri Carbon", "OpenCentauri Cosmos", "Release - 26.08.0", false, false, SlicingPrinterModel.ELEGOO_CENTAURI_CARBON),
        COSMOS_CC_CANVAS(PrinterKind.GENERIC_KLIPPER, "Elegoo", "Centauri Carbon", "OpenCentauri Cosmos", "Release - 26.08.0", false, true, SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_COSMOS_CANVAS),
        COSMOS_CC_LEGACY(PrinterKind.GENERIC_KLIPPER, "Elegoo", "Centauri Carbon", "OpenCentauri Cosmos", "Release - 26.06.2", false, false, SlicingPrinterModel.ELEGOO_CENTAURI_CARBON),
        GENERIC_KLIPPER(PrinterKind.GENERIC_KLIPPER, "Generic", "Klipper printer", "", "v0.12.0-437-g5b3c6c5b", false, false, SlicingPrinterModel.GENERIC_KLIPPER);

        companion object { fun parse(s: String) = entries.firstOrNull { it.name.equals(s.replace('-', '_'), ignoreCase = true) } }
    }

    private val address = "http://192.168.50.23:7125"
    private val hostname = "workshop-printer.local"
    private val apiKey = "sim-4f9c2e71d8a3b6f05e1a"

    override val description = TargetDescription(TargetKind.SIMULATED, preset.manufacturer, preset.model, preset.printerKind, "simulated", "simulated-moonraker",
        "Simulated ${preset.model} ($hostname)", address, preset.slicingModel)

    private var state = "standby"
    private var nozzle = 24.0; private var nozzleTarget = 0.0
    private var bed = 23.0; private var bedTarget = 0.0
    private var progress = 0f
    private var loaded = ""
    private var uploads = 0
    private var homedAxes = ""
    private val position = doubleArrayOf(0.0, 0.0, 0.0)
    private val files = sortedSetOf("benchy.gcode")

    override fun identity() = LiveIdentity(FirmwareIdentity(preset.app, preset.version), hostname, preset.paxx, preset.afc)

    @Synchronized override fun status(): StatusReading {
        if (state == "printing") { progress = (progress + 0.1f).coerceAtMost(1f); if (progress >= 1f) { state = "complete"; nozzleTarget = 0.0; bedTarget = 0.0 } }
        nozzle = if (nozzleTarget > 0) nozzleTarget else 24.0
        bed = if (bedTarget > 0) bedTarget else 23.0
        return StatusReading(state, true, nozzle, nozzleTarget, bed, bedTarget, progress, loaded, clock(), homedAxes, position.toList())
    }

    override fun declaredCapabilities() = CapabilityNames.declared(preset.printerKind, FirmwareFamilies.classify(description, identity()).hardware)
    override fun cameras() = listOf(CameraInfo("Toolhead", "$address/webcam/webrtc?token=$apiKey"))
    override fun cameraSnapshot(camera: CameraInfo): ByteArray = ByteArray(2048).also { b ->
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()).copyInto(b); b[2046] = 0xFF.toByte(); b[2047] = 0xD9.toByte()
    }
    override fun files(): List<String> = synchronized(this) { files.toList() }
    override fun materialSlots(): List<String> = when {
        preset.afc -> listOf("lane1: PLA white", "lane2: PLA black", "lane3: PETG orange", "lane4: empty")
        preset.printerKind == PrinterKind.SNAPMAKER_U1_PAXX || preset.printerKind == PrinterKind.SNAPMAKER_U1 -> listOf("T0: PLA", "T1: PLA", "T2: PLA", "T3: PLA")
        else -> throw UnsupportedByTarget("This printer reports no material slots.")
    }

    override fun uploadPreflight(file: File): String? =
        if ("guard_disabled" in faults) null else file.bufferedReader().useLines { ElegooProfiles.stockElegooCommand(it) }?.let { ElegooProfiles.stockElegooRefusal(it) }

    @Synchronized override fun upload(file: File, requestedName: String): TransferOutcome {
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

    @Synchronized override fun perform(action: ControlAction): CommandOutcome {
        val kind = when (action) {
            is ControlAction.SetTemperature -> "set_temperature"; ControlAction.Home -> "home"; is ControlAction.Jog -> "jog"
            is ControlAction.StartPrint -> "start_print"; ControlAction.Pause -> "pause"; ControlAction.Resume -> "resume"; ControlAction.Cancel -> "cancel"
        }
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

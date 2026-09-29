package net.jamesjennison.klippercompanion.testgrid

import com.nozzleitall.testgrid.CameraInfo
import com.nozzleitall.testgrid.CapabilityNames
import com.nozzleitall.testgrid.CommandOutcome
import com.nozzleitall.testgrid.ControlAction
import com.nozzleitall.testgrid.LiveIdentity
import com.nozzleitall.testgrid.StatusReading
import com.nozzleitall.testgrid.TargetDescription
import com.nozzleitall.testgrid.TargetKind
import com.nozzleitall.testgrid.TestTarget
import com.nozzleitall.testgrid.TransferOutcome
import com.nozzleitall.testgrid.UnsupportedByTarget
import net.jamesjennison.klippercompanion.BambuPrintProtocol
import net.jamesjennison.klippercompanion.BambuPrintRequest
import net.jamesjennison.klippercompanion.FilamentSlotReader
import net.jamesjennison.klippercompanion.HeaterControls
import net.jamesjennison.klippercompanion.HeaterRequest
import net.jamesjennison.klippercompanion.LiveFileChanges
import net.jamesjennison.klippercompanion.Moonraker
import net.jamesjennison.klippercompanion.PrinterCommand
import net.jamesjennison.klippercompanion.PrinterKind
import net.jamesjennison.klippercompanion.PrinterProfile
import net.jamesjennison.klippercompanion.PrinterService
import net.jamesjennison.klippercompanion.PrinterTransport
import net.jamesjennison.klippercompanion.PrusaLinkPrintRequest
import net.jamesjennison.klippercompanion.SlicingModelCatalog
import net.jamesjennison.klippercompanion.SlicingPrinterModel
import net.jamesjennison.klippercompanion.capabilitiesFor
import net.jamesjennison.klippercompanion.printerServiceFor
import java.io.File

/**
 * A saved printer as the Test Grid sees it, driven only through the paths the app already uses:
 *  - reads: the printer's own PrinterService (the dashboard's), plus Moonraker's discovery reads;
 *  - file transfer: LiveFileChanges (idle-only, unique names, SHA-256 verified, durable receipt, never retried);
 *  - heaters: HeaterControls.prepare (live limits, active tool, idle-only), then the same command path as HeaterPanel;
 *  - motion, start, pause, resume, cancel: the same PrinterCommands the dashboard and JogPanel send.
 * Every command is sent at most once; any failure after sending is reported as an unknown outcome.
 *
 * Test Mode v1 drives transfer and controls over Moonraker only (PAXX U1, stock U1, COSMOS, other Klipper). On other
 * connections those steps are BLOCKED, never faked.
 */
class AndroidTestTarget(
    private val profile: PrinterProfile,
    private val cacheDir: File,
    private val declaredFamily: String? = null,
    /** Slots the saved slicing profile declares (Prusa XL 5T, CANVAS...): decides whether multi-material tests apply. */
    private val toolSlots: Int = 1,
    private val serviceFactory: (PrinterProfile) -> PrinterService = { printerServiceFor(it, it.address) },
    private val clock: () -> Long = System::currentTimeMillis,
) : TestTarget {
    private val moonraker = capabilitiesFor(profile.kind).transport == PrinterTransport.MOONRAKER
    @Volatile private var hostname = ""

    override val description: TargetDescription = run {
        val info = SlicingModelCatalog.all.firstOrNull { it.model == profile.slicingModel }
        val manufacturer = info?.vendor?.label ?: "Unknown"
        val model = info?.label?.removePrefix(info.vendor.label)?.trim()?.substringBefore(" (")?.substringBefore(" + ")?.trim()?.ifBlank { null } ?: profile.kind.name
        val (adapter, protocol) = adapterFor(profile)
        TargetDescription(TargetKind.PHYSICAL, manufacturer, model, profile.kind, adapter, protocol, profile.label, profile.address, profile.slicingModel, declaredFamily, toolSlots)
    }

    companion object {
        /** Failure wording that means nothing reached the printer (the services check these before sending). */
        private val NOT_SENT = listOf("Nothing was sent", BambuPrintProtocol.MULTI_MATERIAL_NOT_SUPPORTED, BambuPrintProtocol.DYNAMIC_NOZZLE_MAP_NOT_SUPPORTED,
            "Match each filament", "is sliced for the", "which the printer reports empty", "sliced file is missing",
            "The printer is ", "password", "API key", "access code")
        /** Wording for a definite refusal by the printer after the request went out: nothing is running. */
        private val REFUSED = listOf("rejected the print command", "did not start it", "Could not upload", "(HTTP ")

        fun adapterFor(p: PrinterProfile): Pair<String, String> = when (capabilitiesFor(p.kind).transport) {
            PrinterTransport.MOONRAKER -> "android-moonraker" to "moonraker-http"
            PrinterTransport.ELEGOO -> if (p.slicingModel == SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_2_CANVAS) "android-elegoo-cc2" to "mqtt" else "android-elegoo-sdcp" to "sdcp-websocket"
            PrinterTransport.BAMBU_MQTT -> "android-bambu-lan" to "mqtt-ftps"
            PrinterTransport.PRUSA_LINK -> "android-prusalink" to "prusalink-http"
            PrinterTransport.OCTOPRINT -> "android-octoprint" to "octoprint-http"
            PrinterTransport.CREALITY -> "android-creality-lan" to "creality-websocket"
            PrinterTransport.FLASHFORGE -> "android-flashforge-lan" to "flashforge-http"
        }
    }

    private fun <T> service(block: (PrinterService) -> T): T { val s = serviceFactory(profile); try { return block(s) } finally { s.close() } }
    private fun <T> moonraker(block: (Moonraker) -> T): T {
        if (!moonraker) throw UnsupportedByTarget("Test Mode drives this step over Moonraker only; this printer connects over ${description.protocol}.")
        return Moonraker(profile.address, profile.apiKey).let { m -> try { block(m) } finally { m.close() } }
    }
    private fun fileChanges(): LiveFileChanges = LiveFileChanges(profile.address, File(cacheDir, "testgrid-files"), rawApiKey = profile.apiKey)

    override fun identity(): LiveIdentity = moonraker { m ->
        val fw = m.firmwareIdentity()
        val d = m.discoveryDetails()
        hostname = d.hostname
        LiveIdentity(fw, d.hostname, d.paxxExtendedConfig, d.afc)
    }

    override fun status(): StatusReading = service { s ->
        val snap = s.snapshot()
        val toolhead = if (moonraker) runCatching { (s as? Moonraker)?.toolheadPosition() }.getOrNull() else null
        StatusReading(snap.state, snap.ready, snap.nozzle, snap.nozzleTarget, snap.bed, snap.bedTarget, snap.progress, snap.filename, clock(),
            toolhead?.first, toolhead?.second?.takeIf { it.size == 3 })
    }

    override fun declaredCapabilities(): Set<String> = CapabilityNames.declared(profile.kind)

    override fun cameras(): List<CameraInfo> = service { s -> s.cameras().map { CameraInfo(it.name, it.stream.ifBlank { it.snapshot }) } }

    override fun cameraSnapshot(camera: CameraInfo): ByteArray = service { s ->
        val cam = s.cameras().firstOrNull { it.name == camera.name } ?: throw IllegalStateException("The camera is no longer listed.")
        s.image(cam)
    }

    override fun files(): List<String> {
        if (!moonraker) throw UnsupportedByTarget("File listing is available over Moonraker only in Test Mode.")
        return fileChanges().use { it.files() }
    }

    /** The printer's live filament slots (U1 toolheads, CANVAS lanes...), for slicing with the colours actually loaded. */
    fun liveSlots(): List<net.jamesjennison.klippercompanion.FilamentSlot> =
        runCatching { service { s -> (s as? FilamentSlotReader)?.filamentSlots()?.slots } }.getOrNull().orEmpty()

    override fun materialSlots(): List<String> {
        if (!moonraker && profile.kind !in setOf(PrinterKind.ELEGOO, PrinterKind.CREALITY, PrinterKind.FLASHFORGE)) throw UnsupportedByTarget("This connection reports no material slots.")
        val slots = service { s -> (s as? FilamentSlotReader)?.filamentSlots()?.slots }.orEmpty().map { "${it.name ?: "slot ${it.tool + 1}"}: ${it.label}${it.colorHex?.let { c -> " $c" } ?: ""}${if (it.active) " (active)" else ""}" }
        if (slots.isNotEmpty()) return slots
        if (profile.kind == PrinterKind.SNAPMAKER_U1 || profile.kind == PrinterKind.SNAPMAKER_U1_PAXX) return moonraker { m -> m.toolStatus().tools.map { "toolhead $it" } }
        return emptyList()
    }

    /** LiveFileChanges.prepare checks the file before any request; its refusal is the guard's own words. Nothing is sent. */
    override fun uploadPreflight(file: File): String? {
        if (!moonraker) throw UnsupportedByTarget("The Elegoo stock-firmware guard applies to Moonraker uploads only.")
        return fileChanges().use { f ->
            try { f.prepare(LiveFileChanges.Operation.UPLOAD, "", "nozzle-testgrid-guard.gcode", file); f.cancel(); null }
            catch (e: IllegalArgumentException) { e.message ?: "Refused." }
            catch (e: Exception) { "Refused before sending: ${e.message}" }
        }
    }

    override fun upload(file: File, requestedName: String): TransferOutcome {
        if (!moonraker) throw UnsupportedByTarget("Test Mode uploads over Moonraker only; this printer connects over ${description.protocol}.")
        return fileChanges().use { f ->
            val draft = try { f.prepare(LiveFileChanges.Operation.UPLOAD, "", requestedName, file) } catch (e: Exception) { return TransferOutcome.Refused(e.message ?: "Refused.", sent = false) }
            try { f.confirm(draft.id); TransferOutcome.Verified(draft.destination, draft.sha256) }
            catch (e: IllegalStateException) { if (e.message.orEmpty().startsWith("Outcome requires inspection")) TransferOutcome.Unknown(e.message!!) else TransferOutcome.Refused(e.message ?: "Refused.", sent = false) }
            catch (e: Exception) { TransferOutcome.Refused(e.message ?: "Refused.", sent = false) }
        }
    }

    override fun delete(remotePath: String): TransferOutcome {
        if (!moonraker) throw UnsupportedByTarget("Test Mode deletes over Moonraker only.")
        return fileChanges().use { f ->
            val draft = try { f.prepare(LiveFileChanges.Operation.DELETE, remotePath, "", null) } catch (e: Exception) { return TransferOutcome.Refused(e.message ?: "Refused.", sent = false) }
            try { f.confirm(draft.id); TransferOutcome.Verified(remotePath, draft.sha256) }
            catch (e: IllegalStateException) { if (e.message.orEmpty().startsWith("Outcome requires inspection")) TransferOutcome.Unknown(e.message!!) else TransferOutcome.Refused(e.message ?: "Refused.", sent = false) }
            catch (e: Exception) { TransferOutcome.Refused(e.message ?: "Refused.", sent = false) }
        }
    }

    override fun jobResult(remotePath: String): String? = if (!moonraker) null else runCatching {
        moonraker { m -> m.history(0).jobs.firstOrNull { it.filename == remotePath || it.filename.endsWith("/$remotePath") }?.status }
    }.getOrNull()

    /**
     * The app's own "Send and print" request for printers that take a file only with a print start: Bambu (a .gcode.3mf
     * over FTPS, then the MQTT project_file command), PrusaLink and OctoPrint (upload with print), Elegoo (upload, then start).
     * These services report every failure as an exception, so what is known to have happened is read from its wording:
     * refused before anything was sent, refused by the printer, or anything else (a lost reply), which the runner then
     * confirms from the printer's state or leaves unknown.
     */
    override fun sendAndStart(file: File, requestedName: String): TransferOutcome {
        if (moonraker) throw UnsupportedByTarget("This printer connection takes files through upload and start steps instead.")
        val idle = setOf("standby", "complete", "cancelled", "error")
        val command = if (profile.kind == PrinterKind.BAMBU_LAB)
            PrinterCommand("Print $requestedName", "", allowedStates = idle, bambuPrintRequest = BambuPrintRequest(file, requestedName))
        else PrinterCommand("Print $requestedName", "", allowedStates = idle, prusaLinkPrintRequest = PrusaLinkPrintRequest(file, requestedName))
        return try {
            service { s -> s.command(command) }
            TransferOutcome.Verified(requestedName, com.nozzleitall.testgrid.Canon.sha256(file))
        } catch (e: IllegalArgumentException) { TransferOutcome.Refused(e.message ?: "Refused before sending.", sent = false) }
          catch (e: Exception) {
            val m = e.message.orEmpty()
            when {
                NOT_SENT.any { m.contains(it, ignoreCase = true) } -> TransferOutcome.Refused(m, sent = false)
                REFUSED.any { m.contains(it, ignoreCase = true) } -> TransferOutcome.Refused(m, sent = true)
                else -> TransferOutcome.Unknown("${m.ifBlank { e.javaClass.simpleName }}. The printer may have received the file and started; check it before continuing.")
            }
        }
    }

    override fun perform(action: ControlAction): CommandOutcome = if (!moonraker) serviceControl(action) else moonraker { m ->
        val command = try {
            when (action) {
                is ControlAction.SetTemperature -> {
                    val heater = if (action.heater == "bed") "heater_bed" else m.heaterStatus("extruder").activeTool.ifBlank { "extruder" }
                    HeaterControls.prepare(HeaterRequest(heater, action.celsius.toString()), m.heaterStatus(heater))
                }
                ControlAction.Home -> PrinterCommand("Home all axes", "printer/gcode/script", mapOf("script" to "G28"))
                is ControlAction.Jog -> PrinterCommand("Jog ${action.axis}", "printer/gcode/script",
                    mapOf("script" to "G91\nG1 ${action.axis}${"%.2f".format(java.util.Locale.ROOT, action.mm)} F3000\nG90"))
                is ControlAction.StartPrint -> Moonraker.start(action.remotePath, profile.kind)
                ControlAction.Pause -> PrinterCommand("Pause print", "printer/print/pause", allowedStates = setOf("printing"))
                ControlAction.Resume -> PrinterCommand("Resume print", "printer/print/resume", allowedStates = setOf("paused"))
                ControlAction.Cancel -> PrinterCommand("Cancel print", "printer/print/cancel", allowedStates = setOf("printing", "paused"))
            }
        } catch (e: Exception) { return@moonraker CommandOutcome.Rejected(e.message ?: "Refused before sending.", sent = false) }
        try { m.command(command); CommandOutcome.Accepted }
        catch (e: net.jamesjennison.klippercompanion.U1StartRefused) { CommandOutcome.Rejected(e.message ?: "The printer refused to start.", sent = true) }
        catch (e: Exception) { CommandOutcome.Unknown("${e.message ?: e.javaClass.simpleName}. The printer may have received the command; check it before continuing.") }
    }

    /** Pause, resume and cancel go through the printer's own service, as the dashboard's buttons do; nothing else exists for these printers. */
    private fun serviceControl(action: ControlAction): CommandOutcome {
        val command = when (action) {
            ControlAction.Pause -> PrinterCommand("Pause print", "printer/print/pause", allowedStates = setOf("printing"))
            ControlAction.Resume -> PrinterCommand("Resume print", "printer/print/resume", allowedStates = setOf("paused"))
            ControlAction.Cancel -> PrinterCommand("Cancel print", "printer/print/cancel", allowedStates = setOf("printing", "paused"))
            else -> return CommandOutcome.Rejected("${action.describe()} isn't available for this printer through Nozzle.", sent = false)
        }
        return try { service { s -> s.command(command) }; CommandOutcome.Accepted }
        catch (e: Exception) {
            val m = e.message.orEmpty()
            if (NOT_SENT.any { m.contains(it, ignoreCase = true) } || m.contains("Unsupported command", ignoreCase = true) || m.contains("No active", ignoreCase = true) || m.contains("doesn't support", ignoreCase = true))
                CommandOutcome.Rejected(m, sent = false)
            else CommandOutcome.Unknown("${m.ifBlank { e.javaClass.simpleName }}. The printer may have received the command; check it before continuing.")
        }
    }

    override fun localSecrets(): Set<String> {
        val host = runCatching { java.net.URI(profile.address).host }.getOrNull()
        return setOfNotNull(profile.address, host, profile.apiKey.takeIf { it.isNotBlank() }, profile.name.takeIf { it.isNotBlank() }, profile.label,
            profile.serial.takeIf { it.isNotBlank() }, hostname.takeIf { it.isNotBlank() })
    }
}

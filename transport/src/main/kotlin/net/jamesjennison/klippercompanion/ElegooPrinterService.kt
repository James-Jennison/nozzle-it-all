package net.jamesjennison.klippercompanion

import com.nozzleitall.adapter.elegoo.ElegooLanAdapter
import com.nozzleitall.printer.ActionGuard
import com.nozzleitall.printer.ActionOutcome
import com.nozzleitall.printer.ActionRefused
import com.nozzleitall.printer.PrinterAction
import com.nozzleitall.printer.PrinterConfig
import com.nozzleitall.printer.PrinterFamily
import com.nozzleitall.printer.PrinterIdentity
import com.nozzleitall.printer.PrinterSession
import com.nozzleitall.printer.PrinterState
import com.nozzleitall.printer.PrinterStatus
import com.nozzleitall.printer.UploadResult

/**
 * PrinterKind.ELEGOO: an Elegoo Centauri Carbon on Elegoo's own firmware (SDCP) or a Centauri Carbon 2 (MQTT), through
 * the desktop's own :adapter-elegoo sessions (ported from Elegoo's elegoo-link and the SDCP document; see
 * docs/upstream/PROVENANCE.md P-0014). This class only translates between that adapter's shared printer model and this
 * app's PrinterService: live status and temperatures, the CANVAS slots, send-and-start of a sliced G-code file with its
 * slot map, and pause/resume/cancel (the adapter refuses pause and resume on a Centauri Carbon 2, whose LAN protocol has
 * no resume). Every printer-changing action goes through the adapter's ActionGuard, which re-reads the printer first and
 * never retries. LAN only. Tested against fakes only; not yet run against a real Elegoo printer.
 *
 * [model] picks the protocol as the adapter does (a "Centauri Carbon 2" model name means MQTT); [protocol] ("sdcp" or
 * "mqtt") overrides it. [accessCode] is the Centauri Carbon 2's access code (blank: the printer's default). [serial] is
 * the Centauri Carbon's mainboard ID or the Centauri Carbon 2's serial number when known; otherwise the adapter asks the
 * printer.
 */
class ElegooPrinterService(
    address: String,
    accessCode: String = "",
    serial: String = "",
    model: String = "Elegoo Centauri Carbon",
    protocol: String? = null,
    sessionFactory: (PrinterConfig) -> PrinterSession = { ElegooLanAdapter().open(it) },
) : PrinterService, FilamentSlotReader {
    override val address: String = address
    private val session: PrinterSession = sessionFactory(PrinterConfig(
        PrinterIdentity("android-elegoo", model, model, PrinterFamily.ELEGOO, address), ElegooLanAdapter.ID, accessCode.trim(),
        buildMap { serial.trim().takeIf { it.isNotEmpty() }?.let { put("serial", it) }; protocol?.let { put("protocol", it) } }))
    private val guard = ActionGuard(session)
    @Volatile private var last: PrinterStatus? = null

    private fun read(): PrinterStatus = session.status().also { last = it }

    override fun snapshot(): PrinterSnapshot = toSnapshot(read())

    override fun catalog(): Catalog = Catalog(emptyList(), emptyList(), emptyList(), emptyList())
    override fun image(camera: Camera): ByteArray = throw ApiFailure("Nozzle It All doesn't show this printer's camera yet.")

    override fun filamentSlots(): FilamentSlotStatus = slotsOf(last?.takeIf { System.currentTimeMillis() - it.observedAtMillis < 5_000 } ?: read())

    override fun command(command: PrinterCommand) {
        command.prusaLinkPrintRequest?.let { sendAndStart(it); return } // the plain-G-code upload-and-start request OctoPrint and Prusa Link share
        val action = when (command.path) {
            "printer/print/start" -> PrinterAction.StartJob(command.arguments["filename"]?.takeIf(String::isNotBlank) ?: throw ApiFailure("Missing filename."))
            "printer/print/pause" -> PrinterAction.Pause
            "printer/print/resume" -> PrinterAction.Resume
            "printer/print/cancel" -> PrinterAction.Cancel
            else -> throw ApiFailure("Unsupported command for an Elegoo printer.")
        }
        perform(action)
    }

    private fun sendAndStart(request: PrusaLinkPrintRequest) {
        val file = request.file
        if (!file.isFile) throw ApiFailure("The sliced file is missing. Slice again.")
        val status = read()
        if (status.state !in PrinterAction.StartJob("x").allowedStates) throw ApiFailure("The printer is ${status.state.name.lowercase()}; start a print when it is ready.")
        val map = file.bufferedReader().useLines { ElegooProfiles.toolheadMap(it, status.toolheads.size) }
        val remote = when (val r = session.upload(file, request.remoteName)) {
            is UploadResult.Uploaded -> r.remotePath
            is UploadResult.Failed -> throw ApiFailure(r.reason)
            is UploadResult.Interrupted -> throw ApiFailure(r.reason)
        }
        perform(PrinterAction.StartJob(remote, map))
    }

    private fun perform(action: PrinterAction) {
        val outcome = try {
            val prepared = guard.prepare(action, read())
            guard.execute(prepared, guard.confirm(prepared))
        } catch (e: ActionRefused) { throw ApiFailure(e.message ?: "The printer refused the command.") }
        when (outcome) {
            ActionOutcome.Accepted -> {}
            is ActionOutcome.Rejected -> throw ApiFailure(outcome.reason)
            is ActionOutcome.Unknown -> throw ApiFailure(outcome.reason)
        }
    }

    override fun close() = session.close()

    companion object {
        /** The shared printer states in this app's Moonraker-shaped vocabulary (PrinterSnapshot.state). */
        fun stateName(state: PrinterState): String = when (state) {
            PrinterState.READY -> "standby"
            PrinterState.PRINTING -> "printing"
            PrinterState.PAUSED -> "paused"
            PrinterState.FINISHED -> "complete"
            PrinterState.CANCELLED -> "cancelled"
            PrinterState.ERROR -> "error"
            PrinterState.STARTING -> "startup" // FamilyTerms.sharedState reads this back as STARTING
            else -> "unknown"
        }

        /** An offline printer is a failed read (the poll counts it), as a Moonraker printer that doesn't answer is. */
        fun toSnapshot(s: PrinterStatus): PrinterSnapshot {
            if (s.state == PrinterState.OFFLINE) throw ApiFailure(s.message ?: "Cannot reach the printer.")
            val nozzle = s.toolheads.firstOrNull { it.nozzleTemperature != null || it.nozzleTarget != null }
            val job = s.job
            return PrinterSnapshot(s.state != PrinterState.UNKNOWN && s.state != PrinterState.CONNECTING, stateName(s.state), job?.fileName.orEmpty(),
                job?.fraction ?: 0f, nozzle?.nozzleTemperature, nozzle?.nozzleTarget, s.bed?.current, s.bed?.target, job?.elapsedSeconds,
                job?.currentLayer?.takeIf { it > 0 }, job?.totalLayers?.takeIf { it > 0 }, "extruder")
        }

        /** CANVAS trays as slots; a printer without CANVAS (one toolhead, nothing reported loaded) has none. */
        fun slotsOf(s: PrinterStatus): FilamentSlotStatus {
            if (s.state == PrinterState.OFFLINE) throw ApiFailure(s.message ?: "Cannot reach the printer.")
            val heads = s.toolheads
            if (heads.size <= 1 && heads.none { it.material != null }) return FilamentSlotStatus(emptyList(), "")
            return FilamentSlotStatus(heads.sortedBy { it.index }.map { h ->
                FilamentSlot(h.index, h.material?.let { m -> listOfNotNull(m.type, m.subType).joinToString(" ").ifBlank { null } }, h.material?.colorHex,
                    vendor = h.material?.vendor, active = h.active)
            }, "CANVAS")
        }
    }
}

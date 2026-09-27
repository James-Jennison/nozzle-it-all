package com.nozzleitall.adapter.common

import com.nozzleitall.printer.*
import net.jamesjennison.klippercompanion.ApiFailure
import net.jamesjennison.klippercompanion.Camera
import net.jamesjennison.klippercompanion.PrinterCommand
import net.jamesjennison.klippercompanion.PrinterService
import java.io.File
import java.io.IOException

/**
 * A printer-api session over one of Android's :transport clients (OctoPrint, PrusaLink, Bambu Lab). Read-only status and
 * cameras map onto the shared model; the few commands those clients implement map onto shared actions. The adapter
 * declares [capabilities] to match exactly what its client implements, so screens never offer something that can't work.
 *
 * Outcome rules: only a recognisable refusal (an HTTP 4xx, a rejected key, the client's own validation) proves nothing
 * happened (Rejected). Every other failure during a command might have reached the printer: Unknown, which blocks
 * further commands until the printer has been re-checked (ActionGuard). Nothing is ever retried here.
 */
open class TransportSession(
    override val identity: PrinterIdentity,
    override val capabilities: Capabilities,
    protected val service: PrinterService,
    private val route: ConnectionRoute,
    /** Builds the one-request "upload and start" command for this vendor, when the adapter supports it. */
    private val uploadAndStart: ((File, String) -> PrinterCommand)? = null,
) : PrinterSession {
    private val cameraById = HashMap<String, Camera>()

    init {
        // Screens offer whatever the capabilities list, so a flag this session can't perform would be a dead control.
        require(!capabilities.temperatures && !capabilities.motion && !capabilities.loadUnload && !capabilities.materialEdit && !capabilities.uploadJob) {
            "${identity.displayName}: a transport session can't set temperatures, move axes, change materials or upload without starting; don't declare them."
        }
    }

    override fun status(): PrinterStatus = try {
        val s = service.snapshot()
        val state = if (!s.ready) PrinterState.fromRaw(s.state).takeIf { it == PrinterState.ERROR || it == PrinterState.OFFLINE } ?: PrinterState.STARTING
            else PrinterState.fromRaw(s.state)
        val job = if (state.isActiveJob) JobProgress(s.filename.substringAfterLast('/'), s.activeProgress, s.printDuration, s.currentLayer, s.totalLayers) else null
        val heads = if (s.nozzle != null || s.nozzleTarget != null) listOf(Toolhead(0, s.nozzle, s.nozzleTarget, active = true)) else emptyList()
        PrinterStatus(state, route, job, if (s.bed != null || s.bedTarget != null) Temperature(s.bed, s.bedTarget) else null, heads)
    } catch (e: ApiFailure) {
        PrinterStatus(PrinterState.ERROR, route, message = e.message)
    } catch (e: Exception) {
        PrinterStatus(PrinterState.OFFLINE, route, message = e.message ?: "The printer did not answer.")
    }

    override fun cameras(): List<CameraEndpoint> = if (!capabilities.camera) emptyList() else service.cameras().map { c ->
        cameraById[c.id] = c
        CameraEndpoint(c.id, c.name, if (c.stream.isNotBlank()) CameraKind.MJPEG_STREAM else CameraKind.SNAPSHOT, c.stream.ifBlank { c.snapshot }, c.snapshot.ifBlank { null })
    }

    override fun snapshot(camera: CameraEndpoint): ByteArray =
        service.image(cameraById[camera.id] ?: throw IOException("That camera is not available on this printer."))

    override fun upload(file: File, remoteName: String, progress: UploadProgress): UploadResult =
        UploadResult.Failed("This printer receives a file and starts it in one step. Use Send and print.")

    override fun perform(action: PrinterAction): ActionOutcome {
        val command = when (action) {
            PrinterAction.Pause -> PrinterCommand("Pause", "printer/print/pause").takeIf { capabilities.pausePrint }
            PrinterAction.Resume -> PrinterCommand("Resume", "printer/print/resume").takeIf { capabilities.resumePrint }
            PrinterAction.Cancel -> PrinterCommand("Cancel", "printer/print/cancel").takeIf { capabilities.cancelPrint }
            is PrinterAction.StartJob -> PrinterCommand("Start", "printer/print/start", mapOf("filename" to action.remotePath)).takeIf { capabilities.startPrint }
            is PrinterAction.UploadAndStart -> {
                val f = File(action.localPath)
                if (!f.isFile) return ActionOutcome.Rejected("The sliced file is missing. Slice again.")
                uploadAndStart?.invoke(f, action.remoteName)
            }
            else -> null
        } ?: return ActionOutcome.Rejected("${identity.displayName} doesn't support that from Nozzle It All.")
        return try {
            service.command(command); ActionOutcome.Accepted
        } catch (e: ApiFailure) {
            // The transports use ApiFailure both for refusals and for network failures ("Could not reach ..."). Only a
            // recognisable refusal proves nothing happened; anything else might have reached the printer.
            if (isRefusal(e.message)) ActionOutcome.Rejected(e.message ?: "The printer refused the command.")
            else ActionOutcome.Unknown("${e.message ?: "No reply"}. The command may or may not have run; check the printer before trying again.")
        } catch (e: IllegalArgumentException) {
            ActionOutcome.Rejected(e.message ?: "Invalid command.")
        } catch (e: Exception) {
            ActionOutcome.Unknown("${e.message ?: "No reply"}. The command may or may not have run; check the printer before trying again.")
        }
    }

    override fun close() = service.close()

    companion object {
        private val refusal = Regex("(HTTP 4\\d\\d|rejected|refused|unsupported|missing|no active|invalid|not ready|busy)", RegexOption.IGNORE_CASE)
        fun isRefusal(message: String?) = message != null && !message.startsWith("Could not reach", ignoreCase = true) && refusal.containsMatchIn(message)
    }
}

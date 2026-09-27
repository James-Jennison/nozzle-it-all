package com.nozzleitall.adapter.paxx

import com.nozzleitall.printer.*
import okhttp3.EventListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * The required, native PAXX adapter. It talks to Moonraker on the printer's own address (LAN, or the LAN reached through
 * the user's private network) and nothing else. It works with no internet connection and no account of any kind.
 */
class PaxxLanAdapter(private val eventListener: EventListener? = null) : DeviceAdapter {
    override val id = ID
    override val displayName = "PAXX (LAN)"
    override val firmware = setOf(FirmwareFamily.PAXX, FirmwareFamily.KLIPPER)
    override val mayUseVendorCloud = false

    companion object { const val ID = "paxx-lan" }

    override fun probe(address: String): DiscoveredPrinter? = try {
        MoonrakerLan(address, eventListener = eventListener).use { m ->
            val server = m.get("server/info") as? JSONObject ?: return null
            if (!server.has("klippy_state")) return null
            val info = runCatching { m.get("printer/info") as? JSONObject }.getOrNull()
            val objects = runCatching { (m.get("printer/objects/list") as JSONObject).getJSONArray("objects").let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
            val config = runCatching { (m.get("server/files/list", mapOf("root" to "config")) as JSONArray).let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("path") } } }.getOrDefault(emptyList())
            val webcams = runCatching { (m.get("server/webcams/list") as JSONObject).getJSONArray("webcams") }.getOrNull()
            val d = U1Protocol.detect(info, objects, config, webcams)
            DiscoveredPrinter(m.base.toString(), if (d.isU1) "Snapmaker U1" else "Klipper printer", d.firmware,
                if (d.firmware == FirmwareFamily.STOCK_U1) "stock-u1" else ID, d.evidence, routeFor(m.base.host))
        }
    } catch (e: IllegalArgumentException) { null } catch (e: IOException) { null }

    override fun open(config: PrinterConfig): PrinterSession = U1LanSession(config, paxxCapabilities(config.identity.firmware), eventListener)

    private fun paxxCapabilities(firmware: FirmwareFamily) = Capabilities(camera = true, upload = true, startJob = true, pauseResumeCancel = true,
        temperatures = true, motion = true, materials = true, materialEdit = firmware == FirmwareFamily.PAXX, fullSpectrum = firmware == FirmwareFamily.PAXX,
        requiresVendorAccount = false)
}

/**
 * A Moonraker session to one U1 (or other Klipper printer) over the LAN. Shared by the PAXX adapter and, from the
 * optional Stock U1 helper, for the stock firmware's own LAN interface; [capabilities] decides what is offered.
 */
class U1LanSession(private val config: PrinterConfig, override val capabilities: Capabilities, eventListener: EventListener? = null) : PrinterSession {
    private val m = MoonrakerLan(config.identity.address, config.secret, eventListener)
    private val route = routeFor(m.base.host)
    override val identity = config.identity

    override fun status(): PrinterStatus = try {
        val server = m.get("server/info") as JSONObject
        if (!server.optBoolean("klippy_connected") || server.optString("klippy_state") != "ready") {
            val klippy = server.optString("klippy_state", "")
            PrinterStatus(if (klippy == "shutdown" || klippy == "error") PrinterState.ERROR else PrinterState.STARTING, route,
                message = "Klipper is ${klippy.ifBlank { "not connected" }}.")
        } else U1Protocol.parseStatus(m.get("printer/objects/query", U1Protocol.statusQuery) as JSONObject, route)
    } catch (e: PrinterRejected) {
        PrinterStatus(PrinterState.ERROR, route, message = e.message)
    } catch (e: IOException) {
        PrinterStatus(PrinterState.OFFLINE, route, message = e.message ?: "The printer did not answer.")
    } catch (e: org.json.JSONException) {
        PrinterStatus(PrinterState.UNKNOWN, route, message = "The printer's reply was incomplete.")
    }

    override fun cameras(): List<CameraEndpoint> = U1Protocol.cameras((m.get("server/webcams/list") as JSONObject).getJSONArray("webcams"))
        .map { it.copy(url = m.base.resolve(it.url).toString(), snapshotUrl = it.snapshotUrl?.let { s -> m.base.resolve(s).toString() }) }

    override fun snapshot(camera: CameraEndpoint): ByteArray {
        val target = camera.snapshotUrl ?: throw IOException("This camera has no still-image address; open its live stream instead.")
        return m.bytes(target, 6_000_000)
    }

    override fun upload(file: File, remoteName: String, progress: UploadProgress): UploadResult = try {
        require(file.isFile) { "The file to upload does not exist." }
        UploadResult.Uploaded(m.upload(file, U1Protocol.validateRemotePath(remoteName)) { s, t -> progress.onProgress(s, t) })
    } catch (e: UploadInterrupted) { UploadResult.Interrupted(e.message ?: "The upload was interrupted.") }
      catch (e: IllegalArgumentException) { UploadResult.Failed(e.message ?: "Invalid upload.") }
      catch (e: IOException) { UploadResult.Failed(e.message ?: "The upload failed.") }

    private fun macros(): Set<String> = runCatching {
        (m.get("printer/objects/list") as JSONObject).getJSONArray("objects").let { a -> (0 until a.length()).map { a.getString(it) } }
            .filter { it.startsWith("gcode_macro ") }.map { it.removePrefix("gcode_macro ").uppercase() }.toSet()
    }.getOrDefault(emptySet())

    override fun perform(action: PrinterAction): ActionOutcome {
        // Validation first: an invalid request is rejected without contacting the printer.
        val send: () -> Any = try {
            when (action) {
                is PrinterAction.StartJob -> {
                    val path = U1Protocol.validateRemotePath(action.remotePath)
                    if (action.toolheadMap.isNotEmpty()) { val body = U1Protocol.startLocalPrintBody(path, action.toolheadMap); { m.post("server/files/start_local_print", body = body) } }
                    else { { m.post("printer/print/start", mapOf("filename" to path)) } }
                }
                PrinterAction.Pause -> { { m.post("printer/print/pause") } }
                PrinterAction.Resume -> { { m.post("printer/print/resume") } }
                PrinterAction.Cancel -> { { m.post("printer/print/cancel") } }
                is PrinterAction.SetNozzleTemperature -> U1Protocol.nozzleTemperature(action.toolhead, action.celsius).let { s -> { m.gcode(s) } }
                is PrinterAction.SetBedTemperature -> U1Protocol.bedTemperature(action.celsius).let { s -> { m.gcode(s) } }
                PrinterAction.HomeAll -> { { m.gcode("G28") } }
                is PrinterAction.Jog -> U1Protocol.jog(action.axis, action.millimetres).let { s -> { m.gcode(s) } }
                is PrinterAction.SelectToolhead -> U1Protocol.selectToolhead(action.toolhead).let { s -> { m.gcode(s) } }
                is PrinterAction.SetMaterialInfo -> {
                    if (!capabilities.materialEdit) return ActionOutcome.Rejected("Editing toolhead materials is not available for this printer.")
                    U1Protocol.setMaterial(action.toolhead, action.material).let { s -> { m.gcode(s) } }
                }
                is PrinterAction.LoadMaterial, is PrinterAction.UnloadMaterial -> {
                    val load = action is PrinterAction.LoadMaterial
                    val toolhead = if (action is PrinterAction.LoadMaterial) action.toolhead else (action as PrinterAction.UnloadMaterial).toolhead
                    val macro = if (load) "LOAD_FILAMENT" else "UNLOAD_FILAMENT"
                    if (macro !in macros()) return ActionOutcome.Rejected("This printer has no $macro macro, so Nozzle It All cannot ${if (load) "load" else "unload"} material here. Use the printer's screen.")
                    val script = U1Protocol.selectToolhead(toolhead) + "\n" + macro
                    ({ m.gcode(script) })
                }
            }
        } catch (e: IllegalArgumentException) { return ActionOutcome.Rejected(e.message ?: "Invalid command.") }
        return try {
            val result = send()
            if (result == "ok" || result is JSONObject) ActionOutcome.Accepted else ActionOutcome.Unknown("The printer's acknowledgement was not recognised. Check the printer before trying again.")
        } catch (e: PrinterRejected) { ActionOutcome.Rejected(e.message ?: "The printer refused the command.") }
          catch (e: IOException) { ActionOutcome.Unknown("${e.message ?: "No reply"}. The command may or may not have run; check the printer before trying again.") }
    }

    override fun close() = m.close()
}

package net.jamesjennison.klippercompanion

import java.io.File
import java.io.IOException
import org.json.JSONObject

// Phase 9S: the printer-service abstractions (ApiFailure, snapshots, catalog, PrinterCommand, PrinterService, host
// policy), split out of Moonraker.kt so :domain does not depend on any transport.
class ApiFailure(message: String) : IOException(message)
data class PrinterSnapshot(val ready: Boolean, val state: String, val filename: String = "", val progress: Float = 0f, val nozzle: Double? = null, val nozzleTarget: Double? = null, val bed: Double? = null, val bedTarget: Double? = null, val printDuration: Double? = null, val currentLayer: Int? = null, val totalLayers: Int? = null, val activeExtruder: String = "") {
    // Moonraker retains the loaded filename after completion; preserve raw telemetry.
    val activeFilename: String get() = if(ready && state in setOf("printing", "paused")) filename else ""
    val activeProgress: Float get() = if(activeFilename.isNotBlank()) progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f else 0f
    val displayState: String get() = if(ready && state in setOf("complete", "cancelled")) "standby" else state
    val nozzleLabel: String get() = if(activeExtruder.isBlank()) "NOZZLE · unknown tool" else "NOZZLE · $activeExtruder"
}
// address overrides the printer address a camera is rendered against. Moonraker cameras leave it
// blank (their URLs live on the printer's own host); a Bambu chamber camera is re-served on
// loopback by this app, so its origin is a different host from the printer's by design.
data class Camera(val name: String, val snapshot: String, val stream: String = "", val service: String = "", val id: String = name, val address: String = "")
data class Catalog(val files: List<String>, val macros: List<String>, val cameras: List<Camera>, val warnings: List<String>, val fileInfo: List<FileInfo> = emptyList())
// path/arguments are HTTP-shaped and only mean anything to Moonraker. bambuPrintRequest is the
// one command kind that isn't an HTTP call at all (MQTT + FTPS, see BambuPrinterService); it
// leaves them empty. prusaLinkPrintRequest (Phase 6, WO-23) is the Prusa Link equivalent - a
// real single PUT (upload + Print-After-Upload) rather than Moonraker's separate upload/start
// steps, see PrusaLinkPrinterService.uploadAndPrint().
data class PrinterCommand(val title: String, val path: String, val arguments: Map<String, String> = emptyMap(), val allowedStates: Set<String> = emptySet(), val heaterRequest: HeaterRequest? = null, val fanRequest: FanRequest? = null, val speedFlowRequest: SpeedFlowRequest? = null, val macroRequest: MacroRequest? = null, val ledRequest: LedRequest? = null, val toolRequest: ToolRequest? = null, val bambuPrintRequest: BambuPrintRequest? = null, val pandaBreathRequest: PandaBreathRequest? = null, val aceRequest: AceRequest? = null, val prusaLinkPrintRequest: PrusaLinkPrintRequest? = null)

interface PrinterService {
    val address: String
    fun snapshot(): PrinterSnapshot
    fun catalog(): Catalog
    fun cameras(): List<Camera> = emptyList()
    fun image(camera: Camera): ByteArray
    fun metadata(filename: String): FileMetadata = throw ApiFailure("Metadata unavailable.")
    fun history(start: Int): HistoryPage = throw ApiFailure("History unavailable.")
    fun thumbnail(path: String): ByteArray = throw ApiFailure("Thumbnail unavailable.")
    fun timelapseThumbnail(path: String): ByteArray = throw ApiFailure("Timelapse thumbnail unavailable.")
    fun timelapseVideoUrl(path: String): TimelapseVideoUrl = throw ApiFailure("Timelapse video unavailable.")
    fun heaterStatus(heater: String): HeaterStatus = throw ApiFailure("Heater controls unavailable.")
    fun fanStatus(fan: String): FanStatus = throw ApiFailure("Fan controls unavailable.")
    fun meshStatus(): BedMeshStatus = throw ApiFailure("Bed mesh unavailable.")
    fun toolheadTemperatures(): List<ToolheadTemperature> = throw ApiFailure("Toolhead temperatures unavailable.")
    fun fanReadouts(): List<FanReadout> = throw ApiFailure("Fan readouts unavailable.")
    fun configFile(): ConfigFileContent = throw ApiFailure("Config file unavailable.")
    fun backupConfig(filename: String): String = throw ApiFailure("Config backup unavailable.")
    fun writeConfig(filename: String, content: String): Unit = throw ApiFailure("Config save unavailable.")
    fun speedFlowStatus(): SpeedFlowStatus = throw ApiFailure("Speed/flow status unavailable.")
    fun macroStatus(name: String): MacroStatus = throw ApiFailure("Macro status unavailable.")
    fun leds(): List<String> = emptyList()
    fun ledStatus(led: String): LedStatus = throw ApiFailure("Light status unavailable.")
    fun timelapses(): List<TimelapseClip> = throw ApiFailure("Timelapse unavailable.")
    fun toolStatus(): ToolStatus = throw ApiFailure("Tool controls unavailable.")
    fun pandaBreathStatus(): PandaBreathStatus = throw ApiFailure("Panda Breath unavailable.")
    fun aceStatus(): AceStatus = throw ApiFailure("multiACE unavailable.")
    // WO-13: read live before ever using a Centauri-Carbon/COSMOS-specific slicer profile - see
    // FirmwareIdentity.kt's own header comment on why a cached/stale value is not safe here.
    fun firmwareIdentity(): FirmwareIdentity = throw ApiFailure("Firmware identity unavailable.")
    // Snapmaker U1/PAXX only (PrinterKind.SNAPMAKER_U1_PAXX); see Bespok3d.kt.
    fun bespok3dProbe(): Bespok3dProbe = throw ApiFailure("Bespok3d bridge unavailable.")
    fun bespok3dStatus(connection: Bespok3dConnection): Bespok3dStatus? = throw ApiFailure("Bespok3d bridge unavailable.")
    fun bespok3dPlugins(connection: Bespok3dConnection): Bespok3dPluginCatalog = throw ApiFailure("Bespok3d bridge unavailable.")
    fun bespok3dInstallPlugins(connection: Bespok3dConnection, pluginIds: List<String>, vars: Map<String, Map<String, String>>): Bespok3dPluginInstallResult = throw ApiFailure("Bespok3d bridge unavailable.")
    fun command(command: PrinterCommand)
    fun close()
}
// The printer's own touchscreen, exposed by the Bespok3d/HelixScreen plugin as an ordinary webcam
// catalog entry. Its stream must not be treated as a regular camera - mirrors Helix's isGuiWebcam.
// The one bar this app applies to an unencrypted host, shared by Moonraker.parseAddress (which
// applies it to an http:// URL's host) and bambuHostAddress (a bare host with no scheme at all,
// since a Bambu printer has no HTTP endpoint to point a URL at). Both must stay in step: this app
// is local-network-only by design.
fun isLocalHost(host: String): Boolean {
    val parts = host.split('.').mapNotNull { it.toIntOrNull() }
    // 100.64.0.0/10 is the CGNAT range Tailscale assigns tailnet IPv4 addresses from.
    val privateV4 = host.split('.').size == 4 && parts.size == 4 && parts.all { it in 0..255 } && (parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31) || (parts[0] == 100 && parts[1] in 64..127))
    // fd7a:115c:a1e0::/48 is Tailscale's own IPv6 ULA range for tailnet addresses.
    val tailscaleV6 = host.startsWith("fd7a:115c:a1e0:", ignoreCase = true)
    // A bare single-label host (no dot, not an IPv6 literal) can't be a publicly routable
    // DNS name at all - it only resolves via a local search domain, mDNS or a VPN's own
    // private DNS (e.g. Tailscale MagicDNS short names), so it's as trustworthy as .local.
    val bareHostname = host.isNotEmpty() && '.' !in host && ':' !in host
    return privateV4 || tailscaleV6 || bareHostname || host == "localhost" || host == "::1" || host.endsWith(".local") || host.endsWith(".ts.net")
}
fun Camera.isBespok3dScreen(): Boolean = name.equals("gui", ignoreCase = true) || Regex("/screen(?:/|$)", RegexOption.IGNORE_CASE).containsMatchIn(stream)

/**
 * One already-sliced Bambu print job. [file] is a real local file rather than a
 * content Uri: the FTPS transport needs a length and a rewindable stream, and
 * the MD5 the printer checks has to be computed over exactly those bytes. The
 * caller copies a shared Uri into app storage first (as FileWorkspace already
 * does) and hands the copy over.
 */
data class BambuPrintRequest(
    val file: File,
    val remoteName: String = file.name,
    val bedType: String = "textured_plate",
    val bedLeveling: Boolean = true,
    val flowCalibration: Boolean = true,
    val timelapse: Boolean = false,
)

/**
 * One already-sliced Prusa Link print job. [file] is a real local file (this app's own sliced
 * .gcode, same shape [BambuPrintRequest] takes for the Bambu side) rather than a content Uri -
 * PrusaLinkPrinterService.uploadAndPrint() needs a real, re-readable body for the PUT upload, and
 * a real Content-Length declared up front.
 */
// Also the request the OctoPrint service takes: both upload a plain G-code file and start it.
data class PrusaLinkPrintRequest(val file: File, val remoteName: String = file.name)

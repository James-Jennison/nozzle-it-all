package net.jamesjennison.klippercompanion

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

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
// leaves them empty.
data class PrinterCommand(val title: String, val path: String, val arguments: Map<String, String> = emptyMap(), val allowedStates: Set<String> = emptySet(), val heaterRequest: HeaterRequest? = null, val fanRequest: FanRequest? = null, val speedFlowRequest: SpeedFlowRequest? = null, val macroRequest: MacroRequest? = null, val ledRequest: LedRequest? = null, val toolRequest: ToolRequest? = null, val bambuPrintRequest: BambuPrintRequest? = null, val pandaBreathRequest: PandaBreathRequest? = null, val aceRequest: AceRequest? = null)

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
internal fun isLocalHost(host: String): Boolean {
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
class Moonraker(address: String, rawApiKey: String = "") : PrinterService, ConsoleReader, HeaterReader, FanReader, MeshReader, ToolheadReader, FanReadoutReader, ConfigFileReader, ConfigWriter, SpeedFlowReader, MacroReader, LedReader, TimelapseReader, ToolReader, Bespok3dReader, PandaBreathReader, SpoolmanReader, AceReader {
    val base: HttpUrl = parseAddress(address)
    override val address: String get() = base.toString()
    private val apiKey = rawApiKey.trim()
    private val client = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().apply { if (apiKey.isNotEmpty()) header("X-Api-Key", apiKey) }.build()) }
        .build()
    // printer/gcode/script blocks until the gcode finishes: physical actions (a toolchanger's
    // kinematic-coupling swap, homing, calibration) routinely run well past the 7s read-only
    // timeout above without the request actually failing - Moonraker itself keeps a request
    // alive up to 60s (see its "Request 'gcode/script' pending" log) before giving up. Commands
    // get their own longer-lived client so a slow-but-successful action isn't reported as failed.
    private val commandClient = client.newBuilder().readTimeout(65, TimeUnit.SECONDS).callTimeout(70, TimeUnit.SECONDS).build()

    companion object {
        internal fun activeExtruder(status: JSONObject): String = status.optJSONObject("toolhead")?.optString("extruder", "")
            ?.takeIf { it.length <= 32 && Regex("extruder[0-9]*").matches(it) } ?: ""
        fun parseAddress(address: String): HttpUrl {
            val url = address.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter an http:// or https:// printer address.")
            if(url.scheme == "http") require(isLocalHost(url.host)) { "HTTP requires a local IPv4/IPv6 address, a Tailscale address (100.64-127.x.x, fd7a:115c:a1e0::/48 or *.ts.net), a bare local hostname, localhost or .local name. Use HTTPS for other addresses." }
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "Use a base address without credentials, query or fragment." }
            return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build()
        }
        fun cameraUrl(address: String, cameraPath: String): HttpUrl {
            val base = parseAddress(address)
            val target = base.resolve(cameraPath) ?: throw ApiFailure("Invalid camera address.")
            if (target.host != base.host || target.username.isNotEmpty() || target.password.isNotEmpty() || target.fragment != null) throw ApiFailure("Camera must use the printer host without URL credentials.")
            if(base.scheme == "https" && target.scheme != "https") throw ApiFailure("An HTTPS printer requires an HTTPS camera address.")
            parseAddress(target.newBuilder().query(null).build().toString())
            return target
        }
        fun parseSnapshot(result: JSONObject): PrinterSnapshot {
            val status = result.getJSONObject("status")
            val stats = status.optJSONObject("print_stats")
            val ready = status.getJSONObject("webhooks").getString("state") == "ready"
            fun number(obj: String, field: String): Double? = status.optJSONObject(obj)?.optDouble(field)?.takeIf { it.isFinite() }
            val state = if (ready) stats?.optString("state", "unknown") ?: "unknown" else "not ready"
            val active = activeExtruder(status)
            return PrinterSnapshot(ready, state, stats?.optString("filename", "") ?: "",
                (number("virtual_sdcard", "progress") ?: 0.0).coerceIn(0.0, 1.0).toFloat(),
                active.takeIf { it.isNotEmpty() }?.let { number(it, "temperature") }, active.takeIf { it.isNotEmpty() }?.let { number(it, "target") }, number("heater_bed", "temperature"), number("heater_bed", "target"), stats?.finiteNonnegative("print_duration"),
                stats?.optJSONObject("info")?.optInt("current_layer")?.takeIf { it > 0 },
                stats?.optJSONObject("info")?.optInt("total_layer")?.takeIf { it > 0 }, active)
        }
        fun start(file: String) = PrinterCommand("Start $file", "printer/print/start", mapOf("filename" to file), setOf("standby", "complete", "cancelled", "error"))
        fun macro(name: String): PrinterCommand {
            require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)) { "Unsupported macro name" }
            return PrinterCommand("Run $name", "printer/gcode/script", mapOf("script" to name))
        }
    }
    private fun url(path: String, args: Map<String, String> = emptyMap()): HttpUrl {
        val builder = base.resolve(path)!!.newBuilder()
        args.forEach { (k,v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }
    private fun request(path: String, args: Map<String, String> = emptyMap(), mutate: Boolean = false, jsonBody: JSONObject? = null, httpClient: OkHttpClient = client): Any {
        val builder = Request.Builder().url(url(path,args))
        if (jsonBody != null) builder.post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
        else if (mutate) builder.post("".toRequestBody("application/json".toMediaType()))
        httpClient.newCall(builder.build()).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw ApiFailure(
                if (apiKey.isEmpty()) "Moonraker requires authentication. Add its API key when editing this printer."
                else "Moonraker rejected the configured API key. Copy a current key from Fluidd/Mainsail and update it here."
            )
            if (!response.isSuccessful) throw ApiFailure("Printer request failed (HTTP ${response.code}).")
            val body = response.body ?: throw ApiFailure("Empty printer response.")
            val raw = body.source().let { it.request(2_000_001); it.buffer.readByteArray() }
            if (raw.size > 2_000_000) throw ApiFailure("Printer response exceeds the supported size.")
            try {
                val envelope = JSONObject(String(raw, Charsets.UTF_8))
                if (envelope.has("error")) throw ApiFailure("Moonraker rejected the request.")
                return envelope.get("result")
            } catch (e: org.json.JSONException) { throw ApiFailure("Invalid Moonraker response.") }
        }
    }
    private val bespok3dClient by lazy { Bespok3dClient() }
    override fun bespok3dProbe(): Bespok3dProbe = bespok3dClient.probe(base.host)
    override fun bespok3dStatus(connection: Bespok3dConnection): Bespok3dStatus? = try {
        bespok3dClient.status(base.host, connection.token, connection.certificatePem)
    } catch (e: Bespok3dHttpException) { if (e.statusCode == 401) null else throw e }
    override fun bespok3dPlugins(connection: Bespok3dConnection): Bespok3dPluginCatalog =
        bespok3dClient.plugins(base.host, connection.token, connection.certificatePem)
    override fun bespok3dInstallPlugins(connection: Bespok3dConnection, pluginIds: List<String>, vars: Map<String, Map<String, String>>): Bespok3dPluginInstallResult =
        bespok3dClient.installPlugins(base.host, connection.token, connection.certificatePem, pluginIds, vars)
    override fun console(): ConsoleBatch {
        val result=request("server/gcode_store",mapOf("count" to ConsoleLog.MAX_ENTRIES.toString())) as? JSONObject ?: throw ApiFailure("Console unavailable.")
        return ConsoleLog.parse(result)
    }
    override fun heaterStatus(heater: String): HeaterStatus {
        require(HeaterControls.validHeater(heater)) { "Unsupported heater." }
        val result=request("printer/objects/query",mapOf("webhooks" to "state", "print_stats" to "state", "toolhead" to "extruder", "configfile" to "settings", heater to "temperature,target")) as? JSONObject
            ?: throw ApiFailure("Heater state unavailable.")
        return HeaterControls.parse(heater,result)
    }
    override fun speedFlowStatus(): SpeedFlowStatus {
        val result=request("printer/objects/query",mapOf("webhooks" to "state", "print_stats" to "state", "configfile" to "settings", "gcode_move" to "speed_factor,extrude_factor")) as? JSONObject
            ?: throw ApiFailure("Speed/flow status unavailable.")
        return SpeedFlowControls.parse(result)
    }
    override fun leds(): List<String> = LedControls.catalog(request("printer/objects/list") as? JSONObject ?: throw ApiFailure("Light catalog unavailable."))
    override fun ledStatus(led: String): LedStatus {
        require(LedControls.validLed(led)) { "Unsupported light." }
        val result = request("printer/objects/query", mapOf("webhooks" to "state", "led $led" to "color_data")) as? JSONObject
            ?: throw ApiFailure("Light status unavailable.")
        return LedControls.parse(led, result)
    }
    override fun timelapses(): List<TimelapseClip> {
        val list = request("server/files/list", mapOf("root" to "timelapse")) as? JSONArray ?: throw ApiFailure("Timelapse unavailable.")
        return Timelapses.parse(list)
    }
    override fun timelapseThumbnail(path: String): ByteArray = fileBytes("timelapse", path, 2_000_000, "timelapse thumbnail")
    override fun timelapseVideoUrl(path: String): TimelapseVideoUrl {
        validateFilePath(path, "Invalid timelapse path.")
        val target = base.newBuilder().addPathSegments("server/files/timelapse").apply { path.split('/').forEach { addPathSegment(it) } }.build()
        // Moonraker's static file handler honours Range requests, so VideoView can seek without
        // downloading the whole clip; the API key travels as a header, never in the URL itself.
        return TimelapseVideoUrl(target.toString(), if(apiKey.isNotEmpty()) mapOf("X-Api-Key" to apiKey) else emptyMap())
    }
    override fun macroStatus(name: String): MacroStatus {
        require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)) { "Unsupported macro name" }
        val query=request("printer/objects/query",mapOf("webhooks" to "state", "print_stats" to "state")) as? JSONObject
            ?: throw ApiFailure("Macro status unavailable.")
        val listed=request("printer/objects/list") as? JSONObject ?: throw ApiFailure("Macro catalog unavailable.")
        return MacroTools.parseStatus(name,query,listed)
    }
    override fun meshStatus(): BedMeshStatus {
        val result=request("printer/objects/query",mapOf("bed_mesh" to "profile_name,mesh_min,mesh_max,probed_matrix")) as? JSONObject
            ?: throw ApiFailure("Bed mesh unavailable.")
        return BedMesh.parse(result)
    }
    override fun toolheadTemperatures(): List<ToolheadTemperature> {
        val listed = request("printer/objects/list") as? JSONObject ?: throw ApiFailure("Toolhead list unavailable.")
        val names = Toolheads.discover(listed)
        if (names.isEmpty()) return emptyList()
        val fields = names.associateWith { "temperature,target" }
        val result = request("printer/objects/query", fields) as? JSONObject ?: throw ApiFailure("Toolhead temperatures unavailable.")
        return Toolheads.parse(names, result)
    }
    override fun fans(): List<String> = FanControls.catalog(request("printer/objects/list") as? JSONObject ?: throw ApiFailure("Fan catalog unavailable."))
    override fun fanReadouts(): List<FanReadout> {
        val names = FanControls.catalog(request("printer/objects/list") as? JSONObject ?: throw ApiFailure("Fan catalog unavailable."))
        if (names.isEmpty()) return emptyList()
        val fields = names.associateWith { "speed,rpm" }
        val result = request("printer/objects/query", fields) as? JSONObject ?: throw ApiFailure("Fan readouts unavailable.")
        return FanControls.parseReadouts(names, result)
    }
    override fun fanStatus(fan: String): FanStatus {
        require(FanControls.validFan(fan)) { "Unsupported manual fan." }
        val result=request("printer/objects/query",mapOf("webhooks" to "state", "print_stats" to "state", "toolhead" to "extruder", "configfile" to "settings", fan to "speed")) as? JSONObject
            ?: throw ApiFailure("Fan state unavailable.")
        return FanControls.parse(fan,result)
    }
    override fun toolStatus(): ToolStatus {
        val result=request("printer/objects/query",mapOf("webhooks" to "state", "print_stats" to "state", "toolhead" to "extruder", "configfile" to "settings")) as? JSONObject
            ?: throw ApiFailure("Tool state unavailable.")
        return ToolControls.parse(result)
    }
    override fun pandaBreathStatus(): PandaBreathStatus {
        // Which heater_generic object (if any) is Panda Breath isn't known ahead of time, so the
        // catalog is read first - same two-step shape FanControls.catalog()+parse() already uses.
        val objects = (request("printer/objects/list") as? JSONObject)?.getJSONArray("objects") ?: throw ApiFailure("Object catalog unavailable.")
        require(objects.length()<=10000) {"Object catalog is too large."}
        val genericHeaters = (0 until objects.length()).mapNotNull { objects.opt(it) as? String }.filter { it.startsWith("heater_generic ") }
        require(genericHeaters.size<=64) {"Too many generic heaters."}
        val fields = mutableMapOf("webhooks" to "state", "print_stats" to "state", "panda_breath" to "")
        genericHeaters.forEach { fields[it] = "temperature,target" }
        val result = request("printer/objects/query", fields) as? JSONObject ?: throw ApiFailure("Panda Breath status unavailable.")
        // The Auto/Dry gcodes are extras-registered, so probing gcode/help keeps the offered
        // controls honest about what this firmware actually speaks - matches Helix's own
        // hasGcode(gcodeHelp, ...) feature-detection rather than assuming every Klipper install has them.
        val help = request("printer/gcode/help") as? JSONObject ?: JSONObject()
        return PandaBreathControls.parse(result, help)
    }
    // moonraker-spoolman may not be configured at all (404) or configured but pointed at an
    // unreachable Spoolman server - both are ordinary "not available" states here, honest-empty
    // rather than surfaced as an error, matching TimelapseReader's precedent for an optional,
    // separately-installed Moonraker component.
    override fun spoolmanInventory(): SpoolmanInventory = try {
        val idResult = request("server/spoolman/spool_id") as? JSONObject
        val activeId = (idResult?.opt("spool_id") as? Number)?.toInt()
        val proxyBody = JSONObject().put("request_method", "GET").put("path", "/v1/spool").put("query", "allow_archived=true")
        val proxyResult = request("server/spoolman/proxy", jsonBody = proxyBody) as? JSONObject ?: throw ApiFailure("Spoolman unavailable.")
        if (proxyResult.has("error") && !proxyResult.isNull("error")) throw ApiFailure("Spoolman rejected the request.")
        SpoolmanInventory(true, activeId, Spoolman.parseSpools(proxyResult.opt("response")))
    } catch (e: ApiFailure) { SpoolmanInventory(false, null, emptyList()) }
    override fun aceStatus(): AceStatus {
        // "" (all fields) rather than a named field list: the ace object's shape varies by
        // multiACE firmware version (v0.99+'s aces[] vs older single-device fields) and AceControls
        // itself decides what to read, same reasoning as Bespok3dReader's own full-object reads.
        val result = request("printer/objects/query", mapOf("webhooks" to "state", "print_stats" to "state", "ace" to "")) as? JSONObject
            ?: throw ApiFailure("multiACE status unavailable.")
        return AceControls.parse(result)
    }
    override fun snapshot(): PrinterSnapshot {
        val info = request("server/info") as? JSONObject ?: throw ApiFailure("Invalid server information.")
        if (!info.optBoolean("klippy_connected") || info.optString("klippy_state") != "ready") return PrinterSnapshot(false, info.optString("klippy_state", "not ready"))
        try {
            // Every possible toolhead's temperature comes back in this one query, rather than a
            // first request to discover which one is active followed by a second to read its
            // temperature coherently. That two-step version was the actual mechanism behind a
            // real report: a Snapmaker U1/PAXX (multi-toolhead, routinely active on
            // extruder1/2/3) dropping its connection repeatedly over Tailscale on 5G - never on
            // WiFi, and never on a single-extruder printer - because every single poll cost it a
            // third sequential HTTP round-trip that a generic printer's poll never paid, each
            // with its own timeout budget, on a higher and more latency-variable link. Querying
            // every extruderN up front is still exactly one atomic call, so it is strictly more
            // coherent than the old two-step read, not less - there is no window between "see
            // who's active" and "read their temperature" for the tool to have changed in.
            val fields = mapOf("webhooks" to "state", "toolhead" to "extruder", "print_stats" to "state,filename,print_duration,info", "virtual_sdcard" to "progress",
                "extruder" to "temperature,target", "extruder1" to "temperature,target", "extruder2" to "temperature,target", "extruder3" to "temperature,target",
                "heater_bed" to "temperature,target")
            val result = request("printer/objects/query", fields) as JSONObject
            return parseSnapshot(result)
        } catch(e: org.json.JSONException) { throw ApiFailure("Printer state is incomplete.") }
    }
    override fun catalog(): Catalog {
        val warnings = mutableListOf<String>()
        var details = emptyList<FileInfo>()
        val files = try {
            val list = request("server/files/list", mapOf("root" to "gcodes")) as JSONArray
            details = (0 until list.length()).map { list.getJSONObject(it) }.map { FileInfo(it.getString("path"), it.finiteNonnegative("size")?.toLong(), it.finiteNonnegative("modified")) }
            details.map { it.path }.filter { it.endsWith(".gcode", true) || it.endsWith(".gco", true) }.distinct().sorted()
        } catch(e: Exception) { warnings.add("File list unavailable."); emptyList() }
        val macros = try {
            val list = (request("printer/objects/list") as JSONObject).getJSONArray("objects")
            (0 until list.length()).map { list.getString(it) }.filter { it.startsWith("gcode_macro ") }.map { it.removePrefix("gcode_macro ") }
                .filter { Regex("[A-Za-z][A-Za-z0-9_]*").matches(it) }.sorted()
        } catch(e: Exception) { warnings.add("Macro list unavailable."); emptyList() }
        val cameras = try {
            cameras()
        } catch(e: Exception) { warnings.add("Camera configuration unavailable."); emptyList() }
        return Catalog(files, macros, cameras, warnings, details)
    }
    override fun cameras(): List<Camera> {
        val list = (request("server/webcams/list") as JSONObject).getJSONArray("webcams")
        return (0 until list.length()).map { list.getJSONObject(it) }.filter { it.optBoolean("enabled", true) && (it.optString("snapshot_url").isNotBlank() || it.optString("stream_url").isNotBlank()) }
                .map { Camera(it.optString("name", "Camera"), it.optString("snapshot_url"), it.optString("stream_url"), it.optString("service"), it.optString("uid").ifBlank { it.optString("name", "Camera") }) }
    }
    override fun metadata(filename: String): FileMetadata {
        val result = request("server/files/metadata", mapOf("filename" to filename)) as? JSONObject ?: throw ApiFailure("Metadata unavailable.")
        val thumbnails = result.optJSONArray("thumbnails")
        val thumb = thumbnails?.let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .filter { it.optInt("width") in 1..1024 && it.optInt("height") in 1..1024 }
            .maxByOrNull { it.optInt("width") }?.optString("relative_path")?.takeIf { it.isNotBlank() } }
        val parent = filename.substringBeforeLast('/', "")
        return FileMetadata(filename, result.finiteNonnegative("estimated_time"), result.optInt("layer_count").takeIf { it > 0 },
            result.finiteNonnegative("filament_total"), result.finiteNonnegative("filament_weight_total"), result.optString("slicer"),
            thumb?.let { if(parent.isBlank()) it else "$parent/$it" })
    }
    override fun history(start: Int): HistoryPage {
        val result = request("server/history/list", mapOf("start" to start.coerceAtLeast(0).toString(), "limit" to "50", "order" to "desc")) as? JSONObject ?: throw ApiFailure("History unavailable.")
        val jobs = result.getJSONArray("jobs")
        return HistoryPage((0 until minOf(jobs.length(), 50)).map { jobs.getJSONObject(it) }.map {
            PrintJob(it.getString("job_id"), it.optString("filename", "Unknown file"), it.optString("status", "unknown"),
                it.finiteNonnegative("start_time"), it.finiteNonnegative("print_duration"), it.finiteNonnegative("filament_used"))
        }.distinctBy { it.id }, minOf(jobs.length(),50))
    }
    override fun configFile(): ConfigFileContent {
        val filename = "printer.cfg"
        val target = base.newBuilder().addPathSegments("server/files/config").addPathSegment(filename).build()
        client.newCall(Request.Builder().url(target).build()).execute().use { response ->
            if(!response.isSuccessful) throw ApiFailure("Could not read $filename (HTTP ${response.code}). Check the configured main config filename.")
            val source = response.body?.source() ?: throw ApiFailure("Empty config file.")
            source.request(ConfigFile.MAX_BYTES.toLong() + 1)
            val raw = source.buffer.readByteArray()
            if(raw.size > ConfigFile.MAX_BYTES) throw ApiFailure("Config file exceeds the supported size.")
            return ConfigFile.split(filename, String(raw, Charsets.UTF_8))
        }
    }
    override fun backupConfig(filename: String): String {
        require(filename == "printer.cfg") { "Only printer.cfg backups are supported." }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val dest = "$filename.bak-$stamp"
        val body = JSONObject().put("source", "config/$filename").put("dest", "config/$dest")
        val result = request("server/files/copy", jsonBody = body) as? JSONObject ?: throw ApiFailure("Backup failed.")
        val path = result.optJSONObject("item")?.optString("path")
        require(path == dest) { "Unexpected backup acknowledgement; inspect files before saving." }
        return dest
    }
    override fun writeConfig(filename: String, content: String) {
        require(filename == "printer.cfg") { "Only printer.cfg writes are supported." }
        require(content.length <= ConfigFile.MAX_BYTES) { "Configuration exceeds the supported size." }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("root", "config")
            .addFormDataPart("file", filename, content.toByteArray(Charsets.UTF_8).toRequestBody("text/plain".toMediaType()))
            .build()
        client.newCall(Request.Builder().url(base.resolve("server/files/upload")!!).post(body).build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiFailure("Configuration save failed (HTTP ${response.code}). The backup is unaffected.")
            val raw = response.body?.source()?.let { it.request(1_000_001); it.buffer.readByteArray() } ?: throw ApiFailure("Empty save acknowledgement.")
            require(raw.size <= 1_000_000) { "Save acknowledgement exceeds the supported size." }
            val result = JSONObject(String(raw, Charsets.UTF_8)).optJSONObject("result") ?: throw ApiFailure("Unexpected save acknowledgement.")
            val path = result.optJSONObject("item")?.optString("path")
            require(path == filename) { "Unexpected save acknowledgement; inspect the file before restarting." }
        }
    }
    override fun thumbnail(path: String): ByteArray = fileBytes("gcodes", path, 2_000_000, "thumbnail")
    private fun validateFilePath(path: String, message: String) {
        if(path.startsWith('/') || path.split('/').any { it == ".." || it == "." } || path.contains('\\')) throw ApiFailure(message)
    }
    // Shared by thumbnail() (root=gcodes) and timelapseThumbnail() (root=timelapse): both are a
    // small image fully read into memory, unlike a timelapse video which streams via its own URL.
    private fun fileBytes(root: String, path: String, maxBytes: Int, label: String): ByteArray {
        validateFilePath(path, "Invalid $label path.")
        val target = base.newBuilder().addPathSegments("server/files/$root").apply { path.split('/').forEach { addPathSegment(it) } }.build()
        client.newCall(Request.Builder().url(target).build()).execute().use { response ->
            if(!response.isSuccessful) throw ApiFailure("${label.replaceFirstChar { it.uppercase() }} unavailable.")
            val source = response.body?.source() ?: throw ApiFailure("Empty $label.")
            source.request((maxBytes + 1).toLong())
            return source.buffer.readByteArray().also { if(it.size > maxBytes) throw ApiFailure("${label.replaceFirstChar { it.uppercase() }} too large.") }
        }
    }
    override fun image(camera: Camera): ByteArray {
        cameraResponse(client, address, camera.snapshot).use { response ->
            if (!response.isSuccessful) throw ApiFailure("Camera unavailable (HTTP ${response.code}).")
            val data = response.body?.source()?.let { it.request(4_000_001); it.buffer.readByteArray() } ?: throw ApiFailure("Camera returned no image.")
            if (data.size > 4_000_000) throw ApiFailure("Camera image is too large.")
            return data
        }
    }
    override fun command(command: PrinterCommand) {
        // No retries or redirects: lost acknowledgement leaves the result unknown.
        val result = request(command.path, command.arguments, mutate = true, httpClient = commandClient)
        if (result != "ok") throw ApiFailure("Unrecognized command acknowledgement; inspect printer state.")
    }
    override fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); commandClient.dispatcher.cancelAll(); commandClient.connectionPool.evictAll() }
}

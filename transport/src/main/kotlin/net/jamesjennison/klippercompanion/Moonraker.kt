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

class Moonraker(address: String, rawApiKey: String = "") : PrinterService, ConsoleReader, HeaterReader, FanReader, MeshReader, ToolheadReader, FanReadoutReader, ConfigFileReader, ConfigWriter, SpeedFlowReader, MacroReader, LedReader, TimelapseReader, ToolReader, Bespok3dReader, PandaBreathReader, SpoolmanReader, AceReader, FilamentSlotReader {
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
        fun activeExtruder(status: JSONObject): String = MoonrakerRules.activeExtruder(status)
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

        /**
         * How a print is started on [kind]. A Snapmaker U1 (stock or PAXX) starts through Snapmaker's own
         * `server.files.start_local_print` with `bed_level` on, as Snapmaker's slicer does: the U1's Moonraker turns each
         * option into a parameter of `SDCARD_PRINT_FILE_WITH_PARAMETERS`, which sets `print_task_config.auto_bed_leveling`,
         * and the firmware runs the start G-code's adaptive `BED_MESH_CALIBRATE` only when that is set (Snapmaker/u1-moonraker
         * a308cfa, snapmakercloud.py and klippy_apis.start_print_advanced). A plain `printer/print/start` left it at the
         * printer's last value; found on a real PAXX U1, where the adaptive mesh was skipped. Every other printer: [start].
         */
        fun start(file: String, kind: PrinterKind): PrinterCommand =
            if (kind == PrinterKind.SNAPMAKER_U1 || kind == PrinterKind.SNAPMAKER_U1_PAXX)
                PrinterCommand("Start $file", U1_START_LOCAL_PRINT, mapOf("filename" to file, "bed_level" to "1"), setOf("standby", "complete", "cancelled", "error"))
            else start(file)

        /** Marks the U1 start in a PrinterCommand; sent as JSON-RPC on Moonraker's websocket (the U1 refuses it over HTTP). */
        const val U1_START_LOCAL_PRINT = "server/files/start_local_print"
        fun macro(name: String): PrinterCommand = MoonrakerRules.macro(name)
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
    override fun renderTimelapse(): TimelapseRenderResult {
        val result = request("machine/timelapse/render", mutate = true, httpClient = commandClient) as? JSONObject
            ?: throw ApiFailure("Timelapse render request failed.")
        return TimelapseRenderResult(result.optString("status", "error"), result.optString("msg", ""))
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
    override fun supportsBedMeshCalibration(): Boolean {
        val listed = request("printer/objects/list") as? JSONObject ?: return false
        val objects = listed.optJSONArray("objects") ?: return false
        return (0 until objects.length()).any { objects.optString(it) == "bed_mesh" }
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
    // A filament changer's lanes (FilamentLanes, the desktop's and Web App's rules): Moonraker's lane_data database
    // namespace (AFC - the Elegoo CANVAS on COSMOS), else Happy Hare's mmu object. Read-only. A printer without either
    // (no such namespace: Moonraker answers 404) reports no slots rather than an error.
    override fun filamentSlots(): FilamentSlotStatus {
        val objects = (request("printer/objects/query", mapOf("AFC" to "current_load", "mmu" to FilamentLanes.MMU_FIELDS,
            "print_task_config" to FilamentLanes.U1_TASK_CONFIG_FIELDS, "toolhead" to "extruder")) as? JSONObject)?.optJSONObject("status")
        val laneData = try { request("server/database/item", mapOf("namespace" to "lane_data")) as? JSONObject } catch (e: ApiFailure) { null }
        return FilamentLanes.read(laneData, objects)
    }
    // Confirmed live against a real Elegoo Centauri Carbon running COSMOS: printer/info's "app"
    // field is "OpenCentauri Cosmos" and "software_version" is e.g. "Release - 26.08.0". A
    // Snapmaker U1 has no "app" field at all (optString defaults it blank) and a plain
    // "software_version" like "1.6.0.267_20260815150420". Never called by snapshot()'s own
    // regular poll - only right before a Centauri-Carbon-specific slice, deliberately, per
    // FirmwareIdentity.kt's header comment.
    override fun firmwareIdentity(): FirmwareIdentity {
        val info = request("printer/info") as? JSONObject ?: throw ApiFailure("Firmware identity unavailable.")
        return FirmwareIdentity(app = info.optString("app", ""), version = info.optString("software_version", ""))
    }
    /**
     * Read-only facts the network scan also reads (PrinterScanner): the hostname, PAXX's `extended/` config folder and
     * Klipper's AFC object, classified with the same PrinterDiscovery rules. Used by Test Mode to confirm the firmware
     * family before a suite runs. A part that can't be read reads as absent, which never classifies a printer as PAXX or
     * CANVAS by mistake.
     */
    fun discoveryDetails(): MoonrakerDiscoveryDetails {
        val info = request("printer/info") as? JSONObject ?: throw ApiFailure("Printer information unavailable.")
        val paxx = try { PrinterDiscovery.hasExtendedConfig(request("server/files/list", mapOf("root" to "config")) as? JSONArray) } catch (e: Exception) { false }
        val afc = try { PrinterDiscovery.hasAfcObject(request("printer/objects/list") as? JSONObject) } catch (e: Exception) { false }
        return MoonrakerDiscoveryDetails(info.optString("hostname"), paxx, afc)
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
        if (command.path == U1_START_LOCAL_PRINT) { startLocalPrint(command); return }
        // No retries or redirects: lost acknowledgement leaves the result unknown.
        val result = request(command.path, command.arguments, mutate = true, httpClient = commandClient)
        if (result != "ok") throw ApiFailure("Unrecognized command acknowledgement; inspect printer state.")
    }
    /**
     * The U1's start_local_print, as one JSON-RPC request on Moonraker's websocket, sent once. The U1 scans the file's
     * metadata before starting, so the reply can take a while. A reply with an error, or `state` other than success,
     * means the printer refused ([U1StartRefused]); no reply leaves the outcome unknown (ApiFailure), never retried.
     */
    private fun startLocalPrint(command: PrinterCommand) {
        val file = command.arguments["filename"]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("No file to start.")
        val id = java.security.SecureRandom().nextInt(Int.MAX_VALUE)
        val options = JSONObject().apply { command.arguments["bed_level"]?.toIntOrNull()?.let { put("bed_level", it) } }
        val request = JSONObject().put("jsonrpc", "2.0").put("method", "server.files.start_local_print").put("id", id)
            .put("params", JSONObject().put("path", file).put("print_plate", 1).put("options", options))
        val reply = java.util.concurrent.ArrayBlockingQueue<Result<JSONObject>>(1)
        val socket = commandClient.newWebSocket(Request.Builder().url(base.resolve("websocket")!!).build(), object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) { webSocket.send(request.toString()) }
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                val msg = try { JSONObject(text) } catch (e: org.json.JSONException) { return }
                if (msg.optInt("id", -1) == id) reply.offer(Result.success(msg))
            }
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                reply.offer(Result.failure(if (response?.code == 401 || response?.code == 403) U1StartRefused("Moonraker requires authentication for this printer.") else t))
            }
        })
        try {
            val msg = reply.poll(90, java.util.concurrent.TimeUnit.SECONDS)?.getOrElse { throw ApiFailure("Lost the connection while starting the print (${it.message}); check the printer before trying again.") }
                ?: throw ApiFailure("No reply from the printer within 90 s; check whether the print started before trying again.")
            msg.optJSONObject("error")?.let { throw U1StartRefused("The printer refused to start: ${it.optString("message", "error")}") }
            val result = msg.optJSONObject("result") ?: throw ApiFailure("Unrecognized reply to the print start; check the printer.")
            if (result.optString("state") != "success") throw U1StartRefused("The printer refused to start: ${result.optString("message", result.optString("state"))}")
        } finally { socket.close(1000, null) }
    }

    override fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); commandClient.dispatcher.cancelAll(); commandClient.connectionPool.evictAll() }
}

/** See [Moonraker.discoveryDetails]. */
data class MoonrakerDiscoveryDetails(val hostname: String, val paxxExtendedConfig: Boolean, val afc: Boolean)

/** The U1 answered a start request with a refusal: nothing started. Distinct from a lost reply (outcome unknown). */
class U1StartRefused(message: String) : IOException(message)

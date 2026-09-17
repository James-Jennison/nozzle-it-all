package net.jamesjennison.klippercompanion

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
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
data class Camera(val name: String, val snapshot: String, val stream: String = "", val service: String = "", val id: String = name)
data class Catalog(val files: List<String>, val macros: List<String>, val cameras: List<Camera>, val warnings: List<String>, val fileInfo: List<FileInfo> = emptyList())
data class PrinterCommand(val title: String, val path: String, val arguments: Map<String, String> = emptyMap(), val allowedStates: Set<String> = emptySet(), val heaterRequest: HeaterRequest? = null, val fanRequest: FanRequest? = null, val speedFlowRequest: SpeedFlowRequest? = null, val macroRequest: MacroRequest? = null)

interface PrinterService {
    val address: String
    fun snapshot(): PrinterSnapshot
    fun catalog(): Catalog
    fun cameras(): List<Camera> = emptyList()
    fun image(camera: Camera): ByteArray
    fun metadata(filename: String): FileMetadata = throw ApiFailure("Metadata unavailable.")
    fun history(start: Int): HistoryPage = throw ApiFailure("History unavailable.")
    fun thumbnail(path: String): ByteArray = throw ApiFailure("Thumbnail unavailable.")
    fun heaterStatus(heater: String): HeaterStatus = throw ApiFailure("Heater controls unavailable.")
    fun fanStatus(fan: String): FanStatus = throw ApiFailure("Fan controls unavailable.")
    fun meshStatus(): BedMeshStatus = throw ApiFailure("Bed mesh unavailable.")
    fun toolheadTemperatures(): List<ToolheadTemperature> = throw ApiFailure("Toolhead temperatures unavailable.")
    fun fanReadouts(): List<FanReadout> = throw ApiFailure("Fan readouts unavailable.")
    fun configFile(): ConfigFileContent = throw ApiFailure("Config file unavailable.")
    fun speedFlowStatus(): SpeedFlowStatus = throw ApiFailure("Speed/flow status unavailable.")
    fun macroStatus(name: String): MacroStatus = throw ApiFailure("Macro status unavailable.")
    fun command(command: PrinterCommand)
    fun close()
}
class Moonraker(address: String) : PrinterService, ConsoleReader, HeaterReader, FanReader, MeshReader, ToolheadReader, FanReadoutReader, ConfigFileReader, SpeedFlowReader, MacroReader {
    val base: HttpUrl = parseAddress(address)
    override val address: String get() = base.toString()
    private val client = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

    companion object {
        internal fun activeExtruder(status: JSONObject): String = status.optJSONObject("toolhead")?.optString("extruder", "")
            ?.takeIf { it.length <= 32 && Regex("extruder[0-9]*").matches(it) } ?: ""
        fun parseAddress(address: String): HttpUrl {
            val url = address.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter an http:// or https:// printer address.")
            if(url.scheme == "http") {
                val parts = url.host.split('.').mapNotNull { it.toIntOrNull() }
                val privateV4 = url.host.split('.').size == 4 && parts.size == 4 && parts.all { it in 0..255 } && (parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31))
                require(privateV4 || url.host == "localhost" || url.host == "::1" || url.host.endsWith(".local")) { "HTTP requires a local IPv4 address, localhost or .local name. Use HTTPS for other addresses." }
            }
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
    private fun request(path: String, args: Map<String, String> = emptyMap(), mutate: Boolean = false): Any {
        val builder = Request.Builder().url(url(path,args))
        if (mutate) builder.post("".toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw ApiFailure("Moonraker requires authentication. Credential entry is not supported in this MVP.")
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
    override fun snapshot(): PrinterSnapshot {
        val info = request("server/info") as? JSONObject ?: throw ApiFailure("Invalid server information.")
        if (!info.optBoolean("klippy_connected") || info.optString("klippy_state") != "ready") return PrinterSnapshot(false, info.optString("klippy_state", "not ready"))
        try {
            val fields = mapOf("webhooks" to "state", "toolhead" to "extruder", "print_stats" to "state,filename,print_duration,info", "virtual_sdcard" to "progress", "extruder" to "temperature,target", "heater_bed" to "temperature,target")
            val initial = request("printer/objects/query", fields) as JSONObject
            val active = activeExtruder(initial.getJSONObject("status"))
            // Read the selected tool and toolhead together. If it switches again, parsing
            // yields unknown temperatures rather than attributing the previous tool's heat.
            val coherent = if(active.isNotEmpty() && active != "extruder")
                request("printer/objects/query", fields + (active to "temperature,target")) as JSONObject else initial
            return parseSnapshot(coherent)
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
    override fun thumbnail(path: String): ByteArray {
        if(path.startsWith('/') || path.split('/').any { it == ".." || it == "." } || path.contains('\\')) throw ApiFailure("Invalid thumbnail path.")
        val target = base.newBuilder().addPathSegments("server/files/gcodes").apply { path.split('/').forEach { addPathSegment(it) } }.build()
        client.newCall(Request.Builder().url(target).build()).execute().use { response ->
            if(!response.isSuccessful) throw ApiFailure("Thumbnail unavailable.")
            val source = response.body?.source() ?: throw ApiFailure("Empty thumbnail.")
            source.request(2_000_001)
            return source.buffer.readByteArray().also { if(it.size > 2_000_000) throw ApiFailure("Thumbnail too large.") }
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
        val result = request(command.path, command.arguments, mutate = true)
        if (result != "ok") throw ApiFailure("Unrecognized command acknowledgement; inspect printer state.")
    }
    override fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
}

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
data class PrinterSnapshot(val ready: Boolean, val state: String, val filename: String = "", val progress: Float = 0f, val nozzle: Double? = null, val nozzleTarget: Double? = null, val bed: Double? = null, val bedTarget: Double? = null)
data class Camera(val name: String, val snapshot: String, val stream: String = "", val service: String = "")
data class Catalog(val files: List<String>, val macros: List<String>, val cameras: List<Camera>, val warnings: List<String>)
data class PrinterCommand(val title: String, val path: String, val arguments: Map<String, String> = emptyMap(), val allowedStates: Set<String> = emptySet())

interface PrinterService {
    val address: String
    fun snapshot(): PrinterSnapshot
    fun catalog(): Catalog
    fun image(camera: Camera): ByteArray
    fun command(command: PrinterCommand)
    fun close()
}
class Moonraker(address: String) : PrinterService {
    val base: HttpUrl = parseAddress(address)
    override val address: String get() = base.toString()
    private val client = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

    companion object {
        fun parseAddress(address: String): HttpUrl {
            val url = address.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter an http:// or https:// printer address.")
            if(url.scheme == "http") {
                val parts = url.host.split('.').mapNotNull { it.toIntOrNull() }
                val privateV4 = parts.size == 4 && parts.all { it in 0..255 } && (parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31))
                require(privateV4 || url.host == "localhost" || url.host == "::1" || url.host.endsWith(".local")) { "HTTP requires a local IPv4 address, localhost or .local name. Use HTTPS for other addresses." }
            }
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "Use a base address without credentials, query or fragment." }
            return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build()
        }
        fun cameraUrl(address: String, cameraPath: String): HttpUrl {
            val base = parseAddress(address)
            val target = base.resolve(cameraPath) ?: throw ApiFailure("Invalid camera address.")
            if (target.host != base.host || target.username.isNotEmpty() || target.password.isNotEmpty() || target.fragment != null) throw ApiFailure("Camera must use the printer host without URL credentials.")
            parseAddress(target.newBuilder().query(null).build().toString())
            return target
        }
        fun parseSnapshot(result: JSONObject): PrinterSnapshot {
            val status = result.getJSONObject("status")
            val stats = status.optJSONObject("print_stats")
            val ready = status.getJSONObject("webhooks").getString("state") == "ready"
            fun number(obj: String, field: String): Double? = status.optJSONObject(obj)?.optDouble(field)?.takeIf { it.isFinite() }
            val state = if (ready) stats?.optString("state", "unknown") ?: "unknown" else "not ready"
            return PrinterSnapshot(ready, state, stats?.optString("filename", "") ?: "",
                (number("virtual_sdcard", "progress") ?: 0.0).coerceIn(0.0, 1.0).toFloat(),
                number("extruder", "temperature"), number("extruder", "target"), number("heater_bed", "temperature"), number("heater_bed", "target"))
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
    override fun snapshot(): PrinterSnapshot {
        val info = request("server/info") as? JSONObject ?: throw ApiFailure("Invalid server information.")
        if (!info.optBoolean("klippy_connected") || info.optString("klippy_state") != "ready") return PrinterSnapshot(false, info.optString("klippy_state", "not ready"))
        try {
            return parseSnapshot(request("printer/objects/query", mapOf("webhooks" to "state", "print_stats" to "state,filename", "virtual_sdcard" to "progress", "extruder" to "temperature,target", "heater_bed" to "temperature,target")) as JSONObject)
        } catch(e: org.json.JSONException) { throw ApiFailure("Printer state is incomplete.") }
    }
    override fun catalog(): Catalog {
        val warnings = mutableListOf<String>()
        val files = try {
            val list = request("server/files/list", mapOf("root" to "gcodes")) as JSONArray
            (0 until list.length()).map { list.getJSONObject(it).getString("path") }.filter { it.endsWith(".gcode", true) || it.endsWith(".gco", true) }.sorted()
        } catch(e: Exception) { warnings.add("File list unavailable."); emptyList() }
        val macros = try {
            val list = (request("printer/objects/list") as JSONObject).getJSONArray("objects")
            (0 until list.length()).map { list.getString(it) }.filter { it.startsWith("gcode_macro ") }.map { it.removePrefix("gcode_macro ") }
                .filter { Regex("[A-Za-z][A-Za-z0-9_]*").matches(it) }.sorted()
        } catch(e: Exception) { warnings.add("Macro list unavailable."); emptyList() }
        val cameras = try {
            val list = (request("server/webcams/list") as JSONObject).getJSONArray("webcams")
            (0 until list.length()).map { list.getJSONObject(it) }.filter { it.optBoolean("enabled", true) && (it.optString("snapshot_url").isNotBlank() || it.optString("stream_url").isNotBlank()) }
                .map { Camera(it.optString("name", "Camera"), it.optString("snapshot_url"), it.optString("stream_url"), it.optString("service")) }
        } catch(e: Exception) { warnings.add("Camera configuration unavailable."); emptyList() }
        return Catalog(files, macros, cameras, warnings)
    }
    override fun image(camera: Camera): ByteArray {
        val target = cameraUrl(address, camera.snapshot)
        if (target.username.isNotEmpty() || target.password.isNotEmpty()) throw ApiFailure("Camera credentials in URLs are not supported.")
        client.newCall(Request.Builder().url(target).header("Cache-Control", "no-cache").build()).execute().use { response ->
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

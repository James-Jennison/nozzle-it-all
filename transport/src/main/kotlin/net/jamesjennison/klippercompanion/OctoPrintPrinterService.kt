package net.jamesjennison.klippercompanion

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// OctoPrint (PrinterKind.OCTOPRINT): the REST API documented at docs.octoprint.org/en/master/api. Authentication is the per-user or
// application API key in the X-Api-Key header. Scoped like the Prusa Link integration: live status and temperatures, the file list,
// upload-and-print of plain G-code, and pause / resume / cancel. Verified against a real OctoPrint 1.11 server driving its virtual
// printer (see OctoPrintPrinterServiceTest / OctoPrintLiveTest); no physical printer behind it.
class OctoPrintPrinterService(host: String, private val apiKey: String) : PrinterService {
    private val base: HttpUrl = (if (host.contains("://")) host else "http://$host/").let {
        it.toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a valid OctoPrint address.")
    }
    override val address: String get() = base.toString()
    private val client = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    // A G-code file can be many megabytes; the status client's short timeouts would cut it off.
    private val uploadClient = client.newBuilder().writeTimeout(120, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()

    private fun url(vararg segments: String, query: Map<String, String> = emptyMap()) = base.newBuilder().apply {
        segments.forEach { addPathSegment(it) }; query.forEach { (k, v) -> addQueryParameter(k, v) }
    }.build()

    private class Reply(val code: Int, val json: Any?)

    private fun send(request: Request, http: OkHttpClient = client): Reply = try {
        http.newCall(request.newBuilder().header("X-Api-Key", apiKey).header("Accept", "application/json").build()).execute().use { r ->
            when (r.code) {
                401, 403 -> throw ApiFailure("OctoPrint rejected the API key. Create one under OctoPrint > Settings > Application Keys (or your user's API key).")
                204 -> Reply(204, null)
                else -> {
                    val raw = r.body?.source()?.let { it.request(2_000_001); it.buffer.readByteArray() } ?: ByteArray(0)
                    if (raw.size > 2_000_000) throw ApiFailure("The OctoPrint response is larger than supported.")
                    val text = String(raw, Charsets.UTF_8)
                    Reply(r.code, if (text.isBlank()) null else try { JSONObject(text) } catch (_: org.json.JSONException) { try { JSONArray(text) } catch (_: org.json.JSONException) { null } })
                }
            }
        }
    } catch (e: java.io.IOException) { if (e is ApiFailure) throw e else throw ApiFailure("Could not reach OctoPrint at ${base.host}: ${e.message ?: "connection failed"}") }

    private fun get(vararg segments: String, query: Map<String, String> = emptyMap()) = send(Request.Builder().url(url(*segments, query = query)).build())

    override fun snapshot(): PrinterSnapshot {
        val printer = get("api", "printer", query = mapOf("exclude" to "sd,history"))
        // 409 = OctoPrint is up but is not connected to a printer.
        if (printer.code == 409) return PrinterSnapshot(false, "OctoPrint is not connected to a printer")
        if (printer.code !in 200..299) throw ApiFailure("OctoPrint returned HTTP ${printer.code}.")
        val status = printer.json as? JSONObject ?: throw ApiFailure("OctoPrint sent an unreadable status.")
        val flags = status.optJSONObject("state")?.optJSONObject("flags")
        val job = (get("api", "job").json as? JSONObject)
        val completion = job?.optJSONObject("progress")?.optDouble("completion")?.takeIf { it.isFinite() } ?: 0.0
        val state = when {
            flags == null -> "error"
            flags.optBoolean("error") || flags.optBoolean("closedOrError") -> "error"
            // "pausing" is still printing: OctoPrint drains its queue first, and ignores a resume sent before it reports paused.
            flags.optBoolean("paused") && !flags.optBoolean("pausing") -> "paused"
            flags.optBoolean("printing") || flags.optBoolean("pausing") || flags.optBoolean("cancelling") || flags.optBoolean("finishing") || flags.optBoolean("resuming") -> "printing"
            flags.optBoolean("operational") && completion >= 100.0 -> "complete"
            flags.optBoolean("operational") -> "standby"
            else -> "error"
        }
        val temps = status.optJSONObject("temperature")
        fun temp(part: String, field: String) = temps?.optJSONObject(part)?.optDouble(field)?.takeIf { it.isFinite() }
        val file = job?.optJSONObject("job")?.optJSONObject("file")
        val name = file?.optString("display")?.takeIf { it.isNotBlank() && it != "null" } ?: file?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }.orEmpty()
        return PrinterSnapshot(state != "error", state, name, (completion / 100.0).coerceIn(0.0, 1.0).toFloat(),
            temp("tool0", "actual"), temp("tool0", "target"), temp("bed", "actual"), temp("bed", "target"),
            job?.optJSONObject("progress")?.optDouble("printTime")?.takeIf { it.isFinite() }, null, null, "extruder")
    }

    override fun catalog(): Catalog {
        val warnings = mutableListOf<String>()
        val files = try {
            val listing = get("api", "files", "local", query = mapOf("recursive" to "true")).json as? JSONObject
            val out = mutableListOf<String>()
            fun walk(entries: JSONArray?) {
                for (i in 0 until (entries?.length() ?: 0)) {
                    val e = entries!!.optJSONObject(i) ?: continue
                    if (e.optString("type") == "folder") walk(e.optJSONArray("children"))
                    else if (e.optString("type") == "machinecode") e.optString("path").takeIf { it.isNotBlank() }?.let(out::add)
                }
            }
            walk(listing?.optJSONArray("files")); out
        } catch (e: ApiFailure) { warnings.add(e.message ?: "The OctoPrint file list is unavailable."); emptyList() }
        warnings.add("OctoPrint file browsing is the list of G-code on the server; download, preview and thumbnails are not available for this printer kind.")
        return Catalog(files, emptyList(), emptyList(), warnings)
    }

    override fun image(camera: Camera): ByteArray = throw ApiFailure("Camera snapshots are not supported for an OctoPrint printer yet.")

    override fun command(command: PrinterCommand) {
        command.prusaLinkPrintRequest?.let { uploadAndPrint(it); return } // the plain-G-code upload-and-print request both kinds share
        when (command.path) {
            "printer/print/start" -> {
                val file = command.arguments["filename"]?.takeIf(String::isNotBlank) ?: throw ApiFailure("Missing filename.")
                post(url("api", "files", "local", *file.split('/').toTypedArray()), JSONObject().put("command", "select").put("print", true))
            }
            "printer/print/pause" -> post(url("api", "job"), JSONObject().put("command", "pause").put("action", "pause"))
            "printer/print/resume" -> post(url("api", "job"), JSONObject().put("command", "pause").put("action", "resume"))
            "printer/print/cancel" -> post(url("api", "job"), JSONObject().put("command", "cancel"))
            else -> throw ApiFailure("Unsupported command for an OctoPrint printer.")
        }
    }

    private fun post(url: HttpUrl, body: JSONObject) {
        val r = send(Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).build())
        if (r.code !in 200..299) throw ApiFailure(((r.json as? JSONObject)?.optString("error")?.takeIf { it.isNotBlank() }) ?: "OctoPrint refused the command (HTTP ${r.code}).")
    }

    private fun uploadAndPrint(request: PrusaLinkPrintRequest) {
        require(request.remoteName.matches(Regex("[A-Za-z0-9._ -]{1,120}\\.(gcode|gco|g)", RegexOption.IGNORE_CASE))) { "Invalid G-code file name." }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", request.remoteName, request.file.asRequestBody("application/octet-stream".toMediaType()))
            .addFormDataPart("select", "true").addFormDataPart("print", "true").build()
        val r = send(Request.Builder().url(url("api", "files", "local")).post(body).build(), uploadClient)
        if (r.code !in 200..299) throw ApiFailure(((r.json as? JSONObject)?.optString("error")?.takeIf { it.isNotBlank() }) ?: "Could not upload ${request.remoteName} to OctoPrint (HTTP ${r.code}).")
        // OctoPrint stores the file even when it cannot start it (printer busy, paused or not connected) and says so only here.
        val reply = r.json as? JSONObject
        if (reply != null && reply.has("effectivePrint") && !reply.optBoolean("effectivePrint")) throw ApiFailure("OctoPrint stored ${request.remoteName} but did not start it: the printer is busy or not ready.")
    }

    override fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
}

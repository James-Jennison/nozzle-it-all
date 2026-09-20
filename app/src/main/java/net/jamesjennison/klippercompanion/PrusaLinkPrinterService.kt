package net.jamesjennison.klippercompanion

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// PrusaLink (PrinterKind.PRUSA_LINK): the local REST API Prusa's own Buddy firmware (MK4, MK3.9,
// MINI, XL) exposes, documented at prusa3d/Prusa-Link-Web's spec/openapi.yaml. Built fresh from
// that published spec, not ported/referenced from Helix - Helix has no Prusa support at all (see
// PrusaLinkDigestAuth.kt's header). M7's own documentation-tier evidence standard applies: no
// owner-owned Prusa hardware exists to physically verify this against.
//
// Scoped down versus a full PrusaLink client, matching this session's other scoped-down builds:
// file listing is the flat /local root only (no subfolder recursion), and file download/preview/
// metadata (which this app's Files tab also offers) is NOT implemented - those use Moonraker's own
// gcode-metadata format, which PrusaLink doesn't speak. Print start/pause/resume/cancel, live
// status and temperatures are real.
data class PrusaLinkAddress(val host: String)
class PrusaLinkPrinterService(host: String, password: String) : PrinterService {
    private val base: HttpUrl = (if (host.contains("://")) host else "http://$host/").let {
        it.toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a valid Prusa Link address.")
    }
    override val address: String get() = base.toString()
    private val client = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .authenticator(PrusaLinkDigestAuthenticator("maker", password)).build()

    // Takes path segments rather than a pre-joined string so each one goes through
    // HttpUrl.Builder's own percent-encoding: java.net.URLEncoder (tried first) form-encodes a
    // space as "+", which is correct for a query string but wrong in a path segment - PrusaLink's
    // server does not decode "+" back to a space there, so a filename with a space would 404.
    // Caught by PrusaLinkPrinterServiceTest before this shipped, not left as a passing test.
    private fun request(pathSegments: List<String>, method: String = "GET"): Any? {
        val url = base.newBuilder().apply { pathSegments.forEach { addPathSegment(it) } }.build()
        val builder = Request.Builder().url(url)
        if (method != "GET") builder.method(method, if (method == "POST" || method == "PUT") "".toRequestBody("application/octet-stream".toMediaType()) else null)
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 401) throw ApiFailure("Prusa Link rejected the configured password. Copy the current one from the printer's own screen/web UI.")
            if (response.code == 204) return null
            if (!response.isSuccessful) throw ApiFailure("Prusa Link request failed (HTTP ${response.code}).")
            val body = response.body ?: return null
            val raw = body.source().let { it.request(2_000_001); it.buffer.readByteArray() }
            if (raw.size > 2_000_000) throw ApiFailure("Prusa Link response exceeds the supported size.")
            if (raw.isEmpty()) return null
            return try { JSONObject(String(raw, Charsets.UTF_8)) } catch (e: org.json.JSONException) {
                try { JSONArray(String(raw, Charsets.UTF_8)) } catch (e2: org.json.JSONException) { null }
            }
        }
    }
    override fun snapshot(): PrinterSnapshot {
        val status = request(listOf("api", "v1", "status")) as? JSONObject ?: return PrinterSnapshot(false, "not ready")
        val printer = status.optJSONObject("printer") ?: return PrinterSnapshot(false, "not ready")
        val rawState = printer.optString("state", "IDLE")
        val ready = rawState !in setOf("ERROR", "ATTENTION")
        val displayState = when (rawState) {
            "PRINTING" -> "printing"; "PAUSED" -> "paused"; "FINISHED" -> "complete"
            "STOPPED" -> "cancelled"; "ERROR", "ATTENTION" -> "error"; "BUSY" -> "busy"; else -> "standby"
        }
        val statusJob = status.optJSONObject("job")
        val filename = if (statusJob?.has("id") == true) {
            (request(listOf("api", "v1", "job")) as? JSONObject)?.optJSONObject("file")?.let { it.optString("display_name").takeIf(String::isNotBlank) ?: it.optString("name") } ?: ""
        } else ""
        fun finite(field: String) = printer.optDouble(field).takeIf { it.isFinite() }
        return PrinterSnapshot(ready, displayState, filename,
            (statusJob?.optDouble("progress")?.takeIf { it.isFinite() } ?: 0.0).div(100.0).coerceIn(0.0, 1.0).toFloat(),
            finite("temp_nozzle"), finite("target_nozzle"), finite("temp_bed"), finite("target_bed"),
            statusJob?.optDouble("time_printing")?.takeIf { it.isFinite() }, null, null, "extruder")
    }
    override fun catalog(): Catalog {
        val warnings = mutableListOf<String>()
        val files = try {
            val folder = request(listOf("api", "v1", "files", "local")) as? JSONObject
            val children = folder?.optJSONArray("children")
            (0 until (children?.length() ?: 0)).mapNotNull { children?.optJSONObject(it) }
                .filter { it.optString("type") == "PRINT_FILE" }.mapNotNull { it.optString("name").takeIf(String::isNotBlank) }
        } catch (e: Exception) {
            warnings.add(e.message ?: "The Prusa Link file list is unavailable."); emptyList()
        }
        warnings.add("Prusa Link file browsing here is the top-level /local folder only - subfolders, download/preview and thumbnails are not supported for this printer kind.")
        return Catalog(files, emptyList(), emptyList(), warnings)
    }
    override fun image(camera: Camera): ByteArray = throw ApiFailure("Camera snapshots are not supported for a Prusa Link printer yet.")
    override fun command(command: PrinterCommand) {
        when (command.path) {
            // Reuses the same abstract path convention Moonraker.start()/MainActivity's Pause/
            // Resume/Cancel buttons already send to any PrinterService, same pattern
            // BambuPrinterService's own command() interprets differently for its protocol.
            "printer/print/start" -> {
                val filename = command.arguments["filename"]?.takeIf(String::isNotBlank) ?: throw ApiFailure("Missing filename.")
                request(listOf("api", "v1", "files", "local", filename), "POST")
            }
            "printer/print/pause" -> withJobId { id -> request(listOf("api", "v1", "job", id.toString(), "pause"), "PUT") }
            "printer/print/resume" -> withJobId { id -> request(listOf("api", "v1", "job", id.toString(), "resume"), "PUT") }
            "printer/print/cancel" -> withJobId { id -> request(listOf("api", "v1", "job", id.toString()), "DELETE") }
            else -> throw ApiFailure("Unsupported command for a Prusa Link printer.")
        }
    }
    private fun withJobId(action: (Int) -> Unit) {
        val job = request(listOf("api", "v1", "job")) as? JSONObject ?: throw ApiFailure("No active Prusa Link job to control.")
        val id = job.optInt("id", -1)
        if (id < 0) throw ApiFailure("No active Prusa Link job to control.")
        action(id)
    }
    override fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
}

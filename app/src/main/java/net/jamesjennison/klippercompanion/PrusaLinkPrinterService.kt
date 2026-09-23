package net.jamesjennison.klippercompanion

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
/**
 * One already-sliced Prusa Link print job. [file] is a real local file (this app's own sliced
 * .gcode, same shape [BambuPrintRequest] takes for the Bambu side) rather than a content Uri -
 * PrusaLinkPrinterService.uploadAndPrint() needs a real, re-readable body for the PUT upload, and
 * a real Content-Length declared up front.
 */
data class PrusaLinkPrintRequest(val file: File, val remoteName: String = file.name)
class PrusaLinkPrinterService(host: String, password: String) : PrinterService {
    private val base: HttpUrl = (if (host.contains("://")) host else "http://$host/").let {
        it.toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a valid Prusa Link address.")
    }
    override val address: String get() = base.toString()
    private val digestAuthenticator = PrusaLinkDigestAuthenticator("maker", password)
    private val client = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .authenticator(digestAuthenticator).build()
    // A real, separate client for the upload endpoint (PUT /api/v1/files/{storage}/{path}): a
    // multi-hundred-megabyte .gcode file can genuinely take longer than the 4s/6s/8s connect/
    // read/call timeouts above allow for over a real LAN/Wi-Fi link - those are sized for small
    // JSON status/control calls, not a file body. Shares the same digest authenticator (and so
    // the same cached challenge state preemptiveHeader() below relies on) and connection pool.
    private val uploadClient = client.newBuilder()
        .writeTimeout(120, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS)
        .build()

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
            val storage = resolveWritableStorage()
            val folder = request(listOf("api", "v1", "files", storage)) as? JSONObject
            val children = folder?.optJSONArray("children")
            (0 until (children?.length() ?: 0)).mapNotNull { children?.optJSONObject(it) }
                .filter { it.optString("type") == "PRINT_FILE" }.mapNotNull { it.optString("name").takeIf(String::isNotBlank) }
        } catch (e: Exception) {
            warnings.add(e.message ?: "The Prusa Link file list is unavailable."); emptyList()
        }
        warnings.add("Prusa Link file browsing here is the top-level storage folder only - subfolders, download/preview and thumbnails are not supported for this printer kind.")
        return Catalog(files, emptyList(), emptyList(), warnings)
    }
    override fun image(camera: Camera): ByteArray = throw ApiFailure("Camera snapshots are not supported for a Prusa Link printer yet.")
    override fun command(command: PrinterCommand) {
        command.prusaLinkPrintRequest?.let { uploadAndPrint(it); return }
        when (command.path) {
            // Reuses the same abstract path convention Moonraker.start()/MainActivity's Pause/
            // Resume/Cancel buttons already send to any PrinterService, same pattern
            // BambuPrinterService's own command() interprets differently for its protocol.
            "printer/print/start" -> {
                val filename = command.arguments["filename"]?.takeIf(String::isNotBlank) ?: throw ApiFailure("Missing filename.")
                request(listOf("api", "v1", "files", resolveWritableStorage(), filename), "POST")
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

    // Real bug fix (Phase 6, WO-23): every call site here used to hardcode "local" as the target
    // storage - wrong for the printers this integration actually targets (MK4/MK3.9/MINI/XL Buddy
    // firmware, per Prusa-Firmware-Buddy's own source): those only ever expose a writable /usb
    // storage, not /local (an MK4 does report a LOCAL-type entry, but it's the printer's own tiny
    // internal flash, reported read_only). GET /api/v1/storage (prusa3d/Prusa-Link-Web's own
    // documented endpoint) returns the real, current list instead of assuming one - the first
    // available && !read_only entry's own declared `path` (its real storage identifier, e.g.
    // "/usb", per the spec's own Storage schema) is used, so this keeps working whether the
    // printer's writable storage is USB, an SD card, or (rarer) a genuinely writable LOCAL.
    private fun resolveWritableStorage(): String {
        val response = request(listOf("api", "v1", "storage")) as? JSONObject
            ?: throw ApiFailure("Could not read this printer's available storage.")
        val list = response.optJSONArray("storage_list")
        val writable = (0 until (list?.length() ?: 0)).mapNotNull { list?.optJSONObject(it) }
            .firstOrNull { it.optBoolean("available", false) && !it.optBoolean("read_only", true) }
            ?: throw ApiFailure("This printer has no writable storage available (insert a USB drive, or check the printer's own storage settings).")
        return writable.optString("path").removePrefix("/")
    }

    /**
     * Uploads an already-sliced .gcode and asks the printer to print it, in one real PUT (the
     * documented `Print-After-Upload: ?1` header - prusa3d/Prusa-Link-Web's own
     * spec/openapi.yaml) rather than a separate upload-then-start round trip.
     *
     * Attaches a preemptive digest Authorization header (see PrusaLinkDigestAuthenticator's own
     * comment) when this session has already completed one real digest handshake (true for every
     * real call site - `snapshot()`/`catalog()` already ran as part of reaching this screen) - the
     * request body is a real file, re-readable, but there is no reason to make the printer's own
     * (typically slow, embedded) TLS/HTTP stack receive a multi-megabyte body twice when the
     * credentials are already known. Falls back to an ordinary unauthenticated first attempt
     * (letting [PrusaLinkDigestAuthenticator.authenticate] answer the 401 reactively, same as
     * every other call in this class) when no challenge has been seen yet, or if a stale
     * preemptive nonce is itself rejected.
     */
    private fun uploadAndPrint(request: PrusaLinkPrintRequest) {
        val storage = resolveWritableStorage()
        val url = base.newBuilder().apply {
            addPathSegment("api"); addPathSegment("v1"); addPathSegment("files")
            addPathSegment(storage); addPathSegment(request.remoteName)
        }.build()
        val uri = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        val body = request.file.asRequestBody("application/octet-stream".toMediaType())
        fun send(preemptiveAuth: String?): Response {
            val builder = Request.Builder().url(url).put(body)
                .header("Print-After-Upload", "?1").header("Overwrite", "?1")
            preemptiveAuth?.let { builder.header("Authorization", it) }
            return uploadClient.newCall(builder.build()).execute()
        }
        val preemptive = digestAuthenticator.preemptiveHeader("PUT", uri)
        var response = send(preemptive)
        try {
            // A stale preemptive nonce (rare - only if the printer rotated its own nonce between
            // the earlier handshake and this call) surfaces as a 401 that the Authenticator itself
            // won't retry (it never retries a request that already carries an Authorization
            // header - see its own comment) - fall back to one genuinely unauthenticated attempt,
            // which the Authenticator's reactive path handles normally.
            if (response.code == 401 && preemptive != null) {
                response.close()
                response = send(null)
            }
            if (response.code == 401) throw ApiFailure("Prusa Link rejected the configured password. Copy the current one from the printer's own screen/web UI.")
            if (!response.isSuccessful) throw ApiFailure("Could not upload ${request.remoteName} to Prusa Link (HTTP ${response.code}).")
        } finally { response.close() }
    }
    override fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
}

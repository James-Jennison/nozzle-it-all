package net.jamesjennison.klippercompanion

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * PrinterKind.DUET: a Duet board on RepRapFirmware, standalone (the `rr_*` API) or with Duet Software Framework on a
 * single-board computer (the `/machine/...` REST API). The request shapes and the choice between the two are DuetRrf's
 * (ported from upstream OrcaSlicer's Duet print host; docs/upstream/PROVENANCE.md P-0035); this class only carries them.
 * [password] is the board's password (M551), blank for RepRapFirmware's default. RepRapFirmware only takes it in the
 * `rr_connect` query, so it is in that one URL, never in a log or an error message.
 *
 * Offered: a reachability check (upstream reads no printer state, so the state is "unknown") and uploading a sliced file
 * to `0:/gcodes/` (an upload never starts anything on a Duet). Starting it is refused until DuetRrf.START_VERIFIED. No
 * other G-code (pause, resume, cancel, homing, jogging, temperatures) is sent. Built from sources only; not yet run
 * against a real Duet.
 */
class DuetPrinterService(address: String, private val password: String) : PrinterService {
    private val base: HttpUrl = Moonraker.parseAddress(if (address.contains("://")) address else "http://$address/")
    override val address: String get() = base.toString()
    private val client = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    // A G-code file can be many megabytes; the status client's short timeouts would cut it off.
    private val uploadClient = client.newBuilder().writeTimeout(120, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()

    private class Reply(val code: Int, val body: String) { val complete: Boolean get() = code in 200..299 }

    /** One request. Orca's Http: 2xx completes, 400+ is an error; a transport failure is an IOException here. */
    private fun call(request: Request, http: OkHttpClient = client): Reply = http.newCall(request).execute().use { r ->
        val raw = r.body?.source()?.let { s -> s.request(1_000_001); s.buffer.readByteArray() } ?: ByteArray(0)
        if (raw.size > 1_000_000) throw ApiFailure("The Duet's reply is larger than supported.")
        Reply(r.code, String(raw, Charsets.UTF_8))
    }

    private fun unreachable(e: IOException): ApiFailure = e as? ApiFailure ?: ApiFailure("Could not reach the Duet at ${base.host}: ${e.message ?: "connection failed"}")

    /**
     * Duet.cpp:124-165: `rr_connect` first; if it fails (HTTP error or no answer), probe `machine/status` for DSF. The
     * rr_connect URL carries the password, so no message built here includes the URL.
     */
    private fun connect(): DuetRrf.ConnectionType {
        val rrConnect = base.newBuilder().addPathSegment("rr_connect").encodedQuery(DuetRrf.connectQuery(password, DuetRrf.timestamp())).build()
        val rrf = try { call(Request.Builder().url(rrConnect).get().build()).takeIf { it.complete } } catch (e: IOException) { if (e is ApiFailure) throw e; null }
        if (rrf != null) {
            DuetRrf.connectError(rrf.body)?.let { throw ApiFailure(it) }
            return DuetRrf.ConnectionType.RRF
        }
        val dsf = try { call(Request.Builder().url(base.newBuilder().addPathSegment("machine").addPathSegment("status").build()).get().build()) }
            catch (e: IOException) { throw unreachable(e) }
        if (!dsf.complete) throw ApiFailure("The Duet at ${base.host} answered HTTP ${dsf.code} to both the RepRapFirmware and the Duet Software Framework requests.")
        return DuetRrf.ConnectionType.DSF
    }

    /** Duet.cpp:167-182: only an RRF session is closed; a failure doesn't matter (the board times the session out). */
    private fun disconnect(type: DuetRrf.ConnectionType) {
        if (type != DuetRrf.ConnectionType.RRF) return
        runCatching { call(Request.Builder().url(base.newBuilder().addPathSegment("rr_disconnect").build()).get().build()) }
    }

    /** Upstream's connection test (Duet.cpp:37-43): connect, then disconnect. Read-only. */
    override fun snapshot(): PrinterSnapshot {
        val type = connect()
        disconnect(type)
        return DuetRrf.probeSnapshot()
    }

    override fun catalog(): Catalog = Catalog(emptyList(), emptyList(), emptyList(), listOf("Nozzle It All doesn't read a Duet's file list yet."))
    override fun image(camera: Camera): ByteArray = throw ApiFailure("Nozzle It All doesn't show this printer's camera yet.")

    override fun command(command: PrinterCommand) {
        command.prusaLinkPrintRequest?.let { sendAndStart(it); return } // the plain-G-code upload-and-start request the other LAN kinds share
        when (command.path) {
            "printer/print/start" -> {
                val name = command.arguments["filename"]?.takeIf(String::isNotBlank) ?: throw ApiFailure("Missing filename.")
                DuetRrf.requireStartVerified(name, uploaded = false)
                start(DuetRrf.remoteName(name))
            }
            "printer/print/pause", "printer/print/resume", "printer/print/cancel" ->
                throw ApiFailure("Unsupported command for a Duet printer: pause, resume and cancel aren't built yet. Use the printer's screen or Duet Web Control.")
            else -> throw ApiFailure("Unsupported command for a Duet printer.")
        }
    }

    /** Uploads the sliced file; while DuetRrf.START_VERIFIED is false that is all, and the start is refused with the reason. */
    private fun sendAndStart(request: PrusaLinkPrintRequest) {
        val file = request.file
        if (!file.isFile) throw ApiFailure("The sliced file is missing. Slice again.")
        val name = DuetRrf.remoteName(request.remoteName)
        upload(file, name)
        DuetRrf.requireStartVerified(name)
        start(name)
    }

    /** Duet.cpp:57-122: connect, upload (RRF POST raw body / DSF PUT raw file), disconnect. Never starts anything. */
    private fun upload(file: File, name: String) {
        val type = connect()
        try {
            val time = DuetRrf.timestamp()
            val reply = try {
                if (type == DuetRrf.ConnectionType.DSF) {
                    // curl's PUT upload (CURLOPT_UPLOAD) sends no Content-Type.
                    call(Request.Builder().url(base.newBuilder().addEncodedPathSegments(DuetRrf.dsfUploadPath(name)).build()).put(file.asRequestBody(null)).build(), uploadClient)
                } else {
                    // curl's CURLOPT_POSTFIELDS body (Http::set_post_body) goes out as application/x-www-form-urlencoded.
                    val url = base.newBuilder().addPathSegment("rr_upload").encodedQuery(DuetRrf.uploadQuery(name, time)).build()
                    call(Request.Builder().url(url).post(file.asRequestBody("application/x-www-form-urlencoded".toMediaType())).build(), uploadClient)
                }
            } catch (e: IOException) { throw ApiFailure("Could not upload $name: ${unreachable(e).message}") }
            if (!reply.complete) throw ApiFailure("Could not upload $name: the Duet answered (HTTP ${reply.code}).")
            if (!DuetRrf.uploadSucceeded(type, reply.code, reply.body)) throw ApiFailure("Could not upload $name: the Duet reported an error.")
        } finally { disconnect(type) }
    }

    /** Only reached with DuetRrf.START_VERIFIED: M32 through `rr_gcode` (RRF) or `/machine/code` (DSF) (Duet.cpp:239-275). */
    private fun start(name: String) {
        DuetRrf.requireStartVerified(name)
        val type = connect()
        try {
            val request = if (type == DuetRrf.ConnectionType.DSF)
                Request.Builder().url(base.newBuilder().addPathSegment("machine").addPathSegment("code").build())
                    .post(DuetRrf.startCode(name).toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()
            else Request.Builder().url(base.newBuilder().addPathSegment("rr_gcode").encodedQuery(DuetRrf.rrGcodeQuery(name)).build()).get().build()
            val reply = try { call(request) } catch (e: IOException) { throw ApiFailure("The printer may not have started $name: ${unreachable(e).message}") }
            if (!reply.complete) throw ApiFailure("The Duet rejected the print command (HTTP ${reply.code}).")
        } finally { disconnect(type) }
    }

    override fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
}

package net.jamesjennison.klippercompanion

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * PrinterKind.REPETIER: one printer behind a Repetier-Server. The request shapes are RepetierServer's (ported from upstream
 * OrcaSlicer's Repetier host; docs/upstream/PROVENANCE.md P-0035); this class only carries them. [apiKey] is the server's
 * API key (the `X-Api-Key` header only, never a URL or a log); [slug] names the server's printer (PrinterProfile.serial,
 * upstream's "printhost_port"), blank for a server with a single printer.
 *
 * Offered: a reachability check (the server identifies itself and lists the printer; upstream reads no printer state, so
 * the state is "unknown") and uploading a sliced file to the server's model library (`printer/model/<slug>`, which never
 * prints). Starting it is refused until RepetierServer.START_VERIFIED. No other request (pause, stop, G-code,
 * temperatures, motion) is sent. Built from sources only; not yet run against a real server.
 */
class RepetierPrinterService(address: String, private val apiKey: String, private val slug: String) : PrinterService {
    private val base: HttpUrl = Moonraker.parseAddress(if (address.contains("://")) address else "http://$address/")
    override val address: String get() = base.toString()
    private val client = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    // A G-code file can be many megabytes; the status client's short timeouts would cut it off.
    private val uploadClient = client.newBuilder().writeTimeout(120, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()

    private class Reply(val code: Int, val body: String) { val complete: Boolean get() = code in 200..299 }

    private fun url(vararg segments: String): HttpUrl = base.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()

    /** One request with the API key header (Repetier.cpp:179-186). */
    private fun call(builder: Request.Builder, http: OkHttpClient = client): Reply {
        if (apiKey.isBlank()) throw ApiFailure(RepetierServer.MISSING_API_KEY)
        return try {
            http.newCall(builder.header(RepetierServer.API_KEY_HEADER, apiKey.trim()).build()).execute().use { r ->
                if (r.code == 401 || r.code == 403) throw ApiFailure("Repetier-Server rejected the API key.")
                val raw = r.body?.source()?.let { s -> s.request(2_000_001); s.buffer.readByteArray() } ?: ByteArray(0)
                if (raw.size > 2_000_000) throw ApiFailure("Repetier-Server's reply is larger than supported.")
                Reply(r.code, String(raw, Charsets.UTF_8))
            }
        } catch (e: IOException) { throw e as? ApiFailure ?: ApiFailure("Could not reach Repetier-Server at ${base.host}: ${e.message ?: "connection failed"}") }
    }

    /** Upstream's test (Repetier.cpp:56-99): `printer/info` must be a Repetier-Server. Then `printer/list` for the slug (243-289). */
    private fun check(): String {
        val info = call(Request.Builder().url(url("printer", "info")).get())
        if (!info.complete) throw ApiFailure("Repetier-Server answered HTTP ${info.code}.")
        RepetierServer.infoProblem(info.body)?.let { throw ApiFailure(it) }
        val list = call(Request.Builder().url(url("printer", "list")).get())
        if (list.code != 200) throw ApiFailure("Repetier-Server answered HTTP ${list.code} to the printer list.")
        return RepetierServer.chooseSlug(slug, RepetierServer.printerSlugs(list.body))
    }

    override fun snapshot(): PrinterSnapshot { check(); return RepetierServer.probeSnapshot() }

    override fun catalog(): Catalog = Catalog(emptyList(), emptyList(), emptyList(), listOf("Nozzle It All doesn't read Repetier-Server's file list yet."))
    override fun image(camera: Camera): ByteArray = throw ApiFailure("Nozzle It All doesn't show this printer's camera yet.")

    override fun command(command: PrinterCommand) {
        command.prusaLinkPrintRequest?.let { sendAndStart(it); return } // the plain-G-code upload-and-start request the other LAN kinds share
        when (command.path) {
            "printer/print/start" -> {
                val name = command.arguments["filename"]?.takeIf(String::isNotBlank) ?: throw ApiFailure("Missing filename.")
                RepetierServer.requireStartVerified(name, uploaded = false)
                throw ApiFailure("Unsupported command for a Repetier-Server printer: starting a file already on the server isn't built.")
            }
            "printer/print/pause", "printer/print/resume", "printer/print/cancel" ->
                throw ApiFailure("Unsupported command for a Repetier-Server printer: pause, resume and stop aren't built yet. Use Repetier-Server or the printer's screen.")
            else -> throw ApiFailure("Unsupported command for a Repetier-Server printer.")
        }
    }

    /**
     * While RepetierServer.START_VERIFIED is false: the file goes to the server's model library (`printer/model/<slug>`,
     * never printed) and the start is refused. Once verified: one `printer/job/<slug>` upload with `autostart=true`, as
     * upstream's "upload and print" (Repetier.cpp:114-175).
     */
    private fun sendAndStart(request: PrusaLinkPrintRequest) {
        val file = request.file
        if (!file.isFile) throw ApiFailure("The sliced file is missing. Slice again.")
        val name = RepetierServer.remoteName(request.remoteName)
        val printer = check()
        if (!RepetierServer.START_VERIFIED) {
            upload(file, name, url("printer", "model", printer), RepetierServer.modelUploadFields())
            RepetierServer.requireStartVerified(name)
        }
        RepetierServer.requireStartVerified(name)
        upload(file, name, url("printer", "job", printer), RepetierServer.jobUploadFields(name))
    }

    private fun upload(file: File, name: String, target: HttpUrl, fields: Map<String, String>) {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM).apply { fields.forEach { (k, v) -> addFormDataPart(k, v) } }
            .addFormDataPart(RepetierServer.FILE_FIELD, name, file.asRequestBody("application/octet-stream".toMediaType())).build()
        val reply = try { call(Request.Builder().url(target).post(form), uploadClient) } catch (e: ApiFailure) { throw ApiFailure("Could not upload $name: ${e.message}") }
        if (!reply.complete) throw ApiFailure("Could not upload $name: Repetier-Server answered (HTTP ${reply.code}).")
    }

    override fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
}

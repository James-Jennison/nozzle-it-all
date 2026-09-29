package net.jamesjennison.klippercompanion

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * PrinterKind.ULTIMAKER: a networked UltiMaker (3 / 3 Extended, S3, S5, S7). Request shapes and parsing are UltiMakerApi's
 * (pairing and the job upload ported from upstream OrcaSlicer's UltiMaker host, status from Cura's UM3NetworkPrinting;
 * docs/upstream/PROVENANCE.md P-0035); this class only carries them.
 *
 * [authId] / [authKey] are the credentials the printer issues when someone allows Nozzle It All on its screen (see
 * [requestCredentials] and [authStatus]); the id is kept in PrinterProfile.serial, the key in the encrypted apiKey slot.
 * The key only ever goes into an HTTP Digest response, never a URL, a log or an error message.
 *
 * Offered: live status and job progress (the cluster API, no auth), and pairing. Sending a print is refused before any
 * request until UltiMakerApi.START_VERIFIED: an UltiMaker prints every job it is sent. No control request (pause, resume,
 * abort, temperatures, motion) is sent. Built from sources only; not yet run against a real UltiMaker.
 */
class UltiMakerPrinterService(address: String, private val authId: String, private val authKey: String) : PrinterService {
    private val base: HttpUrl = Moonraker.parseAddress(if (address.contains("://")) address else "http://$address/")
    override val address: String get() = base.toString()
    private val client = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    private val digest = PrusaLinkDigestAuthenticator(authId.trim(), authKey.trim())
    // Only /api/v1 requests that upstream authenticates carry digest credentials (UltiMaker.cpp set_auth).
    private val authClient = client.newBuilder().authenticator(digest).build()
    // A G-code file can be many megabytes; the status client's short timeouts would cut it off.
    private val uploadClient = authClient.newBuilder().writeTimeout(120, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()

    private class Reply(val code: Int, val body: String) { val complete: Boolean get() = code in 200..299 }

    private fun call(request: Request, http: OkHttpClient = client): Reply = try {
        http.newCall(request).execute().use { r ->
            val raw = r.body?.source()?.let { s -> s.request(4_000_001); s.buffer.readByteArray() } ?: ByteArray(0)
            if (raw.size > 4_000_000) throw ApiFailure("The UltiMaker's reply is larger than supported.")
            Reply(r.code, String(raw, Charsets.UTF_8))
        }
    } catch (e: IOException) { throw e as? ApiFailure ?: ApiFailure("Could not reach the UltiMaker at ${base.host}: ${e.message ?: "connection failed"}") }

    private fun api(vararg segments: String): HttpUrl = base.newBuilder().addPathSegment("api").addPathSegment("v1").apply { segments.forEach { addPathSegment(it) } }.build()
    private fun cluster(vararg segments: String): HttpUrl = base.newBuilder().addPathSegment("cluster-api").addPathSegment("v1").apply { segments.forEach { addPathSegment(it) } }.build()

    private fun getArray(url: HttpUrl): JSONArray {
        val reply = call(Request.Builder().url(url).header("Accept", "application/json").get().build())
        if (!reply.complete) throw ApiFailure("The UltiMaker answered HTTP ${reply.code}.")
        return try { JSONArray(reply.body) } catch (_: org.json.JSONException) { throw ApiFailure("The UltiMaker sent an unreadable status.") }
    }

    /** Cura's reads (ClusterApiClient.py:71-87): the printers, then the print jobs. Read-only, no auth. */
    override fun snapshot(): PrinterSnapshot = UltiMakerApi.snapshot(getArray(cluster("printers")), getArray(cluster("print_jobs")))

    /** `GET /api/v1/system/variant` (UltiMaker.cpp:62-78): the model as the printer names it, e.g. "Ultimaker S5". Read-only. */
    fun variant(): String {
        val reply = call(Request.Builder().url(api("system", "variant")).get().build())
        if (!reply.complete) throw ApiFailure("The UltiMaker answered HTTP ${reply.code}.")
        return UltiMakerApi.variantName(reply.body) ?: throw ApiFailure("The printer at ${base.host} didn't answer like an UltiMaker.")
    }

    /**
     * `POST /api/v1/auth/request` (UltiMaker.cpp:222-302): asks the printer for credentials; the printer then asks the person
     * on its screen whether to allow them. Changes nothing on the printer until that is answered. The key is returned to the
     * caller to store encrypted, and never logged.
     */
    fun requestCredentials(): UltiMakerApi.Credentials {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM).apply { UltiMakerApi.authRequestFields().forEach { (k, v) -> addFormDataPart(k, v) } }.build()
        val reply = call(Request.Builder().url(api("auth", "request")).post(form).build())
        if (!reply.complete) throw ApiFailure("The UltiMaker refused the access request (HTTP ${reply.code}).")
        return UltiMakerApi.parseCredentials(reply.body) ?: throw ApiFailure("The UltiMaker's reply to the access request had no credentials.")
    }

    /** `GET /api/v1/auth/check/<id>` (UltiMaker.cpp:176-218): has the person allowed these credentials yet? Read-only. */
    fun authStatus(): UltiMakerApi.AuthStatus {
        if (authId.isBlank()) throw ApiFailure(UltiMakerApi.MISSING_CREDENTIALS)
        val reply = call(Request.Builder().url(api("auth", "check", authId.trim())).get().build(), authClient)
        return if (reply.complete) UltiMakerApi.authStatus(reply.body) else UltiMakerApi.AuthStatus.UNKNOWN
    }

    /** `GET /api/v1/auth/verify` with digest auth (UltiMaker.cpp:126-171). Read-only. */
    fun verify(): Boolean {
        if (authId.isBlank() || authKey.isBlank()) return false
        val reply = call(Request.Builder().url(api("auth", "verify")).get().build(), authClient)
        return reply.complete && UltiMakerApi.isVerified(reply.body)
    }

    override fun catalog(): Catalog = Catalog(emptyList(), emptyList(), emptyList(), listOf("Nozzle It All doesn't read an UltiMaker's file list."))
    override fun image(camera: Camera): ByteArray = throw ApiFailure("Nozzle It All doesn't show this printer's camera yet.")

    override fun command(command: PrinterCommand) {
        command.prusaLinkPrintRequest?.let { send(it); return } // the plain-G-code upload-and-start request the other LAN kinds share
        when (command.path) {
            "printer/print/start" -> {
                val name = command.arguments["filename"]?.takeIf(String::isNotBlank) ?: throw ApiFailure("Missing filename.")
                UltiMakerApi.requireStartVerified(name)
                throw ApiFailure("Unsupported command for an UltiMaker printer: starting a file already on the printer isn't built.")
            }
            "printer/print/pause", "printer/print/resume", "printer/print/cancel" ->
                throw ApiFailure("Unsupported command for an UltiMaker printer: pause, resume and abort aren't built yet. Use the printer's screen.")
            else -> throw ApiFailure("Unsupported command for an UltiMaker printer.")
        }
    }

    /**
     * Refused before any request while UltiMakerApi.START_VERIFIED is false. Once verified, upstream's order: credentials
     * checked (test_auth), the Griffin header cleaned up in a copy, then one `POST /api/v1/print_job` (UltiMaker.cpp:438-505).
     * Upstream's connect/disconnect/start_print for UltiMaker are copies of its Duet host (rr_disconnect, rr_gcode M32) that an
     * UltiMaker doesn't serve; they are not ported.
     */
    private fun send(request: PrusaLinkPrintRequest) {
        val file = request.file
        if (!file.isFile) throw ApiFailure("The sliced file is missing. Slice again.")
        val name = UltiMakerApi.remoteName(request.remoteName)
        UltiMakerApi.requireStartVerified(name)
        if (authId.isBlank() || authKey.isBlank()) throw ApiFailure("Nothing was sent: " + UltiMakerApi.MISSING_CREDENTIALS)
        UltiMakerApi.authProblem(authStatus(), verify())?.let { throw ApiFailure("Nothing was sent: $it") }
        val seconds = file.bufferedReader().useLines { UltiMakerApi.printTimeSeconds(it) }
        val copy = File.createTempFile("ultimaker", ".gcode", file.parentFile)
        try {
            val ok = file.bufferedReader().use { input -> copy.bufferedWriter().use { output -> UltiMakerApi.writeGriffinCompatible(input, output, seconds) } }
            if (!ok) throw ApiFailure(UltiMakerApi.NO_GRIFFIN_HEADER)
            val form = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("jobname", name)
                .addFormDataPart("file", name, copy.asRequestBody("application/octet-stream".toMediaType())).build()
            val url = api("print_job")
            // verify() above answered a digest challenge: answer this one up front, so the file isn't sent twice (as PrusaLink).
            val post = Request.Builder().url(url).post(form).apply { digest.preemptiveHeader("POST", url.encodedPath)?.let { header("Authorization", it) } }.build()
            val reply = try { call(post, uploadClient) }
                catch (e: ApiFailure) { throw ApiFailure("The printer may have received $name: ${e.message}") }
            if (!reply.complete) throw ApiFailure("Could not upload $name: the UltiMaker answered (HTTP ${reply.code}).")
        } finally { copy.delete() }
    }

    override fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
}

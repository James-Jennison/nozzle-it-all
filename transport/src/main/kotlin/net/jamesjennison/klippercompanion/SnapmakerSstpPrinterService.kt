package net.jamesjennison.klippercompanion

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * PrinterKind.SNAPMAKER_A_SERIES: a Snapmaker 2.0 (A150 / A250 / A350, single or dual extruder, with or without the Quick
 * Swap Kit) over its touchscreen's HTTP API. The protocol rules and reply shapes are SnapmakerSstp's (ported from Luban's
 * SstpHttpChannel.ts; docs/upstream/PROVENANCE.md P-0037); this class only carries them.
 *
 * [token] is the one the printer handed out when the person accepted Nozzle It All on the touchscreen (stored in the
 * profile's apiKey slot, like the other kinds' secrets). It only ever goes in a request body: the connect and the
 * upload's `token` field. It is never put in a URL, an error message or a log.
 *
 * Offered: live status and temperatures (read-only), and uploading a sliced file, which never starts a print. Starting
 * it is refused until SnapmakerSstp.START_VERIFIED: the file is uploaded and the person is told to start it on the
 * printer's screen. Pause, resume, stop, temperatures, nozzle switching and homing / moving are refused the same way
 * before anything is sent. Laser and CNC work is never offered. Built from sources only; not yet run against a printer.
 *
 * [connect] sends `POST /api/v1/connect`, which makes the printer ask the person on its screen. It is only ever called
 * from the "Connect" button in Edit printer; status reads and uploads never send it.
 */
class SnapmakerSstpPrinterService(
    address: String,
    private val token: String = "",
    /** The series the printer reported on its last connect (kept in the profile's serial slot); shown only. */
    private val series: String = "",
) : PrinterService {
    private val base: HttpUrl = Moonraker.parseAddress(if (address.contains("://")) address else "http://$address/")
    override val address: String get() = base.toString()
    private val host: String = base.host
    /** An address with its own port wins; else SnapmakerSstp.DEFAULT_PORT (unconfirmed from the sources, see there). */
    private val port: Int = base.port.takeIf { it != HttpUrl.defaultPort(base.scheme) } ?: SnapmakerSstp.DEFAULT_PORT
    private val client = OkHttpClient.Builder()
        .connectTimeout(SnapmakerSstp.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).build()
    // Luban gives the upload 5 minutes (sstp:469).
    private val uploadClient = client.newBuilder().writeTimeout(SnapmakerSstp.UPLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(SnapmakerSstp.UPLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()

    private fun url(path: String): HttpUrl =
        HttpUrl.Builder().scheme(base.scheme).host(host).port(port).addPathSegments(path).build()

    private fun bodyText(r: Response): String = r.body?.source()?.let { s -> s.request(256 * 1024); s.buffer.clone().readUtf8() }.orEmpty()

    private val form = "application/x-www-form-urlencoded".toMediaType()

    // ---- connect (the Connect button only) -----------------------------------------------------------------------------

    /**
     * `POST /api/v1/connect` with `token=<token>` (or empty the first time), sstp:171-199. Returns AwaitingApproval until the
     * person accepts on the touchscreen, then Connected with the token to keep. A laser or CNC head is refused
     * (SnapmakerSstp.NOT_A_PRINTING_HEAD) and nothing is kept.
     */
    fun connect(): SnapmakerSstp.ConnectResult {
        val result = try {
            client.newCall(Request.Builder().url(url(SnapmakerSstp.CONNECT_PATH))
                .post(SnapmakerSstp.tokenBody(token).toRequestBody(form)).build()).execute().use { r ->
                SnapmakerSstp.parseConnect(r.code, bodyText(r), token)
            }
        } catch (e: IOException) {
            if (e is ApiFailure) throw e
            throw ApiFailure("Could not reach the Snapmaker at $host (port $port).")
        }
        if (result is SnapmakerSstp.ConnectResult.Connected &&
            (result.head == SnapmakerSstp.Head.LASER || result.head == SnapmakerSstp.Head.CNC)) {
            throw ApiFailure(SnapmakerSstp.NOT_A_PRINTING_HEAD)
        }
        return result
    }

    // ---- status ---------------------------------------------------------------------------------------------------------

    /**
     * `GET /api/v1/status`, without the token (the token never goes in a URL; Luban's own reachability probe asks without
     * one, sstp:935-937). No content, or a refusal, means the connection has to be accepted first (sstp:328-336).
     */
    private fun status(): SnapmakerSstp.Status = try {
        client.newCall(Request.Builder().url(url(SnapmakerSstp.STATUS_PATH)).get().build()).execute().use { r ->
            when {
                r.code == 204 || r.code == 401 || r.code == 403 -> throw ApiFailure(SnapmakerSstp.NEEDS_CONNECT)
                !r.isSuccessful -> throw ApiFailure("The Snapmaker answered HTTP ${r.code}.")
                else -> SnapmakerSstp.parseStatus(bodyText(r))
            }
        }
    } catch (e: IOException) {
        if (e is ApiFailure) throw e
        throw ApiFailure("Could not reach the Snapmaker at $host (port $port).")
    }

    override fun snapshot(): PrinterSnapshot = status().snapshot()
    override fun toolheadTemperatures(): List<ToolheadTemperature> = status().toolheads()

    override fun catalog(): Catalog = Catalog(emptyList(), emptyList(), emptyList(),
        listOf("Nozzle It All doesn't read a Snapmaker's file list or camera yet." + if (series.isNotBlank()) " Connected series: $series." else ""))
    override fun image(camera: Camera): ByteArray = throw ApiFailure("Nozzle It All doesn't show this printer's camera yet.")

    // ---- commands -------------------------------------------------------------------------------------------------------

    override fun command(command: PrinterCommand) {
        command.prusaLinkPrintRequest?.let { send(it); return } // the plain-G-code upload request the other LAN kinds share
        when {
            command.path == "printer/print/start" -> {
                val name = command.arguments["filename"]?.takeIf(String::isNotBlank) ?: throw ApiFailure("Missing filename.")
                SnapmakerSstp.requireStartVerified(name, uploaded = false)
                notBuilt("starting a print")
            }
            command.path == "printer/print/pause" -> notBuilt("pausing a print")
            command.path == "printer/print/resume" -> notBuilt("resuming a print")
            command.path == "printer/print/cancel" -> notBuilt("stopping a print")
            command.heaterRequest != null -> notBuilt("setting a temperature")
            command.toolRequest != null -> notBuilt("switching the nozzle")
            command.path == "printer/gcode/script" || command.path == "printer/emergency_stop" -> notBuilt("homing, moving or heating")
            else -> throw ApiFailure("Unsupported command for a Snapmaker printer.")
        }
    }

    /** Refused before anything is sent while SnapmakerSstp.START_VERIFIED is false; not built past the gate either. */
    private fun notBuilt(what: String): Nothing {
        SnapmakerSstp.requireControlVerified(what)
        throw ApiFailure("Unsupported command for a Snapmaker printer: $what isn't built yet. Use the printer's screen.")
    }

    /**
     * Uploads the sliced file (sstp:461-476): multipart, the `token` field then the `file` named as it should be stored.
     * An upload never starts a print; the start (`POST /api/v1/start_print`, sstp:599-612) is refused afterwards with the
     * reason while SnapmakerSstp.START_VERIFIED is false, and isn't built past that gate.
     */
    private fun send(request: PrusaLinkPrintRequest) {
        val file = request.file
        if (!file.isFile) throw ApiFailure("The sliced file is missing. Slice again.")
        val name = SnapmakerSstp.safeFileName(request.remoteName)
        if (token.isBlank()) throw ApiFailure("Nothing was sent: " + SnapmakerSstp.NEEDS_CONNECT)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(SnapmakerSstp.UPLOAD_TOKEN_FIELD, token)
            .addFormDataPart(SnapmakerSstp.UPLOAD_FILE_FIELD, name, file.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        try {
            uploadClient.newCall(Request.Builder().url(url(SnapmakerSstp.UPLOAD_PATH)).post(body).build()).execute().use { r ->
                SnapmakerSstp.uploadProblem(r.code, name)?.let { throw ApiFailure(it) }
            }
        } catch (e: IOException) {
            if (e is ApiFailure) throw e
            throw ApiFailure("Could not upload $name to the Snapmaker: ${e.message ?: "connection failed"}")
        }
        SnapmakerSstp.requireStartVerified(name)
        notBuilt("starting a print")
    }

    /**
     * Doesn't send `POST /api/v1/disconnect` (sstp:284-306): the app makes a short-lived service per screen and read, and
     * they all share the one session the printer accepted, so one closing must not end it for the others.
     */
    override fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
}

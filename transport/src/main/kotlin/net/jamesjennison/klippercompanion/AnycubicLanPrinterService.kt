package net.jamesjennison.klippercompanion

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PrinterKind.ANYCUBIC_LAN: an Anycubic Kobra 3 / 3 Max, Kobra S1 / S1 Max or Kobra X on stock firmware in LAN mode, with
 * or without an ACE / ACE Pro. The protocol rules and message shapes are AnycubicLan's (ported from anycubic-orca-plugin,
 * cross-checked against kobra-connect; docs/upstream/PROVENANCE.md P-0036); this class only carries them:
 * - the handshake over plain HTTP on port 18910 (`GET /info`, the signed `POST` to its `ctrlInfoUrl`), whose MQTT
 *   credentials are kept in memory for this service only, never stored or logged;
 * - one short MQTT-over-TLS session per read (AnycubicMqttSession, the HiveMQ client Bambu uses), like BambuStatusProbe:
 *   connect, subscribe to the reports, send one query, take the answer, close;
 * - the multipart upload to the printer's own upload URL.
 *
 * Offered: live status and temperatures (read-only), the ACE / ACE Pro slots (read-only), and uploading a sliced file,
 * which never starts a print. Starting it is refused until AnycubicLan.START_VERIFIED: the file is uploaded and the person
 * is told to start it on the printer. Pause, resume, cancel, temperatures, homing / moving and the ACE's feed, unload and
 * dryer are refused the same way before anything is sent. Built from sources only; not yet run against a real printer.
 */
class AnycubicLanPrinterService(
    address: String,
    private val sessions: (AnycubicMqttSession.Listener) -> AnycubicMqttSession = { LiveAnycubicMqttSession(it) },
) : PrinterService, FilamentSlotReader {
    private val base: HttpUrl = Moonraker.parseAddress(if (address.contains("://")) address else "http://$address/")
    override val address: String get() = base.toString()
    private val host: String = base.host
    /** An address with its own port names the HTTP daemon's port, as acl.py:393-399 splits `host:port`; else 18910. */
    private val httpPort: Int = base.port.takeIf { it != HttpUrl.defaultPort(base.scheme) } ?: AnycubicLan.HTTP_PORT
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).build()
    // A G-code file can be many megabytes; acl.py:771 gives the upload 120 s.
    private val uploadClient = client.newBuilder().writeTimeout(120, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()
    /** This service's `did`, sent in the handshake and as the upload's X-BBL-Device-ID (AnycubicLan.newDeviceId). */
    private val did = AnycubicLan.newDeviceId()

    private class Handshake(val info: AnycubicLan.Info, val credentials: AnycubicLan.Credentials)
    @Volatile private var handshake: Handshake? = null

    // ---- HTTP -----------------------------------------------------------------------------------------------------------

    private fun httpUrl(path: String): HttpUrl = HttpUrl.Builder().scheme("http").host(host).port(httpPort).addPathSegment(path).build()

    private fun bodyText(r: Response): String = r.body?.source()?.let { s -> s.request(256 * 1024); s.buffer.clone().readUtf8() }.orEmpty()

    /**
     * The handshake (AnycubicLan.parseInfo, sign, parseCtrl), cached for this service. Error messages never carry a URL:
     * the `/ctrl` query holds the signature and the upload URL its token.
     */
    private fun handshake(fresh: Boolean = false): Handshake {
        if (!fresh) handshake?.let { return it }
        val infoBody = try {
            client.newCall(Request.Builder().url(httpUrl("info")).build()).execute().use { r ->
                if (!r.isSuccessful) throw ApiFailure("The Anycubic printer at $host didn't answer its LAN handshake (HTTP ${r.code}). Check LAN mode is on.")
                bodyText(r)
            }
        } catch (e: IOException) {
            if (e is ApiFailure) throw e
            throw ApiFailure("Could not reach the Anycubic printer at $host (port $httpPort): ${e.message ?: "connection failed"}")
        }
        val info = AnycubicLan.parseInfo(infoBody)
        val ctrl = AnycubicLan.printerUrl(info.ctrlInfoUrl, host)?.toHttpUrlOrNull()
            ?: throw ApiFailure("Nothing was sent: the printer's LAN handshake points at another address.")
        val ts = System.currentTimeMillis()
        val url = AnycubicLan.ctrlQuery(info.token, ts, AnycubicLan.nonce(), did)
            .fold(ctrl.newBuilder()) { b, (k, v) -> b.addQueryParameter(k, v) }.build()
        // An empty POST; kobra_connect/handshake.py:36-37 marks it application/json (acl.py:439 sends no type), kept.
        val ctrlBody = try {
            client.newCall(Request.Builder().url(url).post(ByteArray(0).toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                if (!r.isSuccessful) throw ApiFailure("The Anycubic printer refused the LAN handshake (HTTP ${r.code}).")
                bodyText(r)
            }
        } catch (e: IOException) {
            if (e is ApiFailure) throw e
            throw ApiFailure("Could not complete the LAN handshake with the Anycubic printer at $host.")
        }
        val credentials = AnycubicLan.parseCtrl(ctrlBody, info.token)
        AnycubicLan.requireTopicSafe(info.modelId, credentials.deviceId)
        return Handshake(info, credentials).also { handshake = it }
    }

    // ---- MQTT -----------------------------------------------------------------------------------------------------------

    /**
     * One MQTT session: connect with the handshake's credentials, subscribe to the reports, publish [command], and return
     * the `data` of the first report whose type is [answer] (null when [answer] is null: publish only), or null when
     * [optional] and none came within [timeoutMs]. A failed connect drops the cached handshake so the next read redoes it.
     */
    private fun exchange(command: AnycubicLan.Command, answer: String?, timeoutMs: Long, optional: Boolean = false): JSONObject? {
        val hs = handshake()
        val reply = CompletableFuture<JSONObject>()
        val dropped = AtomicBoolean(false)
        val session = sessions(object : AnycubicMqttSession.Listener {
            override fun onMessage(topic: String, payload: String) {
                if (answer == null) return
                val o = try { JSONObject(payload) } catch (_: org.json.JSONException) { return }
                if (AnycubicLan.reportType(topic, o) == answer) o.optJSONObject("data")?.let { reply.complete(it) }
            }
            override fun onDisconnected(message: String?) {
                dropped.set(true)
                reply.completeExceptionally(ApiFailure("The Anycubic printer closed the connection."))
            }
        })
        try {
            val config = AnycubicMqttConfig(host, hs.credentials.brokerPort, hs.credentials.username, hs.credentials.password,
                AnycubicLan.reportFilter(hs.info.modelId, hs.credentials.deviceId), hs.credentials.deviceCert, hs.credentials.deviceKey)
            try { await(session.connect(config), CONNECT_TIMEOUT_MS, "Could not reach the Anycubic printer's MQTT broker (port ${hs.credentials.brokerPort}).") }
            catch (e: ApiFailure) { handshake = null; throw e }
            await(session.publish(command.topic(hs.info.modelId, hs.credentials.deviceId), command.payload().toString()), CONNECT_TIMEOUT_MS,
                "Could not send the request to the Anycubic printer.")
            if (answer == null) return null
            return try { await(reply, timeoutMs, "The Anycubic printer didn't answer.") }
            catch (e: ApiFailure) { if (optional && !dropped.get()) null else throw e } // silence is "no ACE"; a dropped link is an error
        } finally {
            session.close()
        }
    }

    private fun <T> await(future: CompletableFuture<T>, timeoutMs: Long, fallback: String): T = try {
        future.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        future.cancel(true)
        throw ApiFailure(fallback)
    } catch (e: ExecutionException) {
        throw (e.cause as? ApiFailure) ?: ApiFailure(fallback)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        throw ApiFailure(fallback)
    }

    /** `info` / `query` (AnycubicLan.infoQuery) and its report. */
    override fun snapshot(): PrinterSnapshot = AnycubicLan.snapshot(exchange(AnycubicLan.infoQuery(), "info", STATUS_WAIT_MS)!!)

    private fun aceSlots(): List<AnycubicLan.AceSlot> = AnycubicLan.parseAce(exchange(AnycubicLan.aceQuery(), "multiColorBox", SLOTS_WAIT_MS, optional = true))

    /** Read-only: `multiColorBox` / `getInfo` (AnycubicLan.aceQuery). No ACE reported (or no answer): no slots. */
    override fun filamentSlots(): FilamentSlotStatus {
        val slots = aceSlots()
        if (slots.isEmpty()) return FilamentSlotStatus(emptyList(), "")
        return FilamentSlotStatus(AnycubicLan.filamentSlots(slots), "the printer's ACE")
    }

    override fun catalog(): Catalog = Catalog(emptyList(), emptyList(), emptyList(), listOf("Nozzle It All doesn't read an Anycubic printer's file list or camera yet."))
    override fun image(camera: Camera): ByteArray = throw ApiFailure("Nozzle It All doesn't show this printer's camera yet.")

    // ---- commands -------------------------------------------------------------------------------------------------------

    override fun command(command: PrinterCommand) {
        command.prusaLinkPrintRequest?.let { sendAndStart(it); return } // the plain-G-code upload-and-start request the other LAN kinds share
        when {
            command.path == "printer/print/start" -> {
                val name = command.arguments["filename"]?.takeIf(String::isNotBlank) ?: throw ApiFailure("Missing filename.")
                AnycubicLan.requireStartVerified(name, uploaded = false)
                start(name, 0L, mappingFor(emptyList(), aceSlots()))
            }
            command.path == "printer/print/pause" -> control("pausing a print", AnycubicLan.pause())
            command.path == "printer/print/resume" -> control("resuming a print", AnycubicLan.resume())
            command.path == "printer/print/cancel" -> control("cancelling a print", AnycubicLan.stop())
            // Built (AnycubicLan.setTemperatures, homeAll, feedFilament, setDrying) but not wired to this app's controls yet.
            command.heaterRequest != null -> gatedNotBuilt("setting a temperature")
            command.aceRequest != null -> gatedNotBuilt("feeding, unloading or drying ACE filament")
            command.path == "printer/gcode/script" || command.path == "printer/emergency_stop" -> gatedNotBuilt("homing, moving or heating")
            else -> throw ApiFailure("Unsupported command for an Anycubic printer.")
        }
    }

    /** Pause / resume / stop: refused before anything is sent until AnycubicLan.START_VERIFIED; then published, fire and forget (as acl.py:623-630). */
    private fun control(what: String, message: AnycubicLan.Command) {
        AnycubicLan.requireControlVerified(what)
        exchange(message, null, 0L)
    }

    private fun gatedNotBuilt(what: String): Nothing {
        AnycubicLan.requireControlVerified(what)
        throw ApiFailure("Unsupported command for an Anycubic printer: $what isn't built yet. Use the printer's screen.")
    }

    private fun mappingFor(map: List<Int>, slots: List<AnycubicLan.AceSlot>, filaments: List<SlicedFileFilaments.Filament> = emptyList()): List<AnycubicLan.BoxMapping> =
        try { AnycubicLan.boxMapping(map, slots, filaments) } catch (e: IllegalArgumentException) { throw ApiFailure("Nothing was sent: ${e.message}") }

    /**
     * Uploads the sliced file; while AnycubicLan.START_VERIFIED is false that is all, and the start is refused with the
     * reason. Once verified: the printer must be idle, the file's tools map to ACE slots one-to-one (T n -> slot n, as
     * ElegooProfiles.toolheadMap), the mapping is checked before anything is uploaded, and the start is confirmed from
     * the next status report (the references fire and forget, acl.py:850).
     */
    private fun sendAndStart(request: PrusaLinkPrintRequest) {
        val file = request.file
        if (!file.isFile) throw ApiFailure("The sliced file is missing. Slice again.")
        val name = AnycubicLan.safeFileName(request.remoteName)
        if (!AnycubicLan.START_VERIFIED) { val remote = upload(file, name); AnycubicLan.requireStartVerified(remote) }
        val state = snapshot().state
        if (!AnycubicLan.isIdle(state)) throw ApiFailure("The printer is $state; start a print when it is ready.")
        val slots = aceSlots()
        val map = file.bufferedReader().useLines { ElegooProfiles.toolheadMap(it, if (slots.isEmpty()) 64 else slots.size) }
        val filaments = file.bufferedReader().useLines { SlicedFileFilaments.read(it) }
        val mapping = mappingFor(map, slots, filaments) // refuses a bad mapping before the upload
        start(upload(file, name), file.length(), mapping)
    }

    /** Only reached with AnycubicLan.START_VERIFIED: `print` / `start` for [remoteName], then a status read to see it began. */
    private fun start(remoteName: String, fileSize: Long, mapping: List<AnycubicLan.BoxMapping>) {
        AnycubicLan.requireStartVerified(remoteName)
        exchange(AnycubicLan.startPrint(remoteName, fileSize, mapping), null, 0L)
        val deadline = System.currentTimeMillis() + START_WATCH_MS
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { snapshot().state }.getOrNull() == "printing") return
            Thread.sleep(1_500)
        }
        throw ApiFailure("Sent the start of $remoteName, but the printer hasn't reported printing yet. Check the printer before trying again.")
    }

    /**
     * The multipart upload (AnycubicLan.uploadUrl / uploadHeaders / parseUploadReply; acl.py:725-790): fields `filename`
     * then `gcode`. A 401 means the upload token went stale: redo the handshake and try once more (acl.py:772-776).
     * Returns the printer's name for the file. Never starts a print.
     */
    private fun upload(file: File, name: String): String {
        for (attempt in 0..1) {
            val hs = handshake(fresh = attempt > 0)
            val url = AnycubicLan.uploadUrl(hs.info, host, httpPort)?.toHttpUrlOrNull()
                ?: throw ApiFailure("Nothing was sent: the printer's upload address points at another machine.")
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart(AnycubicLan.UPLOAD_NAME_FIELD, name)
                .addFormDataPart(AnycubicLan.UPLOAD_FILE_FIELD, name, file.asRequestBody("application/octet-stream".toMediaType()))
                .build()
            val request = Request.Builder().url(url).post(body).apply { AnycubicLan.uploadHeaders(did, file.length()).forEach { (k, v) -> header(k, v) } }.build()
            try {
                uploadClient.newCall(request).execute().use { r ->
                    if (r.code == 401 && attempt == 0) { handshake = null; return@use }
                    if (!r.isSuccessful) throw ApiFailure("Could not upload $name to the printer (HTTP ${r.code}).")
                    return AnycubicLan.parseUploadReply(bodyText(r), name)
                }
            } catch (e: IOException) {
                if (e is ApiFailure) throw e
                throw ApiFailure("Could not upload $name to the printer: ${e.message ?: "connection failed"}")
            }
        }
        throw ApiFailure("Could not upload $name to the printer (HTTP 401).")
    }

    override fun close() { handshake = null; client.dispatcher.cancelAll(); client.connectionPool.evictAll() }

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val STATUS_WAIT_MS = 6_000L
        const val SLOTS_WAIT_MS = 6_000L
        const val START_WATCH_MS = 15_000L
    }
}

package net.jamesjennison.klippercompanion

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * PrinterKind.CREALITY: a Creality K1 / K2 / Hi on Creality's own firmware, with or without a CFS. The protocol rules and
 * message shapes are CrealityCfs's (ported from upstream OrcaSlicer's CrealityPrint print host and CrealityPrint itself;
 * docs/upstream/PROVENANCE.md P-0033); this class only carries them: `GET /info` and `POST /upload/<name>` on the printer's
 * HTTP port, and a short-lived `ws://<host>:9999/` socket per read, answering the printer's heartbeats with `ok`.
 *
 * Offered: live status and temperatures (read-only), the CFS slots (read-only), and uploading a sliced G-code file.
 * Starting it is refused until CrealityCfs.START_VERIFIED: the file is uploaded and the person is told to start it on the
 * printer. Never sent from here: filament load/unload (`feedInOrOut`), RFID refresh (`refreshBox`), CFS settings
 * (`boxConfig`), homing, jogging or temperature commands. Pause, resume and cancel aren't built. Built from sources only;
 * not yet run against a real printer. Printers that only accept encrypted (wss) connections aren't supported.
 */
class CrealityPrinterService(address: String, private val wsPort: Int = CrealityCfs.WS_PORT) : PrinterService, FilamentSlotReader {
    private val base: HttpUrl = Moonraker.parseAddress(if (address.contains("://")) address else "http://$address/")
    override val address: String get() = base.toString()
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    // A G-code file can be many megabytes; the status client's short timeouts would cut it off.
    private val uploadClient = client.newBuilder().writeTimeout(120, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()
    // Each exchange bounds itself (and closes its socket); no read or call timeout may cut a socket off underneath it.
    private val wsClient = client.newBuilder().readTimeout(0, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    private val wsUrl: HttpUrl = HttpUrl.Builder().scheme("http").host(base.host).port(wsPort).build()
    @Volatile private var info: CrealityCfs.Info? = null
    @Volatile private var lastBoxes: Pair<Long, CrealityCfs.Boxes?>? = null

    private class Exchange(val state: JSONObject, val opened: Boolean, val failure: Throwable?)

    /**
     * One short-lived socket: sends [requests] in order once it opens, answers heartbeats with `ok`, merges every JSON
     * frame into one state object, and returns when [done] says so, the printer closes the socket (K1-family firmware
     * does right after replying; CrealityPrint.cpp:335-339), or [timeoutMs] passes, with whatever arrived.
     */
    private fun exchange(requests: List<JSONObject>, timeoutMs: Long, done: (JSONObject) -> Boolean): Exchange {
        val state = JSONObject()
        val finished = CountDownLatch(1)
        val opened = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val socket = wsClient.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.set(true); requests.forEach { webSocket.send(it.toString()) } }
            override fun onMessage(webSocket: WebSocket, text: String) {
                CrealityCfs.replyTo(text)?.let { webSocket.send(it); return }
                val frame = CrealityCfs.parseFrame(text) ?: return
                synchronized(state) { CrealityCfs.merge(state, frame); if (done(state)) finished.countDown() }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); finished.countDown() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { finished.countDown() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failure.set(t); finished.countDown() }
        })
        try { finished.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() } finally { socket.close(1000, null) }
        return Exchange(synchronized(state) { JSONObject(state.toString()) }, opened.get(), failure.get())
    }

    /** [exchange], failing when nothing at all came back. */
    private fun read(requests: List<JSONObject>, timeoutMs: Long, done: (JSONObject) -> Boolean): JSONObject {
        val x = exchange(requests, timeoutMs, done)
        if (x.state.length() > 0) return x.state
        if (!x.opened) throw ApiFailure("Could not reach the Creality printer at ${base.host} (port $wsPort)${x.failure?.message?.let { ": $it" }.orEmpty()}.")
        throw ApiFailure("The Creality printer at ${base.host} connected but sent no status.")
    }

    private fun rememberBoxes(state: JSONObject) {
        if (state.has("boxsInfo")) lastBoxes = System.currentTimeMillis() to CrealityCfs.parseBoxes(state)
    }

    /** The printer pushes its state when a client connects; a boxsInfo request rides along so a CFS read can reuse it. */
    override fun snapshot(): PrinterSnapshot {
        val state = read(listOf(CrealityCfs.get("boxsInfo")), STATUS_WAIT_MS) { it.has("state") && it.has("nozzleTemp") }
        rememberBoxes(state)
        return CrealityCfs.snapshot(state)
    }

    /** Read-only: `get boxsInfo` (CrealityPrint `GetBoxsInfo`). No CFS reported: no slots. */
    override fun filamentSlots(): FilamentSlotStatus {
        val cached = lastBoxes?.takeIf { System.currentTimeMillis() - it.first < 5_000 }
        val boxes = if (cached != null) cached.second else {
            val state = read(listOf(CrealityCfs.get("boxsInfo")), SLOTS_WAIT_MS) { it.has("boxsInfo") }
            rememberBoxes(state)
            CrealityCfs.parseBoxes(state)
        }
        if (boxes == null || boxes.slots.isEmpty()) return FilamentSlotStatus(emptyList(), "")
        return FilamentSlotStatus(CrealityCfs.filamentSlots(boxes), "the printer's " + (boxes.unitName?.let { CrealityCfs.UNIT_NAMES[it] } ?: "CFS"))
    }

    override fun catalog(): Catalog = Catalog(emptyList(), emptyList(), emptyList(), listOf("Nozzle It All doesn't read a Creality printer's file list or macros yet."))
    override fun image(camera: Camera): ByteArray = throw ApiFailure("Nozzle It All doesn't show this printer's camera yet.")

    override fun command(command: PrinterCommand) {
        command.prusaLinkPrintRequest?.let { sendAndStart(it); return } // the plain-G-code upload-and-start request the other LAN kinds share
        when (command.path) {
            "printer/print/start" -> {
                val name = command.arguments["filename"]?.takeIf(String::isNotBlank) ?: throw ApiFailure("Missing filename.")
                CrealityCfs.requireStartVerified(name, uploaded = false)
                start(name, emptyList(), emptyList(), emptyList())
            }
            "printer/print/pause", "printer/print/resume", "printer/print/cancel" ->
                throw ApiFailure("Unsupported command for a Creality printer: pause, resume and cancel aren't built yet. Use the printer's screen.")
            else -> throw ApiFailure("Unsupported command for a Creality printer.")
        }
    }

    /**
     * Uploads the sliced file; while CrealityCfs.START_VERIFIED is false that is all, and the start is refused with the
     * reason. Once verified: the printer must be idle, the file's tools map to CFS slots one-to-one (T n -> CFS slot n, as
     * ElegooProfiles.toolheadMap), the mapping is checked before anything is uploaded, and the start waits for the printer
     * to list the upload (CrealityPrint `executePrintTask` polls the same way, line 1269 @5216346).
     */
    private fun sendAndStart(request: PrusaLinkPrintRequest) {
        val file = request.file
        if (!file.isFile) throw ApiFailure("The sliced file is missing. Slice again.")
        val name = CrealityCfs.safeFileName(request.remoteName)
        if (!CrealityCfs.START_VERIFIED) { upload(file, name); CrealityCfs.requireStartVerified(name) }
        val state = read(listOf(CrealityCfs.get("boxsInfo")), SLOTS_WAIT_MS) { it.has("state") && it.has("boxsInfo") }
        if (!CrealityCfs.isIdle(state)) throw ApiFailure("The printer is ${CrealityCfs.stateName(state.optInt("state", -1))}; start a print when it is ready.")
        val slots = CrealityCfs.parseBoxes(state)?.slots.orEmpty()
        val map = file.bufferedReader().useLines { ElegooProfiles.toolheadMap(it, if (slots.isEmpty()) 64 else slots.size) }
        val filaments = file.bufferedReader().useLines { SlicedFileFilaments.read(it) }
        messages(CrealityCfs.gcodeDirectory(info()?.model) + name, map, slots, filaments) // refuses a bad mapping before the upload
        upload(file, name)
        start(name, map, slots, filaments)
    }

    private fun messages(path: String, map: List<Int>, slots: List<CrealityCfs.Slot>, filaments: List<SlicedFileFilaments.Filament>): List<JSONObject> =
        try { CrealityCfs.startMessages(path, map, slots, filaments) } catch (e: IllegalArgumentException) { throw ApiFailure("Nothing was sent: ${e.message}") }

    /** Only reached with CrealityCfs.START_VERIFIED: the start messages for [name], once the printer lists it. */
    private fun start(name: String, map: List<Int>, slots: List<CrealityCfs.Slot>, filaments: List<SlicedFileFilaments.Filament>) {
        CrealityCfs.requireStartVerified(name)
        val listing = awaitListed(name)
        val path = CrealityCfs.printerPath(name, info()?.model, listing)
        // No acks exist for these (Orca CrealityPrint.cpp:412-432; CrealityPrint fires and forgets): the pushed state says what happened.
        val x = exchange(messages(path, map, slots, filaments), START_WATCH_MS) { CrealityCfs.pushedError(it) != null || it.optInt("state", -1) == 1 }
        if (!x.opened) throw ApiFailure("Could not reach the printer; nothing was sent.")
        CrealityCfs.pushedError(x.state)?.let { throw ApiFailure("The printer rejected the print command: $it") }
        if (x.state.optInt("state", -1) != 1) throw ApiFailure("Sent the start of $name, but the printer hasn't reported printing yet. Check the printer before trying again.")
    }

    /** Polls `get reqGcodeFile` until [name] is listed (up to 10 x 1.5 s, as CrealityPrint), and returns that state. */
    private fun awaitListed(name: String): JSONObject {
        repeat(10) { attempt ->
            val x = exchange(listOf(CrealityCfs.get("reqGcodeFile")), 3_000) { it.has("retGcodeFileInfo2") }
            if (CrealityCfs.isListed(x.state, name)) return x.state
            if (attempt < 9) Thread.sleep(1_500)
        }
        throw ApiFailure("Uploaded $name but the printer hasn't listed it yet, so it did not start it. Check the printer's file list.")
    }

    /** `POST /upload/<name>`, multipart field `file`; the reply contains "OK" (Klipper4408Interface.cpp:38-40,74-78; Orca CrealityPrint.cpp:143-149). */
    private fun upload(file: File, name: String) {
        val url = base.newBuilder().addPathSegment("upload").addPathSegment(name).build()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", name, file.asRequestBody("application/octet-stream".toMediaType())).build()
        try {
            uploadClient.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
                val text = r.body?.source()?.let { s -> s.request(64 * 1024); s.buffer.clone().readUtf8() }.orEmpty()
                if (!r.isSuccessful || !text.contains("ok", ignoreCase = true)) throw ApiFailure("Could not upload $name to the printer (HTTP ${r.code}).")
            }
        } catch (e: IOException) {
            if (e is ApiFailure) throw e
            throw ApiFailure("Could not upload $name to the printer: ${e.message ?: "connection failed"}")
        }
    }

    /** `GET /info`, once (model decides the K1 vs K2 file directory); null when it can't be read. */
    private fun info(): CrealityCfs.Info? = info ?: try {
        client.newCall(Request.Builder().url(base.newBuilder().addPathSegment("info").build()).build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.source()?.let { s -> s.request(64 * 1024); s.buffer.clone().readUtf8() }?.let(CrealityCfs::parseInfo)
        }?.also { info = it }
    } catch (_: IOException) { null }

    override fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }

    companion object {
        const val STATUS_WAIT_MS = 4_000L
        const val SLOTS_WAIT_MS = 6_000L
        const val START_WATCH_MS = 10_000L
    }
}

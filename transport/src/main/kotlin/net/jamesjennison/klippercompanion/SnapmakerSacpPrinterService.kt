package net.jamesjennison.klippercompanion

import okhttp3.HttpUrl
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * PrinterKind.SNAPMAKER_SACP: a Snapmaker J1 or Artisan over SACP on TCP. The packet format, payloads and parsers are
 * SnapmakerSacp's (ported from the Snapmaker SACP SDK and Luban; docs/upstream/PROVENANCE.md P-0037); this class only
 * carries them over one TCP session, as Luban's SacpTcpChannel does.
 *
 * [identity] is the name Nozzle It All says hello with (Luban sends the computer's host name, luban/SacpTcpChannel.ts:73-81);
 * it is kept in the profile's serial slot. It is blank until the person taps "Connect" in Edit printer, and while it is
 * blank nothing is sent to the printer at all: the hello is what makes the printer ask on its screen. [token] is the
 * optional hello token (sdk/models/WifiConnectionInfo.js:6-12), kept in the encrypted apiKey slot and never logged.
 *
 * Offered: live temperatures, job progress and the machine's identity (read-only), and uploading a sliced file, which
 * never starts a print. Starting it is refused until SnapmakerSacp.START_VERIFIED: the file is uploaded and the person is
 * told to start it on the printer's screen. Pause, resume, stop, temperatures, nozzle switching and homing / moving are
 * refused the same way before anything is sent. Laser and CNC work is never offered. Built from sources only; not yet
 * run against a printer.
 */
class SnapmakerSacpPrinterService(
    address: String,
    private val identity: String = "",
    private val token: String = "",
    private val socketFactory: () -> Socket = { Socket() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val helloDelayMs: Long = SnapmakerSacp.HELLO_DELAY_MS,
    private val replyWaitMs: Long = REPLY_WAIT_MS,
    private val statusWaitMs: Long = STATUS_WAIT_MS,
    private val uploadIdleMs: Long = UPLOAD_IDLE_MS,
) : PrinterService {
    private val base: HttpUrl = Moonraker.parseAddress(if (address.contains("://")) address else "http://$address/")
    override val address: String get() = base.toString()
    private val host: String = base.host
    /** An address with its own port wins; else 8888 (luban/SacpTcpChannel.ts:68). */
    private val port: Int = base.port.takeIf { it != HttpUrl.defaultPort(base.scheme) } ?: SnapmakerSacp.TCP_PORT

    private val lock = Any()
    @Volatile private var session: Session? = null

    // ---- the session ----------------------------------------------------------------------------------------------------

    /** One file being served to the printer, which asks for it chunk by chunk (luban/SacpClient.ts:933-976). */
    private class Upload(val file: File, val md5Hex: String) {
        val done = CompletableFuture<Int>()
        val lastActivity = AtomicLong(0L)
    }

    private inner class Session(private val socket: Socket) {
        private val input: InputStream = socket.getInputStream()
        private val output: OutputStream = socket.getOutputStream()
        private var sequence = 0
        private val pending = ConcurrentHashMap<Triple<Int, Int, Int>, CompletableFuture<SnapmakerSacp.Packet>>()
        /** Commands whose packets are notifications (sdk/communication/Dispatcher.js:96-103), added once their subscribe is acknowledged (206-223). */
        val subscribed: MutableSet<Pair<Int, Int>> = ConcurrentHashMap.newKeySet()
        @Volatile var status = SnapmakerSacp.Status()
            private set
        /** Status changes come from the reader thread and from reply callbacks that may run on the caller's: one at a time. */
        fun update(change: (SnapmakerSacp.Status) -> SnapmakerSacp.Status) { synchronized(pending) { status = change(status) } }
        @Volatile var closed = false
        @Volatile var upload: Upload? = null
        private val reader = Thread(::readLoop, "snapmaker-sacp-reader").apply { isDaemon = true }

        fun start() = reader.start()

        private fun write(packet: SnapmakerSacp.Packet) {
            val bytes = SnapmakerSacp.encode(packet)
            synchronized(output) { output.write(bytes); output.flush() }
        }

        /** Sends a request and returns the future of its ACK, matched on command and sequence (sdk/communication/Communication.js:200-212). */
        fun send(command: Pair<Int, Int>, receiver: Int, payload: ByteArray = ByteArray(0)): CompletableFuture<SnapmakerSacp.Packet> {
            if (closed) throw ApiFailure(CLOSED)
            val packet = synchronized(this) {
                sequence = SnapmakerSacp.nextSequence(sequence)
                SnapmakerSacp.request(command, receiver, sequence, payload)
            }
            val future = CompletableFuture<SnapmakerSacp.Packet>()
            val key = Triple(packet.commandSet, packet.commandId, packet.sequence)
            pending[key] = future
            future.whenComplete { _, _ -> pending.remove(key) }
            try { write(packet) } catch (e: IOException) { drop(); throw ApiFailure(CLOSED) }
            return future
        }

        /** Sends a request and waits for its ACK's Response (sdk/communication/Response.js:14-18). */
        fun call(command: Pair<Int, Int>, receiver: Int, payload: ByteArray = ByteArray(0), waitMs: Long = replyWaitMs, fallback: String = NO_ANSWER): SnapmakerSacp.Response =
            SnapmakerSacp.parseResponse(await(send(command, receiver, payload), waitMs, fallback).payload)

        private fun readLoop() {
            val decoder = SnapmakerSacp.Decoder()
            val buf = ByteArray(8192)
            try {
                while (!closed) {
                    val n = input.read(buf)
                    if (n < 0) break
                    for (p in decoder.feed(buf, 0, n)) handle(p)
                }
            } catch (_: IOException) {
            } finally { drop() }
        }

        /** sdk/communication/Communication.js:198-218 and Dispatcher.js:96-109. */
        private fun handle(p: SnapmakerSacp.Packet) {
            if (p.isAck) pending[Triple(p.commandSet, p.commandId, p.sequence)]?.let { it.complete(p); return }
            if (p.command in subscribed) { update { SnapmakerSacp.apply(it, p.command, p.payload, clock()) }; return }
            if (p.isAck) return // an ACK nothing waits for, e.g. the hello heartbeat's
            when (p.command) {
                // The printer says goodbye: acknowledge with result 0 and drop the session (luban/SacpClient.ts:1303-1307).
                SnapmakerSacp.BYE -> { runCatching { write(SnapmakerSacp.ackTo(p, SnapmakerSacp.responsePayload(0))) }; drop() }
                SnapmakerSacp.UPLOAD_CHUNK -> serveChunk(p)
                SnapmakerSacp.UPLOAD_DONE -> {
                    val result = runCatching { SnapmakerSacp.parseUploadResult(p.payload) }.getOrDefault(-1)
                    runCatching { write(SnapmakerSacp.ackTo(p, SnapmakerSacp.responsePayload(0))) } // luban/SacpClient.ts:973
                    upload?.done?.complete(result)
                }
                else -> Unit // no handler, as Luban: nothing is answered
            }
        }

        /**
         * luban/SacpClient.ts:935-962: the chunk at index * 60 KiB, as `[0, md5, index, chunk]`; a read error answers the
         * single byte 200. With no upload in progress nothing is answered (Luban's handler isn't set). A request for another
         * file's md5 is answered 200 too; Luban doesn't check it, this port does.
         */
        private fun serveChunk(p: SnapmakerSacp.Packet) {
            val up = upload ?: return
            up.lastActivity.set(clock())
            val reply = try {
                val req = SnapmakerSacp.parseChunkRequest(p.payload)
                if (req.md5Hex != up.md5Hex) SnapmakerSacp.chunkErrorPayload()
                else SnapmakerSacp.chunkPayload(req.md5Hex, req.index, readChunk(up.file, req.index))
            } catch (_: IOException) { SnapmakerSacp.chunkErrorPayload() }
            catch (_: IllegalArgumentException) { SnapmakerSacp.chunkErrorPayload() }
            runCatching { write(SnapmakerSacp.ackTo(p, reply)) }
        }

        fun drop() {
            if (closed) return
            closed = true
            runCatching { socket.close() }
            pending.values.forEach { it.completeExceptionally(ApiFailure(CLOSED)) }
            upload?.done?.completeExceptionally(ApiFailure(CLOSED))
            synchronized(lock) { if (session === this) session = null }
        }

        /** Unsubscribe and say goodbye (sdk/communication/Dispatcher.js:227-230, luban/SacpClient.ts:1320-1324), then close. */
        fun close() {
            if (closed) return
            runCatching {
                subscribed.toList().forEach { send(SnapmakerSacp.UNSUBSCRIBE, SnapmakerSacp.PEER_CONTROLLER, SnapmakerSacp.unsubscribePayload(it)) }
                send(SnapmakerSacp.BYE, SnapmakerSacp.PEER_SCREEN).get(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)
            }
            drop()
        }
    }

    private fun readChunk(file: File, index: Int): ByteArray {
        RandomAccessFile(file, "r").use { f ->
            val start = index.toLong() * SnapmakerSacp.CHUNK_BYTES
            if (start >= f.length()) return ByteArray(0) // Luban's read stream past the end yields an empty chunk
            val out = ByteArray(minOf(SnapmakerSacp.CHUNK_BYTES.toLong(), f.length() - start).toInt())
            f.seek(start); f.readFully(out)
            return out
        }
    }

    /**
     * Opens the session as luban/SacpTcpChannel.ts:62-117 does: TCP connect, wait 200 ms, the hello to the screen (result 0
     * means accepted), the hello heartbeat, the machine info; then the status subscriptions at 1000 ms (luban/SacpChannel.ts:
     * 896-1134) and the printing file's info. [helloWaitMs] is how long the person has to accept on the printer's screen.
     */
    private fun open(helloWaitMs: Long): Session {
        synchronized(lock) { session?.takeIf { !it.closed }?.let { return it } }
        if (identity.isBlank()) throw ApiFailure(NEEDS_CONNECT)
        val socket = socketFactory()
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS.toInt())
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw ApiFailure("Could not reach the Snapmaker at $host (port $port).")
        }
        val s = Session(socket)
        s.start()
        try {
            if (helloDelayMs > 0) Thread.sleep(helloDelayMs)
            val hello = s.call(SnapmakerSacp.HELLO, SnapmakerSacp.PEER_SCREEN, SnapmakerSacp.helloPayload(identity, SnapmakerSacp.CLIENT_NAME, token),
                helloWaitMs, "The Snapmaker didn't accept the connection. Tap Connect in Edit printer and accept on the printer's screen.")
            if (hello.result != 0) throw ApiFailure("The Snapmaker refused the connection (result ${hello.result}). Tap Connect in Edit printer and accept on the printer's screen.")
            s.send(SnapmakerSacp.HELLO_HEARTBEAT, SnapmakerSacp.PEER_SCREEN) // not awaited, as luban/SacpTcpChannel.ts:101
            val info = s.call(SnapmakerSacp.MACHINE_INFO, SnapmakerSacp.PEER_CONTROLLER)
            if (info.result == 0) runCatching { SnapmakerSacp.parseMachineInfo(info.data) }.getOrNull()?.let { m -> s.update { it.copy(machine = m) } }
            for (command in SnapmakerSacp.STATUS_SUBSCRIPTIONS) {
                val r = s.call(SnapmakerSacp.SUBSCRIBE, SnapmakerSacp.PEER_CONTROLLER, SnapmakerSacp.subscribePayload(command))
                if (r.result == 0) s.subscribed += command
            }
            requestFileInfo(s)
        } catch (e: Exception) {
            s.drop()
            if (e is InterruptedException) { Thread.currentThread().interrupt(); throw ApiFailure(NO_ANSWER) }
            throw e
        }
        synchronized(lock) {
            val existing = session?.takeIf { !it.closed }
            if (existing != null) { s.close(); return existing }
            session = s
        }
        return s
    }

    /** `getPrintingFileInfo` to the screen (luban/SacpClient.ts:203-220), not awaited: result 0 carries the file. */
    private fun requestFileInfo(s: Session) {
        runCatching {
            s.send(SnapmakerSacp.FILE_INFO, SnapmakerSacp.PEER_SCREEN, SnapmakerSacp.fileInfoPayload()).thenAccept { p ->
                val r = SnapmakerSacp.parseResponse(p.payload)
                if (r.result == 0) runCatching { SnapmakerSacp.parseFileInfo(r.data) }.getOrNull()?.let { f -> s.update { it.copy(fileInfo = f) } }
            }
        }
    }

    /**
     * The "Connect" button: says hello with [identity] and waits for the person to accept on the printer's screen, then
     * returns what the printer says it is. This is the only call that waits that long; reads use a short wait.
     */
    fun connect(): SnapmakerSacp.MachineInfo? = open(CONNECT_HELLO_WAIT_MS).status.machine

    // ---- status ---------------------------------------------------------------------------------------------------------

    private fun current(): SnapmakerSacp.Status {
        val s = open(replyWaitMs)
        val deadline = clock() + statusWaitMs
        while (s.status.lastHeartbeatAt == 0L && !s.closed && clock() < deadline) Thread.sleep(50)
        if (s.status.currentLine != null && s.status.fileInfo == null) requestFileInfo(s)
        return s.status
    }

    override fun snapshot(): PrinterSnapshot { val st = current(); return st.snapshot(ready = !SnapmakerSacp.heartbeatStale(st, clock())) }
    override fun toolheadTemperatures(): List<ToolheadTemperature> = current().toolheads()

    override fun catalog(): Catalog = Catalog(emptyList(), emptyList(), emptyList(), listOf("Nozzle It All doesn't read a Snapmaker's file list or camera yet."))
    override fun image(camera: Camera): ByteArray = throw ApiFailure("Nozzle It All doesn't show this printer's camera yet.")

    // ---- commands -------------------------------------------------------------------------------------------------------

    override fun command(command: PrinterCommand) {
        command.prusaLinkPrintRequest?.let { send(it); return } // the plain-G-code upload request the other LAN kinds share
        when {
            command.path == "printer/print/start" -> {
                val name = command.arguments["filename"]?.takeIf(String::isNotBlank) ?: throw ApiFailure("Missing filename.")
                SnapmakerSacp.requireStartVerified(name, uploaded = false)
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

    /** Refused before anything is sent while SnapmakerSacp.START_VERIFIED is false; not built past the gate either. */
    private fun notBuilt(what: String): Nothing {
        SnapmakerSacp.requireControlVerified(what)
        throw ApiFailure("Unsupported command for a Snapmaker printer: $what isn't built yet. Use the printer's screen.")
    }

    /**
     * Uploads the sliced file (luban/SacpClient.ts:933-996): announce it to the screen, serve the chunks the printer asks
     * for, and wait for its result (0 is success). An upload never starts a print; the start (`0xb0/0x08`) is refused
     * afterwards with the reason while SnapmakerSacp.START_VERIFIED is false, and isn't built past that gate.
     */
    private fun send(request: PrusaLinkPrintRequest) {
        val file = request.file
        if (!file.isFile) throw ApiFailure("The sliced file is missing. Slice again.")
        val name = SnapmakerSacp.safeFileName(request.remoteName)
        val md5 = file.inputStream().use { SnapmakerSacp.md5Hex(it) }
        val payload = try { SnapmakerSacp.uploadStartPayload(name, file.length(), md5) }
            catch (e: IllegalArgumentException) { throw ApiFailure("Nothing was sent: ${e.message}") }
        val s = open(replyWaitMs)
        val up = Upload(file, md5)
        synchronized(lock) {
            if (s.upload?.done?.isDone == false) throw ApiFailure("Nothing was sent: another upload to this printer is still running.")
            s.upload = up
        }
        up.lastActivity.set(clock())
        try {
            // Luban doesn't read this ACK's result; a refusal (non-zero) is taken as a failed upload here.
            s.send(SnapmakerSacp.UPLOAD_START, SnapmakerSacp.PEER_SCREEN, payload).thenAccept { p ->
                val r = SnapmakerSacp.parseResponse(p.payload)
                if (r.result != 0) up.done.completeExceptionally(ApiFailure("The Snapmaker refused the upload of $name (result ${r.result})."))
            }
            val result = waitForUpload(up, name)
            if (result != 0) throw ApiFailure("Could not upload $name to the Snapmaker (result $result).")
        } finally {
            s.upload = null
        }
        SnapmakerSacp.requireStartVerified(name)
        notBuilt("starting a print")
    }

    /** Waits while the printer keeps asking for chunks; gives up after [uploadIdleMs] without a request. */
    private fun waitForUpload(up: Upload, name: String): Int {
        while (true) {
            try {
                return up.done.get(200, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                if (clock() - up.lastActivity.get() > uploadIdleMs) throw ApiFailure("Could not upload $name to the Snapmaker: it stopped asking for the file.")
            } catch (e: ExecutionException) {
                throw (e.cause as? ApiFailure) ?: ApiFailure("Could not upload $name to the Snapmaker.")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ApiFailure("Could not upload $name to the Snapmaker.")
            }
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

    override fun close() { val s = synchronized(lock) { session.also { session = null } }; s?.close() }

    companion object {
        const val CONNECT_TIMEOUT_MS = 5_000L
        /**
         * How long a request waits for its ACK. Luban's SACP requests wait without a limit unless sent with retransmit
         * (sdk/communication/Dispatcher.js:120-148, Communication.js:77-117), which these aren't; a limit is this port's.
         */
        const val REPLY_WAIT_MS = 5_000L
        /** A fresh session's wait for the first heartbeat, which the 1000 ms subscription should bring. */
        const val STATUS_WAIT_MS = 3_000L
        /** How long "Connect" waits for the person to accept on the printer's screen. This port's choice. */
        const val CONNECT_HELLO_WAIT_MS = 60_000L
        /** An upload is given up after this long without a chunk request. This port's choice. */
        const val UPLOAD_IDLE_MS = 30_000L
        const val CLOSE_WAIT_MS = 1_000L
        const val NEEDS_CONNECT = "Nozzle It All hasn't connected to this Snapmaker yet. Tap Connect in Edit printer and accept on the printer's screen."
        const val CLOSED = "The Snapmaker closed the connection."
        const val NO_ANSWER = "The Snapmaker didn't answer."
    }
}

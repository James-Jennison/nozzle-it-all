package net.jamesjennison.klippercompanion

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * PrinterKind.FLASHFORGE saved WITHOUT a serial number and access code: an older Flashforge (Adventurer 3 / 4, Creator,
 * Guider) on its legacy TCP console, port 8899, as upstream OrcaSlicer decides it (FlashforgeLegacy.usesLegacy). The lines
 * and framing are FlashforgeLegacy's (ported from Flashforge.cpp and TCPConsole.cpp; docs/upstream/PROVENANCE.md P-0035);
 * this class only carries them, one short blocking socket per exchange as upstream opens one, with upstream's connect and
 * read timeouts. Callers run it off the main thread, as every other service.
 *
 * Offered: a connection check (`~M601 S1`; state unknown, since upstream reads no replies) and uploading a sliced file
 * (`~M28` + data + `~M29`, never `~M23`). Starting it is refused until FlashforgeLegacy.START_VERIFIED. No other command
 * (pause, resume, cancel, homing, jogging, temperatures) is sent. No filament station over this protocol. Built from
 * sources only; not yet run against a real printer.
 */
class FlashforgeLegacyPrinterService(address: String, private val port: Int = FlashforgeLegacy.PORT,
                                     private val saveDelayMs: Long = FlashforgeLegacy.SAVE_DELAY_MS) : PrinterService, FilamentSlotReader {
    private val base = Moonraker.parseAddress(if (address.contains("://")) address else "http://$address/")
    // Orca addresses the console by host alone (TCPConsole(m_host, "8899")), whatever port was typed.
    private val host: String = base.host
    override val address: String get() = base.toString()
    @Volatile private var lastProbe = 0L
    @Volatile private var closed = false

    /** One upstream TCPConsole run: a fresh connection, [block], then close (TCPConsole.cpp:163-216). */
    private fun <T> session(deadlineMs: Long = 30_000, block: (Console) -> T): T {
        if (closed) throw ApiFailure("This printer connection is closed.")
        val socket = Socket()
        // A socket write has no timeout of its own (upstream's is 10 s per write): closing the socket at the deadline ends a
        // stalled write or read with an IOException instead of blocking the caller forever.
        val watchdog = Thread { try { Thread.sleep(deadlineMs); runCatching { socket.close() } } catch (_: InterruptedException) {} }.apply { isDaemon = true; start() }
        try {
            try { socket.connect(InetSocketAddress(host, port), FlashforgeLegacy.CONNECT_TIMEOUT_MS) }
            catch (e: IOException) { throw ApiFailure("Could not reach the Flashforge printer at $host:$port: ${e.message ?: "connection failed"}") }
            socket.soTimeout = FlashforgeLegacy.READ_TIMEOUT_MS
            socket.tcpNoDelay = true
            return try { block(Console(BufferedInputStream(socket.getInputStream()), socket.getOutputStream())) }
            catch (e: ApiFailure) { throw e }
            catch (e: IOException) { throw ApiFailure("The Flashforge printer at $host stopped answering: ${e.message ?: "connection lost"}") }
        } finally { watchdog.interrupt(); runCatching { socket.close() } }
    }

    /** TCPConsole's exchange: a command waits for a reply ending in a line `ok`; data is written without waiting. */
    private class Console(private val input: InputStream, private val output: OutputStream) {
        fun command(text: String): List<String> {
            output.write(FlashforgeLegacy.wire(text).toByteArray(Charsets.US_ASCII)); output.flush()
            val lines = mutableListOf<String>()
            while (true) {
                val line = readLine() ?: throw ApiFailure("The Flashforge printer closed the connection before answering ${text.trim()}.")
                if (FlashforgeLegacy.isDone(line)) return lines
                if (lines.size >= 500) throw ApiFailure("The Flashforge printer's reply to ${text.trim()} is longer than supported.")
                lines += line
            }
        }
        fun data(bytes: ByteArray, length: Int) { output.write(bytes, 0, length) }
        fun flush() = output.flush()
        /** One `\n`-terminated line (TCPConsole reads until its `\n` delimiter), at most 1024 bytes kept as upstream's getline buffer. */
        private fun readLine(): String? {
            val buffer = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (buffer.size() == 0) null else buffer.toString(Charsets.ISO_8859_1.name())
                if (b == '\n'.code) return buffer.toString(Charsets.ISO_8859_1.name())
                if (buffer.size() < 1024) buffer.write(b)
            }
        }
    }

    /** Upstream's connection test (Flashforge.cpp:329-345): `~M601 S1` alone. Repeated at most every 10 s. Nothing moves or heats. */
    override fun snapshot(): PrinterSnapshot {
        if (System.currentTimeMillis() - lastProbe > 10_000) {
            session { it.command(FlashforgeLegacy.CONTROL) }
            lastProbe = System.currentTimeMillis()
        }
        return FlashforgeLegacy.probeSnapshot()
    }

    override fun filamentSlots(): FilamentSlotStatus = FilamentSlotStatus(emptyList(), "")
    override fun catalog(): Catalog = Catalog(emptyList(), emptyList(), emptyList(), listOf("Nozzle It All doesn't read a Flashforge printer's file list yet."))
    override fun image(camera: Camera): ByteArray = throw ApiFailure("Nozzle It All doesn't show this printer's camera yet.")

    override fun command(command: PrinterCommand) {
        command.prusaLinkPrintRequest?.let { sendAndStart(it); return } // the plain-G-code upload-and-start request the other LAN kinds share
        when (command.path) {
            "printer/print/start" -> {
                val name = command.arguments["filename"]?.takeIf(String::isNotBlank) ?: throw ApiFailure("Missing filename.")
                FlashforgeLegacy.requireStartVerified(name, uploaded = false)
                start(FlashforgeLegacy.fileName(name))
            }
            "printer/print/pause", "printer/print/resume", "printer/print/cancel" ->
                throw ApiFailure("Unsupported command for a Flashforge printer: pause, resume and cancel aren't built yet. Use the printer's screen.")
            else -> throw ApiFailure("Unsupported command for a Flashforge printer.")
        }
    }

    /** Uploads the sliced file; while FlashforgeLegacy.START_VERIFIED is false that is all, and `~M23` is refused. */
    private fun sendAndStart(request: PrusaLinkPrintRequest) {
        val file = request.file
        if (!file.isFile) throw ApiFailure("The sliced file is missing. Slice again.")
        val name = FlashforgeLegacy.fileName(request.remoteName, file.extension.takeIf { it.isNotEmpty() }?.let { ".$it" } ?: ".gcode")
        upload(file, name)
        FlashforgeLegacy.requireStartVerified(name)
        start(name)
    }

    /**
     * Flashforge.cpp:410-492: connect (`~M601 S1`, `~M115`, `~M650`, `~M119`); `~M28 <bytes> 0:/user/<name>` and the file
     * in 4096-byte pieces on a new connection; `~M29` on another after 3 s. Upstream goes on even when its connect fails;
     * here a failed connect stops the upload before `~M28`.
     */
    private fun upload(file: File, name: String) {
        try {
            session { c -> FlashforgeLegacy.connectSequence().forEach { c.command(it) } }
            session(deadlineMs = 300_000) { c ->
                c.command(FlashforgeLegacy.beginUpload(file.length(), name))
                val buffer = ByteArray(FlashforgeLegacy.CHUNK_BYTES)
                file.inputStream().use { input -> while (true) { val n = input.read(buffer); if (n < 0) break; if (n > 0) c.data(buffer, n) } }
                c.flush()
            }
            Thread.sleep(saveDelayMs)
            session { it.command(FlashforgeLegacy.SAVE_FILE) }
        } catch (e: ApiFailure) { throw ApiFailure("Could not upload $name: ${e.message}") }
    }

    /** Only reached with FlashforgeLegacy.START_VERIFIED: `~M23 0:/user/<name>` (Flashforge.cpp:393-408). */
    private fun start(name: String) {
        FlashforgeLegacy.requireStartVerified(name)
        try { session { it.command(FlashforgeLegacy.startPrint(name)) } }
        catch (e: ApiFailure) { throw ApiFailure("The printer may not have started $name: ${e.message}") }
    }

    override fun close() { closed = true }
}

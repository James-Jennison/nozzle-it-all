package net.jamesjennison.klippercompanion

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * PrinterKind.USB_SERIAL: a printer plugged in directly over USB, spoken to as a Marlin/Prusa-protocol serial port
 * (MarlinSerial for the line protocol, UsbSerial for the chip-level control transfers - both module :domain, pure
 * Kotlin, no android.hardware.usb). [port] does the actual bytes; [UsbSerialTransport] is the real implementation,
 * tests use an in-memory fake (see UsbSerialPrinterServiceTest.FakeUsbSerialPort).
 *
 * Connecting never restarts the printer: [ensureStarted] only ever calls [UsbSerialPort.open], never
 * [UsbSerialPort.restartBoard] - see UsbSerialPort.kt's own header. [restartBoard] is the one explicit, user-initiated
 * escape hatch, and the only place in this whole feature that may assert DTR.
 *
 * The only commands this class ever writes on its own are read-only/harmless: M110 N0, M115, M105 (only when the
 * firmware didn't advertise Cap:AUTOREPORT_TEMP:1), M155 S<n> (only when it did), M27, M20. Every other command
 * ([command]'s risky branches) is refused via [notBuilt] before a single byte is written - see UsbSerialPrinter.kt.
 */
class UsbSerialPrinterService(
    override val address: String,
    private val baud: Int,
    portFactory: () -> UsbSerialPort,
    private val clock: () -> Long = System::currentTimeMillis,
    private val autoReportIntervalSeconds: Int = 2,
    private val pollIntervalMs: Long = 3_000L,
    private val replyTimeoutMs: Long = 5_000L,
) : PrinterService {
    private val port: UsbSerialPort by lazy(portFactory)
    private val sendWindow = MarlinSerial.SendWindow()
    private val replyQueue = LinkedBlockingQueue<MarlinSerial.Reply>()
    private val lock = Any()
    @Volatile private var closed = false
    @Volatile private var started = false
    private var needsResync = false
    @Volatile private var capabilities = MarlinSerial.Capabilities()
    @Volatile private var lastTemperatures: MarlinSerial.TemperatureReport? = null
    @Volatile private var lastSdStatus: MarlinSerial.SdStatus = MarlinSerial.SdStatus(false, null, null)
    private var lastTempPollAt = 0L
    private var lastSdPollAt = 0L
    private val lineBuffer = StringBuilder()
    private var reader: Thread? = null

    /** Opens the port and runs the read-only handshake, exactly once. Never touches DTR/RTS beyond the chip's openSequence. */
    private fun ensureStarted() {
        if (started) return
        synchronized(lock) {
            if (started) return
            if (reader == null) { // a handshake that timed out is retried on the already-open port, not by reopening it
                port.open(baud)
                val t = Thread(::readLoop, "usb-serial-reader").apply { isDaemon = true }
                reader = t
                t.start()
            }
            sendAndAwaitOk(MarlinSerial.RESET_LINE_NUMBER)
            val m115 = sendAndCollect("M115")
            capabilities = MarlinSerial.parseCapabilities(m115)
            if (capabilities.supportsAutoReportTemp) sendAndAwaitOk("M155 S$autoReportIntervalSeconds")
            started = true
        }
    }

    private fun readLoop() {
        val buf = ByteArray(READ_BUFFER_BYTES) // at least one full high-speed bulk packet (512), or the read overflows
        while (!closed) {
            val n = try { port.read(buf, 200) } catch (_: Exception) { break }
            if (n <= 0) continue
            for (i in 0 until n) {
                val b = buf[i]
                if (b == '\n'.code.toByte()) {
                    val line = lineBuffer.toString()
                    lineBuffer.clear()
                    handleLine(line)
                } else if (b != '\r'.code.toByte()) {
                    lineBuffer.append(b.toInt().toChar())
                }
            }
        }
    }

    private fun handleLine(line: String) {
        val reply = MarlinSerial.parseReply(line)
        when (reply) {
            is MarlinSerial.Reply.Temperature -> lastTemperatures = reply.report
            is MarlinSerial.Reply.Ok -> reply.temperatures?.let { lastTemperatures = it }
            else -> Unit
        }
        replyQueue.offer(reply)
    }

    /** Sends [command] and waits for its "ok" (handling Resend/Busy), throwing on timeout. */
    private fun sendAndAwaitOk(command: String) {
        exchange(command)
    }

    /**
     * Like [sendAndAwaitOk] but also returns every non-ok line's text (M115/M27/M20 replies are multi-line) up to the
     * "ok", joined for the domain parsers to read.
     */
    private fun sendAndCollect(command: String): String = exchange(command)

    /**
     * The one send path. Lines left over from before this command (an unsolicited "echo:", or the reply to a command
     * that already timed out) are dropped first, so they're never read as this command's reply - temperature pushes
     * among them were already recorded by [handleLine]. After a timeout the firmware's line counter is unknown, so
     * the next exchange renumbers with M110 N0 before sending anything else.
     */
    private fun exchange(command: String): String {
        if (needsResync) {
            needsResync = false
            exchange(MarlinSerial.RESET_LINE_NUMBER)
        }
        replyQueue.clear()
        var text = sendWindow.prepareSend(command)
        port.write(text.toByteArray(Charsets.US_ASCII))
        val collected = StringBuilder()
        val deadline = clock() + replyTimeoutMs
        while (true) {
            val remaining = deadline - clock()
            val reply = if (remaining > 0) replyQueue.poll(remaining, TimeUnit.MILLISECONDS) else null
            if (reply == null) {
                if (clock() < deadline) continue
                sendWindow.abandon()
                needsResync = true
                throw ApiFailure("The printer didn't answer ${command.trim()}.")
            }
            if (reply is MarlinSerial.Reply.Other) collected.appendLine(reply.line)
            when (val outcome = sendWindow.onReply(reply)) {
                MarlinSerial.SendWindow.Outcome.Cleared -> return collected.toString()
                is MarlinSerial.SendWindow.Outcome.Resend -> { text = outcome.text; port.write(text.toByteArray(Charsets.US_ASCII)) }
                MarlinSerial.SendWindow.Outcome.StillWaiting -> Unit
            }
        }
    }

    override fun snapshot(): PrinterSnapshot {
        ensureStarted()
        synchronized(lock) {
            val now = clock()
            if (!capabilities.supportsAutoReportTemp && now - lastTempPollAt >= pollIntervalMs) {
                sendAndAwaitOk("M105")
                lastTempPollAt = now
            }
            if (now - lastSdPollAt >= pollIntervalMs) {
                lastSdStatus = MarlinSerial.parseSdStatus(sendAndCollect("M27"))
                lastSdPollAt = now
            }
        }
        val sd = lastSdStatus
        val state = if (sd.printing) "printing" else "standby"
        val total = sd.bytesTotal
        val printed = sd.bytesPrinted
        val progress = if (sd.printing && total != null && total > 0 && printed != null)
            (printed.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
        return PrinterSnapshot(ready = true, state = state, filename = sd.filename, progress = progress)
    }

    override fun toolheadTemperatures(): List<ToolheadTemperature> {
        ensureStarted()
        val report = lastTemperatures ?: return emptyList()
        val result = mutableListOf<ToolheadTemperature>()
        for (h in report.hotends) result.add(ToolheadTemperature(name = if (h.index == 0) "extruder" else "extruder${h.index}", temperature = h.current, target = h.target))
        report.bed?.let { result.add(ToolheadTemperature(name = "bed", temperature = it.current, target = it.target)) }
        return result
    }

    override fun catalog(): Catalog {
        ensureStarted()
        val files = try { synchronized(lock) { MarlinSerial.parseFileList(sendAndCollect("M20")) } } catch (_: ApiFailure) { emptyList() }
        val warning = "USB-connected printers aren't verified on real hardware yet (firmware: ${capabilities.firmwareName.ifBlank { "unknown" }})."
        return Catalog(files, emptyList(), emptyList(), listOf(warning))
    }

    override fun image(camera: Camera): ByteArray = throw ApiFailure("A USB-connected printer has no camera.")

    override fun command(command: PrinterCommand) {
        command.prusaLinkPrintRequest?.let { throw ApiFailure(UsbSerialPrinter.CANNOT_SAVE_TO_PRINTER) }
        when {
            command.path == "printer/print/start" -> {
                val name = command.arguments["filename"]?.takeIf(String::isNotBlank) ?: throw ApiFailure("Missing filename.")
                UsbSerialPrinter.requireStartVerified(name)
                notBuilt("starting a print")
            }
            command.path == "printer/print/pause" -> notBuilt("pausing a print")
            command.path == "printer/print/resume" -> notBuilt("resuming a print")
            command.path == "printer/print/cancel" -> notBuilt("stopping a print")
            command.heaterRequest != null -> notBuilt("setting a temperature")
            command.fanRequest != null -> notBuilt("running a fan")
            command.toolRequest != null -> notBuilt("switching the nozzle")
            command.path == "printer/gcode/script" || command.path == "printer/emergency_stop" -> notBuilt("homing, moving or heating")
            else -> throw ApiFailure("Unsupported command for a USB-connected printer.")
        }
    }

    /** Refused before anything is sent while UsbSerialPrinter.START_VERIFIED is false; not built past the gate either. */
    private fun notBuilt(what: String): Nothing {
        UsbSerialPrinter.requireControlVerified(what)
        throw ApiFailure("Unsupported command for a USB-connected printer: $what isn't built yet.")
    }

    /**
     * The explicit, user-initiated "Restart the printer's board to connect" action. The ONLY call in this class (or
     * anywhere in this feature) that may assert DTR - see UsbSerialPort.kt. Must not be used while printing; the
     * caller (the UI) is responsible for that warning, this call itself has no notion of "while printing" to check.
     */
    fun restartBoard() {
        ensureStarted()
        port.restartBoard()
    }

    override fun close() {
        closed = true
        runCatching { port.close() }
    }

    private companion object {
        const val READ_BUFFER_BYTES = 4096
    }
}

package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// PrinterKind.SNAPMAKER_SACP's service against a fake J1 / Artisan: a ServerSocket on 127.0.0.1 that speaks SACP with the
// codec under test (SnapmakerSacp, whose bytes SnapmakerSacpTest pins to known answers). It answers the way Luban expects
// a printer to (docs/upstream/PROVENANCE.md P-0037): ACKs matched on command and sequence, subscription notifications
// as `[result 0, data]`, and an upload the printer drives by asking for chunks (luban/SacpClient.ts:933-996). No real
// printer, no network beyond 127.0.0.1.
class SnapmakerSacpPrinterServiceTest {
    private class FakePrinter(
        val helloResult: Int = 0,
        val uploadResult: Int = 0,
        val uploadStartResult: Int = 0,
    ) : AutoCloseable {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val accepted = AtomicInteger(0)
        val received = CopyOnWriteArrayList<SnapmakerSacp.Packet>()
        val receivedBytes = CopyOnWriteArrayList<ByteArray>()
        @Volatile var uploaded: ByteArray? = null
        @Volatile var uploadedName: String? = null
        private val sockets = CopyOnWriteArrayList<Socket>()

        init {
            Thread({
                try {
                    while (true) {
                        val socket = server.accept()
                        accepted.incrementAndGet(); sockets += socket
                        Thread({ serve(socket) }, "fake-snapmaker").apply { isDaemon = true }.start()
                    }
                } catch (_: IOException) {}
            }, "fake-snapmaker-accept").apply { isDaemon = true }.start()
        }

        val address get() = "127.0.0.1:${server.localPort}"
        fun commands() = received.filter { !it.isAck }.map { it.command }

        private fun serve(socket: Socket) {
            val out = socket.getOutputStream()
            val decoder = SnapmakerSacp.Decoder()
            val buf = ByteArray(8192)
            var seq = 100
            var md5 = ""; var chunks = 0; var nextChunk = 0; var data = ByteArray(0)
            fun write(p: SnapmakerSacp.Packet) = synchronized(out) { out.write(SnapmakerSacp.encode(p)); out.flush() }
            fun ack(p: SnapmakerSacp.Packet, payload: ByteArray) =
                write(SnapmakerSacp.Packet(p.sender, p.receiver, SnapmakerSacp.ATTR_ACK, p.sequence, p.commandSet, p.commandId, payload))
            fun request(command: Pair<Int, Int>, payload: ByteArray) {
                seq++
                write(SnapmakerSacp.Packet(SnapmakerSacp.PEER_LUBAN, SnapmakerSacp.PEER_SCREEN, SnapmakerSacp.ATTR_REQUEST, seq, command.first, command.second, payload))
            }
            fun notify(command: Pair<Int, Int>, hexData: String) = request(command, SnapmakerSacp.responsePayload(0, hex(hexData)))
            try {
                val input = socket.getInputStream()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    receivedBytes += buf.copyOf(n)
                    for (p in decoder.feed(buf, 0, n)) {
                        received += p
                        if (p.isAck) {
                            if (p.command == SnapmakerSacp.UPLOAD_CHUNK) {
                                val r = SnapmakerSacp.Reader(p.payload)
                                assertEquals(0, r.u8()); assertEquals(md5, r.str()); assertEquals(nextChunk, r.u16())
                                val len = r.u16(); data += p.payload.copyOfRange(r.offset, r.offset + len)
                                nextChunk++
                                if (nextChunk < chunks) request(SnapmakerSacp.UPLOAD_CHUNK, SnapmakerSacp.Writer().str(md5).u16(nextChunk).build())
                                else { uploaded = data; request(SnapmakerSacp.UPLOAD_DONE, byteArrayOf(uploadResult.toByte())) }
                            }
                            continue
                        }
                        when (p.command) {
                            SnapmakerSacp.HELLO -> ack(p, byteArrayOf(helloResult.toByte()))
                            SnapmakerSacp.MACHINE_INFO -> ack(p, SnapmakerSacp.responsePayload(0, hex("04024e61bc000500312e322e33")))
                            SnapmakerSacp.FILE_INFO -> {
                                ack(p, SnapmakerSacp.responsePayload(0, hex("0a00637562652e67636f6465d0070000100e0000")))
                                // All five subscriptions are acknowledged by now: the notifications (sdk/communication/Dispatcher.js:96-103).
                                notify(SnapmakerSacp.NOZZLES, "000100010001009001000044360300d8470300")
                                notify(SnapmakerSacp.BED, "00010060ea00003c00")
                                notify(SnapmakerSacp.CURRENT_LINE, "e8030000")
                                notify(SnapmakerSacp.PRINTING_TIME, "3c000000")
                                notify(SnapmakerSacp.HEARTBEAT, "01")
                            }
                            SnapmakerSacp.UPLOAD_START -> {
                                val r = SnapmakerSacp.Reader(p.payload)
                                uploadedName = r.str(); r.u32(); chunks = r.u16(); md5 = r.str()
                                ack(p, byteArrayOf(uploadStartResult.toByte()))
                                if (uploadStartResult == 0) { nextChunk = 0; data = ByteArray(0); request(SnapmakerSacp.UPLOAD_CHUNK, SnapmakerSacp.Writer().str(md5).u16(0).build()) }
                            }
                            else -> ack(p, byteArrayOf(0)) // hello heartbeat, subscribe, unsubscribe, bye
                        }
                    }
                }
            } catch (_: IOException) {
            } finally { runCatching { socket.close() } }
        }

        override fun close() { runCatching { server.close() }; sockets.forEach { runCatching { it.close() } } }
    }

    private fun service(printer: FakePrinter, identity: String = "Pixel 9", token: String = "") = SnapmakerSacpPrinterService(
        printer.address, identity, token, helloDelayMs = 0, replyWaitMs = 3_000, statusWaitMs = 3_000, uploadIdleMs = 3_000)

    private fun gcode(size: Int): File = File.createTempFile("cube", ".gcode").apply {
        deleteOnExit(); writeBytes(ByteArray(size) { (it % 251).toByte() })
    }

    @Test fun withoutAConnectionNameNothingIsSent() {
        FakePrinter().use { printer ->
            val s = service(printer, identity = "")
            try {
                assertEquals(SnapmakerSacpPrinterService.NEEDS_CONNECT, assertThrows(ApiFailure::class.java) { s.snapshot() }.message)
                assertEquals(SnapmakerSacpPrinterService.NEEDS_CONNECT, assertThrows(ApiFailure::class.java) {
                    s.command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(gcode(10), "cube.gcode")))
                }.message)
            } finally { s.close() }
            Thread.sleep(100)
            assertEquals("no TCP connection at all", 0, printer.accepted.get())
        }
    }

    @Test fun readsStatusFromTheSubscriptions() {
        FakePrinter().use { printer ->
            val s = service(printer, token = "hello-token")
            try {
                val snap = s.snapshot()
                assertTrue(snap.ready); assertEquals("unknown", snap.state)
                assertEquals(210.5, snap.nozzle!!, 1e-9); assertEquals(215.0, snap.nozzleTarget!!, 1e-9)
                assertEquals(60.0, snap.bed!!, 1e-9); assertEquals(60.0, snap.bedTarget!!, 1e-9)
                assertEquals("cube.gcode", snap.filename); assertEquals(0.5f, snap.progress); assertEquals(60.0, snap.printDuration!!, 1e-9)
                assertEquals("", snap.activeFilename) // no state, so never shown as printing
                assertEquals(listOf(ToolheadTemperature("Left nozzle", 210.5, 215.0)), s.toolheadTemperatures())
                // Luban's order (luban/SacpTcpChannel.ts:62-117, luban/SacpChannel.ts:896-1134), one TCP connection.
                assertEquals(1, printer.accepted.get())
                assertEquals(listOf(SnapmakerSacp.HELLO, SnapmakerSacp.HELLO_HEARTBEAT, SnapmakerSacp.MACHINE_INFO) +
                    List(5) { SnapmakerSacp.SUBSCRIBE } + listOf(SnapmakerSacp.FILE_INFO), printer.commands())
                // The hello's exact bytes: the first packet on the wire.
                val hello = SnapmakerSacp.encode(SnapmakerSacp.request(SnapmakerSacp.HELLO, SnapmakerSacp.PEER_SCREEN, 1,
                    SnapmakerSacp.helloPayload("Pixel 9", "Nozzle It All", "hello-token")))
                assertArrayEquals(hello, printer.receivedBytes.fold(ByteArray(0)) { a, b -> a + b }.copyOf(hello.size))
                val subs = printer.received.filter { it.command == SnapmakerSacp.SUBSCRIBE }
                assertEquals(SnapmakerSacp.STATUS_SUBSCRIPTIONS.map { SnapmakerSacp.subscribePayload(it).toList() }, subs.map { it.payload.toList() })
                assertTrue(subs.all { it.receiver == SnapmakerSacp.PEER_CONTROLLER })
            } finally { s.close() }
            // Closing unsubscribes and says goodbye to the screen.
            val deadline = System.currentTimeMillis() + 2_000
            while (SnapmakerSacp.BYE !in printer.commands() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(5, printer.commands().count { it == SnapmakerSacp.UNSUBSCRIBE })
            assertEquals(SnapmakerSacp.BYE, printer.commands().last())
        }
    }

    @Test fun connectReturnsWhatThePrinterIs() {
        FakePrinter().use { printer ->
            val s = service(printer)
            try {
                assertEquals(SnapmakerSacp.MachineInfo(4, 2, 12345678L, "1.2.3"), s.connect())
            } finally { s.close() }
        }
    }

    @Test fun aRefusedHelloIsReported() {
        FakePrinter(helloResult = 1).use { printer ->
            val s = service(printer)
            try {
                val m = assertThrows(ApiFailure::class.java) { s.snapshot() }.message!!
                assertTrue(m, m.startsWith("The Snapmaker refused the connection (result 1)."))
                assertEquals("nothing after the refused hello", listOf(SnapmakerSacp.HELLO), printer.commands())
            } finally { s.close() }
        }
    }

    @Test fun sendingUploadsTheFileAndNeverStartsAPrint() {
        assertFalse(SnapmakerSacp.START_VERIFIED)
        FakePrinter().use { printer ->
            val s = service(printer)
            try {
                val file = gcode(SnapmakerSacp.CHUNK_BYTES + 1000) // two chunks
                val refusal = assertThrows(ApiFailure::class.java) {
                    s.command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(file, "cube.gcode")))
                }
                assertEquals(SnapmakerSacp.startNotVerified("cube.gcode"), refusal.message)
                assertEquals("cube.gcode", printer.uploadedName)
                assertArrayEquals(file.readBytes(), printer.uploaded)
                val start = printer.received.first { it.command == SnapmakerSacp.UPLOAD_START }
                assertEquals(SnapmakerSacp.PEER_SCREEN, start.receiver)
                assertArrayEquals(SnapmakerSacp.uploadStartPayload("cube.gcode", file.length(), file.inputStream().use { SnapmakerSacp.md5Hex(it) }), start.payload)
                // The upload's own ACKs (chunks, result) plus nothing that starts, heats or moves anything.
                val allowed = setOf(SnapmakerSacp.HELLO, SnapmakerSacp.HELLO_HEARTBEAT, SnapmakerSacp.MACHINE_INFO, SnapmakerSacp.SUBSCRIBE,
                    SnapmakerSacp.FILE_INFO, SnapmakerSacp.UPLOAD_START)
                assertTrue(printer.commands().toString(), printer.commands().all { it in allowed })
                assertEquals(2, printer.received.count { it.isAck && it.command == SnapmakerSacp.UPLOAD_CHUNK })
                // The result's ACK is written just before the upload returns; give the fake a moment to read it.
                val deadline = System.currentTimeMillis() + 2_000
                while (printer.received.none { it.isAck && it.command == SnapmakerSacp.UPLOAD_DONE } && System.currentTimeMillis() < deadline) Thread.sleep(20)
                assertEquals(1, printer.received.count { it.isAck && it.command == SnapmakerSacp.UPLOAD_DONE })
            } finally { s.close() }
        }
    }

    @Test fun aFailedUploadIsReported() {
        FakePrinter(uploadResult = 3).use { printer ->
            val s = service(printer)
            try {
                assertEquals("Could not upload cube.gcode to the Snapmaker (result 3).", assertThrows(ApiFailure::class.java) {
                    s.command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(gcode(100), "cube.gcode")))
                }.message)
            } finally { s.close() }
        }
        FakePrinter(uploadStartResult = 9).use { printer ->
            val s = service(printer)
            try {
                assertEquals("The Snapmaker refused the upload of cube.gcode (result 9).", assertThrows(ApiFailure::class.java) {
                    s.command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(gcode(100), "cube.gcode")))
                }.message)
            } finally { s.close() }
        }
    }

    @Test fun everyGatedControlIsRefusedWithNothingSent() {
        FakePrinter().use { printer ->
            val s = service(printer)
            try {
                val commands = listOf(
                    PrinterCommand("Start", "printer/print/start", mapOf("filename" to "a.gcode")),
                    PrinterCommand("Pause", "printer/print/pause"),
                    PrinterCommand("Resume", "printer/print/resume"),
                    PrinterCommand("Cancel", "printer/print/cancel"),
                    PrinterCommand("Home", "printer/gcode/script", mapOf("script" to "G28")),
                    PrinterCommand("Stop", "printer/emergency_stop"),
                    PrinterCommand("Heat", "", heaterRequest = HeaterRequest("extruder", "210")),
                    PrinterCommand("Tool", "", toolRequest = ToolRequest("T1")),
                )
                for (c in commands) {
                    val m = assertThrows(ApiFailure::class.java) { s.command(c) }.message!!
                    assertTrue("${c.title}: $m", m.contains("isn't verified on real hardware yet"))
                }
                assertEquals(SnapmakerSacp.controlNotVerified("pausing a print"), assertThrows(ApiFailure::class.java) { s.command(commands[1]) }.message)
            } finally { s.close() }
            Thread.sleep(100)
            assertEquals("no TCP connection at all", 0, printer.accepted.get())
        }
    }

    private companion object {
        fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}

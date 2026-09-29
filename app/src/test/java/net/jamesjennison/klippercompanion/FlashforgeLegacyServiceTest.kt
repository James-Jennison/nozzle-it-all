package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

// PrinterKind.FLASHFORGE without credentials (the legacy port-8899 console) against a fake TCP printer on 127.0.0.1 that
// answers every command line with CONSTRUCTED replies ending in `ok` (the only thing upstream OrcaSlicer's TCPConsole reads).
// No real printer. The point: upstream's connect / ~M28 + data / ~M29 sequence goes out, and ~M23 never does while
// FlashforgeLegacy.START_VERIFIED is false.
class FlashforgeLegacyServiceTest {
    /** One accepted connection: the command lines it got (without line ends) and, after `~M28`, the raw bytes. */
    private class Connection { val lines = CopyOnWriteArrayList<String>(); val data = ByteArrayOutputStream() }

    private class FakePrinter : AutoCloseable {
        val server = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        val connections = CopyOnWriteArrayList<Connection>()
        private val acceptor = Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                Thread { serve(socket) }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }

        private fun readLine(input: InputStream): String? {
            val b = ByteArrayOutputStream()
            while (true) { val c = input.read(); if (c < 0) return if (b.size() == 0) null else b.toString("US-ASCII"); if (c == '\n'.code) return b.toString("US-ASCII"); b.write(c) }
        }

        private fun serve(socket: Socket) = socket.use { s ->
            val conn = Connection().also { connections += it }
            val input = s.getInputStream(); val out = s.getOutputStream()
            while (true) {
                val line = readLine(input)?.trim() ?: break
                if (line.isEmpty()) continue // the "\n" after a command's own "\r\n"
                conn.lines += line
                out.write("CMD ${line.removePrefix("~").substringBefore(' ')} Received.\r\nok\r\n".toByteArray()); out.flush()
                if (line.startsWith("~M28")) { input.copyTo(conn.data); break }
            }
        }
        override fun close() { server.close() }
    }

    private fun service(fake: FakePrinter) = FlashforgeLegacyPrinterService("http://127.0.0.1/", port = fake.server.localPort, saveDelayMs = 0)

    @Test fun theConnectionCheckIsUpstreamsControlCommandOnly() {
        FakePrinter().use { fake ->
            val s = service(fake)
            try {
                val snap = s.snapshot()
                assertEquals("unknown", snap.state); assertFalse(snap.ready)
                assertEquals(listOf("~M601 S1"), fake.connections.single().lines)
                assertTrue(s.filamentSlots().slots.isEmpty())
            } finally { s.close() }
        }
    }

    @Test fun sendUploadsWithM28DataAndM29AndNeverM23() {
        assertFalse(FlashforgeLegacy.START_VERIFIED)
        FakePrinter().use { fake ->
            val s = service(fake)
            try {
                val content = (1..3000).joinToString("\n") { "G1 X$it Y$it" } // > 4096 bytes: several pieces
                val file = File.createTempFile("cube", ".gcode").apply { deleteOnExit(); writeText(content) }
                val refusal = assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(file, "my cube.gcode"))) }
                assertEquals(FlashforgeLegacy.startNotVerified("my_cube.gcode"), refusal.message)
                val conns = fake.connections.toList()
                assertEquals(3, conns.size)
                assertEquals(listOf("~M601 S1", "~M115", "~M650", "~M119"), conns[0].lines)
                assertEquals(listOf("~M28 ${file.length()} 0:/user/my_cube.gcode"), conns[1].lines)
                // The fake drains the data on its own thread; give it a moment to reach the end of the stream.
                val until = System.currentTimeMillis() + 5_000
                while (conns[1].data.size() < file.length() && System.currentTimeMillis() < until) Thread.sleep(10)
                assertEquals(content, conns[1].data.toString("UTF-8"))
                assertEquals(listOf("~M29"), conns[2].lines)
                assertTrue("no ~M23", fake.connections.flatMap { it.lines }.none { it.startsWith("~M23") })
            } finally { s.close() }
        }
    }

    @Test fun startsAndControlsAreRefusedBeforeAnythingIsSent() {
        FakePrinter().use { fake ->
            val s = service(fake)
            try {
                val start = assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("Start", "printer/print/start", mapOf("filename" to "a.gcode"))) }
                assertEquals(FlashforgeLegacy.startNotVerified("a.gcode", uploaded = false), start.message)
                for (path in listOf("printer/print/pause", "printer/print/resume", "printer/print/cancel", "printer/gcode/script"))
                    assertTrue(assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("x", path)) }.message!!.contains("Unsupported command"))
                assertTrue("nothing reached the printer", fake.connections.isEmpty())
            } finally { s.close() }
        }
    }

    @Test fun theAppPicksTheLegacyConsoleOnlyWithoutBothCredentials() {
        fun serviceFor(serial: String, code: String): PrinterService =
            printerServiceFor(PrinterProfile("http://127.0.0.1/", kind = PrinterKind.FLASHFORGE, serial = serial, apiKey = code), "http://127.0.0.1/")
        for ((serial, code, legacy) in listOf(Triple("", "", true), Triple("SN1", "", true), Triple("", "12345678", true), Triple("SN1", "12345678", false))) {
            val s = serviceFor(serial, code)
            try { assertEquals("$serial/$code", legacy, s is FlashforgeLegacyPrinterService); assertEquals(!legacy, s is FlashforgePrinterService) } finally { s.close() }
        }
    }
}

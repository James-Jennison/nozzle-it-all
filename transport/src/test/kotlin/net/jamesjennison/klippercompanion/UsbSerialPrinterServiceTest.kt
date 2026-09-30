package net.jamesjennison.klippercompanion

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UsbSerialPrinterService] against an in-memory fake port: connect sequence, only-whitelisted-commands-written,
 * DTR-never-raised-without-explicit-restartBoard, gated actions write zero bytes, resend and timeout recovery.
 */
class UsbSerialPrinterServiceTest {

    /**
     * A USB port with a small Marlin behind it: every written line is checked the way Marlin's queue.cpp does (checksum,
     * N must be last+1, "M110 N<k>" sets last to k) and answered - a bad line gets "Error:...", "Resend: <last+1>",
     * "ok" exactly as flush_and_request_resend sends them. Records every open()/write()/restartBoard() call.
     */
    private class FakeUsbSerialPort(
        private val capabilityLines: List<String> = emptyList(),
        /** Line numbers whose first copy arrives corrupted (the firmware asks for them again). */
        private val corruptOnce: MutableSet<Long> = mutableSetOf(),
        /** Commands whose first reply is lost entirely (nothing comes back), e.g. "M27". */
        private val silentOnce: MutableSet<String> = mutableSetOf(),
    ) : UsbSerialPort {
        override val deviceLabel: String = "fake"
        val openCalls = CopyOnWriteArrayList<Int>()
        val restartBoardCalls = AtomicInteger(0)
        val writes = CopyOnWriteArrayList<String>()
        val resendRequests = AtomicInteger(0)
        private val outgoing = LinkedBlockingQueue<String>()
        private var lastN = 0L
        @Volatile var closed = false

        override fun open(baud: Int) { openCalls.add(baud) }
        override fun restartBoard() { restartBoardCalls.incrementAndGet() }
        override fun write(data: ByteArray): Int {
            val text = String(data, Charsets.US_ASCII)
            writes.add(text)
            receive(text.trimEnd('\n'))
            return data.size
        }
        override fun read(buffer: ByteArray, timeoutMs: Int): Int {
            val line = outgoing.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return 0
            val bytes = "$line\r\n".toByteArray(Charsets.US_ASCII)
            bytes.copyInto(buffer)
            return bytes.size
        }
        override fun close() { closed = true }

        private fun receive(line: String) {
            val m = Regex("""^N(\d+) (.*)\*(\d+)$""").find(line) ?: error("unnumbered line written: $line")
            val n = m.groupValues[1].toLong()
            val command = m.groupValues[2]
            val body = "N$n $command"
            val renumber = Regex("""^M110 N(\d+)""").find(command)?.groupValues?.get(1)?.toLong()
            val corrupt = corruptOnce.remove(n)
            if (corrupt || m.groupValues[3].toInt() != MarlinSerial.checksum(body)) return requestResend("checksum mismatch")
            if (renumber == null && n != lastN + 1) return requestResend("Line Number is not Last Line Number+1")
            lastN = renumber ?: n
            val word = command.substringBefore(' ')
            if (silentOnce.remove(word)) return
            when (word) {
                "M115" -> { outgoing += "FIRMWARE_NAME:Marlin 2.1.2"; capabilityLines.forEach { outgoing += it }; outgoing += "ok" }
                "M105" -> outgoing += "ok T:21.0 /0.0 B:20.5 /0.0"
                "M27" -> { outgoing += "Not SD printing"; outgoing += "ok" }
                "M20" -> { outgoing += "Begin file list"; outgoing += "BENCHY.GCO 1024"; outgoing += "End file list"; outgoing += "ok" }
                else -> outgoing += "ok"
            }
        }

        private fun requestResend(why: String) {
            resendRequests.incrementAndGet()
            outgoing += "Error:$why, Last Line: $lastN"
            outgoing += "Resend: ${lastN + 1}"
            outgoing += "ok"
        }
    }

    private fun service(port: FakeUsbSerialPort, replyTimeoutMs: Long = 2_000L): UsbSerialPrinterService =
        UsbSerialPrinterService(address = "usb:1:2:ABC", baud = 115_200, portFactory = { port }, replyTimeoutMs = replyTimeoutMs)

    @Test fun `connecting opens the port and runs only the read-only handshake`() {
        val port = FakeUsbSerialPort()
        val svc = service(port)
        val snap = svc.snapshot()
        assertEquals("standby", snap.state)
        assertEquals(listOf(115_200), port.openCalls)
        assertEquals(MarlinSerial.numberedLine(0, "M110 N0"), port.writes[0])
        assertEquals(MarlinSerial.numberedLine(1, "M115"), port.writes[1])
        // No AUTOREPORT_TEMP capability was advertised, so M155 must never be sent; M105 (poll) is allowed.
        assertFalse(port.writes.any { it.contains("M155") })
        val allowed = setOf("M110", "M115", "M105", "M155", "M27", "M20")
        for (w in port.writes) assertTrue("unexpected command written: $w", allowed.any { w.contains(it) })
        assertEquals("the firmware never had to ask for a line again", 0, port.resendRequests.get())
        assertEquals(21.0, svc.toolheadTemperatures().first { it.name == "extruder" }.temperature!!, 0.001)
        assertEquals(listOf("BENCHY.GCO"), svc.catalog().files)
        assertEquals(0, port.resendRequests.get())
    }

    @Test fun `M155 is sent only when the firmware advertises AUTOREPORT_TEMP`() {
        val port = FakeUsbSerialPort(capabilityLines = listOf("Cap:AUTOREPORT_TEMP:1"))
        service(port).snapshot()
        assertTrue(port.writes.any { it.contains("M155 S2") })
        // Auto-report firmware must never be polled with M105.
        assertFalse(port.writes.any { it.contains("M105") })
    }

    @Test fun `DTR is never raised except through the explicit restartBoard call`() {
        val port = FakeUsbSerialPort()
        val svc = service(port)
        svc.snapshot()
        svc.toolheadTemperatures()
        svc.catalog()
        assertEquals(0, port.restartBoardCalls.get())
        svc.restartBoard()
        assertEquals(1, port.restartBoardCalls.get())
    }

    @Test fun `starting a print is refused and writes zero bytes`() {
        val port = FakeUsbSerialPort()
        val svc = service(port)
        try {
            svc.command(PrinterCommand(title = "Start", path = "printer/print/start", arguments = mapOf("filename" to "benchy.gcode")))
            org.junit.Assert.fail("expected ApiFailure")
        } catch (e: ApiFailure) {
            assertTrue(e.message!!.contains("aren't verified on real hardware yet") || e.message!!.contains("isn't verified on real hardware yet"))
        }
        assertTrue(port.writes.isEmpty())
        assertTrue(port.openCalls.isEmpty())
    }

    @Test fun `every gated control path writes zero bytes`() {
        val gatedCommands = listOf(
            PrinterCommand(title = "Pause", path = "printer/print/pause"),
            PrinterCommand(title = "Resume", path = "printer/print/resume"),
            PrinterCommand(title = "Cancel", path = "printer/print/cancel"),
            PrinterCommand(title = "Heat", path = "printer/heater", heaterRequest = HeaterRequest("extruder", "200")),
            PrinterCommand(title = "Fan", path = "printer/fan", fanRequest = FanRequest("fan", "100", "extruder")),
            PrinterCommand(title = "Tool", path = "printer/tool", toolRequest = ToolRequest("extruder1")),
            PrinterCommand(title = "Gcode", path = "printer/gcode/script"),
            PrinterCommand(title = "Estop", path = "printer/emergency_stop"),
        )
        for (cmd in gatedCommands) {
            val port = FakeUsbSerialPort()
            val svc = service(port)
            try {
                svc.command(cmd)
                org.junit.Assert.fail("expected ApiFailure for ${cmd.path}")
            } catch (_: ApiFailure) { /* expected */ }
            assertTrue("unexpected bytes written for ${cmd.path}", port.writes.isEmpty())
        }
    }

    @Test fun `uploading a sliced file is refused with the manual-copy message and writes zero bytes`() {
        val port = FakeUsbSerialPort()
        val svc = service(port)
        val file = kotlin.io.path.createTempFile().toFile().apply { writeText("G28\n") }
        try {
            svc.command(PrinterCommand(title = "Upload", path = "printer/print/start", prusaLinkPrintRequest = PrusaLinkPrintRequest(file)))
            org.junit.Assert.fail("expected ApiFailure")
        } catch (e: ApiFailure) {
            assertTrue(e.message!!.contains("SD card") || e.message!!.contains("USB stick"))
        }
        assertTrue(port.writes.isEmpty())
    }

    @Test fun `a resend is honored with the exact original numbered line, and the ok after the resend request isn't taken for it`() {
        val port = FakeUsbSerialPort(corruptOnce = mutableSetOf(1L)) // M115, line 1
        val svc = service(port)
        svc.snapshot()
        val m115Writes = port.writes.filter { it.contains("M115") }
        assertEquals(2, m115Writes.size)
        assertEquals(m115Writes[0], m115Writes[1]) // resend re-sends the exact same numbered/checksummed line
        assertEquals("only the corrupted line was asked for again", 1, port.resendRequests.get())
        assertEquals("standby", svc.snapshot().state)
    }

    @Test fun `after a reply times out the next call renumbers and carries on`() {
        val port = FakeUsbSerialPort(silentOnce = mutableSetOf("M27"))
        val svc = service(port, replyTimeoutMs = 300L)
        try {
            svc.snapshot()
            org.junit.Assert.fail("expected ApiFailure")
        } catch (e: ApiFailure) {
            assertTrue(e.message!!.contains("M27"))
        }
        val before = port.writes.size
        assertEquals("standby", svc.snapshot().state)
        val after = port.writes.drop(before)
        assertEquals(MarlinSerial.numberedLine(0, "M110 N0"), after.first())
        assertEquals(MarlinSerial.numberedLine(1, "M27"), after[1])
        assertEquals(listOf(115_200), port.openCalls) // recovered on the open port, never reopened
        assertEquals(0, port.resendRequests.get())
    }
}

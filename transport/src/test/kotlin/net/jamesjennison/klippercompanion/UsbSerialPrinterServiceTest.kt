package net.jamesjennison.klippercompanion

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UsbSerialPrinterService] against an in-memory fake port: connect sequence, only-whitelisted-commands-written,
 * DTR-never-raised-without-explicit-restartBoard, gated actions write zero bytes, resend recovery.
 */
class UsbSerialPrinterServiceTest {

    /** Records every open()/write()/restartBoard() call; [scriptedReplies] are delivered to read() one at a time. */
    private class FakeUsbSerialPort : UsbSerialPort {
        override val deviceLabel: String = "fake"
        val openCalls = CopyOnWriteArrayList<Int>()
        val restartBoardCalls = AtomicInteger(0)
        val writes = CopyOnWriteArrayList<String>()
        val scriptedReplies = ConcurrentLinkedQueue<String>()
        @Volatile var closed = false

        override fun open(baud: Int) { openCalls.add(baud) }
        override fun restartBoard() { restartBoardCalls.incrementAndGet() }
        override fun write(data: ByteArray): Int {
            writes.add(String(data, Charsets.US_ASCII))
            return data.size
        }
        override fun read(buffer: ByteArray, timeoutMs: Int): Int {
            val line = scriptedReplies.poll() ?: run { Thread.sleep(1); return 0 }
            val bytes = "$line\n".toByteArray(Charsets.US_ASCII)
            bytes.copyInto(buffer)
            return bytes.size
        }
        override fun close() { closed = true }

        fun queueOkFor(handshake: Boolean = true) {
            // M110 N0 -> ok, M115 -> capability lines + ok (no AUTOREPORT_TEMP, so no M155 is sent).
            scriptedReplies += "ok"
            scriptedReplies += "FIRMWARE_NAME:Marlin"
            scriptedReplies += "ok"
        }
    }

    private fun service(port: FakeUsbSerialPort): UsbSerialPrinterService =
        UsbSerialPrinterService(address = "usb:1:2:ABC", baud = 115_200, portFactory = { port }, replyTimeoutMs = 2_000L)

    @Test fun `connecting opens the port and runs only the read-only handshake`() {
        val port = FakeUsbSerialPort()
        port.queueOkFor()
        port.scriptedReplies += "Not SD printing"
        port.scriptedReplies += "ok"
        val svc = service(port)
        svc.snapshot()
        assertEquals(listOf(115_200), port.openCalls)
        assertTrue(port.writes.any { it.contains("M110 N0") })
        assertTrue(port.writes.any { it.contains("M115") })
        // No AUTOREPORT_TEMP capability was advertised, so M155 must never be sent; M105 (poll) is allowed.
        assertFalse(port.writes.any { it.contains("M155") })
        val allowed = setOf("M110", "M115", "M105", "M155", "M27", "M20")
        for (w in port.writes) assertTrue("unexpected command written: $w", allowed.any { w.contains(it) })
    }

    @Test fun `M155 is sent only when the firmware advertises AUTOREPORT_TEMP`() {
        val port = FakeUsbSerialPort()
        port.scriptedReplies += "ok" // M110 N0
        port.scriptedReplies += "FIRMWARE_NAME:Marlin"
        port.scriptedReplies += "Cap:AUTOREPORT_TEMP:1"
        port.scriptedReplies += "ok" // M115
        port.scriptedReplies += "ok" // M155
        port.scriptedReplies += "Not SD printing"
        port.scriptedReplies += "ok" // M27
        val svc = service(port)
        svc.snapshot()
        assertTrue(port.writes.any { it.contains("M155 S2") })
        // Auto-report firmware must never be polled with M105.
        assertFalse(port.writes.any { it.contains("M105") })
    }

    @Test fun `DTR is never raised except through the explicit restartBoard call`() {
        val port = FakeUsbSerialPort()
        port.queueOkFor()
        port.scriptedReplies += "Not SD printing"
        port.scriptedReplies += "ok"
        val svc = service(port)
        svc.snapshot()
        svc.toolheadTemperatures()
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

    @Test fun `a resend is honored with the exact original numbered line before ok clears it`() {
        val port = FakeUsbSerialPort()
        // Handshake: M110 N0 gets a Resend once, then ok; M115 -> ok; then M105 (poll) -> ok; M27 -> not printing, ok.
        port.scriptedReplies += "Resend:1"
        port.scriptedReplies += "ok"
        port.scriptedReplies += "FIRMWARE_NAME:Marlin"
        port.scriptedReplies += "ok"
        port.scriptedReplies += "Not SD printing"
        port.scriptedReplies += "ok"
        val svc = service(port)
        svc.snapshot()
        val m110Writes = port.writes.filter { it.contains("M110 N0") }
        assertEquals(2, m110Writes.size)
        assertEquals(m110Writes[0], m110Writes[1]) // resend re-sends the exact same numbered/checksummed line
    }
}

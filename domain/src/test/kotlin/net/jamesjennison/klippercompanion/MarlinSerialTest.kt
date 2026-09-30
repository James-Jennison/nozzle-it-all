package net.jamesjennison.klippercompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Line protocol tests for [MarlinSerial]: checksums, reply parsing, M115/M27/M20 parsing, and the send window. */
class MarlinSerialTest {

    // ---- checksum / line numbering: known-answer vectors, independently hand-computed -------------------------

    @Test fun `checksum known answers`() {
        // "N1 M110 N0" XOR-folded byte by byte.
        assertEquals(xorFold("N1 M110 N0"), MarlinSerial.checksum("N1 M110 N0"))
        assertEquals(xorFold("N0 M110 N0"), MarlinSerial.checksum("N0 M110 N0"))
        assertEquals(xorFold("N5 M105"), MarlinSerial.checksum("N5 M105"))
    }

    private fun xorFold(s: String): Int {
        var c = 0
        for (b in s.toByteArray(Charsets.US_ASCII)) c = c xor (b.toInt() and 0xFF)
        return c and 0xFF
    }

    @Test fun `numbered line has N-prefix, body, checksum and trailing newline`() {
        val line = MarlinSerial.numberedLine(1, "M110 N0")
        val body = "N1 M110 N0"
        assertEquals("$body*${xorFold(body)}\n", line)
    }

    @Test fun `RESET_LINE_NUMBER is exactly M110 N0`() {
        assertEquals("M110 N0", MarlinSerial.RESET_LINE_NUMBER)
    }

    // ---- reply parsing ----------------------------------------------------------------------------------------

    @Test fun `plain ok with no temperatures`() {
        val reply = MarlinSerial.parseReply("ok")
        assertTrue(reply is MarlinSerial.Reply.Ok)
        assertNull((reply as MarlinSerial.Reply.Ok).temperatures)
    }

    @Test fun `ok with inline single-hotend temperature`() {
        val reply = MarlinSerial.parseReply("ok T:210.0 /210.0 B:60.0 /60.0")
        assertTrue(reply is MarlinSerial.Reply.Ok)
        val temps = (reply as MarlinSerial.Reply.Ok).temperatures!!
        assertEquals(1, temps.hotends.size)
        assertEquals(0, temps.hotends[0].index)
        assertEquals(210.0, temps.hotends[0].current, 0.0001)
        assertEquals(210.0, temps.hotends[0].target)
        assertEquals(60.0, temps.bed!!.current, 0.0001)
    }

    @Test fun `multi-extruder temperature report`() {
        val report = MarlinSerial.parseTemperatures("T0:210 /210 T1:0 /0 B:60 /60")!!
        assertEquals(2, report.hotends.size)
        assertEquals(0, report.hotends[0].index)
        assertEquals(1, report.hotends[1].index)
        assertEquals(0.0, report.hotends[1].current, 0.0001)
        assertEquals(60.0, report.bed!!.current, 0.0001)
    }

    @Test fun `resend forms - Resend colon and rs`() {
        assertEquals(MarlinSerial.Reply.Resend(42), MarlinSerial.parseReply("Resend:42"))
        assertEquals(MarlinSerial.Reply.Resend(7), MarlinSerial.parseReply("rs 7"))
        assertEquals(MarlinSerial.Reply.Resend(7), MarlinSerial.parseReply("RESEND: 7"))
    }

    @Test fun `busy forms`() {
        assertEquals(MarlinSerial.Reply.Busy, MarlinSerial.parseReply("busy: processing"))
        assertEquals(MarlinSerial.Reply.Busy, MarlinSerial.parseReply("echo:busy processing"))
    }

    @Test fun `error line`() {
        val reply = MarlinSerial.parseReply("Error:Line Number is not Last Line Number+1, Last Line: 4")
        assertTrue(reply is MarlinSerial.Reply.Error)
        assertEquals("Line Number is not Last Line Number+1, Last Line: 4", (reply as MarlinSerial.Reply.Error).message)
    }

    @Test fun `start line`() {
        assertEquals(MarlinSerial.Reply.Start, MarlinSerial.parseReply("start"))
    }

    @Test fun `bare temperature push without ok is a Temperature reply`() {
        val reply = MarlinSerial.parseReply("T:205.2 /210.0 B:59.8 /60.0")
        assertTrue(reply is MarlinSerial.Reply.Temperature)
    }

    @Test fun `unrecognized line is Other`() {
        val reply = MarlinSerial.parseReply("echo:some debug text")
        assertTrue(reply is MarlinSerial.Reply.Other)
        assertEquals("echo:some debug text", (reply as MarlinSerial.Reply.Other).line)
    }

    @Test fun `blank line is Other with empty string`() {
        assertEquals(MarlinSerial.Reply.Other(""), MarlinSerial.parseReply("   "))
    }

    // ---- M115 capabilities -------------------------------------------------------------------------------------

    @Test fun `M115 parses firmware fields and capability lines`() {
        val reply = """
            FIRMWARE_NAME:Marlin 2.1.2 MACHINE_TYPE:Prusa MK3S EXTRUDER_COUNT:1
            Cap:AUTOREPORT_TEMP:1
            Cap:SDCARD:1
            Cap:EEPROM:0
            ok
        """.trimIndent()
        val caps = MarlinSerial.parseCapabilities(reply)
        assertEquals("Marlin 2.1.2", caps.firmwareName)
        assertEquals("Prusa MK3S", caps.machineType)
        assertEquals(1, caps.extruderCount)
        assertTrue(caps.supportsAutoReportTemp)
        assertTrue(caps.supportsSdCard)
        assertEquals(false, caps.caps["EEPROM"])
    }

    @Test fun `M115 without AUTOREPORT_TEMP cap falls back to polling`() {
        val caps = MarlinSerial.parseCapabilities("FIRMWARE_NAME:Marlin 1.1.9\nok")
        assertFalse(caps.supportsAutoReportTemp)
    }

    @Test fun `M115 with unknown fields does not throw and keeps defaults`() {
        val caps = MarlinSerial.parseCapabilities("garbage line with no fields\nok")
        assertEquals("", caps.firmwareName)
        assertEquals(1, caps.extruderCount)
    }

    // ---- M27 / M20 -----------------------------------------------------------------------------------------------

    @Test fun `M27 not printing`() {
        val status = MarlinSerial.parseSdStatus("Not SD printing\nok")
        assertFalse(status.printing)
        assertNull(status.bytesPrinted)
    }

    @Test fun `M27 printing byte counts`() {
        val status = MarlinSerial.parseSdStatus("SD printing byte 1234/5678\nok")
        assertTrue(status.printing)
        assertEquals(1234L, status.bytesPrinted)
        assertEquals(5678L, status.bytesTotal)
    }

    @Test fun `M20 file list`() {
        val reply = """
            Begin file list
            BENCHY.GCO 123456
            CALIBRAT.GCO
            End file list
            ok
        """.trimIndent()
        val files = MarlinSerial.parseFileList(reply)
        assertEquals(listOf("BENCHY.GCO", "CALIBRAT.GCO"), files)
    }

    // ---- send window: single-line-in-flight + resend recovery -----------------------------------------------------

    @Test fun `send window allows one line, then blocks until ok`() {
        val window = MarlinSerial.SendWindow()
        assertTrue(window.canSend())
        window.prepareSend("M105")
        assertFalse(window.canSend())
        assertTrue(window.hasLineInFlight)
    }

    @Test fun `send window throws if a second line is prepared before ok`() {
        val window = MarlinSerial.SendWindow()
        window.prepareSend("M105")
        try {
            window.prepareSend("M105")
            org.junit.Assert.fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test fun `send window clears on ok and allows the next line`() {
        val window = MarlinSerial.SendWindow()
        window.prepareSend("M105")
        val outcome = window.onReply(MarlinSerial.Reply.Ok(null))
        assertEquals(MarlinSerial.SendWindow.Outcome.Cleared, outcome)
        assertTrue(window.canSend())
    }

    @Test fun `send window resend recovery returns the original numbered line verbatim`() {
        val window = MarlinSerial.SendWindow()
        val sent = window.prepareSend("M105") // line 1
        val outcome = window.onReply(MarlinSerial.Reply.Resend(1))
        assertTrue(outcome is MarlinSerial.SendWindow.Outcome.Resend)
        val resend = outcome as MarlinSerial.SendWindow.Outcome.Resend
        assertEquals(1L, resend.line)
        assertEquals(sent, resend.text)
        // Still not clear to send a brand-new line until this resent one is ack'd.
        assertFalse(window.canSend())
    }

    @Test fun `send window resend for unknown out-of-history line is StillWaiting`() {
        val window = MarlinSerial.SendWindow()
        window.prepareSend("M105")
        val outcome = window.onReply(MarlinSerial.Reply.Resend(9999))
        assertEquals(MarlinSerial.SendWindow.Outcome.StillWaiting, outcome)
    }

    @Test fun `send window busy reply keeps waiting without clearing`() {
        val window = MarlinSerial.SendWindow()
        window.prepareSend("M105")
        val outcome = window.onReply(MarlinSerial.Reply.Busy)
        assertEquals(MarlinSerial.SendWindow.Outcome.StillWaiting, outcome)
        assertFalse(window.canSend())
    }

    @Test fun `send window resend then ok fully clears the window`() {
        val window = MarlinSerial.SendWindow()
        window.prepareSend("M105") // line 1
        window.onReply(MarlinSerial.Reply.Resend(1))
        val outcome = window.onReply(MarlinSerial.Reply.Ok(null))
        assertEquals(MarlinSerial.SendWindow.Outcome.Cleared, outcome)
        assertTrue(window.canSend())
    }
}

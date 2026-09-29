package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

// The expected lines are CONSTRUCTED from upstream OrcaSlicer 5298e49d src/slic3r/Utils/Flashforge.hpp:57-65 (the command
// texts), Flashforge.cpp:185-202, 329-492 (file names, connect, upload, start) and TCPConsole.cpp:26-107 (framing, the `ok`
// rule). Not captured from a printer.
class FlashforgeLegacyTest {
    @Test fun startIsGatedOff() {
        assertFalse(FlashforgeLegacy.START_VERIFIED)
        assertFalse(startVerifiedFor(PrinterKind.FLASHFORGE))
        val refusal = assertThrows(ApiFailure::class.java) { FlashforgeLegacy.requireStartVerified("cube.gcode") }
        assertTrue(refusal.message!!.contains("isn't verified on real hardware"))
        assertEquals("the same words whichever Flashforge protocol", FlashforgeIfs.startNotVerified("cube.gcode"), FlashforgeLegacy.startNotVerified("cube.gcode"))
    }

    @Test fun legacyOnlyWithoutBothCredentials() {
        assertTrue(FlashforgeLegacy.usesLegacy("", ""))
        assertTrue(FlashforgeLegacy.usesLegacy("SN123", " "))
        assertTrue(FlashforgeLegacy.usesLegacy("", "12345678"))
        assertFalse(FlashforgeLegacy.usesLegacy("SN123", "12345678"))
    }

    @Test fun framingAndReplies() {
        assertEquals("~M601 S1\r\n\n", FlashforgeLegacy.wire(FlashforgeLegacy.CONTROL))
        assertEquals(listOf("~M601 S1\r\n", "~M115\r\n", "~M650\r\n", "~M119\r\n"), FlashforgeLegacy.connectSequence())
        assertEquals("~M640\r\n", FlashforgeLegacy.connectSequence(klipper = true)[2])
        assertTrue(FlashforgeLegacy.isDone("ok\r")); assertTrue(FlashforgeLegacy.isDone("  OK ")); assertFalse(FlashforgeLegacy.isDone("ok T:25"))
        assertFalse(FlashforgeLegacy.isDone("CMD M601 Received."))
    }

    @Test fun uploadAndStartLines() {
        assertEquals("~M28 1234 0:/user/my_cube.gcode", FlashforgeLegacy.beginUpload(1234, "my_cube.gcode"))
        assertEquals("~M29\r\n", FlashforgeLegacy.SAVE_FILE)
        assertEquals("~M23 0:/user/cube.gcode", FlashforgeLegacy.startPrint("cube.gcode"))
        assertEquals(4096, FlashforgeLegacy.CHUNK_BYTES)
    }

    @Test fun fileNamesAreUpstreams() {
        assertEquals("my_cube__1_.gcode", FlashforgeLegacy.fileName("/x/my cube (1).gcode"))
        assertEquals("print.gx", FlashforgeLegacy.fileName("", ".gx"))
        assertEquals("a two-byte character is two bytes upstream", "__.gcode", FlashforgeLegacy.fileName("é.gcode"))
    }

    @Test fun stateIsUnknownAndNotReady() {
        val s = FlashforgeLegacy.probeSnapshot()
        assertEquals("unknown", s.state); assertFalse(s.ready)
    }
}

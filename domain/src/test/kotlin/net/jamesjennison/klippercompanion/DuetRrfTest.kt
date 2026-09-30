package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

// The expected strings are CONSTRUCTED from upstream OrcaSlicer 5298e49d src/slic3r/Utils/Duet.cpp (the URL formats at
// 184-211 and 246-261, the `err` rule at 277-284 and the connect outcomes at 145-159), not captured from a Duet.
class DuetRrfTest {
    @Test fun startIsGatedOff() {
        assertFalse(DuetRrf.START_VERIFIED)
        val refusal = assertThrows(ApiFailure::class.java) { DuetRrf.requireStartVerified("cube.gcode") }
        assertTrue(refusal.message!!.contains("isn't verified on real hardware"))
        assertTrue(refusal.message!!.startsWith("Uploaded cube.gcode to the printer but did not start it"))
        DuetRrf.requireStartVerified("cube.gcode", verified = true)
        assertFalse(startVerifiedFor(PrinterKind.DUET))
    }

    @Test fun timestampIsUpstreamsLocalTimeFormat() {
        assertEquals("2026-09-29T07:05:03", DuetRrf.timestamp(LocalDateTime.of(2026, 9, 29, 7, 5, 3)))
    }

    @Test fun escapingIsCurls() {
        assertEquals("abc-._~XYZ09", DuetRrf.curlEscape("abc-._~XYZ09"))
        assertEquals("a%20b%26c%23d%2Fe%3A", DuetRrf.curlEscape("a b&c#d/e:"))
        assertEquals("%C3%A9", DuetRrf.curlEscape("é"))
    }

    @Test fun connectQueryDefaultsThePasswordAndEscapesIt() {
        assertEquals("password=reprap&time=T", DuetRrf.connectQuery("", "T"))
        assertEquals("password=p%26ss%23&time=T", DuetRrf.connectQuery("p&ss#", "T"))
    }

    @Test fun uploadAndStartShapes() {
        assertEquals("name=0:/gcodes/my%20cube.gcode&time=T", DuetRrf.uploadQuery("my cube.gcode", "T"))
        assertEquals("machine/file/gcodes/my%20cube.gcode", DuetRrf.dsfUploadPath("my cube.gcode"))
        assertEquals("M32 \"0:/gcodes/cube.gcode\"", DuetRrf.startCode("cube.gcode"))
        assertEquals("M37 P\"0:/gcodes/cube.gcode\"", DuetRrf.startCode("cube.gcode", simulate = true))
        assertEquals("gcode=M32%20\"0:/gcodes/my%20cube.gcode\"", DuetRrf.rrGcodeQuery("my cube.gcode"))
    }

    @Test fun errRule() {
        assertEquals(0, DuetRrf.errCode("""{"err":0,"sessionTimeout":8000,"boardType":"duetwifi102"}"""))
        assertEquals(0, DuetRrf.errCode("{}"))
        assertEquals(1, DuetRrf.errCode("""{"err":1}"""))
        assertNull(DuetRrf.errCode("<html>not json</html>"))
        assertNull(DuetRrf.connectError("""{"err":0}"""))
        assertTrue(DuetRrf.connectError("""{"err":1}""")!!.contains("password"))
        assertTrue(DuetRrf.connectError("""{"err":2}""")!!.contains("session"))
        assertNotNull(DuetRrf.connectError("nope"))
    }

    @Test fun uploadSuccessRule() {
        assertTrue(DuetRrf.uploadSucceeded(DuetRrf.ConnectionType.DSF, 201, ""))
        assertFalse("DSF needs 201 exactly", DuetRrf.uploadSucceeded(DuetRrf.ConnectionType.DSF, 200, ""))
        assertTrue(DuetRrf.uploadSucceeded(DuetRrf.ConnectionType.RRF, 200, """{"err":0}"""))
        assertFalse(DuetRrf.uploadSucceeded(DuetRrf.ConnectionType.RRF, 200, """{"err":1}"""))
        assertFalse(DuetRrf.uploadSucceeded(DuetRrf.ConnectionType.RRF, 200, "garbage"))
    }

    @Test fun stateIsUnknownAndAnUnknownStateOnlyAllowsTheGatedUpload() {
        val s = DuetRrf.probeSnapshot()
        assertEquals("unknown", s.state)
        assertFalse(capabilitiesFor(PrinterKind.DUET).readsPrinterState)
        assertTrue("upload-only while gated", "unknown" in sendAllowedStates(PrinterKind.DUET))
        assertFalse("kinds that read state never send in an unknown state", "unknown" in sendAllowedStates(PrinterKind.OCTOPRINT))
    }

    @Test fun remoteNameIsTheBaseName() {
        assertEquals("cube.gcode", DuetRrf.remoteName("/sdcard/x/cube.gcode"))
        assertEquals("print.gcode", DuetRrf.remoteName(" "))
    }
}

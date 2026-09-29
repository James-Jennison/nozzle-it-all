package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

// CONSTRUCTED replies with only the fields upstream OrcaSlicer 5298e49d src/slic3r/Utils/Repetier.cpp reads (`name` /
// `software` from printer/info, `data[].slug` / `error` from printer/list). Not captured from a server.
class RepetierServerTest {
    @Test fun startIsGatedOff() {
        assertFalse(RepetierServer.START_VERIFIED)
        assertFalse(startVerifiedFor(PrinterKind.REPETIER))
        val refusal = assertThrows(ApiFailure::class.java) { RepetierServer.requireStartVerified("cube.gcode") }
        assertTrue(refusal.message!!.contains("isn't verified on real hardware"))
        assertFalse(capabilitiesFor(PrinterKind.REPETIER).readsPrinterState)
        assertTrue("upload-only while gated", "unknown" in sendAllowedStates(PrinterKind.REPETIER))
    }

    @Test fun serverIdentification() {
        assertNull(RepetierServer.infoProblem("""{"name":"My shop","software":"Repetier-Server","version":"1.4.0"}"""))
        assertNull("no software: name must start Repetier", RepetierServer.infoProblem("""{"name":"Repetier-Server Pro"}"""))
        assertNull("neither: accepted, as upstream", RepetierServer.infoProblem("{}"))
        assertNotNull(RepetierServer.infoProblem("""{"software":"OctoPrint"}"""))
        assertNotNull(RepetierServer.infoProblem("""{"name":"OctoPrint"}"""))
        assertNotNull(RepetierServer.infoProblem("<html>"))
    }

    @Test fun printerListAndSlugChoice() {
        val slugs = RepetierServer.printerSlugs("""{"data":[{"name":"Prusa","slug":"Prusa_i3"},{"name":"Delta","slug":"Delta"}]}""")
        assertEquals(listOf("Prusa_i3", "Delta"), slugs)
        assertTrue(assertThrows(ApiFailure::class.java) { RepetierServer.printerSlugs("""{"error":"Authorization required"}""") }.message!!.contains("Authorization required"))
        assertEquals("Delta", RepetierServer.chooseSlug("Delta", slugs))
        assertThrows(ApiFailure::class.java) { RepetierServer.chooseSlug("", slugs) }
        assertThrows(ApiFailure::class.java) { RepetierServer.chooseSlug("Other", slugs) }
        assertEquals("Solo", RepetierServer.chooseSlug(" ", listOf("Solo")))
    }

    @Test fun uploadFields() {
        assertEquals(mapOf("a" to "upload"), RepetierServer.modelUploadFields())
        assertFalse("never autostart on the upload-only path", "autostart" in RepetierServer.modelUploadFields())
        assertEquals(listOf("name", "autostart", "a"), RepetierServer.jobUploadFields("cube.gcode").keys.toList())
        assertEquals("filename", RepetierServer.FILE_FIELD)
    }
}

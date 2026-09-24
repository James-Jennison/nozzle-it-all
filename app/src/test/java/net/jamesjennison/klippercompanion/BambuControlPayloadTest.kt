package net.jamesjennison.klippercompanion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BambuControlPayloadTest {
    @Test fun eachControlBuildsAPrintRequestWithItsWireCommandAndSequence() {
        for (c in BambuPrintProtocol.Control.entries) {
            val print = JSONObject(BambuPrintProtocol.buildControlPayload("1234", c)).getJSONObject("print")
            assertEquals(c.wire, print.getString("command")); assertEquals("1234", print.getString("sequence_id"))
        }
        assertEquals(listOf("pause", "resume", "stop"), BambuPrintProtocol.Control.entries.map { it.wire })
    }
}

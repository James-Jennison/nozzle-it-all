package com.nozzleitall.adapter.prusa

import com.nozzleitall.printer.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PrusaLinkAdapterTest {
    @Test fun statusMapsOntoTheSharedModel() = MockWebServer().use { s ->
        s.enqueue(MockResponse().setBody("""{"job":{"id":42,"progress":37.5,"time_printing":600},"printer":{"state":"PRINTING","temp_nozzle":214.9,"target_nozzle":215.0,"temp_bed":59.5,"target_bed":60.0}}"""))
        s.enqueue(MockResponse().setBody("""{"id":42,"state":"PRINTING","progress":37.5,"time_printing":600,"file":{"name":"cube.bgcode","display_name":"cube.bgcode"}}"""))
        s.start()
        val st = PrusaLinkAdapter().open(PrinterConfig(PrinterIdentity("p", "MK4", "Prusa MK4", PrinterFamily.PRUSA, s.url("/").toString()), PrusaLinkAdapter.ID, "pw")).status()
        assertEquals(PrinterState.PRINTING, st.state)
        assertEquals(215.0, st.toolheads.single().nozzleTarget!!, 0.0)
    }

    @Test fun capabilitiesAreHonest() {
        val c = PrusaLinkAdapter.CAPABILITIES
        assertTrue(c.uploadAndStart && c.pausePrint && c.resumePrint && c.cancelPrint)
        assertFalse(c.camera); assertFalse(c.vendorCloud); assertFalse(c.motion)
    }
}

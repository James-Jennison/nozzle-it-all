package com.nozzleitall.adapter.bambu

import com.nozzleitall.printer.*
import org.junit.Assert.*
import org.junit.Test

class BambuLanAdapterTest {
    @Test fun cameraIsOfferedOnlyWhereTheClientCanShowIt() {
        assertTrue(BambuLanAdapter.capabilitiesFor("Bambu Lab A1 mini").camera)
        assertTrue(BambuLanAdapter.capabilitiesFor("Bambu Lab P1S").camera)
        // X1, P2S and H2 cameras use RTSPS, which isn't built: not offered rather than shown broken.
        assertFalse(BambuLanAdapter.capabilitiesFor("Bambu Lab X1 Carbon").camera)
        assertFalse(BambuLanAdapter.capabilitiesFor("Bambu Lab H2D").camera)
    }

    @Test fun localOnlyAndHonestAboutOutput() {
        val c = BambuLanAdapter.CAPABILITIES
        assertFalse(c.vendorCloud); assertFalse(c.requiresVendorAccount); assertTrue(c.localConnection)
        assertEquals(setOf("gcode.3mf"), c.acceptedOutputs)
        assertFalse("no plain upload in LAN mode", c.uploadJob)
    }

    @Test fun serialNumberIsRequired() {
        assertThrows(IllegalArgumentException::class.java) {
            BambuLanAdapter().open(PrinterConfig(PrinterIdentity("b", "P1S", "Bambu Lab P1S", PrinterFamily.BAMBU_LAB, "192.168.1.60"), BambuLanAdapter.ID, "12345678"))
        }
    }
}

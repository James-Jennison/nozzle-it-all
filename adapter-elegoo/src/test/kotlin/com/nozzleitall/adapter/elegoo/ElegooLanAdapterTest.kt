package com.nozzleitall.adapter.elegoo

import com.nozzleitall.printer.*
import org.junit.Assert.*
import org.junit.Test
import java.util.ServiceLoader

class ElegooLanAdapterTest {
    @Test fun registeredAsAnAdapterModule() {
        val adapters = ServiceLoader.load(DeviceAdapterProvider::class.java).map { it.create() }
        val elegoo = adapters.single { it.id == ElegooLanAdapter.ID }
        assertEquals(setOf(PrinterFamily.ELEGOO), elegoo.families)
        assertFalse(elegoo.mayUseVendorCloud)
        assertTrue(PrinterFamily.ELEGOO in PrinterFamily.known)
    }

    @Test fun capabilitiesDeclareOnlyWhatIsImplemented() {
        listOf(ElegooLanAdapter.CC_CAPABILITIES, ElegooLanAdapter.CC2_CAPABILITIES).forEach { c ->
            assertTrue(c.uploadJob && c.startPrint && c.cancelPrint && c.materialState && c.multiMaterial && c.localConnection)
            assertFalse("upload, then start: two steps", c.uploadAndStart)
            assertFalse(c.camera); assertFalse(c.temperatures); assertFalse(c.motion); assertFalse(c.materialEdit); assertFalse(c.loadUnload)
            assertFalse(c.vendorCloud); assertFalse(c.requiresVendorAccount); assertFalse(c.remoteConnection)
            assertEquals(setOf("gcode"), c.acceptedOutputs)
        }
        assertTrue(ElegooLanAdapter.CC_CAPABILITIES.pausePrint && ElegooLanAdapter.CC_CAPABILITIES.resumePrint)
    }

    private fun config(model: String, extras: Map<String, String> = emptyMap(), address: String = "192.168.1.60") =
        PrinterConfig(PrinterIdentity("e", "E", model, PrinterFamily.ELEGOO, address), ElegooLanAdapter.ID, extras = extras)

    @Test fun theModelOrASavedChoicePicksTheProtocol() {
        assertFalse(ElegooLanAdapter.usesMqtt(config("Elegoo Centauri Carbon")))
        assertTrue(ElegooLanAdapter.usesMqtt(config("Elegoo Centauri Carbon 2")))
        assertTrue(ElegooLanAdapter.usesMqtt(config("Elegoo Centauri 2")))
        assertTrue(ElegooLanAdapter.usesMqtt(config("CC2")))
        assertTrue(ElegooLanAdapter.usesMqtt(config("Elegoo Centauri Carbon", mapOf("protocol" to "mqtt"))))
        assertFalse(ElegooLanAdapter.usesMqtt(config("Elegoo Centauri Carbon 2", mapOf("protocol" to "sdcp"))))
        // Opening contacts nothing; the session connects on first use.
        ElegooLanAdapter().open(config("Elegoo Centauri Carbon")).use { assertTrue(it is SdcpSession) }
        ElegooLanAdapter().open(config("Elegoo Centauri Carbon 2")).use { assertTrue(it is Cc2Session) }
    }

    @Test fun publicAddressesAreNeverContacted() {
        assertNull(ElegooLanAdapter().probe("8.8.8.8"))
        assertNull(ElegooLanAdapter().probe("printer.example.com"))
        assertThrows(IllegalArgumentException::class.java) { ElegooLanAdapter().open(config("Elegoo Centauri Carbon", address = "203.0.113.5")) }
    }

    @Test fun discoveryRepliesAreReadFromTheAskedHostOnly() {
        FakeUdpResponder(fixture("sdcp_discovery.json").toString()).use { udp ->
            val d = ElegooDiscovery.askSdcp("127.0.0.1", udp.port, 1000)!!
            assertEquals("000000000001d354", d.mainboardId); assertEquals(listOf("M99999"), udp.asked)
            assertNull("an SDCP reply is not a CC2 reply", ElegooDiscovery.askCc2("127.0.0.1", udp.port, 500))
        }
        FakeUdpResponder(fixture("cc2_discovery.json").toString()).use { udp ->
            assertEquals("CC2A0001B2C3", ElegooDiscovery.askCc2("127.0.0.1", udp.port, 1000)!!.serial)
        }
        assertNull("nothing listening", ElegooDiscovery.askSdcp("127.0.0.1", 9, 300))
    }
}

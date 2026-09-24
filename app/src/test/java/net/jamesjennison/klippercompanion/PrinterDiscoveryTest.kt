package net.jamesjennison.klippercompanion

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class PrinterDiscoveryTest {
    @Test fun classifiesAKnownCentauriCarbonAUsnapmakerU1AndAGenericKlipper() {
        val cc1 = PrinterDiscovery.classifyMoonraker("CC1", "OpenCentauri Cosmos", "Release - 26.08.0", "192.168.1.114")
        assertEquals(PrinterKind.GENERIC_KLIPPER, cc1.kind); assertEquals(SlicingPrinterModel.ELEGOO_CENTAURI_CARBON, cc1.slicingModel); assertEquals("CC1", cc1.name)
        val u1 = PrinterDiscovery.classifyMoonraker("U1", "", "1.6.0.267_20260815150420", "192.168.1.110")
        assertEquals(PrinterKind.SNAPMAKER_U1_PAXX, u1.kind); assertEquals(SlicingPrinterModel.SNAPMAKER_U1, u1.slicingModel)
        val generic = PrinterDiscovery.classifyMoonraker("voron", "Klipper", "v0.12.0-123", "10.0.0.7")
        assertEquals(PrinterKind.GENERIC_KLIPPER, generic.kind); assertEquals(SlicingPrinterModel.GENERIC_KLIPPER, generic.slicingModel)
        assertEquals("a blank hostname falls back to the address", "10.0.0.7", PrinterDiscovery.classifyMoonraker("", "", "", "10.0.0.7").name)
    }

    @Test fun readsAPrusaLinkVersionReplyAndRejectsOtherJson() {
        val p = PrinterDiscovery.parsePrusaLinkVersion("""{"api":"0.9.0-legacy","server":"2.1.2","original":"PrusaLink 0.7.0","text":"PrusaLink 0.7.0"}""", "192.168.1.50")!!
        assertEquals(PrinterKind.PRUSA_LINK, p.kind)
        assertNull(PrinterDiscovery.parsePrusaLinkVersion("""{"api":"1.0","server":"OctoPrint"}""", "x"))
        assertNull(PrinterDiscovery.parsePrusaLinkVersion("not json", "x"))
    }

    @Test fun readsABambuSsdpAnnouncementWithItsSerial() {
        val msg = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1990\r\nCache-Control: max-age=1800\r\nLocation: 192.168.1.77\r\nNT: urn:bambulab-com:device:3dprinter:1\r\nUSN: 01P00A123456789\r\nDevName.bambu.com: Workshop P1S\r\nDevModel.bambu.com: C12\r\nDevConnect.bambu.com: lan\r\n\r\n"
        val b = PrinterDiscovery.parseBambuSsdp(msg)!!
        assertEquals("192.168.1.77", b.address); assertEquals("01P00A123456789", b.serial); assertEquals("Workshop P1S", b.name); assertEquals(PrinterKind.BAMBU_LAB, b.kind)
        assertNull("not a Bambu message", PrinterDiscovery.parseBambuSsdp("NOTIFY * HTTP/1.1\r\nLocation: 192.168.1.9\r\nUSN: uuid:abc\r\nNT: upnp:rootdevice\r\n\r\n"))
        assertNull("a hostile Location is not an address", PrinterDiscovery.parseBambuSsdp("NT: urn:bambulab-com:device:3dprinter:1\r\nUSN: 01P00A123456789\r\nLocation: evil.example/x\r\n"))
    }

    private fun server(routes: Map<String, String>) = MockWebServer().apply {
        dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = routes[request.path?.substringBefore('?')]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404) }
        start(0)
    }

    @Test fun theScannerVerifiesMoonrakerByItsRealReplyAndSkipsSilentHosts() {
        val s = server(mapOf("/server/info" to """{"result":{"klippy_connected":true,"klippy_state":"ready","moonraker_version":"v0.9"}}""",
            "/printer/info" to """{"result":{"hostname":"U1","software_version":"1.6.0.267_20260815150420"}}"""))
        try {
            val scanner = PrinterScanner(moonrakerPorts = listOf(s.port), prusaPorts = emptyList(), ssdpPorts = emptyList(), ssdpWaitMs = 100)
            val found = mutableListOf<DiscoveredPrinter>()
            scanner.scan(listOf("127.0.0.1", "127.0.0.2"), AtomicBoolean(false)) { found += it }
            val u1 = found.singleOrNull { it.address == "127.0.0.1:${s.port}" }
            assertNotNull("the mock printer is found: $found", u1); assertEquals(PrinterKind.SNAPMAKER_U1_PAXX, u1!!.kind)
        } finally { s.shutdown() }
    }

    @Test fun aWebServerThatIsNotMoonrakerIsIgnored() {
        val s = server(mapOf("/server/info" to """{"hello":"world"}"""))
        try {
            val found = mutableListOf<DiscoveredPrinter>()
            PrinterScanner(moonrakerPorts = listOf(s.port), prusaPorts = emptyList(), ssdpPorts = emptyList(), ssdpWaitMs = 100).scan(listOf("127.0.0.1")) { found += it }
            assertTrue(found.isEmpty())
        } finally { s.shutdown() }
    }
}

class LocalNetworkTest {
    @Test fun aSlash24ListsTheOtherTwoHundredFiftyThreeHosts() {
        val hosts = LocalNetwork.hostsIn(byteArrayOf(192.toByte(), 168.toByte(), 1, 20), 24)
        assertEquals(253, hosts.size); assertFalse("192.168.1.20" in hosts); assertTrue("192.168.1.110" in hosts); assertFalse("192.168.1.0" in hosts || "192.168.1.255" in hosts)
    }
    @Test fun aWiderNetworkIsOnlyScannedAsTheOwnSlash24() { assertEquals(253, LocalNetwork.hostsIn(byteArrayOf(10, 0, 5, 9), 16).size) }
    @Test fun aSmallSubnetIsRespected() { assertEquals(5, LocalNetwork.hostsIn(byteArrayOf(192.toByte(), 168.toByte(), 1, 9), 29).size) }
}

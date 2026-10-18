package com.nozzleitall.desktop

import com.nozzleitall.desktop.discovery.LanScan
import com.nozzleitall.printer.ConnectionRoute
import com.nozzleitall.printer.DiscoveredPrinter
import com.nozzleitall.printer.PrinterFamily
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class LanScanTest {
    @Test fun aSlash24VisitsTheOtherTwoHundredFiftyThreeAddresses() {
        val h = LanScan.hostsFor("192.168.1.20", 24)
        assertEquals(253, h.size); assertFalse("192.168.1.20" in h); assertFalse("192.168.1.0" in h); assertFalse("192.168.1.255" in h); assertTrue("192.168.1.40" in h)
    }

    @Test fun aBiggerNetworkIsLimitedToTheComputersOwn24() {
        val h = LanScan.hostsFor("10.0.5.7", 16)
        assertEquals(253, h.size); assertTrue(h.all { it.startsWith("10.0.5.") })
    }

    @Test fun aSmallerNetworkStaysInItsBlock() {
        val h = LanScan.hostsFor("192.168.1.70", 26)
        assertEquals(61, h.size); assertTrue(h.first() == "192.168.1.65" && h.last() == "192.168.1.126")
    }

    @Test fun onlyPrivateRangesCount() {
        assertTrue(LanScan.isPrivate("192.168.0.5")); assertTrue(LanScan.isPrivate("10.1.2.3")); assertTrue(LanScan.isPrivate("172.20.0.1"))
        assertFalse(LanScan.isPrivate("8.8.8.8")); assertFalse(LanScan.isPrivate("100.87.130.114")); assertFalse(LanScan.isPrivate("172.32.0.1"))
    }

    @Test fun nonsenseAddressesGiveNothing() {
        assertTrue(LanScan.hostsFor("not-an-ip", 24).isEmpty()); assertTrue(LanScan.hostsFor("10.0.0.1", 32).isEmpty())
    }

    @Test fun onlyAddressesWithThePortOpenAreAskedWhatTheyAre() = runBlocking {
        val asked = CopyOnWriteArrayList<String>(); val found = CopyOnWriteArrayList<DiscoveredPrinter>(); val progress = CopyOnWriteArrayList<Int>()
        val hosts = (1..20).map { "10.0.0.$it" }
        LanScan.scan(hosts, endpoint = { if (it == "10.0.0.5") it else if (it == "10.0.0.9") "http://$it/" else null }, probe = { h ->
            asked += h
            if (h == "10.0.0.5") listOf(DiscoveredPrinter("http://$h", "Snapmaker U1", PrinterFamily.PAXX_U1, "paxx", "x", ConnectionRoute.LAN)) else emptyList()
        }, onFound = { found += it }, onProgress = { n, _ -> progress += n })
        assertEquals(setOf("10.0.0.5", "http://10.0.0.9/"), asked.toSet())
        assertEquals(listOf("Snapmaker U1"), found.map { it.model })
        assertEquals(20, progress.size); assertEquals(20, progress.max())
    }

    private fun info(pid: Int, host: String = "lava") = """{"result": {"state": "ready", "hostname": "$host", "process_id": $pid, "software_version": "1.6.0", "config_file": "/home/lava/printer_data/config/printer.cfg"}}"""

    @Test fun theSamePrinterOnTwoAddressesHasOneFingerprint() {
        assertEquals(LanScan.fingerprintOf(info(1235)), LanScan.fingerprintOf(info(1235)))
        assertNotEquals(LanScan.fingerprintOf(info(1235)), LanScan.fingerprintOf(info(77)))
        assertNotEquals(LanScan.fingerprintOf(info(1235)), LanScan.fingerprintOf(info(1235, host = "other")))
    }

    @Test fun somethingThatIsNotAPrinterReplyHasNoFingerprint() {
        assertNull(LanScan.fingerprintOf("<html>router</html>")); assertNull(LanScan.fingerprintOf("""{"result": {}}"""))
    }
}

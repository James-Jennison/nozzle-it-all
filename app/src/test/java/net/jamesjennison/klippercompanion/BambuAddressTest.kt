package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

/** Mirrors MoonrakerTest's parseAddress coverage: the bar for a scheme-less Bambu host is the same. */
class BambuAddressTest {
    @Test fun acceptsPrivateAndTailscaleHosts() {
        assertEquals("192.168.1.50", bambuHostAddress(" 192.168.1.50 "))
        assertEquals("10.0.0.7", bambuHostAddress("10.0.0.7"))
        assertEquals("172.16.0.1", bambuHostAddress("172.16.0.1"))
        assertEquals("100.100.1.2", bambuHostAddress("100.100.1.2"))
        assertEquals("localhost", bambuHostAddress("localhost"))
        assertEquals("my-printer", bambuHostAddress("my-printer"))
        assertEquals("printer.local", bambuHostAddress("printer.local"))
        assertEquals("printer.tailnet-name.ts.net", bambuHostAddress("printer.tailnet-name.ts.net"))
        assertEquals("fd7a:115c:a1e0::1", bambuHostAddress("fd7a:115c:a1e0::1"))
        assertEquals("fd7a:115c:a1e0::1", bambuHostAddress("[fd7a:115c:a1e0::1]"))
    }
    @Test fun rejectsRoutableAndLookalikeHosts() {
        listOf("example.com", "8.8.8.8", "10.0.0.1.attacker.com", "192.168.1.1.example.com",
            "172.16.0.1.evil.net", "100.200.1.2", "2001:4860:4860::8888").forEach {
            try { bambuHostAddress(it); fail("Must reject $it") } catch(_: IllegalArgumentException) {}
        }
    }
    @Test fun rejectsSchemesPathsCredentialsPortsAndBlanks() {
        listOf("", "   ", "http://192.168.1.50", "192.168.1.50/print", "user@192.168.1.50",
            "192.168.1.50?x=1", "192.168.1.50#f", "192.168.1.50:8883", "192.168.1.50:80").forEach {
            try { bambuHostAddress(it); fail("Must reject '$it'") } catch(_: IllegalArgumentException) {}
        }
    }
}

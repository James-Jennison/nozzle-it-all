package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class NormalizedAddressTest {
    @Test fun aBareHostGetsPlainHttpForEveryNonBambuKind() {
        for (kind in listOf(PrinterKind.GENERIC_KLIPPER, PrinterKind.SNAPMAKER_U1_PAXX, PrinterKind.PRUSA_LINK, PrinterKind.OCTOPRINT)) {
            assertEquals("http://192.168.1.50/", normalizedInputAddress("192.168.1.50", kind))
            assertEquals("http://192.168.1.60:5000/", normalizedInputAddress(" 192.168.1.60:5000 ", kind))
        }
        assertEquals("http://octopi.local/", normalizedInputAddress("octopi.local", PrinterKind.OCTOPRINT))
    }
    @Test fun anExplicitSchemeIsKept() = assertEquals("http://192.168.1.110/", normalizedInputAddress("http://192.168.1.110/", PrinterKind.GENERIC_KLIPPER))
    @Test fun aPublicPlainHttpHostIsStillRefused() { assertThrows(IllegalArgumentException::class.java) { normalizedInputAddress("example.com", PrinterKind.OCTOPRINT) } }
    @Test fun aStoredAddressWithoutASchemeIsStillNotTurnedIntoAConnectionTarget() { assertThrows(IllegalArgumentException::class.java) { normalizedAddress("192.168.1.50", PrinterKind.GENERIC_KLIPPER) } }
}

package com.nozzleitall.testgrid

import net.jamesjennison.klippercompanion.PrinterKind
import net.jamesjennison.klippercompanion.startVerifiedFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Duet, UltiMaker, Repetier-Server and both Flashforge protocols (docs/upstream/PROVENANCE.md P-0035): while their start is
 * gated off in the app, the Test Grid never claims send-and-print or any control for them, and each gets its own family.
 */
class GatedKindsTest {
    private val gated = listOf(PrinterKind.DUET, PrinterKind.ULTIMAKER, PrinterKind.REPETIER, PrinterKind.FLASHFORGE, PrinterKind.ANYCUBIC_LAN)

    @Test fun gatedKindsDeclareNoStartOrControl() {
        for (kind in gated) {
            assertFalse("$kind", startVerifiedFor(kind))
            val declared = CapabilityNames.declared(kind)
            for (c in listOf(CapabilityNames.UPLOAD_AND_START, CapabilityNames.START_PRINT, CapabilityNames.PAUSE, CapabilityNames.RESUME,
                    CapabilityNames.CANCEL, CapabilityNames.TEMPERATURES, CapabilityNames.MOTION))
                assertFalse("$kind declares $c", c in declared)
        }
    }

    private fun describe(kind: PrinterKind, protocol: String) =
        TargetDescription(TargetKind.PHYSICAL, "Maker", "Model", kind, "adapter", protocol, "label", "http://192.168.1.50/", null)

    @Test fun eachConnectionIsItsOwnFamily() {
        assertEquals(FirmwareFamilies.DUET, FirmwareFamilies.classify(describe(PrinterKind.DUET, "duet-http"), null).family)
        assertEquals(FirmwareFamilies.ULTIMAKER, FirmwareFamilies.classify(describe(PrinterKind.ULTIMAKER, "ultimaker-http"), null).family)
        assertEquals(FirmwareFamilies.REPETIER, FirmwareFamilies.classify(describe(PrinterKind.REPETIER, "repetier-http"), null).family)
        assertEquals(FirmwareFamilies.FLASHFORGE, FirmwareFamilies.classify(describe(PrinterKind.FLASHFORGE, "flashforge-http"), null).family)
        assertEquals(FirmwareFamilies.FLASHFORGE_LEGACY, FirmwareFamilies.classify(describe(PrinterKind.FLASHFORGE, FirmwareFamilies.LEGACY_FLASHFORGE_PROTOCOL), null).family)
        assertEquals(FirmwareFamilies.ANYCUBIC_LAN, FirmwareFamilies.classify(describe(PrinterKind.ANYCUBIC_LAN, "anycubic-mqtt"), null).family)
    }

    /** P-0036: Anycubic reads its ACE slots (material state), but claims no start or control while AnycubicLan.START_VERIFIED is false. */
    @Test fun anycubicDeclaresMaterialStateOnly() {
        val declared = CapabilityNames.declared(PrinterKind.ANYCUBIC_LAN)
        assertEquals(setOf(CapabilityNames.STATUS, CapabilityNames.MATERIAL_STATE), declared)
    }
}

package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

// Phase 2 (Consumer Slicer Plan §10): one capability-resolution test per existing vendor
// integration (the plan's own Tests requirement), plus real regression coverage for the two
// concrete bugs this phase's migration fixed (FilePanels' Moonraker-only buttons showing for
// Prusa, on-device slicing assuming every printer speaks Moonraker's upload protocol).
class PrinterCapabilitiesTest {
    @Test fun genericKlipperGetsFullMoonrakerCapabilities() {
        val caps = capabilitiesFor(PrinterKind.GENERIC_KLIPPER)
        assertEquals(PrinterTransport.MOONRAKER, caps.transport)
        assertTrue(caps.supportsPauseResumeCancel)
        assertTrue(caps.supportsCamera)
        assertTrue(caps.supportsKlipperExtras)
        assertFalse(caps.supportsNativePrintFileFlow)
        assertTrue(caps.acceptsOnDeviceSlicedGcode)
        assertFalse(caps.hasBespok3d)
        assertFalse(caps.hasMultiAce)
        assertTrue(caps.verifiedOnRealHardware)
    }

    @Test fun snapmakerU1PaxxGetsMoonrakerPlusVendorAddOns() {
        val caps = capabilitiesFor(PrinterKind.SNAPMAKER_U1_PAXX)
        assertEquals(PrinterTransport.MOONRAKER, caps.transport)
        assertTrue(caps.supportsKlipperExtras)
        assertTrue("expected PAXX vendor add-ons", caps.hasBespok3d)
        assertTrue("expected PAXX vendor add-ons", caps.hasMultiAce)
        assertTrue(caps.verifiedOnRealHardware)
    }

    // BambuPrinterService's command() only ever carries a print request (bambuPrintRequest) -
    // no pause/resume/cancel, no heater/fan/macro/console/config readers, and this app's slicer
    // only produces plain .gcode when Bambu needs a .gcode.3mf bundle.
    @Test fun bambuLabIsCorrectlyRestricted() {
        val caps = capabilitiesFor(PrinterKind.BAMBU_LAB)
        assertEquals(PrinterTransport.BAMBU_MQTT, caps.transport)
        assertFalse("BambuPrinterService's command() carries no pause/resume/cancel", caps.supportsPauseResumeCancel)
        assertTrue("Bambu chamber cam is real", caps.supportsCamera)
        assertFalse("BambuPrinterService implements none of the Klipper reader interfaces", caps.supportsKlipperExtras)
        assertTrue("routes shared .gcode.3mf files to BambuPrintPanel", caps.supportsNativePrintFileFlow)
        assertFalse("this app's slicer only produces plain .gcode, Bambu needs .gcode.3mf", caps.acceptsOnDeviceSlicedGcode)
        assertFalse(caps.verifiedOnRealHardware)
    }

    // PrusaLinkPrinterService's command() DOES support pause/resume/cancel (real, unlike Bambu),
    // but has no camera, no Klipper-shaped readers, and no generic file-upload endpoint this app
    // implements - the real bug this phase fixed (SliceAndPrintPanel/ProjectEditorScreen/
    // FilePanels previously assumed otherwise for at least one of these).
    @Test fun prusaLinkSupportsPrintControlButNotFileUploadOrKlipperExtras() {
        val caps = capabilitiesFor(PrinterKind.PRUSA_LINK)
        assertEquals(PrinterTransport.PRUSA_LINK, caps.transport)
        assertTrue("PrusaLinkPrinterService.command() does support pause/resume/cancel", caps.supportsPauseResumeCancel)
        assertFalse("PrusaLinkPrinterService.image() throws - no camera implemented", caps.supportsCamera)
        assertFalse(caps.supportsKlipperExtras)
        assertFalse(caps.supportsNativePrintFileFlow)
        assertFalse("no generic PrusaLink file-upload endpoint is implemented", caps.acceptsOnDeviceSlicedGcode)
        assertFalse(caps.verifiedOnRealHardware)
    }

    // Real, plan-specified capabilities no transport implements yet - explicit false, not a
    // missing field, for every vendor.
    @Test fun noVendorImplementsTheNotYetBuiltCapabilitiesYet() {
        for (kind in PrinterKind.entries) {
            val caps = capabilitiesFor(kind)
            assertFalse("$kind", caps.hasFilamentSensor)
            assertFalse("$kind", caps.supportsJog)
            assertFalse("$kind", caps.supportsBedLevelingTrigger)
            assertFalse("$kind", caps.supportsTimelapseTrigger)
        }
    }

    @Test fun unverifiedOnRealHardwareRoutesThroughCapabilities() {
        assertFalse(PrinterKind.GENERIC_KLIPPER.unverifiedOnRealHardware)
        assertFalse(PrinterKind.SNAPMAKER_U1_PAXX.unverifiedOnRealHardware)
        assertTrue(PrinterKind.BAMBU_LAB.unverifiedOnRealHardware)
        assertTrue(PrinterKind.PRUSA_LINK.unverifiedOnRealHardware)
    }

    @Test fun screenStateCapabilitiesForResolvesTheCorrectProfile() {
        val state = ScreenState(profiles = listOf(
            PrinterProfile("http://klipper.local/", kind = PrinterKind.GENERIC_KLIPPER),
            PrinterProfile("192.168.1.50", kind = PrinterKind.BAMBU_LAB),
        ))
        assertEquals(PrinterTransport.MOONRAKER, state.capabilitiesFor("http://klipper.local/").transport)
        assertEquals(PrinterTransport.BAMBU_MQTT, state.capabilitiesFor("192.168.1.50").transport)
        // An address with no saved profile defaults to GENERIC_KLIPPER's capabilities (matches
        // ScreenState.kindFor's own existing default) - never null, never a crash.
        assertEquals(PrinterTransport.MOONRAKER, state.capabilitiesFor("http://unknown.local/").transport)
    }
}

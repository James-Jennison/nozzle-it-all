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
        // Phase 7 (§16): real G-code jog/bed-leveling-trigger/timelapse-render-trigger/filament
        // load-unload, all Moonraker/Klipper-only - see JogPanel.kt/BedMeshPanel.kt/
        // TimelapsePanel.kt/FilamentLoadUnloadControls.kt.
        assertTrue(caps.supportsJog)
        assertTrue(caps.supportsBedLevelingTrigger)
        assertTrue(caps.supportsTimelapseTrigger)
        assertTrue(caps.supportsFilamentLoadUnload)
    }

    @Test fun stockSnapmakerU1GetsBespok3dButNotMultiAce() {
        val caps = capabilitiesFor(PrinterKind.SNAPMAKER_U1)
        assertEquals(PrinterTransport.MOONRAKER, caps.transport)
        assertTrue(caps.supportsKlipperExtras)
        assertTrue("stock firmware is Bespok3d's target", caps.hasBespok3d)
        assertFalse("multiACE is a PAXX add-on", caps.hasMultiAce)
        assertFalse("not yet run on a stock-firmware U1", caps.verifiedOnRealHardware)
        assertTrue(caps.supportsJog)
        assertTrue(caps.supportsBedLevelingTrigger)
        assertTrue(caps.supportsTimelapseTrigger)
        assertTrue(caps.supportsFilamentLoadUnload)
    }

    @Test fun bespok3dAndMultiAceNeverAppearTogether() {
        PrinterKind.values().forEach { k -> val c = capabilitiesFor(k); assertFalse("$k", c.hasBespok3d && c.hasMultiAce) }
    }

    @Test fun snapmakerU1PaxxGetsMoonrakerPlusVendorAddOns() {
        val caps = capabilitiesFor(PrinterKind.SNAPMAKER_U1_PAXX)
        assertEquals(PrinterTransport.MOONRAKER, caps.transport)
        assertTrue(caps.supportsKlipperExtras)
        assertFalse("PAXX/extended firmware must not offer Bespok3d (its preflight refuses it)", caps.hasBespok3d)
        assertTrue("expected PAXX vendor add-on", caps.hasMultiAce)
        assertTrue(caps.verifiedOnRealHardware)
        assertTrue(caps.supportsJog)
        assertTrue(caps.supportsBedLevelingTrigger)
        assertTrue(caps.supportsTimelapseTrigger)
        assertTrue(caps.supportsFilamentLoadUnload)
    }

    // BambuPrinterService's command() only ever carries a print request (bambuPrintRequest) -
    // no pause/resume/cancel, no heater/fan/macro/console/config readers, and this app's slicer
    // only produces plain .gcode when Bambu needs a .gcode.3mf bundle.
    @Test fun bambuLabIsCorrectlyRestricted() {
        val caps = capabilitiesFor(PrinterKind.BAMBU_LAB)
        assertEquals(PrinterTransport.BAMBU_MQTT, caps.transport)
        assertTrue("BambuPrinterService.command() maps pause/resume/cancel to MQTT control requests", caps.supportsPauseResumeCancel)
        assertTrue("Bambu chamber cam is real", caps.supportsCamera)
        assertFalse("BambuPrinterService implements none of the Klipper reader interfaces", caps.supportsKlipperExtras)
        assertTrue("routes shared .gcode.3mf files to BambuPrintPanel", caps.supportsNativePrintFileFlow)
        assertTrue("Phase 6 (§16): engine::slice_bambu_bundle now produces a real .gcode.3mf via store_bbs_3mf - see BambuBundleDeviceTest", caps.acceptsOnDeviceSlicedGcode)
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
        assertTrue("WO-23: PrusaLinkPrinterService.uploadAndPrint() is now a real generic upload+print endpoint", caps.acceptsOnDeviceSlicedGcode)
        assertFalse(caps.verifiedOnRealHardware)
    }

    // hasFilamentSensor remains a real, plan-specified capability no transport implements yet -
    // explicit false, not a missing field, for every vendor. Phase 7 (§16) built real jog/bed-
    // leveling-trigger/timelapse-trigger/filament-load-unload for the Moonraker-backed vendors
    // (see the two tests above) - Bambu Lab and Prusa Link genuinely have neither the transport
    // nor the documented endpoints for any of them (BAMBU_MQTT: no jog/bed-mesh/timelapse concept
    // at all; Prusa Link's own published openapi.yaml has no jog/move endpoint), so this test now
    // only asserts false for those two.
    @Test fun noVendorImplementsFilamentSensingYet() {
        for (kind in PrinterKind.entries) {
            assertFalse("$kind", capabilitiesFor(kind).hasFilamentSensor)
        }
    }

    @Test fun bambuAndPrusaLinkHaveNoneOfThePhase7Controls() {
        for (kind in listOf(PrinterKind.BAMBU_LAB, PrinterKind.PRUSA_LINK)) {
            val caps = capabilitiesFor(kind)
            assertFalse("$kind", caps.supportsJog)
            assertFalse("$kind", caps.supportsBedLevelingTrigger)
            assertFalse("$kind", caps.supportsTimelapseTrigger)
            assertFalse("$kind", caps.supportsFilamentLoadUnload)
        }
    }

    @Test fun unverifiedOnRealHardwareRoutesThroughCapabilities() {
        assertFalse(PrinterKind.GENERIC_KLIPPER.unverifiedOnRealHardware)
        assertFalse(PrinterKind.SNAPMAKER_U1_PAXX.unverifiedOnRealHardware)
        assertTrue(PrinterKind.SNAPMAKER_U1.unverifiedOnRealHardware)
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

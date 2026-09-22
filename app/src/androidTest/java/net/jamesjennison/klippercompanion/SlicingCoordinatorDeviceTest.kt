package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// WO-13 Phases 2/3: exercises SlicingCoordinator.slice() end to end, including its live
// firmware-identity check - not just the underlying engine call (SlicingProfilePacksDeviceTest
// covers that in isolation already). Runs against the same real printers used throughout WO-13's
// device verification tonight.
@RunWith(AndroidJUnit4::class)
class SlicingCoordinatorDeviceTest {
    private fun cube(context: android.content.Context): File {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val input = File(context.cacheDir, "coordinator_cube.stl")
        testContext.assets.open("cube.stl").use { it.copyTo(input.outputStream()) }
        return input
    }
    @Test fun profileWithNoSlicingModelIsRejectedBeforeTouchingTheNetwork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outcome = SlicingCoordinator.slice(context, cube(context), PrinterProfile("http://192.0.2.1/"))
        assertTrue(outcome is SliceOutcome.Failed)
    }
    @Test fun snapmakerU1ProfileSlicesRealGcodeThroughTheCoordinator() = runBlocking {
        // 192.168.1.110: the real Snapmaker U1 used throughout WO-13's device verification. No
        // live firmware check applies to it (not a Centauri Carbon profile).
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = PrinterProfile("http://192.168.1.110/", "U1", slicingModel = SlicingPrinterModel.SNAPMAKER_U1)
        val outcome = SlicingCoordinator.slice(context, cube(context), profile)
        assertTrue("expected Success, got $outcome", outcome is SliceOutcome.Success)
        val gcode = (outcome as SliceOutcome.Success).gcode
        assertTrue(gcode.exists() && gcode.length() > 1000)
    }
    @Test fun centauriCarbonProfileWithNoDeclaredFirmwareIsBlockedEvenAgainstTheRealPrinter() = runBlocking {
        // 192.168.1.114: the real Centauri Carbon/COSMOS printer. Live firmware IS readable here
        // (confirmed earlier tonight), but this profile has no declaredFirmwareVersion at all -
        // checkCentauriCarbonFirmwareMatch must still block on a live/declared mismatch, not
        // assume a bare "COSMOS detected" is enough without a matching declared generation.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = PrinterProfile("http://192.168.1.114:80/", "CC1", slicingModel = SlicingPrinterModel.ELEGOO_CENTAURI_CARBON)
        val outcome = SlicingCoordinator.slice(context, cube(context), profile)
        assertTrue("expected FirmwareBlocked (no declared firmware yet), got $outcome", outcome is SliceOutcome.FirmwareBlocked)
    }
    @Test fun centauriCarbonProfileWithTheCorrectDeclaredGenerationSlicesRealCosmosGcode() = runBlocking {
        // The real printer's live firmware (confirmed via curl earlier tonight: "OpenCentauri
        // Cosmos" / "Release - 26.08.0") resolves to CosmosProfileGeneration.CURRENT - declaring
        // that same version here must let slicing proceed for real, against the real printer.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = PrinterProfile("http://192.168.1.114:80/", "CC1", slicingModel = SlicingPrinterModel.ELEGOO_CENTAURI_CARBON, declaredFirmwareVersion = "Release - 26.08.0")
        val outcome = SlicingCoordinator.slice(context, cube(context), profile)
        assertTrue("expected Success against the real, live-matching CC1, got $outcome", outcome is SliceOutcome.Success)
        val gcode = (outcome as SliceOutcome.Success).gcode.readText()
        assertTrue(gcode.contains("PRINT_START"))
        assertFalse(gcode.contains("M729"))
    }
    @Test fun centauriCarbonProfileWithTheWrongDeclaredGenerationIsBlockedAgainstTheRealPrinter() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Declares a pre-26.07.0 version against a printer that's actually running 26.08.0 live.
        val profile = PrinterProfile("http://192.168.1.114:80/", "CC1", slicingModel = SlicingPrinterModel.ELEGOO_CENTAURI_CARBON, declaredFirmwareVersion = "26.05.0")
        val outcome = SlicingCoordinator.slice(context, cube(context), profile)
        assertTrue("expected FirmwareBlocked (declared/live mismatch), got $outcome", outcome is SliceOutcome.FirmwareBlocked)
    }
}

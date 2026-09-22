package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
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
    @Test fun emptyProjectIsRejectedBeforeTouchingTheNetwork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = PrinterProfile("http://192.168.1.110/", "U1", slicingModel = SlicingPrinterModel.SNAPMAKER_U1)
        val outcome = SlicingCoordinator.sliceProject(context, emptyList(), profile)
        assertTrue("expected Failed for an empty project, got $outcome", outcome is SliceOutcome.Failed)
    }
    @Test fun sliceProjectPlacesEachRealObjectAtItsOwnTransform() = runBlocking {
        // WO-17 (Phase 1): proves the real multi-object workspace's own eventual slice path
        // (ProjectEditorScreen -> SlicingCoordinator.sliceProject -> engine::slice_multi_object)
        // through the same firmware-confirmation/profile-pack resolution slice() already uses,
        // not a second, untested path - MultiObjectSlicingDeviceTest already proved the native
        // call itself places objects correctly; this proves the coordinator wires real
        // ModelTransform values through to it correctly.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = PrinterProfile("http://192.168.1.110/", "U1", slicingModel = SlicingPrinterModel.SNAPMAKER_U1)
        val a = cube(context)
        val b = File(context.cacheDir, "coordinator_cube_b.stl").also { a.copyTo(it, overwrite = true) }
        val objects = listOf(a to ModelTransform(offsetXMm = -30f), b to ModelTransform(offsetXMm = 30f))
        val outcome = SlicingCoordinator.sliceProject(context, objects, profile)
        assertTrue("expected Success, got $outcome", outcome is SliceOutcome.Success)
        val gcode = (outcome as SliceOutcome.Success).gcode.readText()
        val objectIds = Regex("; printing object [^\\n]* id:(\\d+)").findAll(gcode).map { it.groupValues[1] }.toSet()
        assertEquals("expected exactly 2 distinct real per-object G-code ids", 2, objectIds.size)
        val xCoords = Regex("G1 [^\\n]*X(-?[0-9]+\\.?[0-9]*)").findAll(gcode).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        val spread = xCoords.max() - xCoords.min()
        assertTrue("expected toolpath X spread of at least 50mm across two objects 60mm apart, got ${spread}mm", spread >= 50.0)
    }
    @Test fun slicedGcodeEmbedsRealThumbnails() = runBlocking {
        // Real bug hit live: a print on the CC1 was uploaded and started with no thumbnail at
        // all (confirmed via Moonraker's own job history - no "thumbnails" field, unlike every
        // desktop-sliced file already on that printer), because slic3r_engine.cpp passed a null
        // thumbnail_cb to Print::export_gcode() even though the default "thumbnails" config
        // value already requests 48x48 and 300x300 PNGs. Fixed with thumbnail_render.cpp (a
        // headless software rasterizer of the model's own mesh). This confirms the fix actually
        // reaches the real engine, not just that it compiles.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = PrinterProfile("http://192.168.1.110/", "U1", slicingModel = SlicingPrinterModel.SNAPMAKER_U1)
        val outcome = SlicingCoordinator.slice(context, cube(context), profile)
        assertTrue("expected Success, got $outcome", outcome is SliceOutcome.Success)
        val gcode = (outcome as SliceOutcome.Success).gcode.readText()
        assertTrue("expected an embedded thumbnail block", gcode.contains("; thumbnail begin 48x48"))
        assertTrue("expected an embedded thumbnail block", gcode.contains("; thumbnail begin 300x300"))
        assertTrue(gcode.contains("; thumbnail end"))
    }
    @Test fun slicedGcodeStaysWithinTheConfiguredBedBounds() = runBlocking {
        // Real bug hit live: this same cube fixture, sliced against the CC1's real COSMOS
        // profile, aborted mid-print with Klipper's own "Move out of range" error - roughly half
        // the object's toolpath fell into negative Y because slic3r_engine.cpp never centered
        // the model on the bed (Model::read_from_file() places instances at the mesh's own local
        // origin, not the bed's actual coordinates). Fixed with
        // model.center_instances_around_point(bed center) in slice_file(). This parses the real
        // output's own "; bed_shape" comment and every G1 X/Y move to confirm none of them fall
        // outside it - the same class of check Klipper itself does at print time, run here
        // against the real engine instead of only being caught live on real hardware.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = PrinterProfile("http://192.168.1.110/", "U1", slicingModel = SlicingPrinterModel.SNAPMAKER_U1)
        val outcome = SlicingCoordinator.slice(context, cube(context), profile)
        assertTrue("expected Success, got $outcome", outcome is SliceOutcome.Success)
        val gcode = (outcome as SliceOutcome.Success).gcode.readText()
        val bedShapeLine = gcode.lineSequence().first { it.trim().startsWith("; bed_shape") }
        val points = Regex("(-?[\\d.]+)x(-?[\\d.]+)").findAll(bedShapeLine).map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.toList()
        assertTrue("could not parse bed_shape from: $bedShapeLine", points.size >= 3)
        val minX = points.minOf { it.first }; val maxX = points.maxOf { it.first }
        val minY = points.minOf { it.second }; val maxY = points.maxOf { it.second }
        var checked = 0
        for (line in gcode.lineSequence()) {
            if (!line.startsWith("G1")) continue
            val x = Regex("X(-?[\\d.]+)").find(line)?.groupValues?.get(1)?.toDoubleOrNull()
            val y = Regex("Y(-?[\\d.]+)").find(line)?.groupValues?.get(1)?.toDoubleOrNull()
            if (x == null || y == null) continue
            checked++
            assertTrue("X=$x outside bed [$minX,$maxX] in line: $line", x in minX..maxX)
            assertTrue("Y=$y outside bed [$minY,$maxY] in line: $line", y in minY..maxY)
        }
        assertTrue("expected to check at least some real toolpath moves", checked > 10)
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
    // WO-16: unlike the FirmwareBlocked-path tests above/below (which never actually reach the
    // network - the declared/live mismatch is caught before any connection attempt), this one
    // requires a real round trip to the live CC1 to get SliceOutcome.Success - found hanging to
    // timeout on every device in a real AWS Device Farm run, off this printer's LAN. Opt in
    // explicitly on the real LAN: -e approved_live_cosmos_slice true.
    @Test fun centauriCarbonProfileWithTheCorrectDeclaredGenerationSlicesRealCosmosGcode() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("approved_live_cosmos_slice") == "true")
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

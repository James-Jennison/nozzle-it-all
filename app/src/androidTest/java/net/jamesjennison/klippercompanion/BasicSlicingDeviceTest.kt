package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// Phase 4 (Consumer Slicer Plan §4/§16): the plan's own "intelligent defaulting engine" -
// meshNeedsSupport()'s real overhang detection, against real geometry loaded through the real
// native engine (not a synthetic MeshGeometry fixture, which BasicSlicingTest already covers),
// plus the full real path: AUTO support mode actually changing what the native slicer decides,
// confirmed in the real sliced G-code.
@RunWith(AndroidJUnit4::class)
class BasicSlicingDeviceTest {
    private fun cube(): File {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(appContext.cacheDir, "basic_slicing_cube.stl")
        testContext.assets.open("cube.stl").use { it.copyTo(input.outputStream()) }
        return input
    }

    // Same real fixture PaintSessionDeviceTest uses - a 4x4x10mm pillar with a 16x16x2mm cap, a
    // genuine overhang on all four sides of the cap.
    private fun overhang(): File {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(appContext.cacheDir, "basic_slicing_overhang.stl")
        testContext.assets.open("overhang.stl").use { it.copyTo(input.outputStream()) }
        return input
    }

    @Test fun aFlatCubeHasNoRealOverhang() = runBlocking {
        val geometry = MeshLoader.load(cube().absolutePath)
        assertFalse("a cube has no downward-facing surface that isn't its own bed-contact base", meshNeedsSupport(geometry))
    }

    @Test fun theOverhangFixtureIsCorrectlyDetected() = runBlocking {
        val geometry = MeshLoader.load(overhang().absolutePath)
        assertTrue("expected the cap's real overhanging underside to be detected", meshNeedsSupport(geometry))
    }

    // The plan's own acceptance-adjacent proof: AUTO support mode, driven by the real geometry
    // answer above, actually changes what the native slicer emits - not just that the override
    // key/value pair was accepted.
    @Test fun autoSupportModeEnablesRealSupportMaterialInTheSlicedGcodeForAnOverhangingModel() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = PrinterProfile("http://192.168.1.110/", "U1", slicingModel = SlicingPrinterModel.SNAPMAKER_U1)
        val geometry = MeshLoader.load(overhang().absolutePath)
        assertTrue(meshNeedsSupport(geometry)) // sanity: this fixture really does need support
        val settings = BasicSliceSettings(supportMode = SupportMode.AUTO)
        val outcome = SlicingCoordinator.sliceProject(context, listOf(overhang() to ModelTransform()), profile, settings.toOverrides(needsSupport = true))
        assertTrue("expected Success, got $outcome", outcome is SliceOutcome.Success)
        val gcode = (outcome as SliceOutcome.Success).gcode.readText()
        assertTrue("expected real support-material extrusion in the G-code for a genuinely overhanging model", gcode.contains("support material", ignoreCase = true))
    }

    @Test fun explicitSupportOffProducesNoRealSupportMaterialEvenForAnOverhangingModel() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = PrinterProfile("http://192.168.1.110/", "U1", slicingModel = SlicingPrinterModel.SNAPMAKER_U1)
        val settings = BasicSliceSettings(supportMode = SupportMode.OFF)
        // needsSupport=true is passed deliberately - proves the explicit OFF choice wins over
        // what the geometry says, not just that OFF happens to match a non-overhanging model.
        val outcome = SlicingCoordinator.sliceProject(context, listOf(overhang() to ModelTransform()), profile, settings.toOverrides(needsSupport = true))
        assertTrue("expected Success, got $outcome", outcome is SliceOutcome.Success)
        val gcode = (outcome as SliceOutcome.Success).gcode.readText()
        assertFalse("expected no support material with an explicit Off choice", gcode.contains("support material", ignoreCase = true))
    }
}

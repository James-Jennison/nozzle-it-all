package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nozzleitall.printer.ext.MixedFilamentFormat
import net.jamesjennison.klippercompanion.testgrid.AndroidTestSlicer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// Mixed filaments (OrcaSlicer 2.5.0's): proves they actually reach a real slice
// through the exact same SlicingCoordinator.sliceProject path the project editor's own colour-mixing UI uses
// (AndroidTestSlicer.sliceColourMix), never a parallel test-only pipeline, against the bundled Snapmaker U1
// profile pack.
//
// Kept on Snapmaker U1 rather than switched to a non-U1 multi-slot pack (colour-mixing follow-up, requirement
// 5b): U1 is the only bundled profile this app has actually proven slices multi-tool through
// SlicingCoordinator's production path at all - see ToolAssignmentSlicingDeviceTest's own header, which
// hardcodes U1's real override recipe (filament_diameter's array length is what really drives libslic3r's
// extruder count, not machine.json) and explicitly calls generalizing it to another profile "a real, separate
// follow-up". Every other bundled multi-slot pack (Prusa XL's 5T, the MMU3 packs, RatRig IDEX, the AMS/CFS
// packs) either uses a different tool-count mechanism (declared filament slots rather than
// filament_diameter's length) or has never been slice-tested multi-tool on Android at all; picking one for this
// suite without first proving *that* profile's own override recipe would risk a flaky or silently-wrong test
// rather than a real one. So this stays U1 until a non-U1 profile gets that same proof of its own.
//
// Never talks to a real printer: everything here is a local file slice against a bundled asset profile, exactly
// like every other *SlicingDeviceTest in this package.
@RunWith(AndroidJUnit4::class)
class ColourMixingSlicingDeviceTest {
    private fun cube(name: String): File {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(appContext.cacheDir, name)
        testContext.assets.open("cube.stl").use { it.copyTo(input.outputStream()) }
        return input
    }

    private fun slicer(): AndroidTestSlicer {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val printer = PrinterProfile(address = "colourmix-test", kind = PrinterKind.SNAPMAKER_U1)
        return AndroidTestSlicer(appContext, printer)
    }

    // The bundled Snapmaker U1 pack's own real declared tool-slot count (see ToolAssignmentSlicingDeviceTest's
    // header for how this was confirmed against the vendored engine: filament_diameter's own array length, not
    // machine.json's nozzle count alone). slotMaterials below must have exactly this many entries.
    private val physicalToolCount = 4

    private val red get() = BUNDLED_MATERIAL_PROFILES.first { it.id == "bundled-pla" }.copy(id = "mf-red", colorHex = "#FF0000")
    private val blue get() = BUNDLED_MATERIAL_PROFILES.first { it.id == "bundled-pla" }.copy(id = "mf-blue", colorHex = "#0000FF")

    private fun sliceMix(name: String, row: MixedFilamentFormat.Row): String {
        val cubeFile = cube(name)
        val slotMaterials = listOf(red, blue, null, null)
        val outcome = slicer().sliceColourMix(
            SlicingPrinterModel.SNAPMAKER_U1,
            listOf(cubeFile to ModelTransform()),
            toolSlotIndices = listOf(physicalToolCount + 1), // the first mixed filament
            slotMaterials = slotMaterials,
            mixedRows = listOf(row),
        )
        val gcode = (outcome as? SliceOutcome.Success)?.gcode ?: throw AssertionError("expected a real mixed-filament slice, got $outcome")
        assertTrue("expected real g-code output, got ${gcode.length()} bytes", gcode.exists() && gcode.length() > 1000)
        return gcode.readText()
    }

    // A 50/50 mix of loaded filaments 1 and 2, on the tool after the four loaded ones, switches between those two tools
    // within the layers, layer by layer.
    @Test fun aMixedFilamentAlternatesToolsAcrossLayers() {
        val text = sliceMix("mixed-5050.stl", MixedFilamentFormat.Row(listOf(1, 2), listOf(1, 1)))
        val toolChangesByLayer = text.split(Regex("(?m)^;LAYER_CHANGE$")).drop(1).map { layer ->
            Regex("(?m)^T[0-9]+$").findAll(layer).map { it.value }.toList()
        }
        assertTrue("expected at least 10 layers in this slice, got ${toolChangesByLayer.size}", toolChangesByLayer.size >= 10)
        val layersWithAToolChange = toolChangesByLayer.count { it.isNotEmpty() }
        assertTrue(
            "expected tool changes on at least 80% of layers (the mix alternates), got $layersWithAToolChange/${toolChangesByLayer.size}",
            layersWithAToolChange >= (toolChangesByLayer.size * 0.8),
        )
        assertTrue("both components print", setOf("T0", "T1").all { t -> toolChangesByLayer.any { t in it } })
    }

    // The mixed filament's settings reach the sliced config: Orca's own end-of-file config dump echoes every key it applied.
    @Test fun mixedFilamentSettingsReachTheSlicedConfig() {
        val text = sliceMix("mixed-config.stl", MixedFilamentFormat.Row(listOf(1, 2), listOf(3, 1)))
        assertTrue("filament_is_mixed in the sliced config", Regex("(?m)^; filament_is_mixed = .*1").containsMatchIn(text))
        assertTrue("the 3:1 ratio in the sliced config", text.contains("0.7500,0.2500"))
    }
}

package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nozzleitall.printer.ext.FullSpectrumFormat
import com.nozzleitall.printer.ext.PrusaColorMixFormat
import net.jamesjennison.klippercompanion.testgrid.AndroidTestSlicer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// Colour mixing (0.2.0, requirement 5): proves the two engine-side mixing systems actually reach a real slice
// through the exact same SlicingCoordinator.sliceProject path the project editor's own colour-mixing UI uses
// (AndroidTestSlicer.sliceColourMix), never a parallel test-only pipeline, against the bundled Snapmaker U1
// profile pack (the one bundled multi-slot profile, same one ToolAssignmentSlicingDeviceTest/
// MultiObjectSlicingDeviceTest already slice against - 4 real declared tool slots).
//
// Never talks to a real printer: everything here is a local file slice against a bundled asset profile, exactly
// like every other *SlicingDeviceTest in this package.
//
// These tests need the mixed_filament_definitions/virtual_extruders ops actually implemented by the on-device
// engine (nozzle-engine PR #9, not yet re-pinned into this branch as of writing) - until that pin lands they are
// expected to fail with "This engine doesn't support ... yet." from AndroidFullSpectrum/AndroidColorMix, or with
// the *Mix native calls simply not existing (UnsatisfiedLinkError). The owner will re-run this suite once the
// pin is updated.
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

    @Test fun colorMixBlendAlternatesToolsAcrossLayers() {
        val cubeFile = cube("colormix-blend.stl")
        val red = BUNDLED_MATERIAL_PROFILES.first { it.id == "bundled-pla" }.copy(id = "cm-red", colorHex = "#FF0000")
        val blue = BUNDLED_MATERIAL_PROFILES.first { it.id == "bundled-pla" }.copy(id = "cm-blue", colorHex = "#0000FF")
        val slotMaterials = listOf(red, blue, null, null)

        // A 50/50 blend of physical filaments 1 and 2 (PrusaColorMix.Component's own 1-based extruder numbering -
        // see PrusaColorMixTest.kt's blend()/MaterialSlot(i + 1, ...) convention), given the next free virtual id
        // after the 4 physical slots (5).
        val virtual = PrusaColorMixFormat.Virtual(
            id = physicalToolCount + 1, kind = "blend",
            components = listOf(PrusaColorMixFormat.Component(1, 0.5), PrusaColorMixFormat.Component(2, 0.5)),
        )
        val virtualExtrudersJson = PrusaColorMixFormat.sliceRequestJson(listOf(virtual))

        val outcome = slicer().sliceColourMix(
            SlicingPrinterModel.SNAPMAKER_U1,
            listOf(cubeFile to ModelTransform()),
            toolSlotIndices = listOf(physicalToolCount + 1),
            slotMaterials = slotMaterials,
            virtualExtruders = virtualExtrudersJson,
        )
        val gcode = (outcome as? SliceOutcome.Success)?.gcode ?: throw AssertionError("expected a real ColorMix slice, got $outcome")
        assertTrue("expected real g-code output, got ${gcode.length()} bytes", gcode.exists() && gcode.length() > 1000)
        val text = gcode.readText()

        val toolChangesByLayer = text.split(Regex("(?m)^;LAYER_CHANGE$")).drop(1).map { layer ->
            Regex("(?m)^T[0-9]+$").findAll(layer).map { it.value }.toList()
        }
        assertTrue("expected at least 10 layers in this slice, got ${toolChangesByLayer.size}", toolChangesByLayer.size >= 10)
        val layersWithAToolChange = toolChangesByLayer.count { it.isNotEmpty() }
        assertTrue(
            "expected tool changes on at least 80% of layers (the blend alternates every layer), got $layersWithAToolChange/${toolChangesByLayer.size}",
            layersWithAToolChange >= (toolChangesByLayer.size * 0.8),
        )
    }

    @Test fun fullSpectrumDefinitionsReachTheSlicedConfig() {
        val cubeFile = cube("fullspectrum.stl")
        // A minimal, well-formed mixed_filament_definitions string, exactly Full Spectrum's own on-disk shape
        // (Snapmaker Orca's MixedFilamentManager save format) - not fabricated ad hoc, so a real config-apply
        // failure surfaces here rather than being masked by a garbage-in/garbage-out pass.
        val definitions = "1:0:1:50:#800080:Mix 1:1:-1:"
        val red = BUNDLED_MATERIAL_PROFILES.first { it.id == "bundled-pla" }.copy(id = "fs-red", colorHex = "#FF0000")
        val blue = BUNDLED_MATERIAL_PROFILES.first { it.id == "bundled-pla" }.copy(id = "fs-blue", colorHex = "#0000FF")
        val slotMaterials = listOf(red, blue, null, null)

        val outcome = slicer().sliceColourMix(
            SlicingPrinterModel.SNAPMAKER_U1,
            listOf(cubeFile to ModelTransform()),
            toolSlotIndices = emptyList(),
            slotMaterials = slotMaterials,
            mixedFilamentDefinitions = definitions,
        )
        val gcode = (outcome as? SliceOutcome.Success)?.gcode ?: throw AssertionError("expected a real Full Spectrum slice, got $outcome")
        assertTrue("expected real g-code output, got ${gcode.length()} bytes", gcode.exists() && gcode.length() > 1000)
        val text = gcode.readText()
        // The override travels as an ordinary config key (SlicingCoordinator.sliceProject's own mixedOverrides) -
        // OrcaSlicer's own end-of-file config dump echoes every non-default key it applied, `key = value` per line.
        assertTrue(
            "expected ${FullSpectrumFormat.DEFINITIONS_KEY} in the sliced config block",
            text.contains(FullSpectrumFormat.DEFINITIONS_KEY),
        )
        assertTrue("expected the real definitions value in the sliced config block", text.contains(definitions))
    }
}

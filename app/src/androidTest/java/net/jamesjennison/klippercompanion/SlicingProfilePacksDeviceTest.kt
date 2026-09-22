package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.orcaslicer.engine.NativeEngine
import java.io.File

// WO-13 Phase 4: proves the real, bundled per-printer profile packs actually work through the
// real engine, not just that their JSON resolves offline. The Centauri Carbon/COSMOS pack is the
// safety-critical one - see assets/slicer_profiles/PROVENANCE.md and FirmwareIdentity.kt.
@RunWith(AndroidJUnit4::class)
class SlicingProfilePacksDeviceTest {
    private fun sliceCube(model: SlicingPrinterModel, cosmosGeneration: CosmosProfileGeneration? = null): String {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(appContext.cacheDir, "cube.stl")
        testContext.assets.open("cube.stl").use { it.copyTo(input.outputStream()) }
        val output = File(appContext.cacheDir, "profile_pack_output_${model.name}.gcode")
        output.delete()
        val pack = slicingProfilePack(model, cosmosGeneration) ?: throw AssertionError("no profile pack for $model/$cosmosGeneration")
        val profilePaths = pack.materialize(appContext)
        NativeEngine.nativeSliceFile(input.absolutePath, output.absolutePath, profilePaths.toTypedArray(), emptyArray(), emptyArray(), 0.0, 0.0, 0.0, 1.0)
        assertTrue("expected real g-code output for $model", output.exists() && output.length() > 1000)
        return output.readText()
    }
    @Test fun centauriCarbonCosmosProfileProducesRealCosmosStartGcode() {
        val gcode = sliceCube(SlicingPrinterModel.ELEGOO_CENTAURI_CARBON, CosmosProfileGeneration.CURRENT)
        // The exact, safety-critical thing this whole data model exists to guarantee: the real
        // COSMOS macros appear in the output, and the old, incompatible M729/M8213-era sequence
        // does not (that's the sequence COSMOS 26.07.0+ rejects with a hard emergency stop).
        assertTrue("expected COSMOS's own PRINT_START macro", gcode.contains("PRINT_START"))
        assertTrue("expected COSMOS's own PRINT_END macro", gcode.contains("PRINT_END"))
        assertFalse("must not contain the old, COSMOS-incompatible M729", gcode.contains("M729"))
        assertFalse("must not contain the old, COSMOS-incompatible M8213", gcode.contains("M8213"))
    }
    @Test fun snapmakerU1ProfileSlicesRealGcode() {
        val gcode = sliceCube(SlicingPrinterModel.SNAPMAKER_U1)
        assertTrue(gcode.contains("G1"))
    }
    @Test fun genericKlipperProfileSlicesRealGcode() {
        val gcode = sliceCube(SlicingPrinterModel.GENERIC_KLIPPER)
        assertTrue(gcode.contains("G1"))
    }
    @Test fun bambuGenericProfileSlicesRealGcode() {
        val gcode = sliceCube(SlicingPrinterModel.BAMBU_GENERIC)
        assertTrue(gcode.contains("G1"))
    }
    @Test fun prusaGenericProfileSlicesRealGcode() {
        val gcode = sliceCube(SlicingPrinterModel.PRUSA_GENERIC)
        assertTrue(gcode.contains("G1"))
    }
}

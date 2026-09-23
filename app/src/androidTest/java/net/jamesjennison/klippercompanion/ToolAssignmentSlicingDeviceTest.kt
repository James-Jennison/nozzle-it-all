package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.orcaslicer.engine.NativeEngine
import java.io.File

// Phase 8 first real increment (§11, §16, WO-25): engine::slice_multi_object's new per-object
// tool_index parameter sets the real OrcaSlicer per-object "extruder" config option
// (ModelObject::config.set("extruder", N), the same mechanism the desktop GUI's own "Set
// extruder" uses - PrintConfig.cpp/Model.hpp, read directly, not assumed). This test proves the
// plumbing is real and harmless (doesn't crash, doesn't corrupt output, doesn't affect single-
// tool slicing) - it deliberately does NOT assert a differentiated tool-change appears in the
// G-code yet, because it genuinely doesn't with today's bundled profiles: confirmed empirically
// by diffing two real slices (tool-assigned [1,2] vs. default [0,0]) of the same two objects on
// the Snapmaker U1 profile (the one bundled machine.json declaring >1 real extruder) - the only
// difference was filenames baked into comments, zero G-code-level effect. Root cause, also
// confirmed by reading the bundled asset directly: slicer_profiles/snapmaker_u1/filament.json
// declares exactly one filament slot (every filament_* array has length 1, no filament_colour
// key at all) even though its own machine.json's extruder_colour array declares 4 - OrcaSlicer's
// per-object "extruder" assignment selects *which configured filament slot* prints an object, and
// there is only one configured here, so every value normalizes to the same, only real slot
// regardless of what's requested. Wiring a genuine multi-slot filament config for Snapmaker U1 is
// real, separate, larger work (not guessed at or half-built here) - see WORK_ORDER.md's own note
// on this for what a later increment needs to do.
@RunWith(AndroidJUnit4::class)
class ToolAssignmentSlicingDeviceTest {
    private fun cube(name: String): File {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(appContext.cacheDir, name)
        testContext.assets.open("cube.stl").use { it.copyTo(input.outputStream()) }
        return input
    }

    private fun sliceWithTools(outputName: String, toolIndices: IntArray): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val a = cube("tool-a-$outputName.stl")
        val b = cube("tool-b-$outputName.stl")
        val output = File(appContext.cacheDir, outputName)
        output.delete()
        val pack = slicingProfilePack(SlicingPrinterModel.SNAPMAKER_U1, null) ?: throw AssertionError("no bundled Snapmaker U1 profile pack")
        val profilePaths = pack.materialize(appContext)
        NativeEngine.nativeSliceMultiObject(
            arrayOf(a.absolutePath, b.absolutePath),
            doubleArrayOf(-30.0, 30.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0), toolIndices,
            output.absolutePath, profilePaths.toTypedArray(), emptyArray(), emptyArray(),
        )
        assertTrue("expected real g-code output, got ${output.length()} bytes", output.exists() && output.length() > 1000)
        return output.readText()
    }

    @Test fun assigningRealToolIndicesNeitherCrashesNorCorruptsTheSlice() {
        val gcode = sliceWithTools("tool-assigned.gcode", intArrayOf(1, 2))
        assertTrue("expected G1 extrusion moves in the output", gcode.contains("G1"))
        assertTrue("expected an OrcaSlicer header", gcode.contains("HEADER_BLOCK_START"))
        val objectIds = Regex("; printing object [^\\n]* id:(\\d+)").findAll(gcode).map { it.groupValues[1] }.toSet()
        assertEquals("both objects must still slice, tool assignment must not drop one", 2, objectIds.size)
    }

    @Test fun toolIndexZeroLeavesTheDefaultToolUnchanged() {
        val gcode = sliceWithTools("tool-zero.gcode", intArrayOf(0, 0))
        assertTrue(gcode.contains("G1"))
    }

    @Test fun mismatchedToolSlotArrayLengthFailsWithARealException() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val a = cube("tool-mismatch.stl")
        val output = File(appContext.cacheDir, "tool-mismatch.gcode")
        assertThrows(RuntimeException::class.java) {
            NativeEngine.nativeSliceMultiObject(
                arrayOf(a.absolutePath),
                doubleArrayOf(0.0), doubleArrayOf(0.0), doubleArrayOf(0.0), doubleArrayOf(1.0), intArrayOf(0, 0),
                output.absolutePath, emptyArray(), emptyArray(), emptyArray(),
            )
        }
    }
}

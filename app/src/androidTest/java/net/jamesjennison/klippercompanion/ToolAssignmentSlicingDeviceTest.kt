package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.orcaslicer.engine.NativeEngine
import java.io.File

// Phase 8 first real increment (§11, §16, WO-25/WO-26): proves engine::slice_multi_object's
// per-object tool_index parameter produces a genuinely differentiated real tool-change in the
// sliced G-code against the one bundled profile with >1 real extruder (Snapmaker U1).
//
// Getting here took two real, root-caused fixes over WO-25/WO-26, both confirmed by reading the
// vendored engine source directly, not assumed:
// 1. Setting only the generic per-object "extruder" config key (the same one the desktop GUI's
//    own "Set extruder" writes) had zero effect, because this vendored engine's own
//    DynamicPrintConfig::normalize_fdm() - the real function that would translate "extruder" into
//    the concrete per-feature filament-id keys GCode generation actually reads - is commented out
//    in its own PrintApply.cpp. Fixed by setting those six keys directly (outer_wall_filament_id/
//    inner_wall_filament_id/sparse_infill_filament_id/internal_solid_filament_id/
//    top_surface_filament_id/bottom_surface_filament_id - slic3r_engine.cpp's own comment on this
//    has the full trail).
// 2. Even with those keys set, every requested tool silently clamped back to 1
//    (PrintObject.cpp's own clamp_feature_filament_to_valid) because libslic3r computes how many
//    extruders really exist from `filament_diameter`'s own array length (PrintApply.cpp: `size_t
//    num_extruders = m_config.filament_diameter.size()`), not from machine.json's
//    nozzle_diameter/extruder_colour (which only bound how many *could* exist) - and the bundled
//    Snapmaker U1 filament.json only ever declares one real filament_diameter entry. Fixed here by
//    overriding filament_diameter (and the other real per-slot keys below) to a real 4-entry
//    array via the existing generic config_overrides mechanism - no further native change needed.
//
// The real override recipe below is not yet generalized into production Kotlin (that's a real,
// separate follow-up - building it safely for an arbitrary MaterialAssignment list means
// replicating every other filament_* array key too, not just the ones varied here, to avoid an
// out-of-range read elsewhere in libslic3r) - this test hardcodes it to prove the underlying
// mechanism is real and correct before that generalization is built.
@RunWith(AndroidJUnit4::class)
class ToolAssignmentSlicingDeviceTest {
    private fun cube(name: String): File {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(appContext.cacheDir, name)
        testContext.assets.open("cube.stl").use { it.copyTo(input.outputStream()) }
        return input
    }

    // Real per-slot overrides proven against the bundled Snapmaker U1 profile: filament_diameter
    // is the load-bearing one (see this file's own header comment for why); filament_colour/
    // filament_type/nozzle_temperature*(the same real keys MaterialProfile.toOverrides() already
    // uses for the single-material case) make the four slots genuinely distinct rather than
    // identical copies.
    private val multiSlotOverrideKeys = arrayOf(
        "filament_diameter", "filament_colour", "filament_type",
        "nozzle_temperature", "nozzle_temperature_initial_layer",
    )
    private val multiSlotOverrideValues = arrayOf(
        "1.75,1.75,1.75,1.75", "#FF0000;#00FF00;#0000FF;#FFFF00", "PLA;PLA;PLA;PLA",
        "210,210,210,210", "210,210,210,210",
    )

    private fun sliceWithTools(outputName: String, toolIndices: IntArray, extraOverrides: Boolean): String {
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
            output.absolutePath, profilePaths.toTypedArray(),
            if (extraOverrides) multiSlotOverrideKeys else emptyArray(),
            if (extraOverrides) multiSlotOverrideValues else emptyArray(),
        )
        assertTrue("expected real g-code output, got ${output.length()} bytes", output.exists() && output.length() > 1000)
        return output.readText()
    }

    @Test fun differentToolIndicesWithARealMultiSlotFilamentConfigProduceRealToolChanges() {
        // tool_index is 1-based (see NativeEngine.nativeSliceMultiObject's own doc comment):
        // object A -> filament slot 1 (T0), object B -> filament slot 2 (T1).
        val gcode = sliceWithTools("tool-different.gcode", intArrayOf(1, 2), extraOverrides = true)
        val toolChanges = Regex("(?m)^T[0-9]+$").findAll(gcode).map { it.value }.toSet()
        assertTrue("expected a real T1 tool-change command in the sliced G-code, got $toolChanges", toolChanges.contains("T1"))
    }

    @Test fun sameToolIndexWithARealMultiSlotFilamentConfigStaysOnOneTool() {
        // A real negative control: both objects requesting the same slot must not produce a
        // second tool's worth of tool-change commands, proving the T1 above genuinely tracks the
        // per-object assignment rather than always appearing once a multi-slot config exists.
        val gcode = sliceWithTools("tool-same.gcode", intArrayOf(1, 1), extraOverrides = true)
        val toolChanges = Regex("(?m)^T[0-9]+$").findAll(gcode).map { it.value }.toSet()
        assertFalse("expected no T1 when both objects share tool 1, got $toolChanges", toolChanges.contains("T1"))
    }

    @Test fun toolIndexZeroWithoutAMultiSlotConfigLeavesTheDefaultToolUnchanged() {
        // Today's actual default call shape (every existing caller before this parameter
        // existed) - must still slice real, ordinary single-tool G-code.
        val gcode = sliceWithTools("tool-zero.gcode", intArrayOf(0, 0), extraOverrides = false)
        assertTrue(gcode.contains("G1"))
        val objectIds = Regex("; printing object [^\\n]* id:(\\d+)").findAll(gcode).map { it.groupValues[1] }.toSet()
        assertEquals(2, objectIds.size)
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

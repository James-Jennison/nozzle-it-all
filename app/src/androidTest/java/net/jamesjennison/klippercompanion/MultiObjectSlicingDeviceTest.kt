package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.orcaslicer.engine.NativeEngine
import java.io.File

// Phase 1 (Consumer Slicer Plan §16): proves engine::slice_multi_object/nativeSliceMultiObject
// really slices a multi-object build plate into one G-code file with each object placed at its
// own real position, not flattened into one mesh or silently dropping objects - the actual
// capability Phase 1's multi-object workspace depends on (Phase 0 only proved multi-object
// *loading* was possible, not slicing). "; printing object" comments (gcode_label_objects,
// OrcaSlicer's own real per-object G-code marker, default-on in PrintConfig.cpp - verified, not
// assumed) are the ground truth here: two real objects means exactly two real markers, not an
// object count asserted from this app's own bridge code marking its own homework.
@RunWith(AndroidJUnit4::class)
class MultiObjectSlicingDeviceTest {
    private fun cube(name: String): File {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(appContext.cacheDir, name)
        testContext.assets.open("cube.stl").use { it.copyTo(input.outputStream()) }
        return input
    }

    @Test fun slicesTwoRealObjectsPlacedApartIntoOneGcode() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val a = cube("multi-a.stl")
        val b = cube("multi-b.stl")
        val output = File(appContext.cacheDir, "multi-object.gcode")
        output.delete()

        // Two cubes placed 60mm apart on X (well clear of each other - cube.stl is a small
        // fixture, no real collision risk here) - real, distinct placements, not both left at
        // the default centered position.
        NativeEngine.nativeSliceMultiObject(
            arrayOf(a.absolutePath, b.absolutePath),
            doubleArrayOf(-30.0, 30.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0),
            output.absolutePath, emptyArray(), arrayOf("use_relative_e_distances"), arrayOf("0"),
        )

        assertTrue("expected real g-code output, got ${output.length()} bytes", output.exists() && output.length() > 1000)
        val gcode = output.readText()
        assertTrue("expected G1 extrusion moves in the output", gcode.contains("G1"))
        assertTrue("expected an OrcaSlicer header", gcode.contains("HEADER_BLOCK_START"))

        // "; printing object <name> id:<id> copy <copy>" is written once per layer per object
        // (GCode.cpp's own per-layer print loop), not once per object - real-world print jobs
        // instantly disprove a naive raw-count check (a 20mm cube at typical layer height is
        // ~100 layers, so 2 objects means ~200 markers, not 2). The real, unambiguous signal is
        // the number of *distinct* object ids, not how many times each was mentioned.
        val objectIds = Regex("; printing object [^\\n]* id:(\\d+)").findAll(gcode).map { it.groupValues[1] }.toSet()
        assertEquals("expected exactly 2 distinct real per-object G-code ids (gcode_label_objects, default-on)", 2, objectIds.size)

        // Real placement, not just an object count: collect every G1 X coordinate and confirm
        // the toolpath actually spans both placements (60mm apart), not just one object sliced
        // twice at the same spot. Checks the real spread, not absolute coordinates - the two
        // objects are centered around the bed's own center (100mm for the stock factory
        // printable_area, not 0mm - a real bed-shape detail, confirmed by a first failed attempt
        // that assumed origin-centered placement), so this doesn't assume a specific bed shape.
        val xCoords = Regex("G1 [^\\n]*X(-?[0-9]+\\.?[0-9]*)").findAll(gcode).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        assertTrue("expected real toolpath X coordinates", xCoords.isNotEmpty())
        val spread = xCoords.max() - xCoords.min()
        assertTrue("expected toolpath X spread of at least 50mm across two objects 60mm apart, got ${spread}mm", spread >= 50.0)
    }

    @Test fun mismatchedArrayLengthsFailWithARealException() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val a = cube("multi-mismatch.stl")
        val output = File(appContext.cacheDir, "multi-mismatch.gcode")
        assertThrows(RuntimeException::class.java) {
            NativeEngine.nativeSliceMultiObject(
                arrayOf(a.absolutePath),
                doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0), doubleArrayOf(0.0), doubleArrayOf(1.0),
                output.absolutePath, emptyArray(), emptyArray(), emptyArray(),
            )
        }
    }
}

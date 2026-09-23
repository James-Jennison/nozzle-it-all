package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.orcaslicer.engine.NativeEngine
import java.io.File
import java.util.zip.ZipFile

// Phase 6 follow-up (WO-23): proves engine::slice_multi_object_bambu_bundle/
// nativeSliceMultiObjectBambuBundle really slices a multi-object build plate into one real
// Bambu-compatible .gcode.3mf bundle - the same real structural checks BambuBundleDeviceTest
// already runs for the single-object bundle path, plus MultiObjectSlicingDeviceTest's own real
// per-object placement check (distinct object ids, a real toolpath spread), applied to the
// G-code actually embedded inside the bundle rather than a bare .gcode file.
@RunWith(AndroidJUnit4::class)
class MultiObjectBambuBundleDeviceTest {
    private fun cube(name: String): File {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(appContext.cacheDir, name)
        testContext.assets.open("cube.stl").use { it.copyTo(input.outputStream()) }
        return input
    }

    private fun sliceBundle(): File {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val a = cube("multi-bambu-a.stl")
        val b = cube("multi-bambu-b.stl")
        val output = File(appContext.cacheDir, "multi_bambu_bundle_test.gcode.3mf")
        output.delete()
        val pack = slicingProfilePack(SlicingPrinterModel.BAMBU_GENERIC, null) ?: throw AssertionError("no bundled Bambu profile pack")
        val profilePaths = pack.materialize(appContext)
        // Two cubes 60mm apart on X, same real, distinct placement MultiObjectSlicingDeviceTest
        // already uses for the plain-.gcode multi-object path.
        NativeEngine.nativeSliceMultiObjectBambuBundle(
            arrayOf(a.absolutePath, b.absolutePath),
            doubleArrayOf(-30.0, 30.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0),
            output.absolutePath, profilePaths.toTypedArray(), emptyArray(), emptyArray(),
        )
        return output
    }

    private fun embeddedGcode(bundle: File): String =
        ZipFile(bundle).use { zip ->
            val entry = zip.getEntry("Metadata/plate_1.gcode") ?: throw AssertionError("no plate_1.gcode entry")
            zip.getInputStream(entry).bufferedReader().readText()
        }

    @Test fun producesARealBundleWithTwoDistinctObjectsEmbedded() {
        val bundle = sliceBundle()
        assertTrue("expected a real, non-trivial .gcode.3mf file", bundle.exists() && bundle.length() > 5000)
        val gcode = embeddedGcode(bundle)
        assertTrue("expected real G-code content", gcode.contains("G1"))
        val objectIds = Regex("; printing object [^\\n]* id:(\\d+)").findAll(gcode).map { it.groupValues[1] }.toSet()
        assertEquals("expected exactly 2 distinct real per-object G-code ids", 2, objectIds.size)
    }

    @Test fun theTwoObjectsAreReallyPlacedApartInsideTheBundle() {
        val bundle = sliceBundle()
        val gcode = embeddedGcode(bundle)
        val xCoords = Regex("G1 [^\\n]*X(-?[0-9]+\\.?[0-9]*)").findAll(gcode).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        assertTrue("expected real toolpath X coordinates", xCoords.isNotEmpty())
        val spread = xCoords.max() - xCoords.min()
        assertTrue("expected toolpath X spread of at least 50mm across two objects 60mm apart, got ${spread}mm", spread >= 50.0)
    }

    @Test fun theEmbeddedMd5ActuallyMatchesTheEmbeddedGcode() {
        val bundle = sliceBundle()
        ZipFile(bundle).use { zip ->
            val gcodeEntry = zip.getEntry("Metadata/plate_1.gcode") ?: throw AssertionError("no plate_1.gcode entry")
            val gcodeBytes = zip.getInputStream(gcodeEntry).readBytes()
            val md5Entry = zip.getEntry("Metadata/plate_1.gcode.md5") ?: throw AssertionError("no plate_1.gcode.md5 entry")
            val declaredMd5 = zip.getInputStream(md5Entry).bufferedReader().readText().trim()
            val realMd5 = java.security.MessageDigest.getInstance("MD5").digest(gcodeBytes).joinToString("") { "%02X".format(it) }
            assertEquals("expected the archive's own declared MD5 to match a real MD5 of the embedded G-code", realMd5, declaredMd5)
        }
    }

    @Test fun mismatchedArrayLengthsFailWithARealException() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val a = cube("multi-bambu-mismatch.stl")
        val output = File(appContext.cacheDir, "multi-bambu-mismatch.gcode.3mf")
        assertThrows(RuntimeException::class.java) {
            NativeEngine.nativeSliceMultiObjectBambuBundle(
                arrayOf(a.absolutePath),
                doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0), doubleArrayOf(0.0), doubleArrayOf(1.0),
                output.absolutePath, emptyArray(), emptyArray(), emptyArray(),
            )
        }
    }
}

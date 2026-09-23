package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.orcaslicer.engine.NativeEngine
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

// Phase 6 (Consumer Slicer Plan §16): proves engine::slice_bambu_bundle() produces a real,
// structurally-correct .gcode.3mf bundle - not a mock, not just "the JNI call didn't throw."
// Verifies everything checkable without real Bambu hardware: real zip structure, the real files
// libslic3r's own store_bbs_3mf() writer is documented (by its own source, read directly - see
// slic3r_engine.cpp's header comment on this function) to produce for a Silence|WithGcode|
// SkipModel|SkipAuxiliary bundle, real G-code inside, and a real MD5 match (proving the archive's
// own embedded checksum is genuinely computed from the embedded G-code, not a placeholder).
// Whether a real Bambu printer's firmware *accepts* this bundle can't be verified without real
// Bambu hardware (this project's owner has none) - see docs/WORK_ORDER.md's own honest note on
// this gap.
@RunWith(AndroidJUnit4::class)
class BambuBundleDeviceTest {
    private fun cube(): File {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(appContext.cacheDir, "bambu_bundle_cube.stl")
        testContext.assets.open("cube.stl").use { it.copyTo(input.outputStream()) }
        return input
    }

    private fun sliceBundle(): File {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(appContext.cacheDir, "bambu_bundle_test.gcode.3mf")
        output.delete()
        val pack = slicingProfilePack(SlicingPrinterModel.BAMBU_GENERIC, null) ?: throw AssertionError("no bundled Bambu profile pack")
        val profilePaths = pack.materialize(appContext)
        NativeEngine.nativeSliceBambuBundle(cube().absolutePath, output.absolutePath, profilePaths.toTypedArray(), emptyArray(), emptyArray(), 0.0, 0.0, 0.0, 1.0)
        return output
    }

    @Test fun producesARealNonTrivialZipArchive() {
        val bundle = sliceBundle()
        assertTrue("expected a real, non-trivial .gcode.3mf file", bundle.exists() && bundle.length() > 5000)
        // A real zip - ZipFile throws on anything that isn't a genuine zip container.
        ZipFile(bundle).use { zip ->
            assertTrue("expected at least a handful of real entries", zip.size() >= 4)
        }
    }

    @Test fun containsTheRealRequired3mfContainerFiles() {
        val bundle = sliceBundle()
        ZipFile(bundle).use { zip ->
            assertNotNull("expected the real 3MF content-types declaration", zip.getEntry("[Content_Types].xml"))
            assertNotNull("expected the real 3MF relationships file", zip.getEntry("_rels/.rels"))
        }
    }

    @Test fun containsRealGcodeInsideThePlateFile() {
        val bundle = sliceBundle()
        ZipFile(bundle).use { zip ->
            val entry = zip.getEntry("Metadata/plate_1.gcode")
            assertNotNull("expected a real Metadata/plate_1.gcode entry", entry)
            val gcode = zip.getInputStream(entry).bufferedReader().readText()
            assertTrue("expected real G-code content", gcode.contains("G1"))
            assertTrue("expected a real OrcaSlicer header", gcode.contains("HEADER_BLOCK_START"))
        }
    }

    // The exporter computes this MD5 itself from the real embedded G-code (see
    // slic3r_engine.cpp's own comment on this) - a real, independent verification that the
    // checksum genuinely matches the bytes actually embedded, not a placeholder string.
    @Test fun theEmbeddedMd5ActuallyMatchesTheEmbeddedGcode() {
        val bundle = sliceBundle()
        ZipFile(bundle).use { zip ->
            val gcodeEntry = zip.getEntry("Metadata/plate_1.gcode") ?: throw AssertionError("no plate_1.gcode entry")
            val gcodeBytes = zip.getInputStream(gcodeEntry).readBytes()
            val md5Entry = zip.getEntry("Metadata/plate_1.gcode.md5") ?: throw AssertionError("no plate_1.gcode.md5 entry")
            val declaredMd5 = zip.getInputStream(md5Entry).bufferedReader().readText().trim()
            val realMd5 = MessageDigest.getInstance("MD5").digest(gcodeBytes).joinToString("") { "%02X".format(it) }
            assertEquals("expected the archive's own declared MD5 to match a real MD5 of the embedded G-code", realMd5, declaredMd5)
        }
    }

    @Test fun containsARealNonEmptyPlateThumbnail() {
        val bundle = sliceBundle()
        ZipFile(bundle).use { zip ->
            val entry = zip.getEntry("Metadata/plate_1.png")
            assertNotNull("expected a real Metadata/plate_1.png thumbnail", entry)
            assertTrue("expected real, non-trivial PNG bytes", entry!!.size > 100)
        }
    }

    @Test fun containsRealSliceInfoWithTheRealPrinterModel() {
        val bundle = sliceBundle()
        ZipFile(bundle).use { zip ->
            val entry = zip.getEntry("Metadata/slice_info.config")
            assertNotNull("expected a real Metadata/slice_info.config", entry)
            val xml = zip.getInputStream(entry).bufferedReader().readText()
            // "Bambu Lab A1" is the real printer_model value this app's own bundled
            // bambu_generic/machine.json declares - not invented, confirmed by the profile file itself.
            assertTrue("expected the real printer_model to appear in slice_info.config", xml.contains("Bambu Lab A1"))
        }
    }

    // No intermediate .gcode.tmp file should survive - see slic3r_engine.cpp's own cleanup.
    @Test fun leavesNoIntermediateGcodeFileBehind() {
        val bundle = sliceBundle()
        val leftover = File(bundle.parentFile, bundle.name + ".gcode.tmp")
        assertFalse("expected the intermediate .gcode.tmp to be cleaned up", leftover.exists())
    }
}

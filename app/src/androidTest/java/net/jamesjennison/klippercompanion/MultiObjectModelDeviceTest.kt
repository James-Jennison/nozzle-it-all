package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.orcaslicer.engine.NativeEngine
import java.io.File

// Phase 0 (WO-16, Consumer Slicer Plan §16): proves the native bridge's model load really
// preserves a multi-object Model::objects list, ahead of any UI using it yet - the specific
// acceptance criterion the plan's Phase 0 calls out ("a multi-object 3MF fixture loads with >1
// object reported by the native layer").
//
// Real regression coverage for a genuine bug found and fixed while building this test
// (slic3r_engine.cpp's own header comment has the full trace): every .3mf file - a real
// OrcaSlicer-native multi-object calibration file, a real single-object fixture, and a
// hand-crafted minimal spec-valid one - loaded with zero objects (while .stl worked fine),
// because Model::read_from_file()'s default LoadStrategy never set the LoadModel bit that
// _BBS_3MF_Importer gates all object/instance creation behind. Three different 3MF fixtures are
// kept here deliberately (not reduced to one) since they're what proved the bug was universal,
// not fixture-specific, and now guard against a regression the same way.
@RunWith(AndroidJUnit4::class)
class MultiObjectModelDeviceTest {
    private fun copyAsset(name: String): File {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(appContext.cacheDir, name)
        testContext.assets.open(name).use { it.copyTo(input.outputStream()) }
        return input
    }

    // multi_object.3mf: a real, unmodified 9-object 3MF copied from orcaslicer-android-engine's
    // own vendored OrcaSlicer resources (flow-rate calibration tooling) - see
    // THIRD_PARTY_NOTICES.md.
    @Test fun realMultiObject3mfReportsMoreThanOneObject() {
        val count = NativeEngine.nativeCountModelObjects(copyAsset("multi_object.3mf").absolutePath)
        assertTrue("expected the fixture's real object count (9), got $count", count > 1)
    }

    // A hand-crafted, minimal, spec-valid 2-object 3MF (no Bambu/Orca-specific metadata) -
    // proved the bug wasn't specific to Orca-native files.
    @Test fun plainStandardTwoObject3mfReportsTwoObjects() {
        val count = NativeEngine.nativeCountModelObjects(copyAsset("plain_two_objects.3mf").absolutePath)
        assertEquals(2, count)
    }

    // A real single-object 3MF fixture (orcaslicer/tests/data/test_3mf) - proved the bug affected
    // single-object files too, not just multi-object ones.
    @Test fun realSingleObject3mfReportsOneObject() {
        val count = NativeEngine.nativeCountModelObjects(copyAsset("single_real.3mf").absolutePath)
        assertEquals(1, count)
    }

    @Test fun realSingleObjectStlReportsExactlyOneObject() {
        val count = NativeEngine.nativeCountModelObjects(copyAsset("cube.stl").absolutePath)
        assertEquals(1, count)
    }

    @Test fun missingFileFailsWithARealException() {
        assertThrows(RuntimeException::class.java) {
            NativeEngine.nativeCountModelObjects("/does/not/exist.3mf")
        }
    }
}

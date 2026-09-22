package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.orcaslicer.engine.NativeEngine
import java.io.File

// WO-13: proves the real slicing engine (not a stub - full libslic3r, Boost/
// CGAL/OpenVDB/OCCT and the rest) actually slices real geometry on-device
// through the app's own build, not just in the standalone CLI tool this
// engine was originally verified with. cube.stl is the exact fixture that
// tool already sliced successfully by hand - see docs/WORK_ORDER.md's WO-13
// entry.
@RunWith(AndroidJUnit4::class)
class NativeEngineSmokeTest {
    @Test fun realEngineSlicesRealGeometry() {
        // assets/cube.stl lives in this test APK, not the app-under-test's own APK - must read
        // it via the instrumentation's own `context`, not `targetContext` (a real bug hit on
        // first run: FileNotFoundException).
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(appContext.cacheDir, "cube.stl")
        testContext.assets.open("cube.stl").use { it.copyTo(input.outputStream()) }
        val output = File(appContext.cacheDir, "cube.gcode")
        output.delete()

        // No real printer profile - just one override (use_relative_e_distances=0), the same
        // fix the standalone CLI tool needed for a bare geometry test with stock factory
        // defaults, which otherwise assume a real profile's start/layer gcode supplies the
        // G92 E0 reset relative-E addressing needs. Passed as a direct config override, not a
        // profile-file load (a real bug hit on the second run: loading the same override
        // through a bare profile JSON file tripped GCode.cpp's placeholder-resolution check in
        // a way the proven-working CLI tool's direct-override path never did).
        NativeEngine.nativeSliceFile(input.absolutePath, output.absolutePath, emptyArray(), arrayOf("use_relative_e_distances"), arrayOf("0"), 0.0, 0.0, 0.0, 1.0)

        assertTrue("expected real g-code output, got ${output.length()} bytes", output.exists() && output.length() > 1000)
        val gcode = output.readText()
        assertTrue("expected G1 extrusion moves in the output", gcode.contains("G1"))
        assertTrue("expected an OrcaSlicer header", gcode.contains("HEADER_BLOCK_START"))
    }

    // Temporary diagnostic - see NativeEngine.nativeDiagnoseConfigDef's own comment and
    // docs/WORK_ORDER.md's WO-13 entry. Not an assertion of expected behavior; just surfaces
    // the native-side state for direct inspection until the root cause is found.
    @Test fun diagnoseConfigDefState() {
        println("WO13_DIAGNOSTIC: ${NativeEngine.nativeDiagnoseConfigDef()}")
    }
}

package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.orcaslicer.engine.NativeEngine
import java.io.File

// WO-14 part D (owner request: "I want it all now", including support painting). Real,
// upstream-GUI-traced libslic3r machinery (AABBMesh, TriangleSelector, ModelVolume.
// supported_facets), not reinvented - see slic3r_engine.cpp's own extensive comment on exactly
// which transform came from where. This proves the native paint session actually paints real
// triangles and that a slice through it actually honors what was painted, on the real device.
@RunWith(AndroidJUnit4::class)
class PaintSessionDeviceTest {
    private fun cube(): File {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(appContext.cacheDir, "paint_session_cube.stl")
        testContext.assets.open("cube.stl").use { it.copyTo(input.outputStream()) }
        return input
    }
    // A real overhang fixture (not in the app's own shipped assets - generated for this test):
    // a 4x4x10mm pillar with a 16x16x2mm cap on top, so the cap overhangs the pillar on all
    // sides - unlike cube.stl, which has no downward-facing surface anywhere that could ever
    // need support. See project_and_append_custom_facets (PrintObject.cpp): painted enforcers
    // are only ever projected from downward-facing facets ("Project downward facing painted
    // areas upwards") - a flat-top cube structurally cannot exercise that code path at all.
    private fun overhang(): File {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(appContext.cacheDir, "paint_session_overhang.stl")
        testContext.assets.open("overhang.stl").use { it.copyTo(input.outputStream()) }
        return input
    }
    // Straight down through the bed's default center, from well above any real model's height -
    // guaranteed to hit the cube's top face regardless of its exact real dimensions.
    private fun paintTopFace(handle: Long, enforcer: Boolean = true) {
        NativeEngine.nativePaintStroke(handle, 100.0, 100.0, 1000.0, 0.0, 0.0, -1.0, 5.0, enforcer)
    }
    // The cap's underside at local (6,0,10) - outside the pillar's +-2mm footprint, so it's a
    // real, unsupported overhang - which becomes world (106,100,10) after the default 200x200
    // bed's centering (the object's own local XY bbox is already centered on (0,0)). Approaches
    // from below (upward ray) so the hit is on the downward-facing underside, not the top.
    private fun paintOverhangUnderside(handle: Long, enforcer: Boolean = true) {
        NativeEngine.nativePaintStroke(handle, 106.0, 100.0, 5.0, 0.0, 0.0, 1.0, 3.0, enforcer)
    }

    @Test fun openPaintCloseSessionMarksRealTriangles() {
        val handle = NativeEngine.nativeOpenPaintSession(cube().absolutePath)
        try {
            assertEquals("nothing painted yet", 0, NativeEngine.nativeGetPaintedFacets(handle).size)
            paintTopFace(handle)
            val painted = NativeEngine.nativeGetPaintedFacets(handle)
            assertTrue("expected real painted triangles after a stroke that hits the model", painted.isNotEmpty())
            assertEquals("position-only buffer: multiple of 9 floats (3 vertices x 3 floats)", 0, painted.size % 9)
        } finally { NativeEngine.nativeClosePaintSession(handle) }
    }
    @Test fun strokeMissingTheModelIsASilentNoOp() {
        val handle = NativeEngine.nativeOpenPaintSession(cube().absolutePath)
        try {
            // Aimed far away from the bed-centered cube (100,100) - a real miss, not a hit.
            NativeEngine.nativePaintStroke(handle, -5000.0, -5000.0, 1000.0, 0.0, 0.0, -1.0, 5.0, true)
            assertEquals("a ray that misses the mesh must not paint anything", 0, NativeEngine.nativeGetPaintedFacets(handle).size)
        } finally { NativeEngine.nativeClosePaintSession(handle) }
    }
    @Test fun closingAnUnknownOrAlreadyClosedSessionDoesNotCrash() {
        // close is a real cleanup call, not a query - it must be safe to call on a handle that
        // was never opened (e.g. a Kotlin-side double-dispose bug) rather than crash the process.
        NativeEngine.nativeClosePaintSession(999_999_999L)
    }
    @Test fun paintedSupportsActuallyReachTheSlicedGcode() {
        // Isolates painting as the only variable between the two slices below. Both use the same
        // real overhang fixture and support_type=normal(manual), which - per PrintConfig.cpp's
        // own tooltip - disables automatic overhang detection entirely: "If Normal (manual)... is
        // selected, only support enforcers are generated." So any support material in the
        // painted slice can only have come from the painted enforcer, not auto-detection finding
        // the same real overhang on its own (which it otherwise would, since this fixture has a
        // genuine one - see overhang()'s own comment).
        // has_support() (Print.hpp) is `enable_support || enforce_support_layers > 0`, so
        // enable_support=1 is still required for the support subsystem to run at all.
        // use_relative_e_distances=0: the same fix cli_test.cpp's own comment documents - stock
        // defaults enable relative extruder addressing, which validate() correctly rejects
        // without a "G92 E0" reset in layer_gcode; a real printer profile supplies that, but
        // there's none here (this test slices with no profile, same as cli_test.cpp).
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val unpaintedOutput = File(appContext.cacheDir, "paint_session_unpainted.gcode")
        val paintedOutput = File(appContext.cacheDir, "paint_session_painted.gcode")
        unpaintedOutput.delete(); paintedOutput.delete()
        val overrideKeys = arrayOf("enable_support", "use_relative_e_distances", "support_type")
        val overrideValues = arrayOf("1", "0", "normal(manual)")

        NativeEngine.nativeSliceFile(overhang().absolutePath, unpaintedOutput.absolutePath, emptyArray(), overrideKeys, overrideValues)
        val unpaintedGcode = unpaintedOutput.readText()
        assertFalse("manual support mode with nothing painted must not generate any support toolpath, even though this fixture has a real overhang",
            unpaintedGcode.contains(";TYPE:Support"))

        val handle = NativeEngine.nativeOpenPaintSession(overhang().absolutePath)
        try {
            paintOverhangUnderside(handle)
            assertTrue(NativeEngine.nativeGetPaintedFacets(handle).isNotEmpty())
            NativeEngine.nativeSlicePaintSession(handle, paintedOutput.absolutePath, emptyArray(), overrideKeys, overrideValues)
        } finally { NativeEngine.nativeClosePaintSession(handle) }
        val paintedGcode = paintedOutput.readText()
        assertTrue("a painted enforcer on the overhang's underside must produce support toolpath in manual mode, where nothing else could have",
            paintedGcode.contains(";TYPE:Support"))
    }
}

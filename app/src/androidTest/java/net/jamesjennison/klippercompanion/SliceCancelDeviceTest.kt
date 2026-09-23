package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.orcaslicer.engine.NativeEngine
import java.io.File
import java.util.concurrent.CancellationException

@RunWith(AndroidJUnit4::class)
class SliceCancelDeviceTest {
    private fun setup(name: String): Triple<File, File, Array<String>> {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(appContext.cacheDir, "cube.stl")
        testContext.assets.open("cube.stl").use { it.copyTo(input.outputStream()) }
        val output = File(appContext.cacheDir, name).also { it.delete() }
        val pack = slicingProfilePack(SlicingPrinterModel.GENERIC_KLIPPER, null) ?: throw AssertionError("no pack")
        return Triple(input, output, pack.materialize(appContext).toTypedArray())
    }

    // A tall, fine-layered slice that takes many seconds uncancelled.
    private fun slowSlice(input: File, output: File, profiles: Array<String>) = NativeEngine.nativeSliceFile(
        input.absolutePath, output.absolutePath, profiles, arrayOf("layer_height", "initial_layer_print_height"), arrayOf("0.04", "0.04"),
        0.0, 0.0, 0.0, 4.0,
    )

    @Test fun cancellingMidSliceThrowsCancellationRemovesOutputAndReportsProgress() {
        val (input, output, profiles) = setup("cancel_mid.gcode")
        NativeEngine.nativeResetCancel()
        val progress = mutableListOf<Int>()
        val canceller = Thread {
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline) {
                val p = NativeEngine.nativeSliceProgress(); progress += p
                if (p in 1..99) { NativeEngine.nativeCancelSlice(); return@Thread }
                Thread.sleep(20)
            }
        }
        canceller.start()
        val started = System.currentTimeMillis()
        try { slowSlice(input, output, profiles); fail("expected the slice to be cancelled (finished in ${System.currentTimeMillis() - started} ms)") }
        catch (_: CancellationException) {}
        canceller.join()
        assertFalse("partial output must be removed", output.exists())
        assertTrue("progress must be non-decreasing: $progress", progress.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun aCancelledSliceDoesNotPoisonTheNextOne() {
        val (input, output, profiles) = setup("cancel_then_ok.gcode")
        NativeEngine.nativeCancelSlice() // stale request with nothing running
        NativeEngine.nativeResetCancel()
        NativeEngine.nativeSliceFile(input.absolutePath, output.absolutePath, profiles, emptyArray(), emptyArray(), 0.0, 0.0, 0.0, 1.0)
        assertTrue(output.exists() && output.length() > 1000)
        assertEquals(100, NativeEngine.nativeSliceProgress())
    }
}

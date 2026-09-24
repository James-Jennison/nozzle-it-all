package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MemoryPressureDeviceTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val realCheck = SlicingCoordinator.systemLowMemory
    private val marker get() = File(ctx.filesDir, "slice-in-progress")
    @After fun restore() { SlicingCoordinator.systemLowMemory = realCheck; marker.delete() }

    private fun cube(): File { val f = File(ctx.cacheDir, "mem-cube.stl"); InstrumentationRegistry.getInstrumentation().context.assets.open("cube.stl").use { it.copyTo(f.outputStream()) }; return f }
    private val profile = PrinterProfile("http://192.168.1.50/", "K", kind = PrinterKind.GENERIC_KLIPPER, slicingModel = SlicingPrinterModel.GENERIC_KLIPPER)

    @Test fun aSliceIsRefusedWhileAndroidReportsLowMemoryAndLeavesNoTraceOfItself() = runBlocking<Unit> {
        SlicingCoordinator.systemLowMemory = { true }
        val outcome = SlicingCoordinator.sliceProject(ctx, listOf(cube() to ModelTransform()), profile)
        assertEquals(SliceOutcome.Failed(SlicingCoordinator.LOW_MEMORY_MESSAGE), outcome)
        assertFalse("a refused slice must not leave an interruption marker", marker.exists())
    }

    @Test fun aNormalSliceLeavesNoInterruptionMarkerBehind() = runBlocking<Unit> {
        SlicingCoordinator.systemLowMemory = { false }
        assertTrue(SlicingCoordinator.sliceProject(ctx, listOf(cube() to ModelTransform()), profile) is SliceOutcome.Success)
        assertFalse(marker.exists())
        assertFalse(SlicingCoordinator.consumeInterruptedSlice(ctx))
    }

    @Test fun aLeftoverMarkerIsReportedExactlyOnceAsAnInterruptedSlice() {
        marker.writeText("1")
        assertTrue(SlicingCoordinator.consumeInterruptedSlice(ctx))
        assertFalse(marker.exists())
        assertFalse(SlicingCoordinator.consumeInterruptedSlice(ctx))
    }

    @Test fun theRealSystemMemoryCheckAnswersWithoutThrowing() { assertNotNull(realCheck(ctx)) }
}

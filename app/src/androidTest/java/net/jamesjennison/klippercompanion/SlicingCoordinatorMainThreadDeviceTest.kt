package net.jamesjennison.klippercompanion

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

// WO-13: real bug hit live tonight - SlicingCoordinator.slice() threw
// NetworkOnMainThreadException when called from the actual UI (SliceAndPrintPanel's
// LaunchedEffect, which runs on Compose's Main dispatcher), even though
// SlicingCoordinatorDeviceTest's own runBlocking-based tests all passed - runBlocking runs on a
// plain JVM thread, never Android's real main looper, so it couldn't have caught this. This test
// specifically drives slice() from inside a real Composable's LaunchedEffect, the same call
// shape SliceAndPrintPanel uses, so this exact regression can't silently come back.
class SlicingCoordinatorMainThreadDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Volatile private var outcome: Any? = null

    @Test fun sliceCalledFromAComposableLaunchedEffectDoesNotThrowOnMainThread() {
        outcome = null
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(appContext.cacheDir, "main_thread_cube.stl")
        testContext.assets.open("cube.stl").use { it.copyTo(input.outputStream()) }
        // The exact shape that broke live: a Centauri Carbon profile, so slice() actually
        // performs a live network call (firmwareIdentity()) as part of its normal path.
        val profile = PrinterProfile("http://192.168.1.114:80/", "CC1", slicingModel = SlicingPrinterModel.ELEGOO_CENTAURI_CARBON, declaredFirmwareVersion = "Release - 26.08.0")

        compose.setContent {
            LaunchedEffect(Unit) {
                outcome = try { SlicingCoordinator.slice(appContext, input, profile) } catch (e: Throwable) { e }
            }
        }
        compose.waitUntil(timeoutMillis = 20_000) { outcome != null }
        val result = outcome
        assertFalse("must not throw (NetworkOnMainThreadException or anything else) when called from a real Composable's LaunchedEffect: $result", result is Throwable)
        assertTrue("expected a real SliceOutcome, got $result", result is SliceOutcome)
    }
}

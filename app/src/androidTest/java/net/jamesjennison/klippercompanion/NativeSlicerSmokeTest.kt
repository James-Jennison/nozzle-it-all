package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// WO-13 Phase 0: proves System.loadLibrary + JNI + the vendored oneTBB
// cross-compile actually work on a real device, not just in the build
// output. A wrong ABI or a broken link step throws from NativeSlicer's
// init block before this test body even runs.
@RunWith(AndroidJUnit4::class)
class NativeSlicerSmokeTest {
    @Test fun nativeLibraryLoadsAndTbbReportsRealConcurrency() {
        val concurrency = NativeSlicer.tbbMaxConcurrency()
        assertTrue("expected a positive core count from a real oneTBB call, got $concurrency", concurrency > 0)
    }
}

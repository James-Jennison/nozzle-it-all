package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelfCheckDeviceTest {
    @Test fun everyDependencyCheckPasses() = runBlocking<Unit> {
        val results = SelfCheck.run(InstrumentationRegistry.getInstrumentation().targetContext)
        assertEquals(9, results.size)
        val failed = results.filterNot { it.ok }
        assertTrue("self-check failures: " + failed.joinToString { "${it.name}: ${it.detail}" }, failed.isEmpty())
    }
}

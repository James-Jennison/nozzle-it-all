package net.jamesjennison.klippercompanion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.nozzleitall.environment.*
import net.jamesjennison.klippercompanion.environment.AndroidEnvironmentalStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Run only against the isolated .environment acceptance package, never an owner's populated app. */
class EnvironmentalDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun settingsOffEnableIdleAndHistoryAreIndependentOfPrinters() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".environment")) { "Requires isolated environment acceptance package" }
        assertFalse(File(context.filesDir, "environment/history.db").exists())
        compose.onNodeWithTag("nav-4").performClick()
        compose.onNodeWithTag("environment-enable").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("environment-discover").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(File(context.filesDir, "environment/history.db").exists())
        compose.onNodeWithTag("environment-history").performScrollTo().performClick()
        compose.waitUntil(5000) { File(context.filesDir, "environment/history.db").exists() }
        compose.onNodeWithTag("nav-0").performClick()
        compose.onNodeWithTag("nav-4").performClick()
        compose.onNodeWithTag("environment-discover").performScrollTo().assertExists()
    }
    @Test fun actualAndroidDatabaseTransactionsPagingAndDelete() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".environment"))
        val path = File(context.cacheDir, "environment-fixture-${newId()}.db")
        val h = EnvironmentalHistory(SqlStoreFactory { AndroidEnvironmentalStore(path) })
        val b = h.bind(Candidate("fixture", "bthome-v2", "BTHome v2"), "fixture")
        val o = Observation("fixture", "inc", "test", "run", 1, b.id, 1, 1000, 1001, 1,
            decoder = BleDecoders.VERSION, readings = listOf(Reading(Channel.TEMPERATURE, -12.34)))
        assertEquals("inserted", h.append(o, "inc"))
        assertEquals("duplicate", h.append(o.copy(receivedAt = 2000), "inc"))
        assertEquals(1, h.page().size)
        val recent = o.copy(eventId = "recent", observedAt = 1734567890123L, receivedAt = 1734567890124L)
        h.append(recent, "inc")
        h.retain(1734567890124L, days = 1)
        assertEquals(1, h.page().size)
        assertTrue(h.page().single().contains("1734567890123"))
        h.deleteAll(); assertTrue(h.page().isEmpty()); h.close()
        // Only this test-owned environmental fixture, not any owner data.
        assertTrue(context.deleteDatabase(path.absolutePath))
    }
}

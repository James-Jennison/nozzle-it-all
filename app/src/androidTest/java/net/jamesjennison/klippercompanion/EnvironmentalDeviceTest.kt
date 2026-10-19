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
import org.junit.Assume.assumeTrue
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject

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
        val monitor = (context.applicationContext as NozzleApp).environment
        compose.onNodeWithTag("nav-0").performClick()
        compose.onNodeWithTag("nav-4").performClick()
        compose.onNodeWithTag("environment-discover").performScrollTo().assertExists()
        assertSame(monitor, (context.applicationContext as NozzleApp).environment)
        compose.onNodeWithText("Delete environmental data…").performScrollTo().performClick()
        compose.onNodeWithText("Confirm delete / resume").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("environment-enable").fetchSemanticsNodes().isNotEmpty() }
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
    /** Explicitly opt in on the authorized phone after granting the real OS permission prompt. */
    @Test fun foregroundRadioHardwareAcceptance() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("environmentRadio") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".environment"))
        val permission = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
        assertEquals("Grant the actual OS prompt before this opt-in run", PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(permission))
        compose.onNodeWithTag("nav-4").performClick()
        if (compose.onAllNodesWithTag("environment-enable").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("environment-enable").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("environment-discover").fetchSemanticsNodes().isNotEmpty() }
        }
        compose.onNodeWithTag("environment-discover").performScrollTo().performClick()
        compose.waitUntil(44000) { compose.onAllNodesWithText("Save only").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Save only")[0].performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("environment-start").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Save room assignment")[0].performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodes(hasText("Room assignment saved", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("environment-start").performScrollTo().performClick()
        compose.waitUntil(45000) { compose.onAllNodes(hasText("Reception ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodes(hasText("Android BLE · radio", substring = true)).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("nav-0").performClick()
        compose.onNodeWithTag("nav-4").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("environment-discover").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("environment-discover").performScrollTo().assertExists()
        val history = EnvironmentalHistory(SqlStoreFactory { AndroidEnvironmentalStore(File(context.filesDir, "environment/history.db")) })
        history.use { h ->
            val before = h.eventCount()
            assertTrue("Actual hardware reception must persist", before > 0)
            Thread.sleep(2000)
            assertEquals("Leaving panel must stop persistence and not auto-resume", before, h.eventCount())
            val event = JSONObject(h.page().first())
            println("ENV_RADIO actual_receptions=$before decoder=${event.getString("decoder")} foreground_stop=true qualification=false")
        }
    }
}

package net.jamesjennison.klippercompanion

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.nozzleitall.environment.*
import net.jamesjennison.klippercompanion.environment.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GoveeNameImportDeviceTest {
    @get:Rule val compose = createComposeRule()
    private fun fixture(block: (AndroidGoveeNames, android.content.SharedPreferences) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".environment"))
        val file = "govee-name-fixture-${java.util.UUID.randomUUID()}"
        val prefs = CredentialStore.open(context, file)
        val store = AndroidGoveeNames({ prefs }) { key, _ ->
            assertEquals("fixture-not-a-real-key", key)
            listOf(GoveeDeviceName("AA:BB:CC:DD:EE:FF", "H5100", "Office shelf"))
        }
        try { block(store, prefs) } finally { store.cancel(); assertTrue(context.deleteSharedPreferences(file)) }
    }
    @Test fun privateEntryDefaultsToNotRememberingAndImportsNames() = fixture { store, _ ->
        compose.setContent { MaterialTheme { GoveeNameImport(store) } }
        compose.onNodeWithText("Import names from Govee").performClick()
        compose.onNodeWithTag("govee-private-key").performTextReplacement("fixture-not-a-real-key")
        compose.onNodeWithText("Import names", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { store.devices.value.isNotEmpty() }
        compose.runOnIdle { assertEquals("Office shelf", store.devices.value.single().name) }
        assertFalse(store.hasSavedKey())
        compose.onNodeWithText("Forget Govee key and imported names").performClick()
        compose.waitUntil(5000) { store.devices.value.isEmpty() }
    }
    @Test fun forgetRejectsAnInFlightLookupAndDoesNotResurrectNamesOrSavedKey() = fixture { _, prefs ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val store = AndroidGoveeNames({ prefs }) { _, _ ->
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS))
            listOf(GoveeDeviceName("AA:BB:CC:DD:EE:FF", "H5100", "Obsolete name"))
        }
        val request = store.begin()
        val outcome = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val worker = Thread { try { store.import("fixture-not-a-real-key", true, request) } catch (e: Throwable) { outcome.set(e) } }
        worker.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        store.forget(); release.countDown(); worker.join(5000)
        assertFalse(worker.isAlive)
        assertTrue(outcome.get() is GoveeNameException)
        assertTrue(store.devices.value.isEmpty()); assertFalse(store.hasSavedKey())
    }
    @Test fun cancellationDuringCredentialCommitRestoresThePreviousCredential() = fixture { _, prefs ->
        assertTrue(prefs.edit().putString("api-key", "previous-synthetic-fixture").commit())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val firstCommit = java.util.concurrent.atomic.AtomicBoolean(true)
        val delayed = object : android.content.SharedPreferences by prefs {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = prefs.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { editor.putString(key, value); return this }
                    override fun remove(key: String?): android.content.SharedPreferences.Editor { editor.remove(key); return this }
                    override fun commit(): Boolean {
                        if (firstCommit.compareAndSet(true, false)) {
                            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS))
                        }
                        return editor.commit()
                    }
                }
            }
        }
        val store = AndroidGoveeNames({ delayed }) { _, _ -> listOf(GoveeDeviceName("AA:BB:CC:DD:EE:FF", "H5100", "Obsolete")) }
        val outcome = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val request = store.begin()
        val worker = Thread { try { store.import("fixture-not-a-real-key", true, request) } catch (e: Throwable) { outcome.set(e) } }
        worker.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        store.cancel(); release.countDown(); worker.join(5000)
        assertFalse(worker.isAlive); assertTrue(outcome.get() is GoveeNameException)
        assertEquals("previous-synthetic-fixture", prefs.getString("api-key", null))
        assertTrue(store.devices.value.isEmpty())
        store.forget(); assertFalse(store.hasSavedKey())
    }
    @Test fun queuedForgetCannotBeOvertakenByANewImport() = fixture { store, _ ->
        val forget = store.requestForgetAsync()
        kotlinx.coroutines.runBlocking { forget.await() }
        assertFalse(store.forgetting.value)
        assertFalse(store.hasSavedKey())
        store.begin()
    }
}

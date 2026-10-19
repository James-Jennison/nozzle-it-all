package net.jamesjennison.klippercompanion

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
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
    @Test fun discoveryRestoresNamesAfterProcessRecreationWithoutOpeningCredentialsOrNetwork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".environment"))
        val file = java.io.File(context.cacheDir, "govee-directory-fixture-${java.util.UUID.randomUUID()}.json")
        try {
            GoveeNameCache(file).write(listOf(GoveeDeviceName("AA:BB:CC:DD:EE:FF", "H5100", "Saved fixture shelf")))
            val store = AndroidGoveeNames({ error("Discovery must not open credential storage") }, GoveeNameCache(file)) { _, _ -> error("Discovery must not use the network") }
            assertTrue(store.devices.value.isEmpty())
            assertEquals("Saved fixture shelf", store.cachedDevices().single().name)
            assertEquals("Saved fixture shelf", store.devices.value.single().name)
        } finally { file.delete() }
    }
    private fun AndroidGoveeNames.forget() { kotlinx.coroutines.runBlocking { requestForgetAsync().await() } }
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
        // Match the production panel's Column; sibling buttons must not overlap
        // in the test host's root Box (a touch would otherwise hit Forget).
        compose.setContent { MaterialTheme { Column { GoveeNameImport(store) } } }
        compose.onNodeWithText("Import names from Govee").performClick()
        compose.onNodeWithTag("govee-private-key", useUnmergedTree = true).performTextReplacement("fixture-not-a-real-key")
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText("Import names", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { store.devices.value.isNotEmpty() }
        compose.runOnIdle { assertEquals("Office shelf", store.devices.value.single().name) }
        assertFalse(store.hasSavedKey())
        compose.onNodeWithText("Forget Govee key and imported names").performClick()
        compose.waitUntil(5000) { store.devices.value.isEmpty() }
    }
    @Test fun rememberedFixtureCanBeReusedAndExplicitlyForgotten() = fixture { store, prefs ->
        assertEquals(1, store.import("fixture-not-a-real-key", true, store.begin()))
        assertTrue(store.hasSavedKey())
        assertEquals("fixture-not-a-real-key", prefs.getString("api-key", null))
        assertEquals(1, store.import(null, false, store.begin()))
        assertEquals("Office shelf", store.devices.value.single().name)
        store.forget()
        assertFalse(store.hasSavedKey()); assertFalse(prefs.contains("api-key"))
        assertTrue(store.devices.value.isEmpty())
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
    @Test fun failedCancellationRollbackRevokesSavedCredentialUse() {
      for (failDuringEdit in listOf(false, true)) fixture { _, prefs ->
        assertTrue(prefs.edit().putString("api-key", "previous-synthetic-fixture").commit())
        lateinit var store: AndroidGoveeNames
        var commits = 0
        var edits = 0
        val broken = object : android.content.SharedPreferences by prefs {
            override fun edit(): android.content.SharedPreferences.Editor {
                edits++
                if (failDuringEdit && edits == 2) throw IllegalStateException("synthetic edit failure")
                val editor = prefs.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { editor.putString(key, value); return this }
                    override fun remove(key: String?): android.content.SharedPreferences.Editor { editor.remove(key); return this }
                    override fun commit(): Boolean {
                        commits++
                        return if (commits == 1) editor.commit().also { store.cancel() } else if (!failDuringEdit && commits == 2) false else editor.commit()
                    }
                }
            }
        }
        var fetched = 0
        store = AndroidGoveeNames({ broken }) { _, _ -> fetched++; emptyList() }
        val error = assertThrows(GoveeNameException::class.java) { store.import("synthetic-new-fixture", true, store.begin()) }
        assertEquals(GoveeNameFailure.STORAGE, error.reason)
        assertEquals(GoveeNameFailure.CANCELLED, (error.suppressed.single() as GoveeNameException).reason)
        assertTrue(store.devices.value.isEmpty()); assertFalse(store.hasSavedKey())
        assertEquals(GoveeNameFailure.CLEANUP, assertThrows(GoveeNameException::class.java) { store.import(null, false, store.begin()) }.reason)
        assertEquals(1, fetched)
        store.forget(); assertFalse(prefs.contains("api-key"))
      }
    }
    @Test fun queuedForgetCannotBeOvertakenOrAbandonedAndFailureRevokesSavedKey() = fixture { _, prefs ->
        assertTrue(prefs.edit().putString("api-key", "synthetic-old-key").commit())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var fail = true
        val delayed = object : android.content.SharedPreferences by prefs {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = prefs.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    override fun remove(key: String?): android.content.SharedPreferences.Editor { editor.remove(key); return this }
                    override fun commit(): Boolean {
                        entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS))
                        return if (fail) false else editor.commit()
                    }
                }
            }
        }
        var fetched = false
        val store = AndroidGoveeNames({ delayed }) { _, _ -> fetched = true; emptyList() }
        val work = store.requestForgetAsync()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(store.forgetting.value)
            assertEquals(GoveeNameFailure.CLEANUP, assertThrows(GoveeNameException::class.java) { store.begin() }.reason)
            // Cancelling the caller's handle cannot orphan the already-owned cleanup.
            work.cancel()
        } finally { release.countDown() }
        kotlinx.coroutines.runBlocking { work.join() }
        assertFalse(store.forgetting.value)
        assertFalse(store.hasSavedKey())
        assertEquals(GoveeNameFailure.CLEANUP, assertThrows(GoveeNameException::class.java) { store.import(null, false, store.begin()) }.reason)
        assertFalse(fetched)
        fail = false; store.forget()
        assertFalse(prefs.contains("api-key"))
    }
}

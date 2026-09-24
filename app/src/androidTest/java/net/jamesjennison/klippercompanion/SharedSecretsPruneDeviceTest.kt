package net.jamesjennison.klippercompanion

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Saving printer profiles prunes stale printer keys from the shared secrets file - it must leave other features' entries alone. */
class SharedSecretsPruneDeviceTest {
    @Test fun savingProfilesKeepsMmfAndBespok3dEntriesAndDropsStalePrinterKeys() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val a = "prune-test-${UUID.randomUUID()}"; val b = "prune-test-${UUID.randomUUID()}"
        val prefs = ctx.getSharedPreferences(a, 0); val secrets = ctx.getSharedPreferences(b, 0)
        try {
            secrets.edit().putString("mmf.token", "T").putString("mmf.expires", "1").putString("bespok3d:10.0.0.5", "{}").putString("10.0.0.9:7125", "stale-key").commit()
            PrinterPreferences.saveProfiles(prefs, secrets, "10.0.0.1:7125", listOf(PrinterProfile("10.0.0.1:7125", apiKey = "k1")))
            assertEquals("T", secrets.getString("mmf.token", null)); assertEquals("{}", secrets.getString("bespok3d:10.0.0.5", null))
            assertNull("a stale printer key is still pruned", secrets.getString("10.0.0.9:7125", null))
            assertEquals("k1", secrets.getString("10.0.0.1:7125", null))
        } finally { ctx.deleteSharedPreferences(a); ctx.deleteSharedPreferences(b) }
    }
}

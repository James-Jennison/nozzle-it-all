package net.jamesjennison.klippercompanion

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

/** In-memory SharedPreferences fake with a working edit()/apply(), so saveProfiles' output can be
 * read back through profiles() in the same test - unlike the read-only fake in
 * PrinterPreferencesSecretFailureTest.kt, which only needs to simulate stored reads. */
private class InMemoryPrefs(private val strings: MutableMap<String, String> = mutableMapOf()) : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = strings
    override fun getString(key: String?, defValue: String?): String? = strings[key] ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String?, defValue: Int) = defValue
    override fun getLong(key: String?, defValue: Long) = defValue
    override fun getFloat(key: String?, defValue: Float) = defValue
    override fun getBoolean(key: String?, defValue: Boolean) = defValue
    override fun contains(key: String?) = strings.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, String?>()
        private val removals = mutableSetOf<String>()
        override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply {}
        override fun putInt(key: String?, value: Int) = apply {}
        override fun putLong(key: String?, value: Long) = apply {}
        override fun putFloat(key: String?, value: Float) = apply {}
        override fun putBoolean(key: String?, value: Boolean) = apply {}
        override fun remove(key: String?) = apply { if (key != null) removals += key }
        override fun clear() = apply { strings.clear() }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() { removals.forEach { strings.remove(it) }; pending.forEach { (k, v) -> if (v == null) strings.remove(k) else strings[k] = v } }
    }
}

class PrinterKindTest {
    @Test fun kindRoundTripsThroughSaveAndLoad() {
        val prefs = InMemoryPrefs()
        val secrets = InMemoryPrefs()
        val profile = PrinterProfile("http://u1.local/", "U1", kind = PrinterKind.SNAPMAKER_U1_PAXX)
        PrinterPreferences.saveProfiles(prefs, secrets, profile.address, listOf(profile))
        val loaded = PrinterPreferences.profiles(prefs, secrets)
        assertEquals(PrinterKind.SNAPMAKER_U1_PAXX, loaded.single().kind)
    }
    @Test fun missingOrUnrecognizedKindDefaultsToGenericKlipper() {
        val prefs = InMemoryPrefs(mutableMapOf("profilesV1" to
            """[{"address":"http://a.local/","name":"A","favorite":false,"cameraId":""},
                {"address":"http://b.local/","name":"B","favorite":false,"cameraId":"","kind":"SOME_FUTURE_VENDOR"}]"""))
        val secrets = InMemoryPrefs()
        val profiles = PrinterPreferences.profiles(prefs, secrets)
        assertEquals(PrinterKind.GENERIC_KLIPPER, profiles[0].kind)
        assertEquals(PrinterKind.GENERIC_KLIPPER, profiles[1].kind)
    }
}

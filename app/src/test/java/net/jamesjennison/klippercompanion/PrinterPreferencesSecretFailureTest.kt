package net.jamesjennison.klippercompanion

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

/** Minimal SharedPreferences fake supporting only what PrinterPreferences actually calls,
 * with the ability to make one key's read throw something other than ClassCastException -
 * simulating an EncryptedSharedPreferences/Keystore failure that isn't a plain cast error. */
private class FakePrefs(private val strings: MutableMap<String, String> = mutableMapOf(), private val throwingKeys: Set<String> = emptySet()) : SharedPreferences {
    override fun getAll() = throw UnsupportedOperationException()
    override fun getString(key: String?, defValue: String?): String? {
        if (key in throwingKeys) throw java.security.GeneralSecurityException("Keystore key invalidated")
        return strings[key] ?: defValue
    }
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String?, defValue: Int) = defValue
    override fun getLong(key: String?, defValue: Long) = defValue
    override fun getFloat(key: String?, defValue: Float) = defValue
    override fun getBoolean(key: String?, defValue: Boolean) = defValue
    override fun contains(key: String?) = strings.containsKey(key)
    override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

class PrinterPreferencesSecretFailureTest {
    @Test fun secretReadFailureOtherThanClassCastDoesNotDropTheWholeProfile() {
        val prefs = FakePrefs(mutableMapOf("profilesV1" to
            """[{"address":"http://a.local/","name":"A","favorite":false,"cameraId":""}]"""))
        val secrets = FakePrefs(throwingKeys = setOf("http://a.local/"))
        val profiles = PrinterPreferences.profiles(prefs, secrets)
        assertEquals(1, profiles.size)
        assertEquals("http://a.local/", profiles.single().address)
        assertEquals("A", profiles.single().name)
        assertEquals("", profiles.single().apiKey)
    }
}

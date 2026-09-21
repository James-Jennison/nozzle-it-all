package net.jamesjennison.klippercompanion

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

/** Minimal in-memory SharedPreferences fake - same shape as PrinterKindTest's own InMemoryPrefs,
 * kept file-local under a different name since Kotlin top-level classes share one package-wide
 * name even when private, so reusing "InMemoryPrefs" here collided with that file's. */
private class WidgetTestPrefs(private val strings: MutableMap<String, String> = mutableMapOf()) : SharedPreferences {
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

class WidgetPrinterSelectionTest {
    @Test fun roundTripsAndDefaultsToNullWhenNeverSet() {
        val prefs = WidgetTestPrefs()
        assertNull(WidgetPrinterSelection.get(prefs, 42))
        WidgetPrinterSelection.set(prefs, 42, "http://u1.local/")
        assertEquals("http://u1.local/", WidgetPrinterSelection.get(prefs, 42))
    }
    @Test fun differentWidgetIdsAreIndependent() {
        val prefs = WidgetTestPrefs()
        WidgetPrinterSelection.set(prefs, 1, "http://u1.local/")
        WidgetPrinterSelection.set(prefs, 2, "http://cc1.local/")
        assertEquals("http://u1.local/", WidgetPrinterSelection.get(prefs, 1))
        assertEquals("http://cc1.local/", WidgetPrinterSelection.get(prefs, 2))
    }
    @Test fun removeClearsOnlyThatWidgetsSelection() {
        val prefs = WidgetTestPrefs()
        WidgetPrinterSelection.set(prefs, 1, "http://u1.local/")
        WidgetPrinterSelection.set(prefs, 2, "http://cc1.local/")
        WidgetPrinterSelection.remove(prefs, 1)
        assertNull(WidgetPrinterSelection.get(prefs, 1))
        assertEquals("http://cc1.local/", WidgetPrinterSelection.get(prefs, 2))
    }
}

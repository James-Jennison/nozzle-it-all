package net.jamesjennison.klippercompanion

import android.content.SharedPreferences

object PrinterPreferences {
    fun address(prefs: SharedPreferences): String = try {
        prefs.getString("address", "") ?: ""
    } catch (_: ClassCastException) { "" }

    fun printers(prefs: SharedPreferences): List<String> = try {
        (prefs.getStringSet("savedPrinters", emptySet()) ?: emptySet()).sorted()
    } catch (_: ClassCastException) { emptyList() }

    fun save(prefs: SharedPreferences, address: String, printers: List<String>) {
        prefs.edit().putString("address", address).putStringSet("savedPrinters", printers.toSet()).apply()
    }
}

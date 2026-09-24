package net.jamesjennison.klippercompanion

import android.app.Application
import android.content.Context

/** Installs the persistent Bambu certificate pin store for every entry point (activity, monitor service, widget). */
class NozzleApp : Application() {
    override fun onCreate() { super.onCreate(); installBambuPins(this) }
}

private class PrefsBambuPinStore(context: Context) : BambuPinStore {
    private val prefs = context.getSharedPreferences("bambu_cert_pins", Context.MODE_PRIVATE)
    override fun get(serial: String) = prefs.getString(serial.uppercase(), null)
    override fun put(serial: String, sha256: String) { prefs.edit().putString(serial.uppercase(), sha256).commit() }
    override fun forget(serial: String) { prefs.edit().remove(serial.uppercase()).commit() }
}

fun installBambuPins(context: Context) { BambuCertPins.store = PrefsBambuPinStore(context.applicationContext) }

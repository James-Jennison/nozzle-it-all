package net.jamesjennison.klippercompanion

import android.app.Application
import android.content.Context

/** Installs the persistent Bambu certificate pin store for every entry point (activity, monitor service, widget). */
class NozzleApp : Application() {
    // One authority per app process. Lazy: no environmental startup, provider, history or permission side effects.
    val environment by lazy {
        com.nozzleitall.environment.EnvironmentalCoordinator(java.io.File(filesDir, "environment/control.json"),
            com.nozzleitall.environment.EnvironmentalHistory(com.nozzleitall.environment.SqlStoreFactory {
                net.jamesjennison.klippercompanion.environment.AndroidEnvironmentalStore(java.io.File(filesDir, "environment/history.db"))
            }), { net.jamesjennison.klippercompanion.environment.AndroidBleTransport(applicationContext) },
            platformIdentity = "android/${android.os.Build.MODEL}/${android.os.Build.FINGERPRINT}")
    }
    override fun onCreate() { super.onCreate(); installBambuPins(this); UsbSerialDeviceManager.install(this) }
}

private class PrefsBambuPinStore(context: Context) : BambuPinStore {
    private val prefs = context.getSharedPreferences("bambu_cert_pins", Context.MODE_PRIVATE)
    override fun get(serial: String) = prefs.getString(serial.uppercase(), null)
    override fun put(serial: String, sha256: String) { prefs.edit().putString(serial.uppercase(), sha256).commit() }
    override fun forget(serial: String) { prefs.edit().remove(serial.uppercase()).commit() }
}

fun installBambuPins(context: Context) { BambuCertPins.store = PrefsBambuPinStore(context.applicationContext) }

package net.jamesjennison.klippercompanion

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object PrinterPreferences {
    fun address(prefs: SharedPreferences): String = try { prefs.getString("address", "") ?: "" } catch (_: ClassCastException) { "" }
    fun printers(prefs: SharedPreferences): List<String> = try { (prefs.getStringSet("savedPrinters", emptySet()) ?: emptySet()).sorted() } catch (_: ClassCastException) { emptyList() }
    fun profiles(prefs: SharedPreferences): List<PrinterProfile> = try {
        val list = JSONArray(prefs.getString("profilesV1", "[]"))
        (0 until list.length()).mapNotNull { i -> runCatching {
            val p = list.getJSONObject(i)
            PrinterProfile(Moonraker.parseAddress(p.getString("address")).toString(), p.optString("name").take(80), p.optBoolean("favorite"), p.optString("cameraId"))
        }.getOrNull() }.distinctBy { it.address }
    } catch (_: Exception) { emptyList() }
    fun save(prefs: SharedPreferences, address: String, printers: List<String>) {
        prefs.edit().putString("address", address).putStringSet("savedPrinters", printers.toSet()).apply()
    }
    fun saveProfiles(prefs: SharedPreferences, address: String, profiles: List<PrinterProfile>) {
        val json = JSONArray().apply { profiles.forEach { p -> put(JSONObject().put("address",p.address).put("name",p.name).put("favorite",p.favorite).put("cameraId",p.cameraId)) } }
        prefs.edit().putString("address",address).putStringSet("savedPrinters",profiles.map { it.address }.toSet()).putString("profilesV1",json.toString()).apply()
    }
}

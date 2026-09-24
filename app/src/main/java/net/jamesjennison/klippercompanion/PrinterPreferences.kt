package net.jamesjennison.klippercompanion

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object PrinterPreferences {
    fun address(prefs: SharedPreferences): String = try { prefs.getString("address", "") ?: "" } catch (_: ClassCastException) { "" }
    fun printers(prefs: SharedPreferences): List<String> = try { (prefs.getStringSet("savedPrinters", emptySet()) ?: emptySet()).sorted() } catch (_: ClassCastException) { emptyList() }
    // API keys are kept out of profilesV1 and read back from the caller's encrypted store
    // (see CredentialStore) so a credential granting full printer control never lands in plaintext.
    fun profiles(prefs: SharedPreferences, secrets: SharedPreferences): List<PrinterProfile> = try {
        val list = JSONArray(prefs.getString("profilesV1", "[]"))
        (0 until list.length()).mapNotNull { i -> runCatching {
            val p = list.getJSONObject(i)
            // Absent/unrecognized "kind" (older saved data, or a downgrade after a future
            // value was written) defaults to GENERIC_KLIPPER rather than failing the profile.
            val kind = runCatching { PrinterKind.valueOf(p.optString("kind")) }.getOrDefault(PrinterKind.GENERIC_KLIPPER)
            // Read before the address: a Bambu profile stores a bare host, which parseAddress rejects.
            val address = normalizedAddress(p.getString("address"), kind)
            // Broad catch is deliberate: a Keystore-key-invalidation failure (biometric/lock-screen
            // change, restore to a new device) must degrade to "no key" here, not propagate out to
            // the outer runCatching and silently drop this whole profile (address/name/favorite too).
            // For a BAMBU_LAB profile this same slot holds the access code - same class of secret
            // (a control-granting credential), so it gets the same encrypted storage.
            val apiKey = try { secrets.getString(address, "") ?: "" } catch (_: Exception) { "" }
            // Same absent/unrecognized-value tolerance as "kind": an older saved profile (or a
            // downgrade after a future value was written) has no slicingModel at all, not a bad one.
            val slicingModel = p.optString("slicingModel").takeIf { it.isNotBlank() }?.let { runCatching { SlicingPrinterModel.valueOf(it) }.getOrNull() }
            PrinterProfile(address, p.optString("name").take(80), p.optBoolean("favorite"), p.optString("cameraId"), apiKey, kind, p.optString("serial").take(40), slicingModel, p.optString("declaredFirmwareVersion").take(80))
        }.getOrNull() }.distinctBy { it.address }
    } catch (_: Exception) { emptyList() }
    fun save(prefs: SharedPreferences, address: String, printers: List<String>) {
        prefs.edit().putString("address", address).putStringSet("savedPrinters", printers.toSet()).apply()
    }
    fun saveProfiles(prefs: SharedPreferences, secrets: SharedPreferences, address: String, profiles: List<PrinterProfile>) {
        val json = JSONArray().apply { profiles.forEach { p -> put(JSONObject().put("address",p.address).put("name",p.name).put("favorite",p.favorite).put("cameraId",p.cameraId).put("kind",p.kind.name).put("serial",p.serial).put("slicingModel",p.slicingModel?.name ?: "").put("declaredFirmwareVersion",p.declaredFirmwareVersion)) } }
        prefs.edit().putString("address",address).putStringSet("savedPrinters",profiles.map { it.address }.toSet()).putString("profilesV1",json.toString()).apply()
        val keep = profiles.map { it.address }.toSet()
        secrets.edit().apply {
            // The store is shared: only stale printer keys go. MyMiniFactory (mmf.*) and Bespok3d (bespok3d:*) entries belong to other features.
            secrets.all.keys.filter { it !in keep && !it.startsWith("mmf.") && !it.startsWith("bespok3d:") }.forEach { remove(it) }
            profiles.forEach { p -> if (p.apiKey.isBlank()) remove(p.address) else putString(p.address, p.apiKey) }
        }.apply()
    }
}

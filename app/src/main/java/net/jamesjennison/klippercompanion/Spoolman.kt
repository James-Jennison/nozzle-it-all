package net.jamesjennison.klippercompanion

import org.json.JSONArray
import org.json.JSONObject

// Spoolman (moonraker-spoolman component + the separate Spoolman server it proxies to) filament
// inventory - read-only, per this app's own P13 scope ("read first, validated mutations later").
// Referenced against Helix's logic (its own code is TypeScript, nothing portable to Kotlin - same
// situation as PandaBreathControls), not ported: Original app/(tabs)/spoolman.tsx and
// services/moonraker.ts's spoolman* calls establish the exact Moonraker endpoints used below.
// Helix's own spoolman.tsx additionally supports creating/editing spools, filaments and vendors,
// a community filament catalog and an NFC/QR spool-label scanner; none of that is built here.
data class SpoolmanSpool(
    val id: Int, val remainingWeight: Double?, val totalWeight: Double?, val archived: Boolean,
    val filamentName: String?, val material: String?, val colorHex: String?, val vendorName: String?,
)
data class SpoolmanInventory(val available: Boolean, val activeSpoolId: Int?, val spools: List<SpoolmanSpool>)
interface SpoolmanReader : AutoCloseable {
    fun spoolmanInventory(): SpoolmanInventory
}
object Spoolman {
    fun displayName(spool: SpoolmanSpool): String =
        listOfNotNull(spool.vendorName, spool.filamentName).joinToString(" ").ifBlank { "Spool #${spool.id}" }
    fun parseSpools(response: Any?): List<SpoolmanSpool> {
        val array = response as? JSONArray ?: return emptyList()
        require(array.length() <= 2000) { "Spool list is too large." }
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map { spool ->
            val filament = spool.optJSONObject("filament")
            val vendor = filament?.optJSONObject("vendor")
            fun finite(obj: JSONObject?, field: String) = obj?.optDouble(field)?.takeIf { it.isFinite() }
            fun text(obj: JSONObject?, field: String) = obj?.optString(field)?.takeIf { it.isNotBlank() }
            SpoolmanSpool(spool.optInt("id"), finite(spool, "remaining_weight"), finite(filament, "weight"),
                spool.optBoolean("archived"), text(filament, "name"), text(filament, "material"),
                text(filament, "color_hex"), text(vendor, "name"))
        }
    }
}

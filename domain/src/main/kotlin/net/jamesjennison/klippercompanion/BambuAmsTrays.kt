// Adapted from Helix (github.com/FatBoy721/Helix), AGPL-3.0-or-later.
// Original: services/bambuReport.ts (bambuTrays, isBambuTrayLoaded, activeBambuTray).
package net.jamesjennison.klippercompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * A Bambu printer's AMS trays, read from its MQTT status report (`print.ams`), as filament slots. Pure; no I/O.
 *
 * Trays are numbered the way the printer addresses them: AMS unit n owns global trays 4n..4n+3. That is the lane the
 * print command's `ams_mapping` names (BambuPrintProtocol). A tray only holds filament when its bit in
 * `tray_exist_bits` is set; Bambu leaves stale type and colour in an unloaded tray. The external spool holder
 * (`vt_tray`, id 254) is listed last, as [EXTERNAL_TRAY].
 */
object BambuAmsTrays {
    const val TRAYS_PER_AMS = 4
    /** Nothing loaded (tray_now / tray_tar). */
    const val NO_TRAY = 255
    /** The external spool holder's tray id. */
    const val EXTERNAL_TRAY = 254

    /** The report's `print` object (or the whole report) to its AMS trays; empty when the printer has no AMS. */
    fun parse(report: JSONObject): List<FilamentSlot> {
        val print = report.optJSONObject("print") ?: report
        val ams = print.optJSONObject("ams")
        val loadedBits = ams?.optString("tray_exist_bits")?.toLongOrNull(16) ?: 0L
        val active = number(ams?.opt("tray_now"))?.takeIf { it != NO_TRAY }
        val slots = ArrayList<FilamentSlot>()
        val units = ams?.optJSONArray("ams") ?: JSONArray()
        for (position in 0 until minOf(units.length(), 16)) {
            val unit = units.optJSONObject(position) ?: continue
            // Trust the unit's own id over its position: a second AMS can report out of order.
            val unitId = number(unit.opt("id")) ?: position
            val trays = unit.optJSONArray("tray") ?: continue
            for (trayPosition in 0 until minOf(trays.length(), TRAYS_PER_AMS)) {
                val tray = trays.optJSONObject(trayPosition) ?: continue
                val index = unitId * TRAYS_PER_AMS + (number(tray.opt("id")) ?: trayPosition)
                val loaded = index in 0..62 && (loadedBits and (1L shl index)) != 0L
                slots += slot(tray, index, loaded, active == index, "AMS ${unitId + 1} · slot ${index % TRAYS_PER_AMS + 1}")
            }
        }
        // The external holder has no occupancy bit: it holds filament when it reports a type.
        print.optJSONObject("vt_tray")?.let { tray ->
            slots += slot(tray, EXTERNAL_TRAY, tray.optString("tray_type").isNotBlank(), active == EXTERNAL_TRAY, "External spool")
        }
        return slots
    }

    private fun slot(tray: JSONObject, index: Int, loaded: Boolean, active: Boolean, name: String) = FilamentSlot(
        tool = index,
        material = tray.optString("tray_type").trim().takeIf { loaded && it.isNotEmpty() },
        colorHex = if (loaded) FilamentLanes.normalizeColor(tray.optString("tray_color")) else null,
        vendor = tray.optString("tray_sub_brands").trim().takeIf { loaded && it.isNotEmpty() },
        nozzleTempC = number(tray.opt("nozzle_temp_max"))?.takeIf { loaded && it > 0 },
        active = active,
        name = name,
    )

    /** Bambu sends numbers as strings about as often as it sends them as numbers. */
    private fun number(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }
}

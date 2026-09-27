package com.nozzleitall.desktop.prepare

import org.json.JSONObject

/**
 * The colours each filament is sold in, from Snapmaker Orca's filament colour library (filaments_colours.json at the
 * engine pin): what Snapmaker's filament picker offers for a slot, and what Full Spectrum's recommended palette is drawn from.
 */
object FilamentColours {
    data class Swatch(val hex: String, val name: String)

    private val byFamily: Map<String, List<Swatch>> by lazy {
        val text = FilamentColours::class.java.getResourceAsStream("/fullspectrum/filaments_colours.json")?.readBytes()?.decodeToString() ?: return@lazy emptyMap()
        val a = JSONObject(text).optJSONArray("filaments") ?: return@lazy emptyMap()
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optBoolean("enabled", true) }.associate { f ->
            val colours = f.optJSONArray("filament_color")?.let { c -> (0 until c.length()).map { c.getJSONObject(it) } }.orEmpty()
                .filter { it.optBoolean("enabled", true) && it.optInt("mode", 0) == 0 }
                .mapNotNull { c -> c.optJSONArray("filament_color")?.optString(0)?.takeIf { it.startsWith("#") }?.let { Swatch(it.uppercase(), c.optJSONObject("color_name")?.optString("en").orEmpty()) } }
            f.optString("filament_name").trim().lowercase() to colours
        }
    }

    /** Catalogue colours for a filament profile (matched by Snapmaker's family name, e.g. "Snapmaker PLA Basic @U1"). */
    fun forFamily(family: String): List<Swatch> = byFamily[family.trim().lowercase()].orEmpty()
}

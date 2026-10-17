package com.nozzleitall.desktop.prepare

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The filament profiles a printer profile can slice with, one per material slot, as Orca-family slicers pick a filament
 * preset per slot. Bundled flattened from the printer's own slicer (scripts/bundle_filament_library.py; the Snapmaker U1's
 * come from Snapmaker Orca at the engine pin). A slot with no filament profile uses the printer profile's own filament.
 */
object FilamentLibrary {
    data class Entry(val id: String, val name: String, val vendor: String, val type: String, val family: String) {
        /** The name people know it by: Snapmaker's " @U1 0.4 nozzle" style suffixes dropped. */
        val displayName get() = name.substringBefore(" @").trim()
    }

    private val cache = HashMap<String, List<Entry>>()

    /**
     * Filament profiles for [profileId]: "<profile>@<machine>" reads that nozzle size's filaments from the printer's
     * profile family (PrinterLibrary); a plain profile id reads its bundled filament list (empty when none is).
     */
    fun forProfile(profileId: String): List<Entry> = synchronized(cache) {
        cache.getOrPut(profileId) {
            libraryMachine(profileId)?.let { (lib, m) -> return@getOrPut lib.filamentsFor(m) }
            val text = FilamentLibrary::class.java.getResourceAsStream("/filaments/$profileId/index.json")?.readBytes()?.decodeToString() ?: return@getOrPut emptyList()
            val a = JSONObject(text).getJSONArray("filaments")
            (0 until a.length()).map { a.getJSONObject(it) }.map { Entry(it.getString("id"), it.getString("name"), it.optString("vendor"), it.optString("type"), it.optString("family")) }
        }
    }

    fun profileJson(profileId: String, filamentId: String): JSONObject? =
        libraryMachine(profileId)?.first?.json("filament", filamentId)
            ?: FilamentLibrary::class.java.getResourceAsStream("/filaments/$profileId/$filamentId.json")?.readBytes()?.decodeToString()?.let { JSONObject(it) }

    private fun libraryMachine(key: String): Pair<PrinterLibrary, PrinterLibrary.Machine>? {
        val parts = key.split('@')
        val lib = PrinterLibrary.of(parts[0]) ?: return null
        // A plain printer id means its family's default machine (the 0.4 mm nozzle).
        return if (parts.size < 2) lib to lib.machineFor(null) else lib.machines.firstOrNull { it.id == parts[1] }?.let { lib to it }
    }

    /** The filament key for a slice's profile folder ("<profile>@<machine>@<process>" → "<profile>@<machine>"). */
    fun keyForDir(dirName: String): String = dirName.split('@').take(2).joinToString("@")

    /**
     * The library entry for a loaded filament a printer reports (vendor and type, e.g. Snapmaker PLA), preferring the
     * vendor's own basic profile, then the generic one for that type.
     */
    fun bestFor(profileId: String, vendor: String?, type: String?, subType: String? = null): Entry? {
        val all = forProfile(profileId).filter { type != null && it.type.equals(type, true) }
        fun named(prefix: String) = all.filter { it.displayName.startsWith(prefix, true) }
        val v = vendor?.trim().orEmpty()
        val wanted = listOfNotNull(subType?.let { "$v $type $it" }, "$v $type Basic", "$v $type")
        return wanted.firstNotNullOfOrNull { w -> named(w).minByOrNull { it.displayName.length } }
            ?: all.firstOrNull { it.vendor.equals("Generic", true) && it.displayName.equals("Generic $type", true) }
    }

    /**
     * One filament config for [perSlot] filaments: every per-filament (list) setting takes one value per slot, the slot's own
     * profile's first value, else [base] (the printer profile's own filament), else the setting's engine default; everything
     * that isn't per filament comes from [base]. OrcaSlicer 2.5.0 lays a multi-filament config out this way (one entry per
     * filament, widened to its extruder variants by the engine's normalize_filament_values_to_variants); a profile's second
     * and later values (Snapmaker's high-flow nozzle, or another extruder variant) are not carried into a per-slot slice yet.
     */
    fun combine(base: JSONObject, perSlot: List<JSONObject?>): JSONObject {
        val out = JSONObject(base.toString())
        val sources = perSlot.map { it ?: base }
        val keys = LinkedHashSet<String>().apply { addAll(base.keySet()); perSlot.filterNotNull().forEach { addAll(it.keySet()) } }
        for (k in keys) {
            val listy = (base.opt(k) is JSONArray) || perSlot.any { it?.opt(k) is JSONArray }
            // Snapmaker's flow-variant bookkeeping has no meaning on 2.5.0 (its engine reads extruder variants instead).
            if (k == "filament_flow_step_size" || k == "filament_flow_support") { out.remove(k); continue }
            if (!listy) continue
            fun values(src: JSONObject): JSONArray? = (src.opt(k) as? JSONArray)?.takeIf { it.length() > 0 } ?: (base.opt(k) as? JSONArray)?.takeIf { it.length() > 0 }
            val merged = sources.map { values(it)?.opt(0) ?: engineDefault(k) }
            if (merged.any { it == null }) out.remove(k) else out.put(k, JSONArray(merged))
        }
        out.put("name", "Nozzle per-slot filaments")
        return out
    }

    /** The engine's default for per-filament setting [key] (its first entry), from the bundled settings schema. */
    private fun engineDefault(key: String): Any? {
        val d = com.nozzleitall.desktop.settings.SettingsCatalog.bundled.byKey[key] ?: return null
        val text = d.default ?: return null
        return if (d.type == "strings") com.nozzleitall.desktop.settings.SettingDef.parseStrings(text).firstOrNull() ?: ""
        else text.split(',').firstOrNull()?.trim()
    }

    /** The per-slot filament file for a slice in [dir], or null when no slot has its own filament profile. */
    fun writeCombined(profileId: String, profileDir: File, slots: List<com.nozzleitall.project.ProjectManifest.MaterialSlot>, dir: File): File? {
        if (slots.none { it.filamentProfile != null }) return null
        val base = JSONObject(File(profileDir, "filament.json").readText())
        return File(dir, "filaments.json").apply { writeText(combine(base, slots.map { s -> s.filamentProfile?.let { profileJson(profileId, it) } }).toString(1)) }
    }
}

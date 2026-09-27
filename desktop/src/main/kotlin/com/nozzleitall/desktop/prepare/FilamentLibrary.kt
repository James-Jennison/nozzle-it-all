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
     * The flow-variant settings: a filament holds one value per nozzle flow type it declares (filament_flow_support),
     * and the combined config lays them out filament by filament (Snapmaker Orca's filament_flow_variant_options()).
     */
    val FLOW_VARIANT_KEYS = setOf("filament_flow_ratio", "enable_pressure_advance", "pressure_advance", "nozzle_temperature_initial_layer",
        "nozzle_temperature", "filament_max_volumetric_speed", "fan_min_speed", "fan_max_speed", "additional_cooling_fan_speed",
        "filament_retraction_length", "filament_retraction_speed", "filament_deretraction_speed", "filament_z_hop_types",
        "filament_wipe_distance", "filament_retract_length_toolchange", "filament_multitool_ramming", "filament_multitool_ramming_volume",
        "filament_multitool_ramming_flow", "filament_minimal_purge_on_wipe_tower")

    /**
     * One filament config for [perSlot] filaments, composed the way Snapmaker Orca's PresetBundle::full_fff_config()
     * composes one filament preset per slot: an ordinary per-filament (list) setting takes each slot's first value; a
     * flow-variant setting (and filament_flow_support) takes each slot's segment of as many values as that slot declares
     * flow types, the first repeated where the profile has fewer; filament_flow_step_size records the segment lengths.
     * A slot's values come from its own profile ([perSlot] entry), else [base] (the printer profile's own filament), else
     * the setting's engine default; everything that isn't per filament comes from [base].
     */
    fun combine(base: JSONObject, perSlot: List<JSONObject?>): JSONObject {
        val out = JSONObject(base.toString())
        val sources = perSlot.map { it ?: base }
        val steps = sources.map { (it.opt("filament_flow_support") as? JSONArray)?.length()?.takeIf { n -> n > 0 } ?: 1 }
        val keys = LinkedHashSet<String>().apply { addAll(base.keySet()); perSlot.filterNotNull().forEach { addAll(it.keySet()) } }
        for (k in keys) {
            val listy = (base.opt(k) is JSONArray) || perSlot.any { it?.opt(k) is JSONArray }
            if (!listy || k == "filament_flow_step_size") continue
            fun values(src: JSONObject): JSONArray? = (src.opt(k) as? JSONArray)?.takeIf { it.length() > 0 } ?: (base.opt(k) as? JSONArray)?.takeIf { it.length() > 0 }
            val segmented = k in FLOW_VARIANT_KEYS || k == "filament_flow_support"
            val merged = ArrayList<Any?>()
            sources.forEachIndexed { i, src ->
                val v = values(src)
                val n = if (segmented) steps[i] else 1
                for (j in 0 until n) merged += v?.let { if (j < it.length()) it.opt(j) else it.opt(0) } ?: engineDefault(k)
            }
            if (merged.any { it == null }) {
                // filament_flow_support has no default: a slot that declares none gets "standard", as a preset without it.
                if (k == "filament_flow_support") out.put(k, JSONArray(merged.map { it ?: "standard" })) else out.remove(k)
            } else out.put(k, JSONArray(merged))
        }
        out.put("filament_flow_step_size", JSONArray(steps.map { it.toString() }))
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

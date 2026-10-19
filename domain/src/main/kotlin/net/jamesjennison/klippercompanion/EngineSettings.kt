package net.jamesjennison.klippercompanion

import org.json.JSONObject

/**
 * Every setting the slicing engine accepts that a person can enter as one value, beyond the curated [SettingsCatalog]
 * entries: process, filament and printer settings. Built from the engine's own metadata
 * (schemas/slicing/settings-schema.json, exported by `nozzle-engine --schema`) and grouped by
 * schemas/slicing/settings-groups.json, the same two files Desktop's settings sheet reads, so Android offers what Desktop
 * does. A filament or printer setting is a list with one entry per filament or extruder; one value is entered and
 * [expand] repeats it for each. Code, notes, points and credentials are not here.
 */
object EngineSettings {
    const val PROCESS = "process"; const val FILAMENT = "filament"; const val PRINTER = "printer"
    val scopes = listOf(PROCESS, FILAMENT, PRINTER)

    // The printer's identity and technology are the profile's business, not a per-print override.
    private val identity = setOf("printer_technology", "printer_model", "printer_variant", "printer_settings_id", "print_settings_id", "filament_settings_id",
        "default_print_profile", "default_filament_profile", "printable_area", "bed_exclude_area", "thumbnails", "thumbnails_format")

    private fun resource(name: String) = JSONObject(EngineSettings::class.java.getResourceAsStream("/settings/$name")!!.bufferedReader().readText())

    val all: List<SettingDef> by lazy { load(resource("settings-schema.json"), resource("settings-groups.json")) }
    private val byKey by lazy { all.associateBy { it.key } }
    fun get(key: String): SettingDef? = byKey[key]

    /** [tier] and below in [scope], optionally narrowed by a free-text query, as [SettingsCatalog.visible] does for the curated list. */
    fun visible(tier: SettingTier, query: String = "", scope: String = PROCESS): List<SettingDef> {
        val q = query.trim().lowercase()
        return all.filter { it.scope == scope && it.tier.ordinal <= tier.ordinal && (q.isEmpty() || listOf(it.label, it.key, it.group, it.help).any { s -> s.lowercase().contains(q) }) }
    }

    /** The engine's own default for each listed setting, as the config string it takes (what a profile that leaves it alone gets). */
    val defaults: Map<String, String> by lazy {
        val options = resource("settings-schema.json").getJSONArray("options")
        (0 until options.length()).map { options.getJSONObject(it) }.filter { it.has("default") && it.getString("key") in byKey }
            .associate { it.getString("key") to it.getString("default") }
    }

    /**
     * [overrides] ready for the engine: a per-filament setting's one value repeated for each of [filaments], a per-extruder one for
     * each of [extruders] (a single-tool print needs no repeating, and a list setting the engine reads with fewer entries than tools
     * would apply to the first tool only).
     */
    fun expand(overrides: Map<String, String>, filaments: Int, extruders: Int): Map<String, String> = overrides.mapValues { (k, v) ->
        val d = get(k)?.takeIf { it.vector } ?: return@mapValues v
        val n = if (d.scope == FILAMENT) filaments else extruders
        if (n > 1) List(n) { v }.joinToString(",") else v
    }

    internal fun load(schema: JSONObject, layout: JSONObject): List<SettingDef> {
        val hidden = layout.getJSONArray("hidden").let { a -> (0 until a.length()).map { Regex(a.getString(it)) } }
        val rulesAll = layout.getJSONArray("groups").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        fun rules(scope: String) = rulesAll.filter { it.getString("scope") == scope }
            .map { r -> r.getString("title") to r.getJSONArray("keys").let { k -> (0 until k.length()).map { Regex(k.getString(it)) } } }
        val rulesByScope = scopes.associateWith(::rules)
        val curated = SettingsCatalog.all.map { it.key }.toSet()
        val options = schema.getJSONArray("options")
        return (0 until options.length()).map { options.getJSONObject(it) }.asSequence()
            .filter { it.getString("scope") in scopes && it.optString("mode") != "develop" && it.getString("key") !in curated && it.getString("key") !in identity &&
                hidden.none { h -> h.matches(it.getString("key")) } }
            .distinctBy { it.getString("key") }
            .mapNotNull { o ->
                val key = o.getString("key"); val scope = o.getString("scope")
                val (type, vector) = typeOf(o) ?: return@mapNotNull null
                val group = rulesByScope.getValue(scope).firstOrNull { (_, keys) -> keys.any { it.matches(key) } }?.first ?: o.optString("category").ifBlank { "Other" }
                val label = o.optString("label").ifBlank { key.replace('_', ' ').replaceFirstChar { it.uppercase() } }
                // The curated list is the Basic tier; Orca's own "simple" options start the Advanced tier here, the rest are Expert.
                val tier = if (o.optString("mode") == "simple") SettingTier.ADVANCED else SettingTier.EXPERT
                SettingDef(key, label, group, tier, type, o.optString("tooltip").ifBlank { label }, scope = scope, vector = vector)
            }.toList()
    }

    private fun typeOf(o: JSONObject): Pair<SettingType, Boolean>? {
        val unit = o.optString("units")
        fun num(name: String, fallback: Double) = if (o.has(name)) o.getDouble(name) else fallback
        fun choices() = o.optJSONArray("choices")?.let { c -> (0 until c.length()).map { c.getJSONObject(it).let { x -> x.getString("label") to x.getString("value") } } }?.takeIf { it.size >= 2 }
        val text = !(o.optBoolean("multiline") || o.optBoolean("code") || o.optBoolean("readonly"))
        val type = o.getString("type")
        val t: SettingType = when (type) {
            "bool", "bools" -> SettingType.Toggle
            "int", "ints" -> SettingType.IntRange(num("min", 0.0).coerceIn(-1e9, 1e9).toInt(), num("max", 1e9).coerceIn(-1e9, 1e9).toInt(), unit)
            "float", "floats" -> SettingType.Decimal(num("min", -1e9).coerceAtLeast(-1e9), num("max", 1e9).coerceAtMost(1e9), unit)
            "percent", "percents" -> SettingType.Percent(num("min", 0.0).coerceIn(-1e9, 1e9).toInt(), num("max", 100.0).coerceIn(-1e9, 1e9).toInt())
            "float_or_percent", "floats_or_percents" -> SettingType.FloatOrPercent(unit)
            "string", "strings" -> if (text) SettingType.Text else return null
            "enum", "enums" -> SettingType.Choice(choices() ?: return null)
            else -> return null
        }
        return t to isVector(type)
    }

    private fun isVector(type: String) = type in setOf("bools", "ints", "floats", "percents", "strings", "enums", "floats_or_percents")
}

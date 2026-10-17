package net.jamesjennison.klippercompanion

import org.json.JSONObject

/**
 * Every process setting the slicing engine accepts that a person can enter as one value, beyond the curated
 * [SettingsCatalog] entries. Built from the engine's own metadata (schemas/slicing/settings-schema.json, exported by
 * `nozzle-engine --schema`) and grouped by schemas/slicing/settings-groups.json, the same two files Desktop's settings
 * sheet reads, so Android offers what Desktop does. Per-filament and per-extruder lists are not here: they have no
 * single value to override.
 */
object EngineSettings {
    private fun resource(name: String) = JSONObject(EngineSettings::class.java.getResourceAsStream("/settings/$name")!!.bufferedReader().readText())

    val all: List<SettingDef> by lazy { load(resource("settings-schema.json"), resource("settings-groups.json")) }
    private val byKey by lazy { all.associateBy { it.key } }
    /** The engine's own default for each listed setting, as the config string it takes (what a profile that leaves it alone gets). */
    val defaults: Map<String, String> by lazy {
        val options = resource("settings-schema.json").getJSONArray("options")
        (0 until options.length()).map { options.getJSONObject(it) }.filter { it.getString("scope") == "process" && it.has("default") && it.getString("key") in byKey }
            .associate { it.getString("key") to it.getString("default") }
    }
    fun get(key: String): SettingDef? = byKey[key]

    /** [tier] and below, optionally narrowed by a free-text query, as [SettingsCatalog.visible] does for the curated list. */
    fun visible(tier: SettingTier, query: String = ""): List<SettingDef> {
        val q = query.trim().lowercase()
        return all.filter { it.tier.ordinal <= tier.ordinal && (q.isEmpty() || listOf(it.label, it.key, it.group, it.help).any { s -> s.lowercase().contains(q) }) }
    }

    internal fun load(schema: JSONObject, layout: JSONObject): List<SettingDef> {
        val hidden = layout.getJSONArray("hidden").let { a -> (0 until a.length()).map { Regex(a.getString(it)) } }
        val rules = layout.getJSONArray("groups").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.filter { it.getString("scope") == "process" }
            .map { r -> r.getString("title") to r.getJSONArray("keys").let { k -> (0 until k.length()).map { Regex(k.getString(it)) } } }
        val curated = SettingsCatalog.all.map { it.key }.toSet()
        val options = schema.getJSONArray("options")
        return (0 until options.length()).map { options.getJSONObject(it) }.asSequence()
            .filter { it.getString("scope") == "process" && it.optString("mode") != "develop" && it.getString("key") !in curated && hidden.none { h -> h.matches(it.getString("key")) } }
            .distinctBy { it.getString("key") }
            .mapNotNull { o ->
                val key = o.getString("key")
                val type = typeOf(o) ?: return@mapNotNull null
                val group = rules.firstOrNull { (_, keys) -> keys.any { it.matches(key) } }?.first ?: o.optString("category").ifBlank { "Other" }
                val label = o.optString("label").ifBlank { key.replace('_', ' ').replaceFirstChar { it.uppercase() } }
                // The curated list is the Basic tier; Orca's own "simple" options start the Advanced tier here, the rest are Expert.
                val tier = if (o.optString("mode") == "simple") SettingTier.ADVANCED else SettingTier.EXPERT
                SettingDef(key, label, group, tier, type, o.optString("tooltip").ifBlank { label })
            }.toList()
    }

    private fun typeOf(o: JSONObject): SettingType? {
        val unit = o.optString("units")
        fun num(name: String, fallback: Double) = if (o.has(name)) o.getDouble(name) else fallback
        return when (o.getString("type")) {
            "bool" -> SettingType.Toggle
            "int" -> SettingType.IntRange(num("min", 0.0).coerceIn(-1e9, 1e9).toInt(), num("max", 1e9).coerceIn(-1e9, 1e9).toInt(), unit)
            "float" -> SettingType.Decimal(num("min", -1e9).coerceAtLeast(-1e9), num("max", 1e9).coerceAtMost(1e9), unit)
            "percent" -> SettingType.Percent(num("min", 0.0).coerceIn(-1e9, 1e9).toInt(), num("max", 100.0).coerceIn(-1e9, 1e9).toInt())
            "float_or_percent" -> SettingType.FloatOrPercent(unit)
            "string" -> if (o.optBoolean("multiline") || o.optBoolean("code") || o.optBoolean("readonly")) null else SettingType.Text
            "enum" -> o.optJSONArray("choices")?.let { c -> (0 until c.length()).map { c.getJSONObject(it).let { x -> x.getString("label") to x.getString("value") } } }
                ?.takeIf { it.size >= 2 }?.let { SettingType.Choice(it) }
            else -> null
        }
    }
}

package com.nozzleitall.desktop.settings

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Every setting the slicing engine accepts, from schemas/slicing/settings-schema.json (exported by `nozzle-engine --schema`,
 * so it always matches the engine), organised by Nozzle's own groups (settings-groups.json). Screens are generated from
 * this; nothing here copies another slicer's layout.
 */
enum class Scope(val id: String, val label: String) { PROCESS("process", "Print"), FILAMENT("filament", "Material"), PRINTER("printer", "Printer") }

/** How much detail a person wants to see; each level includes the ones before it. */
enum class Detail(val label: String, val modes: Set<String>) {
    ESSENTIAL("Essential", setOf("simple")), MORE("More", setOf("simple", "advanced")), EVERYTHING("Everything", setOf("simple", "advanced", "expert"))
}

data class Choice(val value: String, val label: String)

data class SettingDef(
    val key: String, val scope: Scope, val type: String, val label: String, val help: String?, val units: String?,
    val min: Double?, val max: Double?, val mode: String, val choices: List<Choice>, val openChoices: Boolean,
    val default: String?, val multiline: Boolean, val code: Boolean, val readonly: Boolean,
) {
    /** Vector options hold one value per extruder or filament slot; numbers are edited as a comma-separated list. */
    val isList get() = type in LIST_TYPES
    val baseType get() = when (type) { "floats" -> "float"; "ints" -> "int"; "bools" -> "bool"; "strings" -> "string"; "percents" -> "percent"; "enums" -> "enum"; "floats_or_percents" -> "float_or_percent"; else -> type }

    /** What a person sees and edits, from the engine's serialized value. Text lists show their first entry. */
    fun display(serialized: String): String = when (type) {
        "strings" -> parseStrings(serialized).firstOrNull().orEmpty()
        else -> if (isList) serialized.split(',').joinToString(", ") { it.trim() } else serialized
    }

    /** The engine's serialized form of what a person typed (the form libslic3r's deserialize accepts). */
    fun serialize(text: String): String = when (type) {
        "strings" -> quote(text)
        else -> if (isList) text.split(',').joinToString(",") { it.trim() } else text.trim()
    }

    /** Null when [text] (as typed) is acceptable, otherwise a short reason. Lists are checked item by item. */
    fun problem(text: String): String? {
        if (baseType == "string" || baseType.startsWith("point")) return null
        val items = if (isList) text.split(',').map { it.trim() } else listOf(text.trim())
        for (v in items) {
            when (baseType) {
                "bool" -> if (v !in setOf("0", "1")) return "Choose on or off."
                "int", "float", "percent", "float_or_percent" -> {
                    val number = v.removeSuffix("%").toDoubleOrNull() ?: return "Enter a number${units?.let { " ($it)" } ?: ""}."
                    if (baseType == "int" && number % 1.0 != 0.0) return "Enter a whole number."
                    if (baseType == "percent" || !v.endsWith("%")) {
                        min?.let { if (number < it) return "At least ${fmt(it)}." }
                        max?.let { if (number > it) return "At most ${fmt(it)}." }
                    }
                }
                "enum" -> if (!openChoices && choices.none { it.value == v }) return "Choose one of the listed options."
            }
        }
        return null
    }

    companion object {
        val LIST_TYPES = setOf("floats", "ints", "bools", "strings", "percents", "enums", "floats_or_percents", "points")
        fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

        /** libslic3r's C-style quoting for one string in a string list. */
        fun quote(text: String) = buildString {
            append('"')
            for (ch in text) when (ch) { '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); else -> append(ch) }
            append('"')
        }

        /** Parses a serialized string list ("a";"b" or a bare single value) into its entries. */
        fun parseStrings(serialized: String): List<String> {
            val s = serialized.trim()
            if (!s.startsWith('"')) return if (s.isEmpty()) emptyList() else s.split(';')
            val out = mutableListOf<String>(); var i = 0
            while (i < s.length) {
                if (s[i] != '"') { i++; continue }
                val b = StringBuilder(); i++
                while (i < s.length && s[i] != '"') {
                    if (s[i] == '\\' && i + 1 < s.length) { i++; b.append(when (s[i]) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> s[i] }) } else b.append(s[i])
                    i++
                }
                out += b.toString(); i++
            }
            return out
        }
    }
}

data class SettingGroup(val id: String, val scope: Scope, val title: String, val summary: String, val settings: List<SettingDef>)
/** A tab of groups for one scope (for example Print → Quality). */
data class SettingTab(val id: String, val scope: Scope, val title: String, val groups: List<SettingGroup>)

class SettingsCatalog(val all: List<SettingDef>, val groups: List<SettingGroup>, val engineCommit: String?, val tabs: Map<Scope, List<SettingTab>> = emptyMap()) {
    val byKey = all.associateBy { it.key }

    /** Settings matching every word of [query] in their label, help, key, group or choices ("gyroid" finds the infill pattern). */
    fun search(query: String): List<Pair<SettingGroup, SettingDef>> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        // Best first: the label is the query, then every word is in the label, then anywhere in the help, key or group.
        return groups.flatMap { g -> g.settings.map { g to it } }.mapNotNull { (g, s) ->
            val label = s.label.lowercase()
            val hay = "$label ${s.help.orEmpty()} ${s.key.replace('_', ' ')} ${g.title} ${s.choices.joinToString(" ") { it.label }}".lowercase()
            if (!words.all { it in hay }) return@mapNotNull null
            val rank = when { label == words.joinToString(" ") -> 0; words.all { it in label } -> 1; else -> 2 }
            Triple(rank, g, s)
        }.sortedBy { it.first }.map { it.second to it.third }
    }

    companion object {
        fun load(schema: JSONObject, layout: JSONObject): SettingsCatalog {
            val options = schema.getJSONArray("options")
            val defs = (0 until options.length()).map { options.getJSONObject(it) }.mapNotNull { o ->
                val scope = Scope.entries.firstOrNull { it.id == o.getString("scope") } ?: return@mapNotNull null
                val choices = o.optJSONArray("choices")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { c -> Choice(c.getString("value"), c.getString("label")) } } } ?: emptyList()
                SettingDef(o.getString("key"), scope, o.getString("type"), o.optString("label").ifBlank { o.getString("key").replace('_', ' ').replaceFirstChar { it.uppercase() } },
                    o.optString("tooltip").ifBlank { null }, o.optString("units").ifBlank { null },
                    if (o.has("min")) o.getDouble("min") else null, if (o.has("max")) o.getDouble("max") else null,
                    o.optString("mode", "advanced"), choices, o.optBoolean("openChoices"), if (o.has("default")) o.getString("default") else null,
                    o.optBoolean("multiline"), o.optBoolean("code"), o.optBoolean("readonly"))
            }.distinctBy { it.scope to it.key } // the engine lists a few printer keys twice
            val hidden = layout.getJSONArray("hidden").strings().map { Regex(it) }
            val rules = layout.getJSONArray("groups").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            val placed = LinkedHashMap<String, MutableList<SettingDef>>()
            for (d in defs) {
                if (d.mode == "develop" || hidden.any { it.matches(d.key) }) continue
                val rule = rules.firstOrNull { r -> r.getString("scope") == d.scope.id && r.getJSONArray("keys").strings().any { Regex(it).matches(d.key) } } ?: continue
                placed.getOrPut(rule.getString("id")) { mutableListOf() } += d
            }
            val groups = rules.mapNotNull { r ->
                val list = placed[r.getString("id")] ?: return@mapNotNull null
                // Essential settings first, then by the engine's own order.
                SettingGroup(r.getString("id"), Scope.entries.first { it.id == r.getString("scope") }, r.getString("title"), r.getString("summary"),
                    list.sortedBy { listOf("simple", "advanced", "expert").indexOf(it.mode).let { i -> if (i < 0) 9 else i } })
            }
            val byId = groups.associateBy { it.id }
            val tabs = layout.optJSONObject("tabs")?.let { t ->
                Scope.entries.associateWith { sc -> t.optJSONArray(sc.id)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                    SettingTab(o.getString("id"), sc, o.getString("title"), o.getJSONArray("groups").strings().mapNotNull(byId::get))
                } } ?: emptyList() }
            } ?: emptyMap()
            return SettingsCatalog(defs, groups, schema.optJSONObject("source")?.optString("commit"), tabs)
        }

        /** The catalog shipped with the app (resources/settings). */
        val bundled: SettingsCatalog by lazy {
            fun res(name: String) = JSONObject(SettingsCatalog::class.java.getResourceAsStream("/settings/$name")!!.bufferedReader().readText())
            load(res("settings-schema.json"), res("settings-groups.json"))
        }

        private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    }
}

/**
 * The values a printer profile gives each setting (its machine, process and filament files), so screens can show what a
 * setting is before it's changed and what "reset" returns to. Orca stores list settings as JSON arrays; they're shown as
 * comma-separated lists.
 */
class ProfileValues(private val values: Map<String, String>) {
    /** The engine's serialized value for [key] in this profile, or null if the profile doesn't set it. */
    operator fun get(key: String): String? = values[key]

    companion object {
        fun read(profileDir: File): ProfileValues {
            val out = HashMap<String, String>()
            for (name in listOf("machine.json", "filament.json", "process.json")) {
                val f = File(profileDir, name); if (!f.isFile) continue
                val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: continue
                // Arrays become the engine's list syntax: quoted and ;-separated when any entry needs it (text), else commas.
                for (k in o.keys()) out[k] = when (val v = o.get(k)) {
                    is JSONArray -> (0 until v.length()).map { v.get(it).toString() }.let { items ->
                        if (items.any { it.any { ch -> ch in ",;\"\n" } || it.isEmpty() }) items.joinToString(";") { SettingDef.quote(it) } else items.joinToString(",")
                    }
                    else -> v.toString()
                }
            }
            return ProfileValues(out)
        }
    }
}

package com.nozzleitall.project

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Nozzle project manifest, schema `nozzle.project` 1.x (schemas/project-manifest/project-manifest-1.schema.json).
 *
 * It carries only what 3MF itself cannot: Nozzle's project identity and revision, the target printer, material and
 * toolhead assignments, the settings the user chose, attribution, and namespaced platform extensions. Geometry,
 * transforms and plate membership stay in canonical 3MF.
 *
 * Forward compatibility: every JSON object keeps the fields this version doesn't know in [unknown] and writes them back
 * unchanged, so a newer app's data survives a round trip through an older one. A different major version is refused.
 */
data class ProjectManifest(
    val projectId: String,
    val revision: Long,
    val name: String,
    val createdBy: Producer,
    val modifiedBy: Producer,
    val modifiedAtMillis: Long,
    val printer: PrinterTarget? = null,
    val plates: List<PlateEntry> = emptyList(),
    val materials: List<MaterialSlot> = emptyList(),
    val settings: SettingsChoice = SettingsChoice(),
    val attribution: List<Attribution> = emptyList(),
    /** Platform-specific data, one namespace per writer (for example "android", "desktop", "web"). Opaque to others. */
    val extensions: JSONObject = JSONObject(),
    val unknown: JSONObject = JSONObject(),
    val formatMinor: Int = MINOR,
) {
    data class Producer(val app: String, val platform: String, val version: String)
    /**
     * The printer a project is prepared for. [profileId] names the slicing profile (portable across platforms and
     * independent of any connection); [family] the printer family id ("paxx-u1", "prusa", ...); [printerId] a local saved
     * printer on the device that wrote it (meaningless elsewhere, kept for that device). Never credentials or addresses.
     */
    data class PrinterTarget(val model: String, val firmware: String? = null, val printerId: String? = null, val nozzleDiameters: List<Double> = emptyList(),
                             val unknown: JSONObject = JSONObject(), val profileId: String? = null, val family: String? = null)
    data class PlateEntry(val index: Int, val name: String = "", val objects: List<ObjectEntry> = emptyList(), val unknown: JSONObject = JSONObject())
    /**
     * [objectId] is the 3MF `<object id>` of a build item on this plate. [materialSlot] is 1-based, like Orca's filament index.
     * [paintSlots] maps the object's painted colours to slots: entry N-1 is the slot that the file's filament N prints with.
     * [settings] are the object's own print settings (engine keys to serialized values), as Orca's object list sets them.
     */
    data class ObjectEntry(val objectId: Int, val name: String = "", val materialSlot: Int? = null, val unknown: JSONObject = JSONObject(), val paintSlots: List<Int> = emptyList(),
                           val settings: Map<String, String> = emptyMap())
    /**
     * [slot] is 1-based. [toolhead] is the 0-based physical toolhead it is loaded in, when known. [filamentProfile] names
     * the filament profile this slot slices with (an id in the printer's filament library), or null for the printer
     * profile's own filament.
     */
    data class MaterialSlot(val slot: Int, val type: String? = null, val vendor: String? = null, val subType: String? = null, val colorHex: String? = null, val toolhead: Int? = null,
                            val unknown: JSONObject = JSONObject(), val filamentProfile: String? = null)
    /** [preset] names a guided choice ("standard", "fine", "draft", ...); [overrides] are explicit engine setting keys. */
    data class SettingsChoice(val preset: String? = null, val overrides: Map<String, String> = emptyMap(), val unknown: JSONObject = JSONObject())
    data class Attribution(val title: String, val creator: String? = null, val source: String? = null, val license: String? = null, val unknown: JSONObject = JSONObject())

    companion object {
        const val SCHEMA = "nozzle.project"
        const val MAJOR = 1
        const val MINOR = 0
        /** Where the manifest lives inside the 3MF. Auxiliaries/ is preserved by Orca-derived writers on save. */
        const val ARCHIVE_PATH = "Auxiliaries/Nozzle/project.json"
        /** Model-level 3MF metadata keys (also preserved by Orca-derived writers), used to recognise and recover. */
        const val META_PROJECT_ID = "nozzle:ProjectId"
        const val META_REVISION = "nozzle:Revision"
        const val META_MANIFEST_SHA256 = "nozzle:ManifestSha256"

        private fun JSONObject.rest(vararg known: String): JSONObject {
            val out = JSONObject()
            keySet().filter { it !in known }.forEach { out.put(it, get(it)) }
            return out
        }
        private fun JSONObject.strOrNull(k: String) = if (has(k) && !isNull(k)) optString(k) else null
        private fun JSONObject.intOrNull(k: String) = if (has(k) && !isNull(k)) optInt(k) else null
        private fun merge(base: JSONObject, unknown: JSONObject): JSONObject { unknown.keySet().forEach { if (!base.has(it)) base.put(it, unknown.get(it)) }; return base }

        fun parse(text: String): ProjectManifest = parse(JSONObject(text))

        fun parse(o: JSONObject): ProjectManifest {
            if (o.optString("schema") != SCHEMA) throw ProjectFormatException("This is not a Nozzle It All project manifest.")
            val version = o.optJSONArray("version") ?: throw ProjectFormatException("The project manifest has no version.")
            val major = version.optInt(0, -1)
            if (major != MAJOR) throw IncompatibleProjectException(major, "This project was saved by a newer Nozzle It All (format $major). Update Nozzle It All to open it.")
            fun producer(p: JSONObject?) = Producer(p?.optString("app").orEmpty(), p?.optString("platform").orEmpty(), p?.optString("version").orEmpty())
            val printer = o.optJSONObject("printer")?.let { p ->
                PrinterTarget(p.optString("model"), p.strOrNull("firmware"), p.strOrNull("printerId"),
                    p.optJSONArray("nozzleDiameters")?.let { a -> (0 until a.length()).map { a.optDouble(it) } } ?: emptyList(),
                    p.rest("model", "firmware", "printerId", "nozzleDiameters", "profileId", "family"), p.strOrNull("profileId"), p.strOrNull("family"))
            }
            val plates = o.optJSONArray("plates")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { p ->
                PlateEntry(p.optInt("index"), p.optString("name"), p.optJSONArray("objects")?.let { oa -> (0 until oa.length()).mapNotNull { oa.optJSONObject(it) }.map { e ->
                    ObjectEntry(e.optInt("objectId"), e.optString("name"), e.intOrNull("materialSlot"), e.rest("objectId", "name", "materialSlot", "paintSlots", "settings"),
                        e.optJSONArray("paintSlots")?.let { ps -> (0 until ps.length()).map { ps.optInt(it, 1).coerceAtLeast(1) } } ?: emptyList(),
                        e.optJSONObject("settings")?.let { st -> st.keySet().filter { st.opt(it) is String }.associateWith { st.getString(it) } } ?: emptyMap()) } } ?: emptyList(),
                    p.rest("index", "name", "objects")) } } ?: emptyList()
            val materials = o.optJSONArray("materials")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { m ->
                MaterialSlot(m.optInt("slot"), m.strOrNull("type"), m.strOrNull("vendor"), m.strOrNull("subType"), m.strOrNull("colorHex"), m.intOrNull("toolhead"),
                    m.rest("slot", "type", "vendor", "subType", "colorHex", "toolhead", "filamentProfile"), m.strOrNull("filamentProfile")) } } ?: emptyList()
            val settings = o.optJSONObject("settings")?.let { s ->
                SettingsChoice(s.strOrNull("preset"), s.optJSONObject("overrides")?.let { ov -> ov.keySet().associateWith { ov.optString(it) } } ?: emptyMap(), s.rest("preset", "overrides"))
            } ?: SettingsChoice()
            val attribution = o.optJSONArray("attribution")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { x ->
                Attribution(x.optString("title"), x.strOrNull("creator"), x.strOrNull("source"), x.strOrNull("license"), x.rest("title", "creator", "source", "license")) } } ?: emptyList()
            return ProjectManifest(o.optString("projectId").ifBlank { throw ProjectFormatException("The project manifest has no project id.") },
                o.optLong("revision", 0), o.optString("name"), producer(o.optJSONObject("createdBy")), producer(o.optJSONObject("modifiedBy")),
                o.optLong("modifiedAtMillis", 0), printer, plates, materials, settings, attribution, o.optJSONObject("extensions") ?: JSONObject(),
                o.rest("schema", "version", "projectId", "revision", "name", "createdBy", "modifiedBy", "modifiedAtMillis", "printer", "plates", "materials", "settings", "attribution", "extensions"),
                version.optInt(1, 0))
        }
    }

    fun toJson(): JSONObject {
        fun producer(p: Producer) = JSONObject().put("app", p.app).put("platform", p.platform).put("version", p.version)
        val o = JSONObject().put("schema", SCHEMA).put("version", JSONArray().put(MAJOR).put(maxOf(MINOR, formatMinor)))
            .put("projectId", projectId).put("revision", revision).put("name", name).put("createdBy", producer(createdBy))
            .put("modifiedBy", producer(modifiedBy)).put("modifiedAtMillis", modifiedAtMillis)
        printer?.let { p -> o.put("printer", merge(JSONObject().put("model", p.model).putOpt("firmware", p.firmware).putOpt("printerId", p.printerId)
            .putOpt("profileId", p.profileId).putOpt("family", p.family)
            .apply { if (p.nozzleDiameters.isNotEmpty()) put("nozzleDiameters", JSONArray(p.nozzleDiameters)) }, p.unknown)) }
        o.put("plates", JSONArray(plates.map { p -> merge(JSONObject().put("index", p.index).put("name", p.name)
            .put("objects", JSONArray(p.objects.map { e -> merge(JSONObject().put("objectId", e.objectId).put("name", e.name).putOpt("materialSlot", e.materialSlot)
                .apply { if (e.paintSlots.isNotEmpty()) put("paintSlots", JSONArray(e.paintSlots)); if (e.settings.isNotEmpty()) put("settings", JSONObject(e.settings.toSortedMap())) }, e.unknown) })), p.unknown) }))
        o.put("materials", JSONArray(materials.map { m -> merge(JSONObject().put("slot", m.slot).putOpt("type", m.type).putOpt("vendor", m.vendor)
            .putOpt("subType", m.subType).putOpt("colorHex", m.colorHex).putOpt("toolhead", m.toolhead).putOpt("filamentProfile", m.filamentProfile), m.unknown) }))
        o.put("settings", merge(JSONObject().putOpt("preset", settings.preset).put("overrides", JSONObject(settings.overrides)), settings.unknown))
        o.put("attribution", JSONArray(attribution.map { a -> merge(JSONObject().put("title", a.title).putOpt("creator", a.creator).putOpt("source", a.source).putOpt("license", a.license), a.unknown) }))
        o.put("extensions", extensions)
        return merge(o, unknown)
    }

    /** Stable text for hashing and storage (sorted keys, no whitespace variance). */
    fun canonicalText(): String = canonical(toJson())

    private fun canonical(v: Any?): String = when (v) {
        is JSONObject -> v.keySet().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(v.get(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(v)
        is Number -> JSONObject.numberToString(v)
        else -> v.toString()
    }
}

open class ProjectFormatException(message: String) : Exception(message)
class IncompatibleProjectException(val major: Int, message: String) : ProjectFormatException(message)

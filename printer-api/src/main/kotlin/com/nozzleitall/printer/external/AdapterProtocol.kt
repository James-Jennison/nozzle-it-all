package com.nozzleitall.printer.external

import com.nozzleitall.printer.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * nozzle-adapter protocol, version 1.x. Specification: docs/protocols/ADAPTER_PROTOCOL.md.
 *
 * Newline-delimited UTF-8 JSON over the helper's stdin/stdout. Readers ignore fields they do not know, so minor
 * versions may add fields; a different major version is refused during the hello exchange.
 */
object AdapterProtocol {
    const val NAME = "nozzle-adapter"
    const val MAJOR = 1
    const val MINOR = 0
    const val MAX_LINE_BYTES = 8 * 1024 * 1024

    fun hello(extra: JSONObject.() -> Unit = {}): JSONObject =
        JSONObject().put("type", "hello").put("protocol", NAME).put("version", JSONArray().put(MAJOR).put(MINOR)).apply(extra)

    /** Null when compatible, else the reason to refuse. */
    fun incompatibility(hello: JSONObject): String? {
        if (hello.optString("type") != "hello" || hello.optString("protocol") != NAME) return "The adapter did not identify itself with the $NAME protocol."
        val version = hello.optJSONArray("version") ?: return "The adapter did not report a protocol version."
        val major = version.optInt(0, -1)
        return if (major != MAJOR) "The adapter speaks $NAME $major.x; this Nozzle It All speaks $MAJOR.x. Install matching versions." else null
    }

    fun request(id: Long, method: String, params: JSONObject = JSONObject()) =
        JSONObject().put("type", "request").put("id", id).put("method", method).put("params", params)
    fun result(id: Long, result: Any) = JSONObject().put("type", "response").put("id", id).put("result", result)
    fun error(id: Long, code: String, message: String) =
        JSONObject().put("type", "response").put("id", id).put("error", JSONObject().put("code", code).put("message", message))

    // --- codecs. Enum values travel by name; unknown names decode to a safe default, never an exception. ---

    fun encode(identity: PrinterIdentity) = JSONObject().put("id", identity.id).put("displayName", identity.displayName)
        .put("model", identity.model).put("family", identity.family.id).put("address", identity.address).putOpt("profileId", identity.profileId)
    fun decodeIdentity(o: JSONObject) = PrinterIdentity(o.getString("id"), o.optString("displayName"), o.optString("model"),
        PrinterFamily.parse(o.optString("family").ifBlank { o.optString("firmware") }), o.getString("address"), o.optString("profileId").ifBlank { null })

    fun encode(config: PrinterConfig) = JSONObject().put("identity", encode(config.identity)).put("adapterId", config.adapterId)
        .put("secret", config.secret).put("extras", JSONObject(config.extras))
    fun decodeConfig(o: JSONObject): PrinterConfig {
        val extras = o.optJSONObject("extras")?.let { e -> e.keySet().associateWith { e.optString(it) } } ?: emptyMap()
        return PrinterConfig(decodeIdentity(o.getJSONObject("identity")), o.getString("adapterId"), o.optString("secret"), extras)
    }

    /** Capability schema 2, snake_case keys as in the brief; unknown keys are ignored so newer adapters stay readable. */
    fun encode(c: Capabilities) = JSONObject().put("schema", Capabilities.SCHEMA_VERSION)
        .put("upload_job", c.uploadJob).put("upload_and_start", c.uploadAndStart).put("start_print", c.startPrint).put("pause_print", c.pausePrint)
        .put("resume_print", c.resumePrint).put("cancel_print", c.cancelPrint).put("temperatures", c.temperatures).put("motion", c.motion)
        .put("camera", c.camera).put("material_state", c.materialState).put("material_edit", c.materialEdit).put("load_unload", c.loadUnload)
        .put("multi_material", c.multiMaterial).put("toolhead_state", c.toolheadState).put("bed_mesh", c.bedMesh).put("files", c.files)
        .put("job_history", c.jobHistory).put("local_connection", c.localConnection).put("remote_connection", c.remoteConnection)
        .put("vendor_cloud", c.vendorCloud).put("requires_vendor_account", c.requiresVendorAccount).put("firmware_updates", c.firmwareUpdates)
        .put("calibration", c.calibration).put("accepted_outputs", JSONArray(c.acceptedOutputs.sorted())).put("vendor_extensions", JSONArray(c.vendorExtensions.sorted()))
    fun decodeCapabilities(o: JSONObject) = Capabilities(o.optBoolean("upload_job"), o.optBoolean("upload_and_start"), o.optBoolean("start_print"),
        o.optBoolean("pause_print"), o.optBoolean("resume_print"), o.optBoolean("cancel_print"), o.optBoolean("temperatures"), o.optBoolean("motion"),
        o.optBoolean("camera"), o.optBoolean("material_state"), o.optBoolean("material_edit"), o.optBoolean("load_unload"), o.optBoolean("multi_material"),
        o.optBoolean("toolhead_state"), o.optBoolean("bed_mesh"), o.optBoolean("files"), o.optBoolean("job_history"), o.optBoolean("local_connection"),
        o.optBoolean("remote_connection"), o.optBoolean("vendor_cloud"), o.optBoolean("requires_vendor_account"), o.optBoolean("firmware_updates"),
        o.optBoolean("calibration"), o.optJSONArray("accepted_outputs")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: setOf("gcode"),
        o.optJSONArray("vendor_extensions")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet())

    /** JSON-compatible extension values (String, Number, Boolean, List, Map) to and from org.json. */
    fun toJson(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply { v.forEach { (k, x) -> put(k.toString(), toJson(x)) } }
        is Iterable<*> -> JSONArray(v.map { toJson(it) })
        is String, is Number, is Boolean -> v
        else -> v.toString()
    }
    fun fromJson(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> v.keySet().associateWith { fromJson(v.get(it)) }
        is JSONArray -> (0 until v.length()).map { fromJson(v.get(it)) }
        else -> v
    }

    fun encode(m: Material) = JSONObject().putOpt("vendor", m.vendor).putOpt("type", m.type).putOpt("subType", m.subType)
        .putOpt("colorHex", m.colorHex).put("fromTag", m.fromTag)
    fun decodeMaterial(o: JSONObject) = Material(o.optStringOrNull("vendor"), o.optStringOrNull("type"), o.optStringOrNull("subType"),
        o.optStringOrNull("colorHex"), o.optBoolean("fromTag"))

    fun encode(s: PrinterStatus): JSONObject = JSONObject().put("state", s.state.name).put("route", s.route.name)
        .putOpt("job", s.job?.let { JSONObject().put("fileName", it.fileName).put("fraction", it.fraction.toDouble()).putOpt("elapsedSeconds", it.elapsedSeconds)
            .putOpt("currentLayer", it.currentLayer).putOpt("totalLayers", it.totalLayers) })
        .putOpt("bed", s.bed?.let { JSONObject().putOpt("current", it.current).putOpt("target", it.target) })
        .put("toolheads", JSONArray(s.toolheads.map { t -> JSONObject().put("index", t.index).putOpt("nozzleTemperature", t.nozzleTemperature)
            .putOpt("nozzleTarget", t.nozzleTarget).putOpt("nozzleDiameterMm", t.nozzleDiameterMm).put("loaded", t.loaded)
            .putOpt("material", t.material?.let(::encode)).put("active", t.active) }))
        .put("extensions", toJson(s.extensions))
        .putOpt("message", s.message).put("observedAtMillis", s.observedAtMillis)

    fun decodeStatus(o: JSONObject): PrinterStatus {
        val job = o.optJSONObject("job")?.let { JobProgress(it.optString("fileName"), it.optDouble("fraction", 0.0).toFloat().coerceIn(0f, 1f),
            it.optDoubleOrNull("elapsedSeconds"), it.optIntOrNull("currentLayer"), it.optIntOrNull("totalLayers")) }
        val bed = o.optJSONObject("bed")?.let { Temperature(it.optDoubleOrNull("current"), it.optDoubleOrNull("target")) }
        val heads = o.optJSONArray("toolheads")?.let { a -> (0 until minOf(a.length(), 64)).mapNotNull { a.optJSONObject(it) }.map { t ->
            Toolhead(t.optInt("index"), t.optDoubleOrNull("nozzleTemperature"), t.optDoubleOrNull("nozzleTarget"), t.optDoubleOrNull("nozzleDiameterMm"),
                t.optBoolean("loaded"), t.optJSONObject("material")?.let(::decodeMaterial), t.optBoolean("active")) } } ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val ext = (fromJson(o.optJSONObject("extensions")) as? Map<String, Any?>)?.filterValues { it != null } as Map<String, Any>? ?: emptyMap()
        return PrinterStatus(enumOr(o.optString("state"), PrinterState.UNKNOWN), enumOr(o.optString("route"), ConnectionRoute.VENDOR_CLOUD),
            job, bed, heads, o.optStringOrNull("message"), o.optLong("observedAtMillis", System.currentTimeMillis()), ext)
    }

    fun encode(c: CameraEndpoint) = JSONObject().put("id", c.id).put("name", c.name).put("kind", c.kind.name).put("url", c.url).putOpt("snapshotUrl", c.snapshotUrl).putOpt("liveUrl", c.liveUrl).putOpt("videoUrl", c.videoUrl)
    fun decodeCamera(o: JSONObject) = CameraEndpoint(o.getString("id"), o.optString("name"), enumOr(o.optString("kind"), CameraKind.SNAPSHOT), o.getString("url"), o.optStringOrNull("snapshotUrl"), o.optStringOrNull("liveUrl"), o.optStringOrNull("videoUrl"))

    fun encode(d: DiscoveredPrinter) = JSONObject().put("address", d.address).put("model", d.model).put("suggestedFamily", d.suggestedFamily.id)
        .put("adapterId", d.adapterId).put("evidence", d.evidence).put("route", d.route.name)
    fun decodeDiscovered(o: JSONObject) = DiscoveredPrinter(o.getString("address"), o.optString("model"), PrinterFamily.parse(o.optString("suggestedFamily").ifBlank { o.optString("suggestedFirmware") }),
        o.optString("adapterId"), o.optString("evidence"), enumOr(o.optString("route"), ConnectionRoute.LAN))

    fun encode(a: PrinterAction): JSONObject = when (a) {
        is PrinterAction.StartJob -> JSONObject().put("kind", "start").put("remotePath", a.remotePath).put("toolheadMap", JSONArray(a.toolheadMap))
        PrinterAction.Pause -> JSONObject().put("kind", "pause")
        PrinterAction.Resume -> JSONObject().put("kind", "resume")
        PrinterAction.Cancel -> JSONObject().put("kind", "cancel")
        is PrinterAction.SetNozzleTemperature -> JSONObject().put("kind", "nozzleTemperature").put("toolhead", a.toolhead).put("celsius", a.celsius)
        is PrinterAction.SetBedTemperature -> JSONObject().put("kind", "bedTemperature").put("celsius", a.celsius)
        PrinterAction.HomeAll -> JSONObject().put("kind", "home")
        is PrinterAction.Jog -> JSONObject().put("kind", "jog").put("axis", a.axis.toString()).put("millimetres", a.millimetres)
        is PrinterAction.LoadMaterial -> JSONObject().put("kind", "load").put("toolhead", a.toolhead)
        is PrinterAction.UnloadMaterial -> JSONObject().put("kind", "unload").put("toolhead", a.toolhead)
        is PrinterAction.SetMaterialInfo -> JSONObject().put("kind", "materialInfo").put("toolhead", a.toolhead).put("material", encode(a.material))
        is PrinterAction.SelectToolhead -> JSONObject().put("kind", "selectToolhead").put("toolhead", a.toolhead)
        is PrinterAction.UploadAndStart -> JSONObject().put("kind", "uploadAndStart").put("localPath", a.localPath).put("remoteName", a.remoteName)
    }

    /** Null for an action kind this side does not know; the caller answers "unsupported" instead of guessing. */
    fun decodeAction(o: JSONObject): PrinterAction? = when (o.optString("kind")) {
        "start" -> PrinterAction.StartJob(o.getString("remotePath"), o.optJSONArray("toolheadMap")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList())
        "pause" -> PrinterAction.Pause
        "resume" -> PrinterAction.Resume
        "cancel" -> PrinterAction.Cancel
        "nozzleTemperature" -> PrinterAction.SetNozzleTemperature(o.getInt("toolhead"), o.getInt("celsius"))
        "bedTemperature" -> PrinterAction.SetBedTemperature(o.getInt("celsius"))
        "home" -> PrinterAction.HomeAll
        "jog" -> PrinterAction.Jog(o.getString("axis").single(), o.getDouble("millimetres"))
        "load" -> PrinterAction.LoadMaterial(o.getInt("toolhead"))
        "unload" -> PrinterAction.UnloadMaterial(o.getInt("toolhead"))
        "materialInfo" -> PrinterAction.SetMaterialInfo(o.getInt("toolhead"), decodeMaterial(o.getJSONObject("material")))
        "selectToolhead" -> PrinterAction.SelectToolhead(o.getInt("toolhead"))
        "uploadAndStart" -> PrinterAction.UploadAndStart(o.getString("localPath"), o.getString("remoteName"))
        else -> null
    }

    fun encode(outcome: ActionOutcome): JSONObject = when (outcome) {
        ActionOutcome.Accepted -> JSONObject().put("outcome", "accepted")
        is ActionOutcome.Rejected -> JSONObject().put("outcome", "rejected").put("reason", outcome.reason)
        is ActionOutcome.Unknown -> JSONObject().put("outcome", "unknown").put("reason", outcome.reason)
    }
    fun decodeOutcome(o: JSONObject): ActionOutcome = when (o.optString("outcome")) {
        "accepted" -> ActionOutcome.Accepted
        "rejected" -> ActionOutcome.Rejected(o.optString("reason"))
        // Anything unrecognised is treated as unknown, which forces a reconcile rather than assuming success.
        else -> ActionOutcome.Unknown(o.optString("reason", "Unrecognised reply from the adapter."))
    }

    inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E = enumValues<E>().firstOrNull { it.name == name } ?: fallback
    fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
    fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null
    fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
}

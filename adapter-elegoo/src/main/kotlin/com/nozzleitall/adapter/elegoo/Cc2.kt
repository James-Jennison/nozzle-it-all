package com.nozzleitall.adapter.elegoo

import com.nozzleitall.printer.JobProgress
import com.nozzleitall.printer.PrinterState
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Centauri Carbon 2's LAN protocol: JSON-RPC-like messages over MQTT on the printer's own broker (port 1883). Pure
 * message rules, no I/O.
 *
 * Source: elegoo-link (Apache-2.0), src/lan/adapters/elegoo_fdm_cc2/:
 *  - elegoo_fdm_cc2_protocol.cpp: tcp://host:1883; user "elegoo" with the access code as password ("123456" when none
 *    is set); client id "1_PC_<4 digits>" and request id "<client id>_req"; topics elegoo/<sn>/<client>/api_request,
 *    .../api_response, elegoo/<sn>/api_status, elegoo/<sn>/api_register and elegoo/<sn>/<request id>/register_response;
 *    the registration reply ({"client_id", "error": "ok" | "too many clients" | ...}); the {"type":"PING"} / "PONG"
 *    heartbeat every 10 s on the request topic.
 *  - elegoo_fdm_cc2_message_adapter.cpp: {"id", "method", "params"} requests; the method table (1001 attributes, 1002
 *    status, 1020 start, 1021 pause, 1022 stop, 2005 CANVAS, 6000 status event, 6008 attribute event; resume is not in
 *    it); the START_PRINT params with config.slot_map; the full-status cache merged with 6000 deltas (exception_code
 *    replaced, not merged); machine_status/sub_status meanings; print_status, extruder and heater_bed fields; the error
 *    code table in convertRequestErrorToElegooError.
 *  - elegoo_fdm_cc2_discovery_strategy.cpp: {"id":0,"method":7000} to UDP 52700; result.host_name, machine_model, sn,
 *    token_status (access code required) and lan_status (1 LAN-only mode).
 */
object Cc2 {
    const val MQTT_PORT = 1883
    const val DISCOVERY_PORT = 52700
    const val DISCOVERY_MESSAGE = """{"id":0,"method":7000}"""
    const val USERNAME = "elegoo"
    const val DEFAULT_ACCESS_CODE = "123456"
    const val HEARTBEAT = """{"type":"PING"}"""

    const val METHOD_ATTRIBUTES = 1001
    const val METHOD_STATUS = 1002
    const val METHOD_START_PRINT = 1020
    const val METHOD_PAUSE = 1021
    const val METHOD_STOP = 1022
    const val METHOD_CANVAS = 2005
    const val EVENT_STATUS = 6000

    fun requestTopic(sn: String, clientId: String) = "elegoo/$sn/$clientId/api_request"
    fun responseTopic(sn: String, clientId: String) = "elegoo/$sn/$clientId/api_response"
    fun statusTopic(sn: String) = "elegoo/$sn/api_status"
    fun registerTopic(sn: String) = "elegoo/$sn/api_register"
    fun registerResponseTopic(sn: String, requestId: String) = "elegoo/$sn/$requestId/register_response"
    fun registration(clientId: String, requestId: String): JSONObject = JSONObject().put("client_id", clientId).put("request_id", requestId)

    data class Discovery(val name: String, val model: String, val serial: String, val accessCodeRequired: Boolean, val lanOnly: Boolean)

    fun parseDiscovery(reply: String): Discovery? = try {
        val json = JSONObject(reply)
        val r = json.optJSONObject("result")
        if (!json.has("id") || r == null) null
        else Discovery(r.optString("host_name").trim(), r.optString("machine_model").trim(), r.optString("sn").trim(), flag(r, "token_status"), flag(r, "lan_status"))
    } catch (e: Exception) { null }

    fun request(id: Int, method: Int, params: JSONObject = JSONObject()): JSONObject = JSONObject().put("id", id).put("method", method).put("params", params)

    /** START_PRINT's params, with ElegooSlicer's send-dialog defaults (no time-lapse, no levelling, textured PEI side "A"). */
    fun startPrintParams(fileName: String, slotMap: JSONArray, autoBedLeveling: Boolean = false, printLayout: String = "A"): JSONObject =
        JSONObject().put("storage_media", "local").put("filename", fileName).put("config", JSONObject()
            .put("delay_video", false).put("printer_check", autoBedLeveling).put("print_layout", printLayout).put("bedlevel_force", false).put("slot_map", slotMap))

    /** convertRequestErrorToElegooError's table. Every non-zero error_code is the printer refusing. */
    fun errorReason(code: Int): String = when (code) {
        109 -> "The printer has run out of filament."
        1000 -> "The printer rejected the access code."
        1001, 1003 -> "The printer didn't accept the request."
        1009 -> "The printer is busy."
        1010 -> "The printer isn't printing."
        1021 -> "The printer couldn't find that file."
        1026 -> "The printer has no bed levelling data. Level the bed on the printer, then start again."
        9004, 9008, 9009 -> "The file failed the printer's MD5 check. Send it again."
        else -> "The printer refused the request (code $code)."
    }

    /** Merges a 6000 delta into the cached full status, as mergeStatusUpdateJson does (in place; returns [into]). */
    fun merge(into: JSONObject, delta: JSONObject): JSONObject {
        for (key in delta.keySet()) {
            val v = delta.opt(key)
            val existing = into.opt(key)
            if (key != "exception_code" && v is JSONObject && existing is JSONObject) merge(existing, v) else into.put(key, v)
        }
        return into
    }

    data class Reading(val state: PrinterState, val message: String?, val job: JobProgress?, val nozzle: Double?, val nozzleTarget: Double?,
                       val bed: Double?, val bedTarget: Double?)

    private val busy = mapOf(0 to "starting up", 3 to "loading or unloading filament", 4 to "loading or unloading filament", 5 to "levelling the bed",
        6 to "calibrating the heaters", 7 to "running a resonance test", 8 to "running its self-check", 9 to "updating its firmware", 10 to "homing",
        11 to "receiving a file", 12 to "making a time-lapse video", 13 to "loading or unloading the extruder")

    /**
     * Maps a (merged) status result onto the shared states, following handlePrinterStatus. Busy machine states become
     * STARTING, which offers no actions. Null when there's no machine_status.
     */
    fun parseStatus(result: JSONObject): Reading? {
        val ms = result.optJSONObject("machine_status") ?: return null
        val status = (ms.opt("status") as? Number)?.toInt() ?: return null
        val sub = (ms.opt("sub_status") as? Number)?.toInt() ?: 0
        var downloading = false
        val (state, note) = when (status) {
            1 -> PrinterState.READY to null
            2 -> when (sub) {
                2501 -> PrinterState.PAUSED to "Pausing"
                2502, 2505 -> PrinterState.PAUSED to null
                2503 -> PrinterState.PRINTING to "Stopping"
                2504 -> PrinterState.CANCELLED to null
                2077 -> PrinterState.FINISHED to null
                2401 -> PrinterState.PRINTING to "Resuming"
                1081, 1082, 1086 -> { downloading = true; PrinterState.PRINTING to "Downloading the file" }
                else -> PrinterState.PRINTING to null
            }
            14 -> PrinterState.ERROR to "The printer is in an emergency stop."
            15 -> PrinterState.ERROR to "The printer is recovering from a power loss. Check it on the printer's screen."
            in busy.keys -> PrinterState.STARTING to "The printer is ${busy.getValue(status)}."
            else -> PrinterState.UNKNOWN to "The printer reported a state Nozzle It All doesn't know ($status)."
        }
        val job = if (state.isActiveJob) {
            val ps = result.optJSONObject("print_status") ?: JSONObject()
            // During a download the numbers aren't the print's (elegoo-link zeroes them for the same reason).
            val fraction = if (downloading) 0.0 else ((ms.opt("progress") as? Number)?.toDouble() ?: 0.0) / 100.0
            JobProgress(ps.optString("filename").substringAfterLast('/'), fraction.coerceIn(0.0, 1.0).toFloat(),
                if (downloading) null else num(ps, "print_duration"), (ps.opt("current_layer") as? Number)?.toInt(), (ps.opt("total_layer") as? Number)?.toInt())
        } else null
        val ex = result.optJSONObject("extruder"); val bed = result.optJSONObject("heater_bed")
        return Reading(state, note, job, ex?.let { num(it, "temperature") }, ex?.let { num(it, "target") }, bed?.let { num(it, "temperature") }, bed?.let { num(it, "target") })
    }

    private fun num(o: JSONObject, k: String): Double? = (o.opt(k) as? Number)?.toDouble()
    private fun flag(o: JSONObject, k: String): Boolean = when (val v = o.opt(k)) { is Boolean -> v; is Number -> v.toInt() == 1; else -> false }
}

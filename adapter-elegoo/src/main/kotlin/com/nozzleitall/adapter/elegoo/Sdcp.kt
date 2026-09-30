package com.nozzleitall.adapter.elegoo

import com.nozzleitall.printer.JobProgress
import com.nozzleitall.printer.PrinterState
import org.json.JSONArray
import org.json.JSONObject

/**
 * SDCP V3, the Centauri Carbon's stock-firmware protocol: JSON over a WebSocket on port 3030. Pure message rules, no I/O.
 *
 * Sources (read-only checkouts):
 *  - SDCP V3.0.0 documentation, /mnt/faststorage/SDCP-V3 "SDCP(Smart Device Control Protocol)_V3.0.0_EN.md": discovery
 *    ("M99999" by UDP to port 3000, the reply's Id/Data fields), ws://host:3030/websocket, topics, "ping"/"pong", the
 *    status and attribute messages, Cmd 0/1/128/129/130/131 and the print-control Ack codes.
 *  - elegoo-link (Apache-2.0), src/lan/adapters/elegoo_fdm_cc/: the request envelope as the Centauri Carbon receives it
 *    (elegoo_fdm_cc_message_adapter.cpp convertRequest/createStandardBody: outer "Id" = MainboardID, empty "Topic",
 *    "From": 1), the command table (0, 1, 128-131 and 324 GET_CANVAS_STATUS), the FDM machine and print sub-status
 *    enums, the status field names (TempOfNozzle, TempOfHotbed, PrintInfo.Progress/CurrentTicks, ...) and the Cmd 128
 *    body with slot_map; elegoo_fdm_cc_discovery_strategy.cpp; elegoo_fdm_cc_protocol.cpp (URL and the "ping" heartbeat).
 */
object Sdcp {
    const val WEBSOCKET_PORT = 3030
    const val WEBSOCKET_PATH = "/websocket"
    const val DISCOVERY_PORT = 3000
    const val DISCOVERY_MESSAGE = "M99999"
    const val HEARTBEAT = "ping"

    const val CMD_STATUS = 0
    const val CMD_ATTRIBUTES = 1
    const val CMD_START_PRINT = 128
    const val CMD_PAUSE = 129
    const val CMD_STOP = 130
    const val CMD_RESUME = 131
    /** GET_CANVAS_STATUS: the CANVAS slots (elegoo-link COMMAND_MAPPING_TABLE; not in the SDCP V3 document). */
    const val CMD_CANVAS = 324
    /** elegoo-link sends From = 1 (SDCP_FROM_WEB_PC in the SDCP document's sdcp_from_t). */
    const val FROM = 1

    data class Discovery(val name: String, val machineName: String, val mainboardId: String, val firmwareVersion: String, val ip: String?)

    /** The reply to "M99999". Null unless it has the SDCP shape (elegoo_fdm_cc_discovery_strategy.cpp parseResponse). */
    fun parseDiscovery(reply: String): Discovery? = try {
        val json = JSONObject(reply)
        val data = json.optJSONObject("Data")
        if (!json.has("Id") || data == null) null
        else Discovery(data.optString("Name").trim(), data.optString("MachineName").trim(), data.optString("MainboardID").trim(),
            data.optString("FirmwareVersion").trim().removePrefix("V").removePrefix("v"), data.optString("MainboardIP").trim().ifEmpty { null })
    } catch (e: Exception) { null }

    /** A request exactly as elegoo-link builds it for the Centauri Carbon. */
    fun request(cmd: Int, data: JSONObject, mainboardId: String, requestId: String, timeStamp: Long): JSONObject =
        JSONObject().put("Id", mainboardId).put("Topic", "").put("Data", JSONObject()
            .put("RequestID", requestId).put("MainboardID", mainboardId).put("TimeStamp", timeStamp)
            .put("Cmd", cmd).put("From", FROM).put("Data", data))

    /**
     * Cmd 128's Data (elegoo-link START_PRINT case): the file name as uploaded, from layer 0, with ElegooSlicer's send
     * dialog defaults (PrintSendDialogEx.cpp: time-lapse off, bed levelling off, the textured PEI plate = platform 0).
     */
    fun startPrintData(fileName: String, slotMap: JSONArray, autoBedLeveling: Boolean = false, platformType: Int = 0, timeLapse: Boolean = false): JSONObject =
        JSONObject().put("Filename", fileName).put("StartLayer", 0).put("Calibration_switch", if (autoBedLeveling) 1 else 0)
            .put("PrintPlatformType", platformType).put("Tlp_Switch", if (timeLapse) 1 else 0).put("slot_map", slotMap)

    /** Print-control Ack codes (SDCP document, sdcp_print_ctrl_ack_t). Every non-zero Ack is the printer refusing. */
    fun ackReason(ack: Int): String = when (ack) {
        1 -> "The printer is busy."
        2 -> "The printer couldn't find that file."
        3 -> "The file failed the printer's MD5 check. Send it again."
        4 -> "The printer couldn't read the file."
        5 -> "The file's resolution doesn't match this printer."
        6 -> "The printer doesn't recognise the file format."
        7 -> "The file was made for a different printer model."
        else -> "The printer refused the command (code $ack)."
    }

    /** What a status message says, in the shared vocabulary. */
    data class Reading(val state: PrinterState, val message: String?, val job: JobProgress?, val nozzle: Double?, val nozzleTarget: Double?,
                       val bed: Double?, val bedTarget: Double?)

    /** Machine statuses that are neither idle nor printing (elegoo-link cc::sdcp_machine_status_t), in plain words. */
    private val busy = mapOf(2 to "receiving a file", 3 to "running an exposure test", 4 to "running its self-check", 5 to "levelling the bed",
        6 to "running a resonance test", 7 to "busy", 8 to "checking a file", 9 to "homing", 10 to "unloading filament", 11 to "calibrating the heaters")

    /**
     * Maps a status message (the object carrying "Status") onto the shared states, following elegoo-link's
     * handlePrinterStatus: CurrentStatus is a list whose first entry counts, except that "file transferring" yields to a
     * second entry; PrintInfo.Status refines a printing machine. Busy states become STARTING, which offers no actions, so
     * nothing can be started or moved while the printer is levelling or receiving a file. After a job, SDCP keeps the
     * last sub-status ("complete", "stopped"), which gives FINISHED and CANCELLED. Null when the message has no status.
     */
    fun parseStatus(message: JSONObject): Reading? {
        val s = message.optJSONObject("Status") ?: return null
        val list = s.optJSONArray("CurrentStatus")?.let { a -> (0 until a.length()).mapNotNull { (a.opt(it) as? Number)?.toInt() } }
            ?: (s.opt("CurrentStatus") as? Number)?.let { listOf(it.toInt()) } ?: emptyList()
        var machine = list.firstOrNull() ?: 0
        if (machine == 2 && list.size > 1) machine = list[1]
        val info = s.optJSONObject("PrintInfo")
        val sub = (info?.opt("Status") as? Number)?.toInt() ?: -1
        val (state, note) = when (machine) {
            0 -> when (sub) {
                9 -> PrinterState.FINISHED to null
                8 -> PrinterState.CANCELLED to null
                14 -> PrinterState.READY to "The last print stopped with an error."
                else -> PrinterState.READY to null
            }
            1 -> when (sub) {
                5 -> PrinterState.PAUSED to "Pausing"
                6 -> PrinterState.PAUSED to null
                7 -> PrinterState.PRINTING to "Stopping"
                8 -> PrinterState.CANCELLED to null
                9 -> PrinterState.FINISHED to null
                12 -> PrinterState.PRINTING to "Resuming"
                14 -> PrinterState.ERROR to "The print stopped with an error."
                else -> PrinterState.PRINTING to null
            }
            in busy.keys -> PrinterState.STARTING to "The printer is ${busy.getValue(machine)}."
            else -> PrinterState.UNKNOWN to "The printer reported a state Nozzle It All doesn't know ($machine)."
        }
        val job = if (state.isActiveJob && info != null) {
            val layer = num(info, "CurrentLayer")?.toInt(); val total = num(info, "TotalLayer")?.toInt()
            val pct = num(info, "Progress")
            val fraction = (pct?.let { it / 100.0 } ?: if (layer != null && total != null && total > 0) layer.toDouble() / total else 0.0).coerceIn(0.0, 1.0)
            // elegoo-link treats the Centauri Carbon's ticks as seconds (remaining = TotalTicks - CurrentTicks, in s).
            JobProgress(info.optString("Filename").substringAfterLast('/'), fraction.toFloat(), num(info, "CurrentTicks"), layer, total)
        } else null
        return Reading(state, note, job, num(s, "TempOfNozzle"), num(s, "TempTargetNozzle"), num(s, "TempOfHotbed"), num(s, "TempTargetHotbed"))
    }

    /** The mainboard id a message carries, wherever the firmware put it. */
    fun mainboardIdOf(message: JSONObject): String? =
        (message.optString("MainboardID").ifBlank { null } ?: message.optJSONObject("Data")?.optString("MainboardID")?.ifBlank { null }
            ?: message.optJSONObject("Attributes")?.optString("MainboardID")?.ifBlank { null })?.trim()

    private fun num(o: JSONObject, k: String): Double? = (o.opt(k) as? Number)?.toDouble()
}

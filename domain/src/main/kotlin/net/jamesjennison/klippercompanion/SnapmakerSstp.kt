package net.jamesjennison.klippercompanion

import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Snapmaker 2.0 A-series (A150 / A250 / A350, single or dual extruder, with or without the Quick Swap Kit) over the
 * touchscreen's HTTP API, which Luban calls SSTP (PrinterKind.SNAPMAKER_A_SERIES): pure request bodies and reply parsers,
 * no I/O. SnapmakerSstpPrinterService does the talking.
 *
 * Source (docs/upstream/PROVENANCE.md P-0037), cited as file:line: Luban db573f5 (AGPL-3.0),
 * `src/server/services/machine/channels/SstpHttpChannel.ts` ("sstp:N") and `types.ts` ("luban/types.ts:N"). Nothing
 * here has been run against a printer.
 *
 * The protocol:
 * - `POST /api/v1/connect` with the form body `token=<token>`, or an empty body the first time (sstp:171-175). The printer
 *   asks the person on its touchscreen to accept. Until they do, the reply carries no data (sstp:189-199). Once they do,
 *   it carries the `token` to use from then on and the machine's `series` and `headType` (sstp:177-179, 201-250).
 * - `GET /api/v1/status`, polled, gives the state, temperatures and job progress (sstp:314-379, 650-678).
 * - `POST /api/v1/upload`, multipart: a `token` field and the `file` (sstp:461-476). An upload never starts a print; Luban
 *   starts one with a separate `POST /api/v1/start_print` (sstp:599-612), which this port never sends.
 * - `POST /api/v1/disconnect` with `token=<token>` (sstp:284-306).
 *
 * The token is a secret. It only ever goes in a request body, never in a URL or a log. Luban also puts it in the
 * query string of some GETs (`module_list`, `module_info`, sstp:568, 586); those are not ported.
 */
object SnapmakerSstp {
    /**
     * Whether starting a print from Nozzle It All (`POST /api/v1/start_print`, sstp:599-612) and the controls that heat or
     * move the printer have been confirmed on a real Snapmaker 2.0. These are pause / resume / stop (sstp:614-649),
     * temperatures and homing / jogging. Until then the service uploads a sliced file and refuses the start with
     * [startNotVerified], and refuses every such control with [controlNotVerified], in both cases before anything is sent.
     * Flip only with a real printer run recorded in docs/upstream/PROVENANCE.md.
     */
    const val START_VERIFIED = false

    /**
     * The touchscreen's HTTP port. Luban reads it from PORT_SCREEN_HTTP (luban/ProtocolDetector.ts:5, 29), whose value is in
     * `src/server/constants`, which isn't in the partial clone used. 8080 is the commonly documented Snapmaker 2.0 API
     * port, so it is the default, but it is NOT confirmed from the sources. An address with its own port wins.
     */
    const val DEFAULT_PORT = 8080

    const val CONNECT_PATH = "api/v1/connect"
    const val DISCONNECT_PATH = "api/v1/disconnect"
    const val STATUS_PATH = "api/v1/status"
    const val UPLOAD_PATH = "api/v1/upload"
    /** The multipart field names (sstp:470-471). */
    const val UPLOAD_TOKEN_FIELD = "token"
    const val UPLOAD_FILE_FIELD = "file"
    /** Luban's timeouts: connect / disconnect 3 s (sstp:174, 296), upload 5 min (sstp:469). */
    const val CONNECT_TIMEOUT_MS = 3_000L
    const val UPLOAD_TIMEOUT_MS = 300_000L

    // ---- the gate (the same wording as SnapmakerSacp) -------------------------------------------------------------------

    fun startNotVerified(remoteName: String, uploaded: Boolean = true): String = SnapmakerSacp.startNotVerified(remoteName, uploaded)

    fun requireStartVerified(remoteName: String, verified: Boolean = START_VERIFIED, uploaded: Boolean = true) {
        if (!verified) throw ApiFailure(startNotVerified(remoteName, uploaded))
    }

    fun controlNotVerified(what: String): String = SnapmakerSacp.controlNotVerified(what)

    fun requireControlVerified(what: String, verified: Boolean = START_VERIFIED) {
        if (!verified) throw ApiFailure(controlNotVerified(what))
    }

    // ---- connect ----------------------------------------------------------------------------------------------------------

    /**
     * The connect body: `token=<token>`, or empty with no token yet (sstp:175). Luban sends the token raw. Here it is
     * form-encoded, which leaves the printer's own tokens unchanged. Luban's tokens are UUID-shaped; that shape is assumed
     * from its handling, not confirmed.
     */
    fun tokenBody(token: String): String = if (token.isBlank()) "" else "token=" + URLEncoder.encode(token, "UTF-8")

    /** The kind of tool head, from `headType` (sstp:207-250). */
    enum class Head { SINGLE_EXTRUDER, DUAL_EXTRUDER, PRINTING_UNKNOWN, LASER, CNC }

    /** sstp:207-250: 1 single extruder, 5 dual; 2 and 8 CNC; 3, 4, 6, 7 and 9 laser; anything else a printing head. */
    fun head(headType: Int?): Head = when (headType) {
        1 -> Head.SINGLE_EXTRUDER
        5 -> Head.DUAL_EXTRUDER
        2, 8 -> Head.CNC
        3, 4, 6, 7, 9 -> Head.LASER
        else -> Head.PRINTING_UNKNOWN
    }

    sealed class ConnectResult {
        /** The person accepted on the touchscreen. [token] is the one to keep (the reply's own, else the one sent). */
        data class Connected(val token: String, val series: String, val head: Head) : ConnectResult() {
            override fun toString(): String = "Connected(series=$series, head=$head)" // never the token
        }
        /** No data yet: the printer is waiting for the person to accept on its screen (sstp:189-199). */
        object AwaitingApproval : ConnectResult()
    }

    const val AWAITING_APPROVAL = "Accept the connection on the Snapmaker's touchscreen, then tap Connect again."
    const val NOT_A_PRINTING_HEAD = "A laser or CNC module is attached to this Snapmaker. Nozzle It All only works with a 3D printing module."

    /**
     * Reads the connect reply. [code] is the HTTP status; Luban accepts 200, 203 and 204 (sstp:76-81). A reply `token`
     * replaces [sentToken] (sstp:177-179). An empty or non-object body, or 204, means no data yet.
     */
    fun parseConnect(code: Int, body: String, sentToken: String): ConnectResult {
        if (code != 200 && code != 203 && code != 204) throw ApiFailure("The Snapmaker refused the connection (HTTP $code).")
        if (code == 204) return ConnectResult.AwaitingApproval
        val o = jsonOrNull(body) ?: return ConnectResult.AwaitingApproval
        if (o.length() == 0) return ConnectResult.AwaitingApproval
        val token = o.optString("token").takeIf { it.isNotBlank() } ?: sentToken
        if (token.isBlank()) return ConnectResult.AwaitingApproval
        val series = o.optString("series").trim()
        val headType = if (o.has("headType") && !o.isNull("headType")) o.optInt("headType", -1) else null
        return ConnectResult.Connected(token, series, head(headType))
    }

    // ---- status ---------------------------------------------------------------------------------------------------------

    /**
     * Luban's state names are its WorkflowStatus values (Idle, Running, Paused, ...), from the lower-cased `status`
     * (sstp:344). The enum is in @snapmaker/luban-platform, which isn't in the sources used, so this mapping is inferred:
     * idle → standby, running → printing, paused → paused, and anything else "unknown", which never allows a send.
     */
    fun stateFor(status: String): String = when (status.trim().lowercase()) {
        "idle" -> "standby"
        "running" -> "printing"
        "paused" -> "paused"
        else -> "unknown"
    }

    /** One status reply. Field names are Luban's MarlinStateData (luban/types.ts:39-71), which Luban's heartbeat spreads the reply into (sstp:339-345). */
    data class Status(
        val rawStatus: String,
        val state: String,
        val nozzle: Double?, val nozzleTarget: Double?,
        val rightNozzle: Double?, val rightNozzleTarget: Double?,
        val bed: Double?, val bedTarget: Double?,
        val currentWorkNozzle: Int?,
        val fileName: String,
        val progress: Float,
        val elapsedSeconds: Double?,
    ) {
        fun snapshot(): PrinterSnapshot = PrinterSnapshot(
            ready = true, state = state, filename = fileName, progress = progress, nozzle = nozzle, nozzleTarget = nozzleTarget,
            bed = bed, bedTarget = bedTarget, printDuration = elapsedSeconds,
            activeExtruder = when (currentWorkNozzle) { 1 -> "right nozzle"; 0 -> if (rightNozzle != null) "left nozzle" else "nozzle"; else -> "" },
        )

        fun toolheads(): List<ToolheadTemperature> =
            if (rightNozzle == null && rightNozzleTarget == null) listOf(ToolheadTemperature("Nozzle", nozzle, nozzleTarget))
            else listOf(ToolheadTemperature("Left nozzle", nozzle, nozzleTarget), ToolheadTemperature("Right nozzle", rightNozzle, rightNozzleTarget))
    }

    const val NEEDS_CONNECT = "The Snapmaker hasn't accepted Nozzle It All yet. Tap Connect in Edit printer and accept on the touchscreen."

    /**
     * Reads `GET /api/v1/status`. An empty reply means the printer wants the connection accepted first (sstp:328-336), and
     * so does a reply with no `status`. Progress is current line over total lines, and like Luban's getGcodePrintingInfo
     * (sstp:650-677) only when `currentLine`, `estimatedTime` and `totalLines` are all there and non-zero. `elapsedTime`
     * is in seconds (sstp:672, `* 1000` into ms).
     */
    fun parseStatus(body: String): Status {
        val o = jsonOrNull(body) ?: throw ApiFailure(NEEDS_CONNECT)
        if (o.length() == 0 || !o.has("status")) throw ApiFailure(NEEDS_CONNECT)
        val raw = o.optString("status")
        val current = num(o, "currentLine"); val total = num(o, "totalLines"); val estimated = num(o, "estimatedTime")
        val job = current != null && current != 0.0 && total != null && total != 0.0 && estimated != null && estimated != 0.0
        return Status(
            rawStatus = raw, state = stateFor(raw),
            nozzle = num(o, "nozzleTemperature"), nozzleTarget = num(o, "nozzleTargetTemperature"),
            rightNozzle = num(o, "nozzleRightTemperature"), rightNozzleTarget = num(o, "nozzleRightTargetTemperature"),
            bed = num(o, "heatedBedTemperature"), bedTarget = num(o, "heatedBedTargetTemperature"),
            currentWorkNozzle = num(o, "currentWorkNozzle")?.toInt(),
            fileName = if (job) o.optString("fileName") else "",
            progress = if (job) (current!! / total!!).coerceIn(0.0, 1.0).toFloat() else 0f,
            elapsedSeconds = if (job) num(o, "elapsedTime") else null,
        )
    }

    // ---- upload ---------------------------------------------------------------------------------------------------------

    /** The upload's file name: Luban's `targetFilename` (sstp:462, 471); no path, no control characters. */
    fun safeFileName(name: String): String = SnapmakerSacp.safeFileName(name)

    /** Luban treats any HTTP error as a failed upload and ignores the body (sstp:472-474). */
    fun uploadProblem(code: Int, name: String): String? = when {
        code in 200..299 -> null
        code == 401 || code == 403 -> "The Snapmaker refused the upload of $name (HTTP $code). Tap Connect in Edit printer and accept on the touchscreen."
        else -> "Could not upload $name to the Snapmaker (HTTP $code)."
    }

    // ---- helpers --------------------------------------------------------------------------------------------------------

    private fun jsonOrNull(body: String): JSONObject? = try {
        if (body.isBlank()) null else JSONObject(body)
    } catch (_: JSONException) { null }

    private fun num(o: JSONObject, key: String): Double? =
        if (!o.has(key) || o.isNull(key)) null else o.optDouble(key, Double.NaN).takeIf { it.isFinite() }
}

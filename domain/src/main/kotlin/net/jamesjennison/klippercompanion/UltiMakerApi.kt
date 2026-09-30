package net.jamesjennison.klippercompanion

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.Writer

/**
 * A networked UltiMaker (UltiMaker 3 / 3 Extended, S3, S5, S7; PrinterKind.ULTIMAKER): pure request builders and parsers,
 * no I/O. UltiMakerPrinterService does the talking. The UltiMaker 2 has no network connection and has no kind here.
 *
 * Sources (docs/upstream/PROVENANCE.md P-0035):
 * - Upstream OrcaSlicer 5298e49d `src/slic3r/Utils/UltiMaker.cpp` / `.hpp` for the printer's own API under `/api/v1`: the
 *   variant check (`GET system/variant`), pairing (`POST auth/request` -> the person approves on the printer ->
 *   `GET auth/check/<id>` -> `GET auth/verify` with HTTP Digest auth as id/key), the Griffin header clean-up, and the job
 *   upload (`POST print_job`, multipart `jobname` + `file`, digest auth).
 * - Status: Orca reads none (its `printer/status` request is only a connection probe, in code copied from its Duet host).
 *   It comes from UltiMaker's own slicer instead, Cura 72521b7 `plugins/UM3NetworkPrinting` (LGPL-3.0): the local cluster
 *   API every networked UltiMaker serves without auth, `GET /cluster-api/v1/printers` and `/cluster-api/v1/print_jobs`
 *   (Network/ClusterApiClient.py:71-87), read as Cura reads them (Models/Http/ClusterPrinterStatus.py,
 *   ClusterPrintJobStatus.py, UltimakerNetworkedPrinterOutputDevice.py, cura/PrinterOutput/Models/PrintJobOutputModel.py).
 *   No temperatures: neither slicer reads any over the LAN.
 *
 * Nothing here has been run against a printer.
 */
object UltiMakerApi {
    /**
     * Whether sending a print job to an UltiMaker from Nozzle It All has been confirmed on a real printer. An UltiMaker has
     * no "upload only": `POST /api/v1/print_job` hands the printer a job to print (Orca offers only "upload and print" for it,
     * UltiMaker.hpp:35), so while this is false NOTHING is sent: the send is refused before any request with
     * [startNotVerified]. Flip only with a real run recorded (docs/upstream/PROVENANCE.md), as CrealityCfs.START_VERIFIED.
     */
    const val START_VERIFIED = false

    fun startNotVerified(remoteName: String): String =
        "Nothing was sent: an UltiMaker prints every job it is sent (there is no upload-only), and starting a print on an UltiMaker " +
            "printer from Nozzle It All isn't verified on real hardware yet. Start it from the printer's screen."

    /** Throws the refusal while [verified] (default [START_VERIFIED]) is false. Called before any request of a send. */
    fun requireStartVerified(remoteName: String, verified: Boolean = START_VERIFIED) {
        if (!verified) throw ApiFailure(startNotVerified(remoteName))
    }

    /** The name the printer shows on its "allow access?" dialog (UltiMaker.cpp:236-249 sends "OrcaSlicer"). */
    const val APPLICATION = "Nozzle It All"

    const val MISSING_CREDENTIALS = "Pair Nozzle It All with this UltiMaker first: tap Request access in Edit printer, then allow it on the printer's screen."

    // ---- pairing (UltiMaker.cpp) --------------------------------------------------------------------------------------

    /** `system/variant`'s reply is a JSON string; upstream accepts "Ultimaker 3..." and exactly "Ultimaker S5" (UltiMaker.cpp:77). */
    fun isUpstreamVariant(body: String): Boolean = body.startsWith("\"Ultimaker 3") || body == "\"Ultimaker S5\""

    /** The variant as text ("Ultimaker S5"), or null when the reply isn't a JSON string. */
    fun variantName(body: String): String? = body.trim().takeIf { it.length >= 2 && it.startsWith("\"") && it.endsWith("\"") }?.let {
        try { JSONArray("[$it]").optString(0).takeIf(String::isNotBlank) } catch (_: org.json.JSONException) { null }
    }

    /** `auth/request`'s multipart text fields (UltiMaker.cpp:236-249): `application` and `user`. */
    fun authRequestFields(application: String = APPLICATION): LinkedHashMap<String, String> = linkedMapOf("application" to application, "user" to application)

    data class Credentials(val id: String, val key: String)

    /** `auth/request`'s reply `{"id": ..., "key": ...}` (UltiMaker.cpp:228-233,268-277): null unless both are non-empty. */
    fun parseCredentials(body: String): Credentials? {
        val json = try { JSONObject(body.trim()) } catch (_: org.json.JSONException) { return null }
        val id = (json.opt("id") as? String)?.trim().orEmpty()
        val key = (json.opt("key") as? String)?.trim().orEmpty()
        return if (id.isEmpty() || key.isEmpty()) null else Credentials(id, key)
    }

    enum class AuthStatus { WAITING, AUTHORIZED, UNAUTHORIZED, UNKNOWN }

    /** `auth/check/<id>`'s `{"message": ...}` (UltiMaker.cpp:197-199): "unknown" is still waiting for the person's answer. */
    fun authStatus(body: String): AuthStatus {
        val message = try { JSONObject(body.trim()).opt("message") as? String } catch (_: org.json.JSONException) { null }
        return when (message) {
            "unknown" -> AuthStatus.WAITING
            "authorized" -> AuthStatus.AUTHORIZED
            "unauthorized" -> AuthStatus.UNAUTHORIZED
            else -> AuthStatus.UNKNOWN
        }
    }

    /** `auth/verify`'s success reply: a body starting `"ok"`, or `{"message": "ok"}` (UltiMaker.cpp:152). */
    fun isVerified(body: String): Boolean {
        if (body.startsWith("\"ok\"")) return true
        return try { JSONObject(body.trim()).opt("message") == "ok" } catch (_: org.json.JSONException) { false }
    }

    /** Upstream's test_auth messages (UltiMaker.cpp:306-340), for the stored credentials' state. Null when they work. */
    fun authProblem(status: AuthStatus, verified: Boolean): String? = when {
        status == AuthStatus.WAITING -> "Waiting for approval: allow Nozzle It All on the printer's screen."
        status == AuthStatus.AUTHORIZED && verified -> null
        else -> "The printer doesn't accept these credentials. Request access again."
    }

    // ---- the print file (UltiMaker.cpp:345-435) -----------------------------------------------------------------------

    private val TIME_HMS = Regex("""^; estimated printing time \(normal mode\) =\s*([+-]?\d+)h\s*([+-]?\d+)m\s*([+-]?\d+)s""")
    private val TIME_MS = Regex("""^; estimated printing time \(normal mode\) =\s*([+-]?\d+)m\s*([+-]?\d+)s""")

    /**
     * getPrintTime (UltiMaker.cpp:345-383): the first `; estimated printing time (normal mode) = Hh Mm Ss` (or `Mm Ss`) line
     * in seconds; 0 when there is none. Days aren't read (upstream's sscanf patterns have none).
     */
    fun printTimeSeconds(lines: Sequence<String>): Long {
        for (line in lines) {
            if (!line.startsWith("; estimated printing time (normal mode) =")) continue
            TIME_HMS.find(line)?.let { m -> return m.groupValues[1].toLong() * 3600 + m.groupValues[2].toLong() * 60 + m.groupValues[3].toLong() }
            TIME_MS.find(line)?.let { m -> return m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong() }
        }
        return 0
    }

    /**
     * makeGriffinCompatible (UltiMaker.cpp:386-435): from `;START_OF_HEADER` on, colons in the `; generated by OrcaSlicer`
     * line become dashes (more than one colon on a line crashes UltiMakers, upstream says) and `;PRINT.TIME:` gets
     * [printSeconds]; every line ends in `\n`. Lines before the header are dropped, as upstream drops them. Returns false
     * when there is no `;START_OF_HEADER` at all: upstream then uploads an EMPTY file; here the send is refused instead.
     */
    fun writeGriffinCompatible(input: BufferedReader, output: Writer, printSeconds: Long): Boolean {
        var writing = false
        input.lineSequence().forEach { line ->
            if (writing) {
                when {
                    line.startsWith(";PRINT.TIME:") -> output.write(";PRINT.TIME:$printSeconds\n")
                    line.startsWith("; generated by OrcaSlicer") -> output.write(line.replace(':', '-') + "\n")
                    else -> output.write(line + "\n")
                }
            } else if (line.startsWith(";START_OF_HEADER")) { writing = true; output.write(line + "\n") }
        }
        return writing
    }

    const val NO_GRIFFIN_HEADER = "Nothing was sent: this file has no UltiMaker (Griffin) header. Slice it with an UltiMaker printer profile."

    // ---- status (Cura UM3NetworkPrinting) -----------------------------------------------------------------------------

    /**
     * One printer's status and its active job from the cluster API's `printers` and `print_jobs` lists. The printer is the
     * first one listed (a single printer serves a one-printer "cluster"). Its job is the one whose `printer_uuid` or
     * `assigned_to` is the printer's `uuid` and whose `status` isn't queued/error (UltimakerNetworkedPrinterOutputDevice.py:
     * 46,110-116,348-404); progress is `time_elapsed / max(time_total, 1)`, at most 1 (PrintJobOutputModel.py:148-150).
     */
    fun snapshot(printers: JSONArray, jobs: JSONArray): PrinterSnapshot {
        val printer = printers.optJSONObject(0) ?: return PrinterSnapshot(false, "unknown")
        val uuid = printer.optString("uuid")
        val enabled = printer.opt("enabled") != false
        val printerStatus = if (enabled) (printer.opt("status") as? String ?: "unknown") else "disabled"
        val job = (0 until minOf(jobs.length(), 500)).mapNotNull { jobs.optJSONObject(it) }.firstOrNull { j ->
            uuid.isNotEmpty() && (j.optString("printer_uuid") == uuid || j.optString("assigned_to") == uuid) &&
                (j.opt("status") as? String) !in QUEUED_JOB_STATES
        }
        val jobStatus = job?.opt("status") as? String
        val total = job?.let { number(it, "time_total") }
        val elapsed = job?.let { number(it, "time_elapsed") }
        val state = stateName(printerStatus, jobStatus, cleanupAfterAbort = total != null && elapsed != null && total > elapsed)
        val progress = if (total != null && elapsed != null) (elapsed / maxOf(total, 1.0)).coerceIn(0.0, 1.0).toFloat() else 0f
        return PrinterSnapshot(state != "unknown", state, (job?.opt("name") as? String).orEmpty(), progress, printDuration = elapsed)
    }

    /** Cura's queued job states (UltimakerNetworkedPrinterOutputDevice.py:46): not on a printer yet. */
    val QUEUED_JOB_STATES = setOf("queued", "error")

    /**
     * The printer's `status` (with its job's, when it has one) in this app's Moonraker vocabulary. Names from Cura's monitor
     * (MonitorPrinterCard.qml:346-358, MonitorPrintJobProgressBar.qml:63-104, PrintJobOutputModel.py:157-167): printer
     * idle / pre_print / printing / unreachable / maintenance / error; job pausing, paused, resuming, wait_cleanup,
     * finished, aborted*, failed*. A job in wait_cleanup is finished, or aborted when its elapsed time is short of its
     * total ([cleanupAfterAbort]; Cura's own workaround, MonitorPrintJobProgressBar.qml:65-72). Anything else reads "unknown".
     */
    fun stateName(printerStatus: String, jobStatus: String?, cleanupAfterAbort: Boolean = false): String {
        when {
            jobStatus == "paused" || jobStatus == "pausing" -> return "paused"
            jobStatus == "resuming" -> return "printing"
            jobStatus == "finished" -> return "complete"
            jobStatus == "wait_cleanup" -> return if (cleanupAfterAbort) "cancelled" else "complete"
            jobStatus != null && jobStatus.startsWith("aborted") -> return "cancelled"
            jobStatus != null && jobStatus.startsWith("failed") -> return "error"
        }
        return when (printerStatus) {
            "idle" -> "standby"
            "printing", "pre_print" -> "printing"
            "maintenance" -> "busy"
            "error" -> "error"
            else -> "unknown"
        }
    }

    private fun number(o: JSONObject, k: String): Double? = when (val v = o.opt(k)) { is Number -> v.toDouble(); is String -> v.trim().toDoubleOrNull(); else -> null }?.takeIf { it.isFinite() && it >= 0 }

    /** The job name sent: the base name only. */
    fun remoteName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\').trim().ifBlank { "print.gcode" }.take(120)
}

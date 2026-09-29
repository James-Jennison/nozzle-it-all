package net.jamesjennison.klippercompanion

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A Duet board on RepRapFirmware (PrinterKind.DUET): the two HTTP APIs upstream OrcaSlicer's Duet print host speaks, as
 * pure request builders and parsers, no I/O. DuetPrinterService does the talking.
 *
 * Source (docs/upstream/PROVENANCE.md P-0035): upstream OrcaSlicer 5298e49d `src/slic3r/Utils/Duet.cpp` / `.hpp`, compared
 * with SuperSlicer's copy (PrusaSlicer lineage, same file). Nothing here has been run against a printer.
 *
 * - Which API: `GET <base>rr_connect?password=<pw or "reprap">&time=<local time>` first; a reply means the standalone
 *   RepRapFirmware API ([ConnectionType.RRF]) and its `err` field says whether the password was right. If that request
 *   fails (an HTTP error or no answer), `GET <base>machine/status`: a reply means Duet Software Framework on a single-board
 *   computer ([ConnectionType.DSF]); a failure there too is the error (Duet.cpp:124-165).
 * - Upload: RRF `POST <base>rr_upload?name=0:/gcodes/<name>&time=...` with the file as the raw body, success when the
 *   reply's `err` is 0; DSF `PUT <base>machine/file/gcodes/<name>` with the raw file, success only on HTTP 201
 *   (Duet.cpp:57-122, 184-198). RRF sessions end with `GET <base>rr_disconnect` (Duet.cpp:167-182); DSF has none.
 * - Start: RRF `GET <base>rr_gcode?gcode=M32 "0:/gcodes/<name>"`, DSF `POST <base>machine/code` with `M32 "0:/gcodes/<name>"`
 *   as the body (Duet.cpp:239-275). Gated: see [START_VERIFIED].
 *
 * Upstream reads no printer state at all (no `rr_model`, `rr_status` or `/machine/status` parsing: the DSF status request
 * is only a reachability probe), so neither does this: [probeSnapshot] says "reachable, state unknown".
 */
object DuetRrf {
    /**
     * Whether starting a print from Nozzle It All (`M32` through `rr_gcode` or `/machine/code`) has been confirmed on a real
     * Duet. Until then the service uploads the file (upload never starts anything on a Duet: upstream starts with a
     * separate M32) and refuses the start with [startNotVerified]. Flip only with a real run recorded
     * (docs/upstream/PROVENANCE.md), as CrealityCfs.START_VERIFIED.
     */
    const val START_VERIFIED = false

    /** [uploaded]: the file was just uploaded (the send-and-print path), rather than already on the printer. */
    fun startNotVerified(remoteName: String, uploaded: Boolean = true): String =
        (if (uploaded) "Uploaded $remoteName to the printer but did not start it" else "Did not start $remoteName") +
            ": starting a print on a Duet printer from Nozzle It All isn't verified on real hardware yet. Start it from the printer's screen."

    /** Throws the refusal while [verified] (default [START_VERIFIED]) is false. Called before any M32 is built or sent. */
    fun requireStartVerified(remoteName: String, verified: Boolean = START_VERIFIED, uploaded: Boolean = true) {
        if (!verified) throw ApiFailure(startNotVerified(remoteName, uploaded))
    }

    enum class ConnectionType { RRF, DSF }

    /** Duet.cpp:208: a blank password is sent as RepRapFirmware's default, `reprap`. */
    const val DEFAULT_PASSWORD = "reprap"

    /** `time=%Y-%m-%dT%H:%M:%S` in local time (Duet.cpp:226-237). Returned without the `time=` key. */
    fun timestamp(now: LocalDateTime = LocalDateTime.now()): String = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

    /**
     * libcurl's `curl_easy_escape`, which Orca's Http::url_encode wraps: every byte of the UTF-8 form except ASCII letters,
     * digits and `-._~` as `%XX` (upper-case hex). Used for the password and file names (Duet.cpp:190,194,208,253).
     */
    fun curlEscape(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { b ->
            val c = b.toInt() and 0xFF
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code) append(c.toChar())
            else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
        }
    }

    /** The already-encoded query of `rr_connect` (Duet.cpp:206-209). The password rides in the URL because RepRapFirmware
     *  takes it nowhere else; callers must never log this query or put it in an error message. */
    fun connectQuery(password: String, time: String): String = "password=${curlEscape(password.ifEmpty { DEFAULT_PASSWORD })}&time=$time"

    /** The already-encoded query of `rr_upload` (Duet.cpp:193-196): `name=0:/gcodes/<escaped name>&time=...`. */
    fun uploadQuery(fileName: String, time: String): String = "name=0:/gcodes/${curlEscape(fileName)}&time=$time"

    /** DSF's upload path below the base URL, already encoded (Duet.cpp:189-191). */
    fun dsfUploadPath(fileName: String): String = "machine/file/gcodes/${curlEscape(fileName)}"

    /** The start G-code, as DSF's `/machine/code` body (Duet.cpp:258-261). [simulate] is upstream's M37 simulation mode. */
    fun startCode(fileName: String, simulate: Boolean = false): String =
        if (simulate) "M37 P\"0:/gcodes/$fileName\"" else "M32 \"0:/gcodes/$fileName\""

    /** RRF's `rr_gcode` query, already encoded (Duet.cpp:249-253): the space is `%20`, the quotes and `0:/gcodes/` literal. */
    fun rrGcodeQuery(fileName: String, simulate: Boolean = false): String =
        if (simulate) "gcode=M37%20P\"0:/gcodes/${curlEscape(fileName)}\"" else "gcode=M32%20\"0:/gcodes/${curlEscape(fileName)}\""

    /**
     * `err` from an RRF reply, 0 when absent (Duet.cpp:277-284, `root.get<int>("err", 0)`). Null when the body isn't a JSON
     * object: Orca's ptree parse would throw there, so it is never read as success.
     */
    fun errCode(body: String): Int? {
        val json = try { JSONObject(body.trim()) } catch (_: org.json.JSONException) { return null }
        return when (val v = json.opt("err")) {
            null -> 0
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        }
    }

    /** What `rr_connect`'s reply means (Duet.cpp:145-159): null for a good session, otherwise the reason. */
    fun connectError(body: String): String? = when (errCode(body)) {
        0 -> null
        1 -> "The Duet refused the password. Check the password in Edit printer (blank means RepRapFirmware's default)."
        2 -> "The Duet couldn't open another connection (no free session). Close Duet Web Control elsewhere and try again."
        else -> "The Duet sent an unexpected reply to the connection request."
    }

    /** Upload success (Duet.cpp:85): DSF only on HTTP 201; RRF when the reply's `err` is 0. */
    fun uploadSucceeded(type: ConnectionType, httpCode: Int, body: String): Boolean =
        if (type == ConnectionType.DSF) httpCode == 201 else errCode(body) == 0

    /**
     * A reachable Duet: upstream reads no state (see the class comment), so the state is honestly "unknown". `ready` means
     * only "the board answered"; PrinterCapabilities.readsPrinterState is false for DUET, and sendAllowedStates lets an
     * upload (never a start) go out in the unknown state only while [START_VERIFIED] is false.
     */
    fun probeSnapshot(): PrinterSnapshot = PrinterSnapshot(true, "unknown")

    /** The file name sent to the printer: the base name only; the path is always `0:/gcodes/`. */
    fun remoteName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\').trim().ifBlank { "print.gcode" }.take(120)
}

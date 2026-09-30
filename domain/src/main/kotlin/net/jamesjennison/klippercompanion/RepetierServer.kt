package net.jamesjennison.klippercompanion

import org.json.JSONObject

/**
 * Repetier-Server (PrinterKind.REPETIER): pure request builders and parsers, no I/O. RepetierPrinterService does the talking.
 *
 * Source (docs/upstream/PROVENANCE.md P-0035): upstream OrcaSlicer 5298e49d `src/slic3r/Utils/Repetier.cpp` / `.hpp`,
 * compared with SuperSlicer's copy (PrusaSlicer lineage). Nothing here has been run against a server.
 *
 * - Every request carries the server's API key as the `X-Api-Key` header (Repetier.cpp:179-186); URLs are the address plus
 *   a path (make_url, 188-199).
 * - `GET printer/info` identifies the server: `software` must be "Repetier-Server", or, without `software`, `name` must
 *   start with "Repetier" (validate_repetier, 40-52; test, 56-99).
 * - `GET printer/list` lists the server's printers; each `data[].slug` names one; an `error` field is an error (243-289).
 * - Upload: `POST printer/model/<slug>`, multipart `a=upload` and the file as `filename` (129-155): stored in the
 *   server's model library, NOT printed. `POST printer/job/<slug>` with `name` and `autostart=true` as well uploads and
 *   starts (129-131,149-152): gated, see [START_VERIFIED].
 *
 * Upstream reads no printer state from Repetier-Server (printer/list is read only for slugs), so neither does this:
 * [probeSnapshot] says "reachable, state unknown".
 */
object RepetierServer {
    /**
     * Whether starting a print through Repetier-Server (`printer/job/<slug>` with `autostart=true`) has been confirmed on a
     * real server and printer. Until then a send only stores the file in the server's model library (`printer/model/<slug>`,
     * which never prints) and the start is refused with [startNotVerified]. Flip only with a real run recorded
     * (docs/upstream/PROVENANCE.md), as CrealityCfs.START_VERIFIED.
     */
    const val START_VERIFIED = false

    /** [uploaded]: the file was just uploaded (the send-and-print path), rather than already on the server. */
    fun startNotVerified(remoteName: String, uploaded: Boolean = true): String =
        (if (uploaded) "Uploaded $remoteName to the printer but did not start it" else "Did not start $remoteName") +
            ": starting a print on a Repetier-Server printer from Nozzle It All isn't verified on real hardware yet. Start it from the printer's screen."

    /** Throws the refusal while [verified] (default [START_VERIFIED]) is false. Called before any job upload is built. */
    fun requireStartVerified(remoteName: String, verified: Boolean = START_VERIFIED, uploaded: Boolean = true) {
        if (!verified) throw ApiFailure(startNotVerified(remoteName, uploaded))
    }

    const val API_KEY_HEADER = "X-Api-Key"
    const val MISSING_API_KEY = "Enter the Repetier-Server API key in Edit printer."

    /** validate_repetier (Repetier.cpp:40-52) over `printer/info`'s reply: null when it is Repetier-Server, else the reason. */
    fun infoProblem(body: String): String? {
        val json = try { JSONObject(body.trim()) } catch (_: org.json.JSONException) { return "Could not parse the server's response." }
        val name = json.opt("name") as? String
        val software = json.opt("software") as? String
        val ok = if (software != null) software == "Repetier-Server" else name?.startsWith("Repetier") ?: true
        return if (ok) null else "This address isn't a Repetier-Server (it reports ${(software ?: name ?: "Repetier").take(80)})."
    }

    /** `printer/list`'s reply (Repetier.cpp:243-289): the printers' slugs; an `error` field or a missing `data` list throws. */
    fun printerSlugs(body: String): List<String> {
        val json = try { JSONObject(body.trim()) } catch (_: org.json.JSONException) { throw ApiFailure("Could not parse the server's printer list.") }
        (json.opt("error") as? String)?.let { throw ApiFailure("Repetier-Server: ${it.take(200)}") }
        val data = json.optJSONArray("data") ?: throw ApiFailure("The server's printer list has no printers.")
        return (0 until minOf(data.length(), 200)).mapNotNull { data.optJSONObject(it)?.opt("slug") as? String }.filter { it.isNotBlank() }
    }

    /**
     * Which printer to use: the saved slug when the server lists it; with none saved, the server's only printer. Upstream
     * has the person pick one from the list (get_printers); a server with several and no saved slug is an error here.
     */
    fun chooseSlug(saved: String, slugs: List<String>): String {
        val wanted = saved.trim()
        if (wanted.isNotEmpty()) return wanted.takeIf { it in slugs } ?: throw ApiFailure("Repetier-Server has no printer \"${wanted.take(80)}\" (it lists ${slugs.joinToString().ifBlank { "none" }.take(200)}).")
        return slugs.singleOrNull() ?: throw ApiFailure(
            if (slugs.isEmpty()) "Repetier-Server lists no printers." else "Repetier-Server has several printers (${slugs.joinToString().take(200)}): enter one in Edit printer.")
    }

    /** The upload-only form fields before the file (Repetier.cpp:149-155 without StartPrint): `a=upload`. */
    fun modelUploadFields(): LinkedHashMap<String, String> = linkedMapOf("a" to "upload")

    /** The upload-and-start form fields before the file (Repetier.cpp:149-155 with StartPrint). Only behind [requireStartVerified]. */
    fun jobUploadFields(name: String): LinkedHashMap<String, String> = linkedMapOf("name" to name, "autostart" to "true", "a" to "upload")

    /** The multipart field the file goes in (Repetier.cpp:155). */
    const val FILE_FIELD = "filename"

    /** A reachable Repetier-Server: upstream reads no printer state (see the class comment), so the state is "unknown". */
    fun probeSnapshot(): PrinterSnapshot = PrinterSnapshot(true, "unknown")

    /** The file name sent: the base name only (upload_path.filename(), Repetier.cpp:118). */
    fun remoteName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\').trim().ifBlank { "print.gcode" }.take(120)
}

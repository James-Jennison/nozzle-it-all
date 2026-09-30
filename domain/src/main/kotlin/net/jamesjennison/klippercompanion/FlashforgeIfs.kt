package net.jamesjennison.klippercompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * Flashforge's local HTTP API on port 8898 (PrinterKind.FLASHFORGE; the AD5X and its Intelligent Filament System, IFS):
 * pure request builders and parsers, no I/O. FlashforgePrinterService does the talking.
 *
 * Source (docs/upstream/PROVENANCE.md P-0033): upstream OrcaSlicer 5298e49d `src/slic3r/Utils/Flashforge.cpp` and `.hpp`,
 * and the IFS mapping in `src/slic3r/GUI/PrintHostDialogs.cpp` (FlashforgePrintHostSendDialog). Nothing here has been run
 * against a printer.
 *
 * - Every call is `POST http://<ip>:8898/<endpoint>`. Auth is the printer's serial number plus its LAN access code
 *   ("check code"), in the JSON body (`/detail`, Flashforge.cpp:494-498) or as headers (`/uploadGcode`, 557-618).
 * - A reply with a nonzero `code` (else `err`) is an error, its text in `message` (else `msg`) (Flashforge.cpp:152-183).
 * - `/uploadGcode` carries its options as header strings; `printNow: true` starts the print when the upload completes.
 *
 * Starting a print is gated: see [START_VERIFIED].
 */
object FlashforgeIfs {
    /**
     * Whether starting a print from Nozzle It All (`/uploadGcode` with `printNow: true`, with or without an IFS mapping) has
     * been confirmed on a real Flashforge printer. Until then the service uploads with `printNow: false` and refuses the
     * start with [startNotVerified]; the headers it would send are built and unit-tested (Orca's wire format), but a wrong
     * start heats and moves the printer, and a wrong mapping feeds the wrong spool. Flip only with a real AD5X run recorded
     * (docs/upstream/PROVENANCE.md), as BambuAms.AMS_PRINT_VERIFIED.
     */
    const val START_VERIFIED = false

    const val PORT = 8898

    /** [uploaded]: the file was just uploaded (the send-and-print path), rather than already on the printer. */
    fun startNotVerified(remoteName: String, uploaded: Boolean = true): String =
        (if (uploaded) "Uploaded $remoteName to the printer but did not start it" else "Did not start $remoteName") +
            ": starting a print on a Flashforge printer from Nozzle It All isn't verified on real hardware yet. Start it from the printer's screen."

    /** Throws the refusal while [verified] (default [START_VERIFIED]) is false. Called before a `printNow: true` upload is built. */
    fun requireStartVerified(remoteName: String, verified: Boolean = START_VERIFIED, uploaded: Boolean = true) {
        if (!verified) throw ApiFailure(startNotVerified(remoteName, uploaded))
    }

    const val MISSING_CREDENTIALS = "Enter this printer's serial number and access code (both on the printer's own network settings screen). " +
        "Flashforge's local API needs both."

    /** `/detail`'s request body (Flashforge.cpp:494-498). The check code is a credential: never log it or put it in a URL. */
    fun authBody(serial: String, checkCode: String): JSONObject = JSONObject().put("serialNumber", serial).put("checkCode", checkCode)

    /** Orca's reply rule (validate_local_api_response): the error text, or null when the reply is not an error. */
    fun apiError(reply: JSONObject): String? {
        val code = intOf(reply.opt("code")) ?: intOf(reply.opt("err")) ?: return null
        if (code == 0) return null
        val message = (reply.opt("message") as? String)?.takeIf { it.isNotBlank() } ?: (reply.opt("msg") as? String)?.takeIf { it.isNotBlank() } ?: "Request failed"
        return "The printer refused the request (Flashforge error $code: ${message.take(200)})."
    }

    // ---- IFS slots ----------------------------------------------------------------------------------------------------

    /** One IFS slot. [slotId] is 1-based, as the API reports and expects it (Flashforge.hpp:18). */
    data class Slot(val slotId: Int, val hasFilament: Boolean, val materialName: String, val materialColor: String?) {
        /** A stable physical number: slot n is tool n-1 (T n-1 by default). */
        val tool: Int get() = slotId - 1
    }

    data class Station(val present: Boolean, val slots: List<Slot>)

    /**
     * `/detail`'s reply to its IFS, with Orca's rules (fetch_material_slots, Flashforge.cpp:500-555): the object may be wrapped
     * as `{"detail":{...}}`; keys may be camelCase or PascalCase; a station is present when `hasMatlStation` is nonzero,
     * `slotCnt` > 0 or `slotInfos` isn't empty; a slot without `slotId` is its position + 1.
     */
    fun parseStation(reply: JSONObject): Station {
        val detail = detailOf(reply)
        val station = detail.optJSONObject("matlStationInfo") ?: detail.optJSONObject("MatlStationInfo")
        val infos = station?.optJSONArray("slotInfos") ?: station?.optJSONArray("SlotInfos") ?: JSONArray()
        val flag = intOf(detail.opt("hasMatlStation")) ?: intOf(detail.opt("HasMatlStation"))
        val count = intOf(station?.opt("slotCnt")) ?: intOf(station?.opt("SlotCnt"))
        val slots = (0 until minOf(infos.length(), 16)).mapNotNull { i ->
            val s = infos.optJSONObject(i) ?: return@mapNotNull null
            val id = intOf(s.opt("slotId"))?.takeIf { it in 1..16 } ?: (i + 1)
            Slot(id, boolOf(s.opt("hasFilament")) ?: false, (s.opt("materialName") as? String)?.trim().orEmpty(),
                FilamentLanes.normalizeColor(s.opt("materialColor") as? String))
        }.distinctBy { it.slotId }
        return Station((flag ?: 0) != 0 || (count ?: 0) > 0 || slots.isNotEmpty(), slots)
    }

    fun filamentSlots(station: Station): List<FilamentSlot> = station.slots.sortedBy { it.slotId }.map { s ->
        val loaded = s.hasFilament
        FilamentSlot(s.tool, if (loaded) s.materialName.uppercase().ifBlank { "LOADED (TYPE NOT REPORTED)" } else null,
            if (loaded) s.materialColor else null, name = "IFS slot ${s.slotId}")
    }

    // ---- status -------------------------------------------------------------------------------------------------------

    /**
     * `/detail`'s printer state. These field names are NOT on disk (Orca reads only the IFS); they are the ones Flashforge's
     * 5M-series local API is generally reported to use (`status`, `printProgress` 0..1, `printFileName`, `printDuration`,
     * `printLayer`, `targetPrintLayer`, `rightTemp`/`rightTargetTemp` for the nozzle, `platTemp`/`platTargetTemp` for the
     * bed). Unverified: check against a real reply before relying on any of them. Unknown values read as "unknown".
     */
    fun snapshot(reply: JSONObject): PrinterSnapshot {
        val d = detailOf(reply)
        val state = stateName((d.opt("status") as? String).orEmpty())
        val raw = number(d, "printProgress") ?: 0.0
        val progress = (if (raw > 1.0) raw / 100.0 else raw).coerceIn(0.0, 1.0).toFloat()
        return PrinterSnapshot(state != "unknown", state, (d.opt("printFileName") as? String)?.substringAfterLast('/').orEmpty(), progress,
            number(d, "rightTemp") ?: number(d, "leftTemp"), number(d, "rightTargetTemp") ?: number(d, "leftTargetTemp"),
            number(d, "platTemp"), number(d, "platTargetTemp"), number(d, "printDuration"),
            number(d, "printLayer")?.toInt()?.takeIf { it > 0 }, number(d, "targetPrintLayer")?.toInt()?.takeIf { it > 0 }, "extruder")
    }

    /** `status` in this app's Moonraker vocabulary. Unverified names (see [snapshot]). */
    fun stateName(status: String): String = when (status.trim().lowercase()) {
        "ready", "idle" -> "standby"
        "printing", "heating" -> "printing"
        "pausing", "paused", "pause" -> "paused"
        "completed", "complete", "finished" -> "complete"
        "cancel", "canceled", "cancelled", "stopped" -> "cancelled"
        "error" -> "error"
        "busy", "calibrate_doing" -> "busy"
        else -> "unknown"
    }

    fun isIdle(snapshot: PrinterSnapshot): Boolean = snapshot.state in setOf("standby", "complete", "cancelled")

    // ---- print start (gated) ------------------------------------------------------------------------------------------

    /**
     * Orca's material family rule (FlashforgePrintHostSendDialog::normalize_material, PrintHostDialogs.cpp:1090-1122):
     * upper case, letters and digits only; SILK, PLA-CF, PETG-CF, any PLA, ABS and ASA as ABS, PETG, and TPU/TPE/FLEX as TPU.
     */
    fun normalizeMaterial(material: String?): String {
        val n = material.orEmpty().uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
        return when {
            n.isEmpty() -> ""
            "SILK" in n -> "SILK"
            "PLA" in n && "CF" in n -> "PLACF"
            "PETG" in n && "CF" in n -> "PETGCF"
            "PLA" in n -> "PLA"
            "ABS" in n || "ASA" in n -> "ABS"
            "PETG" in n -> "PETG"
            "TPU" in n || "TPE" in n || "FLEX" in n -> "TPU"
            else -> n
        }
    }

    /** One `materialMappings` entry (PrintHostDialogs.cpp:845-882). [toolId] is the 0-based file tool, [slotId] the 1-based slot. */
    data class Mapping(val toolId: Int, val slotId: Int, val materialName: String, val toolMaterialColor: String, val slotMaterialColor: String)

    /**
     * [toolheadMap] (entry T is the [Slot.tool] feeding file tool T, -1 unmapped) as `materialMappings`, with Orca's checks
     * before sending (PrintHostDialogs.cpp:1057-1087): every mapped slot exists and has filament, and its material family
     * matches the file's (when the file declares one). Throws IllegalArgumentException otherwise; nothing is sent then.
     */
    fun mappings(toolheadMap: List<Int>, slots: List<Slot>, fileFilaments: List<SlicedFileFilaments.Filament> = emptyList()): List<Mapping> {
        require(toolheadMap.size <= 16) { "Too many materials for one print." }
        return toolheadMap.withIndex().filter { it.value >= 0 }.map { (t, tool) ->
            val slot = slots.firstOrNull { it.tool == tool } ?: throw IllegalArgumentException("Material ${t + 1} is mapped to IFS slot ${tool + 1}, which this printer doesn't report.")
            require(slot.hasFilament) { "Material ${t + 1} is mapped to IFS slot ${slot.slotId}, which has nothing loaded." }
            val file = fileFilaments.firstOrNull { it.tool == t }
            val want = normalizeMaterial(file?.type)
            require(want.isEmpty() || normalizeMaterial(slot.materialName) == want) {
                "Material ${t + 1} is ${file?.type} but IFS slot ${slot.slotId} holds ${slot.materialName.ifBlank { "an unknown material" }}."
            }
            Mapping(t, slot.slotId, slot.materialName, file?.colorHex ?: "#FFFFFF", slot.materialColor ?: "#FFFFFF")
        }
    }

    /**
     * The `materialMappings` JSON, before base64. Keys in the order Orca's nlohmann::json dump writes them (an ordered
     * std::map, so alphabetical); order doesn't change the meaning.
     */
    fun mappingsJson(mappings: List<Mapping>): String = mappings.joinToString(",", "[", "]") { m ->
        """{"materialName":${JSONObject.quote(m.materialName)},"slotId":${m.slotId},"slotMaterialColor":${JSONObject.quote(m.slotMaterialColor)},""" +
            """"toolId":${m.toolId},"toolMaterialColor":${JSONObject.quote(m.toolMaterialColor)}}"""
    }

    /**
     * `/uploadGcode`'s headers, in Orca's order (Flashforge.cpp:585-596). [printNow] true starts the print (heat and motion)
     * when the upload completes; false only stores the file. An empty [mappings] turns the IFS off, as Orca does when its
     * "use IFS" box is clear (`useMatlStation` false, `gcodeToolCnt` 0, `materialMappings` base64 of `[]`).
     */
    fun uploadHeaders(serial: String, checkCode: String, fileSize: Long, printNow: Boolean, mappings: List<Mapping> = emptyList(),
                      levelingBeforePrint: Boolean = false, timeLapseVideo: Boolean = false): LinkedHashMap<String, String> = linkedMapOf(
        "serialNumber" to serial,
        "checkCode" to checkCode,
        "fileSize" to fileSize.toString(),
        "printNow" to printNow.toString(),
        "levelingBeforePrint" to levelingBeforePrint.toString(),
        "flowCalibration" to "false",
        "firstLayerInspection" to "false",
        "timeLapseVideo" to timeLapseVideo.toString(),
        "useMatlStation" to mappings.isNotEmpty().toString(),
        "gcodeToolCnt" to mappings.size.toString(),
        "materialMappings" to java.util.Base64.getEncoder().encodeToString(mappingsJson(mappings).toByteArray(Charsets.UTF_8)),
    )

    /** The multipart file name: `[A-Za-z0-9._-]`, anything else `_` (sanitize_flashforge_filename, Flashforge.cpp:185-202). */
    fun safeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "print.gcode" }
        return base.map { if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-') it else '_' }.joinToString("").take(120)
    }

    // ---- discovery ----------------------------------------------------------------------------------------------------

    const val DISCOVERY_PORT = 48899
    /** Orca sends from, and listens on, this port (Flashforge.cpp:48-49). */
    const val DISCOVERY_LISTEN_PORT = 18007
    /** The 20-byte discovery probe (Flashforge.cpp:51-55). */
    val DISCOVERY_MESSAGE: ByteArray = byteArrayOf(0x77, 0x77, 0x77, 0x2e, 0x75, 0x73, 0x72, 0x22, 0x65, 0x36, 0xc0.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0)

    data class Discovered(val name: String, val serial: String)

    /** A discovery reply: at least 0xC4 bytes, the name NUL-terminated at 0 and the serial at 0x92, 32 bytes each (Flashforge.cpp:67-76). */
    fun parseDiscovery(reply: ByteArray, length: Int = reply.size): Discovered? {
        if (length < 0xC4 || length > reply.size) return null
        fun field(at: Int): String {
            val end = (at until at + 32).firstOrNull { reply[it] == 0.toByte() } ?: (at + 32)
            return String(reply, at, end - at, Charsets.US_ASCII).trim().filter { it.code in 32..126 }
        }
        val name = field(0); val serial = field(0x92)
        return if (name.isEmpty() && serial.isEmpty()) null else Discovered(name.take(80), serial.take(40))
    }

    private fun detailOf(reply: JSONObject): JSONObject = reply.optJSONObject("detail") ?: reply

    private fun intOf(v: Any?): Int? = when (v) {
        is Boolean -> if (v) 1 else 0
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    private fun boolOf(v: Any?): Boolean? = when (v) { is Boolean -> v; is Number -> v.toInt() != 0; is String -> v.trim().equals("true", true) || v.trim() == "1"; else -> null }

    private fun number(o: JSONObject, k: String): Double? = when (val v = o.opt(k)) { is Number -> v.toDouble(); is String -> v.trim().toDoubleOrNull(); else -> null }?.takeIf { it.isFinite() }
}

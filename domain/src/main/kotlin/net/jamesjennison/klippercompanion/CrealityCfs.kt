package net.jamesjennison.klippercompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * The LAN protocol of Creality's K1 / K2 / Hi family (PrinterKind.CREALITY) and its Creality Filament System (CFS, CFS-C):
 * pure message builders and parsers, no I/O. CrealityPrinterService does the talking.
 *
 * Sources (docs/upstream/PROVENANCE.md P-0033): upstream OrcaSlicer 5298e49d `src/slic3r/Utils/CrealityPrint.cpp`,
 * `CrealityPrintAgent.cpp`, `CrealityHostDiscovery.cpp` (open source; its boxsInfo schema comment says it was checked
 * against a K2 Combo F021 on firmware 1.1.260206), and CrealityPrint 59ae8cb (`print_manage/Device/LanDeviceProbe.cpp`,
 * `Klipper4408Interface.cpp`, `print_manage/data/DataType.cpp`, and the minified device page
 * `resources/web/deviceMgr/assets/BZCDzYbb.js`, cited as "line N @byte"). Nothing here has been run against a printer.
 *
 * - `GET http://<ip>/info` identifies the printer: JSON with `model` and `mac` (LanDeviceProbe.cpp:124-126,177-189).
 * - `ws://<ip>:9999/` carries state and commands as JSON text frames, with no request ids and no acks. The printer pushes
 *   state unprompted; a `get` asks for a key and the reply is a pushed object carrying it (CrealityPrint.cpp:227-256).
 *   K1-family firmware sends `heart_beat` frames that must be answered with a text frame `ok` or it drops the socket
 *   (CrealityPrint.cpp:243-249); a bare `ok` frame from the printer means "healthy" (BZCDzYbb.js line 1248 @4806411).
 * - `POST http://<ip>/upload/<name>`, multipart field `file`; the reply contains "OK" on success (Klipper4408Interface.cpp:38-40,74-78).
 *
 * Starting a print is gated: see [START_VERIFIED].
 */
object CrealityCfs {
    /**
     * Whether starting a print from Nozzle It All (`opGcodeFile printprt:`, or `colorMatch` then `multiColorPrint`) has been
     * confirmed on a real Creality printer. Until then the service uploads the file and refuses the start with
     * [startNotVerified]; the messages it would send are built and unit-tested (CrealityPrint's and Orca's wire format), but
     * a wrong start heats and moves the printer, and a wrong mapping feeds the wrong spool. Flip only with a real K1/K2
     * run recorded (docs/upstream/PROVENANCE.md), as BambuAms.AMS_PRINT_VERIFIED.
     */
    const val START_VERIFIED = false

    const val WS_PORT = 9999
    const val SLOTS_PER_BOX = 4
    /** Spool racks (box type 1 external, 2 smart rack) get tool numbers from here up, clear of the CFS units' 0..63. */
    const val RACK_BASE = 240
    /** The one reply the printer expects to its heartbeat, as a text frame. */
    const val HEARTBEAT_REPLY = "ok"

    /** [uploaded]: the file was just uploaded (the send-and-print path), rather than already on the printer. */
    fun startNotVerified(remoteName: String, uploaded: Boolean = true): String =
        (if (uploaded) "Uploaded $remoteName to the printer but did not start it" else "Did not start $remoteName") +
            ": starting a print on a Creality printer from Nozzle It All isn't verified on real hardware yet. Start it from the printer's screen."

    /** Throws the refusal while [verified] (default [START_VERIFIED]) is false. Called before any start message is built. */
    fun requireStartVerified(remoteName: String, verified: Boolean = START_VERIFIED, uploaded: Boolean = true) {
        if (!verified) throw ApiFailure(startNotVerified(remoteName, uploaded))
    }

    // ---- frames -------------------------------------------------------------------------------------------------------

    /** `{"method":"get","params":{"<key>":1,...}}` (CrealityPrint `GetBoxsInfo`, BZCDzYbb.js line 111 @4507080). Read-only. */
    fun get(vararg keys: String): JSONObject = JSONObject().put("method", "get").put("params", JSONObject().apply { keys.forEach { put(it, 1) } })

    /** K1-family heartbeat (`{"ModeCode":"heart_beat",...}`); Orca matches the substring (CrealityPrint.cpp:243). */
    fun isHeartbeat(frame: String): Boolean = frame.contains("heart_beat")

    /** What to send back for [frame]: `ok` for a heartbeat, nothing otherwise. */
    fun replyTo(frame: String): String? = if (isHeartbeat(frame)) HEARTBEAT_REPLY else null

    /** A state frame as JSON; null for `ok`, heartbeats and anything that isn't a JSON object. */
    fun parseFrame(frame: String): JSONObject? {
        val t = frame.trim()
        if (t.equals(HEARTBEAT_REPLY, ignoreCase = true) || isHeartbeat(t) || !t.startsWith("{")) return null
        return try { JSONObject(t) } catch (_: org.json.JSONException) { null }
    }

    /** Merges one pushed frame into [state], key by key, as CrealityPrint's applyDeviceData does (line 1248 @4867600). */
    fun merge(state: JSONObject, frame: JSONObject) { frame.keys().forEach { k -> state.put(k, frame.opt(k)) } }

    // ---- identity -----------------------------------------------------------------------------------------------------

    data class Info(val model: String, val mac: String, val hostname: String)

    /** `/info`'s body; null unless it carries both `model` and `mac` (LanDeviceProbe.cpp:177-189). */
    fun parseInfo(body: String): Info? {
        val o = try { JSONObject(body) } catch (_: Exception) { return null }
        val model = (o.opt("model") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val mac = (o.opt("mac") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Info(model.take(40), mac.take(40), ((o.opt("hostname") as? String)?.trim().orEmpty()).take(80))
    }

    /** `/info` model codes Orca lists as CFS-capable, with their names (CrealityPrint.cpp:282-294). The Hi and K2 SE codes aren't on disk. */
    val CFS_MODELS: Map<String, String> = linkedMapOf("F008" to "K2 Plus", "F012" to "K2 Pro", "F021" to "K2", "F022" to "SPARKX i7",
        "K1" to "K1", "K1 SE" to "K1 SE", "K1C" to "K1C", "K1_CFS-C" to "K1_CFS-C")

    /** The slicing pack a model code suggests (the person can still change it). K1-family codes get the plain pack. */
    fun slicingModelFor(model: String): SlicingPrinterModel? = when (model) {
        "F008" -> SlicingPrinterModel.CREALITY_K2_PLUS
        "F012" -> SlicingPrinterModel.CREALITY_K2_PRO
        "F021" -> SlicingPrinterModel.CREALITY_K2
        "F022" -> SlicingPrinterModel.CREALITY_SPARKX_I7
        "K1" -> SlicingPrinterModel.CREALITY_K1
        "K1 SE" -> SlicingPrinterModel.CREALITY_K1_SE
        "K1C" -> SlicingPrinterModel.CREALITY_K1C
        "K1_CFS-C" -> SlicingPrinterModel.CREALITY_K1_CFS_C
        else -> null
    }

    /** K1-family firmware keeps G-code under /usr/data; the K2 family under /mnt/UDISK (CrealityPrint.cpp:258-269,350-351). */
    fun isK1Family(model: String?): Boolean = model in setOf("K1", "K1 SE", "K1C", "K1_CFS-C")

    fun gcodeDirectory(model: String?): String = if (isK1Family(model)) "/usr/data/printer_data/gcodes/" else "/mnt/UDISK/printer_data/gcodes/"

    // ---- files --------------------------------------------------------------------------------------------------------

    /**
     * The name to upload as: ASCII letters, digits, `.`, `_` and `-` only (Orca replaces spaces, CrealityPrint's page strips
     * `\/:*?"'<>|`; this keeps the stricter union so the same name works in a websocket path and a URL).
     */
    fun safeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "print.gcode" }
        return base.map { if (it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '_' || it == '-') it else '_' }.joinToString("").take(120)
    }

    /** `retGcodeFileInfo2`'s entries' names (the reply to `get reqGcodeFile`). */
    fun listedFiles(state: JSONObject): List<JSONObject> {
        val list = state.optJSONArray("retGcodeFileInfo2") ?: return emptyList()
        return (0 until minOf(list.length(), 5000)).mapNotNull { list.optJSONObject(it) }
    }

    fun isListed(state: JSONObject, name: String): Boolean =
        listedFiles(state).any { it.optString("name") == name || it.optString("path").substringAfterLast('/') == name }

    /**
     * The printer-side path of an uploaded [name]: the directory of the printer's own first listed file, with `gcodes/...`
     * replaced by `gcodes/<name>` (CrealityPrint `HandleSendPrintCommand`, line 1269 @5226305), else the model's directory.
     */
    fun printerPath(name: String, model: String?, state: JSONObject?): String {
        val sample = state?.let { listedFiles(it) }?.firstNotNullOfOrNull { it.optString("path").takeIf { p -> p.startsWith("/") && "gcodes/" in p } }
        return if (sample != null) sample.substringBefore("gcodes/") + "gcodes/" + name else gcodeDirectory(model) + name
    }

    // ---- status -------------------------------------------------------------------------------------------------------

    /** Pushed `state` (0 idle, 1 printing, 2 complete, 3 error, 4 cancelled, 5 paused; read from CrealityPrint's own Klipper mapping, line 1248 @4639107) in this app's Moonraker vocabulary. */
    fun stateName(state: Int?): String = when (state) {
        0 -> "standby"; 1 -> "printing"; 2 -> "complete"; 3 -> "error"; 4 -> "cancelled"; 5 -> "paused"; else -> "unknown"
    }

    /**
     * The merged pushed state as a snapshot. Keys as CrealityPrint reads them (applyDeviceData / formatTemperatureData,
     * line 1248 @4867600-4883132): `state`, `printProgress` (percent), `printJobTime` (s), `printFileName` (a path),
     * `layer`, `TotalLayer`, `nozzleTemp`, `targetNozzleTemp`, `bedTemp0`, `targetBedTemp0` (numbers or numeric strings).
     */
    fun snapshot(state: JSONObject): PrinterSnapshot {
        val name = stateName(int(state, "state"))
        val progress = (number(state, "printProgress") ?: 0.0).div(100.0).coerceIn(0.0, 1.0).toFloat()
        return PrinterSnapshot(name != "unknown", name, (state.opt("printFileName") as? String)?.substringAfterLast('/').orEmpty(), progress,
            number(state, "nozzleTemp"), number(state, "targetNozzleTemp"), number(state, "bedTemp0"), number(state, "targetBedTemp0"),
            number(state, "printJobTime"), int(state, "layer")?.takeIf { it > 0 }, int(state, "TotalLayer")?.takeIf { it > 0 }, "extruder")
    }

    /** CrealityPrint only starts on `deviceState == 0` (line 1269 @5231819); without it, on an idle `state`. */
    fun isIdle(state: JSONObject): Boolean {
        int(state, "deviceState")?.let { return it == 0 }
        return int(state, "state") in setOf(0, 2, 3, 4)
    }

    /** A firmware error the printer pushed (`err.errcode` / `err.key`, 0 = none; line 1248 @4878093), or null. */
    fun pushedError(state: JSONObject): String? {
        val err = state.optJSONObject("err") ?: return null
        val code = int(err, "errcode")?.takeIf { it != 0 } ?: int(err, "key")?.takeIf { it != 0 } ?: return null
        return "The printer reported error $code."
    }

    // ---- CFS slots ----------------------------------------------------------------------------------------------------

    /**
     * One physical slot. [boxId] and [materialId] are the printer's raw ids, sent back as-is in `colorMatch`
     * (`boxId` the raw `materialBoxs[].id`, not a renumbered one; `materialId` 0-3). [boxType] 0 is a CFS unit, 1 the
     * external spool rack, 2 a smart rack (BZCDzYbb.js line 1248 @4878023).
     */
    data class Slot(val boxId: Int, val boxType: Int, val materialId: Int, val loaded: Boolean, val type: String?, val vendor: String?,
                    val brand: String?, val colorHex: String?, val selected: Boolean) {
        val isRack: Boolean get() = boxType != 0
        /** A stable physical number: CFS unit n slot m is 4(n-1)+m (T n-1 by default); racks sit at [RACK_BASE] and up. */
        val tool: Int get() = if (isRack) RACK_BASE + boxId * SLOTS_PER_BOX + materialId else (boxId - 1) * SLOTS_PER_BOX + materialId
        val label: String get() = if (isRack) (if (boxType == 1) "External spool" else "Spool rack $boxId") else "CFS $boxId · slot ${'A' + materialId}"
    }

    /** [unitName] is `boxsInfo.name` (MF003 CFS, MF050 CFS-C, ...; line 1269 @5233162). */
    data class Boxes(val unitName: String?, val slots: List<Slot>)

    /** `boxsInfo.name` codes CrealityPrint names (`cfsName`, line 1269 @5233162). */
    val UNIT_NAMES: Map<String, String> = mapOf("MF003" to "CFS", "MF050" to "CFS-C", "MF040" to "CFS Lite", "MF046" to "CFS Mini",
        "MF049" to "CFS Nano", "MF042" to "CFS Pro", "MF054" to "CFS Nano2")

    /**
     * `boxsInfo` (the pushed object, or the frame carrying it) to its slots, with Orca's rules (CrealityPrintAgent.cpp:195-283)
     * and CrealityPrint's field names (DataType.cpp:121-200): boxes whose `state` isn't 1 are skipped; a slot is loaded
     * when its `state` isn't 0 and it reports a vendor or a type (K2 Plus firmware 1.1.5.5+ uses 1 = loaded and selected,
     * 2 = loaded); `#0RRGGBB` colours drop their extra digit. Empty slots are kept, as unloaded. Null without `materialBoxs`.
     */
    fun parseBoxes(boxsInfo: JSONObject?): Boxes? {
        val info = boxsInfo?.optJSONObject("boxsInfo") ?: boxsInfo ?: return null
        val boxes = info.optJSONArray("materialBoxs") ?: return null
        val slots = ArrayList<Slot>()
        for (b in 0 until minOf(boxes.length(), 16)) {
            val box = boxes.optJSONObject(b) ?: continue
            if (int(box, "state") != 1) continue
            val id = int(box, "id") ?: continue
            val type = int(box, "type") ?: 0
            if (type == 0 && id !in 1..16) continue
            if (type != 0 && id !in 0..15) continue
            val materials = box.optJSONArray("materials") ?: continue
            for (m in 0 until minOf(materials.length(), SLOTS_PER_BOX)) {
                val mat = materials.optJSONObject(m) ?: continue
                val materialId = int(mat, "id") ?: m
                if (materialId !in 0 until SLOTS_PER_BOX) continue
                val vendor = str(mat, "vendor"); val kind = str(mat, "type")
                val loaded = (int(mat, "state") ?: 0) != 0 && !(vendor.isEmpty() && kind.isEmpty())
                slots += Slot(id, type, materialId, loaded, kind.takeIf { loaded && it.isNotEmpty() }, vendor.takeIf { loaded && it.isNotEmpty() },
                    str(mat, "name").takeIf { loaded && it.isNotEmpty() }, if (loaded) normalizeColor(str(mat, "color")) else null, int(mat, "selected") == 1)
            }
        }
        return Boxes((info.opt("name") as? String)?.trim()?.takeIf { it.isNotEmpty() }, slots.distinctBy { it.tool }.sortedBy { it.tool })
    }

    /** `#0RRGGBB` (8 characters) to `#RRGGBB` (Orca CrealityPrintAgent.cpp:272-275; the page's `"#"+color.slice(2,8)`). */
    fun normalizeColor(raw: String?): String? {
        val s = raw?.trim() ?: return null
        return FilamentLanes.normalizeColor(if (s.length == 8 && s[0] == '#') "#" + s.substring(2) else s)
    }

    /** The slots as this app shows them; a loaded slot with no type is still loaded. */
    fun filamentSlots(boxes: Boxes): List<FilamentSlot> = boxes.slots.map { s ->
        FilamentSlot(s.tool, if (s.loaded) (s.type?.uppercase() ?: "LOADED (TYPE NOT REPORTED)") else null, s.colorHex,
            vendor = s.vendor, active = s.loaded && s.selected, name = s.label)
    }

    // ---- print start (gated) ------------------------------------------------------------------------------------------

    /**
     * `colorMatch.list[].id` for 0-based file tool [tool]: `T{floor(T/4)+1}{'A'+T%4}` (T0 T1A, T3 T1D, T4 T2A, T15 T4D), as
     * CrealityPrint builds it from its 1-based extruder index (line 1269; line 1254 @4944805). Orca's `"T1"+('A'+i)`
     * (PrintHostDialogs.cpp:2096-2101) is wrong from T4 up.
     */
    fun filamentId(tool: Int): String {
        require(tool in 0 until 16 * SLOTS_PER_BOX) { "Material ${tool + 1} is beyond what a CFS can feed." }
        return "T${tool / SLOTS_PER_BOX + 1}${'A' + tool % SLOTS_PER_BOX}"
    }

    /** Single colour, no CFS, or the external spool (`StartNormalPrint`, line 111 @4509151). Starts heating and motion. */
    fun opGcodeFile(path: String, enableSelfTest: Int = 0): JSONObject = JSONObject().put("method", "set")
        .put("params", JSONObject().put("opGcodeFile", "printprt:$path").put("enableSelfTest", enableSelfTest))

    /** Stores the file-tool -> slot mapping (`SetColorMatch`, line 111 @4508100). A write; sent only right before [multiColorPrint]. */
    fun colorMatch(path: String, list: JSONArray): JSONObject = JSONObject().put("method", "set")
        .put("params", JSONObject().put("colorMatch", JSONObject().put("path", path).put("list", list)))

    /** Starts a CFS print (`StartMultiColorPrint`, line 111 @4508994). Starts heating and motion. */
    fun multiColorPrint(path: String, enableSelfTest: Int = 0): JSONObject = JSONObject().put("method", "set")
        .put("params", JSONObject().put("multiColorPrint", JSONObject().put("gcode", path).put("enableSelfTest", enableSelfTest)))

    /**
     * The messages that start [path], in order. [toolheadMap] is PrinterAction.StartJob's: entry T is the [Slot.tool]
     * feeding file tool T, -1 unmapped; empty means "print from what is loaded" (single colour). [fileFilaments] gives each
     * file tool's declared type (CrealityPrint sends the file's type; Orca the slot's, used here when the file has none).
     *
     * - nothing mapped: `opGcodeFile`;
     * - the external spool: `opGcodeFile` only, as Orca does (CrealityPrint.cpp:373-401), and only for one file tool;
     * - otherwise `colorMatch` (CFS slots only, as CrealityPrint matches `boxType 0` only, line 1254 @4944805) then
     *   `multiColorPrint` (line 1269 @5226305; Orca lines 412-424).
     *
     * Throws IllegalArgumentException for a slot the printer didn't report or one with nothing loaded; nothing is sent then.
     */
    fun startMessages(path: String, toolheadMap: List<Int>, slots: List<Slot>, fileFilaments: List<SlicedFileFilaments.Filament> = emptyList(),
                      enableSelfTest: Int = 0): List<JSONObject> {
        require(path.startsWith("/") && '"' !in path) { "Invalid printer file path." }
        require(toolheadMap.size <= 16 * SLOTS_PER_BOX) { "Too many materials for one print." }
        val mapped = toolheadMap.withIndex().filter { it.value >= 0 }
        if (mapped.isEmpty()) return listOf(opGcodeFile(path, enableSelfTest))
        val picks = mapped.map { (t, tool) ->
            val slot = slots.firstOrNull { it.tool == tool } ?: throw IllegalArgumentException("Material ${t + 1} is mapped to a slot this printer doesn't report. Check its CFS, then start again.")
            require(slot.loaded) { "Material ${t + 1} is mapped to ${slot.label}, which has nothing loaded." }
            t to slot
        }
        if (picks.any { it.second.isRack }) {
            require(picks.size == 1) { "The external spool can feed a single-filament print only. Map every material to a CFS slot." }
            return listOf(opGcodeFile(path, enableSelfTest))
        }
        val list = JSONArray()
        picks.forEach { (t, slot) ->
            val type = fileFilaments.firstOrNull { it.tool == t }?.type ?: slot.type.orEmpty()
            list.put(JSONObject().put("id", filamentId(t)).put("type", type).put("color", slot.colorHex ?: "#FFFFFF")
                .put("boxId", slot.boxId).put("materialId", slot.materialId))
        }
        return listOf(colorMatch(path, list), multiColorPrint(path, enableSelfTest))
    }

    private fun int(o: JSONObject, k: String): Int? = when (val v = o.opt(k)) { is Number -> v.toInt(); is String -> v.trim().toDoubleOrNull()?.toInt(); else -> null }
    private fun number(o: JSONObject, k: String): Double? = when (val v = o.opt(k)) { is Number -> v.toDouble(); is String -> v.trim().toDoubleOrNull(); else -> null }?.takeIf { it.isFinite() }
    private fun str(o: JSONObject, k: String): String = (o.opt(k) as? String)?.trim().orEmpty()
}

// Adapted from Helix (github.com/FatBoy721/Helix), AGPL-3.0-or-later.
// Original: android/app/src/main/java/org/crabcore/u1control/bambu/BambuPrintProtocol.kt
//
// Helix's AMS lane mapping was first trimmed from this port, restored from
// Helix's original, and is now built with Bambu Studio's values
// (src/slic3r/GUI/SelectMachine.cpp get_ams_mapping_result, :1425-1539, and
// DeviceCore/DevMapping.cpp; AGPL-3.0): one entry per project filament, AMS HT
// trays as 128+, both external holders, use_ams whenever an AMS tray feeds.
// With no mapping (the default) the payload is the single-material,
// external-spool form this app has always sent, which a real P1S accepted.
// Starting a print with a mapping is gated by BambuAms.AMS_PRINT_VERIFIED
// until a real Bambu printer has confirmed it.
//
// Bambu Studio doesn't build the MQTT project_file itself (it hands these
// values to its closed network plugin, Jobs/PrintJob.cpp:279-291), so the
// values are Bambu Studio's but some wire key names are general knowledge
// ([GK], from Helix, OpenBambuAPI and ha-bambulab); each is marked where sent.
package net.jamesjennison.klippercompanion

import org.json.JSONArray
import org.json.JSONObject

/** Pure construction and acknowledgement parsing for Bambu `project_file`. */
object BambuPrintProtocol {

    /**
     * The payload without a mapping (a one-filament print from the external spool) keeps the four-entry form a real P1S
     * accepted (BambuPrintProtocolTest.matchesThePayloadAcceptedByTheRealP1s). With a mapping the length is the file's
     * project filament count instead; there is no four-filament limit.
     */
    const val LEGACY_MAPPING_SLOTS = 4
    /** `ams_mapping`'s value for a filament not fed from an AMS tray: unused, or either external holder (SelectMachine.cpp:1487). */
    const val NOT_ON_AMS = -1
    /** `ams_mapping2`'s "no tray" (unused filament), {255, 255} (SelectMachine.cpp:1464-1466, 1491-1493). Not the external spool. */
    private const val UNUSED_AMS_ID = 255
    private const val UNUSED_SLOT_ID = 255
    private val MD5_PATTERN = Regex("^[0-9A-F]{32}$")

    data class ProjectFileCommand(
        val sequenceId: String,
        val fileName: String,
        val subtaskName: String,
        val md5: String,
        val bedType: String,
        val bedLeveling: Boolean,
        val flowCalibration: Boolean,
        val timelapse: Boolean,
        /**
         * One entry per project filament (index = zero-based T number; the list is as long as the file's filament count):
         * the tray that feeds it, or null for a filament the plate doesn't use. Empty: no mapping, the legacy
         * external-spool payload.
         */
        val amsMapping: List<BambuAmsTrays.BambuTray?> = emptyList(),
        /** The file's `filament_maps` (1 left, 2 right per project filament), checked against the trays on a two-nozzle printer. */
        val filamentMaps: List<Int> = emptyList(),
        val dualNozzle: Boolean = false,
        val layerInspection: Boolean = true,
        val vibrationCalibration: Boolean = false,
        val autoBedLeveling: Int = 1,
        val extrudeCalibrationFlag: Int = 2,
        val extrudeCalibrationManualMode: Int = 0,
        val nozzleOffsetCalibration: Int = 2,
    ) {
        /**
         * `use_ams` is true whenever any filament is fed from an AMS tray, a mix of AMS and external included, and false
         * only when every mapped filament is on an external holder (SelectMachine.cpp:3384-3400, 2620-2642). A
         * one-filament print from an AMS tray is an AMS print too.
         */
        val useAms: Boolean get() = amsMapping.any { it != null && !it.external }
    }

    enum class Acknowledgement {
        NOT_MATCHING,
        SUCCESS,
        REJECTED,
    }

    fun buildProjectFilePayload(command: ProjectFileCommand): String {
        require(command.sequenceId.isNotBlank()) { "sequenceId is required" }
        require(command.fileName.isSafeRootFileName()) { "fileName must be a root filename" }
        require(command.fileName.endsWith(".gcode.3mf", ignoreCase = true)) {
            "Bambu print artifacts must end in .gcode.3mf"
        }
        require(command.subtaskName.isNotBlank()) { "subtaskName is required" }
        require(command.md5.uppercase().matches(MD5_PATTERN)) { "md5 must contain 32 hex digits" }
        require(command.bedType.isNotBlank()) { "bedType is required" }

        val mapping: List<Int>
        val mapping2: List<JSONObject>
        if (command.amsMapping.isEmpty()) {
            // No mapping: every filament routes off the AMS, in the form the real P1S accepted.
            mapping = List(LEGACY_MAPPING_SLOTS) { NOT_ON_AMS }
            mapping2 = List(LEGACY_MAPPING_SLOTS) { slot(UNUSED_AMS_ID, UNUSED_SLOT_ID) }
        } else {
            val byTool = command.amsMapping.withIndex().mapNotNull { (tool, tray) -> tray?.let { tool to it } }.toMap()
            require(byTool.isNotEmpty()) { "an AMS mapping must map at least one filament" }
            BambuAms.mappingProblem(byTool, command.filamentMaps, command.dualNozzle)?.let { throw IllegalArgumentException(it) }
            // ams_mapping: the global tray index (AMS HT 128+, the A2L's mixed lite 24+), -1 for unused and for either
            // external holder (SelectMachine.cpp:1463-1490).
            mapping = command.amsMapping.map { tray -> if (tray == null || tray.external) NOT_ON_AMS else tray.trayIndex }
            // ams_mapping2: {ams_id, slot_id} from the tray itself: {255,255} unused, {255,0} / {254,0} the right (or
            // only) / left external holder, {128+k,0} an AMS HT (SelectMachine.cpp:1491-1496, DevMapping.cpp:77-111, 235).
            mapping2 = command.amsMapping.map { tray ->
                when {
                    tray == null -> slot(UNUSED_AMS_ID, UNUSED_SLOT_ID)
                    tray.external -> slot(tray.amsId, 0)
                    else -> slot(tray.amsId, tray.slotId)
                }
            }
        }

        val print = JSONObject().apply {
            put("ams_mapping", JSONArray(mapping))
            // [GK] The wire key "ams_mapping2" (Bambu Studio names only the value it hands its plugin).
            put("ams_mapping2", JSONArray(mapping2))
            put("auto_bed_leveling", command.autoBedLeveling)
            put("bed_leveling", command.bedLeveling)
            put("bed_type", command.bedType)
            put("cfg", "0")
            put("command", "project_file")
            put("extrude_cali_flag", command.extrudeCalibrationFlag)
            put("extrude_cali_manual_mode", command.extrudeCalibrationManualMode)
            put("file", command.fileName)
            put("flow_cali", command.flowCalibration)
            put("layer_inspect", command.layerInspection)
            put("md5", command.md5.uppercase())
            put("nozzle_offset_cali", command.nozzleOffsetCalibration)
            put("param", "Metadata/plate_1.gcode")
            put("profile_id", "0")
            put("project_id", "0")
            put("sequence_id", command.sequenceId)
            put("subtask_id", "0")
            put("subtask_name", command.subtaskName)
            put("task_id", "0")
            put("timelapse", command.timelapse)
            put("use_ams", command.useAms)
            put("vibration_cali", command.vibrationCalibration)
            put("url", "ftp://${command.fileName}")
        }
        return JSONObject().put("print", print).toString()
    }

    enum class Control(val wire: String) { PAUSE("pause"), RESUME("resume"), STOP("stop") }

    /** The pause/resume/stop request the printer's MQTT `request` topic takes. */
    fun buildControlPayload(sequenceId: String, control: Control): String =
        JSONObject().put("print", JSONObject().put("sequence_id", sequenceId).put("command", control.wire).put("param", "")).toString()

    /**
     * How many `<filament>` rows a `.gcode.3mf` bundle's `Metadata/slice_info.config` lists: one per filament the plate
     * uses (not per project filament; `filament_maps` covers those). More than one needs an AMS mapping, which is only
     * sent once BambuAms.AMS_PRINT_VERIFIED, so until then such a file is refused rather than started on a guess.
     */
    fun usedFilaments(sliceInfo: String): Int = Regex("<filament\\s").findAll(sliceInfo).count()

    /** The filaments a bundle's slice_info lists, with their zero-based T numbers, types, colours, filament ids and groups. */
    fun fileFilaments(sliceInfo: String): List<BambuAms.FileFilament> =
        Regex("<filament\\s([^>]*)>").findAll(sliceInfo).mapNotNull { row ->
            fun attr(name: String) = Regex("\\b$name=\"([^\"]*)\"").find(row.groupValues[1])?.groupValues?.get(1)
            val id = attr("id")?.toIntOrNull() ?: return@mapNotNull null
            BambuAms.FileFilament(id - 1, attr("type").orEmpty(), FilamentLanes.normalizeColor(attr("color")),
                attr("tray_info_idx")?.trim()?.takeIf { it.isNotEmpty() }, attr("group_id")?.trim()?.toIntOrNull())
        }.toList()

    /**
     * Bambu printer model ids with two nozzles (BambuStudio resources/printers/<model id>.json, model_id: O1D H2D, O1E H2D Pro,
     * O1C / O1C2 H2C, N6 X2D), all with `physical_extruder_map` [1, 0].
     */
    val DUAL_NOZZLE_MODEL_IDS = setOf("O1D", "O1E", "O1C", "O1C2", "N6")

    /**
     * The first plate of a bundle's slice_info: the used filaments, and the plate metadata Bambu Studio writes beside them
     * (bbs_3mf.cpp:336-366, 8800-8846): `filament_maps` (one entry per *project* filament, 1 left, 2 right; all 1 on a
     * one-nozzle file), `filament_map_mode`, `enable_filament_dynamic_map`, `has_filament_switcher`, `printer_model_id`
     * and `nozzle_diameters`.
     */
    data class SlicePlate(
        val filaments: List<BambuAms.FileFilament>,
        val filamentMaps: List<Int> = emptyList(),
        val filamentMapMode: String? = null,
        val dynamicNozzleMap: Boolean = false,
        val hasFilamentSwitcher: Boolean = false,
        val printerModelId: String? = null,
        val nozzleCount: Int? = null,
    ) {
        /** The project filament count `ams_mapping` is as long as: `filament_maps`' size, else the highest used filament. */
        val projectFilamentCount: Int get() = maxOf(filamentMaps.size, (filaments.maxOfOrNull { it.tool } ?: -1) + 1)
        /** Sliced for a two-nozzle printer: by its model id, else by its nozzle count. */
        val dualNozzle: Boolean get() = printerModelId in DUAL_NOZZLE_MODEL_IDS || (nozzleCount ?: 1) >= 2
    }

    fun slicePlate(sliceInfo: String): SlicePlate {
        val plate = Regex("<plate>(.*?)</plate>", RegexOption.DOT_MATCHES_ALL).find(sliceInfo)?.groupValues?.get(1) ?: sliceInfo
        fun meta(key: String): String? = Regex("<metadata\\s[^>]*\\bkey=\"$key\"[^>]*>").find(plate)?.value
            ?.let { Regex("\\bvalue=\"([^\"]*)\"").find(it)?.groupValues?.get(1) }
        fun flag(key: String) = meta(key)?.trim().equals("true", ignoreCase = true)
        return SlicePlate(
            filaments = fileFilaments(plate),
            filamentMaps = meta("filament_maps").orEmpty().trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() },
            filamentMapMode = meta("filament_map_mode")?.trim()?.takeIf { it.isNotEmpty() },
            dynamicNozzleMap = flag("enable_filament_dynamic_map"),
            hasFilamentSwitcher = flag("has_filament_switcher"),
            printerModelId = meta("printer_model_id")?.trim()?.takeIf { it.isNotEmpty() },
            nozzleCount = meta("nozzle_diameters")?.split(Regex("[,;\\s]+"))?.count { it.isNotBlank() }?.takeIf { it > 0 },
        )
    }

    /**
     * An H2C file sliced for its nozzle rack's dynamic filament map needs the printer's `get_auto_nozzle_mapping` handshake
     * and a `nozzle_mapping` in the print command; Bambu Studio won't send without it (SelectMachine.cpp:3349-3351,
     * 6062-6098; DevMappingNozzle.cpp:96-262). This app doesn't do that handshake yet.
     */
    const val DYNAMIC_NOZZLE_MAP_NOT_SUPPORTED =
        "This file was sliced to let the H2C's nozzle rack choose nozzles as it prints. Nozzle It All can't start such a " +
        "file yet. Print it from Bambu Studio or Bambu Handy, or re-slice it without the dynamic nozzle map."

    const val MULTI_MATERIAL_NOT_SUPPORTED =
        "This file uses more than one filament. Nozzle It All can slice multi-colour prints for Bambu printers but " +
        "can't start them with the AMS yet. Print it from Bambu Studio or Bambu Handy, or slice it with one filament."

    /** Only a matching `project_file` response can resolve a start request. */
    fun acknowledgement(payload: String, expectedSequenceId: String): Acknowledgement {
        val print = runCatching { JSONObject(payload).optJSONObject("print") }.getOrNull()
            ?: return Acknowledgement.NOT_MATCHING
        if (print.optString("command") != "project_file") return Acknowledgement.NOT_MATCHING
        if (print.optString("sequence_id") != expectedSequenceId) return Acknowledgement.NOT_MATCHING
        return if (print.optString("result").equals("success", ignoreCase = true)) {
            Acknowledgement.SUCCESS
        } else {
            Acknowledgement.REJECTED
        }
    }

    private fun slot(amsId: Int, slotId: Int): JSONObject = JSONObject().put("ams_id", amsId).put("slot_id", slotId)

    private fun String.isSafeRootFileName(): Boolean =
        isNotBlank() && this != "." && this != ".." && !contains('/') && !contains('\\')
}

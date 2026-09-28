// Adapted from Helix (github.com/FatBoy721/Helix), AGPL-3.0-or-later.
// Original: android/app/src/main/java/org/crabcore/u1control/bambu/BambuPrintProtocol.kt
//
// Trimmed against Helix's original: this app prints single-material only, from
// the external spool. Helix's AMS/multi-material lane mapping (a tool -> global
// AMS lane map, and the use_ams switch that feeds it) is therefore dropped, and
// the on-wire ams_mapping/ams_mapping2 fields are always emitted in their
// external-spool form. Everything else is Helix's payload verbatim.
package net.jamesjennison.klippercompanion

import org.json.JSONArray
import org.json.JSONObject

/** Pure construction and acknowledgement parsing for Bambu `project_file`. */
object BambuPrintProtocol {

    const val TOOL_COUNT = 4
    const val EXTERNAL_SPOOL = -1
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
        val layerInspection: Boolean = true,
        val vibrationCalibration: Boolean = false,
        val autoBedLeveling: Int = 1,
        val extrudeCalibrationFlag: Int = 2,
        val extrudeCalibrationManualMode: Int = 0,
        val nozzleOffsetCalibration: Int = 2,
    )

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

        // Single-material, external spool: every tool routes off the AMS.
        val mapping = List(TOOL_COUNT) { EXTERNAL_SPOOL }
        val mapping2 = mapping.map { externalSpoolMapping() }

        val print = JSONObject().apply {
            put("ams_mapping", JSONArray(mapping))
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
            put("use_ams", false)
            put("vibration_cali", command.vibrationCalibration)
            put("url", "ftp://${command.fileName}")
        }
        return JSONObject().put("print", print).toString()
    }

    enum class Control(val wire: String) { PAUSE("pause"), RESUME("resume"), STOP("stop") }

    /** The pause/resume/stop request the printer's MQTT `request` topic takes. */
    fun buildControlPayload(sequenceId: String, control: Control): String =
        JSONObject().put("print", JSONObject().put("sequence_id", sequenceId).put("command", control.wire).put("param", "")).toString()

    /** Only a matching `project_file` response can resolve a start request. */
    /**
     * The filaments a `.gcode.3mf` bundle's `Metadata/slice_info.config` lists (one `<filament>` row per filament the
     * print uses). More than one needs the AMS, which this app's print command doesn't map yet: it always sends
     * `use_ams:false` (see the header), so such a file is refused rather than started on a guess.
     */
    fun usedFilaments(sliceInfo: String): Int = Regex("<filament\\s").findAll(sliceInfo).count()

    const val MULTI_MATERIAL_NOT_SUPPORTED =
        "This file uses more than one filament. Nozzle It All can slice multi-colour prints for Bambu printers but " +
        "can't start them with the AMS yet. Print it from Bambu Studio or Bambu Handy, or slice it with one filament."

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

    private fun externalSpoolMapping(): JSONObject =
        JSONObject().put("ams_id", UNUSED_AMS_ID).put("slot_id", UNUSED_SLOT_ID)

    private fun String.isSafeRootFileName(): Boolean =
        isNotBlank() && this != "." && this != ".." && !contains('/') && !contains('\\')
}

package net.jamesjennison.klippercompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Ported from Helix's BambuPrintProtocolTest. The payload here is the one sent
 * without an AMS mapping (single-material, external spool), which is what the
 * app sends until BambuAms.AMS_PRINT_VERIFIED; the AMS mapping itself (one entry
 * per project filament, AMS HT, both external holders, two nozzles) is tested
 * in domain's BambuAmsMappingTest.
 */
class BambuPrintProtocolTest {

    @Test
    fun matchesThePayloadAcceptedByTheRealP1s() {
        val payload = BambuPrintProtocol.buildProjectFilePayload(
            BambuPrintProtocol.ProjectFileCommand(
                sequenceId = "20000",
                fileName = "Small_Tiny_Mini_Micro_Hen.gcode.3mf",
                subtaskName = "Small_Tiny_Mini_Micro_Hen",
                md5 = "ead10b6e54929a62386c9639a39f344a",
                bedType = "supertack_plate",
                bedLeveling = true,
                flowCalibration = false,
                timelapse = false,
            )
        )

        val print = JSONObject(payload).getJSONObject("print")
        assertEquals("project_file", print.getString("command"))
        assertEquals("ftp://Small_Tiny_Mini_Micro_Hen.gcode.3mf", print.getString("url"))
        assertEquals("Metadata/plate_1.gcode", print.getString("param"))
        assertEquals("EAD10B6E54929A62386C9639A39F344A", print.getString("md5"))
        assertEquals("supertack_plate", print.getString("bed_type"))
        assertEquals(1, print.getInt("auto_bed_leveling"))
        assertEquals(2, print.getInt("extrude_cali_flag"))
        assertEquals(0, print.getInt("extrude_cali_manual_mode"))
        assertEquals(2, print.getInt("nozzle_offset_cali"))
        assertEquals(true, print.getBoolean("bed_leveling"))
        assertEquals(false, print.getBoolean("flow_cali"))
        assertEquals(true, print.getBoolean("layer_inspect"))
        assertEquals(false, print.getBoolean("timelapse"))
        assertEquals(false, print.getBoolean("vibration_cali"))
    }

    @Test
    fun everyToolAlwaysRoutesToTheExternalSpool() {
        val print = JSONObject(BambuPrintProtocol.buildProjectFilePayload(command())).getJSONObject("print")

        assertEquals(false, print.getBoolean("use_ams"))
        assertEquals(listOf(-1, -1, -1, -1), print.getJSONArray("ams_mapping").intList())
        assertEquals(
            listOf(255 to 255, 255 to 255, 255 to 255, 255 to 255),
            print.getJSONArray("ams_mapping2").mappingList(),
        )
    }

    @Test
    fun rejectsPathsAndNonPrintArchives() {
        assertThrows(IllegalArgumentException::class.java) {
            BambuPrintProtocol.buildProjectFilePayload(command().copy(fileName = "cache/job.gcode.3mf"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BambuPrintProtocol.buildProjectFilePayload(command().copy(fileName = "job.gcode"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BambuPrintProtocol.buildProjectFilePayload(command().copy(md5 = "not-an-md5"))
        }
    }

    @Test
    fun acceptsOnlyTheMatchingSuccessfulPrinterResponse() {
        val success = """{"print":{"command":"project_file","sequence_id":"42","result":"success"}}"""
        val rejected = """{"print":{"command":"project_file","sequence_id":"42","result":"failed"}}"""
        val otherSequence = """{"print":{"command":"project_file","sequence_id":"41","result":"success"}}"""
        val telemetry = """{"print":{"command":"push_status","sequence_id":"42"}}"""

        assertEquals(BambuPrintProtocol.Acknowledgement.SUCCESS, BambuPrintProtocol.acknowledgement(success, "42"))
        assertEquals(BambuPrintProtocol.Acknowledgement.REJECTED, BambuPrintProtocol.acknowledgement(rejected, "42"))
        assertEquals(BambuPrintProtocol.Acknowledgement.NOT_MATCHING, BambuPrintProtocol.acknowledgement(otherSequence, "42"))
        assertEquals(BambuPrintProtocol.Acknowledgement.NOT_MATCHING, BambuPrintProtocol.acknowledgement(telemetry, "42"))
        assertEquals(BambuPrintProtocol.Acknowledgement.NOT_MATCHING, BambuPrintProtocol.acknowledgement("not json", "42"))
    }

    private fun command() = BambuPrintProtocol.ProjectFileCommand(
        sequenceId = "42",
        fileName = "job.gcode.3mf",
        subtaskName = "job",
        md5 = "0123456789ABCDEF0123456789ABCDEF",
        bedType = "supertack_plate",
        bedLeveling = true,
        flowCalibration = false,
        timelapse = false,
    )

    private fun org.json.JSONArray.intList(): List<Int> = (0 until length()).map(::getInt)

    private fun org.json.JSONArray.mappingList(): List<Pair<Int, Int>> =
        (0 until length()).map { index ->
            getJSONObject(index).let { it.getInt("ams_id") to it.getInt("slot_id") }
        }
}

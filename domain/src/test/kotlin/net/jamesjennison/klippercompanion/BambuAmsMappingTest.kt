package net.jamesjennison.klippercompanion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BambuAmsMappingTest {
    private fun tray(lane: Int, material: String?, color: String?) = FilamentSlot(lane, material, color)
    private val f = BambuAms::FileFilament

    @Test fun eachFilamentGetsATrayOfItsMaterialPreferringItsColour() {
        val trays = listOf(tray(0, "PLA", "#FFFFFF"), tray(1, "PLA", "#FF0000"), tray(2, "PETG", "#000000"), tray(3, null, null))
        val match = BambuAms.matchTrays(listOf(f(0, "PLA", "#FF0000"), f(1, "PETG", "#00FF00"), f(2, "PLA", "#0000FF")), trays)
        assertEquals(BambuAms.TrayMatch.Mapped(mapOf(0 to 1, 1 to 2, 2 to 0)), match)
    }

    @Test fun aFilamentWithNoLoadedTrayOfItsMaterialIsReported() {
        val match = BambuAms.matchTrays(listOf(f(0, "PLA", null), f(1, "TPU", null)), listOf(tray(0, "PLA", null), tray(1, null, null)))
        assertEquals(BambuAms.TrayMatch.Missing(listOf(f(1, "TPU", null))), match)
    }

    @Test fun twoFilamentsNeverShareATrayAndTheExternalSpoolIsNotPicked() {
        val trays = listOf(tray(0, "PLA", "#FFFFFF"), tray(BambuAmsTrays.EXTERNAL_TRAY, "PLA", "#FFFFFF"))
        assertTrue(BambuAms.matchTrays(listOf(f(0, "PLA", "#FFFFFF"), f(1, "PLA", "#FFFFFF")), trays) is BambuAms.TrayMatch.Missing)
    }

    @Test fun readsTheFileFilamentsFromSliceInfo() {
        val info = """<filament id="1" tray_info_idx="" type="PLA" color="#FF0000" used_m="1" /><filament id="3" type="PETG" color="#00ff00ff" />"""
        assertEquals(listOf(f(0, "PLA", "#FF0000"), f(2, "PETG", "#00FF00")), BambuPrintProtocol.fileFilaments(info))
    }

    private fun command(map: Map<Int, Int>, useAms: Boolean) = BambuPrintProtocol.ProjectFileCommand(
        sequenceId = "1", fileName = "a.gcode.3mf", subtaskName = "a", md5 = "0123456789ABCDEF0123456789ABCDEF",
        bedType = "textured_plate", bedLeveling = true, flowCalibration = true, timelapse = false, toolToLane = map, useAms = useAms)

    @Test fun anAmsMappingNamesEachToolsLaneAsHelixSendsIt() {
        val print = JSONObject(BambuPrintProtocol.buildProjectFilePayload(command(mapOf(0 to 2, 1 to 5), useAms = true))).getJSONObject("print")
        assertTrue(print.getBoolean("use_ams"))
        assertEquals("[2,5,-1,-1]", print.getJSONArray("ams_mapping").toString())
        val detailed = print.getJSONArray("ams_mapping2")
        assertEquals(0, detailed.getJSONObject(0).getInt("ams_id")); assertEquals(2, detailed.getJSONObject(0).getInt("slot_id"))
        assertEquals(1, detailed.getJSONObject(1).getInt("ams_id")); assertEquals(1, detailed.getJSONObject(1).getInt("slot_id"))
        assertEquals(255, detailed.getJSONObject(2).getInt("ams_id"))
    }

    @Test fun withoutUseAmsAMappingIsIgnored() {
        val print = JSONObject(BambuPrintProtocol.buildProjectFilePayload(command(mapOf(0 to 2), useAms = false))).getJSONObject("print")
        assertFalse(print.getBoolean("use_ams"))
        assertEquals("[-1,-1,-1,-1]", print.getJSONArray("ams_mapping").toString())
    }

    @Test fun amsPrintingStaysOffUntilARealPrinterConfirmsIt() {
        assertFalse(BambuAms.AMS_PRINT_VERIFIED)
    }
}

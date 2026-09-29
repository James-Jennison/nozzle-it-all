package net.jamesjennison.klippercompanion

import net.jamesjennison.klippercompanion.BambuAmsTrays.AmsKind
import net.jamesjennison.klippercompanion.BambuAmsTrays.BambuTray
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

// Fixture names (F5-F7) are the Bambu AMS research report's; F5 is BambuAmsFixtures.F5 (CONSTRUCTED), and every tray
// built below is CONSTRUCTED from Bambu Studio's rules, not captured from a printer.
class BambuAmsMappingTest {
    private fun ams(amsId: Int, slot: Int, material: String?, color: String?, extruder: Int? = 0, kind: AmsKind = AmsKind.AMS,
                    filamentId: String? = null, remain: Int? = null) =
        BambuTray(amsId, slot, BambuAmsTrays.trayIndex(kind, amsId, slot), kind, extruder, loaded = material != null, material = material,
            colorHex = color, trayInfoIdx = filamentId, remain = remain, name = "${kind.label} ${amsId + 1} · slot ${slot + 1}")
    /** Global lane [i] of a one-nozzle printer's AMS units: unit i/4, slot i%4. */
    private fun lane(i: Int, material: String?, color: String?) = ams(i / 4, i % 4, material, color)
    private fun ext(id: Int, material: String?, color: String?) =
        BambuTray(id, 0, id, AmsKind.EXTERNAL, if (id == BambuAmsTrays.EXT_MAIN) 0 else 1, loaded = material != null, material = material,
            colorHex = color, name = "External")
    // Changed: FileFilament grew filamentId and groupId (with defaults), so the old `BambuAms::FileFilament` reference,
    // which can't skip default arguments, became this function.
    private fun f(tool: Int, type: String, color: String?, filamentId: String? = null) = BambuAms.FileFilament(tool, type, color, filamentId)

    @Test fun eachFilamentGetsATrayOfItsMaterialPreferringItsColour() {
        val trays = listOf(lane(0, "PLA", "#FFFFFF"), lane(1, "PLA", "#FF0000"), lane(2, "PETG", "#000000"), lane(3, null, null))
        val match = BambuAms.matchTrays(listOf(f(0, "PLA", "#FF0000"), f(1, "PETG", "#00FF00"), f(2, "PLA", "#0000FF")), trays)
        assertEquals(mapOf(0 to 1, 1 to 2, 2 to 0), (match as BambuAms.TrayMatch.Mapped).toolToLane)
    }

    @Test fun aFilamentWithNoLoadedTrayOfItsMaterialIsReported() {
        val match = BambuAms.matchTrays(listOf(f(0, "PLA", null), f(1, "TPU", null)), listOf(lane(0, "PLA", null), lane(1, null, null)))
        assertEquals(BambuAms.TrayMatch.Missing(listOf(f(1, "TPU", null))), match)
    }

    // Changed (was twoFilamentsNeverShareATrayAndTheExternalSpoolIsNotPicked, expecting Missing): Bambu Studio lets a
    // filament reuse an already-picked tray of exactly the same type and colour (DevMapping.cpp:327-338), so both white
    // PLA filaments now come from AMS tray 0. The external spool is still not picked while the AMS has trays.
    @Test fun sameTypeAndColourMayShareATrayAndTheExternalSpoolIsNotPickedBesideAnAms() {
        val trays = listOf(lane(0, "PLA", "#FFFFFF"), ext(255, "PLA", "#FFFFFF"))
        val match = BambuAms.matchTrays(listOf(f(0, "PLA", "#FFFFFF"), f(1, "PLA", "#FFFFFF")), trays)
        assertEquals(mapOf(0 to 0, 1 to 0), (match as BambuAms.TrayMatch.Mapped).toolToLane)
    }

    @Test fun aTrayIsSharedOnlyOnAnExactColour() {
        val trays = listOf(lane(0, "PLA", "#FFFFFF"), ext(255, "PLA", "#FF0000"))
        val match = BambuAms.matchTrays(listOf(f(0, "PLA", "#FFFFFF"), f(1, "PLA", "#FF0000")), trays)
        assertEquals(BambuAms.TrayMatch.Missing(listOf(f(1, "PLA", "#FF0000"))), match)
    }

    // G10: with no AMS on that side the external holder is a candidate (SelectMachine.cpp:1331-1336, 1364).
    @Test fun withoutAnAmsTheExternalSpoolFeeds() {
        val match = BambuAms.matchTrays(listOf(f(0, "PETG", null)), listOf(ext(255, "PETG", "#0000FF")))
        assertEquals(mapOf(0 to 255), (match as BambuAms.TrayMatch.Mapped).toolToLane)
    }

    @Test fun theUserMayChooseTheExternalSpool() {
        val trays = listOf(lane(0, "PETG", "#FFFFFF"), ext(255, "PETG", "#0000FF"))
        val match = BambuAms.matchTrays(listOf(f(0, "PETG", "#FFFFFF")), trays, external = setOf(0))
        assertTrue((match as BambuAms.TrayMatch.Mapped).trays.getValue(0).external)
    }

    @Test fun supportFilamentAliasesMatch() {
        val match = BambuAms.matchTrays(listOf(f(0, "Support W", null)), listOf(lane(0, "PLA-S", "#FFFFFF")))
        assertEquals(mapOf(0 to 0), (match as BambuAms.TrayMatch.Mapped).toolToLane)
    }

    // G11: between equally good trays the one with the file's filament id and less (known) filament left goes first.
    @Test fun aPartlyUsedSpoolOfTheSameFilamentWinsATie() {
        val trays = listOf(ams(0, 0, "PLA", "#FFFFFF", filamentId = "GFA00", remain = 80), ams(0, 1, "PLA", "#FFFFFF", filamentId = "GFA00", remain = 30))
        assertEquals(mapOf(0 to 1), (BambuAms.matchTrays(listOf(f(0, "PLA", "#FFFFFF", "GFA00")), trays) as BambuAms.TrayMatch.Mapped).toolToLane)
        // Another filament id, or an unknown remain, keeps the lowest slot.
        assertEquals(mapOf(0 to 0), (BambuAms.matchTrays(listOf(f(0, "PLA", "#FFFFFF", "GFL99")), trays) as BambuAms.TrayMatch.Mapped).toolToLane)
        val unknown = listOf(ams(0, 0, "PLA", "#FFFFFF", filamentId = "GFA00", remain = -1), ams(0, 1, "PLA", "#FFFFFF", filamentId = "GFA00", remain = -1))
        assertEquals(mapOf(0 to 0), (BambuAms.matchTrays(listOf(f(0, "PLA", "#FFFFFF", "GFA00")), unknown) as BambuAms.TrayMatch.Mapped).toolToLane)
    }

    // --- Two nozzles (G9): F5's trays, F6's file ---

    private val f5 get() = BambuAmsTrays.read(JSONObject(BambuAmsFixtures.F5))
    /** F6's project filaments: T0 PLA white, T1 ABS black, T2 PETG blue. */
    private val f6 = listOf(f(0, "PLA", "#FFFFFF"), f(1, "ABS", "#000000"), f(2, "PETG", "#0000FF"))

    @Test fun onTwoNozzlesEachFilamentOnlyTakesTraysOnItsOwnSide() {
        // filament_maps "2 1 2": T0 and T2 right (extruder 0), T1 left (extruder 1). The right side has an AMS, so its
        // external holder is not picked automatically (SelectMachine.cpp:1331-1336): T2's PETG is missing.
        val auto = BambuAms.matchTrays(f6, f5.trays, dualNozzle = true, filamentMaps = listOf(2, 1, 2))
        assertEquals(BambuAms.TrayMatch.Missing(listOf(f6[2])), auto)
        // F6: the user chooses the right external holder for T2.
        val chosen = BambuAms.matchTrays(f6, f5.trays, dualNozzle = true, filamentMaps = listOf(2, 1, 2), external = setOf(2))
        assertEquals(mapOf(0 to 0, 1 to 4, 2 to 255), (chosen as BambuAms.TrayMatch.Mapped).toolToLane)
    }

    @Test fun f6NegativeTheOnlyPlaIsOnTheWrongSide() {
        val match = BambuAms.matchTrays(f6, f5.trays, dualNozzle = true, filamentMaps = listOf(1, 1, 1))
        assertTrue((match as BambuAms.TrayMatch.Missing).filaments.contains(f6[0]))
    }

    @Test fun onTwoNozzlesAFileWithoutFilamentMapsMatchesNothing() {
        val match = BambuAms.matchTrays(f6.take(1), f5.trays, dualNozzle = true)
        assertEquals(BambuAms.TrayMatch.Missing(f6.take(1)), match)
        // One nozzle: every tray is on the only side.
        assertEquals(-1, BambuAms.extruderFor(0, emptyList(), dualNozzle = true))
        assertNull(BambuAms.extruderFor(0, emptyList(), dualNozzle = false))
        assertEquals(BambuAmsTrays.DEPUTY_EXTRUDER, BambuAms.extruderFor(0, listOf(1), dualNozzle = true))
        assertEquals(BambuAmsTrays.MAIN_EXTRUDER, BambuAms.extruderFor(0, listOf(2), dualNozzle = true))
    }

    // --- The print command (G12-G15) ---

    private fun command(mapping: List<BambuTray?> = emptyList(), filamentMaps: List<Int> = emptyList(), dualNozzle: Boolean = false) =
        BambuPrintProtocol.ProjectFileCommand(
            sequenceId = "1", fileName = "a.gcode.3mf", subtaskName = "a", md5 = "0123456789ABCDEF0123456789ABCDEF",
            bedType = "textured_plate", bedLeveling = true, flowCalibration = true, timelapse = false,
            amsMapping = mapping, filamentMaps = filamentMaps, dualNozzle = dualNozzle)

    private fun payload(command: BambuPrintProtocol.ProjectFileCommand) = JSONObject(BambuPrintProtocol.buildProjectFilePayload(command)).getJSONObject("print")
    private fun JSONArray.pairs(): List<Pair<Int, Int>> = (0 until length()).map { getJSONObject(it).let { o -> o.getInt("ams_id") to o.getInt("slot_id") } }

    // F6 (CONSTRUCTED): F5's H2D, filament_maps "2 1 2", T2 on the right external holder.
    @Test fun f6TheH2dMappingIsBambuStudios() {
        val mapped = BambuAms.matchTrays(f6, f5.trays, dualNozzle = true, filamentMaps = listOf(2, 1, 2), external = setOf(2)) as BambuAms.TrayMatch.Mapped
        val print = payload(command(List(3) { mapped.trays[it] }, listOf(2, 1, 2), dualNozzle = true))
        assertEquals("[0,4,-1]", print.getJSONArray("ams_mapping").toString())
        assertEquals(listOf(0 to 0, 1 to 0, 255 to 0), print.getJSONArray("ams_mapping2").pairs())
        assertTrue(print.getBoolean("use_ams"))
    }

    @Test fun theCommandRefusesATrayOnTheWrongNozzle() {
        val left = f5.trays.single { it.trayIndex == 4 }
        assertThrows(IllegalArgumentException::class.java) { payload(command(listOf(left), listOf(2), dualNozzle = true)) }
        assertNotNull(BambuAms.mappingProblem(mapOf(0 to left), listOf(2), dualNozzle = true))
        assertNull(BambuAms.mappingProblem(mapOf(0 to left), listOf(1), dualNozzle = true))
    }

    @Test fun theCommandRefusesATrayThePrinterReportsEmpty() {
        val gone = lane(0, null, null).copy(exists = false)
        assertThrows(IllegalArgumentException::class.java) { payload(command(listOf(gone))) }
    }

    // F7 (a) (CONSTRUCTED): one nozzle, T0 on AMS HT 128, T1 unused, T2 on AMS 1 slot 2 (tray index 6).
    @Test fun f7aAnAmsHtIs128AndAnUnusedFilamentIsNoTray() {
        val ht = ams(128, 0, "PA-CF", "#000000", kind = AmsKind.AMS_HT)
        val print = payload(command(listOf(ht, null, ams(1, 2, "PLA", "#FFFFFF")), listOf(1, 1, 1)))
        assertEquals("[128,-1,6]", print.getJSONArray("ams_mapping").toString())
        assertEquals(listOf(128 to 0, 255 to 255, 1 to 2), print.getJSONArray("ams_mapping2").pairs())
        assertTrue(print.getBoolean("use_ams"))
    }

    // F7 (b) (CONSTRUCTED): one nozzle, external spool only. Changed from the old encoding, which sent {255,255} (Bambu
    // Studio's "unused") for the external spool: the external holder is {255,0} (SelectMachine.cpp:1491-1496).
    @Test fun f7bTheExternalSpoolAloneIsNotAnAmsPrint() {
        val print = payload(command(listOf(ext(255, "PLA", "#FFFFFF"))))
        assertEquals("[-1]", print.getJSONArray("ams_mapping").toString())
        assertEquals(listOf(255 to 0), print.getJSONArray("ams_mapping2").pairs())
        assertFalse(print.getBoolean("use_ams"))
    }

    // F7 (c) (CONSTRUCTED): six filaments across two AMS. The old TOOL_COUNT = 4 refused T4 and T5.
    @Test fun f7cSixFilamentsAcrossTwoAmsAreSixEntries() {
        val print = payload(command(List(6) { lane(it, "PLA", null) }))
        assertEquals("[0,1,2,3,4,5]", print.getJSONArray("ams_mapping").toString())
        assertEquals(listOf(0 to 0, 0 to 1, 0 to 2, 0 to 3, 1 to 0, 1 to 1), print.getJSONArray("ams_mapping2").pairs())
    }

    // G15: a one-filament print from an AMS tray is an AMS print (the old code only set use_ams for several filaments).
    @Test fun aSingleColourPrintFromAnAmsTrayUsesTheAms() {
        val print = payload(command(listOf(lane(2, "PLA", "#FFFFFF"))))
        assertTrue(print.getBoolean("use_ams"))
        assertEquals("[2]", print.getJSONArray("ams_mapping").toString())
    }

    @Test fun aMixOfAmsAndExternalIsStillAnAmsPrint() {
        val print = payload(command(listOf(lane(0, "PLA", null), ext(255, "PETG", null))))
        assertTrue(print.getBoolean("use_ams"))
        assertEquals("[0,-1]", print.getJSONArray("ams_mapping").toString())
        assertEquals(listOf(0 to 0, 255 to 0), print.getJSONArray("ams_mapping2").pairs())
    }

    // Changed (was withoutUseAmsAMappingIsIgnored): there is no separate useAms switch any more, use_ams follows the
    // mapping. Without a mapping the payload is the four-entry external-spool form a real P1S accepted.
    @Test fun withoutAMappingTheLegacyExternalSpoolFormIsSent() {
        val print = payload(command())
        assertFalse(print.getBoolean("use_ams"))
        assertEquals("[-1,-1,-1,-1]", print.getJSONArray("ams_mapping").toString())
        assertEquals(List(4) { 255 to 255 }, print.getJSONArray("ams_mapping2").pairs())
    }

    // --- The sliced file (G17) ---

    @Test fun readsTheFileFilamentsFromSliceInfo() {
        val info = """<filament id="1" tray_info_idx="" type="PLA" color="#FF0000" used_m="1" /><filament id="3" type="PETG" color="#00ff00ff" />"""
        assertEquals(listOf(f(0, "PLA", "#FF0000"), f(2, "PETG", "#00FF00")), BambuPrintProtocol.fileFilaments(info))
    }

    // CONSTRUCTED from bbs_3mf.cpp's writer (8800-8846, rows ~8904-8916): an H2D plate using filaments 1 and 2 of three.
    private val h2dSliceInfo = """<?xml version="1.0" encoding="UTF-8"?>
<config>
  <header>
    <header_item key="X-BBL-Client-Type" value="slicer"/>
  </header>
  <plate>
    <metadata key="index" value="1"/>
    <metadata key="extruder_type" value="0,0"/>
    <metadata key="printer_model_id" value="O1D"/>
    <metadata key="nozzle_diameters" value="0.4,0.4"/>
    <metadata key="enable_filament_dynamic_map" value="false"/>
    <metadata key="has_filament_switcher" value="false"/>
    <metadata key="filament_maps" value="2 1 2"/>
    <metadata key="filament_map_mode" value="Manual"/>
    <filament id="1" tray_info_idx="GFA00" type="PLA" color="#FFFFFF" used_m="1.2" used_g="3.5" group_id="1" nozzle_diameter="0.40" volume_type="Standard"/>
    <filament id="2" tray_info_idx="GFB00" type="ABS" color="#000000" used_m="0.4" used_g="1.1" group_id="0" nozzle_diameter="0.40" volume_type="Standard"/>
  </plate>
</config>
"""

    @Test fun readsThePlatesFilamentMapAndNozzleLayout() {
        val plate = BambuPrintProtocol.slicePlate(h2dSliceInfo)
        assertEquals(listOf(2, 1, 2), plate.filamentMaps)
        assertEquals("Manual", plate.filamentMapMode)
        assertTrue(plate.dualNozzle); assertEquals("O1D", plate.printerModelId); assertEquals(2, plate.nozzleCount)
        assertFalse(plate.dynamicNozzleMap); assertFalse(plate.hasFilamentSwitcher)
        assertEquals("filament_maps covers all three project filaments", 3, plate.projectFilamentCount)
        assertEquals(listOf(BambuAms.FileFilament(0, "PLA", "#FFFFFF", "GFA00", 1), BambuAms.FileFilament(1, "ABS", "#000000", "GFB00", 0)), plate.filaments)
    }

    @Test fun aOneNozzleFileIsNotDual() {
        val plate = BambuPrintProtocol.slicePlate(h2dSliceInfo.replace("O1D", "C12").replace("0.4,0.4", "0.4").replace("2 1 2", "1 1 1"))
        assertFalse(plate.dualNozzle)
        // No filament_maps at all: the highest used filament decides the count.
        assertEquals(2, BambuPrintProtocol.slicePlate("<plate><filament id=\"2\" type=\"PLA\" color=\"#FFFFFF\"/></plate>").projectFilamentCount)
    }

    // G16: an H2C dynamic-nozzle-map file is refused until the app does the nozzle-mapping handshake.
    @Test fun anH2cDynamicNozzleMapFileIsFlagged() {
        val plate = BambuPrintProtocol.slicePlate(h2dSliceInfo.replace("O1D", "O1C").replace("\"enable_filament_dynamic_map\" value=\"false\"", "\"enable_filament_dynamic_map\" value=\"true\""))
        assertTrue(plate.dynamicNozzleMap)
        assertTrue(BambuPrintProtocol.DYNAMIC_NOZZLE_MAP_NOT_SUPPORTED.contains("Bambu Studio"))
    }

    @Test fun amsPrintingStaysOffUntilARealPrinterConfirmsIt() {
        assertFalse(BambuAms.AMS_PRINT_VERIFIED)
    }
}

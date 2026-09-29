package net.jamesjennison.klippercompanion

import net.jamesjennison.klippercompanion.BambuAmsTrays.AmsKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

// resources/bambu/helix-p1s-report.json is Helix's real Bambu P1S status report fixture (github.com/FatBoy721/Helix,
// scripts/fixtures/bambu-p1s-report.json, AGPL-3.0-or-later): one AMS, PETG in slot 1, the others empty.
// Fixture names (F1-F8) are the Bambu AMS research report's; the rules they check are Bambu Studio's (BambuAmsTrays).
class BambuAmsTraysTest {
    private fun fixture() = JSONObject(javaClass.getResource("/bambu/helix-p1s-report.json")!!.readText())

    // F1 (real capture).
    @Test fun readsARealP1sReportsAmsTrays() {
        val slots = BambuAmsTrays.parse(fixture())
        // Changed: the external holder used to be tool 254 (its vt_tray wire id). Bambu Studio treats a single vt_tray as
        // the main external spool, 255, whatever id it carries (DeviceManager.cpp:3530-3541); 254 is the left holder of a
        // two-nozzle printer.
        assertEquals(listOf(0, 1, 2, 3, BambuAmsTrays.EXT_MAIN), slots.map { it.tool })
        val first = slots[0]
        assertTrue(first.loaded); assertEquals("PETG", first.material); assertEquals("#0ACC38", first.colorHex)
        assertEquals("AMS 1 · slot 1", first.name)
        assertEquals("AMS", first.unitKind); assertEquals(0, first.extruder); assertEquals("A1", first.shortName)
        assertTrue(slots.subList(1, 4).none { it.loaded })
        assertFalse("the external holder reports no type", slots.last().loaded)
        assertEquals("External spool", slots.last().name)
        assertTrue("tray_now is 255: nothing is active", slots.none { it.active })
    }

    @Test fun theRealP1sReportIsOneNozzleWithTheMainExternalHolder() {
        val report = BambuAmsTrays.read(fixture())
        assertFalse(report.dualNozzle)
        val ext = report.trays.last()
        assertTrue(ext.external); assertEquals(255, ext.amsId); assertEquals(0, ext.slotId); assertEquals(0, ext.extruder)
        assertEquals(AmsKind.AMS, report.trays[0].kind); assertEquals("GFG99", report.trays[0].trayInfoIdx)
    }

    private fun report(units: JSONArray, existBits: String, trayNow: String = "255") =
        JSONObject().put("print", JSONObject().put("ams", JSONObject().put("ams", units).put("tray_exist_bits", existBits).put("tray_now", trayNow)))

    // Changed: carries tray_info_idx too. Bambu Studio only takes a tray's type when both tray_info_idx and tray_type are
    // present (DevFilaSystem.cpp:832-846), and so does BambuAmsTrays now.
    private fun tray(id: Int, type: String = "", color: String = "") =
        JSONObject().put("id", "$id").put("tray_info_idx", if (type.isEmpty()) "" else "GFL99").put("tray_type", type).put("tray_color", color)

    @Test fun aSecondAmsReportedFirstKeepsItsOwnNumbers() {
        val units = JSONArray()
            .put(JSONObject().put("id", "1").put("tray", JSONArray().put(tray(0)).put(tray(1, "PLA", "FF0000FF")).put(tray(2)).put(tray(3))))
            .put(JSONObject().put("id", "0").put("tray", JSONArray().put(tray(0, "PLA", "FFFFFFFF")).put(tray(1)).put(tray(2)).put(tray(3))))
        // Bits 0 (AMS 1 slot 1) and 5 (AMS 2 slot 2) loaded; the printer is feeding lane 5.
        val slots = BambuAmsTrays.parse(report(units, "21", trayNow = "5")).associateBy { it.tool }
        assertEquals("#FF0000", slots[5]!!.colorHex); assertTrue(slots[5]!!.active); assertEquals("AMS 2 · slot 2", slots[5]!!.name)
        assertEquals("B2", slots[5]!!.shortName)
        assertEquals("#FFFFFF", slots[0]!!.colorHex); assertFalse(slots[0]!!.active)
    }

    @Test fun anUnloadedTrayWithStaleDataIsEmpty() {
        val units = JSONArray().put(JSONObject().put("id", "0").put("tray", JSONArray().put(tray(0, "PLA", "FF0000FF"))))
        val slot = BambuAmsTrays.parse(report(units, "0")).single()
        assertFalse(slot.loaded); assertNull(slot.colorHex)
    }

    @Test fun noAmsMeansNoTrays() {
        assertTrue(BambuAmsTrays.parse(JSONObject().put("print", JSONObject())).isEmpty())
    }

    // G7: the type needs both tray_info_idx and tray_type; GFS00 / GFS01 are PLA-S / PA-S.
    @Test fun aTrayTypeNeedsItsFilamentIdAndSupportIdsAreNamedAsBambuStudioDoes() {
        val units = JSONArray().put(JSONObject().put("id", "0").put("tray", JSONArray()
            .put(JSONObject().put("id", "0").put("tray_type", "PLA").put("tray_color", "FF0000FF"))
            .put(JSONObject().put("id", "1").put("tray_info_idx", "GFS00").put("tray_type", "Support").put("tray_color", "FFFFFFFF"))
            .put(JSONObject().put("id", "2").put("tray_info_idx", "GFS01").put("tray_type", "Support").put("tray_color", "FFFFFFFF"))))
        val trays = BambuAmsTrays.read(report(units, "7")).trays
        assertFalse("no tray_info_idx: no type", trays[0].loaded); assertNull(trays[0].material); assertTrue(trays[0].exists)
        assertEquals("PLA-S", trays[1].material); assertEquals("PA-S", trays[2].material)
        assertEquals("PLA-S", BambuAmsTrays.materialKey("Support W")); assertEquals("PA-S", BambuAmsTrays.materialKey("Support G"))
        assertEquals("PETG", BambuAmsTrays.materialKey(" petg "))
    }

    // F2 (from OrcaSlicer tests/slic3rutils/test_dev_mapping.cpp:91-117): three units bound to different extruders.
    @Test fun eachUnitsInfoSaysWhichExtruderItFeeds() {
        val json = """{"print":{"ams":{"tray_exist_bits":"111","ams":[
            {"id":"0","info":"00000001","tray":[{"id":"0","tray_color":"FF0000FF"}]},
            {"id":"1","info":"00000101","tray":[{"id":"0","tray_color":"00FF00FF"}]},
            {"id":"2","tray":[{"id":"0","tray_color":"0000FFFF"}]}]}}}"""
        val trays = BambuAmsTrays.read(JSONObject(json)).trays.filterNot { it.external }
        assertEquals(listOf(0, 4, 8), trays.map { it.trayIndex })
        assertEquals(listOf(0, 1, 0), trays.map { it.extruder })
        assertTrue(trays.all { it.exists })
        assertTrue(trays.all { it.kind == AmsKind.AMS })
    }

    // F3 (same upstream test, :44-53): 0xE in bits 8-11 is bound to no extruder (or a Filament Track Switch).
    @Test fun aUnitBoundToNoExtruderHasNone() {
        val json = """{"print":{"ams":{"tray_exist_bits":"1","ams":[{"id":"0","info":"00000E01","tray":[{"id":"0"}]}]}}}"""
        assertNull(BambuAmsTrays.read(JSONObject(json)).trays.single().extruder)
    }

    // F4 (BambuAmsFixtures.F4, CONSTRUCTED): an X1C with two AMS and one AMS HT, the HT feeding.
    @Test fun anAmsHtIsTrayIndex128WithItsOwnExistBit() {
        val trays = BambuAmsTrays.read(JSONObject(BambuAmsFixtures.F4)).trays
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 128, 255), trays.map { it.trayIndex })
        assertEquals(listOf(0, 5, 128), trays.filter { it.loaded }.map { it.trayIndex })
        val ht = trays.single { it.trayIndex == 128 }
        assertEquals(AmsKind.AMS_HT, ht.kind); assertEquals(128, ht.amsId); assertEquals(0, ht.slotId)
        assertEquals("AMS HT 1", ht.name); assertEquals("A", ht.shortName); assertEquals("PA-CF", ht.material)
        assertTrue("tray_now 128 is the HT", ht.active)
        assertEquals(listOf(128), trays.filter { it.active }.map { it.trayIndex })
        val ext = trays.last()
        assertTrue(ext.external); assertEquals(255, ext.trayIndex); assertFalse(ext.loaded)
    }

    // F5 (BambuAmsFixtures.F5, CONSTRUCTED): an H2D, AMS 2 Pro (id 0) on the right nozzle, AMS (id 1) on the left.
    @Test fun anH2dsUnitsFeedTheirOwnNozzleAndBothExternalHoldersAreRead() {
        val report = BambuAmsTrays.read(JSONObject(BambuAmsFixtures.F5))
        assertTrue(report.dualNozzle)
        val byIndex = report.trays.associateBy { it.trayIndex }
        val pla = byIndex[0]!!
        assertEquals("PLA", pla.material); assertEquals(0, pla.extruder); assertEquals(AmsKind.AMS_2_PRO, pla.kind)
        assertEquals("AMS 2 Pro 1 · slot 1", pla.name)
        assertTrue("extruder 0 is current and its snow is AMS 0 slot 0", pla.active)
        val abs = byIndex[4]!!
        assertEquals("ABS", abs.material); assertEquals(1, abs.extruder); assertEquals(AmsKind.AMS, abs.kind); assertFalse(abs.active)
        val right = byIndex[255]!!
        assertEquals("PETG", right.material); assertEquals(0, right.extruder); assertEquals("External (right)", right.name)
        val left = byIndex[254]!!
        assertFalse(left.loaded); assertEquals(1, left.extruder); assertEquals("External (left)", left.name)
        assertEquals(1, report.trays.count { it.active })
    }

    @Test fun onTwoNozzlesTrayNowIsNotTheActiveTray() {
        // The same H2D with tray_now naming tray 4: ignored there, the extruder's snow decides.
        val json = JSONObject(BambuAmsFixtures.F5); json.getJSONObject("print").getJSONObject("ams").put("tray_now", "4")
        assertEquals(listOf(0), BambuAmsTrays.read(json).trays.filter { it.active }.map { it.trayIndex })
    }

    // F8 (CONSTRUCTED): a vir_slot id with the ams id in bits 8-15 decodes to 254 + 0, the deputy (left) holder.
    @Test fun aVirtualSlotIdWithTheAmsIdInItsHighByteIsDecoded() {
        assertEquals(254, BambuAmsTrays.virtualSlotId(65024))
        assertEquals(255, BambuAmsTrays.virtualSlotId(255))
        val json = """{"print":{"vir_slot":[{"id":"65024","tray_info_idx":"GFA00","tray_type":"PLA","tray_color":"FF0000FF"}]}}"""
        val ext = BambuAmsTrays.read(JSONObject(json)).trays.single()
        assertEquals(254, ext.amsId); assertEquals(1, ext.extruder); assertTrue(ext.loaded); assertEquals("External (left)", ext.name)
    }

    // CONSTRUCTED: on one nozzle, tray_now 254 means the external spool, which is the main holder (255).
    @Test fun oneNozzleTrayNow254IsTheMainExternalHolder() {
        val json = JSONObject().put("print", JSONObject().put("ams", JSONObject().put("ams", JSONArray()).put("tray_now", "254"))
            .put("vt_tray", JSONObject().put("id", "254").put("tray_info_idx", "GFL99").put("tray_type", "PLA").put("tray_color", "FFFFFFFF")))
        val ext = BambuAmsTrays.read(json).trays.single()
        assertEquals(255, ext.trayIndex); assertTrue(ext.active); assertTrue(ext.loaded)
    }

    // CONSTRUCTED: a unit whose ams_exist_bits bit is clear is stale, whatever its trays' bits say.
    @Test fun aUnitMissingFromAmsExistBitsHasNoLoadedTrays() {
        val units = JSONArray().put(JSONObject().put("id", "1").put("tray", JSONArray().put(tray(0, "PLA", "FF0000FF"))))
        val json = report(units, "10"); json.getJSONObject("print").getJSONObject("ams").put("ams_exist_bits", "1")
        assertFalse(BambuAmsTrays.read(json).trays.single().loaded)
    }

    // CONSTRUCTED: the A2L's mixed AMS lite (type 5) uses tray index and exist bit 24 + slot, unit bit 12.
    @Test fun theMixedAmsLiteIsTrayIndex24On() {
        val units = JSONArray().put(JSONObject().put("id", "0").put("info", "5").put("tray", JSONArray().put(tray(0, "PLA", "FF0000FF")).put(tray(1))))
        val json = report(units, "1000000", trayNow = "24"); json.getJSONObject("print").getJSONObject("ams").put("ams_exist_bits", "1000")
        val trays = BambuAmsTrays.read(json).trays
        assertEquals(listOf(24, 25), trays.map { it.trayIndex })
        assertTrue(trays[0].loaded); assertTrue(trays[0].active); assertEquals(AmsKind.AMS_LITE_MIXED, trays[0].kind)
    }

    // No info (old firmware): AMS by default, AMS lite when the caller knows it is an A1 / A1 mini.
    @Test fun aUnitWithoutInfoIsTheKindTheCallerSays() {
        val units = JSONArray().put(JSONObject().put("id", "0").put("tray", JSONArray().put(tray(0, "PLA", "FF0000FF"))))
        assertEquals(AmsKind.AMS, BambuAmsTrays.read(report(units, "1")).trays.single().kind)
        val lite = BambuAmsTrays.read(report(units, "1"), unitKindWithoutInfo = AmsKind.AMS_LITE).trays.single()
        assertEquals(AmsKind.AMS_LITE, lite.kind); assertEquals("AMS Lite 1 · slot 1", lite.name); assertEquals(0, lite.extruder)
    }
}

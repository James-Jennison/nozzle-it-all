package net.jamesjennison.klippercompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

// resources/bambu/helix-p1s-report.json is Helix's real Bambu P1S status report fixture (github.com/FatBoy721/Helix,
// scripts/fixtures/bambu-p1s-report.json, AGPL-3.0-or-later): one AMS, PETG in slot 1, the others empty.
class BambuAmsTraysTest {
    private fun fixture() = JSONObject(javaClass.getResource("/bambu/helix-p1s-report.json")!!.readText())

    @Test fun readsARealP1sReportsAmsTrays() {
        val slots = BambuAmsTrays.parse(fixture())
        assertEquals(listOf(0, 1, 2, 3, BambuAmsTrays.EXTERNAL_TRAY), slots.map { it.tool })
        val first = slots[0]
        assertTrue(first.loaded); assertEquals("PETG", first.material); assertEquals("#0ACC38", first.colorHex)
        assertEquals("AMS 1 · slot 1", first.name)
        assertTrue(slots.subList(1, 4).none { it.loaded })
        assertFalse("the external holder reports no type", slots.last().loaded)
        assertTrue("tray_now is 255: nothing is active", slots.none { it.active })
    }

    private fun report(units: JSONArray, existBits: String, trayNow: String = "255") =
        JSONObject().put("print", JSONObject().put("ams", JSONObject().put("ams", units).put("tray_exist_bits", existBits).put("tray_now", trayNow)))

    private fun tray(id: Int, type: String = "", color: String = "") = JSONObject().put("id", "$id").put("tray_type", type).put("tray_color", color)

    @Test fun aSecondAmsReportedFirstKeepsItsOwnNumbers() {
        val units = JSONArray()
            .put(JSONObject().put("id", "1").put("tray", JSONArray().put(tray(0)).put(tray(1, "PLA", "FF0000FF")).put(tray(2)).put(tray(3))))
            .put(JSONObject().put("id", "0").put("tray", JSONArray().put(tray(0, "PLA", "FFFFFFFF")).put(tray(1)).put(tray(2)).put(tray(3))))
        // Bits 0 (AMS 1 slot 1) and 5 (AMS 2 slot 2) loaded; the printer is feeding lane 5.
        val slots = BambuAmsTrays.parse(report(units, "21", trayNow = "5")).associateBy { it.tool }
        assertEquals("#FF0000", slots[5]!!.colorHex); assertTrue(slots[5]!!.active); assertEquals("AMS 2 · slot 2", slots[5]!!.name)
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
}

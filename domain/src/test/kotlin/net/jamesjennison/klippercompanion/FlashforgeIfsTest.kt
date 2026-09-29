package net.jamesjennison.klippercompanion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

// resources/flashforge/*.json are CONSTRUCTED fixtures, not captures. The IFS part follows the fields upstream OrcaSlicer
// 5298e49d reads in src/slic3r/Utils/Flashforge.cpp (fetch_material_slots, 500-555): four slots, slot 1 PLA Silk, slot 2 PLA,
// slot 3 empty, slot 4 PETG. The printer-state fields (status, temperatures) are not on disk anywhere: a best guess to check
// against a real AD5X reply. flashforge-ad5x-detail-pascal.json is the same station unwrapped with PascalCase keys and
// string/int flags, which Orca also accepts. Replace with real captures once someone has a printer (PROVENANCE.md P-0033).
class FlashforgeIfsTest {
    private fun fixture(name: String) = JSONObject(javaClass.getResource("/flashforge/$name")!!.readText())
    private fun station() = FlashforgeIfs.parseStation(fixture("flashforge-ad5x-detail.json"))

    @Test fun readsTheIfsSlotsOneBased() {
        val s = station()
        assertTrue(s.present)
        assertEquals(listOf(1, 2, 3, 4), s.slots.map { it.slotId })
        assertEquals(listOf(true, true, false, true), s.slots.map { it.hasFilament })
        val slots = FlashforgeIfs.filamentSlots(s)
        assertEquals(listOf(0, 1, 2, 3), slots.map { it.tool })
        assertEquals(listOf("IFS slot 1", "IFS slot 2", "IFS slot 3", "IFS slot 4"), slots.map { it.name })
        assertEquals("PLA SILK", slots[0].material); assertEquals("#FFD700", slots[0].colorHex)
        assertFalse(slots[2].loaded); assertNull(slots[2].colorHex)
    }

    @Test fun unwrappedPascalCaseParsesTheSame() {
        assertEquals(station(), FlashforgeIfs.parseStation(fixture("flashforge-ad5x-detail-pascal.json")))
    }

    @Test fun noStationIsNoSlots() {
        val s = FlashforgeIfs.parseStation(JSONObject("""{"code":0,"detail":{"hasMatlStation":false,"status":"ready"}}"""))
        assertFalse(s.present); assertTrue(s.slots.isEmpty())
        assertTrue(FlashforgeIfs.parseStation(JSONObject("""{"matlStationInfo":{"slotCnt":4,"slotInfos":[]}}""")).present)
    }

    @Test fun errorBodiesAreErrors() {
        assertTrue(FlashforgeIfs.apiError(JSONObject("""{"code":1,"message":"check code error"}"""))!!.contains("check code error"))
        assertTrue(FlashforgeIfs.apiError(JSONObject("""{"err":"2","msg":"x"}"""))!!.contains("error 2: x"))
        assertNull(FlashforgeIfs.apiError(JSONObject("""{"code":0,"message":"Success"}""")))
        assertNull(FlashforgeIfs.apiError(fixture("flashforge-ad5x-detail.json")))
        assertNull(FlashforgeIfs.apiError(JSONObject("{}")))
    }

    private val file = listOf(SlicedFileFilaments.Filament(0, "PLA", "#FF0000"), SlicedFileFilaments.Filament(1, "PETG", "#FFFFFF"))

    @Test fun goldenHeadersForATwoToolMap() {
        // T0 -> IFS slot 2 (tool 1), T1 -> IFS slot 4 (tool 3).
        val mappings = FlashforgeIfs.mappings(listOf(1, 3), station().slots, file)
        val h = FlashforgeIfs.uploadHeaders("SNMOMC9900001", "12345678", 2048, printNow = true, mappings = mappings)
        assertEquals(listOf("serialNumber", "checkCode", "fileSize", "printNow", "levelingBeforePrint", "flowCalibration", "firstLayerInspection",
            "timeLapseVideo", "useMatlStation", "gcodeToolCnt", "materialMappings"), h.keys.toList())
        assertEquals("SNMOMC9900001", h["serialNumber"]); assertEquals("12345678", h["checkCode"]); assertEquals("2048", h["fileSize"])
        assertEquals("true", h["printNow"]); assertEquals("true", h["useMatlStation"]); assertEquals("2", h["gcodeToolCnt"])
        assertEquals("false", h["flowCalibration"]); assertEquals("false", h["firstLayerInspection"])
        val json = String(java.util.Base64.getDecoder().decode(h["materialMappings"]), Charsets.UTF_8)
        assertEquals("""[{"materialName":"PLA","slotId":2,"slotMaterialColor":"#FF0000","toolId":0,"toolMaterialColor":"#FF0000"},""" +
            """{"materialName":"PETG","slotId":4,"slotMaterialColor":"#FAFAFA","toolId":1,"toolMaterialColor":"#FFFFFF"}]""", json)
        assertFalse("standard base64, no line breaks", h["materialMappings"]!!.contains('\n'))
    }

    @Test fun anUploadOnlyRequestTurnsTheStationOff() {
        val h = FlashforgeIfs.uploadHeaders("SN", "code", 10, printNow = false)
        assertEquals("false", h["printNow"]); assertEquals("false", h["useMatlStation"]); assertEquals("0", h["gcodeToolCnt"])
        assertEquals("W10=", h["materialMappings"]) // base64 of []
    }

    @Test fun refusesEmptyUnknownAndMismatchedSlots() {
        val slots = station().slots
        assertThrows(IllegalArgumentException::class.java) { FlashforgeIfs.mappings(listOf(2), slots, file) } // slot 3 is empty
        assertThrows(IllegalArgumentException::class.java) { FlashforgeIfs.mappings(listOf(7), slots, file) } // no slot 8
        assertThrows(IllegalArgumentException::class.java) { FlashforgeIfs.mappings(listOf(0), slots, file) } // PLA file, PLA Silk slot (Orca: SILK)
        assertEquals(1, FlashforgeIfs.mappings(listOf(-1, 3), slots, file).single().toolId)
    }

    @Test fun materialFamiliesFollowOrca() {
        assertEquals("PLA", FlashforgeIfs.normalizeMaterial("pla+"))
        assertEquals("SILK", FlashforgeIfs.normalizeMaterial("PLA Silk"))
        assertEquals("PLACF", FlashforgeIfs.normalizeMaterial("PLA-CF"))
        assertEquals("PETGCF", FlashforgeIfs.normalizeMaterial("PETG CF"))
        assertEquals("ABS", FlashforgeIfs.normalizeMaterial("ASA"))
        assertEquals("TPU", FlashforgeIfs.normalizeMaterial("TPE 95A"))
        assertEquals("PETG", FlashforgeIfs.normalizeMaterial("PETG"))
        assertEquals("", FlashforgeIfs.normalizeMaterial(null))
    }

    @Test fun readsTheDiscoveryReply() {
        val reply = ByteArray(0xC4)
        "AD5X".toByteArray().copyInto(reply, 0)
        "SNMOMC9900001".toByteArray().copyInto(reply, 0x92)
        val found = FlashforgeIfs.parseDiscovery(reply)!!
        assertEquals("AD5X", found.name); assertEquals("SNMOMC9900001", found.serial)
        assertNull(FlashforgeIfs.parseDiscovery(reply.copyOf(0xC3)))
        assertNull(FlashforgeIfs.parseDiscovery(ByteArray(0xC4)))
        assertEquals(20, FlashforgeIfs.DISCOVERY_MESSAGE.size)
    }

    @Test fun statusFieldsReadAsASnapshot() {
        val s = FlashforgeIfs.snapshot(fixture("flashforge-ad5x-detail.json"))
        assertTrue(s.ready); assertEquals("standby", s.state); assertEquals(26.5, s.nozzle!!, 0.01); assertEquals(25.1, s.bed!!, 0.01)
        assertTrue(FlashforgeIfs.isIdle(s))
        val printing = FlashforgeIfs.snapshot(JSONObject("""{"detail":{"status":"printing","printProgress":0.5,"printFileName":"cube.gcode"}}"""))
        assertEquals("printing", printing.state); assertEquals(0.5f, printing.progress, 0.001f); assertFalse(FlashforgeIfs.isIdle(printing))
        assertFalse(FlashforgeIfs.snapshot(JSONObject("{}")).ready)
    }

    @Test fun fileNamesStaySafe() {
        assertEquals("my_cube__2_.gcode", FlashforgeIfs.safeFileName("my cube (2).gcode"))
    }

    @Test fun startingStaysOffUntilARealPrinterConfirmsIt() {
        assertFalse(FlashforgeIfs.START_VERIFIED)
        val refusal = assertThrows(ApiFailure::class.java) { FlashforgeIfs.requireStartVerified("cube.gcode") }
        assertTrue(refusal.message!!.contains("isn't verified on real hardware"))
        FlashforgeIfs.requireStartVerified("cube.gcode", verified = true)
    }
}

package net.jamesjennison.klippercompanion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

// resources/creality/*.json are CONSTRUCTED fixtures, not captures: built from the boxsInfo schema in upstream OrcaSlicer
// 5298e49d src/slic3r/Utils/CrealityPrintAgent.cpp (its comment says it was checked against a K2 Combo F021 on firmware
// 1.1.260206) and the field names in CrealityPrint 59ae8cb print_manage/data/DataType.cpp:121-200.
// creality-k2-boxsinfo.json: external rack (box 0, type 1, TPU); CFS 1 active with A PLA (state 2), B empty, C PETG (state 1,
// selected), D state 1 but no vendor and no type (empty, as Orca treats it); CFS 2 inactive (state 0).
// creality-k2plus-boxsinfo.json: the K2 Plus 1.1.5.5+ state encoding (1 loaded+selected, 2 loaded), CFS 1 and CFS 3 (a gap).
// Replace with real captures once someone has a printer (docs/upstream/PROVENANCE.md P-0033).
class CrealityCfsTest {
    private fun fixture(name: String) = JSONObject(javaClass.getResource("/creality/$name")!!.readText())
    private fun k2() = CrealityCfs.parseBoxes(fixture("creality-k2-boxsinfo.json"))!!

    @Test fun readsTheK2BoxesKeepingRawIdsAndSkippingTheInactiveUnit() {
        val boxes = k2()
        assertEquals("MF003", boxes.unitName)
        assertEquals(listOf(0, 1, 2, 3, CrealityCfs.RACK_BASE), boxes.slots.map { it.tool })
        assertTrue("CFS 2 is inactive", boxes.slots.none { it.boxId == 2 && !it.isRack })
        assertEquals(listOf(0, 2, CrealityCfs.RACK_BASE), boxes.slots.filter { it.loaded }.map { it.tool })
        val petg = boxes.slots.single { it.tool == 2 }
        assertEquals(1, petg.boxId); assertEquals(2, petg.materialId); assertEquals("PETG", petg.type); assertEquals("#FFFFFF", petg.colorHex)
        val rack = boxes.slots.single { it.isRack }
        assertEquals(0, rack.boxId); assertEquals(1, rack.boxType); assertEquals("#000000", rack.colorHex)
        assertFalse("state 1 with no vendor and no type is empty", boxes.slots.single { it.tool == 3 }.loaded)
    }

    @Test fun showsSlotsAsFilamentSlotsWithColoursNormalised() {
        val slots = CrealityCfs.filamentSlots(k2())
        assertEquals(listOf("CFS 1 · slot A", "CFS 1 · slot B", "CFS 1 · slot C", "CFS 1 · slot D", "External spool"), slots.map { it.name })
        assertEquals("PLA", slots[0].material); assertEquals("#FF0000", slots[0].colorHex); assertEquals("Creality", slots[0].vendor)
        assertFalse(slots[1].loaded); assertNull(slots[1].colorHex)
        assertTrue("slot C is selected", slots[2].active)
        assertEquals(listOf(false, false, true, false, false), slots.map { it.active })
    }

    @Test fun k2PlusStateEncodingAndSelectedGiveTheActiveSlotAndGappedBoxesKeepTheirIds() {
        val boxes = CrealityCfs.parseBoxes(fixture("creality-k2plus-boxsinfo.json"))!!
        assertEquals(listOf(0, 1, 2, 3, 8), boxes.slots.map { it.tool })
        assertEquals(listOf(true, true, true, false, true), boxes.slots.map { it.loaded })
        assertEquals(1, CrealityCfs.filamentSlots(boxes).single { it.active }.tool)
        val abs = boxes.slots.single { it.tool == 8 }
        assertEquals("raw box id, not renumbered", 3, abs.boxId); assertEquals("CFS 3 · slot A", abs.label); assertEquals("#AABBCC", abs.colorHex)
    }

    @Test fun colourDropsTheExtraDigit() {
        assertEquals("#FF0000", CrealityCfs.normalizeColor("#0FF0000"))
        assertEquals("#12AB34", CrealityCfs.normalizeColor("#012ab34"))
        assertEquals("#FF0000", CrealityCfs.normalizeColor("#FF0000"))
        assertNull(CrealityCfs.normalizeColor(""))
    }

    @Test fun filamentIdsUseCrealityPrintsEncodingNotOrcas() {
        assertEquals("T1A", CrealityCfs.filamentId(0))
        assertEquals("T1D", CrealityCfs.filamentId(3))
        assertEquals("T2A", CrealityCfs.filamentId(4)) // Orca would send T1E
        assertEquals("T4D", CrealityCfs.filamentId(15))
    }

    private val path = "/mnt/UDISK/printer_data/gcodes/cube.gcode"

    @Test fun singleColourIsOneOpGcodeFile() {
        val messages = CrealityCfs.startMessages(path, emptyList(), k2().slots)
        assertEquals(1, messages.size)
        assertTrue(messages[0].toString(), JSONObject("""{"method":"set","params":{"opGcodeFile":"printprt:$path","enableSelfTest":0}}""").similar(messages[0]))
    }

    @Test fun multiColourIsColorMatchThenMultiColorPrint() {
        val file = listOf(SlicedFileFilaments.Filament(0, "PLA", "#FF0000"), SlicedFileFilaments.Filament(1, "PETG", "#00FF00"))
        val messages = CrealityCfs.startMessages(path, listOf(0, 2), k2().slots, file)
        assertEquals(2, messages.size)
        val colorMatch = JSONObject("""{"method":"set","params":{"colorMatch":{"path":"$path","list":[
            {"id":"T1A","type":"PLA","color":"#FF0000","boxId":1,"materialId":0},
            {"id":"T1B","type":"PETG","color":"#FFFFFF","boxId":1,"materialId":2}]}}}""")
        assertTrue(messages[0].toString(), colorMatch.similar(messages[0]))
        assertTrue(messages[1].toString(), JSONObject("""{"method":"set","params":{"multiColorPrint":{"gcode":"$path","enableSelfTest":0}}}""").similar(messages[1]))
    }

    @Test fun aHighFileToolKeepsItsOwnIdAndUnmappedToolsAreLeftOut() {
        val list = CrealityCfs.startMessages(path, listOf(-1, -1, -1, -1, 2), k2().slots)[0].getJSONObject("params").getJSONObject("colorMatch").getJSONArray("list")
        assertEquals(1, list.length()); assertEquals("T2A", list.getJSONObject(0).getString("id"))
        assertEquals("the slot's type when the file declares none", "PETG", list.getJSONObject(0).getString("type"))
    }

    @Test fun theExternalSpoolIsASingleFilamentOpGcodeFile() {
        val messages = CrealityCfs.startMessages(path, listOf(CrealityCfs.RACK_BASE), k2().slots)
        assertEquals(1, messages.size); assertTrue(messages[0].getJSONObject("params").has("opGcodeFile"))
        assertThrows(IllegalArgumentException::class.java) { CrealityCfs.startMessages(path, listOf(0, CrealityCfs.RACK_BASE), k2().slots) }
    }

    @Test fun refusesEmptyAndUnknownSlotsBeforeBuildingAnything() {
        assertThrows(IllegalArgumentException::class.java) { CrealityCfs.startMessages(path, listOf(1), k2().slots) } // CFS 1 slot B is empty
        assertThrows(IllegalArgumentException::class.java) { CrealityCfs.startMessages(path, listOf(3), k2().slots) } // blank slot D
        assertThrows(IllegalArgumentException::class.java) { CrealityCfs.startMessages(path, listOf(4), k2().slots) } // inactive CFS 2
        assertThrows(IllegalArgumentException::class.java) { CrealityCfs.startMessages("cube.gcode", emptyList(), k2().slots) }
    }

    @Test fun printerPathsForK1AndK2AndFromTheListing() {
        assertEquals("/usr/data/printer_data/gcodes/a.gcode", CrealityCfs.printerPath("a.gcode", "K1C", null))
        assertEquals("/mnt/UDISK/printer_data/gcodes/a.gcode", CrealityCfs.printerPath("a.gcode", "F021", null))
        assertEquals("/mnt/UDISK/printer_data/gcodes/a.gcode", CrealityCfs.printerPath("a.gcode", null, null))
        val listing = JSONObject("""{"retGcodeFileInfo2":[{"name":"old.gcode","path":"/media/x/printer_data/gcodes/sub/old.gcode"}]}""")
        assertEquals("/media/x/printer_data/gcodes/a.gcode", CrealityCfs.printerPath("a.gcode", "K1", listing))
        val sample = JSONObject("""{"retGcodeFileInfo2":[{"name":"a.gcode","path":"/mnt/UDISK/printer_data/gcodes/a.gcode"}]}""")
        assertTrue(CrealityCfs.isListed(sample, "a.gcode")); assertFalse(CrealityCfs.isListed(sample, "b.gcode"))
    }

    @Test fun heartbeatsAreAnsweredWithOkAndOkIsNotState() {
        val beat = """{"ModeCode":"heart_beat","msg":"2026-09-29 10:00:00"}"""
        assertEquals("ok", CrealityCfs.replyTo(beat))
        assertNull(CrealityCfs.parseFrame(beat))
        assertNull(CrealityCfs.parseFrame("ok"))
        assertNull(CrealityCfs.replyTo("ok"))
        assertNull(CrealityCfs.replyTo("""{"state":1}"""))
        assertEquals(1, CrealityCfs.parseFrame("""{"state":1}""")!!.getInt("state"))
        assertNull(CrealityCfs.parseFrame("not json"))
    }

    @Test fun mergedStateReadsAsASnapshot() {
        val state = JSONObject()
        CrealityCfs.merge(state, JSONObject("""{"state":1,"printProgress":42,"printFileName":"/mnt/UDISK/printer_data/gcodes/cube.gcode"}"""))
        CrealityCfs.merge(state, JSONObject("""{"nozzleTemp":"219.50","targetNozzleTemp":"220","bedTemp0":"59.9","targetBedTemp0":60,"printJobTime":600,"layer":7,"TotalLayer":100}"""))
        val s = CrealityCfs.snapshot(state)
        assertTrue(s.ready); assertEquals("printing", s.state); assertEquals("cube.gcode", s.filename); assertEquals(0.42f, s.progress, 0.001f)
        assertEquals(219.5, s.nozzle!!, 0.01); assertEquals(60.0, s.bedTarget!!, 0.01); assertEquals(7, s.currentLayer); assertEquals(100, s.totalLayers)
        assertFalse(CrealityCfs.isIdle(state))
        assertEquals("paused", CrealityCfs.snapshot(JSONObject("""{"state":5}""")).state)
        assertFalse(CrealityCfs.snapshot(JSONObject()).ready)
        assertTrue(CrealityCfs.isIdle(JSONObject("""{"state":1,"deviceState":0}""")))
        assertEquals("The printer reported error 2839.", CrealityCfs.pushedError(JSONObject("""{"err":{"errcode":0,"key":2839}}""")))
        assertNull(CrealityCfs.pushedError(JSONObject("""{"err":{"errcode":0,"key":0}}""")))
    }

    @Test fun identifiesAPrinterFromInfo() {
        val found = PrinterDiscovery.parseCrealityInfo("""{"model":"F021","mac":"54:33:24:28:0C:DB","hostname":"K2-DB19"}""", "192.168.1.40")!!
        assertEquals(PrinterKind.CREALITY, found.kind); assertEquals("K2-DB19", found.name); assertEquals(SlicingPrinterModel.CREALITY_K2, found.slicingModel)
        assertEquals(SlicingPrinterModel.CREALITY_K1C, PrinterDiscovery.parseCrealityInfo("""{"model":"K1C","mac":"x"}""", "h")!!.slicingModel)
        assertNull("mac is required", PrinterDiscovery.parseCrealityInfo("""{"model":"F021"}""", "h"))
        assertNull(PrinterDiscovery.parseCrealityInfo("<html>", "h"))
    }

    @Test fun fileNamesStaySafe() {
        assertEquals("my_cube__2_.gcode", CrealityCfs.safeFileName("my cube (2).gcode"))
        assertEquals("a.gcode", CrealityCfs.safeFileName("/x/y/a.gcode"))
    }

    @Test fun startingStaysOffUntilARealPrinterConfirmsIt() {
        assertFalse(CrealityCfs.START_VERIFIED)
        val refusal = assertThrows(ApiFailure::class.java) { CrealityCfs.requireStartVerified("cube.gcode") }
        assertTrue(refusal.message!!.contains("isn't verified on real hardware"))
        assertTrue(refusal.message!!.contains("cube.gcode"))
        CrealityCfs.requireStartVerified("cube.gcode", verified = true)
    }
}

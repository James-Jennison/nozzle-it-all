package net.jamesjennison.klippercompanion

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

// Android's port of the desktop's filament-lane rules (adapter-paxx FilamentLanesTest, same data), the Moonraker read,
// and the Moonraker upload guard against files sliced for Elegoo's stock firmware.
class FilamentLanesTest {
    // Shaped exactly as AFC_lane.py send_lane_data writes each lane (AFC 484a09b, the commit COSMOS ships): "lane" is the
    // mapped tool as a string, cleared lanes have empty strings everywhere.
    private fun afcLane(tool: String, color: String, material: String, nozzle: Any = 220) = JSONObject().put("color", color).put("material", material)
        .put("bed_temp", 60).put("nozzle_temp", nozzle).put("scan_time", "").put("td", "").put("lane", tool).put("extruder_index", 0).put("spool_id", JSONObject.NULL).put("weight", 1000)

    private val canvas = JSONObject()
        .put("CANVAS_1", afcLane("0", "#FF0000", "PLA"))
        .put("CANVAS_2", afcLane("1", "00ff00", "PETG", 240))
        .put("CANVAS_3", afcLane("2", "", "", ""))
        .put("CANVAS_4", afcLane("3", "#0000FFFF", "pla"))

    @Test fun afcLanesBecomeSlotsByTool() {
        val lanes = FilamentLanes.fromLaneData(JSONObject().put("value", canvas))!!
        assertEquals(listOf(0, 1, 2, 3), lanes.map { it.tool })
        assertEquals(listOf("#FF0000", "#00FF00", null, "#0000FF"), lanes.map { it.colorHex })
        assertEquals(listOf(true, true, false, true), lanes.map { it.loaded })
        assertEquals(240, lanes[1].nozzleTempC)
    }

    // Shaped as the U1 reports print_task_config (adapter-paxx U1ProtocolTest's recorded data): 32 logical entries, the
    // first four physical; unloaded toolheads carry leftover values that must not be shown.
    private val u1TaskConfig = JSONObject()
        .put("filament_exist", org.json.JSONArray(listOf(true, false, true, true) + List(28) { false }))
        .put("filament_vendor", org.json.JSONArray(listOf("Snapmaker", "Snapmaker", "Generic", "NONE") + List(28) { "NONE" }))
        .put("filament_type", org.json.JSONArray(listOf("pla", "PETG", "PLA", "") + List(28) { "" }))
        .put("filament_sub_type", org.json.JSONArray(listOf("Basic", "", "Silk", "") + List(28) { "" }))
        .put("filament_color_rgba", org.json.JSONArray(listOf("FF0000FF", "00FF00FF", "#0000ff", "") + List(28) { "" }))
        .put("filament_official", org.json.JSONArray(listOf(true, true, false, false) + List(28) { false }))

    @Test fun u1ToolheadsBecomeSlotsWithTheirLoadedMaterial() {
        val status = FilamentLanes.read(null, JSONObject().put("print_task_config", u1TaskConfig).put("toolhead", JSONObject().put("extruder", "extruder2")))
        assertEquals("Snapmaker U1 toolheads", status.source)
        val s = status.slots
        assertEquals(listOf(0, 1, 2, 3), s.map { it.tool })
        assertEquals(listOf("T0", "T1", "T2", "T3"), s.map { it.name })
        assertEquals(listOf("PLA", null, "PLA", "LOADED (TYPE NOT REPORTED)"), s.map { it.material })
        assertEquals(listOf("#FF0000", null, "#0000FF", null), s.map { it.colorHex })
        assertEquals(listOf("Snapmaker PLA", "Empty", "Generic PLA", "LOADED (TYPE NOT REPORTED)"), s.map { it.label })
        assertEquals(listOf(false, false, true, false), s.map { it.active })
        // AFC lanes, where present, still win (a COSMOS printer never reports print_task_config).
        assertEquals("Filament changer lanes (AFC)", FilamentLanes.read(JSONObject().put("value", canvas), JSONObject().put("print_task_config", u1TaskConfig)).source)
        assertNull(FilamentLanes.fromU1TaskConfig(JSONObject(), null))
    }

    @Test fun lanesWithoutAToolAreSkippedAsUpstreamDoes() {
        val v = JSONObject().put("lane1", afcLane("", "#FF0000", "PLA")).put("lane2", JSONObject(afcLane("1", "#00FF00", "PLA").toString()).put("lane", 1))
        assertNull(FilamentLanes.fromLaneData(JSONObject().put("value", v)))
        assertNull(FilamentLanes.fromLaneData(JSONObject().put("value", JSONObject())))
        assertNull(FilamentLanes.fromLaneData(null))
    }

    @Test fun happyHareGates() {
        val mmu = JSONObject().put("num_gates", 4).put("gate_status", org.json.JSONArray(listOf(1, 0, 2, -1))).put("gate_material", org.json.JSONArray(listOf("PLA", "PLA", "ABS", "PLA")))
            .put("gate_color", org.json.JSONArray(listOf("ff8800", "000000", "#123456", "ffffff"))).put("gate_temperature", org.json.JSONArray(listOf(210, 200, 250, 200)))
        val lanes = FilamentLanes.fromHappyHare(mmu)!!
        assertEquals(listOf(0, 2), lanes.map { it.tool })
        assertEquals(listOf("#FF8800", "#123456"), lanes.map { it.colorHex })
        assertNull(FilamentLanes.fromHappyHare(JSONObject()))
    }

    @Test fun theFeedingLaneIsMarkedAndTypesReadAsTheDesktopShowsThem() {
        val lanes = FilamentLanes.fromLaneData(JSONObject().put("value", canvas))!!
        val s = FilamentLanes.slots(lanes, currentLane = "CANVAS_2")
        assertEquals(listOf(false, true, false, false), s.map { it.active })
        assertEquals(listOf("PLA", "PETG", null, "PLA"), s.map { it.material })
        assertFalse(s[2].loaded)
        assertEquals(listOf(false, false, true, false), FilamentLanes.slots(lanes, currentTool = 2).map { it.active })
        assertTrue(FilamentLanes.slots(lanes).none { it.active })
    }

    private class FakeMoonraker(var laneData: JSONObject?, var objects: JSONObject, val configFiles: Map<String, String> = emptyMap()) : AutoCloseable {
        val server = MockWebServer()
        val paths = mutableListOf<String>()
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath; synchronized(paths) { paths += path }
                    return when (path) {
                        "/printer/objects/query" -> MockResponse().setBody(JSONObject().put("result", JSONObject().put("eventtime", 1.0).put("status", objects)).toString())
                        "/server/database/item" -> laneData?.let { MockResponse().setBody(JSONObject().put("result", JSONObject().put("namespace", "lane_data").put("key", JSONObject.NULL).put("value", it)).toString()) }
                            ?: MockResponse().setResponseCode(404).setBody("""{"error":{"code":404,"message":"Namespace 'lane_data' not found"}}""")
                        else -> configFiles[path.removePrefix("/server/files/config/")]?.takeIf { path.startsWith("/server/files/config/") }
                            ?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
        }
        val address get() = server.url("/").toString()
        override fun close() = server.shutdown()
    }

    @Test fun moonrakerReadsCanvasLanesOnCosmos() = FakeMoonraker(canvas, JSONObject().put("AFC", JSONObject().put("current_load", "CANVAS_1"))).use { fake ->
        val s = Moonraker(fake.address).filamentSlots()
        assertEquals(listOf(0, 1, 2, 3), s.slots.map { it.tool })
        assertEquals(listOf("#FF0000", "#00FF00", null, "#0000FF"), s.slots.map { it.colorHex })
        assertTrue(s.slots[0].active)
        assertTrue(s.source.contains("AFC"))
        assertEquals(setOf("/printer/objects/query", "/server/database/item"), fake.paths.toSet())
    }

    @Test fun moonrakerFallsBackToHappyHareAndReportsNothingWithoutAChanger() {
        val mmu = JSONObject().put("num_gates", 2).put("gate_status", org.json.JSONArray(listOf(1, 1))).put("gate_material", org.json.JSONArray(listOf("PLA", "PETG")))
            .put("gate_color", org.json.JSONArray(listOf("ff0000", "00ff00"))).put("gate_temperature", org.json.JSONArray(listOf(210, 240))).put("tool", 1)
        FakeMoonraker(null, JSONObject().put("mmu", mmu)).use { fake ->
            val s = Moonraker(fake.address).filamentSlots()
            assertEquals(listOf("PLA", "PETG"), s.slots.map { it.material }); assertTrue(s.slots[1].active)
        }
        FakeMoonraker(null, JSONObject()).use { fake -> assertTrue(Moonraker(fake.address).filamentSlots().slots.isEmpty()) }
    }

    @Test fun moonrakerUploadRefusesAStockElegooFileWithoutSendingAnything() {
        MockWebServer().use { server ->
            server.start()
            val dir = File.createTempFile("live", "").apply { delete(); mkdirs() }
            try {
                val stock = File(dir, "cube.gcode").apply { writeText("G28\nM729\nG1 X10\n") }
                LiveFileChanges(server.url("/").toString(), File(dir, "cache")).use { api ->
                    try { api.prepare(LiveFileChanges.Operation.UPLOAD, "", "cube.gcode", stock); fail("must refuse") }
                    catch (e: IllegalArgumentException) { assertTrue(e.message!!, e.message!!.contains("M729")) }
                }
                assertEquals("nothing reached the printer", 0, server.requestCount)
            } finally { dir.deleteRecursively() }
        }
    }

    // Qidi Box (Q2, X-Plus 4, ...): fixtures built from OrcaSlicer's QidiPrinterAgent and QIDIStudio's readers (constructed,
    // not captured from a printer).
    private val qidiDictionary = "[colordict]\n1 = #FFFFFF\n3 = #FF0000\n\n[fila1]\nfilament = PLA\n[fila11]\nfilament = ABS\n# comment\n"
    private fun qidiStatus(flat: Boolean): JSONObject {
        val vars = JSONObject().put("box_count", 1).put("filament_slot0", 1).put("color_slot0", 3).put("vendor_slot0", 1)
            .put("filament_slot2", 11).put("color_slot2", 1).put("last_load_slot", "slot2").put("enable_box", 1)
        val status = JSONObject().put("save_variables", if (flat) vars else JSONObject().put("variables", vars))
        listOf(0, 1, 0, null).forEachIndexed { i, b -> status.put("box_stepper slot$i", JSONObject().put("runout_button", b ?: JSONObject.NULL)) }
        // Sensors for a second Box that box_count says isn't there.
        status.put("box_stepper slot4", JSONObject().put("runout_button", 0))
        return status
    }

    @Test fun qidiBoxSlotsFromNestedOrFlatSaveVariables() {
        val dictionary = QidiFilamentDictionary.parse(qidiDictionary)
        assertEquals(mapOf(1 to "PLA", 11 to "ABS"), dictionary.filaments); assertEquals("#FF0000", dictionary.colors[3])
        for (flat in listOf(false, true)) {
            val slots = QidiBox.slots(qidiStatus(flat), dictionary)!!
            assertEquals("one Box, four slots", listOf(0, 1, 2, 3), slots.map { it.tool })
            assertEquals(listOf("PLA", null, "ABS", null), slots.map { it.material })
            assertEquals(listOf("#FF0000", null, "#FFFFFF", null), slots.map { it.colorHex })
            assertEquals("QIDI", slots[0].vendor); assertTrue("last_load_slot feeds the nozzle", slots[2].active)
            assertEquals("Box 1 · slot 3", slots[2].name)
        }
        // Without the dictionary the slots still show which hold filament.
        assertEquals("LOADED (TYPE NOT REPORTED)", QidiBox.slots(qidiStatus(false), null)!![0].material)
        assertNull("no Box", QidiBox.slots(JSONObject().put("save_variables", JSONObject()), null))
    }

    // What a printer without a Box answers to the Box's part of the query, as a real Snapmaker U1 did (2026-10-01):
    // Klipper returns every asked object, with null fields for the ones it doesn't have.
    private fun withoutABox(status: JSONObject): JSONObject {
        status.put("save_variables", JSONObject().put("variables", JSONObject.NULL))
        QidiBox.QUERY_OBJECTS.keys.filter { it.startsWith("box_stepper ") }.forEach { status.put(it, JSONObject().put("runout_button", JSONObject.NULL)) }
        return status.put("AFC", JSONObject().put("current_load", JSONObject.NULL)).put("mmu", JSONObject().put("num_gates", JSONObject.NULL))
    }

    @Test fun aPrinterWithoutABoxIsNotReadAsOne() {
        assertFalse(QidiBox.present(withoutABox(JSONObject()))); assertNull(QidiBox.slots(withoutABox(JSONObject()), null))
        val u1 = withoutABox(JSONObject().put("print_task_config", u1TaskConfig).put("toolhead", JSONObject().put("extruder", "extruder")))
        FakeMoonraker(null, u1).use { fake ->
            val s = Moonraker(fake.address).filamentSlots()
            assertEquals("Snapmaker U1 toolheads", s.source)
            assertEquals(listOf("#FF0000", null, "#0000FF", null), s.slots.map { it.colorHex })
        }
        FakeMoonraker(canvas, withoutABox(JSONObject())).use { fake -> assertEquals("Filament changer lanes (AFC)", Moonraker(fake.address).filamentSlots().source) }
    }

    @Test fun moonrakerReadsAQidiBoxWithItsDictionary() =
        FakeMoonraker(null, qidiStatus(false), mapOf(QidiFilamentDictionary.CONFIG_FILE to qidiDictionary)).use { fake ->
            val s = Moonraker(fake.address).filamentSlots()
            assertEquals("Qidi Box", s.source); assertEquals(listOf("PLA", null, "ABS", null), s.slots.map { it.material })
            assertTrue(fake.paths.contains("/server/files/config/${QidiFilamentDictionary.CONFIG_FILE}"))
        }
}

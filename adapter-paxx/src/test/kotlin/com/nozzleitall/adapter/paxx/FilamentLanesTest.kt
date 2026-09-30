package com.nozzleitall.adapter.paxx

import com.nozzleitall.printer.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

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
        assertEquals(240, lanes[1].nozzleTemp)
    }

    @Test fun lanesWithoutAToolAreSkippedAsUpstreamDoes() {
        val v = JSONObject().put("lane1", afcLane("", "#FF0000", "PLA")).put("lane2", JSONObject(afcLane("1", "#00FF00", "PLA").toString()).put("lane", 1))
        assertNull(FilamentLanes.fromLaneData(JSONObject().put("value", v)))
        assertNull(FilamentLanes.fromLaneData(JSONObject().put("value", JSONObject())))
    }

    @Test fun happyHareGates() {
        val mmu = JSONObject().put("num_gates", 4).put("gate_status", listOf(1, 0, 2, -1)).put("gate_material", listOf("PLA", "PLA", "ABS", "PLA"))
            .put("gate_color", listOf("ff8800", "000000", "#123456", "ffffff")).put("gate_temperature", listOf(210, 200, 250, 200))
        val lanes = FilamentLanes.fromHappyHare(mmu)!!
        assertEquals(listOf(0, 2), lanes.map { it.tool })
        assertEquals(listOf("#FF8800", "#123456"), lanes.map { it.colorHex })
        assertNull(FilamentLanes.fromHappyHare(JSONObject()))
    }

    @Test fun oneNozzleItsTemperatureOnTheFeedingLane() {
        val base = PrinterStatus(PrinterState.READY, ConnectionRoute.LAN, toolheads = listOf(Toolhead(0, 215.0, 220.0, 0.4, active = true)))
        val lanes = FilamentLanes.fromLaneData(JSONObject().put("value", canvas))!!
        val s = FilamentLanes.apply(base, lanes, currentLane = "CANVAS_2")
        assertEquals(4, s.toolheads.size)
        assertEquals(listOf(null, 215.0, null, null), s.toolheads.map { it.nozzleTemperature })
        assertEquals(listOf(false, true, false, false), s.toolheads.map { it.active })
        assertEquals(Material(type = "PETG", colorHex = "#00FF00"), s.toolheads[1].material)
        assertNull(s.toolheads[2].material)
        assertEquals("PLA", s.toolheads[3].material?.type)
        // Nothing in the toolhead: the reading goes on the first lane so it stays visible.
        assertEquals(215.0, FilamentLanes.apply(base, lanes).toolheads[0].nozzleTemperature)
    }

    @Test fun klipperSessionReadsCanvasLanesFromMoonraker() = FakeMoonraker(paxx = false).use { fake ->
        // A Centauri Carbon on COSMOS: one extruder, no U1 toolhead report, AFC's lanes in Moonraker's database.
        fake.status = JSONObject(fake.status.toString()).apply {
            remove("print_task_config"); remove("extruder1"); remove("extruder2"); remove("extruder3")
            put("AFC", JSONObject().put("current_load", "CANVAS_1"))
        }
        val config = PrinterConfig(PrinterIdentity("cc", "Centauri", "Elegoo Centauri Carbon", PrinterFamily.KLIPPER, fake.address), MoonrakerAdapter.ID)
        MoonrakerAdapter().open(config).use { session ->
            assertEquals(1, session.status().toolheads.size) // no lane data: the plain extruder
            fake.laneData = canvas
            session.status()
            assertEquals("a missing namespace is not asked about again straight away", 1, fake.calls.count { it.path == "/server/database/item" })
        }
        MoonrakerAdapter().open(config).use { session ->
            val s = session.status()
            assertEquals(listOf(0, 1, 2, 3), s.toolheads.map { it.index })
            assertEquals(listOf("#FF0000", "#00FF00", null, "#0000FF"), s.toolheads.map { it.material?.colorHex })
            assertTrue(s.toolheads[0].active)
        }
    }

    @Test fun u1IgnoresLaneData() = FakeMoonraker().use { fake ->
        fake.laneData = canvas
        val config = PrinterConfig(PrinterIdentity("u1", "U1", "Snapmaker U1", PrinterFamily.PAXX_U1, fake.address), PaxxLanAdapter.ID)
        PaxxLanAdapter().open(config).use { s ->
            s.status()
            assertTrue(fake.calls.none { it.path == "/server/database/item" })
        }
    }
}

class StockElegooGuardTest {
    @Test fun findsStockFirmwareCommands() {
        assertEquals("M729", U1Protocol.stockElegooCommand(sequenceOf("G28", "  m729 ; clean nozzle", "M8213")))
        assertNull(U1Protocol.stockElegooCommand(sequenceOf("PRINT_START EXTRUDER=220", "; M729 in a comment", "M7290", "T1 PURGE_LENGTH=30")))
    }

    @Test fun moonrakerRefusesAStockElegooFileWithoutSendingIt() = FakeMoonraker(paxx = false).use { fake ->
        val f = java.io.File.createTempFile("stock", ".gcode").apply { deleteOnExit(); writeText("G28\nM729\nG1 X10\n") }
        val config = PrinterConfig(PrinterIdentity("cc", "Centauri", "Elegoo Centauri Carbon", PrinterFamily.KLIPPER, fake.address), MoonrakerAdapter.ID)
        MoonrakerAdapter().open(config).use { s ->
            val r = s.upload(f, "cube.gcode")
            assertTrue(r is UploadResult.Failed && "M729" in r.reason)
            assertTrue(fake.calls.none { it.path == "/server/files/upload" })
        }
    }
}

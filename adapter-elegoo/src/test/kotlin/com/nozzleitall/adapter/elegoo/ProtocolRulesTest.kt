package com.nozzleitall.adapter.elegoo

import com.nozzleitall.printer.PrinterState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/** CANVAS slots, SDCP and CC2 message rules, MQTT framing: pure functions, no network. */
class ProtocolRulesTest {
    private val sdcpCanvas = fixture("sdcp_canvas_response.json").getJSONObject("Data").getJSONObject("Data")

    @Test fun canvasSlotsCarryColourTypeBrandAndLoaded() {
        val state = Canvas.parse(sdcpCanvas)!!
        assertEquals(4, state.slots.size)
        assertEquals(listOf("#FF0000", "#00FF00", null, "#0000FF"), state.slots.map { it.colorHex })
        assertEquals(listOf(true, true, false, true), state.slots.map { it.loaded })
        val first = state.slots[0].material!!
        assertEquals("ELEGOO", first.vendor); assertEquals("PLA", first.type); assertEquals("Matte", first.subType)
        // A blank brand (" ") is no brand; a name that repeats the type adds nothing.
        val second = state.slots[1].material!!
        assertNull(second.vendor); assertEquals("PETG", second.type); assertNull(second.subType)
        assertNull("an empty tray has no material", state.slots[2].material)
        assertEquals(1, state.active!!.index)
    }

    @Test fun slotsBecomeToolheadsWithTheNozzleOnTheFeedingSlot() {
        val heads = Canvas.toolheads(Canvas.parse(sdcpCanvas), 219.6, 220.0)
        assertEquals(listOf(0, 1, 2, 3), heads.map { it.index })
        assertEquals(219.6, heads[1].nozzleTemperature!!, 0.0); assertTrue(heads[1].active)
        assertNull(heads[0].nozzleTemperature); assertFalse(heads[0].active)
        assertFalse(heads[2].loaded); assertNull(heads[2].material)
        // No CANVAS: the one nozzle is the one toolhead.
        val single = Canvas.toolheads(null, 25.0, 0.0)
        assertEquals(1, single.size); assertTrue(single[0].active); assertNull(single[0].material)
    }

    @Test fun disconnectedCanvasHasNoSlots() {
        val obj = JSONObject(sdcpCanvas.toString()).apply { getJSONArray("canvas_list").getJSONObject(0).put("connected", 0) }
        assertTrue(Canvas.parse(obj)!!.slots.isEmpty())
        assertNull(Canvas.parse(JSONObject().put("Ack", 0)))
    }

    @Test fun slotMapUsesThePrintersOwnIdsAndRefusesEmptyOrMissingSlots() {
        val state = Canvas.parse(sdcpCanvas)!!
        val map = Canvas.slotMap(listOf(3, -1, 0), state)
        assertTrue(JSONArray("""[{"t":0,"canvas_id":0,"tray_id":3},{"t":2,"canvas_id":0,"tray_id":0}]""").similar(map))
        assertEquals(0, Canvas.slotMap(emptyList(), null).length())
        assertThrows(IllegalArgumentException::class.java) { Canvas.slotMap(listOf(2), state) }
        assertThrows(IllegalArgumentException::class.java) { Canvas.slotMap(listOf(9), state) }
        assertThrows(IllegalArgumentException::class.java) { Canvas.slotMap(listOf(0), null) }
    }

    @Test fun coloursAreNormalised() {
        assertEquals("#A1B2C3", ElegooNet.normalizeColor("a1b2c3"))
        assertEquals("#A1B2C3", ElegooNet.normalizeColor("#a1b2c3ff"))
        assertNull(ElegooNet.normalizeColor("")); assertNull(ElegooNet.normalizeColor("red")); assertNull(ElegooNet.normalizeColor("#12345"))
    }

    @Test fun onlyPrivateHostsAreAccepted() {
        assertEquals(ElegooHost("192.168.1.50", null), ElegooNet.parseHost("192.168.1.50"))
        assertEquals(ElegooHost("192.168.1.50", 3030), ElegooNet.parseHost("http://192.168.1.50:3030/"))
        assertEquals("centauri.local", ElegooNet.parseHost("centauri.local").host)
        assertThrows(IllegalArgumentException::class.java) { ElegooNet.parseHost("8.8.8.8") }
        assertThrows(IllegalArgumentException::class.java) { ElegooNet.parseHost("printer.example.com") }
        assertThrows(IllegalArgumentException::class.java) { ElegooNet.parseHost("") }
    }

    @Test fun sdcpDiscoveryReplyFromTheDocumentParses() {
        val d = Sdcp.parseDiscovery(fixture("sdcp_discovery.json").toString())!!
        assertEquals("000000000001d354", d.mainboardId); assertEquals("Centauri Carbon", d.machineName)
        assertEquals("1.4.44", d.firmwareVersion); assertEquals("Workshop CC", d.name)
        assertNull(Sdcp.parseDiscovery("""{"result":{}}""")); assertNull(Sdcp.parseDiscovery("M99999")); assertNull(Sdcp.parseDiscovery("""{"Id":"x"}"""))
    }

    @Test fun sdcpRequestEnvelopeMatchesElegooLink() {
        val r = Sdcp.request(Sdcp.CMD_CANVAS, JSONObject(), "abc", "100000", 1687069655)
        assertTrue(JSONObject("""{"Id":"abc","Topic":"","Data":{"RequestID":"100000","MainboardID":"abc","TimeStamp":1687069655,"Cmd":324,"From":1,"Data":{}}}""").similar(r))
    }

    @Test fun sdcpStatusMapsOntoTheSharedStates() {
        val printing = Sdcp.parseStatus(fixture("sdcp_status_printing.json"))!!
        assertEquals(PrinterState.PRINTING, printing.state)
        assertEquals("cube.gcode", printing.job!!.fileName); assertEquals(0.42f, printing.job!!.fraction, 0.0001f)
        assertEquals(600.0, printing.job!!.elapsedSeconds!!, 0.0); assertEquals(7, printing.job!!.currentLayer); assertEquals(100, printing.job!!.totalLayers)
        assertEquals(219.6, printing.nozzle!!, 0.0); assertEquals(60.0, printing.bedTarget!!, 0.0)
        fun state(machine: String, sub: Int) = Sdcp.parseStatus(JSONObject("""{"Status":{"CurrentStatus":$machine,"PrintInfo":{"Status":$sub}}}"""))!!.state
        assertEquals(PrinterState.READY, state("[0]", 0))
        assertEquals(PrinterState.FINISHED, state("[0]", 9))
        assertEquals(PrinterState.CANCELLED, state("[0]", 8))
        assertEquals(PrinterState.PAUSED, state("[1]", 6))
        assertEquals(PrinterState.PAUSED, state("[1]", 5))
        assertEquals(PrinterState.ERROR, state("[1]", 14))
        // Busy states offer nothing; "file transferring" yields to the status after it.
        assertEquals(PrinterState.STARTING, state("[5]", 0))
        assertEquals(PrinterState.STARTING, state("[2]", 0))
        assertEquals(PrinterState.PRINTING, state("[2,1]", 13))
        assertEquals(PrinterState.UNKNOWN, state("[42]", 0))
        assertNull(Sdcp.parseStatus(JSONObject("""{"Attributes":{}}""")))
        assertNull("no job outside an active print", Sdcp.parseStatus(JSONObject("""{"Status":{"CurrentStatus":[0],"PrintInfo":{"Status":9,"Filename":"a.gcode"}}}"""))!!.job)
    }

    @Test fun cc2DiscoveryAndStatus() {
        val d = Cc2.parseDiscovery(fixture("cc2_discovery.json").toString())!!
        assertEquals("CC2A0001B2C3", d.serial); assertTrue(d.accessCodeRequired); assertTrue(d.lanOnly); assertEquals("Centauri Carbon 2", d.model)
        assertNull(Cc2.parseDiscovery(fixture("sdcp_discovery.json").toString()))
        val r = Cc2.parseStatus(fixture("cc2_status.json").getJSONObject("result"))!!
        assertEquals(PrinterState.PRINTING, r.state); assertEquals(0.25f, r.job!!.fraction, 0.0001f); assertEquals("benchy.gcode", r.job!!.fileName)
        assertEquals(900.0, r.job!!.elapsedSeconds!!, 0.0); assertEquals(214.5, r.nozzle!!, 0.0); assertEquals(55.0, r.bedTarget!!, 0.0)
        fun state(s: Int, sub: Int) = Cc2.parseStatus(JSONObject("""{"machine_status":{"status":$s,"sub_status":$sub,"progress":0}}"""))!!.state
        assertEquals(PrinterState.READY, state(1, 0)); assertEquals(PrinterState.PAUSED, state(2, 2502)); assertEquals(PrinterState.FINISHED, state(2, 2077))
        assertEquals(PrinterState.CANCELLED, state(2, 2504)); assertEquals(PrinterState.STARTING, state(5, 2901)); assertEquals(PrinterState.ERROR, state(14, 0))
        assertEquals(PrinterState.UNKNOWN, state(99, 0))
        val downloading = Cc2.parseStatus(JSONObject("""{"machine_status":{"status":2,"sub_status":1081,"progress":57},"print_status":{"filename":"x.gcode","print_duration":5}}"""))!!
        assertEquals("a download's progress is not the print's", 0f, downloading.job!!.fraction, 0f)
    }

    @Test fun cc2DeltasMergeButExceptionsAreReplaced() {
        val full = JSONObject("""{"extruder":{"temperature":200,"target":210},"exception":{"exception_code":{"803":{"time":1}}}}""")
        Cc2.merge(full, JSONObject("""{"extruder":{"temperature":205},"exception":{"exception_code":{"1101":{"time":2}}}}"""))
        assertEquals(205, full.getJSONObject("extruder").getInt("temperature")); assertEquals(210, full.getJSONObject("extruder").getInt("target"))
        assertEquals(setOf("1101"), full.getJSONObject("exception").getJSONObject("exception_code").keySet())
    }

    @Test fun cc2StartParamsMatchElegooLink() {
        val p = Cc2.startPrintParams("benchy.gcode", JSONArray("""[{"t":0,"canvas_id":0,"tray_id":2}]"""))
        assertTrue(JSONObject("""{"storage_media":"local","filename":"benchy.gcode","config":{"delay_video":false,"printer_check":false,"print_layout":"A","bedlevel_force":false,"slot_map":[{"t":0,"canvas_id":0,"tray_id":2}]}}""").similar(p))
    }

    @Test fun mqttFramingFollowsTheSpec() {
        // MQTT 3.1.1 section 3.1: CONNECT with clean session, user name and password.
        val c = MqttCodec.connect("c", "u", "p", 60)
        assertArrayEquals(byteArrayOf(0x10, 19, 0, 4, 'M'.code.toByte(), 'Q'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(), 4, 0xC2.toByte(), 0, 60,
            0, 1, 'c'.code.toByte(), 0, 1, 'u'.code.toByte(), 0, 1, 'p'.code.toByte()), c)
        // Remaining length 321 is 0xC1 0x02 (section 2.2.3).
        val big = MqttCodec.encode(MqttCodec.PUBLISH, 0, ByteArray(321))
        assertEquals(0xC1.toByte(), big[1]); assertEquals(0x02.toByte(), big[2])
        val back = MqttCodec.read(ByteArrayInputStream(MqttCodec.publish("a/b", "hi".toByteArray(), 1, 258)), 1000)!!
        val pub = MqttCodec.parsePublish(back)
        assertEquals("a/b", pub.topic); assertEquals(258, pub.packetId); assertEquals(1, pub.qos); assertEquals("hi", String(pub.payload))
        assertThrows(IOException::class.java) { MqttCodec.read(ByteArrayInputStream(big), 100) }
    }
}

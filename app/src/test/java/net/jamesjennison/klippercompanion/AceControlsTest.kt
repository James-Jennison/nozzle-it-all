package net.jamesjennison.klippercompanion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse

class AceControlsTest {
    private val detected = AceStatus(true, "complete", true, listOf(
        AceUnit(0, true, true, 24.0, 30.0, AceDryer(false, null, null), listOf(
            AceLane(0, "loaded", "Bambu Lab", "PLA", "FF0000"), AceLane(1, "empty", null, null, null),
            AceLane(2, "empty", null, null, null), AceLane(3, "empty", null, null, null),
        )),
        AceUnit(1, true, false, 25.0, 31.0, AceDryer(false, null, null), List(4) { AceLane(it, "empty", null, null, null) }),
    ))
    private val undetected = AceStatus(true, "complete", false, emptyList())

    @Test fun loadAndUnloadBuildExactGcode() {
        val load = AceControls.prepareLoad(AceRequest.Load(0, 1), detected)
        assertEquals("ACE_LOAD_HEAD HEAD=1 ACE=0 SLOT=1", load.arguments["script"])
        val unload = AceControls.prepareUnload(AceRequest.Unload(0), detected)
        assertEquals("ACE_UNLOAD_HEAD HEAD=0", unload.arguments["script"])
        val unloadAll = AceControls.prepareUnloadAll(detected)
        assertEquals("ACE_UNLOAD_ALL_HEADS", unloadAll.arguments["script"])
    }
    @Test fun dryStartAndStopBuildExactGcodeAndBoundDuration() {
        val start = AceControls.prepareDryStart(AceRequest.DryStart(0, "55", "720"), detected)
        assertEquals("ACE_DRY ACE=0 TEMP=55 DURATION=720", start.arguments["script"])
        val stop = AceControls.prepareDryStop(AceRequest.DryStop(0), detected)
        assertEquals("ACE_STOP_DRYING ACE=0", stop.arguments["script"])
        assertThrows(IllegalArgumentException::class.java) { AceControls.prepareDryStart(AceRequest.DryStart(0, "81", "60"), detected) }
        assertThrows(IllegalArgumentException::class.java) { AceControls.prepareDryStart(AceRequest.DryStart(0, "55", "0"), detected) }
        assertThrows(IllegalArgumentException::class.java) { AceControls.prepareDryStart(AceRequest.DryStart(0, "55", "2881"), detected) }
    }
    @Test fun switchAndUnknownAceIndexIsRejected() {
        val switch = AceControls.prepareSwitch(AceRequest.Switch(1), detected)
        assertEquals("ACE_SWITCH TARGET=1 AUTOLOAD=1", switch.arguments["script"])
        assertThrows(IllegalArgumentException::class.java) { AceControls.prepareSwitch(AceRequest.Switch(9), detected) }
        assertThrows(IllegalArgumentException::class.java) { AceControls.prepareLoad(AceRequest.Load(0, 9), detected) }
    }
    @Test fun undetectedOrNonIdlePrinterRejectsEveryAction() {
        assertThrows(IllegalArgumentException::class.java) { AceControls.prepareLoad(AceRequest.Load(0, 0), undetected) }
        assertThrows(IllegalArgumentException::class.java) { AceControls.prepareUnloadAll(detected.copy(printState = "printing")) }
        assertThrows(IllegalArgumentException::class.java) { AceControls.prepareUnloadAll(detected.copy(ready = false)) }
    }
    @Test fun parseReadsMultiDeviceLanesAndDryerState() {
        val json = JSONObject("""{"status":{"webhooks":{"state":"ready"},"print_stats":{"state":"complete"},
            "ace":{"device_count":1,"active_device":0,"aces":[{"idx":0,"connected":true,"temp":24,"humidity":30,
            "dryer_status":{"status":"drying","target_temp":55,"remain_time":42},
            "slots":[{"index":0,"status":"ready","brand":"Bambu Lab","material":"PLA","color":"FF0000"},
            {"index":1,"status":"empty"},{"index":2,"status":"empty"},{"index":3,"status":"empty"}]}]}}}""")
        val status = AceControls.parse(json)
        assertTrue(status.hardwareDetected); assertEquals(1, status.units.size)
        val unit = status.units.first()
        assertTrue(unit.dryer.active); assertEquals(42.0, unit.dryer.remainingMinutes!!, 0.0)
        assertEquals("drying", unit.lanes[0].status); assertEquals("Bambu Lab", unit.lanes[0].brand)
        assertEquals("empty", unit.lanes[1].status)
    }
    @Test fun parseTreatsZeroDeviceCountAsUndetected() {
        val json = JSONObject("""{"status":{"webhooks":{"state":"ready"},"print_stats":{"state":"complete"},"ace":{"device_count":0}}}""")
        assertFalse(AceControls.parse(json).hardwareDetected)
        val noAce = JSONObject("""{"status":{"webhooks":{"state":"ready"},"print_stats":{"state":"complete"}}}""")
        assertFalse(AceControls.parse(noAce).hardwareDetected)
    }
    @Test fun aceInspectionQueriesTheAceObject() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"result":{"status":{"webhooks":{"state":"ready"},"print_stats":{"state":"complete"},"ace":{"device_count":0}}}}"""))
            server.start()
            val api = Moonraker(server.url("/").toString())
            try {
                val status = api.aceStatus()
                assertFalse(status.hardwareDetected)
                val request = server.takeRequest()
                assertEquals("/printer/objects/query", request.requestUrl!!.encodedPath)
                assertEquals("", request.requestUrl!!.queryParameter("ace"))
            } finally { api.close() }
        }
    }
}

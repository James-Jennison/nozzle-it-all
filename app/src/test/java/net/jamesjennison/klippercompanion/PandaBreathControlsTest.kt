package net.jamesjennison.klippercompanion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse

class PandaBreathControlsTest {
    private val detected = PandaBreathStatus(true, "complete", true, 25.0, 0.0, "Idle", true, false, "start", false, true)
    private val undetected = PandaBreathStatus(true, "complete", false, null, null, "not detected", false, false, "", false, false)

    @Test fun setTargetBuildsM141AndValidatesRange() {
        val command = PandaBreathControls.prepareSetTarget(PandaBreathRequest.SetTarget("45.00"), detected)
        assertEquals("M141 S45", command.arguments["script"])
        assertEquals("45.00", (command.pandaBreathRequest as PandaBreathRequest.SetTarget).value)
        assertThrows(IllegalArgumentException::class.java) { PandaBreathControls.prepareSetTarget(PandaBreathRequest.SetTarget("61"), detected) }
        assertThrows(IllegalArgumentException::class.java) { PandaBreathControls.prepareSetTarget(PandaBreathRequest.SetTarget("-1"), detected) }
        assertThrows(IllegalArgumentException::class.java) { PandaBreathControls.prepareSetTarget(PandaBreathRequest.SetTarget("40;G28"), detected) }
    }
    @Test fun undetectedOrNonIdlePrinterRejectsEveryAction() {
        assertThrows(IllegalArgumentException::class.java) { PandaBreathControls.prepareSetTarget(PandaBreathRequest.SetTarget("40"), undetected) }
        assertThrows(IllegalArgumentException::class.java) { PandaBreathControls.prepareSetTarget(PandaBreathRequest.SetTarget("40"), detected.copy(printState = "printing")) }
        assertThrows(IllegalArgumentException::class.java) { PandaBreathControls.prepareSetTarget(PandaBreathRequest.SetTarget("40"), detected.copy(ready = false)) }
        assertThrows(IllegalArgumentException::class.java) { PandaBreathControls.prepareStop(undetected) }
    }
    @Test fun autoRequiresFirmwareSupportAndBuildsExactGcode() {
        val on = PandaBreathControls.prepareAuto(PandaBreathRequest.SetAuto(true, "45"), detected)
        assertEquals("PANDA_BREATH_AUTO ENABLE=1 TARGET=45 FILTERTEMP=30 HOTBEDTEMP=80", on.arguments["script"])
        val off = PandaBreathControls.prepareAuto(PandaBreathRequest.SetAuto(false, "45"), detected)
        assertEquals("PANDA_BREATH_AUTO ENABLE=0", off.arguments["script"])
        assertThrows(IllegalArgumentException::class.java) { PandaBreathControls.prepareAuto(PandaBreathRequest.SetAuto(true, "45"), detected.copy(supportsAuto = false)) }
    }
    @Test fun dryUsesFirmwareSpecificCommandAndBoundsHours() {
        val start = PandaBreathControls.prepareDry(PandaBreathRequest.Dry("55", "12"), detected.copy(dryCommand = "start"))
        assertEquals("PANDA_BREATH_DRY_START TEMP=55 HOURS=12", start.arguments["script"])
        val run = PandaBreathControls.prepareDry(PandaBreathRequest.Dry("55", "12"), detected.copy(dryCommand = "run"))
        assertEquals("PANDA_BREATH_DRY_RUN TARGET=55 DURATION=720", run.arguments["script"])
        assertThrows(IllegalArgumentException::class.java) { PandaBreathControls.prepareDry(PandaBreathRequest.Dry("55", "0"), detected.copy(dryCommand = "start")) }
        assertThrows(IllegalArgumentException::class.java) { PandaBreathControls.prepareDry(PandaBreathRequest.Dry("55", "49"), detected.copy(dryCommand = "start")) }
        assertThrows(IllegalArgumentException::class.java) { PandaBreathControls.prepareDry(PandaBreathRequest.Dry("55", "12"), detected.copy(dryCommand = "")) }
    }
    @Test fun stopOnlySendsDryStopWhenActuallyDrying() {
        val idle = PandaBreathControls.prepareStop(detected)
        assertEquals("PANDA_BREATH_AUTO ENABLE=0\nM141 S0", idle.arguments["script"])
        val drying = PandaBreathControls.prepareStop(detected.copy(dryActive = true))
        assertEquals("PANDA_BREATH_DRY_STOP\nPANDA_BREATH_AUTO ENABLE=0\nM141 S0", drying.arguments["script"])
    }
    @Test fun parseDetectsNamedHeaterAndFeatureGatesFromGcodeHelp() {
        val status = JSONObject("""{"status":{"webhooks":{"state":"ready"},"print_stats":{"state":"complete"},"heater_generic panda_breath_heater":{"temperature":24,"target":0},"panda_breath":{}}}""")
        val help = JSONObject().put("PANDA_BREATH_AUTO", "").put("PANDA_BREATH_DRY_START", "")
        val parsed = PandaBreathControls.parse(status, help)
        assertTrue(parsed.detected); assertTrue(parsed.supportsAuto); assertEquals("start", parsed.dryCommand); assertEquals("Idle", parsed.mode)
        val undetectedParsed = PandaBreathControls.parse(JSONObject("""{"status":{"webhooks":{"state":"ready"},"print_stats":{"state":"complete"}}}"""), JSONObject())
        assertFalse(undetectedParsed.detected); assertEquals("not detected", undetectedParsed.mode)
    }
    @Test fun pandaBreathInspectionQueriesCatalogThenObjectsThenHelp() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"result":{"objects":["heater_generic panda_breath_heater","extruder"]}}"""))
            server.enqueue(MockResponse().setBody("""{"result":{"status":{"webhooks":{"state":"ready"},"print_stats":{"state":"complete"},"heater_generic panda_breath_heater":{"temperature":24,"target":0}}}}"""))
            server.enqueue(MockResponse().setBody("""{"result":{}}"""))
            server.start()
            val api = Moonraker(server.url("/").toString())
            try {
                val status = api.pandaBreathStatus()
                assertTrue(status.detected)
                assertEquals("/printer/objects/list", server.takeRequest().requestUrl!!.encodedPath)
                val query = server.takeRequest()
                assertEquals("/printer/objects/query", query.requestUrl!!.encodedPath)
                assertEquals("temperature,target", query.requestUrl!!.queryParameter("heater_generic panda_breath_heater"))
                assertEquals("/printer/gcode/help", server.takeRequest().requestUrl!!.encodedPath)
                assertEquals(3, server.requestCount)
            } finally { api.close() }
        }
    }
}

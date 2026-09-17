package net.jamesjennison.klippercompanion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse

class HeaterControlsTest {
    private val ready=HeaterStatus("extruder1","extruder1",true,"complete",34.0,0.0,0.0,300.0,true)
    @Test fun explicitHeaterAndExactDecimalLimits() {
        val command=HeaterControls.prepare(HeaterRequest("extruder1","40.00"),ready)
        assertEquals("SET_HEATER_TEMPERATURE HEATER=extruder1 TARGET=40",command.arguments["script"])
        assertEquals("extruder1",command.heaterRequest?.heater)
        assertThrows(IllegalArgumentException::class.java){HeaterControls.prepare(HeaterRequest("extruder1","300.000000000000000001"),ready)}
        assertEquals("SET_HEATER_TEMPERATURE HEATER=extruder1 TARGET=0",HeaterControls.prepare(HeaterRequest("extruder1","0.00"),ready.copy(minimum=10.0)).arguments["script"])
    }
    @Test fun invalidInputsCannotBecomeCommands() {
        for(value in listOf("-1","NaN","1e2","40;G28","40\nG28","0x20","","9".repeat(25)))
            assertThrows(IllegalArgumentException::class.java){HeaterControls.prepare(HeaterRequest("extruder1",value),ready)}
        assertThrows(IllegalArgumentException::class.java){HeaterControls.prepare(HeaterRequest("extruder1\nG28","40"),ready)}
    }
    @Test fun unsafeOrUnknownStatesRejectHeating() {
        val states=listOf(ready.copy(activeTool="extruder2"),ready.copy(ready=false),ready.copy(printState="printing"),ready.copy(printState="paused"),ready.copy(printState="error"),ready.copy(printState="unknown"),ready.copy(maximum=null),ready.copy(maximum=Double.NaN),ready.copy(temperature=null),ready.copy(target=null),ready.copy(standardCommand=false))
        for(state in states)assertThrows(IllegalArgumentException::class.java){HeaterControls.prepare(HeaterRequest("extruder1","40"),state)}
        assertEquals("heater_bed",HeaterControls.prepare(HeaterRequest("heater_bed","40"),ready.copy(heater="heater_bed",activeTool="extruder2",maximum=100.0)).heaterRequest?.heater)
    }
    @Test fun parserRequiresConfiguredLimitsAndRejectsOverriddenCommand() {
        val data=JSONObject("""{"status":{"webhooks":{"state":"ready"},"print_stats":{"state":"complete"},"toolhead":{"extruder":"extruder1"},"extruder1":{"temperature":34,"target":0},"configfile":{"settings":{"extruder1":{"min_temp":0,"max_temp":300}}}}}""")
        val state=HeaterControls.parse("extruder1",data)
        assertEquals(300.0,state.maximum!!,0.0);assertEquals("extruder1",state.activeTool)
        data.getJSONObject("status").getJSONObject("configfile").getJSONObject("settings").put("gcode_macro SET_HEATER_TEMPERATURE",JSONObject())
        assertFalse(HeaterControls.parse("extruder1",data).standardCommand)
        assertThrows(IllegalArgumentException::class.java){HeaterControls.prepare(HeaterRequest("extruder1","40"),HeaterControls.parse("extruder1",data))}
    }
    @Test fun heaterInspectionUsesBoundedNamedGetAndProxyBase() {
        MockWebServer().use {server ->
            server.enqueue(MockResponse().setBody("""{"result":{"status":{}}}"""));server.start()
            val api=Moonraker(server.url("/proxy/").toString())
            try {
                val state=api.heaterStatus("heater_bed");assertFalse(state.ready)
                val request=server.takeRequest();assertEquals("GET",request.method)
                assertEquals("/proxy/printer/objects/query",request.requestUrl!!.encodedPath)
                assertEquals("temperature,target",request.requestUrl!!.queryParameter("heater_bed"))
                assertThrows(IllegalArgumentException::class.java){api.heaterStatus("heater_bed;G28")}
                assertEquals(1,server.requestCount)
            }finally{api.close()}
        }
    }
}

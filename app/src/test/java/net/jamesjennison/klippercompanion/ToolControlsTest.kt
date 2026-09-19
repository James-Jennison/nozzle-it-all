package net.jamesjennison.klippercompanion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse

class ToolControlsTest {
    private val ready=ToolStatus(listOf("extruder","extruder1","extruder2","extruder3"),"extruder1",true,"standby")
    @Test fun switchingToAnotherToolBuildsTheExactCommand() {
        val command=ToolControls.prepare(ToolRequest("extruder2"),ready)
        assertEquals("T2",command.arguments["script"])
        assertEquals("extruder2",command.toolRequest?.tool)
        assertEquals("T0",ToolControls.prepare(ToolRequest("extruder"),ready).arguments["script"])
    }
    @Test fun cannotSwitchToTheAlreadyActiveTool() {
        assertThrows(IllegalArgumentException::class.java){ToolControls.prepare(ToolRequest("extruder1"),ready)}
    }
    @Test fun unsafeOrUnknownStatesRejectSwitching() {
        val states=listOf(ready.copy(ready=false),ready.copy(printState="printing"),ready.copy(printState="paused"),ready.copy(printState="error"),ready.copy(printState="unknown"))
        for(state in states)assertThrows(IllegalArgumentException::class.java){ToolControls.prepare(ToolRequest("extruder2"),state)}
        assertThrows(IllegalArgumentException::class.java){ToolControls.prepare(ToolRequest("extruder5"),ready)}
        assertThrows(IllegalArgumentException::class.java){ToolControls.prepare(ToolRequest("extruder2\nG28"),ready)}
    }
    @Test fun parserSortsToolsByIndexAndExcludesUnrelatedSettings() {
        val data=JSONObject("""{"status":{"webhooks":{"state":"ready"},"print_stats":{"state":"standby"},"toolhead":{"extruder":"extruder1"},"configfile":{"settings":{"extruder2":{},"extruder":{},"extruder1":{},"heater_bed":{},"fan":{}}}}}""")
        val state=ToolControls.parse(data)
        assertEquals(listOf("extruder","extruder1","extruder2"),state.tools)
        assertEquals("extruder1",state.activeTool)
    }
    @Test fun toolInspectionUsesBoundedGetAndProxyBase() {
        MockWebServer().use {server ->
            server.enqueue(MockResponse().setBody("""{"result":{"status":{}}}"""));server.start()
            val api=Moonraker(server.url("/proxy/").toString())
            try {
                val state=api.toolStatus();assertFalse(state.ready);assertTrue(state.tools.isEmpty())
                val request=server.takeRequest();assertEquals("GET",request.method)
                assertEquals("/proxy/printer/objects/query",request.requestUrl!!.encodedPath)
                assertEquals(1,server.requestCount)
            }finally{api.close()}
        }
    }
}

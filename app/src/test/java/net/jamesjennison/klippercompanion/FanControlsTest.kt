package net.jamesjennison.klippercompanion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class FanControlsTest {
    private fun status(fan:String="fan_generic e1_fan")=FanStatus(fan,"extruder1",true,"standby",0.0,true,true)
    @Test fun exactGenericAndStandardPercentagesRejectOverflowAndInjection() {
        assertEquals("SET_FAN_SPEED FAN=e1_fan SPEED=0.255",FanControls.prepare(FanRequest("fan_generic e1_fan","25.5","extruder1"),status()).arguments["script"])
        assertEquals("M106 S65.025",FanControls.prepare(FanRequest("fan","25.5","extruder1"),status("fan")).arguments["script"])
        assertEquals("M106 S0",FanControls.prepare(FanRequest("fan","0","extruder1"),status("fan")).arguments["script"])
        for(raw in listOf("100.00000000000000000001","-1","NaN","1e2","20\nG28","1;G28"))assertThrows(IllegalArgumentException::class.java){FanControls.prepare(FanRequest("fan_generic e1_fan",raw,"extruder1"),status())}
    }
    @Test fun hiddenFanSetRoundTripsAndDefaultsEmpty() {
        val hidden=setOf("fan_generic exhaust_fan","fan_generic circulation_fan")
        assertEquals(hidden,FanControls.decodeHidden(FanControls.encodeHidden(hidden)))
        assertEquals(emptySet<String>(),FanControls.decodeHidden("[]"))
        assertEquals(emptySet<String>(),FanControls.decodeHidden("not json"))
    }
    @Test fun excludesAutomaticAndMalformedFanIdentities() {
        val json=JSONObject().put("objects",org.json.JSONArray(listOf("fan","fan_generic e1_fan","heater_fan hotend","controller_fan x","temperature_fan chamber","fan_generic bad name","fan_generic a\nG28","fan_generic e1_fan")))
        assertEquals(listOf("fan","fan_generic e1_fan"),FanControls.catalog(json))
    }
    @Test fun changedToolStateCapabilityOrReadingPreventsPreparation() {
        val s=status();val request=FanRequest(s.fan,"50","extruder1")
        for(bad in listOf(s.copy(activeTool="extruder2"),s.copy(ready=false),s.copy(printState="printing"),s.copy(printState="error"),s.copy(configured=false),s.copy(noMacroOverride=false),s.copy(speed=null),s.copy(speed=Double.NaN),s.copy(speed=1.1),s.copy(fan="fan")))assertThrows(IllegalArgumentException::class.java){FanControls.prepare(request,bad)}
    }
    private fun result(override:String?=null):JSONObject {
        val settings=JSONObject().put("fan_generic e1_fan",JSONObject())
        if(override!=null)settings.put(override,JSONObject())
        return JSONObject().put("status",JSONObject().put("webhooks",JSONObject().put("state","ready")).put("print_stats",JSONObject().put("state","standby")).put("toolhead",JSONObject().put("extruder","extruder1")).put("configfile",JSONObject().put("settings",settings)).put("fan_generic e1_fan",JSONObject().put("speed",0.0)))
    }
    @Test fun missingConfigAndMacroOverrideFailClosed() {
        assertFalse(FanControls.parse("fan_generic e1_fan",result("gcode_macro set_fan_speed")).noMacroOverride)
        val r=result();r.getJSONObject("status").remove("configfile")
        assertFalse(FanControls.parse("fan_generic e1_fan",r).configured)
    }
    @Test fun discoveryAndReviewUseOnlyGetWithExactObjectName() {
        MockWebServer().use {server ->
            server.enqueue(MockResponse().setBody(JSONObject().put("result",JSONObject().put("objects",org.json.JSONArray(listOf("fan_generic e1_fan")))).toString()))
            server.enqueue(MockResponse().setBody(JSONObject().put("result",result()).toString()))
            val api=Moonraker(server.url("/").toString())
            assertEquals(listOf("fan_generic e1_fan"),api.fans());assertTrue(api.fanStatus("fan_generic e1_fan").configured)
            val first=server.takeRequest();assertEquals("GET",first.method);assertEquals("/printer/objects/list",first.path)
            val second=server.takeRequest();assertEquals("GET",second.method);assertEquals("speed",second.requestUrl!!.queryParameter("fan_generic e1_fan"));assertEquals(2,server.requestCount);api.close()
        }
    }
    @Test fun moonrakerFanReaderRemainsUsableAfterResetCancellation() {
        MockWebServer().use {server ->
            val body=JSONObject().put("result",JSONObject().put("objects",org.json.JSONArray(listOf("fan_generic e1_fan")))).toString()
            repeat(2){server.enqueue(MockResponse().setBody(body))}
            val reader:FanReader=Moonraker(server.url("/").toString())
            assertEquals(listOf("fan_generic e1_fan"),reader.fans())
            reader.close() // The reset operation cancels requests; it does not shut down the client.
            assertEquals(listOf("fan_generic e1_fan"),reader.fans())
            repeat(2){assertEquals("GET",server.takeRequest().method)}
            assertEquals(2,server.requestCount);reader.close()
        }
    }
}

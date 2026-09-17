package net.jamesjennison.klippercompanion

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ActiveToolTest {
    private fun status(tool: String?) = JSONObject().apply {
        put("webhooks", JSONObject().put("state", "ready"))
        put("print_stats", JSONObject().put("state", "complete"))
        if(tool != null) put("toolhead", JSONObject().put("extruder", tool))
        put("extruder", JSONObject().put("temperature", 30).put("target", 0))
        put("heater_bed", JSONObject().put("temperature", 35).put("target", 0))
    }
    private fun envelope(s: JSONObject) = JSONObject().put("result", JSONObject().put("status", s)).toString()
    @Test fun selectedToolReadingsNeverComeFromPrimaryExtruder() {
        val s=status("extruder1").put("extruder1", JSONObject().put("temperature", 215).put("target", 220))
        val snapshot=Moonraker.parseSnapshot(JSONObject().put("status",s))
        assertEquals("extruder1",snapshot.activeExtruder)
        assertEquals(215.0,snapshot.nozzle!!,0.0);assertEquals(220.0,snapshot.nozzleTarget!!,0.0)
        assertEquals("NOZZLE · extruder1",snapshot.nozzleLabel)
    }
    @Test fun missingOrInvalidSelectionCannotFallBackToPrimaryHeater() {
        for(tool in listOf(null,"","extruder1","extruder;G28","heater_bed","extruder"+"1".repeat(32))) {
            val snapshot=Moonraker.parseSnapshot(JSONObject().put("status",status(tool)))
            assertNull(snapshot.nozzle);assertNull(snapshot.nozzleTarget)
            assertEquals(35.0,snapshot.bed!!,0.0)
        }
        val single=Moonraker.parseSnapshot(JSONObject().put("status",status("extruder")))
        assertEquals(30.0,single.nozzle!!,0.0)
    }
    @Test fun transportReadsActiveToolWithCurrentIdentityAndUsesOnlyGet() {
        MockWebServer().use {server ->
            server.enqueue(MockResponse().setBody("""{"result":{"klippy_connected":true,"klippy_state":"ready"}}"""))
            server.enqueue(MockResponse().setBody(envelope(status("extruder1"))))
            server.enqueue(MockResponse().setBody(envelope(status("extruder1").put("extruder1",JSONObject().put("temperature",215).put("target",220)))))
            server.start();val api=Moonraker(server.url("/proxy/").toString())
            try {
                val snapshot=api.snapshot();assertEquals(215.0,snapshot.nozzle!!,0.0)
                val requests=List(3){server.takeRequest()}
                assertTrue(requests.all{it.method=="GET"})
                val url=requests.last().requestUrl!!
                assertEquals("/proxy/printer/objects/query",url.encodedPath)
                assertEquals("extruder",url.queryParameter("toolhead"))
                assertEquals("temperature,target",url.queryParameter("extruder1"))
            } finally {api.close()}
        }
    }
    @Test fun toolSwitchDuringReadYieldsUnknownInsteadOfOldToolTemperature() {
        MockWebServer().use {server ->
            server.enqueue(MockResponse().setBody("""{"result":{"klippy_connected":true,"klippy_state":"ready"}}"""))
            server.enqueue(MockResponse().setBody(envelope(status("extruder1"))))
            server.enqueue(MockResponse().setBody(envelope(status("extruder2").put("extruder1",JSONObject().put("temperature",215).put("target",220)))))
            server.start();val api=Moonraker(server.url("/").toString())
            try {
                val snapshot=api.snapshot();assertEquals("extruder2",snapshot.activeExtruder)
                assertNull(snapshot.nozzle);assertNull(snapshot.nozzleTarget)
                assertEquals(3,server.requestCount)
            } finally {api.close()}
        }
    }
}

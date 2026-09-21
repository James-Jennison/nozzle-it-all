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
    @Test fun transportReadsActiveToolInASingleQueryRatherThanTwoSequentialOnes() {
        // Was a 3-request round trip (server/info, then a discovery query, then a second "coherent"
        // query for whichever extruder turned out to be active) - confirmed as the mechanism behind
        // a real Snapmaker U1/PAXX connection-drop report on a higher-latency link. Every
        // extruderN's temperature/target is requested up front instead, in the one objects/query
        // call, so there are only 2 requests total regardless of which toolhead is active.
        MockWebServer().use {server ->
            server.enqueue(MockResponse().setBody("""{"result":{"klippy_connected":true,"klippy_state":"ready"}}"""))
            server.enqueue(MockResponse().setBody(envelope(status("extruder1").put("extruder1",JSONObject().put("temperature",215).put("target",220)))))
            server.start();val api=Moonraker(server.url("/proxy/").toString())
            try {
                val snapshot=api.snapshot();assertEquals(215.0,snapshot.nozzle!!,0.0)
                assertEquals(2,server.requestCount)
                server.takeRequest()
                val url=server.takeRequest().requestUrl!!
                assertEquals("/proxy/printer/objects/query",url.encodedPath)
                assertEquals("extruder",url.queryParameter("toolhead"))
                for(name in listOf("extruder","extruder1","extruder2","extruder3")) assertEquals("temperature,target",url.queryParameter(name))
            } finally {api.close()}
        }
    }
    @Test fun singleQueryIsAtomicSoNoToolSwitchRaceIsPossible() {
        // The old two-step read needed a dedicated test proving a tool switch mid-read couldn't
        // misattribute a reading to the wrong extruder (see git history). With one atomic query
        // there is no window for that race to happen in at all - this instead just confirms
        // parseSnapshot correctly reads whichever extruder the single response says is active.
        MockWebServer().use {server ->
            server.enqueue(MockResponse().setBody("""{"result":{"klippy_connected":true,"klippy_state":"ready"}}"""))
            server.enqueue(MockResponse().setBody(envelope(status("extruder2").put("extruder2",JSONObject().put("temperature",210).put("target",205)))))
            server.start();val api=Moonraker(server.url("/").toString())
            try {
                val snapshot=api.snapshot();assertEquals("extruder2",snapshot.activeExtruder)
                assertEquals(210.0,snapshot.nozzle!!,0.0);assertEquals(205.0,snapshot.nozzleTarget!!,0.0)
                assertEquals(2,server.requestCount)
            } finally {api.close()}
        }
    }
    @Test fun unrequestedToolheadIsSafelyAbsentFromASingleExtruderPrinter() {
        // Klipper's own webhooks.py returns {} for a requested object it doesn't have
        // (lookup_object(obj_name, None)), not an error - confirmed against Klipper's source
        // before relying on it. A plain single-extruder printer querying extruder1/2/3 up front
        // must not fail or misbehave just because those objects don't exist there.
        MockWebServer().use {server ->
            server.enqueue(MockResponse().setBody("""{"result":{"klippy_connected":true,"klippy_state":"ready"}}"""))
            server.enqueue(MockResponse().setBody(envelope(status("extruder"))))
            server.start();val api=Moonraker(server.url("/").toString())
            try {
                val snapshot=api.snapshot();assertEquals(30.0,snapshot.nozzle!!,0.0);assertEquals("extruder",snapshot.activeExtruder)
            } finally {api.close()}
        }
    }
}

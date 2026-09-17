package net.jamesjennison.klippercompanion

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MoonrakerTest {
    @Test fun rejectsPublicCleartextEndpoints() {
        listOf("http://example.com", "http://8.8.8.8", "http://10.0.0.1.attacker.com", "http://192.168.1.1.example.com", "http://172.16.0.1.evil.net").forEach { try { Moonraker.parseAddress(it); fail() } catch(_: IllegalArgumentException) {} }
        assertEquals("192.168.1.2",Moonraker.parseAddress("http://192.168.1.2").host)
        assertEquals("example.com",Moonraker.parseAddress("https://example.com").host)
    }
    @Test fun rejectsCameraTlsDowngrade() {
        try { Moonraker.cameraUrl("https://printer.local/", "http://printer.local:8080/webrtc"); fail() } catch(_: ApiFailure) {}
        assertEquals("https",Moonraker.cameraUrl("https://printer.local/","/webcam/webrtc").scheme)
    }
    @Test fun rejectsCameraHostChanges() {
        try { Moonraker.cameraUrl("http://localhost/", "http://192.0.2.1/image"); fail() } catch(_: ApiFailure) {}
        assertEquals("http://localhost:8080/stream",Moonraker.cameraUrl("http://localhost/","http://localhost:8080/stream").toString())
    }
    @Test fun rejectsCredentialsAndQuery() {
        listOf("http://user:pass@localhost", "http://localhost?token=x", "ftp://localhost", "not a url").forEach {
            try { Moonraker.parseAddress(it); fail("Must reject URL") } catch(_: IllegalArgumentException) { }
        }
    }
    @Test fun retainsProxyBasePath() { assertEquals("http://localhost/printer/", Moonraker.parseAddress("http://localhost/printer").toString()) }
    @Test fun incompleteStatusDoesNotBecomeReady() {
        try { Moonraker.parseSnapshot(JSONObject("""{"status":{}}""")); fail("Missing state must fail") } catch(_: org.json.JSONException) { }
    }
    @Test fun parsesMissingOptionalHeatersAndClampsProgress() {
        val s = Moonraker.parseSnapshot(JSONObject("""{"status":{"webhooks":{"state":"ready"},"print_stats":{"state":"printing"},"virtual_sdcard":{"progress":2}}}"""))
        assertTrue(s.ready); assertNull(s.nozzle); assertEquals(1f,s.progress)
    }
    @Test fun commandEncodesFileAndDoesNotRedirect() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"result":"ok"}""")); server.start()
            val api = Moonraker(server.url("/proxy/").toString())
            api.command(Moonraker.start("folder/a & b.gcode"))
            val req = server.takeRequest()
            assertEquals("POST",req.method); assertEquals("/proxy/printer/print/start",req.requestUrl!!.encodedPath)
            assertEquals("folder/a & b.gcode",req.requestUrl!!.queryParameter("filename"))
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location",server.url("/redirect")))
            try { api.command(Moonraker.start("x.gcode")); fail("Must not follow redirect") } catch(_: ApiFailure) { }
            assertEquals(2,server.requestCount); api.close()
        }
    }
    @Test fun malformedAcknowledgementIsFailure() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("not-json")); server.start()
            val api = Moonraker(server.url("/").toString())
            try { api.command(Moonraker.start("x.gcode")); fail() } catch(_: ApiFailure) { }
            api.close()
        }
    }
    @Test fun lostAcknowledgementDoesNotReplayCommand() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)); server.start()
            val api = Moonraker(server.url("/").toString())
            try { api.command(Moonraker.start("x.gcode")); fail() } catch(_: java.io.IOException) { }
            assertEquals(1,server.requestCount); api.close()
        }
    }
    @Test fun invalidMacroCannotInjectGcode() {
        try { Moonraker.macro("HOME\nG28"); fail() } catch(_: IllegalArgumentException) { }
    }
    @Test fun authenticationErrorIsExplicitWhenNoKeyConfigured() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401)); server.start()
            val api=Moonraker(server.url("/").toString())
            try { api.snapshot(); fail() } catch(e: ApiFailure) { assertTrue(e.message!!.contains("API key")) }
            api.close()
        }
    }
    @Test fun rejectedApiKeyErrorDiffersFromMissingKey() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401)); server.start()
            val api=Moonraker(server.url("/").toString(), "wrong-key")
            try { api.snapshot(); fail() } catch(e: ApiFailure) { assertTrue(e.message!!.contains("rejected")) }
            api.close()
        }
    }
    @Test fun apiKeyIsSentAsHeaderOnEveryRequest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"result":"ok"}""")); server.start()
            val api = Moonraker(server.url("/").toString(), "  secret-key  ")
            api.command(Moonraker.start("x.gcode"))
            assertEquals("secret-key", server.takeRequest().getHeader("X-Api-Key"))
            api.close()
        }
    }
    @Test fun blankApiKeyAddsNoHeader() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"result":"ok"}""")); server.start()
            val api = Moonraker(server.url("/").toString(), "   ")
            api.command(Moonraker.start("x.gcode"))
            assertNull(server.takeRequest().getHeader("X-Api-Key"))
            api.close()
        }
    }
    @Test fun acceptsTailscaleAddressesOverHttp() {
        assertEquals("100.100.1.2", Moonraker.parseAddress("http://100.100.1.2").host)
        assertEquals("printer.tailnet-name.ts.net", Moonraker.parseAddress("http://printer.tailnet-name.ts.net").host)
        try { Moonraker.parseAddress("http://100.200.1.2"); fail("100.200.x.x is outside the Tailscale CGNAT range") } catch(_: IllegalArgumentException) {}
    }
    @Test fun clientRemainsUsableAfterBackgroundCleanup() {
        MockWebServer().use { server ->
            repeat(2) {
                server.enqueue(MockResponse().setBody("""{"result":{"klippy_connected":true,"klippy_state":"ready"}}"""))
                server.enqueue(MockResponse().setBody("""{"result":{"status":{"webhooks":{"state":"ready"},"print_stats":{"state":"printing"}}}}"""))
            }
            server.start()
            val api = Moonraker(server.url("/").toString())
            assertTrue(api.snapshot().ready)
            api.close() // Real implementation cancels calls and evicts pooled connections; it does not terminate the dispatcher.
            assertTrue(api.snapshot().ready)
            assertEquals(4, server.requestCount)
            api.close()
        }
    }

}

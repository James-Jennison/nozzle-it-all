package net.jamesjennison.klippercompanion

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

// PrinterKind.REPETIER's service against a local MockWebServer playing Repetier-Server with CONSTRUCTED replies (see
// RepetierServerTest). No real server or printer. The point: the API key is a header, the file goes to the model library
// (printer/model/<slug>) without autostart, and printer/job (upload and print) never goes out while gated.
class RepetierPrinterServiceTest {
    private class FakeRepetier(val printers: String = """{"data":[{"name":"Prusa","slug":"Prusa_i3"}]}""") {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    if (request.getHeader("X-Api-Key") != "k3y") return MockResponse().setResponseCode(401)
                    return when {
                        request.path == "/printer/info" -> MockResponse().setBody("""{"name":"Shop","software":"Repetier-Server"}""")
                        request.path == "/printer/list" -> MockResponse().setBody(printers)
                        request.path!!.startsWith("/printer/model/") && request.method == "POST" -> MockResponse().setBody("{}")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
        fun service(key: String = "k3y", slug: String = "") = RepetierPrinterService("http://127.0.0.1:${server.port}/", key, slug)
    }

    @Test fun statusChecksTheServerAndItsPrinter() {
        val fake = FakeRepetier()
        fake.server.use {
            val s = fake.service()
            try {
                val snap = s.snapshot()
                assertEquals("unknown", snap.state); assertTrue(snap.ready)
                assertEquals(listOf("/printer/info", "/printer/list"), fake.requests.map { it.path })
                assertTrue("the key is a header, never in a URL", fake.requests.none { it.path!!.contains("k3y") })
            } finally { s.close() }
        }
    }

    @Test fun aWrongKeyOrSlugIsAnError() {
        val fake = FakeRepetier()
        fake.server.use {
            val bad = fake.service(key = "nope")
            try { assertTrue(assertThrows(ApiFailure::class.java) { bad.snapshot() }.message!!.contains("API key")) } finally { bad.close() }
            val other = fake.service(slug = "Delta")
            try { assertTrue(assertThrows(ApiFailure::class.java) { other.snapshot() }.message!!.contains("Delta")) } finally { other.close() }
            val none = fake.service(key = "")
            try { assertEquals(RepetierServer.MISSING_API_KEY, assertThrows(ApiFailure::class.java) { none.snapshot() }.message) } finally { none.close() }
        }
    }

    @Test fun sendStoresTheModelWithoutAutostartAndRefusesToStart() {
        assertFalse(RepetierServer.START_VERIFIED)
        val fake = FakeRepetier()
        fake.server.use {
            val s = fake.service()
            try {
                val file = File.createTempFile("cube", ".gcode").apply { deleteOnExit(); writeText("G28\nG1 X1\n") }
                val refusal = assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(file, "cube.gcode"))) }
                assertEquals(RepetierServer.startNotVerified("cube.gcode"), refusal.message)
                val upload = fake.requests.single { it.method == "POST" }
                assertEquals("/printer/model/Prusa_i3", upload.path)
                val body = upload.body.readUtf8()
                assertTrue(body.contains("name=\"a\"")); assertTrue(body.contains("upload"))
                assertTrue(body.contains("name=\"filename\"; filename=\"cube.gcode\"")); assertTrue(body.contains("G1 X1"))
                assertFalse("no autostart", body.contains("autostart"))
                assertTrue("no job upload", fake.requests.none { it.path!!.startsWith("/printer/job") })
            } finally { s.close() }
        }
    }

    @Test fun startsAndControlsAreRefusedBeforeAnythingIsSent() {
        val fake = FakeRepetier()
        fake.server.use {
            val s = fake.service()
            try {
                assertTrue(assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("Start", "printer/print/start", mapOf("filename" to "a.gcode"))) }.message!!.contains("isn't verified on real hardware"))
                for (path in listOf("printer/print/pause", "printer/print/resume", "printer/print/cancel", "printer/gcode/script"))
                    assertTrue(assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("x", path)) }.message!!.contains("Unsupported command"))
                assertEquals("nothing reached the server", 0, fake.server.requestCount)
            } finally { s.close() }
        }
    }
}

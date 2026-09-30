package net.jamesjennison.klippercompanion

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

// PrinterKind.DUET's service against a local MockWebServer playing a Duet with CONSTRUCTED replies (the shapes upstream
// OrcaSlicer 5298e49d Duet.cpp reads: `{"err":N}` from rr_connect / rr_upload, HTTP 201 from a DSF PUT). No real printer.
// The point: the API is chosen as upstream chooses it, a sliced file is uploaded, and no start (M32/M37) ever goes out
// while DuetRrf.START_VERIFIED is false.
class DuetPrinterServiceTest {
    private class FakeDuet(val dsf: Boolean, val connectBody: String = """{"err":0}""", val uploadBody: String = """{"err":0}""") {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/rr_connect") -> if (dsf) MockResponse().setResponseCode(404) else MockResponse().setBody(connectBody)
                        path.startsWith("/rr_upload") && !dsf -> MockResponse().setBody(uploadBody)
                        path == "/rr_disconnect" && !dsf -> MockResponse().setBody("""{"err":0}""")
                        path == "/machine/status" && dsf -> MockResponse().setBody("""{"state":{"status":"idle"}}""")
                        path.startsWith("/machine/file/gcodes/") && dsf && request.method == "PUT" -> MockResponse().setResponseCode(201)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
        fun service(password: String = "") = DuetPrinterService("http://127.0.0.1:${server.port}/", password)
        val starts: List<RecordedRequest> get() = requests.filter { it.path.orEmpty().startsWith("/rr_gcode") || it.path.orEmpty().startsWith("/machine/code") }
    }

    private fun gcode(vararg lines: String): File = File.createTempFile("cube", ".gcode").apply { deleteOnExit(); writeText(lines.joinToString("\n")) }

    @Test fun standaloneRrfConnectsWithTheDefaultPasswordAndDisconnects() {
        val fake = FakeDuet(dsf = false)
        fake.server.use {
            val s = fake.service()
            try {
                val snap = s.snapshot()
                assertEquals("unknown", snap.state); assertTrue(snap.ready)
                val paths = fake.requests.map { it.path.orEmpty() }
                assertTrue(paths[0], paths[0].startsWith("/rr_connect?password=reprap&time="))
                assertEquals("/rr_disconnect", paths[1])
                assertEquals(2, paths.size)
            } finally { s.close() }
        }
    }

    @Test fun aWrongPasswordIsReportedWithoutThePassword() {
        val fake = FakeDuet(dsf = false, connectBody = """{"err":1}""")
        fake.server.use {
            val s = fake.service("hunter2&x")
            try {
                val e = assertThrows(ApiFailure::class.java) { s.snapshot() }
                assertTrue(e.message!!.contains("password")); assertFalse(e.message!!.contains("hunter2"))
                assertTrue(fake.requests[0].path!!.contains("password=hunter2%26x&"))
            } finally { s.close() }
        }
    }

    @Test fun rrfSendUploadsAndRefusesToStart() {
        assertFalse(DuetRrf.START_VERIFIED)
        val fake = FakeDuet(dsf = false)
        fake.server.use {
            val s = fake.service()
            try {
                val file = gcode("G28", "G1 X10")
                val refusal = assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(file, "my cube.gcode"))) }
                assertEquals(DuetRrf.startNotVerified("my cube.gcode"), refusal.message)
                val upload = fake.requests.single { it.path.orEmpty().startsWith("/rr_upload") }
                assertEquals("POST", upload.method)
                assertTrue(upload.path!!, upload.path!!.startsWith("/rr_upload?name=0:/gcodes/my%20cube.gcode&time="))
                assertEquals(file.readText(), upload.body.readUtf8())
                assertTrue("no start went out: ${fake.starts}", fake.starts.isEmpty())
                assertEquals("/rr_disconnect", fake.requests.last().path)
            } finally { s.close() }
        }
    }

    @Test fun dsfIsChosenWhenRrConnectFailsAndUploadsWithPut() {
        val fake = FakeDuet(dsf = true)
        fake.server.use {
            val s = fake.service()
            try {
                assertEquals("unknown", s.snapshot().state)
                val file = gcode("G1 X1")
                val refusal = assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(file, "cube.gcode"))) }
                assertTrue(refusal.message!!.contains("isn't verified on real hardware"))
                val put = fake.requests.single { it.method == "PUT" }
                assertEquals("/machine/file/gcodes/cube.gcode", put.path)
                assertEquals("G1 X1", put.body.readUtf8())
                assertTrue(fake.starts.isEmpty())
                assertTrue("no rr_disconnect on DSF", fake.requests.none { it.path == "/rr_disconnect" })
            } finally { s.close() }
        }
    }

    @Test fun aFailedRrfUploadIsAnError() {
        val fake = FakeDuet(dsf = false, uploadBody = """{"err":1}""")
        fake.server.use {
            val s = fake.service()
            try {
                val e = assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(gcode("G1"), "a.gcode"))) }
                assertTrue(e.message!!, e.message!!.startsWith("Could not upload a.gcode"))
                assertTrue(fake.starts.isEmpty())
            } finally { s.close() }
        }
    }

    @Test fun startsAndControlsAreRefusedBeforeAnythingIsSent() {
        val fake = FakeDuet(dsf = false)
        fake.server.use {
            val s = fake.service()
            try {
                val start = assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("Start", "printer/print/start", mapOf("filename" to "a.gcode"))) }
                assertEquals(DuetRrf.startNotVerified("a.gcode", uploaded = false), start.message)
                for (path in listOf("printer/print/pause", "printer/print/resume", "printer/print/cancel", "printer/gcode/script"))
                    assertTrue(assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("x", path, mapOf("script" to "G28"))) }.message!!.contains("Unsupported command"))
                assertEquals("nothing reached the printer", 0, fake.server.requestCount)
            } finally { s.close() }
        }
    }
}

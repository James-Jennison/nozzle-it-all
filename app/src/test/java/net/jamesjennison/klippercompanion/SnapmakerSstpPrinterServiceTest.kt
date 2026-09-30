package net.jamesjennison.klippercompanion

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

// PrinterKind.SNAPMAKER_A_SERIES's service against a local MockWebServer playing a Snapmaker 2.0 touchscreen's HTTP API
// (docs/upstream/PROVENANCE.md P-0037; paths and fields from Luban's SstpHttpChannel.ts). No real printer, no network
// beyond 127.0.0.1. The point: status is read without the token, a sliced file is uploaded and nothing ever starts a
// print (SnapmakerSstp.START_VERIFIED is false), every gated control is refused with nothing sent, and the token only
// ever travels in a request body.
class SnapmakerSstpPrinterServiceTest {
    private val secret = "5c0f2a1e-token-secret"

    private class FakeScreen(val answer: (RecordedRequest) -> MockResponse) {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse { requests += request; return answer(request) }
            }
            start()
        }
        val address get() = "http://127.0.0.1:${server.port}/"
        fun paths() = requests.map { it.method + " " + it.requestUrl!!.encodedPath }
        fun noTokenInAnyUrl(token: String) = requests.none { it.requestUrl.toString().contains(token) || it.path.orEmpty().contains(token) }
    }

    private val printing = """{"status":"RUNNING","nozzleTemperature":205.5,"nozzleTargetTemperature":210,"heatedBedTemperature":59.8,
        "heatedBedTargetTemperature":60,"currentWorkNozzle":0,"fileName":"cube.gcode","currentLine":500,"totalLines":1000,
        "estimatedTime":3600,"elapsedTime":1800}"""

    private fun gcode(bytes: Int = 64): File = File.createTempFile("cube", ".gcode").apply { deleteOnExit(); writeText("G1 X1\n".repeat(bytes / 6 + 1)) }

    @Test fun readsStatusWithoutTheToken() {
        val screen = FakeScreen { MockResponse().setBody(printing) }
        screen.server.use {
            val s = SnapmakerSstpPrinterService(screen.address, secret, "A350")
            try {
                val snap = s.snapshot()
                assertEquals("printing", snap.state); assertEquals("cube.gcode", snap.activeFilename); assertEquals(0.5f, snap.progress)
                assertEquals(205.5, snap.nozzle!!, 1e-9); assertEquals(60.0, snap.bedTarget!!, 1e-9); assertEquals(1800.0, snap.printDuration!!, 1e-9)
                assertEquals(listOf(ToolheadTemperature("Nozzle", 205.5, 210.0)), s.toolheadTemperatures())
                assertEquals(listOf("GET /api/v1/status", "GET /api/v1/status"), screen.paths())
                assertTrue(screen.noTokenInAnyUrl(secret))
                assertTrue(screen.requests.all { it.body.size == 0L })
            } finally { s.close() }
        }
    }

    @Test fun anUnacceptedPrinterSaysConnectFirst() {
        for (code in listOf(204, 401, 403)) {
            val screen = FakeScreen { MockResponse().setResponseCode(code) }
            screen.server.use {
                val s = SnapmakerSstpPrinterService(screen.address)
                try { assertEquals(SnapmakerSstp.NEEDS_CONNECT, assertThrows(ApiFailure::class.java) { s.snapshot() }.message) } finally { s.close() }
                assertEquals("status reads never connect", listOf("GET /api/v1/status"), screen.paths())
            }
        }
        val screen = FakeScreen { MockResponse().setResponseCode(500) }
        screen.server.use {
            val s = SnapmakerSstpPrinterService(screen.address)
            try { assertEquals("The Snapmaker answered HTTP 500.", assertThrows(ApiFailure::class.java) { s.snapshot() }.message) } finally { s.close() }
        }
    }

    @Test fun connectSendsTheTokenOnlyInTheBody() {
        val screen = FakeScreen { MockResponse().setBody("""{"token":"issued-token","series":"A350","headType":5}""") }
        screen.server.use {
            // First time: no token, an empty form body.
            val first = SnapmakerSstpPrinterService(screen.address)
            try {
                assertEquals(SnapmakerSstp.ConnectResult.Connected("issued-token", "A350", SnapmakerSstp.Head.DUAL_EXTRUDER), first.connect())
            } finally { first.close() }
            // Again with the kept token: `token=<token>` in the form body.
            val again = SnapmakerSstpPrinterService(screen.address, secret)
            try { again.connect() } finally { again.close() }
            assertEquals(listOf("POST /api/v1/connect", "POST /api/v1/connect"), screen.paths())
            assertEquals("", screen.requests[0].body.readUtf8())
            assertEquals("token=$secret", screen.requests[1].body.readUtf8())
            assertTrue(screen.requests[1].getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
            assertTrue(screen.noTokenInAnyUrl(secret))
        }
    }

    @Test fun connectWaitsForTheTouchscreenAndRefusesALaserOrCnc() {
        val waiting = FakeScreen { MockResponse().setResponseCode(204) }
        waiting.server.use {
            val s = SnapmakerSstpPrinterService(waiting.address)
            try { assertSame(SnapmakerSstp.ConnectResult.AwaitingApproval, s.connect()) } finally { s.close() }
        }
        for (head in listOf(3, 2)) {
            val screen = FakeScreen { MockResponse().setBody("""{"token":"t","series":"A350","headType":$head}""") }
            screen.server.use {
                val s = SnapmakerSstpPrinterService(screen.address)
                try { assertEquals(SnapmakerSstp.NOT_A_PRINTING_HEAD, assertThrows(ApiFailure::class.java) { s.connect() }.message) } finally { s.close() }
            }
        }
    }

    @Test fun sendingUploadsTheFileAndNeverStartsAPrint() {
        assertFalse(SnapmakerSstp.START_VERIFIED)
        val screen = FakeScreen { MockResponse().setBody("{}") }
        screen.server.use {
            val s = SnapmakerSstpPrinterService(screen.address, secret)
            try {
                val file = gcode()
                val refusal = assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(file, "cube.gcode"))) }
                assertEquals(SnapmakerSstp.startNotVerified("cube.gcode"), refusal.message)
                assertEquals("only the upload, no start_print", listOf("POST /api/v1/upload"), screen.paths())
                val body = screen.requests[0].body.readUtf8()
                val tokenField = body.indexOf("name=\"token\""); val fileField = body.indexOf("name=\"file\"; filename=\"cube.gcode\"")
                assertTrue(body, tokenField >= 0 && fileField > tokenField)
                assertTrue(body.contains(secret)); assertTrue(body.contains("G1 X1"))
                assertTrue(screen.noTokenInAnyUrl(secret))
                assertFalse(refusal.message!!.contains(secret))
            } finally { s.close() }
        }
    }

    @Test fun aRefusedUploadIsReportedWithoutTheToken() {
        val screen = FakeScreen { MockResponse().setResponseCode(401) }
        screen.server.use {
            val s = SnapmakerSstpPrinterService(screen.address, secret)
            try {
                val m = assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(gcode(), "cube.gcode"))) }.message!!
                assertEquals(SnapmakerSstp.uploadProblem(401, "cube.gcode"), m)
                assertFalse(m.contains(secret))
            } finally { s.close() }
        }
    }

    @Test fun withoutATokenNothingIsUploaded() {
        val screen = FakeScreen { MockResponse() }
        screen.server.use {
            val s = SnapmakerSstpPrinterService(screen.address)
            try {
                val m = assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(gcode(), "cube.gcode"))) }.message
                assertEquals("Nothing was sent: " + SnapmakerSstp.NEEDS_CONNECT, m)
                assertEquals(0, screen.server.requestCount)
            } finally { s.close() }
        }
    }

    @Test fun everyGatedControlIsRefusedWithNothingSent() {
        val screen = FakeScreen { MockResponse() }
        screen.server.use {
            val s = SnapmakerSstpPrinterService(screen.address, secret)
            try {
                val commands = listOf(
                    PrinterCommand("Start", "printer/print/start", mapOf("filename" to "a.gcode")),
                    PrinterCommand("Pause", "printer/print/pause"),
                    PrinterCommand("Resume", "printer/print/resume"),
                    PrinterCommand("Cancel", "printer/print/cancel"),
                    PrinterCommand("Home", "printer/gcode/script", mapOf("script" to "G28")),
                    PrinterCommand("Stop", "printer/emergency_stop"),
                    PrinterCommand("Heat", "", heaterRequest = HeaterRequest("extruder", "210")),
                    PrinterCommand("Tool", "", toolRequest = ToolRequest("T1")),
                )
                for (c in commands) {
                    val m = assertThrows(ApiFailure::class.java) { s.command(c) }.message!!
                    assertTrue("${c.title}: $m", m.contains("isn't verified on real hardware yet"))
                }
                assertEquals("Did not start a.gcode: starting a print on a Snapmaker printer from Nozzle It All isn't verified on real hardware yet. Start it from the printer's screen.",
                    assertThrows(ApiFailure::class.java) { s.command(commands[0]) }.message)
                assertEquals(SnapmakerSstp.controlNotVerified("stopping a print"), assertThrows(ApiFailure::class.java) { s.command(commands[3]) }.message)
                assertEquals("Unsupported command for a Snapmaker printer.", assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("Macro", "printer/other")) }.message)
                assertEquals("nothing reached the printer", 0, screen.server.requestCount)
            } finally { s.close() }
        }
    }
}

package net.jamesjennison.klippercompanion

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class OctoPrintPrinterServiceTest {
    private fun server(routes: (RecordedRequest) -> MockResponse) = MockWebServer().apply { dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = routes(request) }; start() }
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    private val printing = """{"state":{"flags":{"operational":true,"printing":true,"paused":false,"error":false,"closedOrError":false,"cancelling":false,"finishing":false,"resuming":false,"pausing":false}},"temperature":{"tool0":{"actual":205.0,"target":210.0},"bed":{"actual":59.5,"target":60.0}}}"""

    @Test fun aRunningJobMapsToPrintingWithProgressTemperaturesAndFile() {
        val s = server { r -> when (r.path?.substringBefore('?')) {
            "/api/printer" -> json(printing)
            "/api/job" -> json("""{"job":{"file":{"name":"cube.gcode","display":"cube.gcode"}},"progress":{"completion":42.5,"printTime":600}}""")
            else -> json("{}", 404) } }
        try {
            val snap = OctoPrintPrinterService(s.url("/").toString(), "KEY").snapshot()
            assertEquals("printing", snap.state); assertEquals("cube.gcode", snap.filename); assertEquals(0.425f, snap.progress, 0.001f)
            assertEquals(205.0, snap.nozzle!!, 0.01); assertEquals(60.0, snap.bedTarget!!, 0.01); assertEquals(600.0, snap.printDuration!!, 0.01)
        } finally { s.shutdown() }
    }

    @Test fun everyRequestCarriesTheApiKeyHeader() {
        val seen = mutableListOf<String?>()
        val s = server { r -> seen += r.getHeader("X-Api-Key"); if (r.path!!.startsWith("/api/printer")) json(printing) else json("""{"job":{"file":{}},"progress":{"completion":null}}""") }
        try { OctoPrintPrinterService(s.url("/").toString(), "SECRETKEY").snapshot(); assertTrue(seen.isNotEmpty() && seen.all { it == "SECRETKEY" }) } finally { s.shutdown() }
    }

    @Test fun aPrinterOctoPrintIsNotConnectedToIsReportedNotAsAnError() {
        val s = server { json("""{"error":"Printer is not operational"}""", 409) }
        try { val snap = OctoPrintPrinterService(s.url("/").toString(), "K").snapshot(); assertFalse(snap.ready); assertTrue(snap.state.contains("not connected")) } finally { s.shutdown() }
    }

    @Test fun aRejectedKeyGivesAnActionableMessage() {
        val s = server { json("{}", 403) }
        try { val e = assertThrows(ApiFailure::class.java) { OctoPrintPrinterService(s.url("/").toString(), "BAD").snapshot() }; assertTrue(e.message!!.contains("API key")) } finally { s.shutdown() }
    }

    @Test fun pausedAndFinishedAndErrorStatesAreMapped() {
        fun state(flags: String, completion: String): String {
            val s = server { r -> if (r.path!!.startsWith("/api/printer")) json("""{"state":{"flags":$flags},"temperature":{}}""") else json("""{"job":{"file":{}},"progress":{"completion":$completion}}""") }
            try { return OctoPrintPrinterService(s.url("/").toString(), "K").snapshot().state } finally { s.shutdown() }
        }
        assertEquals("paused", state("""{"operational":true,"paused":true,"printing":false}""", "10"))
        assertEquals("a pause OctoPrint has not finished is still printing", "printing", state("""{"operational":true,"pausing":true,"printing":true}""", "10"))
        assertEquals("complete", state("""{"operational":true,"paused":false,"printing":false}""", "100"))
        assertEquals("standby", state("""{"operational":true}""", "null"))
        assertEquals("error", state("""{"operational":false,"closedOrError":true}""", "null"))
    }

    @Test fun controlCommandsUseTheDocumentedJobEndpoint() {
        val bodies = mutableListOf<String>()
        val s = server { r -> bodies += "${r.method} ${r.path} ${r.body.readUtf8()}"; MockResponse().setResponseCode(204) }
        try {
            val svc = OctoPrintPrinterService(s.url("/").toString(), "K")
            svc.command(PrinterCommand("Pause", "printer/print/pause")); svc.command(PrinterCommand("Resume", "printer/print/resume")); svc.command(PrinterCommand("Cancel", "printer/print/cancel"))
            fun body(i: Int) = org.json.JSONObject(bodies[i].substringAfter("/api/job").trim())
            assertTrue(bodies.all { it.startsWith("POST /api/job") })
            assertEquals("pause", body(0).getString("command")); assertEquals("pause", body(0).getString("action"))
            assertEquals("resume", body(1).getString("action")); assertEquals("cancel", body(2).getString("command"))
        } finally { s.shutdown() }
    }

    @Test fun startingAStoredFileSelectsAndPrintsItWithEachPathSegmentEncoded() {
        val seen = mutableListOf<String>()
        val s = server { r -> seen += "${r.path} ${r.body.readUtf8()}"; MockResponse().setResponseCode(204) }
        try {
            OctoPrintPrinterService(s.url("/").toString(), "K").command(PrinterCommand("Start", "printer/print/start", mapOf("filename" to "my prints/cube 1.gcode")))
            assertTrue(seen[0], seen[0].startsWith("/api/files/local/my%20prints/cube%201.gcode"))
            assertTrue(seen[0].contains("\"print\":true") && seen[0].contains("\"select\""))
        } finally { s.shutdown() }
    }

    @Test fun uploadingSlicedGcodeSendsAMultipartFormThatSelectsAndPrints() {
        var contentType = ""; var text = ""
        val s = server { r -> contentType = r.getHeader("Content-Type").orEmpty(); text = r.body.readUtf8(); MockResponse().setResponseCode(201).setBody("{}") }
        try {
            val f = File.createTempFile("job", ".gcode").apply { writeText("G28\nG1 X10 E1\n"); deleteOnExit() }
            OctoPrintPrinterService(s.url("/").toString(), "K").command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(f, "job.gcode")))
            assertTrue(contentType.startsWith("multipart/form-data")); assertTrue(text.contains("name=\"print\"") && text.contains("true")); assertTrue(text.contains("filename=\"job.gcode\"")); assertTrue(text.contains("G28"))
        } finally { s.shutdown() }
    }

    @Test fun aFileOctoPrintStoredButDidNotStartIsReportedAsAFailure() {
        val s = server { json("""{"done":true,"effectivePrint":false,"effectiveSelect":false,"files":{"local":{"name":"job.gcode"}}}""", 201) }
        try {
            val f = File.createTempFile("job", ".gcode").apply { writeText("G28\n"); deleteOnExit() }
            val e = assertThrows(ApiFailure::class.java) { OctoPrintPrinterService(s.url("/").toString(), "K").command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(f, "job.gcode"))) }
            assertTrue(e.message!!, e.message!!.contains("did not start"))
        } finally { s.shutdown() }
    }

    @Test fun aHostileFileNameIsRefusedBeforeAnythingIsSent() {
        val f = File.createTempFile("job", ".gcode").apply { writeText("G28\n"); deleteOnExit() }
        assertThrows(IllegalArgumentException::class.java) { OctoPrintPrinterService("http://127.0.0.1:1/", "K").command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(f, "../../etc/passwd"))) }
    }

    @Test fun theFileListWalksFoldersAndKeepsOnlyMachineCode() {
        val s = server { json("""{"files":[{"type":"machinecode","path":"a.gcode"},{"type":"model","path":"m.stl"},{"type":"folder","children":[{"type":"machinecode","path":"sub/b.gcode"}]}]}""") }
        try { assertEquals(listOf("a.gcode", "sub/b.gcode"), OctoPrintPrinterService(s.url("/").toString(), "K").catalog().files) } finally { s.shutdown() }
    }

    // Against a real OctoPrint (docker run octoprint/octoprint with its virtual printer, API key TESTAPIKEY0123456789ABCDEF012345 on :5000); skipped when none is running.
    @Test fun againstARealOctoPrintServerAPrintCanBeStartedPausedResumedAndCancelled() {
        val key = "TESTAPIKEY0123456789ABCDEF012345"
        val up = runCatching { java.net.URL("http://127.0.0.1:5000/api/version").openConnection().apply { setRequestProperty("X-Api-Key", key); connectTimeout = 800 }.getInputStream().use { it.readBytes().isNotEmpty() } }.getOrDefault(false)
        assumeTrue("no OctoPrint on 127.0.0.1:5000", up)
        val svc = OctoPrintPrinterService("127.0.0.1:5000", key)
        val gcode = File.createTempFile("nozzle-live", ".gcode").apply { writeText(buildString { append("G28\nM104 S0\n"); repeat(3000) { append("G1 X${it % 100} Y${it % 90} E0.01 F600\n") } }); deleteOnExit() }
        fun wait(vararg want: String, timeout: Int = 30): String { var last = ""; repeat(timeout * 2) { last = svc.snapshot().state; if (last in want) return last; Thread.sleep(500) }; return last }
        // Leave the server tidy whatever happens: a job left running or paused would make the next run's upload store but not start.
        if (svc.snapshot().state in setOf("printing", "paused")) runCatching { svc.command(PrinterCommand("Cancel", "printer/print/cancel")) }
        try {
            assertTrue(svc.snapshot().ready)
            svc.command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(gcode, "nozzle-live.gcode")))
            assertEquals("printing", wait("printing"))
            assertTrue("uploaded file is listed", svc.catalog().files.contains("nozzle-live.gcode"))
            svc.command(PrinterCommand("Pause", "printer/print/pause")); assertEquals("paused", wait("paused", timeout = 90))
            svc.command(PrinterCommand("Resume", "printer/print/resume")); assertEquals("printing", wait("printing"))
            svc.command(PrinterCommand("Cancel", "printer/print/cancel")); assertTrue(wait("standby", "complete") in setOf("standby", "complete"))
        } finally { if (svc.snapshot().state in setOf("printing", "paused")) runCatching { svc.command(PrinterCommand("Cancel", "printer/print/cancel")) } }
    }
}

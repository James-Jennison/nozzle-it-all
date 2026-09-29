package net.jamesjennison.klippercompanion

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

// PrinterKind.CREALITY and PrinterKind.FLASHFORGE services against a local MockWebServer that plays the printer with
// CONSTRUCTED replies (shapes from OrcaSlicer's CrealityPrint / Flashforge hosts and CrealityPrint; see CrealityCfsTest and
// FlashforgeIfsTest). No real printer. The point: status and slots are read, a sliced file is uploaded, and the start is
// refused (CrealityCfs.START_VERIFIED / FlashforgeIfs.START_VERIFIED are false) without any start message going out.
class CrealityFlashforgeServiceTest {
    private val boxsInfo = """{"boxsInfo":{"name":"MF003","materialBoxs":[
        {"id":0,"state":1,"type":1,"materials":[{"id":0,"state":1,"vendor":"Generic","type":"TPU","color":"#0000000","selected":0}]},
        {"id":1,"state":1,"type":0,"materials":[
          {"id":0,"state":2,"vendor":"Creality","type":"PLA","color":"#0FF0000","selected":1},
          {"id":1,"state":0,"vendor":"","type":"","color":"#0000000","selected":0},
          {"id":2,"state":1,"vendor":"Generic","type":"PETG","color":"#0FFFFFF","selected":0},
          {"id":3,"state":0,"vendor":"","type":"","color":"#0000000","selected":0}]}]}}"""

    /** A fake K2: `/info`, `/upload/<name>` and the port-9999 socket (served on the same port here). */
    private class FakeCreality(private val boxes: String) {
        val wsReceived = CopyOnWriteArrayList<String>()
        val http = CopyOnWriteArrayList<RecordedRequest>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Upgrade").equals("websocket", ignoreCase = true)) return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"ModeCode":"heart_beat","msg":"2026-09-29 10:00:00"}""")
                            webSocket.send("ok")
                            webSocket.send("""{"state":0,"deviceState":0,"printProgress":0,"printFileName":""}""")
                            webSocket.send("""{"nozzleTemp":"26.10","targetNozzleTemp":"0","bedTemp0":"25.00","targetBedTemp0":"0"}""")
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            wsReceived += text
                            if (text.contains("boxsInfo")) webSocket.send(boxes)
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                    })
                    http += request
                    return when {
                        request.path == "/info" -> MockResponse().setBody("""{"model":"F021","mac":"54:33:24:28:0C:DB","hostname":"K2-DB19"}""")
                        request.path?.startsWith("/upload/") == true && request.method == "POST" -> MockResponse().setBody("""{"code":0,"message":"OK"}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
        fun service() = CrealityPrinterService("http://127.0.0.1:${server.port}/", wsPort = server.port)
    }

    private fun waitFor(condition: () -> Boolean) { val end = System.currentTimeMillis() + 3_000; while (!condition() && System.currentTimeMillis() < end) Thread.sleep(20) }

    private fun gcode(vararg lines: String): File = File.createTempFile("cube", ".gcode").apply { deleteOnExit(); writeText(lines.joinToString("\n")) }

    @Test fun crealityStatusComesFromPushedFramesAndHeartbeatsAreAnswered() {
        val fake = FakeCreality(boxsInfo)
        fake.server.use {
            val s = fake.service()
            try {
                val snap = s.snapshot()
                assertTrue(snap.ready); assertEquals("standby", snap.state); assertEquals(26.1, snap.nozzle!!, 0.01)
                waitFor { "ok" in fake.wsReceived }
                assertTrue("heartbeat answered with ok: ${fake.wsReceived}", "ok" in fake.wsReceived)
                assertTrue(fake.wsReceived.all { it == "ok" || JSONObject(it).getString("method") == "get" })
            } finally { s.close() }
        }
    }

    @Test fun crealityCfsSlotsAreReadOnly() {
        val fake = FakeCreality(boxsInfo)
        fake.server.use {
            val s = fake.service()
            try {
                val slots = s.filamentSlots()
                assertEquals("the printer's CFS", slots.source)
                assertEquals(listOf("CFS 1 · slot A", "CFS 1 · slot B", "CFS 1 · slot C", "CFS 1 · slot D", "External spool"), slots.slots.map { it.name })
                assertEquals(listOf(true, false, true, false, true), slots.slots.map { it.loaded })
                assertTrue(slots.slots[0].active)
                assertTrue("only gets: ${fake.wsReceived}", fake.wsReceived.all { it == "ok" || JSONObject(it).getString("method") == "get" })
            } finally { s.close() }
        }
    }

    @Test fun crealitySendUploadsButRefusesToStart() {
        assertFalse(CrealityCfs.START_VERIFIED)
        val fake = FakeCreality(boxsInfo)
        fake.server.use {
            val s = fake.service()
            try {
                val file = gcode("; generated", "T0", "G1 X1", "T1", "G1 X2", "; filament_type = PLA;PETG")
                val refusal = assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(file, "my cube.gcode"))) }
                assertTrue(refusal.message!!, refusal.message!!.contains("isn't verified on real hardware"))
                assertTrue(refusal.message!!, refusal.message!!.contains("did not start it"))
                val upload = fake.http.single { it.path?.startsWith("/upload/") == true }
                assertEquals("/upload/my_cube.gcode", upload.path)
                val body = upload.body.readUtf8()
                assertTrue(body.contains("name=\"file\"")); assertTrue(body.contains("filename=\"my_cube.gcode\"")); assertTrue(body.contains("T1"))
                assertTrue("no start message: ${fake.wsReceived}", fake.wsReceived.none { it.contains("\"set\"") || it.contains("opGcodeFile") || it.contains("multiColorPrint") || it.contains("colorMatch") })
            } finally { s.close() }
        }
    }

    @Test fun crealityRefusesStartsAndControlsItDoesNotHave() {
        val fake = FakeCreality(boxsInfo)
        fake.server.use {
            val s = fake.service()
            try {
                val start = assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("Start", "printer/print/start", mapOf("filename" to "a.gcode"))) }
                assertTrue(start.message!!.contains("isn't verified"))
                for (path in listOf("printer/print/pause", "printer/print/resume", "printer/print/cancel", "printer/emergency_stop"))
                    assertTrue(assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("x", path)) }.message!!.contains("Unsupported command"))
                assertEquals("nothing reached the printer", 0, fake.server.requestCount)
            } finally { s.close() }
        }
    }

    @Test fun theScannerRecognisesACrealityByInfo() {
        val fake = FakeCreality(boxsInfo)
        fake.server.use {
            val found = CopyOnWriteArrayList<DiscoveredPrinter>()
            PrinterScanner(moonrakerPorts = emptyList(), prusaPorts = emptyList(), octoPrintPorts = emptyList(), ssdpPorts = emptyList(), ssdpWaitMs = 100,
                crealityPorts = listOf(fake.server.port), flashforgePort = 1, flashforgeListenPort = 0).scan(listOf("127.0.0.1"), AtomicBoolean(false)) { found += it }
            val k2 = found.single { it.kind == PrinterKind.CREALITY }
            assertEquals("127.0.0.1:${fake.server.port}", k2.address); assertEquals(SlicingPrinterModel.CREALITY_K2, k2.slicingModel)
        }
    }

    // ---- Flashforge ---------------------------------------------------------------------------------------------------

    private val detail = """{"code":0,"message":"Success","detail":{"status":"ready","rightTemp":26.5,"rightTargetTemp":0,"platTemp":25.1,"platTargetTemp":0,
        "hasMatlStation":true,"matlStationInfo":{"slotCnt":4,"slotInfos":[
        {"slotId":1,"hasFilament":true,"materialName":"PLA","materialColor":"#FF0000"},
        {"slotId":2,"hasFilament":false,"materialName":"","materialColor":""},
        {"slotId":3,"hasFilament":true,"materialName":"PETG","materialColor":"#FFFFFF"},
        {"slotId":4,"hasFilament":true,"materialName":"PLA","materialColor":"#0000FF"}]}}}"""

    private fun flashforge(reply: (RecordedRequest) -> MockResponse) = MockWebServer().apply {
        dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = reply(request) }
        start()
    }

    @Test fun flashforgeReadsStatusAndIfsWithTheCheckCodeInTheBodyOnly() {
        val server = flashforge { MockResponse().setBody(detail) }
        server.use {
            val s = FlashforgePrinterService("http://127.0.0.1:${server.port}/", "SNMOMC9900001", "12345678", port = server.port)
            try {
                assertEquals("standby", s.snapshot().state)
                val req = server.takeRequest()
                assertEquals("/detail", req.path); assertEquals("POST", req.method)
                val body = JSONObject(req.body.readUtf8())
                assertEquals("SNMOMC9900001", body.getString("serialNumber")); assertEquals("12345678", body.getString("checkCode"))
                val slots = s.filamentSlots()
                assertEquals(listOf("IFS slot 1", "IFS slot 2", "IFS slot 3", "IFS slot 4"), slots.slots.map { it.name })
                assertEquals(listOf(true, false, true, true), slots.slots.map { it.loaded })
            } finally { s.close() }
        }
    }

    @Test fun flashforgeWithoutCredentialsSendsNothing() {
        val server = flashforge { MockResponse().setBody(detail) }
        server.use {
            val s = FlashforgePrinterService("http://127.0.0.1:${server.port}/", "", "12345678", port = server.port)
            try {
                assertTrue(assertThrows(ApiFailure::class.java) { s.snapshot() }.message!!.contains("access code"))
                assertEquals(0, server.requestCount)
            } finally { s.close() }
        }
    }

    @Test fun flashforgeErrorsAreErrors() {
        val server = flashforge { MockResponse().setBody("""{"code":1,"message":"check code error"}""") }
        server.use {
            val s = FlashforgePrinterService("http://127.0.0.1:${server.port}/", "SN", "wrong", port = server.port)
            try { assertTrue(assertThrows(ApiFailure::class.java) { s.snapshot() }.message!!.contains("check code error")) } finally { s.close() }
        }
    }

    @Test fun flashforgeSendUploadsWithoutPrintNowAndRefusesToStart() {
        assertFalse(FlashforgeIfs.START_VERIFIED)
        val server = flashforge { MockResponse().setBody("""{"code":0,"message":"Success"}""") }
        server.use {
            val s = FlashforgePrinterService("http://127.0.0.1:${server.port}/", "SNMOMC9900001", "12345678", port = server.port)
            try {
                val file = gcode("T0", "G1 X1", "T2", "G1 X2", "; filament_type = PLA;PLA;PETG")
                val refusal = assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(file, "my cube.gcode"))) }
                assertTrue(refusal.message!!, refusal.message!!.contains("isn't verified on real hardware"))
                assertEquals("one upload, nothing else", 1, server.requestCount)
                val req = server.takeRequest()
                assertEquals("/uploadGcode", req.path)
                assertEquals("false", req.getHeader("printNow")); assertEquals("false", req.getHeader("useMatlStation")); assertEquals("0", req.getHeader("gcodeToolCnt"))
                assertEquals("SNMOMC9900001", req.getHeader("serialNumber")); assertEquals("12345678", req.getHeader("checkCode"))
                assertEquals(file.length().toString(), req.getHeader("fileSize"))
                val body = req.body.readUtf8()
                assertTrue(body.contains("name=\"gcodeFile\"")); assertTrue(body.contains("filename=\"my_cube.gcode\""))
                assertFalse("the check code never goes in a URL", req.path!!.contains("12345678"))
            } finally { s.close() }
        }
    }
}

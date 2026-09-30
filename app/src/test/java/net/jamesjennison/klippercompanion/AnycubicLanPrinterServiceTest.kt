package net.jamesjennison.klippercompanion

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// PrinterKind.ANYCUBIC_LAN's service against a local MockWebServer playing the printer's HTTP daemon (/info, /ctrl,
// /gcode_upload) and a fake MQTT session in place of the printer's broker, the connection-factory seam BambuStatusProbe's
// tests use. No real printer, no network beyond 127.0.0.1. The handshake values and the ACE report come from the primary
// reference's own tests (anycubic-orca-plugin tests/test_plugin.py:47-77, 319-358; see AnycubicLanTest); the info report
// and the report topic from kobra-connect's docs/mqtt-commands.md:66-97. The point: status and the ACE slots are read,
// a sliced file is uploaded and nothing ever starts a print (AnycubicLan.START_VERIFIED is false), and every gated control
// is refused with nothing sent at all.
class AnycubicLanPrinterServiceTest {
    private val masterToken = "0123456789ABCDEFfedcba9876543210"
    // test_user / test_password / dev12345678, AES-128-CBC under masterToken[16:32] with IV "1234567890123456" (AnycubicLanTest).
    private val ctrlCipher = "RWu3jnViU/zFOyPAwWeg6/JI8IeDqG2E3pfLm4Fsd6N8NHibxMpxHEStQbN6xyeSDgcagjA7Dk2xjaIpl73KnE7tk5OTZ2+v0BP3CzjW5s2fcW4umwRArp+jS2ngMstBdCF1maEA+tx6a+fKqqEz70XR3s5+I6vb+cOaYyqcG+8="

    private val infoReport = """{"type":"info","action":"report","timestamp":100107,"msgid":"m","state":"done","code":200,"msg":"done","data":{
        "printerName":"My Kobra S1","model":"Anycubic Kobra S1","state":"busy",
        "temp":{"curr_hotbed_temp":60,"curr_nozzle_temp":210,"target_hotbed_temp":60,"target_nozzle_temp":210},
        "project":{"state":"printing","progress":42,"curr_layer":63,"total_layers":150,"filename":"benchy.gcode","print_time":12}}}"""
    private val aceReport = """{"type":"multiColorBox","action":"getInfo","data":{"multi_color_box":[{"id":0,"loaded_slot":1,"temp":34,"humidity":20,"slots":[
        {"index":0,"type":"PLA Matte","color":[239,237,227],"status":5,"sku":"AHYGOW-101"},
        {"index":1,"type":"PETG","color":[207,79,128],"status":4,"sku":"AHPLMG-107"},
        {"index":2,"type":"PLA+","color":[117,120,123],"status":5,"sku":"AHPLPGY-108"},
        {"index":3,"type":"","color":[0,0,0],"status":0}]}]}}"""

    /** The printer's HTTP daemon on port 18910, played on the MockWebServer's port. */
    private class FakeDaemon(val ctrlCipher: String, val masterToken: String, val cloud: Boolean = false, val ctrlHost: String = "127.0.0.1",
                             val staleFirstUpload: Boolean = false) {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        private val infos = AtomicInteger(0)
        private val uploads = AtomicInteger(0)
        val server = MockWebServer()
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val path = request.requestUrl?.encodedPath
                    return when {
                        path == "/info" && request.method == "GET" -> {
                            val n = infos.incrementAndGet()
                            MockResponse().setBody(JSONObject().put("code", 200).put("token", masterToken).put("modelId", "20025")
                                .put("ctrlType", if (cloud) "cloud" else "lan").put("cn", "DF09-EC93-59A6-43A5").put("deviceName", "Anycubic Kobra S1")
                                .put("ctrlInfoUrl", "http://$ctrlHost:${server.port}/ctrl")
                                .put("fileUploadurl", "http://127.0.0.1:${server.port}/gcode_upload?s=tok$n").toString())
                        }
                        path == "/ctrl" && request.method == "POST" ->
                            MockResponse().setBody("""{"code":200,"data":{"token":"1234567890123456","info":"$ctrlCipher"}}""")
                        path == "/gcode_upload" && request.method == "POST" ->
                            if (staleFirstUpload && uploads.incrementAndGet() == 1) MockResponse().setResponseCode(401)
                            else MockResponse().setBody("""{"code":200,"message":"success","data":{"gcode":"my_cube.gcode"}}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
        }
        val address get() = "http://127.0.0.1:${server.port}/"
        fun paths() = requests.map { it.requestUrl!!.encodedPath }
    }

    /** The printer's MQTT broker: answers a query on `.../printer/public/<model>/<device>/<type>/report` (kobra-connect's doc, line 66). */
    private class FakeBroker(private val answers: Map<String, String>, private val connectError: Throwable? = null) {
        val configs = CopyOnWriteArrayList<AnycubicMqttConfig>()
        val published = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val created = AtomicInteger(0)
        val closed = AtomicInteger(0)
        fun session(listener: AnycubicMqttSession.Listener): AnycubicMqttSession {
            created.incrementAndGet()
            return object : AnycubicMqttSession {
                override fun connect(config: AnycubicMqttConfig): CompletableFuture<Void> {
                    configs += config
                    return if (connectError == null) CompletableFuture.completedFuture<Void>(null) else CompletableFuture<Void>().apply { completeExceptionally(connectError) }
                }
                override fun publish(topic: String, payload: String): CompletableFuture<Void> {
                    val message = JSONObject(payload)
                    published += topic to message
                    answers[message.getString("type")]?.let { listener.onMessage(topic.replace("/slicer/printer/", "/printer/public/") + "/report", it) }
                    return CompletableFuture.completedFuture<Void>(null)
                }
                override fun close() { closed.incrementAndGet() }
            }
        }
    }

    private fun gcode(vararg lines: String): File = File.createTempFile("cube", ".gcode").apply { deleteOnExit(); writeText(lines.joinToString("\n")) }

    @Test fun statusIsReadOverMqttWithTheCredentialsTheHandshakeReturned() {
        val daemon = FakeDaemon(ctrlCipher, masterToken)
        daemon.server.use {
            val broker = FakeBroker(mapOf("info" to infoReport))
            val s = AnycubicLanPrinterService(daemon.address, broker::session)
            try {
                val snap = s.snapshot()
                assertEquals("printing", snap.state); assertEquals("benchy.gcode", snap.filename); assertEquals(0.42f, snap.progress, 0.0001f)
                assertEquals(210.0, snap.nozzle!!, 0.0); assertEquals(60.0, snap.bedTarget!!, 0.0); assertEquals(720.0, snap.printDuration!!, 0.0)
                // The handshake: GET /info, then the signed POST to its ctrlInfoUrl.
                assertEquals(listOf("/info", "/ctrl"), daemon.paths())
                val ctrl = daemon.requests[1].requestUrl!!
                val ts = ctrl.queryParameter("ts")!!.toLong(); val nonce = ctrl.queryParameter("nonce")!!
                assertEquals(AnycubicLan.sign(masterToken, ts, nonce), ctrl.queryParameter("sign"))
                assertTrue(Regex("^[A-Z0-9]{32}$").matches(ctrl.queryParameter("did")!!))
                assertEquals(listOf("ts", "nonce", "sign", "did"), ctrl.queryParameterNames.toList())
                // MQTT: the printer's own host, port 9883, the decrypted credentials, the report subscription.
                val config = broker.configs.single()
                assertEquals("127.0.0.1", config.host); assertEquals(9883, config.port)
                assertEquals("test_user", config.username); assertEquals("test_password", config.password)
                assertEquals("anycubic/anycubicCloud/v1/printer/+/20025/dev12345678/#", config.reportFilter)
                assertFalse("no secret in toString", config.toString().contains("test_password"))
                // Exactly one message: the info query on the slicer topic.
                val (topic, message) = broker.published.single()
                assertEquals("anycubic/anycubicCloud/v1/slicer/printer/20025/dev12345678/info", topic)
                assertEquals("query", message.getString("action")); assertTrue(message.isNull("data"))
                assertEquals(1, broker.closed.get())
                // The handshake is kept for the next read: no second /info or /ctrl.
                s.snapshot()
                assertEquals(listOf("/info", "/ctrl"), daemon.paths())
                assertEquals(2, broker.created.get())
            } finally { s.close() }
        }
    }

    @Test fun aceSlotsAreReadOnly() {
        val daemon = FakeDaemon(ctrlCipher, masterToken)
        daemon.server.use {
            val broker = FakeBroker(mapOf("multiColorBox" to aceReport))
            val s = AnycubicLanPrinterService(daemon.address, broker::session)
            try {
                val slots = s.filamentSlots()
                assertEquals("the printer's ACE", slots.source)
                assertEquals(listOf("ACE 1 · slot 1", "ACE 1 · slot 2", "ACE 1 · slot 3", "ACE 1 · slot 4"), slots.slots.map { it.name })
                assertEquals(listOf(true, true, true, false), slots.slots.map { it.loaded })
                assertEquals(listOf("PLA MATTE", "PETG", "PLA+", null), slots.slots.map { it.material })
                assertEquals("#CF4F80", slots.slots[1].colorHex); assertTrue(slots.slots[1].active)
                val (topic, message) = broker.published.single()
                assertEquals("anycubic/anycubicCloud/v1/slicer/printer/20025/dev12345678/multiColorBox", topic)
                assertEquals("getInfo", message.getString("action"))
            } finally { s.close() }
        }
    }

    @Test fun aPrinterWithoutAnAceHasNoSlots() {
        val daemon = FakeDaemon(ctrlCipher, masterToken)
        daemon.server.use {
            val broker = FakeBroker(mapOf("multiColorBox" to """{"type":"multiColorBox","data":{"multi_color_box":[]}}"""))
            val s = AnycubicLanPrinterService(daemon.address, broker::session)
            try { assertEquals(FilamentSlotStatus(emptyList(), ""), s.filamentSlots()) } finally { s.close() }
        }
    }

    @Test fun sendingUploadsTheFileAndNeverStartsAPrint() {
        assertFalse(AnycubicLan.START_VERIFIED)
        val daemon = FakeDaemon(ctrlCipher, masterToken)
        daemon.server.use {
            val broker = FakeBroker(mapOf("info" to infoReport, "multiColorBox" to aceReport))
            val s = AnycubicLanPrinterService(daemon.address, broker::session)
            try {
                val file = gcode("; generated", "T0", "G1 X1", "T1", "G1 X2", "; filament_type = PLA;PETG")
                val refusal = assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(file, "my cube.gcode"))) }
                assertEquals("Uploaded my_cube.gcode to the printer but did not start it: starting a print on an Anycubic printer from Nozzle It All isn't verified on real hardware yet. Start it from the printer's screen.", refusal.message)
                assertEquals(listOf("/info", "/ctrl", "/gcode_upload"), daemon.paths())
                val upload = daemon.requests[2]
                assertEquals("tok1", upload.requestUrl!!.queryParameter("s"))
                assertEquals(file.length().toString(), upload.getHeader("X-File-Length"))
                assertEquals(daemon.requests[1].requestUrl!!.queryParameter("did"), upload.getHeader("X-BBL-Device-ID"))
                assertEquals("AnycubicSlicerNext/1.3.7.3", upload.getHeader("User-Agent"))
                val body = upload.body.readUtf8()
                val nameField = body.indexOf("name=\"filename\""); val fileField = body.indexOf("name=\"gcode\"; filename=\"my_cube.gcode\"")
                assertTrue(body, nameField >= 0 && fileField > nameField); assertTrue(body.contains("T1"))
                // Nothing at all went over MQTT: no session, so no print/start (or anything else) could be sent.
                assertEquals(0, broker.created.get()); assertTrue(broker.published.isEmpty())
            } finally { s.close() }
        }
    }

    @Test fun aStaleUploadTokenRedoesTheHandshakeOnceAndStillStartsNothing() {
        val daemon = FakeDaemon(ctrlCipher, masterToken, staleFirstUpload = true)
        daemon.server.use {
            val broker = FakeBroker(emptyMap())
            val s = AnycubicLanPrinterService(daemon.address, broker::session)
            try {
                val refusal = assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(gcode("G1 X1"), "cube.gcode"))) }
                assertTrue(refusal.message!!, refusal.message!!.contains("isn't verified on real hardware yet"))
                assertEquals(listOf("/info", "/ctrl", "/gcode_upload", "/info", "/ctrl", "/gcode_upload"), daemon.paths())
                assertEquals("tok2", daemon.requests[5].requestUrl!!.queryParameter("s"))
                assertEquals(0, broker.created.get())
            } finally { s.close() }
        }
    }

    @Test fun everyGatedControlIsRefusedWithNothingSent() {
        val daemon = FakeDaemon(ctrlCipher, masterToken)
        daemon.server.use {
            val broker = FakeBroker(mapOf("info" to infoReport))
            val s = AnycubicLanPrinterService(daemon.address, broker::session)
            try {
                val commands = listOf(
                    PrinterCommand("Start", "printer/print/start", mapOf("filename" to "a.gcode")),
                    PrinterCommand("Pause", "printer/print/pause"),
                    PrinterCommand("Resume", "printer/print/resume"),
                    PrinterCommand("Cancel", "printer/print/cancel"),
                    PrinterCommand("Home", "printer/gcode/script", mapOf("script" to "G28")),
                    PrinterCommand("Heat", "", heaterRequest = HeaterRequest("extruder", "210")),
                )
                for (c in commands) {
                    val m = assertThrows(ApiFailure::class.java) { s.command(c) }.message!!
                    assertTrue("${c.title}: $m", m.contains("isn't verified on real hardware yet"))
                }
                assertTrue(assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("Pause", "printer/print/pause")) }.message!!.startsWith("Nothing was sent: pausing a print"))
                assertEquals("nothing reached the printer", 0, daemon.server.requestCount)
                assertEquals("no MQTT session was opened", 0, broker.created.get())
            } finally { s.close() }
        }
    }

    @Test fun cloudModeIsExplainedAndNothingElseIsSent() {
        val daemon = FakeDaemon(ctrlCipher, masterToken, cloud = true)
        daemon.server.use {
            val broker = FakeBroker(mapOf("info" to infoReport))
            val s = AnycubicLanPrinterService(daemon.address, broker::session)
            try {
                assertEquals(AnycubicLan.CLOUD_MODE, assertThrows(ApiFailure::class.java) { s.snapshot() }.message)
                assertEquals(listOf("/info"), daemon.paths()); assertEquals(0, broker.created.get())
            } finally { s.close() }
        }
    }

    @Test fun aHandshakePointingAtAnotherMachineIsNotFollowed() {
        val daemon = FakeDaemon(ctrlCipher, masterToken, ctrlHost = "192.0.2.10")
        daemon.server.use {
            val broker = FakeBroker(mapOf("info" to infoReport))
            val s = AnycubicLanPrinterService(daemon.address, broker::session)
            try {
                assertTrue(assertThrows(ApiFailure::class.java) { s.snapshot() }.message!!.contains("another address"))
                assertEquals(listOf("/info"), daemon.paths()); assertEquals(0, broker.created.get())
            } finally { s.close() }
        }
    }

    @Test fun aRefusedMqttLoginKeepsTheCredentialsOutOfTheMessageAndRedoesTheHandshake() {
        val daemon = FakeDaemon(ctrlCipher, masterToken)
        daemon.server.use {
            val broker = FakeBroker(mapOf("info" to infoReport), connectError = ApiFailure("The printer refused the LAN credentials it handed out. Check LAN mode is on, then try again."))
            val s = AnycubicLanPrinterService(daemon.address, broker::session)
            try {
                val m = assertThrows(ApiFailure::class.java) { s.snapshot() }.message!!
                assertTrue(m, m.contains("refused the LAN credentials"))
                for (secret in listOf("test_password", "test_user", masterToken, "tok1")) assertFalse(secret, m.contains(secret))
                assertTrue(broker.published.isEmpty())
                assertThrows(ApiFailure::class.java) { s.snapshot() }
                assertEquals("a failed login drops the cached handshake", listOf("/info", "/ctrl", "/info", "/ctrl"), daemon.paths())
            } finally { s.close() }
        }
    }
}

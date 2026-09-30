package net.jamesjennison.klippercompanion

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

// A Snapmaker U1 starts prints through Snapmaker's start_local_print with bed leveling on (found on a real PAXX U1:
// a plain printer/print/start left print_task_config.auto_bed_leveling off, so the adaptive mesh never ran).
class MoonrakerU1StartTest {
    @Test fun onlyU1PrintersStartThroughStartLocalPrintWithBedLeveling() {
        listOf(PrinterKind.SNAPMAKER_U1, PrinterKind.SNAPMAKER_U1_PAXX).forEach { k ->
            val c = Moonraker.start("a.gcode", k)
            assertEquals(Moonraker.U1_START_LOCAL_PRINT, c.path)
            assertEquals(mapOf("filename" to "a.gcode", "bed_level" to "1"), c.arguments)
            assertEquals(Moonraker.start("a.gcode").allowedStates, c.allowedStates)
        }
        PrinterKind.entries.filter { it != PrinterKind.SNAPMAKER_U1 && it != PrinterKind.SNAPMAKER_U1_PAXX }
            .forEach { assertEquals(it.name, "printer/print/start", Moonraker.start("a.gcode", it).path) }
    }

    private fun serve(reply: (JSONObject) -> String?): Pair<MockWebServer, MutableList<JSONObject>> {
        val server = MockWebServer()
        val received = CopyOnWriteArrayList<JSONObject>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val req = JSONObject(text); received += req
                webSocket.send(JSONObject().put("jsonrpc", "2.0").put("method", "notify_proc_stat_update").toString()) // unrelated traffic first
                val r = reply(req)
                if (r == null) webSocket.close(1011, "gone") else webSocket.send(r)
            }
            override fun onOpen(webSocket: WebSocket, response: Response) {}
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }))
        server.start()
        return server to received
    }

    @Test fun sendsOneStartLocalPrintWithBedLevelOnAndAcceptsSuccess() {
        val (server, received) = serve { req -> JSONObject().put("jsonrpc", "2.0").put("id", req.getInt("id")).put("result", JSONObject().put("state", "success").put("message", "Print started")).toString() }
        server.use {
            Moonraker(server.url("/").toString().replace("localhost", "127.0.0.1")).command(Moonraker.start("tests/cube.gcode", PrinterKind.SNAPMAKER_U1_PAXX))
            assertEquals("/websocket", server.takeRequest().path)
            assertEquals(1, received.size)
            val req = received.single()
            assertEquals("server.files.start_local_print", req.getString("method"))
            val params = req.getJSONObject("params")
            assertEquals("tests/cube.gcode", params.getString("path"))
            assertEquals(1, params.getInt("print_plate"))
            assertEquals(1, params.getJSONObject("options").getInt("bed_level"))
        }
    }

    @Test fun aRefusalIsARefusalAndALostReplyIsUnknown() {
        val (refusing, _) = serve { req -> JSONObject().put("id", req.getInt("id")).put("result", JSONObject().put("state", "error").put("message", "Printer is busy, cannot start print")).toString() }
        refusing.use {
            try { Moonraker(refusing.url("/").toString().replace("localhost", "127.0.0.1")).command(Moonraker.start("a.gcode", PrinterKind.SNAPMAKER_U1)); fail() }
            catch (e: U1StartRefused) { assertTrue(e.message!!.contains("busy")) }
        }
        val (rpcError, _) = serve { req -> JSONObject().put("id", req.getInt("id")).put("error", JSONObject().put("code", 400).put("message", "File not found")).toString() }
        rpcError.use {
            try { Moonraker(rpcError.url("/").toString().replace("localhost", "127.0.0.1")).command(Moonraker.start("a.gcode", PrinterKind.SNAPMAKER_U1)); fail() }
            catch (e: U1StartRefused) { assertTrue(e.message!!.contains("File not found")) }
        }
        val (dropping, sent) = serve { null }
        dropping.use {
            try { Moonraker(dropping.url("/").toString().replace("localhost", "127.0.0.1")).command(Moonraker.start("a.gcode", PrinterKind.SNAPMAKER_U1)); fail() }
            catch (e: U1StartRefused) { fail("a dropped connection is not a refusal: the start may have happened") }
            catch (e: ApiFailure) { assertTrue(e.message!!.contains("check")) }
            assertEquals("sent once, never retried", 1, sent.size)
        }
    }
}

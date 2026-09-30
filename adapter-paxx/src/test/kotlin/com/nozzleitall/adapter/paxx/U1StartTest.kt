package com.nozzleitall.adapter.paxx

import com.nozzleitall.printer.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** U1 print starts: `server.files.start_local_print` on Moonraker's websocket (the U1 refuses it over HTTP), bed leveling on. */
class U1StartTest {
    private fun config(address: String, family: PrinterFamily, secret: String = "") =
        PrinterConfig(PrinterIdentity("u1", "Workshop U1", "Snapmaker U1", family, address), PaxxLanAdapter.ID, secret)

    /** A websocket that answers each JSON-RPC request with [answer] (null: close without replying). */
    private fun MockWebServer.rpc(answer: (JSONObject) -> JSONObject?): List<JSONObject> {
        val received = CopyOnWriteArrayList<JSONObject>()
        enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val req = JSONObject(text); received += req
                val reply = answer(req)
                if (reply == null) webSocket.close(1000, null) else webSocket.send(reply.put("jsonrpc", "2.0").put("id", req.getInt("id")).toString())
            }
            override fun onOpen(webSocket: WebSocket, response: Response) {}
        }))
        return received
    }

    private val success: (JSONObject) -> JSONObject = { JSONObject().put("result", JSONObject().put("state", "success")) }

    @Test fun paxxStartGoesOverTheWebsocketWithBedLeveling() = MockWebServer().use { server ->
        val received = server.rpc(success)
        PaxxLanAdapter().open(config(server.url("/").toString(), PrinterFamily.PAXX_U1, secret = "k3y")).use { s ->
            assertEquals(ActionOutcome.Accepted, s.perform(PrinterAction.StartJob("cube.gcode")))
        }
        val upgrade = server.takeRequest()
        assertEquals("/websocket", upgrade.path)
        assertEquals("k3y", upgrade.getHeader("X-Api-Key"))
        assertEquals(1, server.requestCount)
        val req = received.single()
        assertEquals("server.files.start_local_print", req.getString("method"))
        val params = req.getJSONObject("params")
        assertEquals("cube.gcode", params.getString("path"))
        assertEquals(1, params.getInt("print_plate"))
        assertEquals(1, params.getJSONObject("options").getInt("bed_level"))
        assertFalse(params.getJSONObject("options").has("map_table"))
    }

    @Test fun multiColourStartCarriesTheToolheadMap() = MockWebServer().use { server ->
        val received = server.rpc(success)
        PaxxLanAdapter().open(config(server.url("/").toString(), PrinterFamily.PAXX_U1)).use { s ->
            assertEquals(ActionOutcome.Accepted, s.perform(PrinterAction.StartJob("cube.gcode", listOf(2, 0))))
        }
        val options = received.single().getJSONObject("params").getJSONObject("options")
        assertEquals("[[0,2],[1,0]]", options.getString("map_table"))
        assertEquals(1, options.getInt("bed_level"))
    }

    @Test fun stockU1StartsTheSameWay() = MockWebServer().use { server ->
        val received = server.rpc(success)
        U1LanSession(config(server.url("/").toString(), PrinterFamily.STOCK_U1), PaxxLanAdapter.CAPABILITIES).use { s ->
            assertEquals(ActionOutcome.Accepted, s.perform(PrinterAction.StartJob("cube.gcode")))
        }
        assertEquals(1, received.single().getJSONObject("params").getJSONObject("options").getInt("bed_level"))
    }

    @Test fun refusalIsRejectedAndLostReplyIsUnknown() {
        MockWebServer().use { server ->
            server.rpc { JSONObject().put("error", JSONObject().put("code", 400).put("message", "File not found")) }
            PaxxLanAdapter().open(config(server.url("/").toString(), PrinterFamily.PAXX_U1)).use { s ->
                assertEquals(ActionOutcome.Rejected("The printer refused to start: File not found"), s.perform(PrinterAction.StartJob("cube.gcode")))
            }
        }
        MockWebServer().use { server ->
            server.rpc { JSONObject().put("result", JSONObject().put("state", "error").put("message", "printer busy")) }
            PaxxLanAdapter().open(config(server.url("/").toString(), PrinterFamily.PAXX_U1)).use { s ->
                assertEquals(ActionOutcome.Rejected("The printer refused to start: printer busy"), s.perform(PrinterAction.StartJob("cube.gcode")))
            }
        }
        MockWebServer().use { server ->
            server.rpc { null }
            PaxxLanAdapter().open(config(server.url("/").toString(), PrinterFamily.PAXX_U1)).use { s ->
                val r = s.perform(PrinterAction.StartJob("cube.gcode"))
                assertTrue("got $r", r is ActionOutcome.Unknown)
            }
            assertEquals("sent once, never retried", 1, server.requestCount)
        }
    }

    @Test fun otherKlipperPrintersStartOverHttpAndRefuseAToolheadMap() = FakeMoonraker(paxx = false).use { fake ->
        MoonrakerAdapter().open(config(fake.address, PrinterFamily.KLIPPER)).use { s ->
            assertEquals(ActionOutcome.Accepted, s.perform(PrinterAction.StartJob("cube.gcode")))
            assertEquals("/printer/print/start", fake.calls.last().path)
            assertEquals("cube.gcode", fake.calls.last().query["filename"])
            val before = fake.calls.size
            assertTrue(s.perform(PrinterAction.StartJob("cube.gcode", listOf(1, 0))) is ActionOutcome.Rejected)
            assertEquals(before, fake.calls.size)
        }
    }
}

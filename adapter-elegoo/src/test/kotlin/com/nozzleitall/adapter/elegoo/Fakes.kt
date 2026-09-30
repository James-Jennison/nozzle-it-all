package com.nozzleitall.adapter.elegoo

import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

fun fixture(name: String): JSONObject = JSONObject(Fakes::class.java.getResource("/elegoo/$name")!!.readText()).apply { remove("_source") }

object Fakes {
    val FAST = ElegooTimeouts(connectMs = 2_000, statusMs = 1_000, slotsMs = 1_000, commandMs = 1_000, discoveryMs = 300)
}

/**
 * A Centauri Carbon stand-in on 127.0.0.1: the SDCP WebSocket at /websocket and the upload endpoint, on one MockWebServer.
 * Answers Cmd 0 with a status message and Cmd 324 with the CANVAS fixture; print commands get [commandAck] (null = no reply,
 * -1 = close the connection).
 */
class FakeSdcpPrinter : AutoCloseable {
    val requests = CopyOnWriteArrayList<JSONObject>()
    val uploads = CopyOnWriteArrayList<RecordedRequest>()
    var status: JSONObject = fixture("sdcp_status_printing.json")
    var canvas: JSONObject? = fixture("sdcp_canvas_response.json")
    var commandAck: Int? = 0
    /** Reply for the upload chunk at this index (0-based); default success. */
    var uploadReply: (Int) -> MockResponse = { MockResponse().setBody("""{"code":"000000","messages":null,"data":{},"success":true}""") }
    val server = MockWebServer()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/websocket" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text == "ping") { webSocket.send("pong"); return }
                        val msg = JSONObject(text); requests += msg
                        val data = msg.getJSONObject("Data")
                        val id = data.getString("RequestID"); val cmd = data.getInt("Cmd")
                        fun ack(a: Int) = JSONObject().put("Id", msg.getString("Id")).put("Topic", "sdcp/response/${data.getString("MainboardID")}")
                            .put("Data", JSONObject().put("Cmd", cmd).put("RequestID", id).put("MainboardID", data.getString("MainboardID")).put("TimeStamp", 1).put("Data", JSONObject().put("Ack", a)))
                        when (cmd) {
                            Sdcp.CMD_STATUS -> { webSocket.send(ack(0).toString()); webSocket.send(status.toString()) }
                            Sdcp.CMD_CANVAS -> canvas?.let { c -> JSONObject(c.toString()).also { it.getJSONObject("Data").put("RequestID", id) }.let { webSocket.send(it.toString()) } }
                            else -> when (val a = commandAck) { null -> {}; -1 -> webSocket.close(1001, "going away"); else -> webSocket.send(ack(a).toString()) }
                        }
                    }
                })
                "/uploadFile/upload" -> { uploads += request; uploadReply(uploads.size - 1) }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    val port get() = server.port
    fun commands(cmd: Int) = requests.filter { it.getJSONObject("Data").getInt("Cmd") == cmd }
    override fun close() = server.shutdown()
}

/** Answers one UDP discovery message with [reply]; records what it was asked. */
class FakeUdpResponder(private val reply: String) : AutoCloseable {
    private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
    val asked = CopyOnWriteArrayList<String>()
    val port get() = socket.localPort
    init {
        Thread {
            val buf = ByteArray(4096)
            while (!socket.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try { socket.receive(p) } catch (e: Exception) { break }
                asked += String(p.data, 0, p.length)
                val out = reply.toByteArray()
                runCatching { socket.send(DatagramPacket(out, out.size, p.address, p.port)) }
            }
        }.apply { isDaemon = true; start() }
    }
    override fun close() = socket.close()
}

/**
 * A Centauri Carbon 2's MQTT broker, reduced to what the printer does for one client: CONNACK (rejecting a wrong
 * password with code 5), SUBACK, the registration reply, PONG, and replies to requests on the client's response topic.
 */
class FakeBroker(private val serial: String = "CC2A0001B2C3", private val password: String = "123456") : AutoCloseable {
    data class Pub(val topic: String, val payload: JSONObject)
    val published = CopyOnWriteArrayList<Pub>()
    val subscriptions = CopyOnWriteArrayList<String>()
    @Volatile var connectUser: String? = null
    @Volatile var connectPassword: String? = null
    @Volatile var clientId: String? = null
    var registrationError = "ok"
    var status: JSONObject? = fixture("cc2_status.json")
    var canvasReply: JSONObject? = null
    /** error_code for commands; null = no reply; -1 = drop the connection. */
    var commandCode: Int? = 0
    private val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
    @Volatile private var out: OutputStream? = null
    private var client: Socket? = null
    val port get() = server.localPort

    init { Thread { runCatching { while (true) serve(server.accept()) } }.apply { isDaemon = true; start() } }

    @Synchronized private fun send(bytes: ByteArray) { out?.write(bytes); out?.flush() }

    fun publish(topic: String, payload: JSONObject, qos: Int = 0) = send(MqttCodec.publish(topic, payload.toString().toByteArray(), qos, 7))

    private fun serve(s: Socket) {
        client = s
        Thread {
            runCatching {
                val input = BufferedInputStream(s.getInputStream()); out = s.getOutputStream()
                while (true) {
                    val p = MqttCodec.read(input, 1_000_000) ?: break
                    when (p.type) {
                        MqttCodec.CONNECT -> {
                            val b = p.body; var off = 10
                            fun str(): String { val l = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF); val v = String(b, off + 2, l); off += 2 + l; return v }
                            clientId = str(); connectUser = str(); connectPassword = str()
                            send(MqttCodec.encode(MqttCodec.CONNACK, 0, byteArrayOf(0, (if (connectPassword == password) 0 else 5).toByte())))
                        }
                        MqttCodec.SUBSCRIBE -> {
                            val b = p.body; var off = 2
                            while (off < b.size) { val l = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF); subscriptions += String(b, off + 2, l); off += 3 + l }
                            send(MqttCodec.encode(MqttCodec.SUBACK, 0, byteArrayOf(b[0], b[1], 1)))
                        }
                        MqttCodec.PINGREQ -> send(MqttCodec.encode(MqttCodec.PINGRESP, 0, ByteArray(0)))
                        MqttCodec.PUBLISH -> handle(MqttCodec.parsePublish(p))
                        MqttCodec.DISCONNECT -> { s.close(); return@runCatching }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun handle(m: MqttCodec.Publish) {
        if (m.qos == 1 && m.packetId != null) send(MqttCodec.puback(m.packetId))
        val json = JSONObject(String(m.payload)); published += Pub(m.topic, json)
        when {
            m.topic == "elegoo/$serial/api_register" -> publish("elegoo/$serial/${json.getString("request_id")}/register_response",
                JSONObject().put("client_id", json.getString("client_id")).put("error", registrationError))
            m.topic.endsWith("/api_request") -> {
                val responseTopic = m.topic.removeSuffix("/api_request") + "/api_response"
                if (json.optString("type") == "PING") { publish(responseTopic, JSONObject().put("type", "PONG")); return }
                val id = json.getInt("id"); val method = json.getInt("method")
                when (method) {
                    Cc2.METHOD_STATUS -> status?.let { publish(responseTopic, JSONObject(it.toString()).put("id", id), qos = 1) }
                    Cc2.METHOD_CANVAS -> canvasReply?.let { publish(responseTopic, JSONObject(it.toString()).put("id", id)) }
                    else -> when (val c = commandCode) {
                        null -> {}
                        -1 -> client?.close()
                        else -> publish(responseTopic, JSONObject().put("id", id).put("method", method).put("result", JSONObject().put("error_code", c)))
                    }
                }
            }
        }
    }

    fun requests(method: Int) = published.filter { it.topic.endsWith("/api_request") && it.payload.optInt("method", -1) == method }.map { it.payload }
    override fun close() { runCatching { client?.close() }; server.close() }
}


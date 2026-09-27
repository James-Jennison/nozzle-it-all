package com.nozzleitall.adapter.paxx

import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/** An in-process Moonraker stand-in on 127.0.0.1 serving a recorded U1 status. Records every request it receives. */
class FakeMoonraker(var paxx: Boolean = true) : AutoCloseable {
    data class Call(val method: String, val path: String, val query: Map<String, String>, val body: String)
    val calls = CopyOnWriteArrayList<Call>()
    private val fixture = JSONObject(javaClass.getResource("/moonraker/u1_snapshot.json")!!.readText())
    var status: JSONObject = fixture.getJSONObject("status")
    var klippyState = "ready"
    var gcodeReply: (String) -> Pair<Int, String> = { 200 to """{"result":"ok"}""" }
    var dropUpload = false
    var macros = listOf("LOAD_FILAMENT", "UNLOAD_FILAMENT", "PRINT_START")
    val uploaded = mutableMapOf<String, Int>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val address get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { ex ->
            val q = ex.requestURI.rawQuery?.split('&')?.filter { it.isNotEmpty() }?.associate { p ->
                val (k, v) = (p.split('=', limit = 2) + "").take(2); URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8") } ?: emptyMap()
            val path = ex.requestURI.path
            if (path == "/server/files/upload" && dropUpload) { ex.requestBody.readNBytes(1024); ex.close(); return@createContext }
            if (path == "/webcam/redirected" || path == "/webcam/offhost") { // like mjpeg-streamer behind /webcam/ on :8080
                ex.responseHeaders.add("Location", if (path == "/webcam/offhost") "http://example.invalid/steal" else "/webcam/stream.mjpg")
                ex.sendResponseHeaders(302, -1); ex.close(); return@createContext
            }
            if (path == "/webcam/stream.mjpg") { // camera-streamer's MJPEG: two frames, one with Content-Length and one without
                calls += Call(ex.requestMethod, path, q, "")
                ex.responseHeaders.add("Content-Type", "multipart/x-mixed-replace; boundary=frame")
                ex.sendResponseHeaders(200, 0)
                ex.responseBody.use { o ->
                    val f1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
                    val f2 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 4, 5, 0xFF.toByte(), 0xD9.toByte())
                    o.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${f1.size}\r\n\r\n".toByteArray()); o.write(f1)
                    o.write("\r\n--frame\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray()); o.write(f2); o.write("\r\n".toByteArray())
                }
                return@createContext
            }
            val body = ex.requestBody.readBytes()
            calls += Call(ex.requestMethod, path, q, if (path == "/server/files/upload") "<${body.size} bytes>" else String(body))
            val (code, text) = route(ex.requestMethod, path, q, body)
            val bytes = text.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    private fun ok(result: Any) = 200 to JSONObject().put("result", result).toString()

    private fun route(method: String, path: String, q: Map<String, String>, body: ByteArray): Pair<Int, String> = when (path) {
        "/server/info" -> ok(JSONObject().put("klippy_connected", klippyState != "disconnected").put("klippy_state", klippyState))
        "/printer/info" -> ok(JSONObject().put("software_version", "1.6.0.267_20260815150420").put("hostname", "u1"))
        "/printer/objects/list" -> ok(JSONObject().put("objects", JSONArray(status.keySet().toList() + macros.map { "gcode_macro $it" })))
        "/printer/objects/query" -> ok(JSONObject().put("eventtime", 1.0).put("status", JSONObject().apply { q.keys.forEach { k -> status.optJSONObject(k)?.let { put(k, it) } } }))
        "/server/files/list" -> ok(JSONArray().apply {
            put(JSONObject().put("path", "printer.cfg"))
            if (paxx && q["root"] == "config") put(JSONObject().put("path", "extended/extended2.cfg"))
        })
        "/server/webcams/list" -> ok(JSONObject().put("webcams", JSONArray().apply {
            if (paxx) put(JSONObject().put("name", "case").put("stream_url", "/webcam/webrtc").put("snapshot_url", "/webcam/snapshot.jpg").put("service", "webrtc-camerastreamer"))
            put(JSONObject().put("name", "gui").put("stream_url", "/screen/").put("snapshot_url", ""))
        }))
        "/webcam/snapshot.jpg" -> 200 to "JPEGDATA"
        "/printer/gcode/script" -> gcodeReply(q["script"] ?: "")
        "/printer/print/pause", "/printer/print/resume", "/printer/print/cancel", "/printer/print/start" -> ok("ok")
        "/server/files/start_local_print" -> ok("ok")
        "/server/files/upload" -> {
            val name = Regex("filename=\"([^\"]+)\"").find(String(body, Charsets.ISO_8859_1))?.groupValues?.get(1) ?: "unknown"
            uploaded[name] = body.size
            201 to JSONObject().put("result", JSONObject().put("item", JSONObject().put("path", name).put("root", "gcodes")).put("action", "create_file")).toString()
        }
        else -> 404 to """{"error":{"code":404,"message":"Not Found"}}"""
    }

    override fun close() = server.stop(0)
}

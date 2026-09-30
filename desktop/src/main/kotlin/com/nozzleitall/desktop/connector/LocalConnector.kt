package com.nozzleitall.desktop.connector

import com.nozzleitall.desktop.Fleet
import com.nozzleitall.printer.*
import com.nozzleitall.printer.external.AdapterProtocol
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * The local connector (nozzle-connector 1.x, docs/protocols/LOCAL_CONNECTOR.md). Lets Nozzle It All Web, open in a
 * browser on this computer, reach printers the browser itself may not be allowed to reach. Rules:
 *  - listens on 127.0.0.1 only, never on the network;
 *  - answers only Nozzle's own web origins (exact match), and refuses any request whose Host isn't loopback;
 *  - requires pairing: a 6-digit code shown in Desktop, valid for two minutes and one use, exchanged for a random token
 *    (only its SHA-256 is stored);
 *  - only talks to printers already added in Desktop, and printer-changing commands go through Desktop's ActionGuard
 *    (re-checked state, executed once, unknown outcomes block further commands).
 * Nothing is relayed through Nozzle.
 */
class LocalConnector(private val fleet: Fleet, private val tokenFile: File, val port: Int = PORT,
                     private val allowedOrigins: Set<String> = DEFAULT_ORIGINS) {
    companion object {
        const val PORT = 47321
        const val PROTOCOL = "nozzle-connector"
        val DEFAULT_ORIGINS = setOf("https://app.nozzleitall.com", "https://nozzleitall.com", "http://localhost:5173", "http://127.0.0.1:5173", "http://localhost:4173", "http://127.0.0.1:4173")
        private const val MAX_UPLOAD = 1L shl 30
        fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private val random = SecureRandom()
    private var server: HttpServer? = null
    @Volatile private var pairingCode: Pair<String, Long>? = null
    private val tokens: MutableSet<String> = runCatching { JSONArray(tokenFile.readText()).let { a -> (0 until a.length()).map { a.getString(it) } }.toMutableSet() }.getOrDefault(mutableSetOf())

    val running get() = server != null
    val pairedCount get() = tokens.size

    fun start() {
        if (server != null) return
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16)
        s.executor = Executors.newFixedThreadPool(4) { r -> Thread(r, "nozzle-connector").apply { isDaemon = true } }
        s.createContext("/") { ex -> runCatching { handle(ex) }.onFailure { runCatching { reply(ex, 500, JSONObject().put("error", "internal")) } } }
        s.start(); server = s
    }

    fun stop() { server?.stop(0); server = null }

    /** A fresh one-time code for pairing a browser. */
    fun newPairingCode(): String { val c = (random.nextInt(900_000) + 100_000).toString(); pairingCode = c to System.currentTimeMillis() + 120_000; return c }
    fun forgetAll() { tokens.clear(); persist() }

    private fun persist() = com.nozzleitall.desktop.PrinterStore.atomicWrite(tokenFile, JSONArray(tokens.toList()).toString(), private = true)

    private fun cors(ex: HttpExchange, origin: String) {
        ex.responseHeaders.add("Access-Control-Allow-Origin", origin)
        ex.responseHeaders.add("Vary", "Origin")
        ex.responseHeaders.add("Access-Control-Allow-Headers", "Authorization, Content-Type")
        ex.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        // Chrome's local-network permission preflight.
        ex.responseHeaders.add("Access-Control-Allow-Private-Network", "true")
        ex.responseHeaders.add("Access-Control-Max-Age", "600")
    }

    private fun reply(ex: HttpExchange, code: Int, body: Any, type: String = "application/json") {
        val bytes = when (body) { is ByteArray -> body; else -> body.toString().toByteArray() }
        ex.responseHeaders.set("Content-Type", type)
        ex.responseHeaders.set("Cache-Control", "no-store")
        ex.responseHeaders.set("X-Content-Type-Options", "nosniff")
        ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
    }

    private fun handle(ex: HttpExchange) {
        // DNS-rebinding defence: the Host must be loopback, whatever the browser thinks the site is.
        val host = ex.requestHeaders.getFirst("Host")?.substringBeforeLast(':')?.trim('[', ']') ?: ""
        if (host != "127.0.0.1" && host != "localhost" && host != "::1") { reply(ex, 421, JSONObject().put("error", "wrong host")); return }
        val origin = ex.requestHeaders.getFirst("Origin")
        if (origin == null || origin !in allowedOrigins) { reply(ex, 403, JSONObject().put("error", "origin not allowed")); return }
        cors(ex, origin)
        if (ex.requestMethod == "OPTIONS") { reply(ex, 204, ByteArray(0)); return }
        val path = ex.requestURI.path
        val authorized = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.let { sha256(it) in tokens } == true
        when {
            path == "/v1/hello" && ex.requestMethod == "GET" ->
                reply(ex, 200, JSONObject().put("protocol", PROTOCOL).put("version", JSONArray().put(1).put(0)).put("app", "Nozzle It All for Desktop").put("paired", authorized))
            path == "/v1/pair" && ex.requestMethod == "POST" -> {
                val code = runCatching { JSONObject(ex.requestBody.readNBytes(1024).decodeToString()).getString("code") }.getOrNull()
                val current = pairingCode
                if (code == null || current == null || current.second < System.currentTimeMillis() || code != current.first) { reply(ex, 403, JSONObject().put("error", "invalid or expired code")); return }
                pairingCode = null // one use
                val token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
                tokens += sha256(token); persist()
                reply(ex, 200, JSONObject().put("token", token))
            }
            !authorized -> reply(ex, 401, JSONObject().put("error", "not paired"))
            path == "/v1/printers" && ex.requestMethod == "GET" -> reply(ex, 200, JSONObject().put("printers", JSONArray(fleet.order.value.mapNotNull { fleet.printers[it] }.map { e ->
                JSONObject().put("id", e.config.identity.id).put("name", e.config.identity.displayName).put("model", e.config.identity.model)
                    .put("family", e.config.identity.family.id).put("address", e.config.identity.address).put("profileId", e.config.identity.profileId) })))
            path.startsWith("/v1/printers/") -> printerRoute(ex, path.removePrefix("/v1/printers/").split('/'))
            else -> reply(ex, 404, JSONObject().put("error", "not found"))
        }
    }

    private fun printerRoute(ex: HttpExchange, parts: List<String>) {
        val entry = fleet.printers[java.net.URLDecoder.decode(parts.getOrNull(0) ?: "", "UTF-8")] ?: run { reply(ex, 404, JSONObject().put("error", "unknown printer")); return }
        val session = entry.session
        when (parts.getOrNull(1)) {
            "status" -> reply(ex, 200, AdapterProtocol.encode(entry.status.value))
            // What the printer's adapter supports, so the browser offers only those controls (capability schema 2).
            "capabilities" -> entry.capabilities.value?.let { reply(ex, 200, AdapterProtocol.encode(it)) } ?: reply(ex, 503, JSONObject().put("error", "not connected yet"))
            "snapshot" -> {
                val cam = entry.cameras.value.firstOrNull()
                if (session == null || cam == null) { reply(ex, 404, JSONObject().put("error", "no camera")); return }
                runCatching { session.snapshot(cam) }.fold({ reply(ex, 200, it, "image/jpeg") }, { reply(ex, 502, JSONObject().put("error", it.message)) })
            }
            "upload" -> {
                if (ex.requestMethod != "POST" || session == null) { reply(ex, 409, JSONObject().put("result", "failed").put("reason", "The printer isn't connected.")); return }
                val name = ex.requestURI.rawQuery?.split('&')?.firstOrNull { it.startsWith("name=") }?.substringAfter('=')?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: "nozzle/job.gcode"
                val tmp = File.createTempFile("nozzle-connector", ".gcode")
                try {
                    ex.requestBody.use { input -> tmp.outputStream().use { out -> val copied = input.copyTo(out); if (copied > MAX_UPLOAD) error("too large") } }
                    val r = session.upload(tmp, name)
                    reply(ex, 200, when (r) {
                        is UploadResult.Uploaded -> JSONObject().put("result", "uploaded").put("remotePath", r.remotePath)
                        is UploadResult.Interrupted -> JSONObject().put("result", "interrupted").put("reason", r.reason)
                        is UploadResult.Failed -> JSONObject().put("result", "failed").put("reason", r.reason)
                    })
                } finally { tmp.delete() }
            }
            "action" -> {
                val guard = entry.guard
                if (ex.requestMethod != "POST" || guard == null) { reply(ex, 409, JSONObject().put("outcome", "rejected").put("reason", "The printer isn't connected.")); return }
                val action = runCatching { webAction(JSONObject(ex.requestBody.readNBytes(64 * 1024).decodeToString()).getJSONObject("action")) }.getOrNull()
                    ?: run { reply(ex, 400, JSONObject().put("outcome", "rejected").put("reason", "Unrecognised command.")); return }
                // The person confirmed in the browser; Desktop still re-checks state and executes exactly once.
                val outcome = try { val prepared = guard.prepare(action, entry.status.value); guard.execute(prepared, guard.confirm(prepared)) }
                    catch (e: ActionRefused) { ActionOutcome.Rejected(e.message ?: "Not available now.") }
                reply(ex, 200, AdapterProtocol.encode(outcome))
            }
            else -> reply(ex, 404, JSONObject().put("error", "not found"))
        }
    }

    /** The Web App's action shape (web/src/printers/model.ts) mapped onto the shared model. */
    private fun webAction(a: JSONObject): PrinterAction? = when (a.optString("kind")) {
        "start" -> PrinterAction.StartJob(a.getString("path"), a.optJSONArray("toolheadMap")?.let { m -> (0 until m.length()).map { m.getInt(it) } } ?: emptyList())
        "pause" -> PrinterAction.Pause
        "resume" -> PrinterAction.Resume
        "cancel" -> PrinterAction.Cancel
        "home" -> PrinterAction.HomeAll
        "nozzleTemperature" -> PrinterAction.SetNozzleTemperature(a.getInt("toolhead"), a.getInt("celsius"))
        "bedTemperature" -> PrinterAction.SetBedTemperature(a.getInt("celsius"))
        else -> null
    }
}

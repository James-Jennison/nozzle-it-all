package com.nozzleitall.desktop

import com.nozzleitall.desktop.connector.LocalConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files

class LocalConnectorTest {
    private val origin = "http://localhost:5173"

    private fun call(port: Int, path: String, method: String = "GET", origin: String? = this.origin, token: String? = null, body: String? = null, host: String? = null): Pair<Int, String> {
        val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        origin?.let { c.setRequestProperty("Origin", it) }
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        host?.let { c.setRequestProperty("Host", it) }
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        return code to runCatching { (if (code < 400) c.inputStream else c.errorStream)?.readBytes()?.decodeToString() ?: "" }.getOrDefault("")
    }

    @Test fun pairingOriginAndHostRulesHold() {
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true")
        val dir = Files.createTempDirectory("conn").toFile()
        val paths = AppPaths(File(dir, "config"), File(dir, "data"), File(dir, "cache")).ensure()
        val fleet = Fleet(paths, CoroutineScope(Dispatchers.Default))
        val port = ServerSocket(0).use { it.localPort }
        val conn = LocalConnector(fleet, File(paths.config, "tokens.json"), port)
        conn.start()
        try {
            // Only Nozzle's own origins get an answer.
            assertEquals(403, call(port, "/v1/hello", origin = "https://evil.example").first)
            assertEquals(403, call(port, "/v1/hello", origin = null).first)
            // DNS rebinding: a non-loopback Host is refused even from an allowed origin.
            assertEquals(421, call(port, "/v1/hello", host = "attacker.example").first)
            val hello = JSONObject(call(port, "/v1/hello").second)
            assertEquals("nozzle-connector", hello.getString("protocol")); assertFalse(hello.getBoolean("paired"))
            // Nothing but hello and pair works before pairing.
            assertEquals(401, call(port, "/v1/printers").first)
            // Wrong and reused codes are refused; the real code works once.
            val code = conn.newPairingCode()
            assertEquals(403, call(port, "/v1/pair", "POST", body = """{"code":"000000"}""").first)
            val paired = call(port, "/v1/pair", "POST", body = """{"code":"$code"}""")
            assertEquals(200, paired.first)
            val token = JSONObject(paired.second).getString("token")
            assertEquals(403, call(port, "/v1/pair", "POST", body = """{"code":"$code"}""").first)
            assertEquals(200, call(port, "/v1/printers", token = token).first)
            assertTrue(JSONObject(call(port, "/v1/hello", token = token).second).getBoolean("paired"))
            // Only a hash of the token is stored.
            assertFalse(File(paths.config, "tokens.json").readText().contains(token))
            conn.forgetAll()
            assertEquals(401, call(port, "/v1/printers", token = token).first)
        } finally { conn.stop(); fleet.shutdown() }
    }
}

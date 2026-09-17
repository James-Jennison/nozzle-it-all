package net.jamesjennison.klippercompanion

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConfigWriteTest {
    @Test fun backupCopiesServerSideToATimestampedBackupPath() {
        MockWebServer().use { server ->
            server.start()
            // The backup path embeds the current timestamp, unknown ahead of the call, so the
            // dispatcher echoes back whatever "dest" the client actually requested.
            var lastRequest: RecordedRequest? = null
            var requestedBody = ""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    lastRequest = request; requestedBody = request.body.readUtf8()
                    val dest = JSONObject(requestedBody).getString("dest").removePrefix("config/")
                    return MockResponse().setBody(JSONObject().put("result", JSONObject().put("item", JSONObject().put("path", dest))).toString())
                }
            }
            Moonraker(server.url("/").toString()).use { api ->
                val backup = api.backupConfig("printer.cfg")
                assertTrue(backup.startsWith("printer.cfg.bak-"))
                assertEquals("POST", lastRequest?.method); assertEquals("/server/files/copy", lastRequest?.path)
                val body = JSONObject(requestedBody)
                assertEquals("config/printer.cfg", body.getString("source"))
                assertEquals("config/$backup", body.getString("dest"))
            }
        }
    }
    @Test fun backupRejectsAMismatchedAcknowledgement() {
        MockWebServer().use { server ->
            server.start()
            Moonraker(server.url("/").toString()).use { api ->
                server.enqueue(MockResponse().setBody(JSONObject().put("result", JSONObject().put("item", JSONObject().put("path", "unexpected.cfg"))).toString()))
                assertThrows(IllegalArgumentException::class.java) { api.backupConfig("printer.cfg") }
            }
        }
    }
    @Test fun writeUploadsToTheConfigRootAsMultipartAndVerifiesTheAcknowledgedPath() {
        MockWebServer().use { server ->
            server.start()
            Moonraker(server.url("/").toString()).use { api ->
                server.enqueue(MockResponse().setBody(JSONObject().put("result", JSONObject().put("item", JSONObject().put("path", "printer.cfg"))).toString()))
                api.writeConfig("printer.cfg", "[printer]\nkinematics: corexy\n")
                val request = server.takeRequest()
                assertEquals("POST", request.method); assertEquals("/server/files/upload", request.path)
                val body = request.body.readUtf8()
                assertTrue(body.contains("name=\"root\""))
                assertTrue(body.contains("config"))
                assertTrue(body.contains("name=\"file\"; filename=\"printer.cfg\""))
                assertTrue(body.contains("kinematics: corexy"))
            }
        }
    }
    @Test fun writeRejectsAMismatchedAcknowledgementAndOversizedContent() {
        MockWebServer().use { server ->
            server.start()
            Moonraker(server.url("/").toString()).use { api ->
                server.enqueue(MockResponse().setBody(JSONObject().put("result", JSONObject().put("item", JSONObject().put("path", "wrong.cfg"))).toString()))
                assertThrows(IllegalArgumentException::class.java) { api.writeConfig("printer.cfg", "ok") }
                assertThrows(IllegalArgumentException::class.java) { api.writeConfig("printer.cfg", "a".repeat(ConfigFile.MAX_BYTES + 1)) }
            }
        }
    }
    @Test fun onlyPrinterCfgIsSupportedForBackupAndWrite() {
        MockWebServer().use { server ->
            server.start()
            Moonraker(server.url("/").toString()).use { api ->
                assertThrows(IllegalArgumentException::class.java) { api.backupConfig("moonraker.conf") }
                assertThrows(IllegalArgumentException::class.java) { api.writeConfig("moonraker.conf", "x") }
            }
        }
    }
}

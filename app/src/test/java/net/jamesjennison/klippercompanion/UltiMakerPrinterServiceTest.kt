package net.jamesjennison.klippercompanion

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

// PrinterKind.ULTIMAKER's service against a local MockWebServer playing an UltiMaker with CONSTRUCTED replies (see
// UltiMakerApiTest for their sources). No real printer. The point: status and pairing work, the key only ever travels in a
// digest response, and a send is refused before ANY request (an UltiMaker prints every job it is sent).
class UltiMakerPrinterServiceTest {
    private class FakeUltiMaker {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return when (request.path) {
                        "/cluster-api/v1/printers" -> MockResponse().setBody("""[{"uuid":"p-1","status":"printing","enabled":true}]""")
                        "/cluster-api/v1/print_jobs" -> MockResponse().setBody("""[{"uuid":"j-1","name":"cube.gcode","status":"printing","time_total":400,"time_elapsed":100,"printer_uuid":"p-1"}]""")
                        "/api/v1/system/variant" -> MockResponse().setBody("\"Ultimaker S5\"")
                        "/api/v1/auth/request" -> MockResponse().setBody("""{"id": "0a1b2c", "key": "s3cr3tkey"}""")
                        "/api/v1/auth/check/0a1b2c" -> MockResponse().setBody("""{"message": "authorized"}""")
                        "/api/v1/auth/verify" ->
                            if (request.getHeader("Authorization") == null) MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Digest realm=\"constructed\", nonce=\"n0nce\", qop=\"auth\"")
                            else MockResponse().setBody("""{"message": "ok"}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
        fun service(id: String = "0a1b2c", key: String = "s3cr3tkey") = UltiMakerPrinterService("http://127.0.0.1:${server.port}/", id, key)
    }

    @Test fun statusComesFromTheClusterApiWithoutCredentials() {
        val fake = FakeUltiMaker()
        fake.server.use {
            val s = fake.service("", "")
            try {
                val snap = s.snapshot()
                assertEquals("printing", snap.state); assertEquals("cube.gcode", snap.filename); assertEquals(0.25f, snap.progress, 0.0001f)
                assertEquals(listOf("/cluster-api/v1/printers", "/cluster-api/v1/print_jobs"), fake.requests.map { it.path })
                assertTrue(fake.requests.all { it.getHeader("Authorization") == null })
                assertEquals("Ultimaker S5", s.variant())
            } finally { s.close() }
        }
    }

    @Test fun pairingRequestsCredentialsAndChecksThemWithDigest() {
        val fake = FakeUltiMaker()
        fake.server.use {
            val s = fake.service("", "")
            try {
                assertEquals(UltiMakerApi.Credentials("0a1b2c", "s3cr3tkey"), s.requestCredentials())
                val req = fake.requests.single { it.path == "/api/v1/auth/request" }
                assertEquals("POST", req.method)
                val body = req.body.readUtf8()
                assertTrue(body.contains("name=\"application\"")); assertTrue(body.contains("name=\"user\"")); assertTrue(body.contains("Nozzle It All"))
            } finally { s.close() }
            val paired = fake.service()
            try {
                assertEquals(UltiMakerApi.AuthStatus.AUTHORIZED, paired.authStatus())
                assertTrue(paired.verify())
                val verified = fake.requests.last { it.path == "/api/v1/auth/verify" }
                val auth = verified.getHeader("Authorization")!!
                assertTrue(auth.startsWith("Digest username=\"0a1b2c\""))
                assertFalse("the key never travels in the clear", fake.requests.any { it.path!!.contains("s3cr3tkey") || it.getHeader("Authorization").orEmpty().contains("s3cr3tkey") })
            } finally { paired.close() }
        }
    }

    @Test fun sendingIsRefusedBeforeAnyRequest() {
        assertFalse(UltiMakerApi.START_VERIFIED)
        val fake = FakeUltiMaker()
        fake.server.use {
            val s = fake.service()
            try {
                val file = File.createTempFile("cube", ".gcode").apply { deleteOnExit(); writeText(";START_OF_HEADER\n;PRINT.TIME:0\n;END_OF_HEADER\nG28\n") }
                val refusal = assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(file, "cube.gcode"))) }
                assertEquals(UltiMakerApi.startNotVerified("cube.gcode"), refusal.message)
                val start = assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("Start", "printer/print/start", mapOf("filename" to "cube.gcode"))) }
                assertTrue(start.message!!.contains("isn't verified on real hardware"))
                for (path in listOf("printer/print/pause", "printer/print/resume", "printer/print/cancel", "printer/gcode/script"))
                    assertTrue(assertThrows(ApiFailure::class.java) { s.command(PrinterCommand("x", path)) }.message!!.contains("Unsupported command"))
                assertEquals("nothing reached the printer", 0, fake.server.requestCount)
            } finally { s.close() }
        }
    }
}

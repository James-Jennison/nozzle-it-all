package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse

// Contract-tier tests against prusa3d/Prusa-Link-Web's published spec/openapi.yaml shapes, not
// device tests - M7's own documentation-tier evidence standard applies (no owner-owned Prusa
// hardware to verify against). Digest auth itself is covered separately in
// PrusaLinkDigestAuthTest.kt; these fixtures skip the 401 challenge round-trip by answering every
// request with 200 directly, since MockWebServer response ordering makes mixing that concern into
// each contract test awkward without adding real signal.
class PrusaLinkPrinterServiceTest {
    @Test fun snapshotMapsDocumentedStateEnumAndTemperatures() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"job":{"id":42,"progress":37.5,"time_printing":600},
                "printer":{"state":"PRINTING","temp_nozzle":214.9,"target_nozzle":215.0,"temp_bed":59.5,"target_bed":60.0}}"""))
            server.enqueue(MockResponse().setBody("""{"id":42,"state":"PRINTING","progress":37.5,"time_printing":600,
                "file":{"name":"SPICE~1.gco","display_name":"Spice_Harvester.gcode","path":"/local"}}"""))
            server.start()
            val api = PrusaLinkPrinterService(server.url("/").toString(), "secret")
            try {
                val snapshot = api.snapshot()
                assertTrue(snapshot.ready); assertEquals("printing", snapshot.state)
                assertEquals("Spice_Harvester.gcode", snapshot.filename)
                assertEquals(0.375f, snapshot.progress, 0.001f)
                assertEquals(214.9, snapshot.nozzle!!, 0.01); assertEquals(60.0, snapshot.bedTarget!!, 0.01)
                assertEquals("/api/v1/status", server.takeRequest().requestUrl!!.encodedPath)
                assertEquals("/api/v1/job", server.takeRequest().requestUrl!!.encodedPath)
            } finally { api.close() }
        }
    }
    @Test fun errorAndAttentionStatesAreNotReady() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"printer":{"state":"ERROR"}}"""))
            server.start()
            val api = PrusaLinkPrinterService(server.url("/").toString(), "secret")
            try { assertFalse(api.snapshot().ready) } finally { api.close() }
        }
    }
    // Real, standard storage_list body (prusa3d/Prusa-Link-Web's own Storage schema) mirroring an
    // MK4/MK3.9/MINI/XL: a read-only internal LOCAL entry (real - confirmed against
    // Prusa-Firmware-Buddy's own source, WO-22/WO-23's own research) alongside a writable USB
    // entry - the case resolveWritableStorage() must pick /usb over /local, not assume "local".
    private val storageResponse = """{"storage_list":[
        {"name":"Internal","type":"LOCAL","path":"/local","available":true,"read_only":true},
        {"name":"USB Drive","type":"USB","path":"/usb","available":true,"read_only":false}
    ]}"""
    @Test fun catalogListsOnlyPrintFilesFromTheRootFolder() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(storageResponse))
            server.enqueue(MockResponse().setBody("""{"name":"usb","type":"FOLDER","read_only":false,"m_timestamp":0,
                "children":[
                  {"name":"SPICE~1.gco","type":"PRINT_FILE","read_only":false,"m_timestamp":0},
                  {"name":"firmware.bbf","type":"FIRMWARE","read_only":false,"m_timestamp":0},
                  {"name":"subfolder","type":"FOLDER","read_only":false,"m_timestamp":0}
                ]}"""))
            server.start()
            val api = PrusaLinkPrinterService(server.url("/").toString(), "secret")
            try {
                val catalog = api.catalog()
                assertEquals(listOf("SPICE~1.gco"), catalog.files)
                assertTrue(catalog.warnings.isNotEmpty())
                server.takeRequest() // GET /api/v1/storage
                assertEquals("/api/v1/files/usb", server.takeRequest().requestUrl!!.encodedPath)
            } finally { api.close() }
        }
    }
    @Test fun startPrintPostsToTheRealWritableStorageNotAHardcodedLocal() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(storageResponse))
            server.enqueue(MockResponse().setResponseCode(204))
            server.start()
            val api = PrusaLinkPrinterService(server.url("/").toString(), "secret")
            try {
                api.command(Moonraker.start("my file.gcode"))
                server.takeRequest() // GET /api/v1/storage
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/api/v1/files/usb/my%20file.gcode", request.requestUrl!!.encodedPath)
            } finally { api.close() }
        }
    }
    @Test fun resolveWritableStorageFailsHonestlyWhenNothingIsWritable() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"storage_list":[
                {"name":"Internal","type":"LOCAL","path":"/local","available":true,"read_only":true}
            ]}"""))
            server.start()
            val api = PrusaLinkPrinterService(server.url("/").toString(), "secret")
            try {
                assertThrows(ApiFailure::class.java) { api.command(Moonraker.start("a.gcode")) }
            } finally { api.close() }
        }
    }
    @Test fun pauseResumeCancelLookUpTheCurrentJobIdFirst() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":7,"state":"PRINTING","progress":1,"time_printing":1}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            server.start()
            val api = PrusaLinkPrinterService(server.url("/").toString(), "secret")
            try {
                api.command(PrinterCommand("Pause print", "printer/print/pause", allowedStates = setOf("printing")))
                assertEquals("/api/v1/job", server.takeRequest().requestUrl!!.encodedPath)
                val pause = server.takeRequest()
                assertEquals("PUT", pause.method); assertEquals("/api/v1/job/7/pause", pause.requestUrl!!.encodedPath)
            } finally { api.close() }
        }
    }
    @Test fun cancelWithNoActiveJobFailsHonestlyRatherThanGuessingAnId() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204)) // GET /api/v1/job -> 204 No Content, no job
            server.start()
            val api = PrusaLinkPrinterService(server.url("/").toString(), "secret")
            try {
                assertThrows(ApiFailure::class.java) { api.command(PrinterCommand("Cancel print", "printer/print/cancel")) }
            } finally { api.close() }
        }
    }
    // Phase 6 (WO-23): the real upload+print path - one PUT with Print-After-Upload: ?1, not a
    // separate upload-then-start round trip, and against the real writable storage, not "local".
    @Test fun uploadAndPrintPutsToRealStorageWithPrintAfterUploadHeader() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(storageResponse))
            server.enqueue(MockResponse().setResponseCode(201))
            server.start()
            val api = PrusaLinkPrinterService(server.url("/").toString(), "secret")
            val file = java.io.File.createTempFile("prusalink-upload-test", ".gcode").apply { writeText("G1 X0 Y0\n") }
            try {
                api.command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(file, "test.gcode")))
                server.takeRequest() // GET /api/v1/storage
                val upload = server.takeRequest()
                assertEquals("PUT", upload.method)
                assertEquals("/api/v1/files/usb/test.gcode", upload.requestUrl!!.encodedPath)
                assertEquals("?1", upload.getHeader("Print-After-Upload"))
                assertEquals("?1", upload.getHeader("Overwrite"))
                assertEquals("G1 X0 Y0\n", upload.body.readUtf8())
            } finally { api.close(); file.delete() }
        }
    }
    @Test fun uploadAndPrintSurfacesAnHonestFailureOnRejection() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(storageResponse))
            server.enqueue(MockResponse().setResponseCode(409))
            server.start()
            val api = PrusaLinkPrinterService(server.url("/").toString(), "secret")
            val file = java.io.File.createTempFile("prusalink-upload-test", ".gcode").apply { writeText("G1\n") }
            try {
                assertThrows(ApiFailure::class.java) {
                    api.command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(file, "test.gcode")))
                }
            } finally { api.close(); file.delete() }
        }
    }
    @Test fun unrecognizedCommandPathIsRejected() {
        MockWebServer().use { server ->
            server.start()
            val api = PrusaLinkPrinterService(server.url("/").toString(), "secret")
            try {
                assertThrows(ApiFailure::class.java) { api.command(PrinterCommand("Do something else", "printer/gcode/script", mapOf("script" to "G28"))) }
            } finally { api.close() }
        }
    }
}

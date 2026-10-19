package com.nozzleitall.environment

import org.junit.Assert.*
import org.junit.Test
import java.net.URL
import java.io.ByteArrayInputStream
import javax.net.ssl.HttpsURLConnection
import java.security.cert.Certificate

class GoveeNamesTest {
    private val valid = """{"code":200,"data":[{"sku":"H5100","device":"AA:BB:CC:DD:EE:FF","deviceName":"Office shelf"}]}"""
    private class Connection(private val status: Int, private val body: ByteArray) : HttpsURLConnection(URL(GoveeNameApi.ENDPOINT)) {
        var closed = false
        var readBody = false
        override fun getResponseCode() = status
        override fun getInputStream(): java.io.InputStream { readBody = true; return ByteArrayInputStream(body) }
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun connect() {}
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    @Test fun readOnlyFixedEndpointAndNamesOnly() {
        val connection = Connection(200, valid.toByteArray())
        val api = GoveeNameApi { url -> assertEquals(GoveeNameApi.ENDPOINT, url.toString()); connection }
        val result = api.fetch("fixture-not-a-real-key")
        assertEquals("Office shelf", result.single().name)
        assertEquals("GET", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.doOutput)
        assertFalse(connection.useCaches)
        assertEquals("fixture-not-a-real-key", connection.getRequestProperty("Govee-API-Key"))
        assertTrue(connection.closed)
    }
    @Test fun errorsRedirectsRateLimitsAndStaleResultsFailWithoutEchoingResponse() {
        for ((status, reason) in listOf(301 to GoveeNameFailure.RESPONSE, 401 to GoveeNameFailure.AUTH, 403 to GoveeNameFailure.AUTH, 429 to GoveeNameFailure.RATE_LIMIT, 500 to GoveeNameFailure.RESPONSE)) {
            val connection = Connection(status, "private-response-fixture".toByteArray())
            val e = assertThrows(GoveeNameException::class.java) { GoveeNameApi { connection }.fetch("fixture-not-a-real-key") }
            assertEquals(reason, e.reason); assertFalse(e.message!!.contains("private-response-fixture"))
            assertFalse(connection.readBody); assertTrue(connection.closed)
        }
        val connection = Connection(200, valid.toByteArray())
        val e = assertThrows(GoveeNameException::class.java) { GoveeNameApi { connection }.fetch("fixture-not-a-real-key") { false } }
        assertEquals(GoveeNameFailure.CANCELLED, e.reason); assertFalse(connection.readBody)
    }
    @Test fun rejectInjectionOversizeMalformedAndConflictingIdentities() {
        assertThrows(GoveeNameException::class.java) { GoveeNameApi { error("No connection allowed") }.fetch("fixture\r\ninjection") }
        for (body in listOf("not json", """{"code":401,"data":[]}""", valid.replace("Office shelf", ""), valid.replace("Office shelf", "x".repeat(121)), " ".repeat(GoveeNameApi.MAX_BYTES + 1),
            """{"code":200,"data":[{"sku":"H5100","device":"one","deviceName":"A"},{"sku":"H5100","device":"one","deviceName":"B"}]}""")) {
            assertThrows(GoveeNameException::class.java) { GoveeNameApi.parse(body) }
        }
        val connection = Connection(200, ByteArray(GoveeNameApi.MAX_BYTES + 1) { 32 })
        assertEquals(GoveeNameFailure.RESPONSE, assertThrows(GoveeNameException::class.java) { GoveeNameApi { connection }.fetch("fixture-not-a-real-key") }.reason)
        assertTrue(connection.closed)
    }
    @Test fun onlyExactModelAndFullMacCanAutomaticallyAssignName() {
        val candidate = Candidate("aa:bb:cc:dd:ee:ff", "govee-ble", "H5100")
        assertTrue(GoveeNameApi.parse(valid).single().matches(candidate))
        assertFalse(GoveeDeviceName("11:22:AA:BB:CC:DD:EE:FF", "H5100", "Wrong extended ID").matches(candidate))
        assertFalse(GoveeDeviceName("AA:BB:CC:DD:EE:FF", "H5110", "Different model").matches(candidate))
        assertFalse(GoveeDeviceName("opaque-cloud-id", "H5100", "Choose explicitly").matches(candidate))
    }
}

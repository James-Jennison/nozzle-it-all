package com.nozzleitall.environment

import org.junit.Assert.*
import org.junit.Test
import java.net.URL
import java.io.ByteArrayInputStream
import javax.net.ssl.HttpsURLConnection
import java.security.cert.Certificate

class GoveeNamesTest {
    @Test fun unknownEightByteModelNeverAssumesAnAddressLayout() {
        assertFalse(GoveeDeviceName("11:22:AA:BB:CC:DD:EE:FF", "UNKNOWN", "Fixture")
            .matches(Candidate("AA:BB:CC:DD:EE:FF", "govee-ble", "UNKNOWN")))
    }
    private val valid = """{"code":200,"data":[{"sku":"H5100","device":"AA:BB:CC:DD:EE:FF","deviceName":"Office shelf"}]}"""
    private open class Connection(private val status: Int, private val body: ByteArray) : HttpsURLConnection(URL(GoveeNameApi.ENDPOINT)) {
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
        for (body in listOf("not json", """{"code":401,"data":[]}""", "[".repeat(33) + "]".repeat(33), valid.replace("Office shelf", ""), valid.replace("Office shelf", "x".repeat(121)), " ".repeat(GoveeNameApi.MAX_BYTES + 1),
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
        assertTrue(GoveeDeviceName("FF:EE:DD:CC:BB:AA:11:22", "H5100", "Extended ID").matches(candidate))
        assertFalse(GoveeDeviceName("AA:BB:CC:DD:EE:FF", "H5110", "Different model").matches(candidate))
        assertFalse(GoveeDeviceName("opaque-cloud-id", "H5100", "Choose explicitly").matches(candidate))
    }
    @Test fun h5100UsesFullLittleEndianAddressNotH5110PrefixEncoding() {
        val candidate = Candidate("AA:BB:CC:DD:EE:FF", "govee-ble", "H5100")
        for (id in listOf("FF:EE:DD:CC:BB:AA:11:22", "ffeeddccbbaa1122", "FF-EE-DD-CC-BB-AA-11-22")) {
            assertTrue(id, GoveeDeviceName(id, "H5100", "Front left").matches(candidate))
        }
        assertFalse(GoveeDeviceName("11:22:AA:BB:CC:DD:EE:FF", "H5100", "Wrong layout").matches(candidate))
        assertFalse(GoveeDeviceName("FF:EE:DD:CC:BB:AB:11:22", "H5100", "One byte different").matches(candidate))
        assertFalse(GoveeDeviceName("FF:EE:DD:CC:BB:AA:11:22", "H5110", "Wrong model").matches(candidate))
        assertTrue(GoveeDeviceName("AA:BB:CC:DD:EE:FF", "H5100", "Direct MAC").matches(candidate))
        val entries = listOf(GoveeDeviceName("FF:EE:DD:CC:BB:AA:11:22", "H5100", "Front left"),
            GoveeDeviceName("FF:EE:DD:CC:BB:AB:33:44", "H5100", "Rear right"))
        assertEquals("Front left", entries.singleOrNull { it.matches(candidate) }?.name)
        assertEquals("Rear right", entries.singleOrNull { it.matches(candidate.copy(address = "AB:BB:CC:DD:EE:FF")) }?.name)
        assertNull((entries + entries.first().copy(device = "FF:EE:DD:CC:BB:AA:55:66", name = "Conflict")).singleOrNull { it.matches(candidate) })
    }
    @Test fun extendedCloudIdentityRequiresEveryAddressByteAndUniqueModelMatch() {
        val candidate = Candidate("aa:bb:cc:dd:ee:ff", "govee-ble", "H5110")
        for (id in listOf("11:22:AA:BB:CC:DD:EE:FF", "1122aabbccddeeff", "11-22-AA-BB-CC-DD-EE-FF", "AA-BB-CC-DD-EE-FF")) {
            assertTrue(id, GoveeDeviceName(id, "H5110", "Room A").matches(candidate))
        }
        for (id in listOf("11:22:AB:BB:CC:DD:EE:FF", "EE:FF", "11:22:33:AA:BB:CC:DD:EE:FF", "11:22:AA-BB:CC:DD:EE:FF", "opaque-eeff", " 11:22:AA:BB:CC:DD:EE:FF")) {
            assertFalse(id, GoveeDeviceName(id, "H5110", "Wrong").matches(candidate))
        }
        val name = GoveeDeviceName("11:22:AA:BB:CC:DD:EE:FF", "H5110", "Room A")
        assertFalse(name.matches(candidate.copy(protocol = "other")))
        assertFalse(name.matches(candidate.copy(address = "11:22:AA:BB:CC:DD:EE:FF")))
        assertNull(listOf(name, name.copy(device = "33:44:AA:BB:CC:DD:EE:FF", name = "Room B")).singleOrNull { it.matches(candidate) })
        assertEquals("Room A", listOf(name, name.copy(model = "H5100")).singleOrNull { it.matches(candidate) }?.name)
    }
    @Test fun deadlineDisconnectsEvenWhileWaitingForResponseHeaders() {
        val closed = java.util.concurrent.CountDownLatch(1)
        val connection = object : Connection(200, valid.toByteArray()) {
            override fun getResponseCode(): Int { assertTrue(closed.await(2, java.util.concurrent.TimeUnit.SECONDS)); return 200 }
            override fun disconnect() { super.disconnect(); closed.countDown() }
        }
        val error = assertThrows(GoveeNameException::class.java) { GoveeNameApi(50) { connection }.fetch("fixture-not-a-real-key") }
        assertEquals(GoveeNameFailure.NETWORK, error.reason)
        assertTrue(connection.closed)
    }
}

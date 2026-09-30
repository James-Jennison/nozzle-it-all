package net.jamesjennison.klippercompanion

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

/**
 * PrinterKind.ANYCUBIC_LAN's TLS trust (AnycubicMqttSession.kt): both references accept any certificate
 * (anycubic_lan.py:475-476, kobra_connect/client.py:199-204); this app pins the first one seen per printer address, as
 * BambuTrust does per serial. The certificates are BambuTrustPinTest's self-signed test certificates: any two distinct
 * certificates will do, since no name rule is applied.
 */
class AnycubicTrustPinTest {
    private lateinit var saved: BambuPinStore
    @Before fun fresh() { saved = BambuCertPins.store; BambuCertPins.store = InMemoryBambuPinStore() }
    @After fun restore() { BambuCertPins.store = saved }

    private fun cert(pem: String): X509Certificate = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(java.io.ByteArrayInputStream(pem.toByteArray())) as X509Certificate

    @Test fun theFirstCertificateAtAnAddressIsPinnedAndKeepsWorking() {
        val a = cert(PEM_A); val tm = AnycubicPinningTrustManager("192.168.1.40")
        tm.checkServerTrusted(arrayOf(a), "RSA"); tm.checkServerTrusted(arrayOf(a), "RSA")
        assertEquals(BambuCertPins.fingerprint(a), BambuCertPins.store.get(anycubicPinKey("192.168.1.40")))
    }

    @Test fun aDifferentCertificateAtThatAddressIsRefusedUntilForgotten() {
        val tm = AnycubicPinningTrustManager("192.168.1.40")
        tm.checkServerTrusted(arrayOf(cert(PEM_A)), "RSA")
        val e = assertThrows(CertificateException::class.java) { tm.checkServerTrusted(arrayOf(cert(PEM_B)), "RSA") }
        assertTrue(e.message!!, e.message!!.contains(ANYCUBIC_CERT_CHANGED))
        BambuCertPins.store.forget(anycubicPinKeyForAddress("http://192.168.1.40/")!!)
        tm.checkServerTrusted(arrayOf(cert(PEM_B)), "RSA")
    }

    @Test fun pinsAreKeptPerAddressAndApartFromBambusSerials() {
        AnycubicPinningTrustManager("192.168.1.40").checkServerTrusted(arrayOf(cert(PEM_A)), "RSA")
        AnycubicPinningTrustManager("192.168.1.41").checkServerTrusted(arrayOf(cert(PEM_B)), "RSA")
        assertNotEquals(BambuCertPins.store.get(anycubicPinKey("192.168.1.40")), BambuCertPins.store.get(anycubicPinKey("192.168.1.41")))
        assertEquals("ANYCUBIC:192.168.1.40", anycubicPinKey("192.168.1.40"))
        assertEquals(anycubicPinKey("192.168.1.40"), anycubicPinKeyForAddress("192.168.1.40"))
        assertEquals(anycubicPinKey("192.168.1.40"), anycubicPinKeyForAddress("http://192.168.1.40:18910/"))
        assertNull(BambuCertPins.store.get("192.168.1.40"))
    }

    @Test fun noCertificateAndClientCertificatesAreRefused() {
        val tm = AnycubicPinningTrustManager("192.168.1.40")
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(emptyArray(), "RSA") }
        assertThrows(CertificateException::class.java) { tm.checkClientTrusted(arrayOf(cert(PEM_A)), "RSA") }
    }

    @Test fun anUnreadableClientKeyMeansNoClientCertificate() {
        assertNull(anycubicClientKeyManagerFactory("", ""))
        assertNull(anycubicClientKeyManagerFactory(PEM_A, "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----"))
    }

    private companion object {
        val PEM_A = """-----BEGIN CERTIFICATE-----
MIIDdzCCAl+gAwIBAgIUIuS4wi2If3xgJuMOpDBNQEfn8a0wDQYJKoZIhvcNAQEL
BQAwSzELMAkGA1UEBhMCQ04xIjAgBgNVBAoMGUJCTCBUZWNobm9sb2dpZXMgQ28u
LCBMdGQxGDAWBgNVBAMMDzAxUzAwQTAwMDAwMDEyMzAeFw0yNjA5MjQxNzAzNTVa
Fw0zNjA5MjExNzAzNTVaMEsxCzAJBgNVBAYTAkNOMSIwIAYDVQQKDBlCQkwgVGVj
aG5vbG9naWVzIENvLiwgTHRkMRgwFgYDVQQDDA8wMVMwMEEwMDAwMDAxMjMwggEi
MA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDVX5dKDDPEQZByrlDB2kbJLlx/
kw3c/ZquzVa1zU9Tu5OsIpkAqAMpCnEgWErSOLAfCo+ga4mWI2DGL+U+Er8HDuNL
EW8gG//AtxGoLja1XabgcLBf68tVJk7soV+aQq5GDEtx7GCCJABw2n0anZVFu5zG
/Iq7AGr51U/QI0/Uo7Fq4kgO4XOI9F+nGu3S/L2L/61lxiZffZBU9r3QVcfYImcJ
vylb0pYPwPj5jAbve0wmc1jJ533LEMrXCIIeNZk7DUMg3uHWjNVzW+ckJUo+44sR
idUsOh+sXyotk8b19Z9/EV0lmfAI5CVmQ7Uad2075+LRPJ2anD0b+BkVkZGDAgMB
AAGjUzBRMB0GA1UdDgQWBBRLOgiP+OfKUWPzSRClGa2v18n9FDAfBgNVHSMEGDAW
gBRLOgiP+OfKUWPzSRClGa2v18n9FDAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3
DQEBCwUAA4IBAQByb57dKac5/MrLR9ZqlMQObT30LRPxzfyrrk0etsOj0IEwRVN8
MPUiTyPM927XzehoCq7+xghf0ZgiT9BxYhA+ZMXH+QYAZdCVyllJpzFL5LDdSZGd
ho+BuEJxg3eJCTqPB2zGw/iwa3zl8cdolJ6sL/q3R6ZfbItj1ycYxFpvLudnfkFL
cbLpgkiRUOMXQ8BNb6Skgo9WaacERB5e9GYIZgWmKUguI+YPhWz2Wkg5n+XtjG3+
zBnAN/VpG9QMN7G83Lb/ZVcUjJOqsmHEyS686T9MPmDzs5UQ+LH/6luol+iEMEKG
dlg40A2YJ4raC8yIb5hkUJpZHUq1JRW+ddFN
-----END CERTIFICATE-----"""
        val PEM_B = """-----BEGIN CERTIFICATE-----
MIIDdzCCAl+gAwIBAgIUZl0coLMFcqiSII7XP/G/RVPUP2owDQYJKoZIhvcNAQEL
BQAwSzELMAkGA1UEBhMCQ04xIjAgBgNVBAoMGUJCTCBUZWNobm9sb2dpZXMgQ28u
LCBMdGQxGDAWBgNVBAMMDzAxUzAwQTAwMDAwMDEyMzAeFw0yNjA5MjQxNzAzNTVa
Fw0zNjA5MjExNzAzNTVaMEsxCzAJBgNVBAYTAkNOMSIwIAYDVQQKDBlCQkwgVGVj
aG5vbG9naWVzIENvLiwgTHRkMRgwFgYDVQQDDA8wMVMwMEEwMDAwMDAxMjMwggEi
MA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCh44TRamogsPIWxInHZqtl8+8l
Mt+OGS9ijTYxp3esj1/NrN+55lD23hcCptLS3PPTPAvW6HaT3sTlkyk2EgIbsL9E
sXrStLpMT+iHOWBh6Eqh9tJwt0c7IvTrt5NWlGDn4BElX7vv3R2Ty3W9oK7rOhCN
E9Bk2/V3sQvEGT2S66sfXl7HYe9nC6sJJpzcuB1V9+RHWBYy4TkdYC00VrT8cE1c
0I+LeIxBrYhrisF/KTHaO4SuFAYMyKZWfkcK8oOPS6ElRmo2sNrwXDJX3e1KcNnP
eUx18ZzXnFE0jhs56JZczrbrgVt8lTAPnuDbc6E+S2nWNQjT/7teq3QJbLSNAgMB
AAGjUzBRMB0GA1UdDgQWBBSCdu5bJJQOn3hN7Jgka9vOF5GaeTAfBgNVHSMEGDAW
gBSCdu5bJJQOn3hN7Jgka9vOF5GaeTAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3
DQEBCwUAA4IBAQBHtPmFa7FT7NnlS340POJhmNsyM0aUV6fOV3hw82XqWUqCAXWc
FG1QC650e46xLGoK3J8g0IeXXkJUB9pCfABcOyRowAFVKL5WxqHBQ3uVKWomGH5P
BkHS9XCBsZugdH6fcQH0QaG/dMBXOpoiZElWK0vko+fs4RsXEWIsWLiILrTd1Vfa
wEFGbSaOzTjQyQrr63bfto6P4R1vSINlQwd0rcrIOHHPrrzp9MxDbMBJlwlswZb7
hGddTg6HWzgPQSYWKTvRq9H6MnwOspLCI4xZkTzxeOo0yTRkGucKybQUYSzE+ErE
UFVK6IMNiOfB6mZa6IwG2gZ201IQJQTlOHur
-----END CERTIFICATE-----"""
    }
}

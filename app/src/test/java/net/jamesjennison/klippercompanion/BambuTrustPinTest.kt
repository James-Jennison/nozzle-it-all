package net.jamesjennison.klippercompanion

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

class BambuTrustPinTest {
    private lateinit var saved: BambuPinStore
    @Before fun fresh() { saved = BambuCertPins.store; BambuCertPins.store = InMemoryBambuPinStore() }
    @After fun restore() { BambuCertPins.store = saved }

    private fun cert(name: String): X509Certificate = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(java.io.ByteArrayInputStream(when (name) {
        "SERIAL_A" -> PEM_A; "SERIAL_B" -> PEM_B; else -> PEM_OTHER }.toByteArray())) as X509Certificate

    @Test fun theFirstCertificateForASerialIsRememberedAndTheSameOneKeepsWorking() {
        val a = cert("SERIAL_A"); val tm = SerialPinningTrustManager("01S00A000000123")
        tm.checkServerTrusted(arrayOf(a), "RSA"); tm.checkServerTrusted(arrayOf(a), "RSA")
        assertEquals(BambuCertPins.fingerprint(a), BambuCertPins.store.get("01s00a000000123"))
    }

    @Test fun aDifferentCertificateWithTheRightSerialIsRefusedOnceOneIsPinned() {
        val tm = SerialPinningTrustManager("01S00A000000123")
        tm.checkServerTrusted(arrayOf(cert("SERIAL_A")), "RSA")
        val e = assertThrows(CertificateException::class.java) { tm.checkServerTrusted(arrayOf(cert("SERIAL_B")), "RSA") }
        assertTrue(e.message!!, e.message!!.contains(BAMBU_CERT_CHANGED))
    }

    @Test fun forgettingAPrinterLetsItsNewCertificateBeTrustedAgain() {
        val tm = SerialPinningTrustManager("01S00A000000123")
        tm.checkServerTrusted(arrayOf(cert("SERIAL_A")), "RSA")
        BambuCertPins.store.forget("01S00A000000123")
        tm.checkServerTrusted(arrayOf(cert("SERIAL_B")), "RSA")
    }

    @Test fun theWrongSerialIsStillRefusedAndNeverPinned() {
        val tm = SerialPinningTrustManager("01S00A000000123")
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(arrayOf(cert("OTHER")), "RSA") }
        assertNull(BambuCertPins.store.get("01S00A000000123"))
    }

    @Test fun pinsAreKeptPerSerial() {
        SerialPinningTrustManager("01S00A000000123").checkServerTrusted(arrayOf(cert("SERIAL_A")), "RSA")
        SerialPinningTrustManager("OTHERSERIAL0001").checkServerTrusted(arrayOf(cert("OTHER")), "RSA")
        assertNotEquals(BambuCertPins.store.get("01S00A000000123"), BambuCertPins.store.get("OTHERSERIAL0001"))
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
        val PEM_OTHER = """-----BEGIN CERTIFICATE-----
MIIDdzCCAl+gAwIBAgIUfaqDESXuwlA1GV8BHXw0Vvj8fjgwDQYJKoZIhvcNAQEL
BQAwSzELMAkGA1UEBhMCQ04xIjAgBgNVBAoMGUJCTCBUZWNobm9sb2dpZXMgQ28u
LCBMdGQxGDAWBgNVBAMMD09USEVSU0VSSUFMMDAwMTAeFw0yNjA5MjQxNzAzNTVa
Fw0zNjA5MjExNzAzNTVaMEsxCzAJBgNVBAYTAkNOMSIwIAYDVQQKDBlCQkwgVGVj
aG5vbG9naWVzIENvLiwgTHRkMRgwFgYDVQQDDA9PVEhFUlNFUklBTDAwMDEwggEi
MA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDn82uVbfV/YMtELtsJqk9se6H1
+izwemYHmIeEK3AYxm8mQHiq5cVl7S1z6NQxH6Y3rmpTY0/6fmpERtUqzOERYms7
SFFcOuSZRshSmTY/ykmawCzyP1HhpkMlHV7amTmQpglHWX6wqUvalm713SXLvDr8
FVjqRpf/7hczZ6be5CIPOoHnhsWaSt6rZPvPVzCprMAdVj+p3Ar0Pa1Q93gQ/wmm
XGOE5gHJyQx3jzJl9R5JCl5/dHItibF7O/7fIqa0ztSGGAPooZik5PzNg0n3c/lB
Qn9xnfJf1br6zyjCrRFYzCMoutE+4eu9CLeAftLeBiTYjmE8zBvoz2RwdSU7AgMB
AAGjUzBRMB0GA1UdDgQWBBTjFxUo4Ok7dqh/PG7ETlDduueBQDAfBgNVHSMEGDAW
gBTjFxUo4Ok7dqh/PG7ETlDduueBQDAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3
DQEBCwUAA4IBAQDIq+4iEHPhOS7YtMNU5RCdYz8Gg6C2uv7VZEYaAJzbGDxLun28
I02MIurMMuxpDagavgb18NvzIekAIpiANmf4TIo56hGLJzM83URyqc0CTGb1rC0s
pbcC4r1l26Q4gapnWVvYuMQuH/+mw2PgPEakyihkZ2xqXHl0SiehOcTyXuiYS8eQ
xaaeIwjcBsbNu9rKNHfY6Az9m7d9EKN47fmCFbFEGjLGePN13KdTRJbh3ScP558E
PCpH+9Xb1ftAOmboMlIcNAxM/mbmGyOkoiQYaZE3oaITGTCmfw1ajiMX8QCXdHAH
Q0wqJY0CpGx94kN2ftM3arj70j73DeilGjVX
-----END CERTIFICATE-----"""
    }
}

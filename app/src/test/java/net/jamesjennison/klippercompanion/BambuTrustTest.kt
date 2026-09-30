package net.jamesjennison.klippercompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.X509TrustManager

/**
 * Serial pinning is the whole security story for Bambu's LAN services: the
 * chain is self-signed against a CA nothing trusts, so the leaf CN is the only
 * thing binding a session to one specific machine. Helix's own coverage of this
 * lives in its live (printer-required) tests; these are the same assertions
 * against certificates issued offline in the same shape a P1S presents:
 *
 *   subject = CN=<serial>, issuer = C=CN, O=BBL Technologies Co., Ltd, CN=BBL CA
 */
class BambuTrustTest {

    @Test
    fun acceptsThePrinterWhoseSerialWasConfigured() {
        SerialPinningTrustManager(SERIAL).checkServerTrusted(arrayOf(certificate(SERIAL_PEM)), "RSA")
    }

    @Test
    fun serialMatchingIgnoresCase() {
        SerialPinningTrustManager(SERIAL.lowercase())
            .checkServerTrusted(arrayOf(certificate(SERIAL_PEM)), "RSA")
    }

    @Test
    fun refusesAPrinterPresentingADifferentSerial() {
        val error = assertThrows(CertificateException::class.java) {
            SerialPinningTrustManager(SERIAL).checkServerTrusted(arrayOf(certificate(OTHER_PEM)), "RSA")
        }
        // BambuMqttConnection recovers "wrong-serial" by matching this text out of
        // a buried cause, so the wording is load-bearing.
        assertTrue(error.message.orEmpty().contains(BAMBU_SERIAL_MISMATCH))
        assertTrue(error.message.orEmpty().contains(OTHER_SERIAL))
    }

    @Test
    fun refusesAnEmptyChain() {
        assertThrows(CertificateException::class.java) {
            SerialPinningTrustManager(SERIAL).checkServerTrusted(emptyArray(), "RSA")
        }
        assertThrows(CertificateException::class.java) {
            SerialPinningTrustManager(SERIAL).checkServerTrusted(null, "RSA")
        }
    }

    /** No CA is trusted: pinning replaces issuer verification, it does not add to it. */
    @Test
    fun trustsNoIssuers() {
        assertEquals(0, SerialPinningTrustManager(SERIAL).acceptedIssuers.size)
    }

    @Test
    fun factoryHandsOutTheSamePinnedTrustManager() {
        val managers = SerialPinningTrustManagerFactory(SERIAL).trustManagers
        assertEquals(1, managers.size)
        val manager = managers.single() as X509TrustManager
        manager.checkServerTrusted(arrayOf(certificate(SERIAL_PEM)), "RSA")
        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(certificate(OTHER_PEM)), "RSA")
        }
    }

    /** The MQTT/FTPS hostname check is the same identity check, never "accept anything" (Play's HostnameVerifier policy). */
    @Test
    fun hostnameVerifierChecksTheCertificateNotTheName() {
        fun session(vararg certs: java.security.cert.Certificate) = java.lang.reflect.Proxy.newProxyInstance(
            javax.net.ssl.SSLSession::class.java.classLoader, arrayOf(javax.net.ssl.SSLSession::class.java)) { _, method, _ ->
            if (method.name == "getPeerCertificates") certs else null
        } as javax.net.ssl.SSLSession
        assertTrue(BambuHostnameVerifier(SERIAL).verify("192.168.1.50", session(certificate(SERIAL_PEM))))
        org.junit.Assert.assertFalse(BambuHostnameVerifier(SERIAL).verify("192.168.1.50", session(certificate(OTHER_PEM))))
        org.junit.Assert.assertFalse(BambuHostnameVerifier(SERIAL).verify("192.168.1.50", session()))
        org.junit.Assert.assertFalse(BambuHostnameVerifier(SERIAL).verify("192.168.1.50", null))
    }

    private fun certificate(base64: String): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(Base64.getMimeDecoder().decode(base64)))
            as X509Certificate

    private companion object {
        const val SERIAL = "01P00C611300996"
        const val OTHER_SERIAL = "01PDEADBEEF000000"

        // Self-signed, generated offline for this test only; no private key is kept.
        const val SERIAL_PEM = """
MIIDdzCCAl+gAwIBAgIUZouuFN7nlch9mZfgvlMwx40xlPgwDQYJKoZIhvcNAQELBQAwSzELMAkG
A1UEBhMCQ04xIjAgBgNVBAoMGUJCTCBUZWNobm9sb2dpZXMgQ28uLCBMdGQxGDAWBgNVBAMMDzAx
UDAwQzYxMTMwMDk5NjAeFw0yNjA5MjAxNDM2MTJaFw0zNjA5MTcxNDM2MTJaMEsxCzAJBgNVBAYT
AkNOMSIwIAYDVQQKDBlCQkwgVGVjaG5vbG9naWVzIENvLiwgTHRkMRgwFgYDVQQDDA8wMVAwMEM2
MTEzMDA5OTYwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCwMsrbhjPDl0f5YSigsW3X
3/2LkluR3rPe/4A//9EXbCwChrUGwtuQYaFbZQE/BYQkS2KO3Ixu6ZbUhzx6Ht/51Migh5pJqfYc
k6OTgPL5bSUloOecftswmp1Ov3T9y0MyBf2nuX+zmdcROC8dFMbxzc+2Tt4kBXBuQwWML2g2ejCs
6Leps07nKT2lqznbirp3pgQKkQ3v3alNsh8Srbrbhoh5SsLIiUxqa4gwildwKyif1pxcgTxw54dj
05rxsYDTQ9sJEUFytx48D/asDr8G+ce7+pAdgE5g2K3xB4Q29Fp/md7h0A+4Xj9I7DnL4Ao/gX9S
Yky4lv+ccLIAugDpAgMBAAGjUzBRMB0GA1UdDgQWBBS4P3692i6/NngcJO+95o4qiN+6BTAfBgNV
HSMEGDAWgBS4P3692i6/NngcJO+95o4qiN+6BTAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEB
CwUAA4IBAQCU33Z9eNEpmrwOKaGuIwiu7AdMcZLwfVRr6DKbkXZf5zwB/yeQxOorJtnH3S+WuXEA
hVpRux4p/jVhEj6cUnlrZN2ZzZVjn+xRbPxHo/IL4Ku108PphVdfUJId/nIxdl44LnPimSppnL0U
qByckyx7494UNn0SpkPleRhGFhm7BCofsH/VlMWAMNp+losX0KBk3a2BX3xLRjfRcP2z8srosDw4
Tze0pdAkKzFFHNaAQE9zv6iaWfxU1i+XuTui/YQodQarp7yfEMF4hCEx0fG2G63TiohvlwWRDKNB
IPe4aRg4CiYXXE0X+3QKUAQ3lxrxYipIjhRdT2N8vXVcg1kx"""

        const val OTHER_PEM = """
MIIDezCCAmOgAwIBAgIUBJT4pODTvN//nygCAobFvtKXzAcwDQYJKoZIhvcNAQELBQAwTTELMAkG
A1UEBhMCQ04xIjAgBgNVBAoMGUJCTCBUZWNobm9sb2dpZXMgQ28uLCBMdGQxGjAYBgNVBAMMETAx
UERFQURCRUVGMDAwMDAwMB4XDTI2MDkyMDE0MzYxMloXDTM2MDkxNzE0MzYxMlowTTELMAkGA1UE
BhMCQ04xIjAgBgNVBAoMGUJCTCBUZWNobm9sb2dpZXMgQ28uLCBMdGQxGjAYBgNVBAMMETAxUERF
QURCRUVGMDAwMDAwMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAiLL8j3emgts/MpK0
r6shlWFNBZIr220yAuPgjPYzF7Tdi2GZqdGMTnUxjsV4R3vRbKZmOW7UQYCKhp/N80Y5QMt2At5T
d9uY6FW53n6/NR74vKExXVpMnFTE2gnBUTz840ALrhT8FaJr5E0cjFyM1qhWcT/2IVz8B8FXPe3v
ttAE40mqDxluZNGH/nZNP6Ky8aBTjbcqfm1ShE5ay9ZQPhF2D4EQAZmyrOitcm4xCwYXjxm/Me4s
WX2C0Pd/nEJ39uI3kXMhbwaaauPrsb7xBhNg78heDlCWw1nviX0ay44tiOZrhmgaP5Psoa06mlu7
8Haflve4H407OybhXASVCQIDAQABo1MwUTAdBgNVHQ4EFgQUvM+QtICCf5pvFjNpiCBck7OOzD0w
HwYDVR0jBBgwFoAUvM+QtICCf5pvFjNpiCBck7OOzD0wDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG
9w0BAQsFAAOCAQEAMRSuvNhtJWxGFCpFGvP1GW5/l1hCXz5XlkV3LyvSGXUgkM8Q4CTB3wXzvBKd
RRG8nYwnkf0bUx5wqPPS27FdtbPnXeAhjPa+ml558r8ZxvoIlcXtZQdN5Hq7oQFJtYcxrsLl/8w/
r9nvWy+2rmFtv5ftTWWz4oBfYczsmIFdr1vqmSdPrp8J1cDZDOh4Nv1RdtTn+RQqas+p+DweIfl0
/LneHmxQbDYGtLIfYZP7Ujw2p2aSMyGxzNBO3r5uZBCSM2+npnCOQHWKVxXbC3RhOEnw45B1X4iu
44xhaN10MEvKhOuSfJLfy6UM45zMak/ivwp0ZUGFQCbmd7vi3mbmEQ=="""
    }
}

package net.jamesjennison.klippercompanion

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Bespok3d over real TLS: first contact records the certificate without trusting anything, reads the public details
 * pinned to it, and every later request is refused unless the printer presents exactly the pinned certificate.
 */
class Bespok3dTlsTest {
    private fun keystore(dir: File, name: String): KeyStore {
        val file = File(dir, "$name.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        val p = ProcessBuilder(keytool, "-genkeypair", "-alias", "d", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=bespok3d-$name",
            "-validity", "2", "-storetype", "PKCS12", "-keystore", file.absolutePath, "-storepass", "changeit", "-keypass", "changeit")
            .redirectErrorStream(true).start()
        assertEquals(p.inputStream.readBytes().decodeToString(), 0, p.waitFor())
        return KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, "changeit".toCharArray()) } }
    }

    private fun pem(c: X509Certificate) = "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(c.encoded) + "\n-----END CERTIFICATE-----\n"

    @Test fun firstContactPinsTheCertificateAndLaterRequestsRequireIt() {
        val dir = Files.createTempDirectory("b3d").toFile()
        val served = keystore(dir, "printer"); val other = keystore(dir, "impostor")
        val context = SSLContext.getInstance("TLS").apply {
            init(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(served, "changeit".toCharArray()) }.keyManagers, null, null)
        }
        val server = MockWebServer()
        server.useHttps(context.socketFactory, false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.path == "/license")
                MockResponse().setBody("""{"license":"AGPL-3.0-or-later","source":"https://github.com/Bespok3d/daemon","version":"9.9.9"}""")
            else MockResponse().setResponseCode(404)
        }
        assumeTrue("port 4269 free for the fake daemon", runCatching { server.start(java.net.InetAddress.getByName("127.0.0.1"), 4269) }.isSuccess)
        try {
            val probe = Bespok3dClient().probe("127.0.0.1")
            assertEquals("9.9.9", probe.version)
            val servedCert = served.getCertificate("d") as X509Certificate
            assertEquals(pem(servedCert).trim(), probe.certificatePem.trim())
            // Pinned to a different certificate: refused during the handshake, before any request is sent.
            val impostorPem = pem(other.getCertificate("d") as X509Certificate)
            assertThrows(Exception::class.java) { Bespok3dClient().status("127.0.0.1", "a".repeat(64), impostorPem) }
            // Pinned to the right one: the connection is accepted (the fake daemon has no /status, so it answers 404).
            val error = runCatching { Bespok3dClient().status("127.0.0.1", "a".repeat(64), probe.certificatePem) }.exceptionOrNull()
            assertTrue("reached the daemon: $error", error is Bespok3dHttpException)
        } finally { server.shutdown() }
    }
}

package net.jamesjennison.klippercompanion

import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// Runs against a real OpenSSH server (a throwaway container on the dev machine), opt in with:
//   -e sshd_host <ip> -e sshd_port <port> -e sshd_password <root password> -e sshd_fingerprints <comma list of the server's SHA256:... host keys>
class SshHostKeyPinDeviceTest {
    private val args get() = InstrumentationRegistry.getArguments()
    private val host get() = args.getString("sshd_host").orEmpty()
    private val port get() = args.getString("sshd_port")?.toIntOrNull() ?: 22
    private val password get() = args.getString("sshd_password").orEmpty()
    private val known get() = args.getString("sshd_fingerprints").orEmpty().split(',').filter { it.isNotBlank() }
    private fun opted() = assumeTrue("no sshd_host", host.isNotBlank() && password.isNotBlank() && known.isNotEmpty())

    private fun connect(pinned: String) = JSch().apply { hostKeyRepository = PinnedHostKeyRepository(pinned) }.getSession("root", host, port).apply {
        setPassword(password); setConfig("StrictHostKeyChecking", "yes"); setConfig("PreferredAuthentications", "password"); timeout = 8_000; connect(8_000)
    }

    @Test fun readingTheHostKeySendsNoPasswordAndReturnsTheServersRealKey() {
        opted()
        val offered = fetchSshHostKeyFingerprint(host, port)
        assertTrue("offered $offered, server has $known", offered in known)
    }

    @Test fun aSessionPinnedToTheRealKeyAuthenticatesAndRuns() {
        opted()
        val session = connect(fetchSshHostKeyFingerprint(host, port))
        try { assertTrue(session.isConnected) } finally { session.disconnect() }
    }

    @Test fun aSessionPinnedToAnyOtherKeyIsRefusedBeforeTheServerSeesAPassword() {
        opted()
        val wrong = "SHA256:" + "A".repeat(43)
        val e = assertThrows(JSchException::class.java) { connect(wrong) }
        assertTrue("refused because of the host key: ${e.message}", e.message.orEmpty().contains("HostKey", ignoreCase = true))
    }
}

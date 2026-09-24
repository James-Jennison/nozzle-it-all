package net.jamesjennison.klippercompanion

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest
import java.util.Base64

/** `SHA256:<base64>` of an SSH host key blob, the form `ssh-keygen -l` and OpenSSH print. */
fun sshHostKeyFingerprint(keyBlob: ByteArray): String =
    "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(keyBlob))

/**
 * With StrictHostKeyChecking=yes JSch consults the repository BEFORE authenticating, and refuses the connection unless it answers OK. So a
 * session pinned this way never sends the password to a host whose key is not the expected one (checking the key after connect() is too
 * late: by then the password has been sent).
 */
class PinnedHostKeyRepository(private val expectedFingerprint: String) : HostKeyRepository {
    override fun check(host: String?, key: ByteArray?): Int =
        if (key != null && MessageDigest.isEqual(sshHostKeyFingerprint(key).toByteArray(), expectedFingerprint.toByteArray())) HostKeyRepository.OK else HostKeyRepository.CHANGED
    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "pinned"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

/** Records the host key the server offers and then refuses it, so the connection ends before any credential is sent. */
class CapturingHostKeyRepository : HostKeyRepository {
    @Volatile var fingerprint: String? = null
    override fun check(host: String?, key: ByteArray?): Int { if (key != null) fingerprint = sshHostKeyFingerprint(key); return HostKeyRepository.NOT_INCLUDED }
    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "capture"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

/** Reads a server's SSH host key fingerprint without authenticating (no password is involved or sent). */
fun fetchSshHostKeyFingerprint(host: String, port: Int = 22, user: String = "root", timeoutMs: Int = 8_000): String {
    val capture = CapturingHostKeyRepository()
    val session = JSch().apply { hostKeyRepository = capture }.getSession(user, host, port)
    try {
        session.setConfig("StrictHostKeyChecking", "yes")
        session.timeout = timeoutMs
        try { session.connect(timeoutMs) } catch (e: JSchException) { /* expected: the capture repository refuses the key */ if (capture.fingerprint == null) throw e }
    } finally { session.disconnect() }
    return capture.fingerprint ?: throw IllegalStateException("The printer did not offer an SSH host key")
}

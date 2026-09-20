package net.jamesjennison.klippercompanion

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

// PrusaLink's entire /api/v1/ surface requires RFC 2617 HTTP Digest authentication - its own
// OpenAPI spec (prusa3d/Prusa-Link-Web, spec/openapi.yaml) declares "security: - digestAuth: []"
// globally, with no alternative scheme. OkHttp has no built-in digest support, so this implements
// the qop=auth handshake directly. Not adapted from Helix - Helix has no Prusa support at all
// (its own PrinterKind type is exhaustively snapmaker-u1/flashforge-ad5x/generic-klipper/
// bambu-lan), so unlike every other M8 addition this session, there is no reference logic to
// check against; built fresh from Prusa's own published spec.
internal class PrusaLinkDigestAuthenticator(private val username: String, private val password: String) : Authenticator {
    private val nonceCount = AtomicInteger(0)
    override fun authenticate(route: Route?, response: Response): Request? {
        // A request that already carries an Authorization header and still got a 401 has a wrong
        // password, not a fresh challenge to answer - retrying would loop forever.
        if (response.request.header("Authorization") != null) return null
        val challenge = response.challenges().firstOrNull { it.scheme.equals("Digest", ignoreCase = true) } ?: return null
        val realm = challenge.authParams["realm"] ?: return null
        val nonce = challenge.authParams["nonce"] ?: return null
        val qop = challenge.authParams["qop"]?.split(",")?.map { it.trim() }?.firstOrNull { it == "auth" }
        val opaque = challenge.authParams["opaque"]
        val uri = response.request.url.encodedPath + (response.request.url.encodedQuery?.let { "?$it" } ?: "")
        val method = response.request.method
        val cnonce = md5Hex("${System.nanoTime()}:${Math.random()}").take(16)
        val nc = "%08x".format(nonceCount.incrementAndGet())
        val ha1 = md5Hex("$username:$realm:$password")
        val ha2 = md5Hex("$method:$uri")
        val digestResponse = if (qop != null) md5Hex("$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else md5Hex("$ha1:$nonce:$ha2")
        val headerValue = buildString {
            append("Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$digestResponse\"")
            if (qop != null) append(", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
            if (opaque != null) append(", opaque=\"$opaque\"")
        }
        return response.request.newBuilder().header("Authorization", headerValue).build()
    }
    companion object {
        internal fun md5Hex(input: String): String =
            MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        /** Exposed for tests: builds the exact Authorization value a challenge should produce,
         * without OkHttp's Authenticator plumbing or its random cnonce. */
        internal fun buildAuthorizationHeader(username: String, password: String, method: String, uri: String, realm: String, nonce: String, qop: String?, cnonce: String, nc: String, opaque: String?): String {
            val ha1 = md5Hex("$username:$realm:$password")
            val ha2 = md5Hex("$method:$uri")
            val response = if (qop != null) md5Hex("$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else md5Hex("$ha1:$nonce:$ha2")
            return buildString {
                append("Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\"")
                if (qop != null) append(", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
                if (opaque != null) append(", opaque=\"$opaque\"")
            }
        }
    }
}

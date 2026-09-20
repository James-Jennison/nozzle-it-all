package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class PrusaLinkDigestAuthTest {
    // RFC 2617 section 3.5's own worked example, verified independently (python hashlib, not
    // copied from memory) before writing this test: HA1=939e7578ed9e3c518a452acee763bce9,
    // HA2=39aff3a2bab6126f332b942af96d3366, response=6629fae49393a05397450978507c4ef1.
    @Test fun matchesTheRfc2617WorkedExample() {
        val header = PrusaLinkDigestAuthenticator.buildAuthorizationHeader(
            username = "Mufasa", password = "Circle Of Life", method = "GET", uri = "/dir/index.html",
            realm = "testrealm@host.com", nonce = "dcd98b7102dd2f0e8b11d0f600bfb0c093",
            qop = "auth", cnonce = "0a4f113b", nc = "00000001", opaque = "5ccc069c403ebaf9f0171e9517f40e41",
        )
        assertTrue(header.contains("response=\"6629fae49393a05397450978507c4ef1\""))
        assertTrue(header.contains("username=\"Mufasa\""))
        assertTrue(header.contains("nc=00000001"))
        assertTrue(header.contains("qop=auth"))
        assertTrue(header.contains("cnonce=\"0a4f113b\""))
        assertTrue(header.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""))
    }
    @Test fun omitsQopAndOpaqueWhenTheChallengeDidNotOfferThem() {
        val header = PrusaLinkDigestAuthenticator.buildAuthorizationHeader(
            username = "maker", password = "secret", method = "GET", uri = "/api/v1/status",
            realm = "PrusaLink", nonce = "abc123", qop = null, cnonce = "unused", nc = "unused", opaque = null,
        )
        assertFalse(header.contains("qop="))
        assertFalse(header.contains("opaque="))
        // RFC 2617 3.2.2.1: without qop, response = MD5(HA1:nonce:HA2).
        val ha1 = PrusaLinkDigestAuthenticator.md5Hex("maker:PrusaLink:secret")
        val ha2 = PrusaLinkDigestAuthenticator.md5Hex("GET:/api/v1/status")
        val expected = PrusaLinkDigestAuthenticator.md5Hex("$ha1:abc123:$ha2")
        assertTrue(header.contains("response=\"$expected\""))
    }
    @Test fun endToEndAuthenticatorRetriesOnceWithACorrectDigestHeader() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(401)
                .setHeader("WWW-Authenticate", "Digest realm=\"PrusaLink\", nonce=\"testnonce\", qop=\"auth\""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"ok\":true}"))
            server.start()
            val client = okhttp3.OkHttpClient.Builder().authenticator(PrusaLinkDigestAuthenticator("maker", "secret")).build()
            val response = client.newCall(okhttp3.Request.Builder().url(server.url("/api/v1/status")).build()).execute()
            assertTrue(response.isSuccessful)
            assertEquals(2, server.requestCount)
            val first = server.takeRequest(); assertNull(first.getHeader("Authorization"))
            val retried = server.takeRequest(); assertTrue(retried.getHeader("Authorization")!!.startsWith("Digest username=\"maker\""))
            response.close()
        }
    }
}

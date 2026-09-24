package net.jamesjennison.klippercompanion

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MyMiniFactoryAuthTest {
    private val device = MmfDeviceInfo("dev-1", "Motorola", "razr", "en-US", "NozzleItAll/0.1")

    @Test fun authorizeUrlCarriesTheDocumentedParametersEncoded() {
        val url = MmfAuthLinks.authorizeUrl("my client", "abcdefghijklmnop1234")
        assertTrue(url.startsWith("https://auth.myminifactory.com/web/authorize?"))
        assertTrue(url.contains("client_id=my+client")); assertTrue(url.contains("response_type=token")); assertTrue(url.contains("state=abcdefghijklmnop1234"))
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fnozzleitall.com%2Fmmf-auth"))
        assertThrows(IllegalArgumentException::class.java) { MmfAuthLinks.authorizeUrl("", "abcdefghijklmnop1234") }
        assertThrows(IllegalArgumentException::class.java) { MmfAuthLinks.authorizeUrl("c", "short") }
        assertNotEquals(MmfAuthLinks.newState(), MmfAuthLinks.newState())
    }

    @Test fun redirectParsingAcceptsAGoodResponseAndRejectsForgeriesAndDenials() {
        val state = "abcdefghijklmnop1234"
        assertEquals("tok-1234abcd" to 600, MmfAuthLinks.parseRedirect("nozzleitall://mmf-auth#access_token=tok-1234abcd&expires_in=600&state=$state&token_type=Bearer", state))
        fun fails(uri: String) = assertThrows(MmfAuthLinks.SignInFailed::class.java) { MmfAuthLinks.parseRedirect(uri, state) }.message!!
        assertTrue(fails("nozzleitall://mmf-auth#access_token=tok-1234abcd&state=WRONG").contains("did not match"))
        assertTrue(fails("nozzleitall://mmf-auth?error=access_denied&state=$state").contains("cancelled"))
        assertTrue(fails("nozzleitall://mmf-auth#state=$state").contains("did not return"))
        assertTrue(fails("evil://mmf-auth#access_token=tok-1234abcd&state=$state").contains("Unexpected"))
        assertTrue(fails("nozzleitall://other#access_token=tok-1234abcd&state=$state").contains("Unexpected"))
        fails("nozzleitall://mmf-auth#access_token=bad token!&state=$state")
        fails("not a uri at all")
    }

    private fun withOauth(now: () -> Long = { 1_000_000L }, block: (MockWebServer, MyMiniFactoryOAuth) -> Unit) = MockWebServer().use { s ->
        s.start(); block(s, MyMiniFactoryOAuth("CLIENTKEY", s.url("/"), clock = now, allowInsecureHttpForTests = true))
    }

    @Test fun mobileLoginSendsTheDocumentedFormAndReturnsATwoHourSession() = withOauth { s, o ->
        s.enqueue(MockResponse().setBody("""{"user_id":1,"access_token":"long-token-9999","expires_in":7200,"token_type":"Bearer"}"""))
        val session = o.mobileLogin("implicit-token-1", device)
        assertEquals("long-token-9999", session.accessToken); assertEquals(1_000_000L + 7_200_000L, session.expiresAtMs)
        val r = s.takeRequest(); assertEquals("/v1/oauth/mobile/login", r.path)
        val body = r.body.readUtf8(); assertTrue(body.contains("client_key=CLIENTKEY")); assertTrue(body.contains("access_token=implicit-token-1")); assertTrue(body.contains("device_info="))
        assertEquals("dev-1", org.json.JSONObject(java.net.URLDecoder.decode(body.substringAfter("device_info=").substringBefore('&'), "UTF-8")).getString("device_id"))
    }

    @Test fun loginErrorsAreReportedWithoutEchoingSecrets() = withOauth { s, o ->
        s.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_token"}"""))
        assertEquals("MyMiniFactory rejected the sign-in.", assertThrows(MmfAuthLinks.SignInFailed::class.java) { o.mobileLogin("t-tttttttt", device) }.message)
        s.enqueue(MockResponse().setBody("""{"error":"x"}""")); assertThrows(MmfAuthLinks.SignInFailed::class.java) { o.mobileLogin("t-tttttttt", device) }
        s.enqueue(MockResponse().setBody("""{"access_token":"a"}""")); assertThrows(MmfAuthLinks.SignInFailed::class.java) { o.mobileLogin("t-tttttttt", device) }
    }

    @Test fun managerHandsOutAValidTokenRefreshesNearExpiryAndSignsOutWhenRefreshIsRefused() {
        MockWebServer().use { s ->
            s.start(); var now = 0L
            val oauth = MyMiniFactoryOAuth("K", s.url("/"), clock = { now }, allowInsecureHttpForTests = true)
            val store = InMemoryMmfTokenStore(); val mgr = MmfAuthManager(store, oauth) { now }
            assertNull(mgr.validAccessToken()); assertFalse(mgr.isSignedIn())
            s.enqueue(MockResponse().setBody("""{"access_token":"first-token-1","expires_in":7200}"""))
            mgr.completeSignIn("implicit-tok", device); assertTrue(mgr.isSignedIn())
            assertEquals("first-token-1", mgr.validAccessToken()); assertEquals(1, s.requestCount)
            now = 7_200_000L - 30_000 // inside the 60 s margin -> refresh
            s.enqueue(MockResponse().setBody("""{"access_token":"second-token-2","expires_in":7200}"""))
            assertEquals("second-token-2", mgr.validAccessToken())
            val refresh = s.takeRequest().let { s.takeRequest() }; assertEquals("/v1/oauth/mobile/refresh", refresh.path)
            assertTrue(refresh.body.readUtf8().contains("device_id=${store.deviceId()}"))
            now += 7_200_000L; s.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"expired"}"""))
            assertNull(mgr.validAccessToken()); assertFalse("a refused refresh signs the user out", mgr.isSignedIn())
        }
    }

    @Test fun anOfflineRefreshKeepsTheSessionAndSignOutClearsIt() {
        var now = 0L
        val oauth = MyMiniFactoryOAuth("K", okhttp3.HttpUrl.Builder().scheme("http").host("127.0.0.1").port(1).build(), clock = { now }, allowInsecureHttpForTests = true)
        val store = InMemoryMmfTokenStore().also { it.save(MmfSession("old-token-1", 1000)) }
        val mgr = MmfAuthManager(store, oauth) { now }; now = 5000
        assertNull(mgr.validAccessToken()); assertTrue("offline must not sign the user out", mgr.isSignedIn())
        mgr.signOut(); assertFalse(mgr.isSignedIn())
        assertThrows(MmfException.NotConfigured::class.java) { MmfAuthManager(InMemoryMmfTokenStore(), null).completeSignIn("t", device) }
    }
}

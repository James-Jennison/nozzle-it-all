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
        s.start(); block(s, MyMiniFactoryOAuth("CLIENTKEY", s.url("/"), s.url("/api/v2/"), clock = now, allowInsecureHttpForTests = true))
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
            val oauth = MyMiniFactoryOAuth("K", s.url("/"), s.url("/api/v2/"), clock = { now }, allowInsecureHttpForTests = true)
            val store = InMemoryMmfTokenStore(); val mgr = MmfAuthManager(store, oauth) { now }
            assertNull(mgr.validAccessToken()); assertFalse(mgr.isSignedIn())
            s.enqueue(MockResponse().setBody("""{"access_token":"first-token-1","expires_in":7200}""")); s.enqueue(MockResponse().setBody("""{"username":"me"}"""))
            mgr.completeSignIn("implicit-tok", 600, device); assertTrue(mgr.isSignedIn())
            assertEquals("first-token-1", mgr.validAccessToken()); assertEquals("login + validation only", 2, s.requestCount)
            now = 7_200_000L - 30_000 // inside the 60 s margin -> refresh
            s.enqueue(MockResponse().setBody("""{"access_token":"second-token-2","expires_in":7200}"""))
            assertEquals("second-token-2", mgr.validAccessToken())
            val refresh = s.takeRequest().let { s.takeRequest().let { _ -> s.takeRequest() } }; assertEquals("/v1/oauth/mobile/refresh", refresh.path)
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
        assertThrows(MmfException.NotConfigured::class.java) { MmfAuthManager(InMemoryMmfTokenStore(), null).completeSignIn("t", 600, device) }
    }

    // Seen live: a client that is not a "mobile client" gets HTTP 200 with the body `null` from the mobile-login endpoint.
    @Test fun aNullMobileLoginFallsBackToTheRedirectTokenAfterConfirmingItWorks() {
        MockWebServer().use { s ->
            s.start(); var now = 1_000_000L
            val oauth = MyMiniFactoryOAuth("K", s.url("/"), s.url("/api/v2/"), clock = { now }, allowInsecureHttpForTests = true)
            val store = InMemoryMmfTokenStore(); val mgr = MmfAuthManager(store, oauth) { now }
            s.enqueue(MockResponse().setBody("null")); s.enqueue(MockResponse().setBody("""{"username":"me"}"""))
            mgr.completeSignIn("implicit-token-abcd", 21600, device)
            val saved = store.load()!!
            assertEquals("implicit-token-abcd", saved.accessToken); assertFalse(saved.refreshable); assertEquals(1_000_000L + 21_600_000L, saved.expiresAtMs)
            assertEquals("/v1/oauth/mobile/login", s.takeRequest().path)
            val check = s.takeRequest(); assertEquals("/api/v2/user", check.path); assertEquals("Bearer implicit-token-abcd", check.getHeader("Authorization"))
            assertEquals("implicit-token-abcd", mgr.validAccessToken())
            // it cannot be refreshed: once expired the user is asked to sign in again, with no network call
            now += 22_000_000L; val before = s.requestCount
            assertNull(mgr.validAccessToken()); assertFalse(mgr.isSignedIn()); assertEquals(before, s.requestCount)
        }
    }

    @Test fun aTokenMyMiniFactoryDoesNotAcceptIsNeverStored() {
        MockWebServer().use { s ->
            s.start()
            val oauth = MyMiniFactoryOAuth("K", s.url("/"), s.url("/api/v2/"), allowInsecureHttpForTests = true)
            val store = InMemoryMmfTokenStore(); val mgr = MmfAuthManager(store, oauth)
            s.enqueue(MockResponse().setBody("null")); s.enqueue(MockResponse().setResponseCode(401))
            val e = assertThrows(MmfAuthLinks.SignInFailed::class.java) { mgr.completeSignIn("implicit-token-abcd", 600, device) }
            assertTrue(e.message!!.contains("did not accept")); assertFalse(mgr.isSignedIn())
            s.enqueue(MockResponse().setBody("null")); s.enqueue(MockResponse().setResponseCode(500))
            assertTrue(assertThrows(MmfAuthLinks.SignInFailed::class.java) { mgr.completeSignIn("implicit-token-abcd", 600, device) }.message!!.contains("HTTP 500")); assertFalse(mgr.isSignedIn())
        }
    }

    @Test fun anExplicitLoginErrorIsStillAFailureNotAFallback() {
        MockWebServer().use { s ->
            s.start()
            val oauth = MyMiniFactoryOAuth("K", s.url("/"), s.url("/api/v2/"), allowInsecureHttpForTests = true)
            s.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_token"}"""))
            assertThrows(MmfAuthLinks.SignInFailed::class.java) { MmfAuthManager(InMemoryMmfTokenStore(), oauth).completeSignIn("t-tttttttt", 600, device) }
            assertEquals("no fallback validation call was made", 1, s.requestCount)
        }
    }

    @Test fun theOneHourOneDayAndOneWeekChoicesAreAllHonouredNotCapped() {
        val state = "abcdefghijklmnop1234"
        for (seconds in listOf(3600, 86_400, 604_800)) {
            val (_, exp) = MmfAuthLinks.parseRedirect("nozzleitall://mmf-auth#access_token=tok-abcdefgh&expires_in=$seconds&state=$state", state)
            assertEquals(seconds, exp)
            assertEquals(1_000L + seconds * 1000L, MyMiniFactoryOAuth("K", okhttp3.HttpUrl.Builder().scheme("http").host("x").build(), allowInsecureHttpForTests = true, clock = { 1_000L }).implicitSession("tok-abcdefgh", exp).expiresAtMs)
        }
        assertEquals("absurd values are still bounded", MmfAuthLinks.MAX_TOKEN_SECONDS, MmfAuthLinks.parseRedirect("nozzleitall://mmf-auth#access_token=tok-abcdefgh&expires_in=99999999&state=$state", state).second)
    }

    @Test fun anExpiredImplicitSessionIsNotSignedInButARefreshableOneIs() {
        var now = 0L; val store = InMemoryMmfTokenStore(); val mgr = MmfAuthManager(store, null) { now }
        store.save(MmfSession("implicit-token-1", 1000, refreshable = false)); now = 500
        assertTrue(mgr.isSignedIn()); assertEquals(1000L, mgr.sessionExpiresAtMs())
        now = 1500; assertFalse("an expired one-time token is not a login", mgr.isSignedIn())
        store.save(MmfSession("mobile-token-1", 1000, refreshable = true)); assertTrue("a refreshable session renews on demand", mgr.isSignedIn())
        store.clear(); assertNull(mgr.sessionExpiresAtMs()); assertFalse(mgr.isSignedIn())
    }
}

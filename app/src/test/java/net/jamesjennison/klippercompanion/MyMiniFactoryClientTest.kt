package net.jamesjennison.klippercompanion

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MyMiniFactoryClientTest {
    private fun withServer(block: (MockWebServer, MyMiniFactoryClient) -> Unit): Unit = MockWebServer().use { s ->
        s.start(); block(s, MyMiniFactoryClient("SECRET-KEY", s.url("/api/v2/"), allowInsecureHttpForTests = true))
    }
    private val page = """{"total_count":1,"items":[{"id":5,"name":"Vase"}]}"""

    @Test fun searchSendsTheDocumentedParametersAndTheKey() = withServer { s, c ->
        s.enqueue(MockResponse().setBody(page))
        val r = c.search(MmfSearch("dragon head", page = 2, perPage = 10, sort = MmfSort.DATE, remixAllowed = true, supportFree = true, category = 7))
        assertEquals(5L, r.items.single().id)
        val req = s.takeRequest().requestUrl!!
        assertEquals("/api/v2/search", req.encodedPath)
        assertEquals("SECRET-KEY", req.queryParameter("key")); assertEquals("dragon head", req.queryParameter("q")); assertEquals("2", req.queryParameter("page"))
        assertEquals("10", req.queryParameter("per_page")); assertEquals("date", req.queryParameter("sort")); assertEquals("1", req.queryParameter("remix")); assertEquals("1", req.queryParameter("support")); assertEquals("7", req.queryParameter("cat"))
        assertNull("unset filters are omitted", req.queryParameter("commercial_use"))
    }

    @Test fun paginationIsClampedToWhatTheApiAccepts() = withServer { s, c ->
        s.enqueue(MockResponse().setBody(page)); c.search(MmfSearch(page = 99999, perPage = 500))
        val q = s.takeRequest().requestUrl!!; assertEquals("1000", q.queryParameter("page")); assertEquals("60", q.queryParameter("per_page"))
    }

    @Test fun errorsMapToPlainMessagesAndNeverLeakTheKey() = withServer { s, c ->
        for ((code, type) in listOf(401 to MmfException.Unauthorized::class.java, 403 to MmfException.Unauthorized::class.java, 404 to MmfException.NotFound::class.java, 429 to MmfException.RateLimited::class.java, 503 to MmfException.Unavailable::class.java)) {
            s.enqueue(MockResponse().setResponseCode(code).addHeader("Retry-After", "12"))
            val e = assertThrows(MmfException::class.java) { c.objectDetail(1) }
            assertEquals(type, e.javaClass); assertFalse(e.message!!.contains("SECRET-KEY"))
        }
        s.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "12"))
        assertEquals(12, (assertThrows(MmfException.RateLimited::class.java) { c.objectDetail(1) }).retryAfterSeconds)
    }

    @Test fun malformedAndOversizedResponsesAreRejected() = withServer { s, c ->
        s.enqueue(MockResponse().setBody("<html>not json</html>")); assertThrows(MmfException.Malformed::class.java) { c.search(MmfSearch()) }
        s.enqueue(MockResponse().setBody("x".repeat((MyMiniFactoryClient.MAX_JSON_BYTES + 10).toInt()))); assertThrows(MmfException.Malformed::class.java) { c.search(MmfSearch()) }
    }

    @Test fun unreachableServerIsReportedAsOffline() {
        val c = MyMiniFactoryClient("k", "http://127.0.0.1:1/api/v2/".toHttpUrl(), allowInsecureHttpForTests = true)
        assertThrows(MmfException.Offline::class.java) { c.search(MmfSearch()) }
    }

    @Test fun productionClientRefusesInsecureOrEmptyConfiguration() {
        assertThrows(IllegalArgumentException::class.java) { MyMiniFactoryClient("k", "http://example.com/api/v2/".toHttpUrl()) }
        assertThrows(IllegalArgumentException::class.java) { MyMiniFactoryClient("  ") }
        assertTrue(MyMiniFactoryClient.isMyMiniFactoryHost("www.myminifactory.com")); assertTrue(MyMiniFactoryClient.isMyMiniFactoryHost("myminifactory.com"))
        assertFalse(MyMiniFactoryClient.isMyMiniFactoryHost("evilmyminifactory.com")); assertFalse(MyMiniFactoryClient.isMyMiniFactoryHost("myminifactory.com.evil.io"))
    }

    private fun file(url: String?) = MmfFile(1, "part.stl", null, url, null, null)
    private fun target() = File(Files.createTempDirectory("mmf").toFile(), "part.stl")

    @Test fun downloadWritesTheFileAndNeverSendsTheTokenToANonMyMiniFactoryHost() = withServer { s, c ->
        s.enqueue(MockResponse().setBody("solid x"))
        val t = target(); val n = c.download(file(s.url("/files/1").toString()), "TOKEN", t)
        assertEquals(7L, n); assertEquals("solid x", t.readText()); assertFalse(File(t.path + ".part").exists())
        assertNull("the test host is not myminifactory.com, so no Authorization header may be sent", s.takeRequest().getHeader("Authorization"))
    }

    @Test fun downloadFollowsAFewRedirectsAndGivesUpOnLoops() = withServer { s, c ->
        s.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/files/real")); s.enqueue(MockResponse().setBody("data"))
        assertEquals(4L, c.download(file(s.url("/files/1").toString()), "T", target()))
        repeat(6) { s.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/again")) }
        val t = target(); assertThrows(MmfException.UnsafeDownload::class.java) { c.download(file(s.url("/loop").toString()), "T", t) }
        assertFalse(t.exists())
    }

    @Test fun downloadRefusesMissingTokenMissingLinksAndInsecureOrCredentialedUrls() {
        val c = MyMiniFactoryClient("k")
        assertTrue(assertThrows(MmfException.Unauthorized::class.java) { c.download(file("https://cdn.example.com/a.stl"), "", target()) }.needsSignIn)
        assertThrows(MmfException.UnsafeDownload::class.java) { c.download(file(null), "T", target()) }
        assertThrows(MmfException.UnsafeDownload::class.java) { c.download(file("http://cdn.example.com/a.stl"), "T", target()) }
        assertThrows(MmfException.UnsafeDownload::class.java) { c.download(file("https://u:p@cdn.example.com/a.stl"), "T", target()) }
    }

    @Test fun downloadFailuresLeaveNoPartialFileAndSayWhyAnAuthFailureHappened() = withServer { s, c ->
        s.enqueue(MockResponse().setResponseCode(401)); val t = target()
        assertTrue(assertThrows(MmfException.Unauthorized::class.java) { c.download(file(s.url("/f").toString()), "T", t) }.needsSignIn)
        s.enqueue(MockResponse().setBody("")); assertThrows(MmfException.Malformed::class.java) { c.download(file(s.url("/f").toString()), "T", t) }
        assertFalse(t.exists()); assertFalse(File(t.path + ".part").exists())
    }
}

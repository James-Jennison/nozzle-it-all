package net.jamesjennison.klippercompanion

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TimelapseTest {
    private fun entry(path: String, modified: Double = 0.0) =
        JSONObject().put("path", path).put("size", 1024).put("modified", modified)

    @Test fun parseKeepsOnlyKnownVideoExtensionsAndSortsNewestFirst() {
        val list = JSONArray().put(entry("old.mp4", 1.0)).put(entry("new.mp4", 2.0))
            .put(entry("frame0001.jpg", 3.0)).put(entry("README.txt", 4.0)).put(entry("clip.MOV", 5.0))
        val result = Timelapses.parse(list)
        assertEquals(listOf("clip.MOV", "new.mp4", "old.mp4"), result.map { it.path })
    }
    @Test fun parseDedupesRepeatedPaths() {
        val list = JSONArray().put(entry("a.mp4", 1.0)).put(entry("a.mp4", 1.0))
        assertEquals(1, Timelapses.parse(list).size)
    }
    @Test fun parseOfEmptyListingIsEmpty() {
        assertTrue(Timelapses.parse(JSONArray()).isEmpty())
    }
    @Test fun parsePairsAVideoWithItsSameStemPoster() {
        val list = JSONArray().put(entry("dragon.mp4", 1.0)).put(entry("dragon.jpg", 1.0)).put(entry("unrelated.jpeg", 1.0))
        val result = Timelapses.parse(list)
        assertEquals("dragon.jpg", result.single().posterPath)
    }
    @Test fun parseLeavesPosterNullWhenNoneWritten() {
        val result = Timelapses.parse(JSONArray().put(entry("solo.mkv", 1.0)))
        assertNull(result.single().posterPath)
    }
    @Test fun parseMatchesJpegExtensionCaseInsensitively() {
        val list = JSONArray().put(entry("clip.webm", 1.0)).put(entry("clip.JPEG", 1.0))
        assertEquals("clip.JPEG", Timelapses.parse(list).single().posterPath)
    }
    @Test fun requestsTimelapseRootAndParsesResponse() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"result":[{"path":"print1.mp4","size":2048,"modified":100.0}]}"""))
            server.start()
            val api = Moonraker(server.url("/").toString())
            val result = api.timelapses()
            val req = server.takeRequest()
            assertEquals("timelapse", req.requestUrl!!.queryParameter("root"))
            assertEquals(listOf("print1.mp4"), result.map { it.path })
            assertEquals(2048L, result.single().size)
            api.close()
        }
    }
    @Test fun missingTimelapseComponentSurfacesAsUnavailable() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400)); server.start()
            val api = Moonraker(server.url("/").toString())
            try { api.timelapses(); fail() } catch (_: ApiFailure) { }
            api.close()
        }
    }
    @Test fun timelapseThumbnailRejectsTraversalAndFetchesFromTheTimelapseRoot() {
        MockWebServer().use { server ->
            server.start(); val api = Moonraker(server.url("/proxy/").toString())
            for (path in listOf("../config/printer.cfg", "/etc/passwd", "a/../b", "a\\b")) {
                try { api.timelapseThumbnail(path); fail("Traversal accepted") } catch (_: ApiFailure) { }
            }
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setBody("image")); api.timelapseThumbnail("dragon.jpg")
            val req = server.takeRequest()
            assertEquals("/proxy/server/files/timelapse/dragon.jpg", req.requestUrl!!.encodedPath)
            api.close()
        }
    }
    @Test fun timelapseVideoUrlPointsAtTheTimelapseRootAndCarriesTheApiKeyAsAHeader() {
        val api = Moonraker("http://192.168.1.5/", "secret-key")
        val target = api.timelapseVideoUrl("2026-09-20/dragon.mp4")
        assertEquals("http://192.168.1.5/server/files/timelapse/2026-09-20/dragon.mp4", target.url)
        assertEquals("secret-key", target.headers["X-Api-Key"])
        assertFalse(target.url.contains("secret-key"))
        try { api.timelapseVideoUrl("../escape.mp4"); fail() } catch (_: ApiFailure) { }
    }
    @Test fun formatFileSizeScalesUnits() {
        assertEquals("512 B", formatFileSize(512))
        assertEquals("2 KB", formatFileSize(2048))
        assertEquals("1.5 MB", formatFileSize((1.5 * 1_048_576).toLong()))
        assertEquals("2.0 GB", formatFileSize(2L * 1_073_741_824))
        assertEquals("Unknown size", formatFileSize(null))
    }
    @Test fun dayLabelNamesTodayAndYesterdayThenFallsBackToADate() {
        val now = java.util.Date()
        assertEquals("Today", dayLabel(now.time / 1000.0, now))
        assertEquals("Yesterday", dayLabel(now.time / 1000.0 - 86_400, now))
        assertTrue(dayLabel(now.time / 1000.0 - 10 * 86_400, now) !in setOf("Today", "Yesterday"))
    }
}

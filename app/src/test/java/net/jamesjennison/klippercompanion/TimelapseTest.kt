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
}

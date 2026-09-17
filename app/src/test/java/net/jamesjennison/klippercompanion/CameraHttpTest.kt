package net.jamesjennison.klippercompanion

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Test
import org.junit.Assert.*

class CameraHttpTest {
    @Test fun snapshotReaderSurvivesInactiveCleanupAndCameraSelectionChange() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("first-image"))
            server.enqueue(MockResponse().setBody("second-image"))
            val api = Moonraker(server.url("/").toString())
            api.close()
            assertEquals("first-image", String(api.image(Camera("First", "/first"))))
            api.close()
            assertEquals("second-image", String(api.image(Camera("Second", "/second"))))
            assertEquals("/first", server.takeRequest().path)
            assertEquals("/second", server.takeRequest().path)
            api.close()
        }
    }
    @Test fun rejectsDowngradeEvenWhenPrinterBaseIsHttp() {
        try {
            cameraRedirectUrl("http://printer.local/", "https://printer.local/camera".toHttpUrl(), "http://printer.local/stream")
            fail("A camera HTTPS hop cannot downgrade")
        } catch(_: ApiFailure) {}
    }
    private fun client()=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    @Test fun followsSameHostCameraRedirectToAnotherPortAndPreservesQuery() {
        MockWebServer().use { source -> MockWebServer().use { camera ->
            source.start();camera.start()
            source.enqueue(MockResponse().setResponseCode(302).addHeader("Location",camera.url("/?action=stream")))
            camera.enqueue(MockResponse().addHeader("Content-Type","multipart/x-mixed-replace;boundary=frame").setBody("fixture"))
            cameraResponse(client(),source.url("/").toString(),"/webcam/?action=stream").use {
                assertEquals(200,it.code);assertEquals("fixture",it.body!!.string())
            }
            assertEquals("/webcam/?action=stream",source.takeRequest().path)
            val request=camera.takeRequest();assertEquals("GET",request.method);assertEquals("/?action=stream",request.path)
        } }
    }
    @Test fun rejectsRedirectToDifferentHostBeforeAnySecondRequest() {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setResponseCode(302).addHeader("Location","http://other.local:8080/stream"))
            try { cameraResponse(client(),server.url("/").toString(),"/camera").close();fail("Host change must fail") }
            catch(_:ApiFailure) {}
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun stopsRedirectLoopsAfterThreeHops() {
        MockWebServer().use { server ->
            server.start();repeat(4){server.enqueue(MockResponse().setResponseCode(302).addHeader("Location","/loop"))}
            try { cameraResponse(client(),server.url("/").toString(),"/loop").close();fail("Loop must fail") }
            catch(_:ApiFailure) {}
            assertEquals(4,server.requestCount)
        }
    }
    @Test fun snapshotUsesTheSameValidatedRedirectPath() {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setResponseCode(302).addHeader("Location","/actual?action=snapshot"))
            server.enqueue(MockResponse().setBody("fixture-image"))
            val api=Moonraker(server.url("/").toString())
            try { assertEquals("fixture-image",String(api.image(Camera("Fixture","/snapshot")))) }
            finally {api.close()}
            assertEquals(2,server.requestCount)
        }
    }
}

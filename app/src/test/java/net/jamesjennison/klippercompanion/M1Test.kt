package net.jamesjennison.klippercompanion

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.ByteArrayInputStream

class M1Test {
    @Test fun cameraGateBoundsHighRateStreamsBeforeDecode() {
        val gate=CameraFrameGate()
        val accepted=(0 until 300).count { gate.accept(it*16_666_667L) }
        assertTrue(accepted in 60..75)
        assertFalse(gate.accept(299*16_666_667L))
    }
    @Test fun estimatesRequireMatchingMetadataAndAnActivePrint() {
        val snapshot=PrinterSnapshot(true,"printing","part.gcode",printDuration=600.0)
        val metadata=FileMetadata("part.gcode",estimatedSeconds=1200.0)
        assertEquals(600.0,estimatedRemaining(snapshot,metadata)!!,0.01)
        assertNull(estimatedRemaining(snapshot,metadata.copy(filename="other.gcode")))
        assertNull(estimatedRemaining(snapshot.copy(state="paused"),metadata))
        assertNull(estimatedRemaining(snapshot.copy(printDuration=1400.0),metadata))
        assertNull(estimatedRemaining(snapshot,null))
        assertEquals("Unknown",formatDuration(Double.NaN));assertEquals("1h 1m",formatDuration(3660.0))
    }
    @Test fun layersAndDurationsAreOptionalAndNonnegative() {
        val s=Moonraker.parseSnapshot(JSONObject("""{"status":{"webhooks":{"state":"ready"},"print_stats":{"state":"printing","print_duration":-2,"info":{"current_layer":0,"total_layer":0}}}}"""))
        assertNull(s.printDuration);assertNull(s.currentLayer);assertNull(s.totalLayers)
    }
    @Test fun metadataResolvesThumbnailRelativeToGcodeParent() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"result":{"estimated_time":3600,"filament_total":1500,"thumbnails":[{"width":300,"height":300,"relative_path":".thumbs/model.png"}]}}"""));server.start()
            val api=Moonraker(server.url("/proxy/").toString());val m=api.metadata("folder/model.gcode")
            assertEquals("folder/.thumbs/model.png",m.thumbnail);assertEquals(3600.0,m.estimatedSeconds!!,0.01)
            val req=server.takeRequest();assertEquals("folder/model.gcode",req.requestUrl!!.queryParameter("filename"));assertEquals("/proxy/server/files/metadata",req.requestUrl!!.encodedPath)
            api.close()
        }
    }
    @Test fun thumbnailRejectsTraversalBeforeNetworkAndEncodesPaths() {
        MockWebServer().use { server ->
            server.start();val api=Moonraker(server.url("/proxy/").toString())
            for(path in listOf("../config/printer.cfg","/etc/passwd","a/../b","a\\b")) {
                try { api.thumbnail(path);fail("Traversal accepted") } catch(_: ApiFailure) {}
            }
            assertEquals(0,server.requestCount)
            server.enqueue(MockResponse().setBody("image"));api.thumbnail("folder/a #?.png")
            val req=server.takeRequest();assertEquals("/proxy/server/files/gcodes/folder/a%20%23%3F.png",req.requestUrl!!.encodedPath);assertNull(req.requestUrl!!.query)
            api.close()
        }
    }
    @Test fun historyIsPagedAndMissingMeasurementsStayUnknown() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"result":{"count":101,"jobs":[{"job_id":"01","filename":"x.gcode","status":"completed"}]}}"""));server.start()
            val api=Moonraker(server.url("/").toString());val page=api.history(50)
            assertEquals(1,page.pageSize);assertNull(page.jobs.single().duration);assertNull(page.jobs.single().filamentMm)
            val req=server.takeRequest();assertEquals("50",req.requestUrl!!.queryParameter("start"));assertEquals("50",req.requestUrl!!.queryParameter("limit"));assertEquals("GET",req.method);api.close()
        }
    }
    @Test fun mjpegParserExtractsSequentialFramesAndBoundsMalformedInput() {
        val data=byteArrayOf(1,2,-1,-40,10,11,-1,-39,13,10,-1,-40,22,-1,-39)
        val input=ByteArrayInputStream(data)
        assertArrayEquals(byteArrayOf(-1,-40,10,11,-1,-39),nextJpeg(input))
        assertArrayEquals(byteArrayOf(-1,-40,22,-1,-39),nextJpeg(input))
        for(bytes in listOf(ByteArray(100),byteArrayOf(-1,-40)+ByteArray(100),byteArrayOf(-1,-40,1))) {
            try {nextJpeg(ByteArrayInputStream(bytes),32);fail("Malformed frame accepted")} catch(_:ApiFailure){}
        }
    }
    @Test fun cameraSelectionUsesStableIdentityAndReportsRemovedSelection() {
        val a=Camera("A","","/a","webrtc-camerastreamer","a");val b=Camera("B","","/b","mjpegstreamer","b")
        val state=ScreenState(address="http://printer.local/",profiles=listOf(PrinterProfile("http://printer.local/",cameraId="b")),catalog=Catalog(emptyList(),emptyList(),listOf(a,b),emptyList()))
        assertEquals(b,state.selectedCamera());assertNull(state.copy(catalog=state.catalog.copy(cameras=listOf(a))).selectedCamera());assertEquals(a,state.copy(profiles=emptyList()).selectedCamera())
    }
}

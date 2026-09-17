package net.jamesjennison.klippercompanion

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import okhttp3.mockwebserver.*
import java.nio.file.Files

class LivePrintPreviewTest {
    private val code="; café\r\nG90\r\nM83\r\nG1 X0 Y0 Z0.2\r\nG1 X10 E1\r\nG1 Y10 E1"
    @Test fun byteOffsetsIncludeUtf8CrLfAndUnterminatedLastLine(){
        val bytes=code.toByteArray();val p=GcodePreview.parse(bytes.inputStream())
        assertEquals(bytes.size.toLong(),p.byteSize)
        val first=code.substringBeforeLast("G1 Y10").toByteArray().size.toLong()
        assertEquals(first,p.segments.first().byteEnd);assertEquals(p.byteSize,p.segments.last().byteEnd)
        assertNull(p.segmentAt(first-1));assertEquals(p.segments.first(),p.segmentAt(first))
        assertEquals(p.segments.last(),p.segmentAt(p.byteSize));assertNull(p.segmentAt(p.byteSize+1));assertNull(p.segmentAt(-1))
    }
    @Test fun arcSegmentsShareCommandEndAndMappingUsesLastRetainedSegment(){
        val code="G90\nM83\nG1 X10 Y0 Z0.2\nG3 X0 Y10 I-10 J0 E1\n"
        val p=GcodePreview.parse(code.byteInputStream());assertTrue(p.segments.size>1)
        assertTrue(p.segments.all{it.byteEnd==p.byteSize});assertEquals(p.segments.last(),p.segmentAt(p.byteSize))
    }
    @Test fun samplingPreservesOrderedByteMapping(){
        val text=buildString{append("G90\nM83\nG1 X0 Y0\n");repeat(125000){append("G1 X${it%2} E1\n")}}
        val p=GcodePreview.parse(text.byteInputStream());assertTrue(p.sampled)
        assertTrue(p.segments.zipWithNext().all{(a,b)->a.byteEnd<b.byteEnd})
        assertEquals(p.segments.last(),p.segmentAt(p.byteSize))
    }
    private fun status(name:String="a.gcode",size:Long=100,position:Any=50,state:String="printing")=JSONObject().put("webhooks",JSONObject().put("state","ready"))
        .put("print_stats",JSONObject().put("filename",name).put("state",state))
        .put("virtual_sdcard",JSONObject().put("file_size",size).put("file_position",position).put("is_active",state=="printing"))
    private fun meta(name:String="a.gcode",size:Long=100)=JSONObject().put("filename",name).put("size",size).put("modified",123.5)
    private fun rejected(block:()->Unit){try{block();fail("Expected rejection")}catch(_:IllegalArgumentException){}}
    @Test fun stateAndIdentityMustMatchAndPositionMustBeBoundedIntegral(){
        assertEquals(50L,LivePrintPreview.parse(status(),meta()).position)
        assertEquals("paused",LivePrintPreview.parse(status(state="paused"),meta()).state)
        rejected{LivePrintPreview.parse(status(state="complete"),meta())}
        rejected{LivePrintPreview.parse(status(position=101),meta())}
        rejected{LivePrintPreview.parse(status(position=-1),meta())}
        rejected{LivePrintPreview.parse(status(position=1.5),meta())}
        rejected{LivePrintPreview.parse(status(position="50"),meta())}
        rejected{LivePrintPreview.parse(status(),meta(size=99))}
        rejected{LivePrintPreview.parse(status(),meta(name="b.gcode"))}
        rejected{LivePrintPreview.parse(status(name="../a.gcode"),meta())}
    }
    @Test fun loadUsesOnlyGetsRejectsChangedIdentityAndDeletesTemporaryFile(){
        val server=MockWebServer();val dir=Files.createTempDirectory("preview-test").toFile();val size=code.toByteArray().size.toLong()
        var modified=123.5;val methods=mutableListOf<String>()
        server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse{
            methods.add(r.method!!)
            val result=when(r.requestUrl!!.encodedPath){
                "/printer/objects/query"->JSONObject().put("status",status(size=size,position=0))
                "/server/files/metadata"->meta(size=size).put("modified",modified)
                "/server/files/gcodes/a.gcode"->return MockResponse().setBody(code)
                else->return MockResponse().setResponseCode(404)
            }
            return MockResponse().setBody(JSONObject().put("result",result).toString())
        }};server.start()
        try{LivePrintPreview(server.url("/").toString()).use{api->
            val initial=api.progress();assertEquals(size,api.load(initial.identity,dir){false}.byteSize)
            assertTrue(dir.listFiles()!!.isEmpty())
            try{api.load(initial.identity,dir){true};fail("Expected cancellation")}catch(_:java.io.InterruptedIOException){}
            assertTrue(dir.listFiles()!!.isEmpty());modified=124.0
            rejected{api.load(initial.identity,dir){false}};assertTrue(dir.listFiles()!!.isEmpty())
            assertTrue(methods.all{it=="GET"})
        }}finally{server.shutdown();dir.deleteRecursively()}
    }
}

package net.jamesjennison.klippercompanion

import org.junit.Test
import org.junit.Assert.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.ByteArrayInputStream
import java.nio.file.Files

class M2Test {
    @Test fun macroNumericDefinitionsRoundTripAndRejectInjection() {
        val options=MacroOptions(true,"Preparation","TEMP=0,300,200\nCOUNT=1,10,2")
        assertEquals(mapOf("TEST" to options),MacroTools.decode(MacroTools.encode(mapOf("TEST" to options))))
        val defs=MacroTools.definitions(options.parameters)
        assertThrows(IllegalArgumentException::class.java){MacroTools.command("M140 S0 ; injection",emptyList(),emptyMap())}
        assertEquals("TEST TEMP=210 COUNT=2",MacroTools.command("TEST",defs,mapOf("TEMP" to "210.0","COUNT" to "2")).arguments["script"])
        for(value in listOf("200\nG28","200;G28","NaN","Infinity","301","1e2","-1")) {
            assertThrows(IllegalArgumentException::class.java) {MacroTools.command("TEST",defs,mapOf("TEMP" to value,"COUNT" to "2"))}
        }
        assertThrows(IllegalArgumentException::class.java) {MacroTools.definitions("A=0,2,1\nA=0,3,1")}
        assertThrows(IllegalArgumentException::class.java) {MacroTools.definitions("A=2,0,1")}
    }
    @Test fun previewTracksModesResetsUnitsAndLayers() {
        val text="G28\nG21\nG90\nM82\nG0 X0 Y0 Z0.2\nG1 X10 E1\nG92 E0\nM83\nG91\nG1 Y10 E1\nG90\nG92 X0\nG1 X5 E1\nG0 Z0.4\nG1 X10 E1\n"
        val p=GcodePreview.parse(ByteArrayInputStream(text.toByteArray()))
        assertEquals(4,p.segments.size);assertEquals(2,p.heights.size)
        assertEquals(10f,p.segments[2].x1);assertEquals(15f,p.segments[2].x2)
        assertEquals(1,p.segments.last().layer)
        val inch=GcodePreview.parse(ByteArrayInputStream("G20\nM83\nG0 X0 Y0\nG1 X1 E1\n".toByteArray()))
        assertEquals(25.4f,inch.segments.single().x2,0.001f)
    }
    @Test fun ijArcsTrackDirectionEndpointsAndRejectInconsistentGeometry() {
        fun parse(arc:String)=GcodePreview.parse(ByteArrayInputStream(("G90\nM83\nG0 X10 Y0 Z0.2\n"+arc).toByteArray()))
        val ccw=parse("G3 X0 Y10 I-10 J0 E1\n")
        assertTrue(ccw.segments.size>=18);assertEquals(10f,ccw.segments.first().x1);assertEquals(10f,ccw.segments.last().y2)
        assertTrue(ccw.segments.all {it.x2>=-0.001f&&it.y2>=-0.001f})
        val cw=parse("G2 X0 Y-10 I-10 J0 E1\n")
        assertTrue(cw.segments.all {it.y2<=0.001f})
        val travel=parse("G3 Z0.6 I-10 J0 P1\nG1 X11 E1")
        assertEquals(0.6f,travel.heights.single())
        assertThrows(IllegalArgumentException::class.java){parse("G3 X0 Y10 I-10 J0 Z1 E1")}
        assertThrows(IllegalArgumentException::class.java){parse("G3 X50 Y10 I-10 J0 E1")}
        assertThrows(IllegalArgumentException::class.java){parse("G3 X0 Y10 R10 E1")}
    }

    @Test fun commentsCannotChangeCoordinatesAndSkippedMovesAreDisclosed() {
        val p=GcodePreview.parse(ByteArrayInputStream("G90\nM83\nG0 X0 Y0\nG1 X10 E1 (X99 G28)\n".toByteArray()))
        assertEquals(10f,p.segments.single().x2);assertFalse(p.ignoredMotion)
        val endHome=GcodePreview.parse(ByteArrayInputStream("G90\nM83\nG0 X0 Y0\nG1 X10 E1\nG28 X0 Y0\n".toByteArray()))
        assertEquals(1,endHome.segments.size);assertTrue(endHome.ignoredMotion)
        val skipped=GcodePreview.parse(ByteArrayInputStream("G91\nM83\nG1 X10 E1\nG90\nG0 X0 Y0\nG1 X10 E1\n".toByteArray()))
        assertTrue(skipped.ignoredMotion)
    }

    @Test fun numberedLinesAndExplicitOriginsAreRecognized() {
        val numbered=GcodePreview.parse(ByteArrayInputStream("N10 G90\nN20 M83\nN30 G0 X0 Y0 Z0.2\nN40 G1 X10 E1\n".toByteArray()))
        assertEquals(1,numbered.segments.size);assertEquals(0.2f,numbered.heights.single())
        val origin=GcodePreview.parse(ByteArrayInputStream("G28\nM83\nG92 X0 Y0 Z0.2\nG1 X10 E1\nG1 Y10 E1\n".toByteArray()))
        assertEquals(2,origin.segments.size);assertEquals(10f,origin.segments.last().y2)
    }

    @Test fun unsupportedGeometryAndCancellationFailHonestly() {
        for(s in listOf("G0 X0 Y0\nG2 X10 Y10 E1","\u0000","X".repeat(16385))) {
            assertThrows(IllegalArgumentException::class.java) {GcodePreview.parse(ByteArrayInputStream(s.toByteArray()))}
        }
        assertThrows(java.io.InterruptedIOException::class.java) {GcodePreview.parse(ByteArrayInputStream(byteArrayOf())){true}}
    }
    @Test fun downloadIsEncodedBoundedAndDoesNotFollowRedirects() {
        MockWebServer().use {server->server.start();FileTransfer(server.url("/proxy/").toString()).use {api->
            val dir=Files.createTempDirectory("m2").toFile();val target=java.io.File(dir,"part.gcode")
            try {
                server.enqueue(MockResponse().setBody("G0 X0\n"));api.download("folder/a #?.gcode",target)
                assertEquals("G0 X0\n",target.readText());assertEquals("/proxy/server/files/gcodes/folder/a%20%23%3F.gcode",server.takeRequest().path)
                for(path in listOf("../a.gcode","/a.gcode","a\\b.gcode","a.cfg"))assertThrows(IllegalArgumentException::class.java){api.download(path,java.io.File(dir,"other"))}
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location","/elsewhere"))
                assertThrows(IllegalArgumentException::class.java){api.download("a.gcode",java.io.File(dir,"other"))}
                assertEquals(2,server.requestCount)
            }finally{dir.deleteRecursively()}
        }}
    }
    @Test fun cancelledCopiesRemovePartialAndPreserveExistingDestinations() {
        val dir=Files.createTempDirectory("m2copy").toFile();val dest=java.io.File(dir,"file")
        try {
            assertThrows(java.io.InterruptedIOException::class.java){FileTransfer.copyBounded(ByteArrayInputStream(ByteArray(100)),dest){true}}
            assertFalse(dest.exists());dest.writeText("keep")
            assertThrows(IllegalArgumentException::class.java){FileTransfer.copyBounded(ByteArrayInputStream(byteArrayOf(1)),dest)}
            assertEquals("keep",dest.readText())
        }finally{dir.deleteRecursively()}
    }
    @Test fun mutationsAreFixtureOnlyAndCannotOverwriteKnownFiles() {
        MockWebServer().use {server->server.start();val dir=Files.createTempDirectory("m2upload").toFile();val file=java.io.File(dir,"test.gcode").apply{writeText("G0 X0")}
            try {
                assertFalse(FileTransfer::class.java.methods.any {it.name in setOf("upload","rename","delete")})
                assertEquals(0,server.requestCount)
                FileMutationFixture(server.url("/").toString()).use {api->
                    assertThrows(IllegalArgumentException::class.java){api.upload(file,"test.gcode",setOf("test.gcode"))}
                    server.enqueue(MockResponse().setBody("""{"result":{"item":{"path":"new.gcode"}}}"""));api.upload(file,"new.gcode",emptySet())
                    val request=server.takeRequest();assertEquals("POST",request.method);val body=request.body.readUtf8();assertTrue(body.contains("name=\"print\""));assertTrue(body.contains("false"))
                    server.enqueue(MockResponse().setBody("""{"result":{"item":{"path":"renamed.gcode"}}}"""));api.rename("new.gcode","renamed.gcode",setOf("new.gcode"));assertEquals("/server/files/move",server.takeRequest().path)
                    server.enqueue(MockResponse().setBody("""{"result":{"item":{"path":"renamed.gcode"}}}"""));api.delete("renamed.gcode");assertEquals("DELETE",server.takeRequest().method)
                }
            }finally{dir.deleteRecursively()}
        }
    }
}

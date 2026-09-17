package net.jamesjennison.klippercompanion

import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class LiveFileChangesTest {
    private class Fixture:AutoCloseable {
        val dir=Files.createTempDirectory("live-files").toFile()
        val server=MockWebServer();val files=ConcurrentHashMap<String,String>()
        var state="standby";var active=false;var loaded="";var writes=0;var lostAck=false;var retainDeleted=false;var absenceStatus=404
        init {
            files["source.gcode"]="; harmless test\n"
            server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse {
                val path=r.requestUrl!!.encodedPath
                fun json(v:Any)=MockResponse().setBody(JSONObject().put("result",v).toString())
                if(path=="/printer/objects/query")return json(JSONObject().put("status",JSONObject()
                    .put("webhooks",JSONObject().put("state","ready")).put("print_stats",JSONObject().put("state",state).put("filename",loaded))
                    .put("virtual_sdcard",JSONObject().put("is_active",active))))
                if(path=="/server/files/list")return json(JSONArray(files.keys.map{JSONObject().put("path",it)}))
                if(r.method=="GET"&&path.startsWith("/server/files/gcodes/"))return files[r.requestUrl!!.pathSegments.drop(3).joinToString("/")]?.let{MockResponse().setBody(it)}?:MockResponse().setResponseCode(absenceStatus)
                if(r.method=="DELETE"&&path.startsWith("/server/files/gcodes/")){
                    writes++;val name=r.requestUrl!!.pathSegments.drop(3).joinToString("/");if(!retainDeleted)files.remove(name)
                    return if(lostAck)MockResponse().setBody("lost acknowledgement") else json(JSONObject().put("item",JSONObject().put("root","gcodes").put("path",name)).put("action","delete_file"))
                }
                if(path=="/server/files/move"){
                    writes++;val body=JSONObject(r.body.readUtf8());val src=body.getString("source").removePrefix("gcodes/");val dst=body.getString("dest").removePrefix("gcodes/")
                    files[dst]=files.remove(src)?:return MockResponse().setResponseCode(404)
                    return if(lostAck)MockResponse().setBody("invalid acknowledgement") else json(JSONObject().put("item",JSONObject().put("root","gcodes").put("path",dst)))
                }
                if(path=="/server/files/upload"){
                    writes++;val body=r.body.readUtf8();val leaf=Regex("filename=\"([^\"]+)\"").find(body)!!.groupValues[1]
                    val folder=Regex("name=\"path\"\r\n(?:Content-Length: [0-9]+\r\n)?\r\n([^\r]*)").find(body)?.groupValues?.get(1).orEmpty()
                    val dest=if(folder.isEmpty())leaf else "$folder/$leaf"
                    val payload=body.substringAfter("Content-Type: application/octet-stream\r\n").substringAfter("\r\n\r\n", "")
                    // OkHttp includes Content-Length for file parts.
                    files[dest]=payload.substringBeforeLast("\r\n--")
                    assertTrue(body.contains("name=\"print\"\r\nContent-Length: 5\r\n\r\nfalse") || body.contains("\r\n\r\nfalse\r\n"))
                    return MockResponse().setResponseCode(201).setBody(JSONObject().put("item",JSONObject().put("root","gcodes").put("path",dest)).put("print_started",false).toString())
                }
                return MockResponse().setResponseCode(404)
            }}
            server.start()
        }
        fun api(clock:()->Long={System.nanoTime()/1_000_000})=LiveFileChanges(server.url("/").toString(),dir,clock)
        override fun close(){server.shutdown();dir.deleteRecursively()}
    }
    private fun rejected(block:()->Unit){try{block();fail("Expected rejection")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}
    @Test fun uniqueNamesKeepFolderExtensionAndNeverReuseDestination(){
        val a=LiveFileChanges.uniqueDestination("folder/model.gcode");val b=LiveFileChanges.uniqueDestination("folder/model.gcode")
        assertNotEquals(a,b);assertTrue(a.matches(Regex("folder/model-[0-9a-f-]{36}\\.gcode")))
        rejected{LiveFileChanges.uniqueDestination("../model.gcode")}
    }
    @Test fun renameVerifiesContentAndConsumesConfirmation(){Fixture().use{f->f.api().use{api->
        val d=api.prepare(LiveFileChanges.Operation.RENAME,"source.gcode","renamed.gcode",null)
        assertEquals(0,f.writes);assertTrue(api.confirm(d.id).startsWith("Verified rename:"));assertEquals(1,f.writes)
        assertFalse(f.files.containsKey("source.gcode"));assertEquals("; harmless test\n",f.files[d.destination]);rejected{api.confirm(d.id)};assertEquals(1,f.writes)
    }}}
    @Test fun changedSourcePreventsDispatch(){Fixture().use{f->f.api().use{api->
        val d=api.prepare(LiveFileChanges.Operation.RENAME,"source.gcode","new.gcode",null);f.files["source.gcode"]="changed"
        rejected{api.confirm(d.id)};assertEquals(0,f.writes)
    }}}
    @Test fun observedDestinationCollisionPreventsDispatch(){Fixture().use{f->f.api().use{api->
        val d=api.prepare(LiveFileChanges.Operation.RENAME,"source.gcode","new.gcode",null);f.files[d.destination]="other"
        rejected{api.confirm(d.id)};assertEquals("other",f.files[d.destination]);assertEquals(0,f.writes)
    }}}
    @Test fun cancellationInvalidatesDraft(){Fixture().use{f->f.api().use{api->
        val d=api.prepare(LiveFileChanges.Operation.RENAME,"source.gcode","new.gcode",null);api.cancel();rejected{api.confirm(d.id)};assertEquals(0,f.writes)
    }}}
    @Test fun printingAndLoadedFileAreRejected(){Fixture().use{f->f.api().use{api->
        f.state="printing";rejected{api.prepare(LiveFileChanges.Operation.RENAME,"source.gcode","new.gcode",null)}
        f.state="standby";f.loaded="source.gcode";rejected{api.prepare(LiveFileChanges.Operation.RENAME,"source.gcode","new.gcode",null)};assertEquals(0,f.writes)
    }}}
    @Test fun stateChangeBetweenReviewAndConfirmPreventsWrite(){Fixture().use{f->f.api().use{api->
        val d=api.prepare(LiveFileChanges.Operation.RENAME,"source.gcode","new.gcode",null);f.active=true;rejected{api.confirm(d.id)};assertEquals(0,f.writes)
    }}}
    @Test fun expiryPreventsWrite(){Fixture().use{f->var time=0L;f.api{time}.use{api->
        val d=api.prepare(LiveFileChanges.Operation.RENAME,"source.gcode","new.gcode",null);time=120001;rejected{api.confirm(d.id)};assertEquals(0,f.writes)
    }}}
    @Test fun lostAcknowledgementPersistsUncertaintyWithoutRetry(){Fixture().use{f->f.api().use{api->
        val d=api.prepare(LiveFileChanges.Operation.RENAME,"source.gcode","new.gcode",null);f.lostAck=true
        try{api.confirm(d.id);fail()}catch(e:IllegalStateException){assertTrue(e.message!!.contains("no retry"))}
        assertEquals(1,f.writes);assertTrue(f.files.containsKey(d.destination));assertTrue(api.recoveryNotice().contains(d.destination));rejected{api.confirm(d.id)}
    }}}
    @Test fun uploadUsesFrozenCopyAndReadback(){Fixture().use{f->f.api().use{api->
        val upload=File(f.dir,"input.gcode");upload.writeText("; harmless test\n")
        val d=api.prepare(LiveFileChanges.Operation.UPLOAD,"","subfolder/upload.gcode",upload);upload.writeText("changed")
        assertTrue(api.confirm(d.id).startsWith("Verified upload:"));assertEquals("; harmless test\n",f.files[d.destination]);assertEquals(1,f.writes);assertTrue(d.destination.startsWith("subfolder/"));assertTrue(api.recoveryNotice().startsWith("Verified upload:"))
    }}}
    @Test fun interruptedUploadDoesNotRetryAndKeepsOutcomeReceipt() {
        val dir=Files.createTempDirectory("interrupted-upload").toFile()
        try {MockWebServer().use {server->
            val status=JSONObject().put("result",JSONObject().put("status",JSONObject()
                .put("webhooks",JSONObject().put("state","ready"))
                .put("print_stats",JSONObject().put("state","standby").put("filename",""))
                .put("virtual_sdcard",JSONObject().put("is_active",false)))).toString()
            repeat(2){server.enqueue(MockResponse().setBody(status));server.enqueue(MockResponse().setBody("{\"result\":[]}"))}
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY));server.start()
            val input=File(dir,"source.gcode");input.writeBytes(ByteArray(1024*1024){59})
            LiveFileChanges(server.url("/").toString(),dir).use {api->
                val d=api.prepare(LiveFileChanges.Operation.UPLOAD,"","interrupted.gcode",input)
                try {api.confirm(d.id);fail("Interrupted upload must not succeed")}catch(e:IllegalStateException){assertTrue(e.message!!.contains("no retry"))}
                assertEquals(5,server.requestCount);assertTrue(api.recoveryNotice().contains(d.destination))
                rejected{api.confirm(d.id)};assertEquals(5,server.requestCount);assertEquals(1024L*1024,input.length())
                assertEquals(listOf("source.gcode"),dir.listFiles()!!.filter{it.extension=="gcode"}.map{it.name})
            }
        }}finally{dir.deleteRecursively()}
    }

    @Test fun deletionRequiresMatchingSourceAndVerifiesAbsenceOnce(){Fixture().use{f->f.api().use{api->
        val d=api.prepare(LiveFileChanges.Operation.DELETE,"source.gcode","",null);assertEquals(0,f.writes)
        assertEquals("Verified delete: source.gcode",api.confirm(d.id));assertFalse(f.files.containsKey("source.gcode"));assertEquals(1,f.writes)
        rejected{api.confirm(d.id)};assertEquals(1,f.writes);assertTrue(api.recoveryNotice().startsWith("Verified delete:"))
    }}}
    @Test fun changedDeletionSourceIsNotRemoved(){Fixture().use{f->f.api().use{api->
        val d=api.prepare(LiveFileChanges.Operation.DELETE,"source.gcode","",null);f.files["source.gcode"]="changed"
        rejected{api.confirm(d.id)};assertEquals(0,f.writes);assertEquals("changed",f.files["source.gcode"])
    }}}
    @Test fun deletionRejectsLoadedFileAndStateChange(){Fixture().use{f->f.api().use{api->
        f.loaded="source.gcode";rejected{api.prepare(LiveFileChanges.Operation.DELETE,"source.gcode","",null)}
        f.loaded="";val d=api.prepare(LiveFileChanges.Operation.DELETE,"source.gcode","",null);f.state="printing"
        rejected{api.confirm(d.id)};assertEquals(0,f.writes)
    }}}
    @Test fun cancelledDeleteCannotDispatch(){Fixture().use{f->f.api().use{api->
        val d=api.prepare(LiveFileChanges.Operation.DELETE,"source.gcode","",null);api.cancel();rejected{api.confirm(d.id)};assertEquals(0,f.writes)
    }}}
    @Test fun deleteLostAcknowledgementIsNeverRetried(){Fixture().use{f->f.api().use{api->
        val d=api.prepare(LiveFileChanges.Operation.DELETE,"source.gcode","",null);f.lostAck=true
        try{api.confirm(d.id);fail()}catch(e:IllegalStateException){assertTrue(e.message!!.contains("no retry"))}
        assertFalse(f.files.containsKey("source.gcode"));assertEquals(1,f.writes);assertTrue(api.recoveryNotice().contains("Outcome pending"));rejected{api.confirm(d.id)};assertEquals(1,f.writes)
    }}}
    @Test fun successfulDeleteAcknowledgementDoesNotReplaceAbsenceProof(){Fixture().use{f->f.api().use{api->
        val d=api.prepare(LiveFileChanges.Operation.DELETE,"source.gcode","",null);f.retainDeleted=true
        try{api.confirm(d.id);fail()}catch(e:IllegalStateException){assertTrue(e.message!!.contains("absence could not be verified"))};assertEquals(1,f.writes)
    }}}
    @Test fun missingFileWithForbiddenReadIsNotVerifiedDeleted(){Fixture().use{f->f.api().use{api->
        val d=api.prepare(LiveFileChanges.Operation.DELETE,"source.gcode","",null);f.absenceStatus=403
        try{api.confirm(d.id);fail()}catch(e:IllegalStateException){assertTrue(e.message!!.contains("HTTP 403"))}
    }}}
    @Test fun emptyGcodeCanBeDeletedAndUnsafePathsCannot(){Fixture().use{f->f.api().use{api->
        f.files["empty.gcode"]="";val d=api.prepare(LiveFileChanges.Operation.DELETE,"empty.gcode","",null);assertEquals(0L,d.bytes);api.confirm(d.id)
        rejected{api.prepare(LiveFileChanges.Operation.DELETE,"../config/printer.cfg","",null)};assertEquals(1,f.writes)
    }}}

}

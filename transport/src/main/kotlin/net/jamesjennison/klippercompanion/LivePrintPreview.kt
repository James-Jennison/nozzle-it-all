package net.jamesjennison.klippercompanion

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class PrintFileIdentity(val filename:String,val size:Long,val modified:Double)
data class PrintFileProgress(val identity:PrintFileIdentity,val position:Long,val state:String)

/** Last retained command consumed at this byte offset; never a physical nozzle coordinate. */
fun Toolpath.segmentAt(position:Long):ToolpathSegment? {
    if(position !in 0..byteSize)return null
    var lo=0;var hi=segments.size
    while(lo<hi){val mid=(lo+hi)/2;if(segments[mid].byteEnd<=position)lo=mid+1 else hi=mid}
    return segments.getOrNull(lo-1)
}

/** GET-only active-file reader. Filename, size and modification time are a metadata identity,
 * not a server content hash. A same-size replacement preserving mtime cannot be detected. */
class LivePrintPreview(address:String, rawApiKey:String=""):AutoCloseable {
    private val base=Moonraker.parseAddress(address)
    private val apiKey=rawApiKey.trim()
    private val transfer=FileTransfer(address,apiKey)
    private val client=OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(7,TimeUnit.SECONDS)
        .callTimeout(10,TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().apply { if (apiKey.isNotEmpty()) header("X-Api-Key", apiKey) }.build()) }
        .build()
    private fun get(path:String,query:Map<String,String> = emptyMap()):JSONObject {
        val url=base.newBuilder().addPathSegments(path).apply{query.forEach{(k,v)->addQueryParameter(k,v)}}.build()
        return client.newCall(Request.Builder().url(url).get().build()).execute().use{r->
            require(r.isSuccessful){"Preview read failed (HTTP ${r.code})."}
            val input=requireNotNull(r.body).source();input.request(2_000_001)
            require(input.buffer.size<=2_000_000){"Preview response is too large."}
            JSONObject(input.buffer.readUtf8()).getJSONObject("result")
        }
    }
    private fun status()=get("printer/objects/query",mapOf("webhooks" to "state","print_stats" to "state,filename","virtual_sdcard" to "file_position,file_size,is_active")).getJSONObject("status")
    companion object {
        private fun integer(obj:JSONObject,key:String):Long {
            val n=obj.get(key);require(n is Number){"Missing numeric $key."}
            val d=n.toDouble();require(d.isFinite()&&d>=0&&d<=GcodePreview.MAX_BYTES&&d==d.toLong().toDouble()){"Invalid $key."}
            return d.toLong()
        }
        fun parse(status:JSONObject,metadata:JSONObject):PrintFileProgress {
            require(status.getJSONObject("webhooks").getString("state")=="ready"){"Printer is not ready."}
            val print=status.getJSONObject("print_stats");val state=print.getString("state")
            require(state in setOf("printing","paused")){"No active or paused print to follow."}
            val name=FileTransfer.validate(print.getString("filename"));val sd=status.getJSONObject("virtual_sdcard")
            require(state=="paused"||sd.get("is_active")==true){"Print file is not active."}
            val size=integer(sd,"file_size");val position=integer(sd,"file_position")
            require(size>0&&position<=size&&integer(metadata,"size")==size){"Active file size or position changed."}
            require(metadata.getString("filename")==name){"Active filename changed."}
            val modified=metadata.get("modified");require(modified is Number&&modified.toDouble().isFinite()&&modified.toDouble()>0){"File modification time unavailable."}
            return PrintFileProgress(PrintFileIdentity(name,size,modified.toDouble()),position,state)
        }
    }
    fun progress():PrintFileProgress {
        val first=status();val name=FileTransfer.validate(first.getJSONObject("print_stats").getString("filename"))
        val metadata=get("server/files/metadata",mapOf("filename" to name))
        val initial=parse(first,metadata);val last=parse(status(),metadata)
        require(initial.identity==last.identity){"Active file changed during read."}
        return last
    }
    fun load(expected:PrintFileIdentity,cache:File,cancelled:()->Boolean):Toolpath {
        val target=File(cache,"live-preview-${java.util.UUID.randomUUID()}.gcode")
        try {
            transfer.download(expected.filename,target,cancelled)
            require(target.length()==expected.size){"Downloaded file size changed."}
            require(progress().identity==expected){"Active file changed during download."}
            val path=target.inputStream().use{GcodePreview.parse(it,cancelled)}
            require(path.byteSize==expected.size){"Preview byte count differs from active file."}
            return path
        } finally {target.delete()}
    }
    override fun close(){transfer.close();client.dispatcher.cancelAll();client.connectionPool.evictAll()}
}

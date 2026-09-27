package net.jamesjennison.klippercompanion

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

interface LiveFileBackend:AutoCloseable {
    fun recoveryNotice():String
    fun prepare(operation:LiveFileChanges.Operation,source:String,requested:String,upload:File?):LiveFileChanges.Draft
    fun confirm(id:String):String
    fun cancel()
}

/** Single-use, unique-destination operations. No server-side no-replace guarantee is implied. */
class LiveFileChanges(address:String, private val cache:File,
    private val now:()->Long={System.nanoTime()/1_000_000}, rawApiKey:String=""):LiveFileBackend {
    enum class Operation { UPLOAD, RENAME, DELETE }
    data class Draft(val id:String,val operation:Operation,val source:String,val destination:String,val bytes:Long,val sha256:String)
    private data class Pending(val draft:Draft,val local:File?,val prepared:Long,val epoch:Long)
    private val base=Moonraker.parseAddress(address)
    private val apiKey=rawApiKey.trim()
    private val client=OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(5,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(120,TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().apply { if (apiKey.isNotEmpty()) header("X-Api-Key", apiKey) }.build()) }
        .build()
    private var pending:Pending?=null
    private var epoch=0L
    private var closed=false
    private var active=false
    private var writeCall:Call?=null
    private val receipt=File(cache,"last-"+MessageDigest.getInstance("SHA-256").digest(base.toString().toByteArray()).joinToString(""){"%02x".format(it)}+".json")
    override fun recoveryNotice():String=if(receipt.isFile && receipt.length()<8192)runCatching{JSONObject(receipt.readText()).let { "${it.getString("state")}: ${it.getString("destination")}" }}.getOrDefault("Previous file operation needs inspection.") else ""
    private fun record(d:Draft,state:String){
        check(cache.isDirectory||cache.mkdirs())
        val data=JSONObject().put("state",state).put("destination",d.destination).put("source",d.source).put("sha256",d.sha256).toString()
        val temporary=File(cache,"receipt-${UUID.randomUUID()}.tmp")
        try {java.io.FileOutputStream(temporary).use{it.write(data.toByteArray());it.fd.sync()};java.nio.file.Files.move(temporary.toPath(),receipt.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)}finally{temporary.delete()}
    }
    companion object {
        fun uniqueDestination(requested:String):String {
            FileTransfer.validate(requested)
            val name=requested.substringAfterLast('/');val stem=name.substringBeforeLast('.').take(80)
            require(stem.isNotBlank()){ "Enter a filename." }
            val folder=requested.substringBeforeLast('/',"")
            return (if(folder.isEmpty())"" else "$folder/")+stem+"-"+UUID.randomUUID()+"."+name.substringAfterLast('.')
        }
    }
    private fun url(path:String)=base.newBuilder().addPathSegments(path).build()
    private fun fileUrl(path:String)=base.newBuilder().addPathSegments("server/files/gcodes").apply {
        FileTransfer.validate(path).split('/').forEach {addPathSegment(it)}
    }.build()
    private fun json(request:Request,ticket:Long?=null,uploadResponse:Boolean=false):Any {
        val call=client.newCall(request)
        if(ticket!=null)synchronized(this){checkTicket(ticket);writeCall=call}
        try {return call.execute().use {r->
        require(r.isSuccessful){"File request failed (HTTP ${r.code})."}
        val input=r.body?.source()?:error("Missing response.")
        input.request(2_000_001);require(input.buffer.size<=2_000_000){"File response is too large."}
        val document=JSONObject(input.buffer.readUtf8())
        if(uploadResponse)document else document.get("result")
        }}finally{if(ticket!=null)synchronized(this){if(writeCall===call)writeCall=null}}
    }
    fun files():List<String> {
        val a=json(Request.Builder().url(url("server/files/list").newBuilder().addQueryParameter("root","gcodes").build()).build()) as JSONArray
        require(a.length()<=10000){"Too many files."}
        return (0 until a.length()).map {a.getJSONObject(it).getString("path")}
    }
    private fun ready(source:String="") {
        val u=url("printer/objects/query").newBuilder().addQueryParameter("webhooks","state")
            .addQueryParameter("print_stats","state,filename").addQueryParameter("virtual_sdcard","is_active").build()
        val s=(json(Request.Builder().url(u).build()) as JSONObject).getJSONObject("status")
        require(s.getJSONObject("webhooks").getString("state")=="ready" &&
            s.getJSONObject("print_stats").getString("state") in setOf("standby","complete","cancelled") &&
            !s.getJSONObject("virtual_sdcard").getBoolean("is_active")){"File changes require an idle, ready printer."}
        require(source.isEmpty() || s.getJSONObject("print_stats").optString("filename")!=source){"The source is still loaded by the printer. Choose another file."}
    }
    private fun digest(input:java.io.InputStream):Pair<Long,String> {
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(32768);var count=0L
        while(true){if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException("Cancelled")
            val n=input.read(buffer);if(n<0)break;count+=n;require(count<=GcodePreview.MAX_BYTES){"File exceeds 256 MiB."};digest.update(buffer,0,n)}
        return count to digest.digest().joinToString(""){"%02x".format(it)}
    }
    private fun remoteDigest(path:String)=client.newCall(Request.Builder().url(fileUrl(path)).build()).execute().use {r->
        require(r.isSuccessful){"Cannot verify file content (HTTP ${r.code})."}
        r.body!!.byteStream().use(::digest)
    }
    private fun checkTicket(ticket:Long)=synchronized(this){check(!closed && ticket==epoch){"Operation cancelled. Refresh files before trying again."}}
    override fun prepare(operation:Operation,source:String,requested:String,upload:File?):Draft {
        val ticket=synchronized(this){check(!closed&&!active);active=true;pending?.local?.delete();pending=null;++epoch}
        var local:File?=null
        try {
            // A file sliced for Elegoo's stock firmware (M729/M8213) never goes to a Moonraker printer: Klipper lacks both and
            // COSMOS 26.07+ emergency-stops on them. Checked before anything is sent (ElegooProfiles.stockElegooCommand).
            if(operation==Operation.UPLOAD && upload!=null && upload.isFile)
                upload.bufferedReader().useLines { ElegooProfiles.stockElegooCommand(it) }?.let { throw IllegalArgumentException(ElegooProfiles.stockElegooRefusal(it)) }
            val destination=if(operation==Operation.DELETE)FileTransfer.validate(source) else uniqueDestination(requested);ready(if(operation!=Operation.UPLOAD)source else "")
            val list=files();require(operation==Operation.DELETE || destination !in list){"Destination exists. Review again for a new name."}
            val identity=if(operation==Operation.UPLOAD){
                require(upload!=null && upload.isFile){"Import or download a G-code file first."}
                check(cache.isDirectory||cache.mkdirs());local=File(cache,"${UUID.randomUUID()}.gcode")
                upload.inputStream().use {FileTransfer.copyBounded(it,local!!){Thread.currentThread().isInterrupted || synchronized(this){ticket!=epoch||closed}}}
                local!!.inputStream().use(::digest)
            } else {FileTransfer.validate(source);require(source in list){"Source no longer exists."};remoteDigest(source)}
            checkTicket(ticket)
            val draft=Draft(UUID.randomUUID().toString(),operation,if(operation!=Operation.UPLOAD)source else upload!!.name,destination,identity.first,identity.second)
            synchronized(this){checkTicket(ticket);pending=Pending(draft,local,now(),ticket)}
            local=null;return draft
        }finally {local?.delete();synchronized(this){active=false}}
    }
    override fun confirm(id:String):String {
        val p=synchronized(this){check(!closed&&!active);val p=pending;require(p!=null&&p.draft.id==id){"Review the operation again."};pending=null;active=true;p}
        var dispatched=false
        try {
            require(now()-p.prepared in 0..120000){"Review expired. Review again."}
            val d=p.draft;ready(if(d.operation!=Operation.UPLOAD)d.source else "")
            require(d.operation==Operation.DELETE || d.destination !in files()){ "Destination now exists. Review again." }
            if(d.operation!=Operation.UPLOAD)require(remoteDigest(d.source)==(d.bytes to d.sha256)){"Source changed. Review again."}
            if(d.operation!=Operation.UPLOAD)ready(d.source)
            require(now()-p.prepared in 0..120000){"Review expired. Review again."}
            checkTicket(p.epoch)
            val request=if(d.operation==Operation.UPLOAD){
                val body=MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("root","gcodes")
                    .addFormDataPart("print","false").addFormDataPart("checksum",d.sha256)
                    .addFormDataPart("path",d.destination.substringBeforeLast('/',""))
                    .addFormDataPart("file",d.destination.substringAfterLast('/'),p.local!!.asRequestBody("application/octet-stream".toMediaType())).build()
                Request.Builder().url(url("server/files/upload")).post(body).build()
            } else if(d.operation==Operation.DELETE)Request.Builder().url(fileUrl(d.source)).delete().build()
            else Request.Builder().url(url("server/files/move")).post(JSONObject().put("source","gcodes/${d.source}").put("dest","gcodes/${d.destination}").toString().toRequestBody("application/json".toMediaType())).build()
            record(d,"Outcome pending; inspect before another operation")
            dispatched=true
            val result=json(request,p.epoch,uploadResponse=d.operation==Operation.UPLOAD) as JSONObject
            require(result.getJSONObject("item").getString("path")==d.destination && result.getJSONObject("item").getString("root")=="gcodes"){"Unexpected acknowledgement."}
            require(!result.optBoolean("print_started")&&!result.optBoolean("print_queued")){"Unexpected print or queue response."}
            checkTicket(p.epoch)
            if(d.operation==Operation.DELETE){
                client.newCall(Request.Builder().url(fileUrl(d.source)).build()).execute().use{r->require(r.code==404){"File absence could not be verified (HTTP ${r.code})."}}
                require(d.source !in files()){ "File still listed after deletion." }
            } else require(remoteDigest(d.destination)==(d.bytes to d.sha256)){"Destination content verification failed."}
            if(d.operation==Operation.RENAME)require(d.source !in files()){ "Source still exists after rename." }
            checkTicket(p.epoch)
            record(d,"Verified ${d.operation.name.lowercase()}")
            return "Verified ${d.operation.name.lowercase()}: ${d.destination}"
        }catch(e:Exception){if(dispatched)throw IllegalStateException("Outcome requires inspection; no retry sent. Check ${p.draft.destination}. ${e.message}",e);throw e}
        finally{p.local?.delete();synchronized(this){active=false}}
    }
    override fun cancel(){synchronized(this){epoch++;writeCall?.cancel();pending?.local?.delete();pending=null};client.dispatcher.cancelAll()}
    override fun close(){synchronized(this){closed=true};cancel();client.connectionPool.evictAll()}
}

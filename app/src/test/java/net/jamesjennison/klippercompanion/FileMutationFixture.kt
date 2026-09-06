package net.jamesjennison.klippercompanion

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

/** Fixture-only protocol prototype. Pre-checks are not atomic and confer no live no-overwrite guarantee. */
class FileMutationFixture(address:String):AutoCloseable {
    private val base=Moonraker.parseAddress(address)
    private val client=OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    private fun validate(path:String)=FileTransfer.validate(path)
    private fun fileUrl(path:String)=base.newBuilder().addPathSegments("server/files/gcodes").apply {validate(path).split('/').forEach {addPathSegment(it)}}.build()
    private fun write(request: Request): JSONObject {
        check(base.host in setOf("127.0.0.1","::1","localhost")) { "Printer file changes await idle-printer acceptance." }
        client.newCall(request).execute().use { r ->
            if(!r.isSuccessful)throw ApiFailure("File operation rejected or outcome unknown. Inspect files; no retry sent.")
            val source=r.body?.source() ?: throw ApiFailure("Missing acknowledgement; outcome unknown.")
            source.request(1_000_001);val bytes=source.buffer.readByteArray();require(bytes.size<=1_000_000)
            return JSONObject(bytes.toString(Charsets.UTF_8)).getJSONObject("result")
        }
    }
    fun upload(file: File, target: String, existing: Set<String>) {
        validate(target);require(target !in existing) { "Destination already exists; choose a new name." }
        require(file.length() in 1..GcodePreview.MAX_BYTES)
        val body=MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("root","gcodes").addFormDataPart("print","false")
            .addFormDataPart("file",target,file.asRequestBody("application/octet-stream".toMediaType())).build()
        val r=write(Request.Builder().url(base.resolve("server/files/upload")!!).post(body).build())
        require(r.getJSONObject("item").getString("path")==target) { "Unexpected upload acknowledgement; inspect files." }
    }
    fun rename(source: String,destination: String,existing: Set<String>) {
        validate(source);validate(destination);require(source in existing && destination !in existing) { "Source missing or destination exists." }
        val body=JSONObject().put("source","gcodes/$source").put("dest","gcodes/$destination").toString().toRequestBody("application/json".toMediaType())
        val r=write(Request.Builder().url(base.resolve("server/files/move")!!).post(body).build())
        require(r.getJSONObject("item").getString("path")==destination) { "Unexpected rename acknowledgement; inspect files." }
    }
    fun delete(path: String) { val r=write(Request.Builder().url(fileUrl(path)).delete().build());require(r.getJSONObject("item").getString("path")==path) { "Unexpected delete acknowledgement; inspect files." } }
    override fun close(){client.dispatcher.cancelAll();client.connectionPool.evictAll()}
}

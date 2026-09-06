package net.jamesjennison.klippercompanion

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** Read-only production file transport. Mutation prototypes live only in the JVM test source set. */
class FileTransfer(address: String): AutoCloseable {
    private val base=Moonraker.parseAddress(address)
    private val client=OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(120,TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    companion object {
        fun validate(path: String): String {
            require(path.length in 1..1024 && !path.startsWith('/') && !path.contains('\\') && path.none { it.code<32 || it.code==127 }) { "Invalid file path." }
            require(path.split('/').none { it in setOf("",".","..") } && (path.endsWith(".gcode",true)||path.endsWith(".gco",true))) { "Choose a relative G-code file path." }
            return path
        }
        fun copyBounded(input: InputStream, target: File, cancelled: ()->Boolean = {Thread.currentThread().isInterrupted}) {
            require(!target.exists()) { "Destination already exists." }
            try { target.outputStream().use { output ->
                val buffer=ByteArray(32768);var total=0L
                while(true) { if(cancelled())throw InterruptedIOException("Transfer cancelled");val n=input.read(buffer);if(n<0)break;total+=n;require(total<=GcodePreview.MAX_BYTES) { "File exceeds 256 MiB." };output.write(buffer,0,n) }
                require(total>0) { "File is empty." }
            } } catch(e:Exception) { target.delete();throw e }
        }
    }
    private fun fileUrl(path: String)=base.newBuilder().addPathSegments("server/files/gcodes").apply { validate(path).split('/').forEach { addPathSegment(it) } }.build()
    fun download(path: String, target: File, cancelled: ()->Boolean = {Thread.currentThread().isInterrupted}) {
        client.newCall(Request.Builder().url(fileUrl(path)).build()).execute().use { r ->
            require(r.isSuccessful) { "Download failed (HTTP ${r.code})." }
            val body=r.body ?: throw ApiFailure("Empty download.")
            require(body.contentLength()<=GcodePreview.MAX_BYTES) { "File exceeds 256 MiB." }
            body.byteStream().use { copyBounded(it,target,cancelled) }
        }
    }
    override fun close() {client.dispatcher.cancelAll();client.connectionPool.evictAll()}
}

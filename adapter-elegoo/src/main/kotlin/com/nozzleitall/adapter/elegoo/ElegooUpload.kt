package com.nozzleitall.adapter.elegoo

import com.nozzleitall.printer.UploadProgress
import com.nozzleitall.printer.UploadResult
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.ConnectException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The two printers' chunked HTTP uploads. Both send the file in 1 MiB pieces with the whole file's MD5 so the printer can
 * check what it received. Never retried: a failed piece ends the upload.
 *
 * Centauri Carbon (SDCP): elegoo-link src/lan/adapters/elegoo_fdm_cc/elegoo_fdm_cc_http_transfer.cpp doUpload /
 * uploadChunkWithSession, and the SDCP V3 document's "Send File Interface": POST multipart/form-data to /uploadFile/upload
 * with Check=1, S-File-MD5, Offset, Uuid (the same for every piece), TotalSize and File; success is {"code":"000000"},
 * a refusal carries messages[{field:"common_field", message:<code>}] (-1 offset error, -2 offset mismatch, -3 file open
 * failed, -4 unknown).
 *
 * Centauri Carbon 2: elegoo-link src/lan/adapters/elegoo_fdm_cc2/elegoo_fdm_cc2_http_transfer.cpp: PUT /upload with the
 * piece as the body and Content-Range "bytes start-end/total", X-File-Name, X-File-MD5 and X-Token (the access code,
 * "123456" when none is set); success is HTTP 200 with {"error_code":0}; 401/403 and error_code 1000 are a rejected
 * access code, 429 is busy.
 */
internal class ElegooUpload(private val base: HttpUrl) {
    companion object {
        /** "max 1MB per chunk" (both http_transfer.cpp files). */
        const val CHUNK_BYTES = 1024 * 1024
        private const val MAX_REPLY = 64 * 1024
        private val OCTET = "application/octet-stream".toMediaType()
    }

    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).writeTimeout(180, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain ->
            // Defence in depth: the upload goes to the printer's own host or nowhere.
            if (chain.request().url.host != base.host) throw IOException("Refusing a request to a host other than the printer.")
            chain.proceed(chain.request())
        }.build()

    private class Refused(message: String) : Exception(message)

    /** Centauri Carbon: multipart pieces to /uploadFile/upload. */
    fun sdcp(file: File, remoteName: String, progress: UploadProgress): UploadResult {
        val md5 = ElegooNet.md5Hex(file)
        // elegoo-link CryptoUtils::generateUUID: a random version-4 UUID in the usual 8-4-4-4-12 form.
        val uuid = UUID.randomUUID().toString()
        val url = base.newBuilder().encodedPath("/uploadFile/upload").build()
        return chunks(file, progress) { piece, offset, total ->
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("Check", "1").addFormDataPart("S-File-MD5", md5).addFormDataPart("Offset", offset.toString())
                .addFormDataPart("Uuid", uuid).addFormDataPart("TotalSize", total.toString())
                .addFormDataPart("File", remoteName, piece.toRequestBody(OCTET)).build()
            client.newCall(Request.Builder().url(url).header("Accept", "application/json").post(body).build()).execute().use { r ->
                if (!r.isSuccessful) throw Refused("The printer refused the file (HTTP ${r.code}).")
                val json = parse(r) ?: throw Refused("The printer's reply to the upload was not understood.")
                if (json.optString("code") == "000000") return@use
                val code = json.optJSONArray("messages")?.let { m -> (0 until m.length()).mapNotNull { m.optJSONObject(it) }.firstOrNull { it.optString("field") == "common_field" }?.opt("message") }
                throw Refused(when (code?.toString()) {
                    "-1", "-2" -> "The printer lost its place in the file."
                    "-3" -> "The printer couldn't open the file for writing."
                    null -> "The printer refused the file."
                    else -> "The printer refused the file (code $code)."
                })
            }
        }.let { it ?: UploadResult.Uploaded(remoteName) }
    }

    /** Centauri Carbon 2: raw pieces PUT to /upload with Content-Range. */
    fun cc2(file: File, remoteName: String, token: String, progress: UploadProgress): UploadResult {
        val md5 = ElegooNet.md5Hex(file)
        val url = base.newBuilder().encodedPath("/upload").build()
        return chunks(file, progress) { piece, offset, total ->
            val range = "bytes $offset-${offset + piece.size - 1}/$total"
            client.newCall(Request.Builder().url(url).header("Content-Range", range).header("X-File-Name", remoteName).header("X-File-MD5", md5)
                .header("X-Token", token).header("Accept", "application/json").put(piece.toRequestBody(OCTET)).build()).execute().use { r ->
                when (r.code) {
                    200 -> {}
                    401, 403 -> throw Refused("The printer rejected the access code.")
                    429 -> throw Refused("The printer is busy.")
                    else -> throw Refused("The printer refused the file (HTTP ${r.code}).")
                }
                val json = parse(r) ?: throw Refused("The printer's reply to the upload was not understood.")
                when (val code = (json.opt("error_code") as? Number)?.toInt()) {
                    0 -> {}
                    1000 -> throw Refused("The printer rejected the access code.")
                    null -> throw Refused("The printer's reply to the upload was not understood.")
                    else -> throw Refused(Cc2.errorReason(code))
                }
            }
        }.let { it ?: UploadResult.Uploaded(remoteName) }
    }

    /**
     * Sends the file piece by piece. Null when every piece was accepted. A refusal of the first piece, or a connection
     * that never opened, is Failed (nothing is on the printer); anything after data may have arrived is Interrupted.
     */
    private fun chunks(file: File, progress: UploadProgress, send: (ByteArray, Long, Long) -> Unit): UploadResult? {
        if (!file.isFile) return UploadResult.Failed("The file to upload does not exist.")
        val total = file.length()
        if (total <= 0) return UploadResult.Failed("The file to upload is empty.")
        RandomAccessFile(file, "r").use { raf ->
            var offset = 0L
            while (offset < total) {
                val piece = ByteArray(minOf(CHUNK_BYTES.toLong(), total - offset).toInt())
                raf.seek(offset); raf.readFully(piece)
                try { send(piece, offset, total) }
                catch (e: Refused) { return if (offset == 0L) UploadResult.Failed(e.message!!) else UploadResult.Interrupted("${e.message} The file on the printer is incomplete.") }
                catch (e: ConnectException) { return if (offset == 0L) UploadResult.Failed("Couldn't reach the printer to send the file.") else UploadResult.Interrupted("The connection to the printer was lost.") }
                catch (e: IOException) { return UploadResult.Interrupted(e.message ?: "The connection to the printer was lost.") }
                offset += piece.size
                progress.onProgress(offset, total)
            }
        }
        return null
    }

    private fun parse(r: Response): JSONObject? {
        val source = r.body?.source() ?: return null
        source.request(MAX_REPLY.toLong() + 1)
        val bytes = source.buffer.readByteArray(minOf(source.buffer.size, MAX_REPLY.toLong() + 1))
        if (bytes.size > MAX_REPLY) return null
        return try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (e: Exception) { null }
    }
}

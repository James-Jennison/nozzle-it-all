package com.nozzleitall.adapter.paxx

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class PrinterRejected(message: String) : IOException(message)

/**
 * A minimal Moonraker client for one printer on the LAN (or reached over the user's private network).
 * Rules: no automatic retries, no redirects, bounded response sizes, and every request goes to the printer's own host.
 */
class MoonrakerLan(address: String, private val apiKey: String = "", eventListener: EventListener? = null) : AutoCloseable {
    val base: HttpUrl = parse(address)

    private val client = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .apply { eventListener?.let { eventListener(it) } }
        .addInterceptor { chain ->
            val req = chain.request()
            // Defence in depth: a request for any other host is a bug, never a feature.
            if (req.url.host != base.host) throw IOException("Refusing a request to a host other than the printer.")
            chain.proceed(req.newBuilder().apply { if (apiKey.isNotBlank()) header("X-Api-Key", apiKey.trim()) }.build())
        }.build()
    // Commands block until Klipper finishes them (homing, heating waits); Moonraker itself gives up after about 60 s.
    private val commandClient = client.newBuilder().readTimeout(65, TimeUnit.SECONDS).callTimeout(70, TimeUnit.SECONDS).build()
    private val uploadClient = client.newBuilder().writeTimeout(60, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()

    companion object {
        fun isPrivateHost(host: String): Boolean {
            val h = host.lowercase().trim('[', ']')
            val p = h.split('.').mapNotNull { it.toIntOrNull() }
            val v4 = h.count { it == '.' } == 3 && p.size == 4 && p.all { it in 0..255 }
            val privateV4 = v4 && (p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 100 && p[1] in 64..127) || (p[0] == 169 && p[1] == 254))
            val bare = h.isNotEmpty() && '.' !in h && ':' !in h
            return privateV4 || bare || h == "localhost" || h == "::1" || h.startsWith("fd") || h.startsWith("fe80:") || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".ts.net")
        }
        fun parse(address: String): HttpUrl {
            val trimmed = address.trim().let { if ("://" in it) it else "http://$it" }
            val url = trimmed.toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter the printer's address, for example 192.168.1.40.")
            if (url.scheme == "http") require(isPrivateHost(url.host)) { "Plain HTTP is only allowed to a local or private-network address. Use HTTPS for anything else." }
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "Use a plain address without credentials, query or fragment." }
            return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build()
        }
    }

    private fun url(path: String, args: Map<String, String> = emptyMap()) = base.resolve(path)!!.newBuilder().apply { args.forEach { (k, v) -> addQueryParameter(k, v) } }.build()

    private fun readBody(response: Response, max: Int): ByteArray {
        val source = response.body?.source() ?: throw IOException("Empty reply from the printer.")
        source.request(max.toLong() + 1)
        return source.buffer.readByteArray().also { if (it.size > max) throw IOException("The printer's reply was too large.") }
    }

    private fun envelope(response: Response): Any {
        if (response.code == 401 || response.code == 403) throw PrinterRejected(if (apiKey.isBlank()) "The printer requires an API key." else "The printer rejected the API key.")
        val raw = readBody(response, 4_000_000)
        val json = try { JSONObject(String(raw, Charsets.UTF_8)) } catch (e: Exception) { throw IOException("The printer's reply was not understood (HTTP ${response.code}).") }
        json.optJSONObject("error")?.let { throw PrinterRejected(it.optString("message", "The printer rejected the request.").take(300)) }
        if (!response.isSuccessful) throw PrinterRejected("The printer refused the request (HTTP ${response.code}).")
        return json.opt("result") ?: throw IOException("The printer's reply had no result.")
    }

    fun get(path: String, args: Map<String, String> = emptyMap()): Any =
        client.newCall(Request.Builder().url(url(path, args)).build()).execute().use(::envelope)

    fun post(path: String, args: Map<String, String> = emptyMap(), body: JSONObject? = null): Any =
        commandClient.newCall(Request.Builder().url(url(path, args)).post((body?.toString() ?: "").toRequestBody("application/json".toMediaType())).build()).execute().use(::envelope)

    fun gcode(script: String): Any = post("printer/gcode/script", mapOf("script" to script))

    fun bytes(pathOrUrl: String, max: Int): ByteArray {
        val target = base.resolve(pathOrUrl) ?: throw IOException("Invalid address.")
        if (target.host != base.host) throw IOException("Camera must be on the printer's own host.")
        client.newCall(Request.Builder().url(target).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Camera unavailable (HTTP ${r.code}).")
            return readBody(r, max)
        }
    }

    /** Multipart upload to the gcodes root. Throws [UploadInterrupted] if the connection dropped after bytes were sent. */
    fun upload(file: File, remoteName: String, progress: (Long, Long) -> Unit): String {
        val total = file.length()
        var sent = 0L
        val fileBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = total
            override fun writeTo(sink: BufferedSink) {
                file.inputStream().source().use { src ->
                    val buffer = okio.Buffer()
                    while (true) {
                        val n = src.read(buffer, 64 * 1024)
                        if (n < 0) break
                        sink.write(buffer, n); sent += n; progress(sent, total)
                    }
                }
            }
        }
        val parent = remoteName.substringBeforeLast('/', "")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("root", "gcodes")
            .apply { if (parent.isNotEmpty()) addFormDataPart("path", parent) }
            .addFormDataPart("file", remoteName.substringAfterLast('/'), fileBody).build()
        try {
            uploadClient.newCall(Request.Builder().url(url("server/files/upload")).post(body).build()).execute().use { r ->
                if (r.code == 401 || r.code == 403) throw PrinterRejected("The printer rejected the API key.")
                if (!r.isSuccessful) throw PrinterRejected("The printer refused the upload (HTTP ${r.code}).")
                val json = JSONObject(String(readBody(r, 1_000_000), Charsets.UTF_8))
                val item = (json.optJSONObject("result") ?: json).optJSONObject("item") ?: throw UploadInterrupted("The printer did not confirm the upload.")
                return item.optString("path").ifBlank { throw UploadInterrupted("The printer did not confirm the uploaded file.") }
            }
        } catch (e: PrinterRejected) { throw e }
        catch (e: IOException) { if (sent > 0) throw UploadInterrupted(e.message ?: "The connection dropped during the upload.") else throw e }
    }

    override fun close() {
        listOf(client, commandClient, uploadClient).forEach { it.dispatcher.cancelAll(); it.connectionPool.evictAll() }
    }
}

class UploadInterrupted(message: String) : IOException(message)

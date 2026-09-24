package net.jamesjennison.klippercompanion

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for MyMiniFactory API v2. Browsing uses the developer API key (`key` query parameter, per the OpenAPI file);
 * file downloads need an OAuth access token ("download_url is available ONLY with an OAuth connected user").
 * Safety rules: https only, bounded JSON responses, the API key never appears in an error message, and the OAuth token
 * is sent only to myminifactory.com hosts - never to a CDN or any host a redirect leads to.
 */
class MyMiniFactoryClient(
    private val apiKey: String,
    private val base: HttpUrl = PRODUCTION_BASE,
    http: OkHttpClient? = null,
    private val allowInsecureHttpForTests: Boolean = false,
) : MmfApi {
    companion object {
        val PRODUCTION_BASE: HttpUrl = "https://www.myminifactory.com/api/v2/".toHttpUrl()
        const val MAX_JSON_BYTES = 4L * 1024 * 1024
        const val MAX_DOWNLOAD_BYTES = 256L * 1024 * 1024
        private const val MAX_REDIRECTS = 3
        fun isMyMiniFactoryHost(host: String) = host == "myminifactory.com" || host.endsWith(".myminifactory.com")
    }

    init { require(apiKey.isNotBlank()) { "An API key is required." }; require(allowInsecureHttpForTests || base.scheme == "https") { "MyMiniFactory must be reached over https." } }

    private val client: OkHttpClient = (http ?: OkHttpClient()).newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()

    private fun url(path: String, params: Map<String, String?> = emptyMap()): HttpUrl {
        val b = base.newBuilder().addPathSegments(path.trimStart('/')).addQueryParameter("key", apiKey)
        params.forEach { (k, v) -> if (!v.isNullOrEmpty()) b.addQueryParameter(k, v) }
        return b.build()
    }

    private fun failure(response: Response, needsSignInOn401: Boolean = false): MmfException = when (response.code) {
        401, 403 -> MmfException.Unauthorized(needsSignInOn401)
        404 -> MmfException.NotFound()
        429 -> MmfException.RateLimited(response.header("Retry-After")?.toIntOrNull()?.coerceIn(1, 3600))
        else -> MmfException.Unavailable(response.code)
    }

    private fun getJson(url: HttpUrl, accessToken: String? = null): String = try {
        client.newCall(Request.Builder().url(url).header("Accept", "application/json").apply { if (!accessToken.isNullOrBlank()) header("Authorization", "Bearer $accessToken") }.build()).execute().use { r ->
            if (!r.isSuccessful) throw failure(r)
            val source = r.body?.source() ?: throw MmfException.Malformed("MyMiniFactory sent an empty response.")
            val buffer = okio.Buffer(); var total = 0L
            while (true) { val n = source.read(buffer, 8192); if (n < 0) break; total += n; if (total > MAX_JSON_BYTES) throw MmfException.Malformed("The MyMiniFactory response was too large.") }
            buffer.readUtf8()
        }
    } catch (e: MmfException) { throw e } catch (e: IOException) { throw MmfException.Offline(e) }

    private fun <T> parsed(f: () -> T): T = try { f() } catch (e: MmfParseException) { throw MmfException.Malformed(e.message ?: "Unreadable MyMiniFactory response.") }

    override fun search(request: MmfSearch): MmfPage<MmfObject> {
        val flag = { on: Boolean -> if (on) "1" else null }
        val json = getJson(url("search", mapOf(
            "q" to request.query.trim().take(200), "page" to request.page.coerceIn(1, 1000).toString(), "per_page" to request.perPage.coerceIn(1, 60).toString(),
            "sort" to request.sort.code, "cat" to request.category?.toString(), "remix" to flag(request.remixAllowed), "commercial_use" to flag(request.commercialUse), "support" to flag(request.supportFree), "store" to request.price.apiValue, "tech" to if (request.fdmOnly) "FDM" else null)))
        return parsed { MmfParser.parseSearch(json) }
    }

    override fun objectDetail(id: Long, accessToken: String?): MmfObject { require(id > 0); return parsed { MmfParser.parseObject(getJson(url("objects/$id"), accessToken)) } }
    override fun objectFiles(id: Long, accessToken: String?): MmfPage<MmfFile> { require(id > 0); return parsed { MmfParser.parseFiles(getJson(url("objects/$id/files", mapOf("per_page" to "100")), accessToken)) } }

    override fun download(file: MmfFile, accessToken: String, target: File, onProgress: (Long, Long?) -> Unit): Long {
        if (accessToken.isBlank()) throw MmfException.Unauthorized(needsSignIn = true)
        var current = (file.downloadUrl ?: throw MmfException.UnsafeDownload("MyMiniFactory did not provide a download link for ${file.filename} (sign in, or the file needs a purchase).")).let(::checkedUrl)
        val partial = File(target.parentFile, target.name + ".part").also { it.delete() }
        try {
            for (hop in 0..MAX_REDIRECTS) {
                val builder = Request.Builder().url(current)
                // The token only ever goes to MyMiniFactory itself; a CDN or redirect target gets a plain request.
                if (isMyMiniFactoryHost(current.host)) builder.header("Authorization", "Bearer $accessToken")
                val written = client.newCall(builder.build()).execute().use { r ->
                    when {
                        r.code in 301..308 -> { current = checkedUrl(r.header("Location")?.let { current.resolve(it)?.toString() } ?: throw MmfException.UnsafeDownload("Bad redirect from MyMiniFactory.")); -1L }
                        !r.isSuccessful -> throw failure(r, needsSignInOn401 = true)
                        else -> {
                            val body = r.body ?: throw MmfException.Malformed("Empty download.")
                            val declared = body.contentLength().takeIf { it >= 0 }
                            if (declared != null && declared > MAX_DOWNLOAD_BYTES) throw MmfException.UnsafeDownload("That file is larger than the ${MAX_DOWNLOAD_BYTES / 1024 / 1024} MB limit.")
                            var total = 0L
                            partial.outputStream().use { out -> body.byteStream().use { input ->
                                val buf = ByteArray(32768)
                                while (true) {
                                    val n = input.read(buf); if (n < 0) break
                                    total += n; if (total > MAX_DOWNLOAD_BYTES) throw MmfException.UnsafeDownload("That file is larger than the ${MAX_DOWNLOAD_BYTES / 1024 / 1024} MB limit.")
                                    out.write(buf, 0, n); onProgress(total, declared)
                                }
                            } }
                            total
                        }
                    }
                }
                if (written >= 0) {
                    if (written == 0L) throw MmfException.Malformed("The downloaded file was empty.")
                    target.delete(); if (!partial.renameTo(target)) throw MmfException.Malformed("Could not save the downloaded file.")
                    return written
                }
            }
            throw MmfException.UnsafeDownload("Too many redirects from MyMiniFactory.")
        } catch (e: MmfException) { partial.delete(); throw e } catch (e: IOException) { partial.delete(); throw MmfException.Offline(e) }
    }

    private fun checkedUrl(raw: String): HttpUrl {
        val u = raw.toHttpUrlOrNullSafe() ?: throw MmfException.UnsafeDownload("Unusable download link.")
        if (!allowInsecureHttpForTests && u.scheme != "https") throw MmfException.UnsafeDownload("Downloads must use https.")
        if (u.username.isNotEmpty() || u.password.isNotEmpty()) throw MmfException.UnsafeDownload("Download link contains credentials.")
        return u
    }
    private fun String.toHttpUrlOrNullSafe(): HttpUrl? = try { toHttpUrl() } catch (_: IllegalArgumentException) { null }
}

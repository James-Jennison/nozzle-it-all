package net.jamesjennison.klippercompanion

import org.json.JSONArray
import org.json.JSONObject

// Phase 10 (Discover): models and strict parsing for MyMiniFactory's public API v2 (documented in the
// MyMiniFactory/api-documentation repository's OpenAPI file). Everything from the network is untrusted: URLs must be
// https, text is stripped of control characters and length-capped, and numbers are bounds-checked. The description_html /
// printing_details_html fields are deliberately ignored - nothing from the API is ever rendered as HTML.

/** The license terms the API reports per object. Each is a yes/no statement by the designer. */
enum class MmfLicenseTerm(val code: String) {
    MENTION("mention"), REMIX("remix"), COMMERCIAL_USE("commercial-use"), EXCLUSIVITY("exclusivity"), SHARE("share"), STORE("store");
    companion object { fun fromCode(code: String) = entries.firstOrNull { it.code == code } }
}

/** What each reported term means for the owner, in plain words. Unreported terms are never guessed. */
data class MmfLicense(val terms: Map<MmfLicenseTerm, Boolean>) {
    val creditRequired get() = terms[MmfLicenseTerm.MENTION] == true
    val isPaid get() = terms[MmfLicenseTerm.STORE] == true
    fun statements(): List<String> = buildList {
        if (terms[MmfLicenseTerm.MENTION] == true) add("Credit the designer when you share or publish.")
        terms[MmfLicenseTerm.REMIX]?.let { add(if (it) "Remixing is allowed." else "Remixing is not allowed.") }
        terms[MmfLicenseTerm.COMMERCIAL_USE]?.let { add(if (it) "Commercial use is allowed." else "No commercial use.") }
        terms[MmfLicenseTerm.SHARE]?.let { add(if (it) "Sharing is allowed." else "Do not redistribute the files.") }
        if (terms[MmfLicenseTerm.EXCLUSIVITY] == true) add("Shared exclusively on MyMiniFactory - do not post the files elsewhere.")
        if (terms[MmfLicenseTerm.STORE] == true) add("Paid model: the designer sells this under a store license.")
        if (terms.isEmpty()) add("The API reported no license details - check the model page before using it.")
    }
}

data class MmfDesigner(val username: String, val name: String, val profileUrl: String?, val avatarUrl: String?)
data class MmfImage(val thumbnailUrl: String?, val standardUrl: String?, val originalUrl: String?, val primary: Boolean)
data class MmfFile(val id: Long, val filename: String, val sizeBytes: Long?, val downloadUrl: String?, val viewerUrl: String?, val thumbnailUrl: String?) {
    val extension get() = filename.substringAfterLast('.', "").lowercase()
    val isModel get() = extension in MMF_MODEL_EXTENSIONS
    val isArchive get() = extension == "zip"
}
val MMF_MODEL_EXTENSIONS = setOf("stl", "3mf", "obj")

data class MmfObject(
    val id: Long, val name: String, val url: String?, val description: String, val printingDetails: String,
    val designer: MmfDesigner?, val images: List<MmfImage>, val files: List<MmfFile>, val tags: List<String>, val license: MmfLicense,
    val likes: Int, val views: Int, val dimensions: String, val complexity: Int?, val publishedAt: String?, val featured: Boolean,
    val archiveDownloadUrl: String?,
) {
    /** True when the name or tags point at resin printing (resin, SLA, DLP, MSLA, LCD, pre-supported): hidden by "FDM only" even if also tagged FDM. */
    fun mentionsResin(): Boolean = RESIN_WORDS.containsMatchIn(name) || tags.any { RESIN_WORDS.containsMatchIn(it) }
    val coverThumbnail get() = (images.firstOrNull { it.primary } ?: images.firstOrNull())?.let { it.thumbnailUrl ?: it.standardUrl }
    /** The attribution line to show and to store with a project: designer, source and link, per MyMiniFactory's guidelines. */
    fun attribution(): String = "\"$name\" by ${designer?.name?.ifBlank { null } ?: designer?.username ?: "an unknown designer"} on MyMiniFactory" + (url?.let { " - $it" } ?: "")
}

private val RESIN_WORDS = Regex("\\b(resin|sla|dlp|msla|lcd|pre-?supported)\\b", RegexOption.IGNORE_CASE)

data class MmfPage<T>(val totalCount: Int, val items: List<T>)

class MmfParseException(message: String) : Exception(message)

object MmfParser {
    private const val MAX_TEXT = 4000
    private const val MAX_ITEMS = 200
    private val CONTROL = Regex("[\\p{Cntrl}&&[^\\n\\t]]")

    internal fun text(o: JSONObject, key: String, max: Int = MAX_TEXT): String = if (o.isNull(key)) "" else CONTROL.replace(o.optString(key, ""), "").trim().take(max)
    /** Only https URLs are accepted (no credentials in them); anything else is dropped rather than trusted. */
    internal fun httpsUrl(o: JSONObject, key: String): String? {
        val v = text(o, key, 2048); if (v.isEmpty()) return null
        val uri = runCatching { java.net.URI(v) }.getOrNull() ?: return null
        return v.takeIf { uri.scheme == "https" && uri.userInfo == null && !uri.host.isNullOrEmpty() }
    }
    private fun long(o: JSONObject, key: String): Long? = if (!o.has(key) || o.isNull(key)) null else when (val v = o.get(key)) { is Number -> v.toLong(); is String -> v.trim().toLongOrNull(); else -> null }
    private fun int(o: JSONObject, key: String) = long(o, key)?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0

    private fun parseImage(o: JSONObject): MmfImage {
        fun url(size: String) = o.optJSONObject(size)?.let { httpsUrl(it, "url") }
        return MmfImage(url("thumbnail"), url("standard"), url("original"), o.optBoolean("is_primary", false))
    }

    fun parseFile(o: JSONObject): MmfFile? {
        val id = long(o, "id") ?: return null
        val name = text(o, "filename", 255).replace('/', '_').replace('\\', '_').ifBlank { return null }
        return MmfFile(id, name, long(o, "size")?.takeIf { it >= 0 }, httpsUrl(o, "download_url"), httpsUrl(o, "viewer_url"), httpsUrl(o, "thumbnail_url"))
    }

    private fun parseDesigner(o: JSONObject?): MmfDesigner? {
        if (o == null) return null
        val username = text(o, "username", 80); if (username.isEmpty()) return null
        return MmfDesigner(username, text(o, "name", 120), httpsUrl(o, "profile_url"), httpsUrl(o, "avatar_thumbnail_url") ?: httpsUrl(o, "avatar_url"))
    }

    private fun parseLicense(o: JSONObject): MmfLicense {
        val terms = LinkedHashMap<MmfLicenseTerm, Boolean>()
        o.optJSONArray("licenses")?.let { arr -> for (i in 0 until arr.length().coerceAtMost(20)) arr.optJSONObject(i)?.let { l ->
            MmfLicenseTerm.fromCode(text(l, "type", 40))?.let { term -> if (l.has("value") && !l.isNull("value")) terms[term] = l.optBoolean("value") }
        } }
        return MmfLicense(terms)
    }

    fun parseObject(o: JSONObject): MmfObject? {
        val id = long(o, "id") ?: return null
        val name = text(o, "name", 200).ifBlank { return null }
        // Search results include models the site has since deleted (seen live: the top "dragon" hit); only approved ones are usable.
        text(o, "status_name", 40).let { if (it.isNotEmpty() && it != "approved") return null }
        // The live API returns "files" as {"total_count":n,"items":[...]} (the OpenAPI file says array); accept both.
        fun <T> list(key: String, f: (JSONObject) -> T?): List<T> {
            val a = o.optJSONArray(key) ?: o.optJSONObject(key)?.optJSONArray("items") ?: return emptyList()
            return (0 until a.length().coerceAtMost(MAX_ITEMS)).mapNotNull { a.optJSONObject(it)?.let(f) }
        }
        val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length().coerceAtMost(50)).mapNotNull { a.optString(it, "").let { t -> CONTROL.replace(t, "").trim().take(40).ifEmpty { null } } } } ?: emptyList()
        return MmfObject(id, name, httpsUrl(o, "url"), text(o, "description"), text(o, "printing_details"), parseDesigner(o.optJSONObject("designer")),
            list("images", ::parseImage), list("files", ::parseFile), tags, parseLicense(o), int(o, "likes"), int(o, "views"), text(o, "dimensions", 120),
            long(o, "complexity")?.toInt(), text(o, "published_at", 40).ifEmpty { null }, o.optBoolean("featured", false), httpsUrl(o, "archive_download_url"))
    }

    private fun root(json: String): JSONObject = try { JSONObject(json) } catch (e: org.json.JSONException) { throw MmfParseException("The MyMiniFactory response was not valid JSON.") }

    fun parseSearch(json: String): MmfPage<MmfObject> {
        val r = root(json); val items = r.optJSONArray("items") ?: throw MmfParseException("The MyMiniFactory response had no items list.")
        val parsed = (0 until items.length().coerceAtMost(MAX_ITEMS)).mapNotNull { items.optJSONObject(it)?.let(::parseObject) }
        return MmfPage(int(r, "total_count").coerceAtLeast(parsed.size), parsed)
    }

    fun parseObject(json: String): MmfObject = parseObject(root(json)) ?: throw MmfParseException("The MyMiniFactory object was missing its id or name.")

    fun parseFiles(json: String): MmfPage<MmfFile> {
        val r = root(json); val items = r.optJSONArray("items") ?: throw MmfParseException("The MyMiniFactory response had no files list.")
        val parsed = (0 until items.length().coerceAtMost(MAX_ITEMS)).mapNotNull { items.optJSONObject(it)?.let(::parseFile) }
        return MmfPage(int(r, "total_count").coerceAtLeast(parsed.size), parsed)
    }
}

enum class MmfSort(val code: String, val label: String) { POPULARITY("popularity", "Popular"), DATE("date", "Newest"), VISITS("visits", "Most viewed") }

/** Price filter, mapped to the API's `store` parameter (0 = no store license = free, 1 = paid store license). */
enum class MmfPrice(val apiValue: String?, val label: String) { ANY(null, "All"), FREE("0", "Free"), PAID("1", "Paid") }

data class MmfSearch(
    val query: String = "", val page: Int = 1, val perPage: Int = 30, val sort: MmfSort = MmfSort.POPULARITY,
    val remixAllowed: Boolean = false, val commercialUse: Boolean = false, val supportFree: Boolean = false, val category: Int? = null,
    val price: MmfPrice = MmfPrice.ANY,
    /** Only models the designer tagged FDM (the API's `tech=FDM`); untagged models are not matched. */
    val fdmOnly: Boolean = false,
)

/** Failures the UI can explain in plain words. */
sealed class MmfException(message: String) : Exception(message) {
    class NotConfigured : MmfException("MyMiniFactory is not set up yet: add an API key in Discover settings.")
    class Unauthorized(val needsSignIn: Boolean) : MmfException(if (needsSignIn) "Sign in to MyMiniFactory to download files." else "MyMiniFactory rejected the API key.")
    class NotFound : MmfException("MyMiniFactory could not find that model.")
    class RateLimited(val retryAfterSeconds: Int?) : MmfException("MyMiniFactory is rate-limiting requests" + (retryAfterSeconds?.let { " - try again in ${it}s." } ?: " - try again shortly."))
    class Unavailable(code: Int) : MmfException("MyMiniFactory had a problem (HTTP $code). Try again later.")
    class Offline(cause: Throwable) : MmfException("Could not reach MyMiniFactory: ${cause.message ?: cause.javaClass.simpleName}")
    class Malformed(message: String) : MmfException(message)
    class UnsafeDownload(message: String) : MmfException(message)
}

/** The Discover screen's view of MyMiniFactory; the real client lives in :transport, tests use fakes. */
interface MmfApi {
    fun search(request: MmfSearch): MmfPage<MmfObject>
    fun objectDetail(id: Long): MmfObject
    fun objectFiles(id: Long): MmfPage<MmfFile>
    /** Downloads [file] to [target] (an OAuth [accessToken] is required by the API); returns the bytes written. */
    fun download(file: MmfFile, accessToken: String, target: java.io.File, onProgress: (Long, Long?) -> Unit = { _, _ -> }): Long
}

// ---- Sign-in (OAuth implicit grant + MyMiniFactory's "mobile login" exchange) ----

/** A signed-in session. [expiresAtMs] is wall-clock milliseconds. */
/** [refreshable] is true only for tokens obtained through the mobile-login exchange; a plain implicit-grant token cannot be refreshed. */
data class MmfSession(val accessToken: String, val expiresAtMs: Long, val refreshable: Boolean = true) {
    fun validAt(nowMs: Long, marginMs: Long = 60_000) = nowMs + marginMs < expiresAtMs
}

interface MmfTokenStore {
    fun load(): MmfSession?
    fun save(session: MmfSession)
    fun clear()
    /** A random, app-generated installation id (not a hardware identifier) sent as the mobile-login device id. */
    fun deviceId(): String
}

class InMemoryMmfTokenStore : MmfTokenStore {
    private var session: MmfSession? = null; private val id = java.util.UUID.randomUUID().toString()
    override fun load() = session
    override fun save(session: MmfSession) { this.session = session }
    override fun clear() { session = null }
    override fun deviceId() = id
}

object MmfAuthLinks {
    const val AUTHORIZE_URL = "https://auth.myminifactory.com/web/authorize"
    /** Registered with MyMiniFactory (its form expects an https callback). The page there relays the result to [APP_REDIRECT]. */
    const val REDIRECT_URI = "https://nozzleitall.com/mmf-auth"
    /** The app's own deep link, opened by the relay page; this is what parseRedirect accepts. */
    const val APP_REDIRECT = "nozzleitall://mmf-auth"
    /** MyMiniFactory offers 1 hour, 1 day or 1 week at the consent screen; anything up to 8 days is honoured. */
    const val MAX_TOKEN_SECONDS = 8 * 24 * 3600

    fun newState(): String = java.util.UUID.randomUUID().toString().replace("-", "")

    fun authorizeUrl(clientId: String, state: String, redirectUri: String = REDIRECT_URI): String {
        require(clientId.isNotBlank() && state.length >= 16)
        fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
        return "$AUTHORIZE_URL?client_id=${enc(clientId)}&redirect_uri=${enc(redirectUri)}&response_type=token&state=${enc(state)}"
    }

    open class SignInFailed(message: String) : Exception(message)

    /** Parses the relayed redirect (`nozzleitall://mmf-auth#access_token=...&expires_in=...&state=...`); rejects a wrong state or a denial. */
    fun parseRedirect(redirect: String, expectedState: String): Pair<String, Int> {
        val uri = try { java.net.URI(redirect) } catch (e: Exception) { throw SignInFailed("Unreadable sign-in response.") }
        if ("${uri.scheme}://${uri.host}" != APP_REDIRECT) throw SignInFailed("Unexpected sign-in redirect.")
        fun params(s: String?) = s.orEmpty().split('&').filter { it.contains('=') }.associate { p -> p.substringBefore('=') to java.net.URLDecoder.decode(p.substringAfter('=').replace("+", "%2B"), "UTF-8") }
        val p = params(uri.rawFragment) + params(uri.rawQuery)
        if (p["error"] != null) throw SignInFailed(if (p["error"] == "access_denied") "Sign-in was cancelled." else "MyMiniFactory refused the sign-in (${p["error"]!!.take(40)}).")
        if (p["state"] != expectedState) throw SignInFailed("The sign-in response did not match this request (possible forgery).")
        val token = p["access_token"]?.takeIf { it.length in 8..512 && it.all { c -> c.isLetterOrDigit() || c in "-._~+/=" } } ?: throw SignInFailed("MyMiniFactory did not return an access token.")
        return token to (p["expires_in"]?.toIntOrNull()?.coerceIn(1, MAX_TOKEN_SECONDS) ?: 600)
    }
}

/** Pulls printable models out of a downloaded .zip: names are flattened to a safe basename (no path can escape), only STL/3MF/OBJ are kept, sizes and counts are capped. */
object MmfArchive {
    const val MAX_MODELS = 30
    const val MAX_ENTRY_BYTES = 256L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    private const val MAX_SCANNED_ENTRIES = 2000
    private val SAFE = Regex("[^A-Za-z0-9._-]")

    fun extractModels(zip: java.io.File, dir: java.io.File): List<java.io.File> {
        dir.mkdirs()
        val out = ArrayList<java.io.File>(); var total = 0L; var scanned = 0
        try { java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val entry = z.nextEntry ?: break
                if (++scanned > MAX_SCANNED_ENTRIES) throw MmfException.UnsafeDownload("That archive has too many entries.")
                if (entry.isDirectory) continue
                val base = entry.name.substringAfterLast('/').substringAfterLast('\\')
                val ext = base.substringAfterLast('.', "").lowercase()
                if (ext !in MMF_MODEL_EXTENSIONS || base.startsWith(".") || base.startsWith("__")) continue // skips __MACOSX resource forks, dotfiles, readmes and images
                if (out.size >= MAX_MODELS) throw MmfException.UnsafeDownload("That archive holds more than $MAX_MODELS models - download files individually.")
                var name = SAFE.replace(base, "_"); var n = 1
                while (out.any { it.name == name }) name = SAFE.replace(base.substringBeforeLast('.'), "_") + "-${n++}." + ext
                val target = java.io.File(dir, name)
                var size = 0L
                target.outputStream().use { o ->
                    val buf = ByteArray(32768)
                    while (true) {
                        val r = z.read(buf); if (r < 0) break
                        size += r; total += r
                        if (size > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) { target.delete(); throw MmfException.UnsafeDownload("That archive is too large to unpack.") }
                        o.write(buf, 0, r)
                    }
                }
                if (size == 0L) { target.delete(); continue }
                out += target
            }
        } } catch (e: Throwable) { out.forEach { it.delete() }; throw e }
        return out
    }
}

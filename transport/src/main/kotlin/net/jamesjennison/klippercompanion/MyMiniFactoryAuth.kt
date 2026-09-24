package net.jamesjennison.klippercompanion

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class MmfDeviceInfo(val deviceId: String, val manufacturer: String, val model: String, val locale: String, val userAgent: String) {
    fun toJson(): String = JSONObject().put("device_id", deviceId).put("manufacturer", manufacturer).put("device_model", model).put("locale", locale).put("user_agent", userAgent).toString()
}

/**
 * The two calls MyMiniFactory documents for mobile clients: exchange the short-lived (10 min) implicit-grant token for a
 * 2-hour one ("mobile login"), and refresh it. [clientKey] is the developer client's public key, not a secret.
 */
class MobileLoginUnavailable : MmfAuthLinks.SignInFailed("MyMiniFactory's mobile login is not available for this client.")

class MyMiniFactoryOAuth(
    private val clientKey: String,
    private val authBase: HttpUrl = "https://auth.myminifactory.com/".toHttpUrl(),
    private val apiBase: HttpUrl = MyMiniFactoryClient.PRODUCTION_BASE,
    http: OkHttpClient? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val allowInsecureHttpForTests: Boolean = false,
) {
    init { require(clientKey.isNotBlank()); require(allowInsecureHttpForTests || authBase.scheme == "https") }
    private val client = (http ?: OkHttpClient()).newBuilder().followRedirects(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private fun post(path: String, form: FormBody): MmfSession {
        try {
            client.newCall(Request.Builder().url(authBase.newBuilder().addPathSegments(path).build()).post(form).build()).execute().use { r ->
                val body = r.body?.source()?.let { s -> s.request(64 * 1024); s.buffer.clone().readUtf8() }.orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                // Seen live: a client that is not registered as a mobile client (or a token the exchange does not accept)
                // gets HTTP 200 with the body `null`. That means "mobile login unavailable", not a broken sign-in.
                if (r.isSuccessful && (json == null || (!json.has("access_token") && !json.has("error")))) throw MobileLoginUnavailable()
                if (!r.isSuccessful || json == null || json.has("error")) throw MmfAuthLinks.SignInFailed(if (r.code in 400..403) "MyMiniFactory rejected the sign-in." else "MyMiniFactory sign-in failed (HTTP ${r.code}).")
                val token = json.optString("access_token", "").takeIf { it.length in 8..512 } ?: throw MmfAuthLinks.SignInFailed("MyMiniFactory did not return an access token.")
                return MmfSession(token, clock() + json.optLong("expires_in", 7200).coerceIn(1, 86_400) * 1000)
            }
        } catch (e: IOException) { throw MmfException.Offline(e) }
    }

    fun mobileLogin(implicitToken: String, device: MmfDeviceInfo): MmfSession =
        post("v1/oauth/mobile/login", FormBody.Builder().add("client_key", clientKey).add("access_token", implicitToken).add("device_info", device.toJson()).build())

    /** Confirms a token really works by asking MyMiniFactory who it belongs to (GET /user, Bearer). */
    fun validate(token: String) {
        try {
            client.newCall(Request.Builder().url(apiBase.newBuilder().addPathSegments("user").build()).header("Authorization", "Bearer $token").header("Accept", "application/json").build()).execute().use { r ->
                if (r.code == 401 || r.code == 403) throw MmfAuthLinks.SignInFailed("MyMiniFactory did not accept the sign-in token (HTTP ${r.code} from /user). Try signing in again.")
                if (!r.isSuccessful) throw MmfAuthLinks.SignInFailed("Could not confirm the sign-in (HTTP ${r.code}).")
            }
        } catch (e: IOException) { throw MmfException.Offline(e) }
    }

    fun implicitSession(token: String, expiresInSeconds: Int): MmfSession = MmfSession(token, clock() + expiresInSeconds.coerceIn(1, MmfAuthLinks.MAX_TOKEN_SECONDS) * 1000L, refreshable = false)

    fun refresh(session: MmfSession, deviceId: String): MmfSession =
        post("v1/oauth/mobile/refresh", FormBody.Builder().add("client_key", clientKey).add("access_token", session.accessToken).add("device_id", deviceId).build())
}

/** Owns the signed-in state: hands out a valid token (refreshing it when close to expiry) and forgets it on failure. */
open class MmfAuthManager(private val store: MmfTokenStore, private val oauth: MyMiniFactoryOAuth?, private val clock: () -> Long = System::currentTimeMillis) {
    /** True only for a stored session that has not expired (a refreshable one counts: it is renewed on demand). */
    open fun isSignedIn(): Boolean { val s = store.load() ?: return false; return s.refreshable || s.validAt(clock(), 0) }

    /** When the current session ends, or null when signed out. */
    fun sessionExpiresAtMs(): Long? = store.load()?.expiresAtMs

    /**
     * Turns the token from the sign-in redirect into a stored session. The two-hour mobile-login exchange is tried first; when
     * MyMiniFactory does not offer it for this client, the redirect's own token is used (it cannot be refreshed, so the owner
     * signs in again when it expires). Either way the token is confirmed with a real authenticated call before it is kept.
     */
    open fun completeSignIn(implicitToken: String, expiresInSeconds: Int, device: MmfDeviceInfo) {
        val oauth = oauth ?: throw MmfException.NotConfigured()
        val session = try { oauth.mobileLogin(implicitToken, device) } catch (e: MobileLoginUnavailable) { oauth.implicitSession(implicitToken, expiresInSeconds) }
        oauth.validate(session.accessToken)
        store.save(session)
    }

    /** A token good for at least a minute, or null when signed out (an expired session that cannot refresh signs out). */
    fun validAccessToken(): String? {
        val s = store.load() ?: return null
        if (s.validAt(clock())) return s.accessToken
        if (!s.refreshable) { store.clear(); return null } // an implicit token has no refresh: sign in again
        val oauth = oauth ?: return null
        return try { oauth.refresh(s, store.deviceId()).also(store::save).accessToken }
        catch (e: MmfAuthLinks.SignInFailed) { store.clear(); null }
        catch (e: MmfException.Offline) { null } // keep the session; the caller reports "offline"
    }

    fun signOut() = store.clear()
}

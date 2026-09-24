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
class MyMiniFactoryOAuth(
    private val clientKey: String,
    private val authBase: HttpUrl = "https://auth.myminifactory.com/".toHttpUrl(),
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
                if (!r.isSuccessful || json == null || json.has("error")) throw MmfAuthLinks.SignInFailed(if (r.code in 400..403) "MyMiniFactory rejected the sign-in." else "MyMiniFactory sign-in failed (HTTP ${r.code}).")
                val token = json.optString("access_token", "").takeIf { it.length in 8..512 } ?: throw MmfAuthLinks.SignInFailed("MyMiniFactory did not return an access token.")
                return MmfSession(token, clock() + json.optLong("expires_in", 7200).coerceIn(1, 86_400) * 1000)
            }
        } catch (e: IOException) { throw MmfException.Offline(e) }
    }

    fun mobileLogin(implicitToken: String, device: MmfDeviceInfo): MmfSession =
        post("v1/oauth/mobile/login", FormBody.Builder().add("client_key", clientKey).add("access_token", implicitToken).add("device_info", device.toJson()).build())

    fun refresh(session: MmfSession, deviceId: String): MmfSession =
        post("v1/oauth/mobile/refresh", FormBody.Builder().add("client_key", clientKey).add("access_token", session.accessToken).add("device_id", deviceId).build())
}

/** Owns the signed-in state: hands out a valid token (refreshing it when close to expiry) and forgets it on failure. */
class MmfAuthManager(private val store: MmfTokenStore, private val oauth: MyMiniFactoryOAuth?, private val clock: () -> Long = System::currentTimeMillis) {
    fun isSignedIn() = store.load() != null

    fun completeSignIn(implicitToken: String, device: MmfDeviceInfo) {
        val oauth = oauth ?: throw MmfException.NotConfigured()
        store.save(oauth.mobileLogin(implicitToken, device))
    }

    /** A token good for at least a minute, or null when signed out (an expired session that cannot refresh signs out). */
    fun validAccessToken(): String? {
        val s = store.load() ?: return null
        if (s.validAt(clock())) return s.accessToken
        val oauth = oauth ?: return null
        return try { oauth.refresh(s, store.deviceId()).also(store::save).accessToken }
        catch (e: MmfAuthLinks.SignInFailed) { store.clear(); null }
        catch (e: MmfException.Offline) { null } // keep the session; the caller reports "offline"
    }

    fun signOut() = store.clear()
}

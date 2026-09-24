package net.jamesjennison.klippercompanion

import android.content.Context
import android.content.SharedPreferences

/**
 * MyMiniFactory credentials and session, kept in the same encrypted store as printer credentials. The API key and client
 * key come from the user (Discover setup) or, if the owner supplied them at build time, from BuildConfig. Nothing here
 * is ever logged or shown after entry.
 */
class MmfSettings(private val prefs: SharedPreferences, private val buildApiKey: String = BuildConfig.MMF_API_KEY, private val buildClientKey: String = BuildConfig.MMF_CLIENT_KEY) : MmfTokenStore {
    constructor(context: Context) : this(CredentialStore.open(context.applicationContext))

    /** A key the user typed wins over the build-time one. */
    fun apiKey(): String? = (prefs.getString(KEY_API, null)?.takeIf { it.isNotBlank() } ?: buildApiKey.takeIf { it.isNotBlank() })
    fun clientKey(): String? = (prefs.getString(KEY_CLIENT, null)?.takeIf { it.isNotBlank() } ?: buildClientKey.takeIf { it.isNotBlank() })
    fun hasUserApiKey() = !prefs.getString(KEY_API, null).isNullOrBlank()

    fun saveApiKey(key: String) { prefs.edit().putString(KEY_API, sanitizeKey(key)).apply() }
    fun saveClientKey(key: String) { prefs.edit().putString(KEY_CLIENT, sanitizeKey(key)).apply() }
    fun clearCredentials() { prefs.edit().remove(KEY_API).remove(KEY_CLIENT).remove(KEY_TOKEN).remove(KEY_EXPIRES).apply() }

    override fun load(): MmfSession? {
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val expires = prefs.getLong(KEY_EXPIRES, 0L)
        return if (token.isNotBlank() && expires > 0) MmfSession(token, expires, prefs.getBoolean(KEY_REFRESHABLE, true)) else null
    }
    override fun save(session: MmfSession) { prefs.edit().putString(KEY_TOKEN, session.accessToken).putLong(KEY_EXPIRES, session.expiresAtMs).putBoolean(KEY_REFRESHABLE, session.refreshable).apply() }
    override fun clear() { prefs.edit().remove(KEY_TOKEN).remove(KEY_EXPIRES).apply() }
    override fun deviceId(): String = prefs.getString(KEY_DEVICE, null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE, it).apply() }

    /** The pending sign-in state (anti-forgery), remembered across the browser round trip. */
    fun beginSignIn(): String = MmfAuthLinks.newState().also { prefs.edit().putString(KEY_STATE, it).apply() }
    fun pendingState(): String? = prefs.getString(KEY_STATE, null)
    fun endSignIn() { prefs.edit().remove(KEY_STATE).apply() }

    companion object {
        private const val KEY_API = "mmf.apiKey"; private const val KEY_CLIENT = "mmf.clientKey"; private const val KEY_TOKEN = "mmf.token"
        private const val KEY_EXPIRES = "mmf.expires"; private const val KEY_DEVICE = "mmf.deviceId"; private const val KEY_STATE = "mmf.state"; private const val KEY_REFRESHABLE = "mmf.refreshable"
        /** Keys are pasted by hand: trim whitespace and refuse anything that could not be a key. */
        fun sanitizeKey(raw: String): String = raw.trim().takeIf { it.length in 8..256 && it.all { c -> c.isLetterOrDigit() || c in "-_." } } ?: throw IllegalArgumentException("That does not look like a MyMiniFactory key.")
        fun isPlausibleKey(raw: String) = runCatching { sanitizeKey(raw) }.isSuccess
    }
}

package com.nozzleitall.stocku1

import com.nozzleitall.printer.external.AccountAdapter
import com.nozzleitall.printer.external.AdapterAccount
import com.nozzleitall.printer.external.AdapterAccountState
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/**
 * The Snapmaker account used only by Stock U1 printers. Lives entirely inside this helper process: its token file is in
 * the helper's own data directory and is never read by the core.
 *
 * Sign-in uses the system browser. Snapmaker's sign-in page offers no redirect back to a local app, so after signing in
 * the user copies the token Snapmaker shows on its callback page (a JSON body containing "access_token") and pastes it
 * into Nozzle. The token is checked against the same account endpoint the Snapmaker Orca fork uses
 * (WebSMUserLoginDialog.cpp at 11bea5c981: GET /api/common/accounts/current with the raw token as Authorization).
 *
 * UNVERIFIED against the live Snapmaker service: exercised here only against a local stand-in (StockU1AdapterTest).
 */
class SnapmakerAccount(
    private val dataDir: File,
    private val accountHost: String = System.getenv("NOZZLE_STOCK_ACCOUNT_HOST") ?: "https://id.snapmaker.com",
    private val signInUrl: String = System.getenv("NOZZLE_STOCK_SIGNIN_URL") ?: "https://id.snapmaker.com?from=orca",
) : AccountAdapter {
    private val tokenFile = File(dataDir, "account.json")
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    @Volatile private var signingIn = false
    @Volatile private var expired = false

    fun token(): String? = runCatching { JSONObject(tokenFile.readText()).optString("token").takeIf { it.isNotBlank() } }.getOrNull()

    override fun account(): AdapterAccount = when {
        expired -> AdapterAccount(AdapterAccountState.EXPIRED, "Your Snapmaker sign-in has expired. Sign in again to use Stock U1 cloud features.", signInUrl)
        token() != null -> AdapterAccount(AdapterAccountState.SIGNED_IN, "Signed in to Snapmaker for Stock U1 printers.")
        signingIn -> AdapterAccount(AdapterAccountState.SIGNING_IN, "Finish signing in in your browser, then paste the token here.", signInUrl)
        else -> AdapterAccount(AdapterAccountState.SIGNED_OUT, "Only Stock U1 cloud features need a Snapmaker account. PAXX printers never do.", signInUrl)
    }

    override fun beginSignIn(): AdapterAccount { signingIn = true; return account() }

    /** Accepts the callback page's JSON ({"access_token": ...}) or a bare token. */
    override fun completeSignIn(response: String): AdapterAccount {
        val trimmed = response.trim()
        val token = runCatching { JSONObject(trimmed).let { it.optString("access_token").ifBlank { it.optJSONObject("data")?.optString("token").orEmpty() } } }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: trimmed.takeIf { it.isNotBlank() && it.length < 4096 && it.none(Char::isWhitespace) }
            ?: return AdapterAccount(AdapterAccountState.SIGNED_OUT, "That did not look like a Snapmaker sign-in token.", signInUrl)
        return when (val check = validate(token)) {
            Validation.Valid -> { save(token); signingIn = false; expired = false; account() }
            Validation.Rejected -> AdapterAccount(AdapterAccountState.SIGNED_OUT, "Snapmaker did not accept that token. Sign in again.", signInUrl)
            is Validation.Unreachable -> AdapterAccount(AdapterAccountState.UNAVAILABLE, "Snapmaker could not be reached (${check.reason}). Your PAXX printers are unaffected.", signInUrl)
        }
    }

    override fun signOut(): AdapterAccount { tokenFile.delete(); signingIn = false; expired = false; return account() }

    sealed class Validation { object Valid : Validation(); object Rejected : Validation(); data class Unreachable(val reason: String) : Validation() }

    fun validate(token: String): Validation = try {
        http.newCall(Request.Builder().url("$accountHost/api/common/accounts/current").header("Authorization", token).build()).execute().use { r ->
            when {
                r.code == 401 || r.code == 403 -> Validation.Rejected
                r.isSuccessful -> Validation.Valid
                else -> Validation.Unreachable("HTTP ${r.code}")
            }
        }
    } catch (e: IOException) { Validation.Unreachable(e.message ?: e.javaClass.simpleName) }

    /** Re-checks a saved token. A rejection marks it expired (only Stock features are affected); an outage changes nothing. */
    fun refresh(): AdapterAccount {
        val t = token() ?: return account()
        if (validate(t) == Validation.Rejected) expired = true
        return account()
    }

    private fun save(token: String) {
        dataDir.mkdirs()
        val tmp = File(dataDir, "account.json.tmp")
        tmp.writeText(JSONObject().put("token", token).put("savedAt", System.currentTimeMillis()).toString())
        runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        Files.move(tmp.toPath(), tokenFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

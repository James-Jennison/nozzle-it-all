package net.jamesjennison.klippercompanion

import android.content.SharedPreferences
import org.json.JSONObject

// Bespok3d bridge (Snapmaker U1/PAXX only, PrinterKind.SNAPMAKER_U1_PAXX): status/plugin-catalog
// reads and plugin installs against the paired Bespok3d daemon running on the printer itself
// (`Bespok3dClient.kt`, ported from Helix). Mirrors HeaterReader/FanReader: a small reader
// interface with default-throwing methods on PrinterService, implemented by Moonraker using the
// printer's own host. U1 SSH preflight/enrollment is deliberately NOT part of this interface —
// unlike heater/fan status it isn't a Moonraker-adjacent read, it's a raw SSH session to
// root@<host>:22 that needs its own password each call and (for enrollment) an Android Context
// to load the bundled package bundle — see Bespok3dU1EnrollmentService below.

/** A daemon the phone has already paired with (see Bespok3dClient.requestAccess). */


/**
 * SSH-based U1 probe/enrollment. Kept separate from Bespok3dReader/PrinterService: every call
 * needs its own freshly entered SSH password (never persisted, per this app's credential rules)
 * and enrollment needs an Android Context to load the signed bootstrap bundle - neither fits the
 * "address + saved API key" shape the rest of PrinterService is built around.
 */
internal class Bespok3dU1EnrollmentService(
    private val preflight: Bespok3dU1Preflight = Bespok3dU1Preflight(),
    private val enrollment: Bespok3dU1Enrollment = Bespok3dU1Enrollment(),
) {
    fun hostKey(host: String): String = preflight.hostKey(host)
    fun preflight(host: String, password: String, trustedHostKey: String): Bespok3dU1PreflightResult = preflight.run(host, password, trustedHostKey)
    fun enroll(config: Bespok3dU1EnrollmentConfig, bootstrap: Bespok3dBootstrapSet): Bespok3dU1EnrollmentResult =
        enrollment.run(config, bootstrap)
}

/** Persists the paired Bespok3d identity/token/certificate per printer, in the same
 * Keystore-encrypted store as Moonraker API keys (see CredentialStore) - this token grants
 * plugin-install access to the printer's Bespok3d daemon, so it gets the same protection. */
object Bespok3dConnectionStore {
    private fun key(address: String) = "bespok3d:$address"
    fun load(secrets: SharedPreferences, address: String): Bespok3dConnection? = try {
        val raw = secrets.getString(key(address), null) ?: return null
        val json = JSONObject(raw)
        Bespok3dConnection(json.getString("identity"), json.getString("token"), json.getString("certificatePem"))
    } catch (_: Exception) { null }
    fun save(secrets: SharedPreferences, address: String, connection: Bespok3dConnection) {
        val json = JSONObject().put("identity", connection.identity).put("token", connection.token).put("certificatePem", connection.certificatePem)
        secrets.edit().putString(key(address), json.toString()).apply()
    }
    fun clear(secrets: SharedPreferences, address: String) { secrets.edit().remove(key(address)).apply() }
}

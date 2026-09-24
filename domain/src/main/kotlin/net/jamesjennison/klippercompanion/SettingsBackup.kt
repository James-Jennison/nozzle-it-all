package net.jamesjennison.klippercompanion

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String) : Exception(message)

/**
 * A passphrase-encrypted backup of the saved printers, including their API keys and access codes (which is why it is never written in the
 * clear): PBKDF2-HMAC-SHA256 stretches the passphrase, AES-256-GCM encrypts and authenticates the JSON. A wrong passphrase or a damaged file
 * fails the authentication tag rather than producing garbage.
 */
object SettingsBackup {
    private const val VERSION = 1
    private const val ITERATIONS = 210_000
    private const val MAX_PRINTERS = 200
    const val MIN_PASSPHRASE = 8

    fun encode(profiles: List<PrinterProfile>, passphrase: CharArray): ByteArray {
        if (passphrase.size < MIN_PASSPHRASE) throw BackupException("Use a passphrase of at least $MIN_PASSPHRASE characters.")
        val payload = JSONObject().put("printers", JSONArray().apply { profiles.forEach { put(toJson(it)) } }).toString().toByteArray(Charsets.UTF_8)
        val random = SecureRandom(); val salt = ByteArray(16).also(random::nextBytes); val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv)) }
        val b64 = Base64.getEncoder()
        return JSONObject().put("app", "nozzle-it-all-backup").put("v", VERSION).put("iter", ITERATIONS)
            .put("salt", b64.encodeToString(salt)).put("iv", b64.encodeToString(iv)).put("data", b64.encodeToString(cipher.doFinal(payload))).toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray, passphrase: CharArray): List<PrinterProfile> {
        if (bytes.size > 2_000_000) throw BackupException("That file is too large to be a Nozzle It All backup.")
        val outer = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: Exception) { throw BackupException("That is not a Nozzle It All backup.") }
        if (outer.optString("app") != "nozzle-it-all-backup" || outer.optInt("v") != VERSION) throw BackupException("That is not a Nozzle It All backup, or it is from a newer version.")
        val iterations = outer.optInt("iter", ITERATIONS).also { if (it !in 10_000..2_000_000) throw BackupException("Unsupported backup.") }
        val plain = try {
            val d = Base64.getDecoder()
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(passphrase, d.decode(outer.getString("salt")), iterations), GCMParameterSpec(128, d.decode(outer.getString("iv")))) }.doFinal(d.decode(outer.getString("data")))
        } catch (_: Exception) { throw BackupException("Wrong passphrase, or the backup is damaged.") }
        val list = try { JSONObject(String(plain, Charsets.UTF_8)).getJSONArray("printers") } catch (_: Exception) { throw BackupException("The backup could not be read.") }
        if (list.length() > MAX_PRINTERS) throw BackupException("The backup lists too many printers.")
        return (0 until list.length()).mapNotNull { fromJson(list.optJSONObject(it)) }.distinctBy { it.address }
    }

    private fun key(passphrase: CharArray, salt: ByteArray, iterations: Int = ITERATIONS) =
        SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(passphrase, salt, iterations, 256)).encoded, "AES")

    private fun toJson(p: PrinterProfile) = JSONObject().put("address", p.address).put("name", p.name).put("favorite", p.favorite).put("cameraId", p.cameraId).put("apiKey", p.apiKey)
        .put("kind", p.kind.name).put("serial", p.serial).put("slicingModel", p.slicingModel?.name ?: "").put("firmware", p.declaredFirmwareVersion)

    private fun fromJson(o: JSONObject?): PrinterProfile? {
        o ?: return null
        val address = o.optString("address").trim().take(300).ifBlank { return null }
        return PrinterProfile(address, o.optString("name").take(80), o.optBoolean("favorite"), o.optString("cameraId").take(80), o.optString("apiKey").take(200),
            runCatching { PrinterKind.valueOf(o.optString("kind")) }.getOrDefault(PrinterKind.GENERIC_KLIPPER), o.optString("serial").take(40),
            o.optString("slicingModel").takeIf { it.isNotBlank() }?.let { runCatching { SlicingPrinterModel.valueOf(it) }.getOrNull() }, o.optString("firmware").take(80))
    }
}

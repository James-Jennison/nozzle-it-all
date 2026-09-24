package net.jamesjennison.klippercompanion

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Moonraker API keys grant full printer control, so they are stored separately from the
 * plaintext printer-profile preferences, behind Android Keystore-backed encryption. */
object CredentialStore {
    fun open(context: Context, fileName: String = "printer-credentials"): SharedPreferences {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(context, fileName, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
}

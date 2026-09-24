// Adapted from Helix (github.com/FatBoy721/Helix), AGPL-3.0-or-later.
// Original: android/app/src/main/java/org/crabcore/u1control/bambu/BambuTrust.kt
//
// Shared TLS trust for Bambu's LAN services. Both MQTT (8883) and the chamber
// camera (6000) present the same device certificate:
//
//   subject = CN=<serial number>
//   issuer  = C=CN, O=BBL Technologies Co., Ltd, CN=BBL CA
//
// Self-signed against a CA no device trusts, so ordinary verification cannot
// work. Other Bambu clients answer that by trusting everything on the socket.
// We accept the untrusted chain but require the leaf CN to be the serial the
// user configured, which needs no CA yet still binds the session to one
// specific machine rather than to whatever is answering on that port.
package net.jamesjennison.klippercompanion

import java.security.KeyStore
import java.security.Provider
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.ManagerFactoryParameters
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.TrustManagerFactorySpi
import javax.net.ssl.X509TrustManager

/** Marker text the connection layer looks for when classifying handshake failures. */
const val BAMBU_SERIAL_MISMATCH = "serial number does not match"

/** Marker text for a printer whose certificate is not the one first trusted for its serial number. */
const val BAMBU_CERT_CHANGED = "certificate has changed"

/**
 * Remembers, per printer serial number, the SHA-256 of the certificate first seen for it (trust on first use). The serial in the certificate's
 * CN is printed on the printer, so on its own it does not stop a device that copies it; the pin makes any later connection to a different
 * certificate fail until the owner forgets and re-adds the printer.
 */
interface BambuPinStore {
    fun get(serial: String): String?
    fun put(serial: String, sha256: String)
    fun forget(serial: String)
}

class InMemoryBambuPinStore : BambuPinStore {
    private val pins = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(serial: String) = pins[serial.uppercase()]
    override fun put(serial: String, sha256: String) { pins[serial.uppercase()] = sha256 }
    override fun forget(serial: String) { pins.remove(serial.uppercase()) }
}

object BambuCertPins {
    @Volatile var store: BambuPinStore = InMemoryBambuPinStore()
    fun fingerprint(leaf: X509Certificate): String = java.security.MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
}

class SerialPinningTrustManagerFactory(expectedSerial: String) : TrustManagerFactory(
    object : TrustManagerFactorySpi() {
        override fun engineInit(keyStore: KeyStore?) = Unit
        override fun engineInit(parameters: ManagerFactoryParameters?) = Unit
        override fun engineGetTrustManagers(): Array<TrustManager> =
            arrayOf(SerialPinningTrustManager(expectedSerial))
    },
    BambuSecurityProvider,
    "NozzleItAllBambuSerialPinning"
)

class SerialPinningTrustManager(private val expectedSerial: String) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("The printer presented no certificate")

        // RFC 2253 form, e.g. "CN=01P00C611300996". Android has no javax.naming,
        // so this is parsed directly rather than with LdapName.
        val commonName = CN_PATTERN.find(leaf.subjectX500Principal.name)?.groupValues?.get(1)?.trim()

        if (commonName.isNullOrEmpty() || !commonName.equals(expectedSerial, ignoreCase = true)) {
            throw CertificateException(
                "This printer's $BAMBU_SERIAL_MISMATCH " +
                    "(expected $expectedSerial, got ${commonName ?: "nothing"})"
            )
        }

        val fingerprint = BambuCertPins.fingerprint(leaf)
        val pinned = BambuCertPins.store.get(expectedSerial)
        when {
            pinned == null -> BambuCertPins.store.put(expectedSerial, fingerprint)
            !pinned.equals(fingerprint, ignoreCase = true) -> throw CertificateException(
                "This printer's $BAMBU_CERT_CHANGED since it was first trusted. If you replaced or reset the printer, forget it and add it again; " +
                    "otherwise something else on the network is answering as this printer."
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private companion object {
        val CN_PATTERN = Regex("CN=([^,]+)")
    }
}

/** Socket factory for the plain-TLS services; MQTT takes the factory instead. */
fun bambuSocketFactory(expectedSerial: String): SSLSocketFactory =
    SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(SerialPinningTrustManager(expectedSerial)), null)
    }.socketFactory

@Suppress("DEPRECATION") // The (String, String, String) constructor needs API 30; this app targets 26.
private object BambuSecurityProvider : Provider(
    "NozzleItAllBambu",
    1.0,
    "Serial-pinned trust for Bambu Lab printers on the LAN"
)

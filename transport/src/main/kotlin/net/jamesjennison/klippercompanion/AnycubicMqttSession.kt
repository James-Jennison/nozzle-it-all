// PrinterKind.ANYCUBIC_LAN's MQTT link: the same HiveMQ MQTT 3.1.1 client BambuMqttConnection uses, over TLS to the
// printer's own broker (port 9883), with the credentials the LAN handshake returned (AnycubicLan; P-0036).
//
// TLS. Both references turn verification off (anycubic_lan.py:475-476 and kobra_connect/client.py:199-204 both set
// CERT_NONE + tls_insecure_set). As for Bambu (BambuTrust.kt), this app does better without inventing anything about the
// certificate: the printer's chain is accepted unverified the first time, its leaf's SHA-256 is pinned per printer address
// (trust on first use, the same pin store as Bambu's, under an "ANYCUBIC:" key), and any later connection that presents a
// different certificate fails until the owner forgets and re-adds the printer. No CN rule is applied: neither reference
// says what the certificate names.
//
// Client certificate. DISAGREE: kobra_connect/client.py:189-203 (and its README "mutual-TLS") presents the handshake's
// `devicecrt` / `devicepk` as a client certificate; anycubic_lan.py:475 presents none (its doc reports a live Kobra S1
// working that way, docs/orcaslicer-plugin.md:209-218). Kept: offer it when the handshake returned one, since a TLS client
// only sends it if the broker asks, which satisfies both. A key this code can't read (only PKCS#8 and PKCS#1 RSA PEM are)
// means no client certificate, the primary's behaviour.
package net.jamesjennison.klippercompanion

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Provider
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.ManagerFactoryParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.TrustManagerFactorySpi
import javax.net.ssl.X509TrustManager

/** What one connection needs. Holds secrets: [toString] leaves them out, and nothing here is stored. */
class AnycubicMqttConfig(val host: String, val port: Int, val username: String, val password: String, val reportFilter: String,
                         val clientCertPem: String = "", val clientKeyPem: String = "") {
    override fun toString(): String = "AnycubicMqttConfig(host=$host, port=$port)"
}

/** The seam AnycubicLanPrinterService talks through; tests give it a fake, as BambuStatusProbe's tests do. */
interface AnycubicMqttSession {
    interface Listener {
        fun onMessage(topic: String, payload: String)
        fun onDisconnected(message: String?)
    }
    /** Connects and subscribes to [AnycubicMqttConfig.reportFilter]; completes once both are done. */
    fun connect(config: AnycubicMqttConfig): CompletableFuture<Void>
    fun publish(topic: String, payload: String): CompletableFuture<Void>
    fun close()
}

class LiveAnycubicMqttSession(private val listener: AnycubicMqttSession.Listener) : AnycubicMqttSession {
    @Volatile private var client: Mqtt3AsyncClient? = null
    @Volatile private var generation = 0L

    @Synchronized
    override fun connect(config: AnycubicMqttConfig): CompletableFuture<Void> {
        close()
        val session = ++generation
        val result = CompletableFuture<Void>()
        val mqtt = try {
            MqttClient.builder()
                .useMqttVersion3()
                // Any id works for the references (anycubic_lan.py:470 "orca_anycubic_<8 hex>", kobra_connect/client.py:196 "kobra-<8 hex>").
                .identifier("nozzle-" + UUID.randomUUID().toString().replace("-", "").take(8))
                .serverHost(config.host)
                .serverPort(config.port)
                .sslConfig()
                    .trustManagerFactory(AnycubicPinningTrustManagerFactory(config.host))
                    .hostnameVerifier(AnycubicHostnameVerifier(config.host))
                    .apply { anycubicClientKeyManagerFactory(config.clientCertPem, config.clientKeyPem)?.let { keyManagerFactory(it) } }
                    .applySslConfig()
                .addDisconnectedListener { context -> if (generation == session) listener.onDisconnected(context.cause.message) }
                .buildAsync()
        } catch (_: Exception) {
            result.completeExceptionally(ApiFailure("Could not set up the secure connection to the printer."))
            return result
        }
        client = mqtt
        mqtt.connectWith()
            .simpleAuth()
                .username(config.username)
                .password(config.password.toByteArray(StandardCharsets.UTF_8))
                .applySimpleAuth()
            .keepAlive(AnycubicLan.KEEP_ALIVE_SECONDS)
            .cleanSession(true)
            .send()
            .whenComplete { _, error ->
                if (generation != session || client !== mqtt) { result.completeExceptionally(ApiFailure("The connection to the printer was replaced.")); return@whenComplete }
                if (error != null) { close(); result.completeExceptionally(connectFailure(error)); return@whenComplete }
                // QoS 0, as both references subscribe (paho's default).
                mqtt.subscribeWith()
                    .topicFilter(config.reportFilter)
                    .qos(MqttQos.AT_MOST_ONCE)
                    .callback { message -> if (generation == session) listener.onMessage(message.topic.toString(), String(message.payloadAsBytes, StandardCharsets.UTF_8)) }
                    .send()
                    .whenComplete { _, subscribeError ->
                        if (subscribeError != null) { close(); result.completeExceptionally(ApiFailure("Could not subscribe to the printer's reports.")) }
                        else result.complete(null)
                    }
            }
        return result
    }

    @Synchronized
    override fun publish(topic: String, payload: String): CompletableFuture<Void> {
        val result = CompletableFuture<Void>()
        val mqtt = client ?: return result.apply { completeExceptionally(ApiFailure("Not connected to the printer.")) }
        // QoS 0, as both references publish (anycubic_lan.py:547; kobra_connect/client.py:310).
        mqtt.publishWith().topic(topic).qos(MqttQos.AT_MOST_ONCE).payload(payload.toByteArray(StandardCharsets.UTF_8)).send()
            .whenComplete { _, error -> if (error != null) result.completeExceptionally(ApiFailure("Could not send the command to the printer.")) else result.complete(null) }
        return result
    }

    @Synchronized
    override fun close() {
        generation++
        val existing = client
        client = null
        try { existing?.disconnect() } catch (_: Exception) { /* already gone */ }
    }

    private fun connectFailure(error: Throwable): ApiFailure {
        var cursor: Throwable? = error
        while (cursor != null) {
            if (cursor is CertificateException && cursor.message?.contains(ANYCUBIC_CERT_CHANGED) == true) return ApiFailure(cursor.message!!)
            cursor = cursor.cause
        }
        if (error.message?.contains("NOT_AUTHORIZED", ignoreCase = true) == true || error.message?.contains("BAD_USER_NAME_OR_PASSWORD", ignoreCase = true) == true)
            return ApiFailure("The printer refused the LAN credentials it handed out. Check LAN mode is on, then try again.")
        return ApiFailure("Could not reach the printer's MQTT broker: ${error.message ?: "connection failed"}")
    }
}

/** Marker text for a printer whose certificate is not the one first trusted at its address. */
const val ANYCUBIC_CERT_CHANGED = "certificate has changed"

/** The pin-store key for the Anycubic printer at [host] (the Bambu pin store is keyed by serial; these can't collide). */
fun anycubicPinKey(host: String): String = "ANYCUBIC:" + host.trim().removePrefix("[").removeSuffix("]").lowercase()

/** The pin-store key for a saved profile's address, or null when it isn't a valid address. */
fun anycubicPinKeyForAddress(address: String): String? =
    runCatching { anycubicPinKey(Moonraker.parseAddress(if ("://" in address) address else "http://$address/").host) }.getOrNull()

/** Accepts the printer's unverified chain but pins its leaf certificate per printer address (trust on first use). */
class AnycubicPinningTrustManager(private val host: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Client certificates are not accepted")

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("The printer presented no certificate")
        val key = anycubicPinKey(host)
        val fingerprint = BambuCertPins.fingerprint(leaf)
        val pinned = BambuCertPins.store.get(key)
        when {
            pinned == null -> BambuCertPins.store.put(key, fingerprint)
            !pinned.equals(fingerprint, ignoreCase = true) -> throw CertificateException(
                "This printer's $ANYCUBIC_CERT_CHANGED since it was first trusted. If you replaced or reset the printer, forget it and add it again; " +
                    "otherwise something else on the network is answering as this printer."
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

class AnycubicPinningTrustManagerFactory(host: String) : TrustManagerFactory(
    object : TrustManagerFactorySpi() {
        override fun engineInit(keyStore: KeyStore?) = Unit
        override fun engineInit(parameters: ManagerFactoryParameters?) = Unit
        override fun engineGetTrustManagers(): Array<TrustManager> = arrayOf(AnycubicPinningTrustManager(host))
    },
    AnycubicSecurityProvider,
    "NozzleItAllAnycubicPinning"
)

/** The certificate names no LAN address either reference relies on, so the session is accepted when it passes the pin. */
class AnycubicHostnameVerifier(private val host: String) : javax.net.ssl.HostnameVerifier {
    override fun verify(hostname: String?, session: javax.net.ssl.SSLSession?): Boolean {
        val chain = runCatching { session?.peerCertificates }.getOrNull()?.filterIsInstance<X509Certificate>()?.toTypedArray()
        if (chain.isNullOrEmpty()) return false
        return runCatching { AnycubicPinningTrustManager(host).checkServerTrusted(chain, "UNKNOWN") }.isSuccess
    }
}

@Suppress("DEPRECATION") // The (String, String, String) constructor needs API 30; this app targets 26.
private object AnycubicSecurityProvider : Provider("NozzleItAllAnycubic", 1.0, "Address-pinned trust for Anycubic printers on the LAN")

/**
 * The handshake's `devicecrt` / `devicepk` (PEM) as a client key manager (kobra_connect/client.py:189-203), or null when
 * either is missing or can't be read. PKCS#8 (`BEGIN PRIVATE KEY`) and PKCS#1 RSA (`BEGIN RSA PRIVATE KEY`) keys only.
 */
fun anycubicClientKeyManagerFactory(certPem: String, keyPem: String): KeyManagerFactory? {
    if (certPem.isBlank() || keyPem.isBlank()) return null
    return runCatching {
        val certs = CertificateFactory.getInstance("X.509").generateCertificates(certPem.byteInputStream()).filterIsInstance<X509Certificate>()
        val leaf = certs.firstOrNull() ?: return null
        val key = pemPrivateKey(keyPem, leaf.publicKey.algorithm)
        val password = UUID.randomUUID().toString().toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("device", key, password, certs.map { it as java.security.cert.Certificate }.toTypedArray())
        }
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
    }.getOrNull()
}

private fun pemPrivateKey(pem: String, algorithm: String): PrivateKey {
    val pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY")
    val der = Base64.getMimeDecoder().decode(pem.lineSequence().filterNot { it.trim().startsWith("-----") }.joinToString(""))
    val pkcs8 = if (!pkcs1) der else
        PrivateKeyInfo(AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE), org.bouncycastle.asn1.pkcs.RSAPrivateKey.getInstance(der)).getEncoded("DER")
    return KeyFactory.getInstance(if (pkcs1) "RSA" else algorithm).generatePrivate(PKCS8EncodedKeySpec(pkcs8))
}

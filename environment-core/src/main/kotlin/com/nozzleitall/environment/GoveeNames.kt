package com.nozzleitall.environment

import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class GoveeDeviceName(val device: String, val model: String, val name: String) {
    /** Never infer a BLE MAC from a suffix of an eight-byte/opaque cloud identifier. */
    fun matches(candidate: Candidate): Boolean = candidate.protocol == "govee-ble" && model == candidate.model &&
        mac(device)?.let { it == mac(candidate.address) } == true

    companion object {
        private fun mac(value: String): String? = value.replace(":", "").replace("-", "")
            .takeIf { it.length == 12 && it.all { c -> c in "0123456789abcdefABCDEF" } }?.uppercase()
    }
}

enum class GoveeNameFailure(val description: String) {
    KEY("Enter a valid Govee API key privately."), AUTH("Govee rejected this key. Check it in Govee Home."),
    RATE_LIMIT("Govee rate limit reached. Retry later explicitly."),
    RESPONSE("Govee returned an unsupported or oversized response. No names changed."),
    NETWORK("Govee name import failed. Check the connection and retry explicitly."),
    CANCELLED("Name import cancelled; no results applied.")
}
class GoveeNameException(val reason: GoveeNameFailure) : Exception(reason.description)

/** One explicit read-only request. No retry, redirect, control endpoint, logging or background work. */
class GoveeNameApi internal constructor(private val connect: (URL) -> HttpsURLConnection) {
    constructor() : this({ it.openConnection() as HttpsURLConnection })

    fun fetch(key: String, current: () -> Boolean = { true }): List<GoveeDeviceName> {
        if (key.length !in 8..256 || key.any { it.code !in 33..126 }) throw GoveeNameException(GoveeNameFailure.KEY)
        val deadline = System.nanoTime() + 20_000_000_000L
        fun checkCurrent() { if (!current()) throw GoveeNameException(GoveeNameFailure.CANCELLED) }
        var connection: HttpsURLConnection? = null
        try {
            checkCurrent()
            val c = connect(URL(ENDPOINT)); connection = c
            c.instanceFollowRedirects = false
            c.requestMethod = "GET"; c.doOutput = false; c.useCaches = false
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Govee-API-Key", key)
            checkCurrent()
            when (c.responseCode) {
                200 -> Unit
                401, 403 -> throw GoveeNameException(GoveeNameFailure.AUTH)
                429 -> throw GoveeNameException(GoveeNameFailure.RATE_LIMIT)
                else -> throw GoveeNameException(GoveeNameFailure.RESPONSE)
            }
            if (c.contentLengthLong > MAX_BYTES) throw GoveeNameException(GoveeNameFailure.RESPONSE)
            val bytes = java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    checkCurrent()
                    val remaining = (deadline - System.nanoTime()) / 1_000_000L
                    if (remaining <= 0) throw GoveeNameException(GoveeNameFailure.NETWORK)
                    c.readTimeout = minOf(remaining, 10_000).toInt().coerceAtLeast(1)
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (bytes.size() + n > MAX_BYTES) throw GoveeNameException(GoveeNameFailure.RESPONSE)
                    bytes.write(buffer, 0, n)
                }
            }
            checkCurrent()
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
            return parse(text).also { checkCurrent() }
        } catch (e: GoveeNameException) { throw e }
        catch (_: Exception) { throw GoveeNameException(GoveeNameFailure.NETWORK) }
        finally { connection?.disconnect() }
    }

    companion object {
        const val ENDPOINT = "https://openapi.api.govee.com/router/api/v1/user/devices"
        const val MAX_BYTES = 2 * 1024 * 1024
        fun parse(text: String): List<GoveeDeviceName> {
            try {
                require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
                val root = JSONObject(text)
                require(root.getInt("code") == 200)
                val rows = root.getJSONArray("data"); require(rows.length() <= 1000)
                val devices = linkedMapOf<Pair<String, String>, GoveeDeviceName>()
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    fun field(key: String, limit: Int): String {
                        val raw = row.get(key); require(raw is String)
                        require(raw.isNotBlank() && raw.length <= limit && raw.none { it.isISOControl() })
                        return raw
                    }
                    val device = GoveeDeviceName(field("device", 128), field("sku", 40).uppercase(), field("deviceName", 120))
                    val identity = device.model to device.device.uppercase()
                    val previous = devices.put(identity, device)
                    require(previous == null || previous == device) // Conflicting identity fails closed.
                }
                return devices.values.toList()
            } catch (_: Exception) { throw GoveeNameException(GoveeNameFailure.RESPONSE) }
        }
    }
}

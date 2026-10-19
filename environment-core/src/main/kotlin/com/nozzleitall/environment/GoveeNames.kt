package com.nozzleitall.environment

import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class GoveeDeviceName(val device: String, val model: String, val name: String) {
    /** H5100's eight-byte cloud ID is six little-endian address bytes + two extra
     * bytes; other supported IDs prefix the complete address with two bytes.
     * Compare all 48 address bits, never the advertised name's two-byte suffix.
     * Callers must also require a unique directory match before applying a name.
     */
    fun matches(candidate: Candidate): Boolean = candidate.protocol == "govee-ble" && model == candidate.model &&
        address(device, cloud = true, model = model)?.let { it == address(candidate.address, cloud = false) } == true

    companion object {
        private fun address(value: String, cloud: Boolean, model: String? = null): String? {
            val lengths = if (cloud) setOf(6, 8) else setOf(6)
            val compact = when {
                value.matches(Regex("[0-9a-fA-F]+")) && value.length / 2 in lengths && value.length % 2 == 0 -> value
                lengths.any { count -> value.matches(Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){${count - 1}}")) } -> value.replace(":", "")
                lengths.any { count -> value.matches(Regex("[0-9a-fA-F]{2}(-[0-9a-fA-F]{2}){${count - 1}}")) } -> value.replace("-", "")
                else -> return null
            }
            return if (cloud && compact.length == 16) when (model) {
                "H5100" -> compact.take(12).chunked(2).reversed().joinToString("").uppercase()
                "H5110", "H5074", "H5179" -> compact.takeLast(12).uppercase()
                else -> null // No assumed eight-byte layout for an unknown model.
            } else compact.uppercase()
        }
    }
}

enum class GoveeNameFailure(val description: String) {
    KEY("Enter a valid Govee API key privately."), AUTH("Govee rejected this key. Check it in Govee Home."),
    RATE_LIMIT("Govee rate limit reached. Retry later explicitly."),
    RESPONSE("Govee returned an unsupported or oversized response. No names changed."),
    NETWORK("Govee name import failed. Check the connection and retry explicitly."),
    STORAGE("Saved-key update or cleanup failed. Use Forget Govee key and retry explicitly."),
    DIRECTORY("Govee name directory could not be saved or loaded. Existing sensor data is unchanged; retry name import explicitly."),
    CLEANUP("Govee key cleanup is running; retry after it finishes."),
    CANCELLED("Name import cancelled; no results applied.")
}
class GoveeNameException(val reason: GoveeNameFailure) : Exception(reason.description)

/** One explicit read-only request. No retry, redirect, control endpoint, logging or background work. */
class GoveeNameApi internal constructor(private val budgetMillis: Long = 20_000, private val connect: (URL) -> HttpsURLConnection) {
    constructor() : this(20_000, { it.openConnection() as HttpsURLConnection })

    fun fetch(key: String, current: () -> Boolean = { true }): List<GoveeDeviceName> {
        if (key.length !in 8..256 || key.any { it.code !in 33..126 }) throw GoveeNameException(GoveeNameFailure.KEY)
        val deadline = System.nanoTime() + budgetMillis * 1_000_000L
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        fun checkCurrent() {
            if (!current()) throw GoveeNameException(GoveeNameFailure.CANCELLED)
            if (timedOut.get() || System.nanoTime() >= deadline) throw GoveeNameException(GoveeNameFailure.NETWORK)
        }
        var connection: HttpsURLConnection? = null
        var timer: java.util.Timer? = null
        try {
            checkCurrent()
            val c = connect(URL(ENDPOINT)); connection = c
            c.instanceFollowRedirects = false
            c.requestMethod = "GET"; c.doOutput = false; c.useCaches = false
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Govee-API-Key", key)
            checkCurrent()
            val remaining = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1)
            c.connectTimeout = minOf(remaining, 10_000).toInt(); c.readTimeout = c.connectTimeout
            timer = java.util.Timer("Govee-name-deadline", true).apply {
                schedule(object : java.util.TimerTask() { override fun run() { timedOut.set(true); runCatching { c.disconnect() } } }, remaining)
            }
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
        finally { timer?.cancel(); connection?.disconnect() }
    }

    companion object {
        const val ENDPOINT = "https://openapi.api.govee.com/router/api/v1/user/devices"
        const val MAX_BYTES = 2 * 1024 * 1024
        fun parse(text: String): List<GoveeDeviceName> {
            try {
                require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
                // Bound JSON object allocation and nesting before org.json parses capabilities.
                var quoted = false; var escaped = false; var depth = 0; var structure = 0
                for (c in text) {
                    if (quoted) {
                        if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
                    } else when (c) {
                        '"' -> quoted = true
                        '{', '[' -> { depth++; structure++; require(depth <= 32 && structure <= 20_000) }
                        '}', ']' -> { depth--; require(depth >= 0) }
                        ',', ':' -> { structure++; require(structure <= 20_000) }
                    }
                }
                require(!quoted && depth == 0)
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

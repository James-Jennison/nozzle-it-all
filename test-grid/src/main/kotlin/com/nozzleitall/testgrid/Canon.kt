package com.nozzleitall.testgrid

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Canonical JSON and SHA-256 for everything the Test Grid writes. org.json's own toString() orders keys by hash, so
 * two identical records could serialize differently; this writer sorts keys, fixes number formatting and line
 * endings, and so gives the same bytes for the same content on Android and the JVM alike.
 */
object Canon {
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    /** Sorted keys, two-space indent, "\n" line ends and a trailing newline. */
    fun write(value: Any?): String = StringBuilder().also { append(it, value, 0); it.append('\n') }.toString()

    fun bytes(value: Any?): ByteArray = write(value).toByteArray(Charsets.UTF_8)

    /**
     * Keys whose values differ between two otherwise identical runs: timestamps, the run's own UUID and the unique
     * remote filename an upload gets. Excluded from [contentDigest] so a repeated run with the same outcomes compares
     * equal; still present in the bundle itself.
     */
    val VOLATILE_KEYS = setOf("runId", "startedAt", "completedAt", "approvedAt", "observedAt", "recordedAt", "finishedAt",
        "generatedAt", "exportedAt", "remotePath", "reviewedAt", "durationMillis", "at")

    fun withoutVolatile(value: Any?): Any? = when (value) {
        is JSONObject -> value.keySet().filter { it !in VOLATILE_KEYS }.sorted().associateWith { withoutVolatile(value.opt(it)) }
        is Map<*, *> -> value.keys.map { it.toString() }.filter { it !in VOLATILE_KEYS }.sorted().associateWith { k -> withoutVolatile(value[k]) }
        is JSONArray -> (0 until value.length()).map { withoutVolatile(value.opt(it)) }
        is Iterable<*> -> value.map { withoutVolatile(it) }
        else -> value
    }

    /** SHA-256 of the canonical form with [VOLATILE_KEYS] removed: equal for runs whose recorded content is equal. */
    fun contentDigest(value: Any?): String = sha256(bytes(withoutVolatile(value)))

    private fun append(sb: StringBuilder, value: Any?, indent: Int) {
        when (value) {
            null, JSONObject.NULL -> sb.append("null")
            is JSONObject -> appendObject(sb, value.keySet().sorted().map { it to value.opt(it) }, indent)
            is Map<*, *> -> appendObject(sb, value.entries.map { it.key.toString() to it.value }.sortedBy { it.first }, indent)
            is JSONArray -> appendArray(sb, (0 until value.length()).map { value.opt(it) }, indent)
            is Iterable<*> -> appendArray(sb, value.toList(), indent)
            is Array<*> -> appendArray(sb, value.toList(), indent)
            is String -> quote(sb, value)
            is Boolean -> sb.append(value)
            is Int, is Long, is Short, is Byte, is BigInteger -> sb.append(value.toString())
            is Double -> sb.append(number(value.also { require(it.isFinite()) { "Non-finite number in evidence." } }.toString()))
            is Float -> sb.append(number(value.also { require(it.isFinite()) { "Non-finite number in evidence." } }.toString()))
            is BigDecimal -> sb.append(number(value.toPlainString()))
            is Number -> sb.append(number(value.toString()))
            is Enum<*> -> quote(sb, value.name)
            else -> quote(sb, value.toString())
        }
    }

    private fun number(text: String): String {
        val plain = BigDecimal(text).stripTrailingZeros().toPlainString()
        return if (plain == "-0") "0" else plain
    }

    private fun appendObject(sb: StringBuilder, entries: List<Pair<String, Any?>>, indent: Int) {
        if (entries.isEmpty()) { sb.append("{}"); return }
        sb.append("{\n")
        entries.forEachIndexed { i, (k, v) ->
            pad(sb, indent + 1); quote(sb, k); sb.append(": "); append(sb, v, indent + 1)
            if (i < entries.size - 1) sb.append(',')
            sb.append('\n')
        }
        pad(sb, indent); sb.append('}')
    }

    private fun appendArray(sb: StringBuilder, items: List<Any?>, indent: Int) {
        if (items.isEmpty()) { sb.append("[]"); return }
        sb.append("[\n")
        items.forEachIndexed { i, v ->
            pad(sb, indent + 1); append(sb, v, indent + 1)
            if (i < items.size - 1) sb.append(',')
            sb.append('\n')
        }
        pad(sb, indent); sb.append(']')
    }

    private fun pad(sb: StringBuilder, indent: Int) { repeat(indent) { sb.append("  ") } }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' || c == ' ' || c == ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }
}

// Small org.json helpers shared by the parsers.
internal fun JSONObject.strOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
internal fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.opt(it)?.takeIf { v -> v != JSONObject.NULL }?.toString() } } ?: emptyList()
internal fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
/** Keys this build does not understand, kept so a record read and rewritten loses nothing a newer build wrote. */
internal fun JSONObject.rest(vararg known: String): JSONObject {
    val out = JSONObject()
    keySet().filter { it !in known }.forEach { out.put(it, get(it)) }
    return out
}

package com.nozzleitall.testgrid

import org.json.JSONArray
import org.json.JSONObject

/**
 * The redaction boundary: everything written into an evidence bundle passes through [text] or [json]. Two layers:
 *  1. literals the run knows are private (the printer's address, hostname, saved name, API key or access code), masked
 *     wherever they appear;
 *  2. pattern rules for secrets and identifying details a log or error message might carry.
 * [leaks] re-scans the final output; the bundle builder refuses to produce a bundle while it finds anything.
 *
 * Rules are versioned ([RULES_VERSION]); the bundle records the version and how many of each were applied.
 */
class Redactor(literals: Collection<String> = emptyList(), publicTerms: Collection<String> = emptyList()) {
    companion object {
        const val RULES_VERSION = 1

        /** JSON keys whose values are never exported, whatever they contain. */
        val SENSITIVE_KEYS = setOf("password", "passwd", "pass", "secret", "token", "accesstoken", "refreshtoken", "apikey", "api_key",
            "x-api-key", "accesscode", "access_code", "authorization", "cookie", "set-cookie", "serial", "serialnumber", "sn",
            "address", "host", "hostname", "ip", "url", "cameraurl", "snapshoturl", "streamurl", "label", "email", "privatekey", "certificate")

        // Android's regex engine is ICU, which reads "[:" inside a character class as the start of a POSIX class name
        // ("[:alpha:]"), so a literal colon at the start of a set is written "\:". RegexPortabilityTest enforces it.
        private val PRIVATE_KEY = Regex("""-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\s\S]*?(?:-----END [A-Z0-9 ]*PRIVATE KEY-----|$)""")
        private val HEADER = Regex("""(?im)\b(authorization|proxy-authorization|cookie|set-cookie|x-api-key|x-auth-token|x-access-token)(["']?\s*[\:=]\s*)[^\r\n]*""")
        /** A header whose value is not a redaction marker: what [leaks] looks for, inside plain text or a JSON string. */
        private val HEADER_LEAK = Regex("""(?i)\b(authorization|proxy-authorization|cookie|set-cookie|x-api-key|x-auth-token|x-access-token)["']?\s*[\:=]\s*(?!["']?\[(?:redacted|private)\])["']?[^\s"',}\]]""")
        private val SCHEME_TOKEN = Regex("""(?i)\b(bearer|basic|digest|token)\s+[A-Za-z0-9._~+/=-]{8,}""")
        private val JWT = Regex("""\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}""")
        private val KEY_VALUE = Regex("""(?i)(["']?)\b(password|passwd|pwd|passphrase|token|access[_-]?token|refresh[_-]?token|api[_-]?key|apikey|access[_-]?code|secret|client[_-]?secret|session[_-]?id|sessionid|auth[_-]?token|serial(?:[_-]?number)?|sn|pin|psk|wifi[_-]?password)\1(\s*[\:=]\s*)(?!["']?\[(?:redacted|private)\])("[^"]*"|'[^']*'|[^\s,&;}\]]+)""")
        private val URL = Regex("""(?i)\b(?:https?|rtsp|rtsps|wss?|ftps?|mqtts?)://[^\s"'<>\]\)]+""")
        private val EMAIL = Regex("""(?i)\b[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}\b""")
        private val MAC = Regex("""(?i)(?<![0-9a-f:])(?:[0-9a-f]{2}[\:-]){5}[0-9a-f]{2}(?![0-9a-f:])""")
        private val IPV4 = Regex("""(?<![\w.])((?:25[0-5]|2[0-4]\d|1?\d?\d)(?:\.(?:25[0-5]|2[0-4]\d|1?\d?\d)){3})(?::\d{1,5})?(?![\w]|\.\w)""")
        private val IPV6 = Regex("""(?i)(?<![\w:.])(?:[0-9a-f]{1,4}:){1,7}:?(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?(?![\w:])""")
        private val LOCAL_HOST = Regex("""(?i)\b[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?)*\.(?:local|lan|home|internal|localdomain|home\.arpa|ts\.net|intranet|corp)\b""")
        private val USER_PATHS = listOf(
            Regex("""/home/[^/\s"']+""") to "[home]",
            Regex("""/Users/[^/\s"']+""") to "[home]",
            Regex("""(?i)[A-Z]:\\Users\\[^\\\s"']+""") to "[home]",
            Regex("""/storage/emulated/\d+""") to "[device-storage]",
            Regex("""/sdcard\b""") to "[device-storage]",
            Regex("""/data/(?:user|data)(?:/\d+)?/[A-Za-z0-9_.]+""") to "[app-data]",
            Regex("""/data/user_de/\d+/[A-Za-z0-9_.]+""") to "[app-data]",
            Regex("""/mnt/[^/\s"']+""") to "[mount]",
            Regex("""/root\b""") to "[home]",
            Regex("""/tmp/claude-[^\s"']*""") to "[tmp]",
        )
    }

    /**
     * A known-private value that is itself part of the run's public vocabulary ([publicTerms]: the suite's id, title,
     * maker, model and firmware family, never text the printer reported) hides nothing and would erase that vocabulary
     * everywhere: a Centauri Carbon saved as "cosmos" turned every "cosmos" in its bundle into [private] (found on the
     * first COSMOS run). Such values are not masked; the address, API key and any other name still are.
     */
    private val literals: List<String> = literals.map { it.trim() }.filter { it.length >= 3 }.distinct()
        .filter { lit -> publicTerms.none { it.contains(lit, ignoreCase = true) } }.sortedByDescending { it.length }

    /**
     * Each literal matches only where it stands on its own: not inside a longer run of letters and digits. A printer
     * saved as "CC1" otherwise masked the "cc1" inside a snapshot's SHA-256 and corrupted the hash (found on the
     * owner's COSMOS level-3 run).
     */
    private val literalPatterns: List<Pair<String, Regex>> = this.literals.map { lit ->
        val before = if (lit.first().isLetterOrDigit()) "(?<![A-Za-z0-9])" else ""
        val after = if (lit.last().isLetterOrDigit()) "(?![A-Za-z0-9])" else ""
        lit to Regex(before + Regex.escape(lit) + after, RegexOption.IGNORE_CASE)
    }
    val counts: MutableMap<String, Int> = sortedMapOf()

    private fun count(rule: String, n: Int) { if (n > 0) counts[rule] = (counts[rule] ?: 0) + n }

    private fun sub(input: String, rule: String, regex: Regex, replace: (MatchResult) -> String): String {
        var n = 0
        val out = regex.replace(input) { m -> val r = replace(m); if (r != m.value) n++; r }
        count(rule, n)
        return out
    }

    fun text(input: String): String {
        var s = input
        literalPatterns.forEach { (_, re) -> s = sub(s, "known-private-value", re) { "[private]" } }
        s = sub(s, "private-key", PRIVATE_KEY) { "[private-key]" }
        s = sub(s, "auth-header", HEADER) { "${it.groupValues[1]}${it.groupValues[2]}[redacted]" }
        s = sub(s, "auth-token", SCHEME_TOKEN) { "${it.groupValues[1]} [redacted]" }
        s = sub(s, "jwt", JWT) { "[token]" }
        s = sub(s, "secret-value", KEY_VALUE) { "${it.groupValues[1]}${it.groupValues[2]}${it.groupValues[1]}${it.groupValues[3]}[redacted]" }
        s = sub(s, "url", URL) { url(it.value) }
        s = sub(s, "email", EMAIL) { "[email]" }
        s = sub(s, "mac-address", MAC) { "[mac]" }
        s = sub(s, "ip-address", IPV4) { "[ip]" }
        s = sub(s, "ip-address", IPV6) { m -> if (m.value.count { it == ':' } >= 2 && (m.value.contains("::") || m.value.count { it == ':' } >= 4) && m.value.any { it.isLetterOrDigit() }) "[ip]" else m.value }
        s = sub(s, "local-hostname", LOCAL_HOST) { "[local-host]" }
        USER_PATHS.forEach { (re, rep) -> s = sub(s, "user-path", re) { rep } }
        return s
    }

    /** Camera URLs are dropped entirely; other URLs lose credentials, query and fragment, and a local host is masked. */
    private fun url(raw: String): String {
        val lower = raw.lowercase()
        if (lower.startsWith("rtsp") || listOf("webcam", "camera", "stream", "snapshot", "webrtc", "mjpeg", "/cam", "video").any { it in lower }) return "[camera-url]"
        val scheme = raw.substringBefore("://")
        val rest = raw.substringAfter("://")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        val host = authority.substringBefore(':').removePrefix("[").removeSuffix("]")
        val path = rest.removePrefix(rest.substringBefore('/').let { it }).substringBefore('?').substringBefore('#')
        val local = IPV4.matches(authority) || IPV4.matches(host) || host.contains(':') || '.' !in host || LOCAL_HOST.matches(host)
        return if (local) "[local-url]" else "$scheme://$host$path" + if ('?' in rest) "?[query-removed]" else ""
    }

    /** Recursively redacts a JSON value: sensitive keys lose their values entirely, strings pass through [text]. */
    fun json(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().also { out ->
            value.keySet().forEach { k ->
                val norm = k.lowercase().replace("-", "").replace("_", "")
                if (norm in SENSITIVE_KEYS.map { it.replace("-", "").replace("_", "") }) { out.put(k, "[redacted]"); count("sensitive-field", 1) }
                else if (norm == "version" && value.opt(k) is String) out.put(k, versionText(value.getString(k)))
                else out.put(k, json(value.opt(k)))
            }
        }
        is JSONArray -> JSONArray().also { out -> (0 until value.length()).forEach { out.put(json(value.opt(it))) } }
        is String -> text(value)
        else -> value
    }

    /**
     * Version fields (engine, firmware) are four-part numbers often enough to look like IPv4 addresses; the general
     * IP rule masked the native engine's version on the first real bundle. Here only known-private values, and a value
     * that is literally a private-network or loopback address, are masked.
     */
    private fun versionText(input: String): String {
        var s = input
        literals.forEach { lit -> if (s.contains(lit, ignoreCase = true)) { s = s.replace(lit, "[private]", ignoreCase = true); count("known-private-value", 1) } }
        val m = IPV4.find(s)
        if (m != null && m.value == s.trim() && isPrivateV4(m.groupValues[1])) { count("ip-address", 1); return "[ip]" }
        return s
    }

    private fun isPrivateV4(ip: String): Boolean {
        val p = ip.split('.').map { it.toInt() }
        return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 169 && p[1] == 254) || (p[0] == 100 && p[1] in 64..127)
    }

    /**
     * What still looks private in [output] after redaction: known literals, private-network addresses, key material
     * and credentials. Must be empty before anything is exported.
     */
    fun leaks(output: String): List<String> {
        val found = mutableListOf<String>()
        literalPatterns.forEach { (lit, re) -> if (re.containsMatchIn(output)) found += "a known private value (${lit.length} characters)" }
        if (PRIVATE_KEY.containsMatchIn(output)) found += "private key material"
        if (HEADER_LEAK.containsMatchIn(output)) found += "an authentication header"
        if (JWT.containsMatchIn(output)) found += "a token"
        output.lineSequence().forEach { line ->
            val versionLine = line.trimStart().startsWith("\"version\":")
            IPV4.findAll(line).forEach { m -> if (!versionLine || isPrivateV4(m.groupValues[1])) found += "an IP address" }
        }
        LOCAL_HOST.findAll(output).forEach { found += "a local hostname" }
        USER_PATHS.forEach { (re, _) -> if (re.containsMatchIn(output)) found += "a user filesystem path" }
        EMAIL.findAll(output).forEach { found += "an email address" }
        return found.distinct()
    }
}

/** Sanitized attachment content: image metadata (EXIF, GPS, XMP, comments, text chunks) removed; text redacted later. */
data class CleanAttachment(val bytes: ByteArray, val mime: String, val removedMetadata: Boolean)

object Attachments {
    const val MAX_BYTES = 15 * 1024 * 1024
    val ALLOWED = setOf("image/jpeg", "image/png", "text/plain", "application/json")

    fun extension(mime: String) = when (mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "application/json" -> "json"; else -> "txt" }

    fun sniff(b: ByteArray): String? = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 8 && b.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> "image/png"
        else -> null
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun sanitize(bytes: ByteArray, claimedMime: String): CleanAttachment {
        require(bytes.isNotEmpty()) { "The attachment is empty." }
        require(bytes.size <= MAX_BYTES) { "Attachments are limited to ${MAX_BYTES / (1024 * 1024)} MB." }
        val sniffed = sniff(bytes)
        return when {
            sniffed == "image/jpeg" -> jpeg(bytes).let { CleanAttachment(it, "image/jpeg", it.size != bytes.size) }
            sniffed == "image/png" -> png(bytes).let { CleanAttachment(it, "image/png", it.size != bytes.size) }
            claimedMime in setOf("text/plain", "application/json") -> {
                val text = String(bytes, Charsets.UTF_8)
                require(text.none { it == '\u0000' }) { "That file is not text." }
                CleanAttachment(bytes, claimedMime, false)
            }
            else -> throw IllegalArgumentException("Only JPEG or PNG photos and plain-text or JSON files can be attached.")
        }
    }

    /** Keeps only the segments needed to decode the image: drops APP1-APP15 (EXIF, XMP, ICC, maker notes) and comments. */
    fun jpeg(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        out.write(0xFF); out.write(0xD8)
        var i = 2
        while (i + 4 <= b.size) {
            if (b[i] != 0xFF.toByte()) throw IllegalArgumentException("The JPEG is malformed.")
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xD9) { out.write(b, i, 2); return out.toByteArray() }
            if (marker == 0xFF) { i++; continue }
            if (marker == 0x01 || marker in 0xD0..0xD7) { out.write(b, i, 2); i += 2; continue }
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (len < 2 || i + 2 + len > b.size) throw IllegalArgumentException("The JPEG is malformed.")
            val keep = !(marker in 0xE1..0xEF || marker == 0xFE)
            if (marker == 0xDA) {
                // Image data runs to the first EOI (inside it every 0xFF is stuffed or a restart marker). Anything after is
                // appended by the phone - an Ultra HDR gain map, a Motion Photo's video - and is dropped: found on the first
                // real bundle, where every photo carried a gain map with its own XMP.
                var e = i + 2 + len
                while (e + 1 < b.size && !(b[e] == 0xFF.toByte() && b[e + 1] == 0xD9.toByte())) e++
                if (e + 1 >= b.size) throw IllegalArgumentException("The JPEG is truncated.")
                out.write(b, i, e + 2 - i); return out.toByteArray()
            }
            if (keep) out.write(b, i, 2 + len)
            i += 2 + len
        }
        throw IllegalArgumentException("The JPEG is truncated.")
    }

    /** Drops text, time and EXIF chunks; keeps everything needed to render. */
    fun png(b: ByteArray): ByteArray {
        val drop = setOf("tEXt", "zTXt", "iTXt", "eXIf", "tIME")
        val out = java.io.ByteArrayOutputStream(b.size)
        out.write(b, 0, 8)
        var i = 8
        while (i + 12 <= b.size) {
            val len = ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (len < 0 || i + 12 + len > b.size) throw IllegalArgumentException("The PNG is malformed.")
            val type = String(b, i + 4, 4, Charsets.US_ASCII)
            if (type !in drop) out.write(b, i, 12 + len)
            i += 12 + len
            if (type == "IEND") return out.toByteArray()
        }
        throw IllegalArgumentException("The PNG is truncated.")
    }
}

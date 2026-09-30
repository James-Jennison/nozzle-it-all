package com.nozzleitall.adapter.elegoo

import java.io.File
import java.net.URI
import java.security.MessageDigest

/** A printer's host (and an optional port the user typed), checked to be on the LAN or the user's private network. */
data class ElegooHost(val host: String, val port: Int?) {
    /** The host as it goes into a URL (IPv6 in brackets). */
    val urlHost: String get() = if (':' in host) "[$host]" else host
}

/** Shared rules for both Elegoo protocols: LAN-only hosts, bounded reads, the file hash and colour form they use. */
internal object ElegooNet {
    /** Replies larger than this are dropped: status and slot messages are a few kilobytes. */
    const val MAX_MESSAGE_BYTES = 1_000_000

    /**
     * The same private-host rules as the Moonraker client (adapter-paxx MoonrakerLan.isPrivateHost): RFC 1918, loopback,
     * link-local, CGNAT/Tailscale, ULA/link-local IPv6, bare names and .local/.lan/.home.arpa/.ts.net. Anything else is
     * refused before a single packet is sent, because these protocols have no transport security.
     */
    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        val v4 = h.count { it == '.' } == 3 && p.size == 4 && p.all { it in 0..255 }
        val privateV4 = v4 && (p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 100 && p[1] in 64..127) || (p[0] == 169 && p[1] == 254))
        val bare = h.isNotEmpty() && '.' !in h && ':' !in h
        return privateV4 || bare || h == "localhost" || h == "::1" || h.startsWith("fd") || h.startsWith("fe80:") || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".ts.net")
    }

    /** Parses "192.168.1.50", "http://192.168.1.50", "centauri.lan:3030" and the like. Throws IllegalArgumentException. */
    fun parseHost(address: String): ElegooHost {
        val trimmed = address.trim()
        require(trimmed.isNotEmpty()) { "Enter the printer's address, for example 192.168.1.50." }
        val uri = try { URI(if ("://" in trimmed) trimmed else "http://$trimmed") } catch (e: Exception) { throw IllegalArgumentException("Enter the printer's address, for example 192.168.1.50.") }
        val host = uri.host?.trim('[', ']')?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Enter the printer's address, for example 192.168.1.50.")
        require(uri.userInfo == null) { "Use a plain address without a user name or password." }
        require(isPrivateHost(host)) { "Elegoo printers are reached only on your local or private network. Enter the printer's local address." }
        return ElegooHost(host, uri.port.takeIf { it > 0 })
    }

    /** Lower-case hex MD5 of the whole file, as elegoo-link's FileUtils::calculateMD5 (src/utils/utils.cpp) produces. */
    fun md5Hex(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input -> val buf = ByteArray(64 * 1024); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * CANVAS reports colours as hex strings; elegoo-link passes them through untouched and ElegooSlicer's colour matcher
     * (src/libslic3r/StandardColorMatcher.cpp parse_hex) accepts them with or without '#'. Returns "#RRGGBB" upper case,
     * dropping an alpha byte if one is present; null for anything that isn't a colour.
     */
    fun normalizeColor(raw: String?): String? {
        val body = raw?.trim()?.removePrefix("#")?.removePrefix("0x")?.removePrefix("0X") ?: return null
        if (!body.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return when (body.length) { 6, 8 -> "#" + body.substring(0, 6).uppercase(); else -> null }
    }

    /** A file name the printer stores in its own local storage: no folders, no control characters. */
    fun validateRemoteName(name: String): String {
        require(name.isNotBlank() && name.length <= 200 && '/' !in name && '\\' !in name && name != "." && name != ".." && name.none { it.code < 0x20 }) { "Invalid file name on the printer." }
        return name
    }
}

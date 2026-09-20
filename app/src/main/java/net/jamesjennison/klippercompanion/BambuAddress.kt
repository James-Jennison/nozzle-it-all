package net.jamesjennison.klippercompanion

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Validates and normalizes the address of a Bambu Lab printer (PrinterKind.BAMBU_LAB).
 *
 * Unlike every other kind this app talks to, a Bambu printer in LAN mode has no HTTP endpoint at
 * all - MQTT on 8883, FTPS on 990 and the camera on 6000 - so its PrinterProfile.address is a bare
 * host or IP with no scheme, which Moonraker.parseAddress cannot validate. The bar is the same one
 * parseAddress applies to an http:// host (isLocalHost): private or Tailscale IPv4, Tailscale IPv6,
 * a bare local hostname, localhost, .local or .ts.net. Ports are fixed by the protocols, so an
 * address carrying one is a mistake rather than something to honour.
 */
fun bambuHostAddress(host: String): String {
    val raw = host.trim()
    require(raw.isNotEmpty()) { "Enter the printer's IP address, for example 192.168.1.50." }
    require(raw.none { it == '/' || it == '@' || it == '?' || it == '#' || it.code < 33 }) { "Enter just the printer's address — no http://, path or credentials." }
    // An unbracketed IPv6 literal is the one form okhttp will not read as an authority; bracket it
    // so a Tailscale fd7a: address can be typed the way it is displayed everywhere else.
    val authority = if(':' in raw && !raw.startsWith("[")) "[$raw]" else raw
    val url = "http://$authority".toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a valid printer IP address or hostname.")
    require(url.port == 80 && !authority.endsWith(":80")) { "Enter the printer's address without a port; Bambu's ports are fixed." }
    require(isLocalHost(url.host)) { "A Bambu Lab printer must be reachable on your local network: a private IPv4 address, a Tailscale address (100.64-127.x.x, fd7a:115c:a1e0::/48 or *.ts.net), a bare local hostname or a .local name." }
    return url.host
}

package com.nozzleitall.desktop.discovery

import com.nozzleitall.printer.DiscoveredPrinter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Finds printers on the networks this computer is on. Only addresses of the local network are visited, one connection each
 * to Moonraker's port (or the web port in front of it); an address that answers is then asked what it is by the built-in adapters. Nothing is sent anywhere else.
 */
object LanScan {
    /** Moonraker's port, which the Snapmaker U1 and other Klipper printers answer on. */
    const val PORT = 7125

    /** The addresses to visit for a computer at [self] on a network with [prefix] bits: its own block (at least a /24 is never exceeded), without itself, the network and broadcast addresses. */
    fun hostsFor(self: String, prefix: Int): List<String> {
        val parts = self.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || prefix !in 16..30) return emptyList()
        val ip = parts.fold(0L) { acc, p -> (acc shl 8) or p.toLong() }
        val effective = maxOf(prefix, 24)
        val mask = (0xFFFFFFFFL shl (32 - effective)) and 0xFFFFFFFFL
        val network = ip and mask; val broadcast = network or mask.inv() and 0xFFFFFFFFL
        return (network + 1 until broadcast).filter { it != ip }.map { "${it shr 24 and 255}.${it shr 16 and 255}.${it shr 8 and 255}.${it and 255}" }
    }

    /** Whether [address] is in a private range (home and office networks), the only ones worth visiting. */
    fun isPrivate(address: String): Boolean {
        val p = address.split('.').mapNotNull { it.toIntOrNull() }; if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    /** The addresses to visit on this computer's networks: wired and Wi-Fi ones, not virtual bridges or VPNs. */
    fun localHosts(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual && VIRTUAL.none { v -> it.name.startsWith(v) } }
            .flatMap { nic -> nic.interfaceAddresses.filter { it.address is Inet4Address && isPrivate(it.address.hostAddress) }
                .flatMap { hostsFor(it.address.hostAddress, it.networkPrefixLength.toInt()) } }.distinct()
    }.getOrDefault(emptyList())

    private val VIRTUAL = listOf("docker", "veth", "br-", "virbr", "vmnet", "vboxnet", "tailscale", "tun", "tap", "zt")

    /**
     * A printer that answers on two addresses (Wi-Fi and cable) is still one printer. Moonraker's `printer/info` says which Klipper
     * it is: the same host name, process and software version mean the same running printer. Null if the text isn't that reply.
     */
    fun fingerprintOf(printerInfo: String): String? = runCatching {
        val r = org.json.JSONObject(printerInfo).getJSONObject("result")
        listOf(r.optString("hostname"), r.optInt("process_id").toString(), r.optString("software_version"), r.optString("config_file")).takeIf { r.has("process_id") }?.joinToString("|")
    }.getOrNull()

    /** The fingerprint of the printer at [address] (a bare host means Moonraker's own port), or null if it doesn't answer. */
    fun fingerprint(address: String): String? = runCatching {
        val base = if ("://" in address) address.trimEnd('/') else "http://$address:$PORT"
        val c = java.net.URI("$base/printer/info").toURL().openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 1500; c.readTimeout = 2500
        try { fingerprintOf(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
    }.getOrNull()

    private fun portOpen(host: String, port: Int): Boolean = try { Socket().use { it.connect(InetSocketAddress(host, port), 400); true } } catch (_: Exception) { false }

    /**
     * Where a printer at [host] would be asked: Moonraker's own port, or the web port where Moonraker sits behind a web server
     * (the Elegoo Centauri Carbon does). Null if neither is open.
     */
    private fun endpoint(host: String): String? = if (portOpen(host, PORT)) host else if (portOpen(host, 80)) "http://$host/" else null

    /**
     * Visits [hosts] (many at once). An address with a printer port open is handed to [probe] (as the address [endpoint] gives); whatever it recognises goes to [onFound].
     * [onProgress] gets (visited, total) as it goes. [endpoint] and [probe] are parameters so the walk can be tested without a network.
     */
    suspend fun scan(hosts: List<String>, endpoint: (String) -> String? = ::endpoint, probe: suspend (String) -> List<DiscoveredPrinter>,
                     onFound: (DiscoveredPrinter) -> Unit, onProgress: (Int, Int) -> Unit = { _, _ -> }, concurrency: Int = 64) {
        val gate = Semaphore(concurrency); val done = java.util.concurrent.atomic.AtomicInteger()
        coroutineScope {
            hosts.map { h -> async(Dispatchers.IO) {
                gate.withPermit {
                    endpoint(h)?.let { probe(it).forEach(onFound) }
                    onProgress(done.incrementAndGet(), hosts.size)
                }
            } }.awaitAll()
        }
    }
}

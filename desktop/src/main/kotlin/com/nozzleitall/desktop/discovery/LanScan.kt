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
 * to Moonraker's port; an address that answers is then asked what it is by the built-in adapters. Nothing is sent anywhere else.
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

    private fun portOpen(host: String): Boolean = try { Socket().use { it.connect(InetSocketAddress(host, PORT), 400); true } } catch (_: Exception) { false }

    /**
     * Visits [hosts] (many at once). An address with the port open is handed to [probe]; whatever it recognises goes to [onFound].
     * [onProgress] gets (visited, total) as it goes. [open] and [probe] are parameters so the walk can be tested without a network.
     */
    suspend fun scan(hosts: List<String>, open: (String) -> Boolean = ::portOpen, probe: suspend (String) -> List<DiscoveredPrinter>,
                     onFound: (DiscoveredPrinter) -> Unit, onProgress: (Int, Int) -> Unit = { _, _ -> }, concurrency: Int = 64) {
        val gate = Semaphore(concurrency); val done = java.util.concurrent.atomic.AtomicInteger()
        coroutineScope {
            hosts.map { h -> async(Dispatchers.IO) {
                gate.withPermit {
                    if (open(h)) probe(h).forEach(onFound)
                    onProgress(done.incrementAndGet(), hosts.size)
                }
            } }.awaitAll()
        }
    }
}

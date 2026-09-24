package net.jamesjennison.klippercompanion

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds printers on the local network: a bounded TCP sweep that verifies Moonraker (`/server/info`) and PrusaLink (`/api/version`) by their
 * real replies, plus Bambu's SSDP broadcast (which carries the serial number). Read-only: it only GETs two public info endpoints.
 * Nothing here sends credentials, and results are suggestions the wizard still verifies with its own live checks.
 */
class PrinterScanner(
    private val connectTimeoutMs: Int = 600,
    private val moonrakerPorts: List<Int> = listOf(7125, 80),
    private val prusaPorts: List<Int> = listOf(80),
    private val ssdpPorts: List<Int> = listOf(1990, 2021),
    private val ssdpTarget: String = "239.255.255.250",
    private val ssdpWaitMs: Int = 4000,
) {
    private val http = OkHttpClient.Builder().connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS).readTimeout(1500, TimeUnit.MILLISECONDS)
        .followRedirects(false).retryOnConnectionFailure(false).build()

    fun scan(hosts: List<String>, cancelled: AtomicBoolean = AtomicBoolean(false), onFound: (DiscoveredPrinter) -> Unit) {
        val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val report = { p: DiscoveredPrinter -> if (seen.add(p.address)) onFound(p) }
        val pool = Executors.newFixedThreadPool(48)
        val ssdp = Thread { runCatching { listenSsdp(cancelled, hosts, report) } }.apply { isDaemon = true; start() }
        try {
            val jobs = hosts.map { host -> pool.submit { if (!cancelled.get()) probeHost(host)?.let(report) } }
            jobs.forEach { runCatching { it.get(20, TimeUnit.SECONDS) } }
        } finally { pool.shutdownNow(); ssdp.join(ssdpWaitMs + 1000L) }
    }

    internal fun probeHost(host: String): DiscoveredPrinter? {
        for (port in moonrakerPorts) if (open(host, port)) moonraker(host, port)?.let { return it }
        for (port in prusaPorts) if (open(host, port)) prusa(host, port)?.let { return it }
        return null
    }

    private fun open(host: String, port: Int): Boolean = try { Socket().use { it.connect(InetSocketAddress(host, port), connectTimeoutMs); true } } catch (_: Exception) { false }

    private fun get(url: String): String? = try {
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r -> if (r.isSuccessful) r.body?.source()?.let { s -> s.request(64 * 1024); s.buffer.clone().readUtf8() } else null }
    } catch (_: Exception) { null }

    private fun moonraker(host: String, port: Int): DiscoveredPrinter? {
        val base = if (port == 80) "http://$host" else "http://$host:$port"
        val server = get("$base/server/info") ?: return null
        val result = try { JSONObject(server).optJSONObject("result") } catch (_: Exception) { null } ?: return null
        if (!result.has("klippy_state") && !result.has("moonraker_version")) return null
        val info = get("$base/printer/info")?.let { try { JSONObject(it).optJSONObject("result") } catch (_: Exception) { null } }
        val address = if (port == 80) host else "$host:$port"
        return PrinterDiscovery.classifyMoonraker(info?.optString("hostname").orEmpty(), info?.optString("app").orEmpty(), info?.optString("software_version").orEmpty(), address)
    }

    private fun prusa(host: String, port: Int): DiscoveredPrinter? {
        val base = if (port == 80) "http://$host" else "http://$host:$port"
        return get("$base/api/version")?.let { PrinterDiscovery.parsePrusaLinkVersion(it, if (port == 80) host else "$host:$port") }
    }

    private fun listenSsdp(cancelled: AtomicBoolean, hosts: List<String>, report: (DiscoveredPrinter) -> Unit) {
        // Printers announce (NOTIFY) to the SSDP multicast group, which a socket only receives after joining it; a plain bind sees nothing.
        val group = InetAddress.getByName(ssdpTarget)
        val sockets = ssdpPorts.mapNotNull { port -> runCatching { java.net.MulticastSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(port)); soTimeout = 300; joinGroup(group) } }.getOrNull() }
        val sender = runCatching { DatagramSocket().apply { soTimeout = 300; broadcast = true } }.getOrNull()
        val probe = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1990\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:bambulab-com:device:3dprinter:1\r\n\r\n").toByteArray()
        try {
            // Ask everywhere: the multicast group, the subnet broadcast, and each host directly (some printers only answer a direct search).
            val targets = listOf(ssdpTarget) + hosts
            ssdpPorts.forEach { port -> targets.forEach { t -> runCatching { sender?.send(DatagramPacket(probe, probe.size, InetAddress.getByName(t), port)) } } }
            val deadline = System.currentTimeMillis() + ssdpWaitMs; val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline && !cancelled.get()) {
                for (s in sockets + listOfNotNull(sender)) {
                    val packet = DatagramPacket(buf, buf.size)
                    try { s.receive(packet); PrinterDiscovery.parseBambuSsdp(String(packet.data, 0, packet.length, Charsets.UTF_8))?.let(report) } catch (_: java.net.SocketTimeoutException) {}
                }
            }
        } finally { sockets.forEach { runCatching { it.leaveGroup(group) }; it.close() }; sender?.close() }
    }
}

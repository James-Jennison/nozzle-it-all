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
 * real replies, plus Bambu's SSDP broadcast (which carries the serial number) and Elegoo's two UDP discovery messages (Centauri Carbon:
 * "M99999" to port 3000; Centauri Carbon 2: {"id":0,"method":7000} to port 52700, as elegoo-link's discovery strategies send them).
 * Read-only: it only GETs public info endpoints and asks printers to describe themselves.
 * Nothing here sends credentials, and results are suggestions the wizard still verifies with its own live checks.
 */
class PrinterScanner(
    private val connectTimeoutMs: Int = 600,
    private val moonrakerPorts: List<Int> = listOf(7125, 80),
    private val prusaPorts: List<Int> = listOf(80),
    private val octoPrintPorts: List<Int> = listOf(5000, 80),
    private val ssdpPorts: List<Int> = listOf(1990, 2021),
    private val ssdpTarget: String = "239.255.255.250",
    private val ssdpWaitMs: Int = 4000,
    private val elegooSdcpPort: Int = com.nozzleitall.adapter.elegoo.Sdcp.DISCOVERY_PORT,
    private val elegooCc2Port: Int = com.nozzleitall.adapter.elegoo.Cc2.DISCOVERY_PORT,
    private val elegooBroadcast: String = "255.255.255.255",
) {
    private val http = OkHttpClient.Builder().connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS).readTimeout(1500, TimeUnit.MILLISECONDS)
        .followRedirects(false).retryOnConnectionFailure(false).build()

    fun scan(hosts: List<String>, cancelled: AtomicBoolean = AtomicBoolean(false), onFound: (DiscoveredPrinter) -> Unit) {
        val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val report = { p: DiscoveredPrinter -> if (seen.add(p.address)) onFound(p) }
        val pool = Executors.newFixedThreadPool(48)
        val ssdp = Thread { runCatching { listenSsdp(cancelled, hosts, report) } }.apply { isDaemon = true; start() }
        val elegoo = Thread { runCatching { listenElegoo(cancelled, hosts, report) } }.apply { isDaemon = true; start() }
        try {
            val jobs = hosts.map { host -> pool.submit { if (!cancelled.get()) probeHost(host)?.let(report) } }
            jobs.forEach { runCatching { it.get(20, TimeUnit.SECONDS) } }
        } finally { pool.shutdownNow(); ssdp.join(ssdpWaitMs + 1000L); elegoo.join(ssdpWaitMs + 1000L) }
    }

    internal fun probeHost(host: String): DiscoveredPrinter? {
        for (port in moonrakerPorts) if (open(host, port)) moonraker(host, port)?.let { return it }
        for (port in prusaPorts) if (open(host, port)) prusa(host, port)?.let { return it }
        for (port in octoPrintPorts) if (open(host, port)) octoPrint(host, port)?.let { return it }
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
        val app = info?.optString("app").orEmpty()
        // A COSMOS Centauri Carbon with AFC (the CANVAS) gets the CANVAS profile suggested; one more read-only GET.
        val afc = PrinterDiscovery.isCosmos(app) && PrinterDiscovery.hasAfcObject(get("$base/printer/objects/list")?.let { try { JSONObject(it).optJSONObject("result") } catch (_: Exception) { null } })
        val version = info?.optString("software_version").orEmpty()
        // A Snapmaker U1 on PAXX's extended firmware keeps an `extended/` folder among its config files; one more read-only GET.
        val paxx = PrinterDiscovery.isU1Version(version) && PrinterDiscovery.hasExtendedConfig(get("$base/server/files/list?root=config")?.let { try { JSONObject(it).optJSONArray("result") } catch (_: Exception) { null } })
        return PrinterDiscovery.classifyMoonraker(info?.optString("hostname").orEmpty(), app, version, address, afc, paxx)
    }

    companion object {
        /** One reply to either Elegoo discovery message, from [host]; null when it isn't one. */
        fun elegooReply(host: String, reply: String): DiscoveredPrinter? {
            com.nozzleitall.adapter.elegoo.Sdcp.parseDiscovery(reply)?.let { d ->
                val fw = d.firmwareVersion.ifBlank { null }?.let { ", firmware $it" }.orEmpty()
                val model = d.machineName.ifBlank { "Centauri Carbon" }
                return PrinterDiscovery.elegoo(host, d.name.ifBlank { model }, model, false, "Elegoo $model$fw").copy(serial = d.mainboardId.take(40))
            }
            com.nozzleitall.adapter.elegoo.Cc2.parseDiscovery(reply)?.let { d ->
                val model = d.model.ifBlank { "Centauri Carbon 2" }
                val code = if (d.accessCodeRequired) " - needs its access code" else ""
                return PrinterDiscovery.elegoo(host, d.name.ifBlank { model }, model, true, "Elegoo $model$code").copy(serial = d.serial.take(40))
            }
            return null
        }
    }

    private fun octoPrint(host: String, port: Int): DiscoveredPrinter? {
        val base = if (port == 80) "http://$host" else "http://$host:$port"
        return getText("$base/")?.let { PrinterDiscovery.parseOctoPrintPage(it, if (port == 80) host else "$host:$port") }
    }

    private fun getText(url: String): String? = try {
        http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.source()?.let { s -> s.request(64 * 1024); s.buffer.clone().readUtf8() } else null }
    } catch (_: Exception) { null }

    private fun prusa(host: String, port: Int): DiscoveredPrinter? {
        val base = if (port == 80) "http://$host" else "http://$host:$port"
        return get("$base/api/version")?.let { PrinterDiscovery.parsePrusaLinkVersion(it, if (port == 80) host else "$host:$port") }
    }

    /**
     * Elegoo's LAN discovery, asked of the subnet broadcast and each host directly; one socket, replies parsed with the
     * Elegoo adapter's own rules (Sdcp.parseDiscovery, Cc2.parseDiscovery). The printer that answers is named by the reply's
     * source address, so a reply can't point the wizard at some other host.
     */
    internal fun listenElegoo(cancelled: AtomicBoolean, hosts: List<String>, report: (DiscoveredPrinter) -> Unit) {
        val socket = DatagramSocket().apply { soTimeout = 300; broadcast = true }
        socket.use {
            val sdcp = com.nozzleitall.adapter.elegoo.Sdcp.DISCOVERY_MESSAGE.toByteArray(); val cc2 = com.nozzleitall.adapter.elegoo.Cc2.DISCOVERY_MESSAGE.toByteArray()
            (listOf(elegooBroadcast) + hosts).forEach { t ->
                runCatching { val a = InetAddress.getByName(t); socket.send(DatagramPacket(sdcp, sdcp.size, a, elegooSdcpPort)); socket.send(DatagramPacket(cc2, cc2.size, a, elegooCc2Port)) }
            }
            val deadline = System.currentTimeMillis() + ssdpWaitMs; val buf = ByteArray(8192)
            while (System.currentTimeMillis() < deadline && !cancelled.get()) {
                val packet = DatagramPacket(buf, buf.size)
                try { socket.receive(packet) } catch (_: java.net.SocketTimeoutException) { continue }
                if (packet.length >= buf.size) continue
                val host = packet.address.hostAddress ?: continue
                elegooReply(host, String(packet.data, 0, packet.length, Charsets.UTF_8))?.let(report)
            }
        }
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

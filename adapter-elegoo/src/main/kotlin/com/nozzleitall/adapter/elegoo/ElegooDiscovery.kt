package com.nozzleitall.adapter.elegoo

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Elegoo's UDP discovery, asked of one address (the desktop adds printers by address; nothing is broadcast). Read-only:
 * both messages only ask the printer to describe itself.
 *
 * elegoo-link src/lan/discovery/printer_discovery.cpp sends each strategy's message to its port and parses the replies:
 * Centauri Carbon "M99999" to UDP 3000 (elegoo_fdm_cc_discovery_strategy.cpp, and the SDCP V3 document), Centauri
 * Carbon 2 {"id":0,"method":7000} to UDP 52700 (elegoo_fdm_cc2_discovery_strategy.cpp).
 */
object ElegooDiscovery {
    private const val MAX_REPLY = 8 * 1024

    /** Sends [message] to host:port and returns the first reply from that host, or null after [timeoutMs]. */
    fun ask(host: String, port: Int, message: String, timeoutMs: Int = 1500): String? {
        require(ElegooNet.isPrivateHost(host)) { "Elegoo printers are reached only on your local or private network." }
        val target = InetAddress.getByName(host)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val bytes = message.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(bytes, bytes.size, target, port))
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(MAX_REPLY)
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                socket.soTimeout = left.toInt().coerceAtLeast(1)
                val packet = DatagramPacket(buf, buf.size)
                try { socket.receive(packet) } catch (e: SocketTimeoutException) { return null }
                // Only the printer we asked; a full buffer means an oversized reply, which is dropped.
                if (packet.address == target && packet.length < MAX_REPLY) return String(packet.data, 0, packet.length, Charsets.UTF_8)
            }
        }
    }

    fun askSdcp(host: String, port: Int = Sdcp.DISCOVERY_PORT, timeoutMs: Int = 1500): Sdcp.Discovery? =
        runCatching { ask(host, port, Sdcp.DISCOVERY_MESSAGE, timeoutMs) }.getOrNull()?.let(Sdcp::parseDiscovery)

    fun askCc2(host: String, port: Int = Cc2.DISCOVERY_PORT, timeoutMs: Int = 1500): Cc2.Discovery? =
        runCatching { ask(host, port, Cc2.DISCOVERY_MESSAGE, timeoutMs) }.getOrNull()?.let(Cc2::parseDiscovery)
}

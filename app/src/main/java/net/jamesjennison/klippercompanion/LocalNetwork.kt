package net.jamesjennison.klippercompanion

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import java.net.Inet4Address

/** The device's own IPv4 LAN, for a printer scan. Only private (RFC 1918) networks are ever scanned. */
object LocalNetwork {
    /** Every other host on the phone's /24 (or the smaller subnet it is in), or empty when not on a private LAN. */
    fun scanHosts(context: Context): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        // The active network is often a VPN (Tailscale) or cellular; the LAN is whichever connected network holds a private IPv4 address.
        val addr = cm.allNetworks.asSequence().mapNotNull { cm.getLinkProperties(it) }
            .flatMap { it.linkAddresses.asSequence() }.firstOrNull { it.address is Inet4Address && it.address.isSiteLocalAddress } ?: return emptyList()
        return hostsIn(addr.address.address, addr.prefixLength)
    }

    fun hostsIn(ip: ByteArray, prefixLength: Int): List<String> {
        val own = ip.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
        val prefix = prefixLength.coerceIn(24, 30) // a wider network is scanned only as the phone's own /24
        val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        val net = own and mask; val broadcast = net or (mask.inv() and 0xffffffffL)
        return (net + 1 until broadcast).filter { it != own }.map { "${(it shr 24) and 255}.${(it shr 16) and 255}.${(it shr 8) and 255}.${it and 255}" }
    }

    fun multicastLock(context: Context): WifiManager.MulticastLock? = runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)?.createMulticastLock("nozzle-printer-scan")?.apply { setReferenceCounted(false); acquire() }
    }.getOrNull()
}

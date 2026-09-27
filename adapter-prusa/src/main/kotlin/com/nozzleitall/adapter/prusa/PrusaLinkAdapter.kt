package com.nozzleitall.adapter.prusa

import com.nozzleitall.adapter.common.TransportSession
import com.nozzleitall.printer.*
import net.jamesjennison.klippercompanion.PrinterCommand
import net.jamesjennison.klippercompanion.PrusaLinkPrintRequest
import net.jamesjennison.klippercompanion.PrusaLinkPrinterService

/**
 * Prusa printers through PrusaLink on the LAN (digest auth with the printer's PrusaLink password), through Android's
 * PrusaLink client. Prusa Connect (cloud) is not used; if it is ever added it lives only inside this adapter.
 * Physical-printer status: UNVERIFIED (built from Prusa's published OpenAPI description; no real printer tested).
 */
class PrusaLinkAdapter : DeviceAdapter {
    override val id = ID
    override val displayName = "Prusa (PrusaLink)"
    override val families = setOf(PrinterFamily.PRUSA)
    override val mayUseVendorCloud = false

    companion object {
        const val ID = "prusalink"
        val CAPABILITIES = Capabilities(uploadAndStart = true, startPrint = true, pausePrint = true, resumePrint = true, cancelPrint = true,
            files = true, localConnection = true, remoteConnection = true, acceptedOutputs = setOf("gcode", "bgcode"))
    }

    override fun probe(address: String): DiscoveredPrinter? = null

    override fun open(config: PrinterConfig): PrinterSession {
        val service = PrusaLinkPrinterService(config.identity.address, config.secret)
        val host = runCatching { java.net.URI(if ("://" in config.identity.address) config.identity.address else "http://${config.identity.address}").host }.getOrNull() ?: ""
        return TransportSession(config.identity, CAPABILITIES, service, routeFor(host)) { file, name ->
            PrinterCommand("Send and print $name", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(file, name))
        }
    }
}

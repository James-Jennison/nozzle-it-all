package com.nozzleitall.adapter.octoprint

import com.nozzleitall.adapter.common.TransportSession
import com.nozzleitall.printer.*
import net.jamesjennison.klippercompanion.OctoPrintPrinterService
import net.jamesjennison.klippercompanion.PrinterCommand
import net.jamesjennison.klippercompanion.PrusaLinkPrintRequest

/**
 * OctoPrint-compatible printers on the LAN, through Android's OctoPrint client (API key auth). What that client
 * implements is exactly what is declared: status, temperatures, upload-and-print, pause/resume/cancel. No camera yet.
 * Physical-printer status: UNVERIFIED (tested against a real OctoPrint server with no printer attached).
 */
class OctoPrintAdapter : DeviceAdapter {
    override val id = ID
    override val displayName = "OctoPrint"
    override val families = setOf(PrinterFamily.OCTOPRINT)
    override val mayUseVendorCloud = false

    companion object {
        const val ID = "octoprint"
        val CAPABILITIES = Capabilities(uploadAndStart = true, startPrint = true, pausePrint = true, resumePrint = true, cancelPrint = true,
            files = true, localConnection = true, remoteConnection = true, acceptedOutputs = setOf("gcode"))
    }

    /** OctoPrint needs an API key before it says anything useful, so it is added by address and key rather than probed. */
    override fun probe(address: String): DiscoveredPrinter? = null

    override fun open(config: PrinterConfig): PrinterSession {
        val service = OctoPrintPrinterService(config.identity.address, config.secret)
        return TransportSession(config.identity, CAPABILITIES, service, routeFor(hostOf(config.identity.address))) { file, name ->
            PrinterCommand("Send and print $name", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(file, name))
        }
    }
}

internal fun hostOf(address: String) = runCatching { java.net.URI(if ("://" in address) address else "http://$address").host }.getOrNull() ?: address

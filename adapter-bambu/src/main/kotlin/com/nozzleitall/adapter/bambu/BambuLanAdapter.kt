package com.nozzleitall.adapter.bambu

import com.nozzleitall.adapter.common.TransportSession
import com.nozzleitall.printer.*
import net.jamesjennison.klippercompanion.BambuPrintRequest
import net.jamesjennison.klippercompanion.BambuPrinterService
import net.jamesjennison.klippercompanion.PrinterCommand
import net.jamesjennison.klippercompanion.bambuHostAddress

/**
 * Bambu Lab printers in LAN mode (MQTT over TLS with the printer's access code, FTPS upload, chamber camera, with a
 * pinned certificate), through Android's Bambu client. Needs the printer's serial number (PrinterConfig.extras
 * "serial") and access code (secret). Bambu's cloud is not used; if it is ever added it lives only inside this adapter.
 * Starting a print needs a Bambu .gcode.3mf bundle. Physical-printer status: UNVERIFIED (tested against an emulator).
 */
class BambuLanAdapter : DeviceAdapter {
    override val id = ID
    override val displayName = "Bambu Lab (LAN mode)"
    override val families = setOf(PrinterFamily.BAMBU_LAB)
    override val mayUseVendorCloud = false

    companion object {
        const val ID = "bambu-lan"
        val CAPABILITIES = Capabilities(uploadAndStart = true, pausePrint = true, resumePrint = true, cancelPrint = true,
            camera = false, localConnection = true, remoteConnection = false, acceptedOutputs = setOf("gcode.3mf"))
        /** The client reads the port-6000 JPEG camera of A1 and P1 printers only; X1, P2S and H2 use RTSPS, which isn't built. */
        fun capabilitiesFor(model: String) = CAPABILITIES.copy(camera = Regex("\\b(A1|P1[PS]?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(model))
    }

    override fun probe(address: String): DiscoveredPrinter? = null

    override fun open(config: PrinterConfig): PrinterSession {
        val serial = config.extras["serial"].orEmpty()
        require(serial.isNotBlank()) { "A Bambu Lab printer needs its serial number." }
        val host = bambuHostAddress(config.identity.address)
        val service = BambuPrinterService(host, serial, config.secret)
        return TransportSession(config.identity, capabilitiesFor(config.identity.model), service, routeFor(host)) { file, name ->
            require(file.name.endsWith(".gcode.3mf")) { "Bambu Lab printers need a .gcode.3mf file." }
            PrinterCommand("Send and print $name", "", bambuPrintRequest = BambuPrintRequest(file, name))
        }
    }
}

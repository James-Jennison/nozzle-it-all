package com.nozzleitall.adapter.elegoo

import com.nozzleitall.printer.*

/**
 * Elegoo Centauri Carbon printers on the LAN, from Elegoo's own open protocol code (elegoo-link, Apache-2.0, and the SDCP
 * V3 documentation; see docs/upstream/PROVENANCE.md):
 *  - Centauri Carbon on stock firmware: SDCP over a WebSocket (port 3030), chunked HTTP upload, CANVAS slots (Cmd 324).
 *  - Centauri Carbon 2 (and Centauri 2): MQTT on the printer's own broker (port 1883) with its access code, chunked HTTP
 *    upload, CANVAS slots (method 2005 or the status's canvas_info).
 * Elegoo's cloud is never used; a Centauri Carbon 2 must be reachable on the LAN (its access code, "123456" when unset).
 * Which protocol a saved printer uses comes from PrinterConfig.extras "protocol" ("sdcp" or "mqtt") when set, else from
 * the model name. Physical-printer status: UNVERIFIED (tested against in-process fakes only).
 */
class ElegooLanAdapter : DeviceAdapter {
    override val id = ID
    override val displayName = "Elegoo Centauri Carbon"
    override val families = setOf(PrinterFamily.ELEGOO)
    override val mayUseVendorCloud = false

    companion object {
        const val ID = "elegoo-lan"

        /** What the SDCP session implements: upload, then start with a slot map; pause, resume and cancel; CANVAS slots read-only. */
        val CC_CAPABILITIES = Capabilities(uploadJob = true, startPrint = true, pausePrint = true, resumePrint = true, cancelPrint = true,
            materialState = true, multiMaterial = true, toolheadState = true, localConnection = true, acceptedOutputs = setOf("gcode"))
        /**
         * The Centauri Carbon 2 session: the same, minus pause and resume. elegoo-link's LAN method table has pause (1021)
         * but no resume, and a pause Nozzle couldn't undo would leave the print stuck until someone reaches the printer.
         */
        val CC2_CAPABILITIES = CC_CAPABILITIES.copy(pausePrint = false, resumePrint = false)

        private val cc2Model = Regex("centauri( carbon)? ?2\\b|\\bcc2\\b", RegexOption.IGNORE_CASE)
        fun usesMqtt(config: PrinterConfig): Boolean = when (config.extras["protocol"]?.lowercase()) {
            "mqtt" -> true
            "sdcp" -> false
            else -> cc2Model.containsMatchIn(config.identity.model)
        }

        private fun elegooModel(raw: String, fallback: String) = raw.ifBlank { fallback }.let { if (it.startsWith("Elegoo", ignoreCase = true)) it else "Elegoo $it" }
    }

    /** Asks the address with both of Elegoo's discovery messages (read-only). Null for a non-private address or no answer. */
    override fun probe(address: String): DiscoveredPrinter? {
        val host = try { ElegooNet.parseHost(address) } catch (e: IllegalArgumentException) { return null }
        val route = routeFor(host.host)
        ElegooDiscovery.askSdcp(host.host)?.let { d ->
            val fw = d.firmwareVersion.ifBlank { null }?.let { ", firmware $it" }.orEmpty()
            return DiscoveredPrinter(host.host, elegooModel(d.machineName, "Centauri Carbon"), PrinterFamily.ELEGOO, ID,
                "Answered Elegoo's printer discovery as \"${d.name.ifBlank { d.machineName }}\"$fw.", route)
        }
        ElegooDiscovery.askCc2(host.host)?.let { d ->
            val code = if (d.accessCodeRequired) " It needs its access code (on the printer's screen)." else ""
            val cloud = if (!d.lanOnly) " It is in cloud mode; Nozzle It All connects on your network only." else ""
            return DiscoveredPrinter(host.host, elegooModel(d.model, "Centauri Carbon 2"), PrinterFamily.ELEGOO, ID,
                "Answered Elegoo's printer discovery as \"${d.name.ifBlank { d.model }}\".$code$cloud", route)
        }
        return null
    }

    override fun open(config: PrinterConfig): PrinterSession {
        val host = ElegooNet.parseHost(config.identity.address)
        val serial = config.extras["serial"]
        return if (usesMqtt(config)) Cc2Session(config.identity, CC2_CAPABILITIES, host, serial, config.secret)
        else SdcpSession(config.identity, CC_CAPABILITIES, host, serial)
    }
}

class ElegooLanAdapterProvider : DeviceAdapterProvider { override fun create() = ElegooLanAdapter() }

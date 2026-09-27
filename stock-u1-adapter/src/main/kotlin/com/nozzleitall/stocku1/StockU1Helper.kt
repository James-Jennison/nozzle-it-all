package com.nozzleitall.stocku1

import com.nozzleitall.adapter.paxx.PaxxLanAdapter
import com.nozzleitall.adapter.paxx.U1LanSession
import com.nozzleitall.printer.*
import com.nozzleitall.printer.external.AdapterServer
import java.io.File
import java.io.PrintStream

const val STOCK_ADAPTER_VERSION = "0.1.0"

/**
 * Stock U1 printers. The stock firmware's own LAN Moonraker carries status, upload and print control, so those keep
 * working with no account and during a Snapmaker outage. The Snapmaker account is held here for stock-only cloud
 * features; none of those is implemented yet, because Snapmaker's cloud device API is not publicly documented.
 */
class StockU1Adapter(private val account: SnapmakerAccount) : DeviceAdapter {
    override val id = "stock-u1"
    override val displayName = "Stock U1 (optional)"
    override val families = setOf(PrinterFamily.STOCK_U1)
    override val mayUseVendorCloud = true

    override fun probe(address: String): DiscoveredPrinter? =
        PaxxLanAdapter().probe(address)?.takeIf { it.suggestedFamily == PrinterFamily.STOCK_U1 }?.copy(adapterId = id)

    override fun open(config: PrinterConfig): PrinterSession {
        require(config.identity.family == PrinterFamily.STOCK_U1) { "The Stock U1 adapter only serves stock-firmware U1 printers." }
        // Stock firmware has no documented LAN camera, and editing tag-read spools needs PAXX's override.
        return U1LanSession(config, Capabilities(uploadJob = true, startPrint = true, pausePrint = true, resumePrint = true, cancelPrint = true,
            temperatures = true, motion = true, camera = false, materialState = true, materialEdit = false, multiMaterial = true, toolheadState = true,
            files = true, jobHistory = true, localConnection = true, remoteConnection = true, vendorCloud = true, requiresVendorAccount = false,
            vendorExtensions = setOf(com.nozzleitall.printer.ext.Snapmaker.FULL_SPECTRUM)))
    }
}

fun stockDataDir(): File {
    System.getenv("NOZZLE_STOCK_DATA_DIR")?.let { return File(it) }
    val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.local/share")
    return File(base, "nozzle-it-all/adapters/stock-u1")
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--version") { println("nozzle-stock-u1-adapter $STOCK_ADAPTER_VERSION (nozzle-adapter protocol 1.x)"); return }
    // stdout carries only protocol lines; anything else goes to stderr.
    val protocolOut = System.out
    System.setOut(PrintStream(System.err, true))
    val account = SnapmakerAccount(stockDataDir())
    AdapterServer(StockU1Adapter(account), account, STOCK_ADAPTER_VERSION).serve(System.`in`, protocolOut)
}

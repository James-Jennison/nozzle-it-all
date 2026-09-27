package com.nozzleitall.printer

import java.io.File

// The device-adapter boundary:
//
//   DeviceAdapter
//   ├── PAXX LAN adapter    (:adapter-paxx, in process, required, LAN-only, offline-capable)
//   └── Stock U1 adapter    (:stock-u1-adapter, a separate helper process, optional, may use a vendor cloud)
//
// The core depends only on this file's interfaces. Out-of-process adapters are reached through
// ExternalAdapterClient (protocol in docs/protocols/ADAPTER_PROTOCOL.md) and are never loaded unless the user enabled
// them, so a missing, broken or signed-out Stock adapter cannot affect a PAXX printer.

/** Saved connection details for one printer. Secrets stay in [secret] and are never logged. */
data class PrinterConfig(
    val identity: PrinterIdentity,
    val adapterId: String,
    val secret: String = "",
    /** Adapter-owned settings, preserved verbatim for adapters the core does not understand. */
    val extras: Map<String, String> = emptyMap(),
)

/** A printer found by a probe or scan, before the user adds it. */
data class DiscoveredPrinter(
    val address: String,
    val model: String,
    val suggestedFirmware: FirmwareFamily,
    val adapterId: String,
    /** Why the firmware was suggested, in plain words, so the user can correct it. */
    val evidence: String,
    val route: ConnectionRoute,
)

fun interface UploadProgress { fun onProgress(sentBytes: Long, totalBytes: Long) }

sealed class UploadResult {
    data class Uploaded(val remotePath: String) : UploadResult()
    data class Failed(val reason: String) : UploadResult()
    /** The connection dropped after data was sent. The file may be partial on the printer; check before printing. */
    data class Interrupted(val reason: String) : UploadResult()
}

interface PrinterSession : AutoCloseable {
    val identity: PrinterIdentity
    val capabilities: Capabilities
    /** Read-only. Never changes printer state. */
    fun status(): PrinterStatus
    fun cameras(): List<CameraEndpoint>
    fun snapshot(camera: CameraEndpoint): ByteArray
    fun upload(file: File, remoteName: String, progress: UploadProgress = UploadProgress { _, _ -> }): UploadResult
    /** Performs an already-confirmed action exactly once. Callers must go through [ActionGuard]. */
    fun perform(action: PrinterAction): ActionOutcome
}

interface DeviceAdapter {
    /** Stable id stored with each printer, for example "paxx-lan" or "stock-u1". */
    val id: String
    val displayName: String
    val firmware: Set<FirmwareFamily>
    /** True if this adapter may contact a vendor cloud. The registry keeps such adapters out of process. */
    val mayUseVendorCloud: Boolean
    /** Probes one address without changing anything on the printer. Null when nothing this adapter serves answers. */
    fun probe(address: String): DiscoveredPrinter?
    fun open(config: PrinterConfig): PrinterSession
}

class AdapterUnavailable(message: String) : Exception(message)

/**
 * Holds the adapters this installation can use. Built-in adapters are registered directly; optional ones are
 * registered as lazy factories and only instantiated when a printer that needs them is opened.
 */
class AdapterRegistry {
    private val builtIn = LinkedHashMap<String, DeviceAdapter>()
    private val optional = LinkedHashMap<String, () -> DeviceAdapter>()
    private val started = HashMap<String, DeviceAdapter>()

    fun registerBuiltIn(adapter: DeviceAdapter) {
        require(!adapter.mayUseVendorCloud) { "Adapters that may use a vendor cloud must be optional and out of process: ${adapter.id}" }
        builtIn[adapter.id] = adapter
    }

    fun registerOptional(id: String, factory: () -> DeviceAdapter) { optional[id] = factory }

    val builtInIds: Set<String> get() = builtIn.keys
    val optionalIds: Set<String> get() = optional.keys
    /** Optional adapters actually running now. Empty in a PAXX-only session. */
    val startedOptionalIds: Set<String> @Synchronized get() = started.keys.toSet()

    @Synchronized
    fun adapter(id: String): DeviceAdapter {
        builtIn[id]?.let { return it }
        started[id]?.let { return it }
        val factory = optional[id] ?: throw AdapterUnavailable("The \"$id\" adapter is not installed or not enabled.")
        return factory().also { started[id] = it }
    }

    /** Probes with built-in adapters only: scanning must never start an optional adapter. */
    fun probeBuiltIn(address: String): List<DiscoveredPrinter> = builtIn.values.mapNotNull { runCatching { it.probe(address) }.getOrNull() }

    fun open(config: PrinterConfig): PrinterSession = adapter(config.adapterId).open(config)
}

/** Classifies an address the user typed. Only the host matters; nothing is resolved or contacted. */
fun routeFor(host: String): ConnectionRoute {
    val h = host.lowercase().trim('[', ']')
    val v4 = h.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 && h.count { c -> c == '.' } == 3 }
    val tailnet = h.endsWith(".ts.net") || h.startsWith("fd7a:115c:a1e0:") || (v4 != null && v4[0] == 100 && v4[1] in 64..127)
    return if (tailnet) ConnectionRoute.PRIVATE_NETWORK else ConnectionRoute.LAN
}

package com.nozzleitall.desktop

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.nozzleitall.printer.*
import com.nozzleitall.printer.external.AccountAdapter
import com.nozzleitall.printer.external.ExternalAdapterClient
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/** Local app settings. Optional Stock U1 support is off unless the user turns it on. */
data class DesktopSettings(val stockU1Enabled: Boolean = false, val theme: String = "system") {
    fun toJson() = JSONObject().put("stockU1Enabled", stockU1Enabled).put("theme", theme)
    companion object {
        fun load(file: File) = runCatching { JSONObject(file.readText()) }.getOrNull()?.let { DesktopSettings(it.optBoolean("stockU1Enabled"), it.optString("theme", "system")) } ?: DesktopSettings()
    }
}

/** Finds the separately installed Stock U1 helper. Nothing here links against it. */
object StockHelperLocator {
    fun find(env: Map<String, String> = System.getenv()): List<String>? {
        env["NOZZLE_STOCK_ADAPTER"]?.let { p -> if (File(p).canExecute()) return listOf(p) }
        val install = System.getProperty("compose.application.resources.dir")?.let { File(it).parentFile?.parentFile }
        val candidates = listOfNotNull(
            install?.let { File(it, "lib/nozzle-stock-u1-adapter/bin/nozzle-stock-u1-adapter") },
            File("/opt/nozzle-stock-u1-adapter/bin/nozzle-stock-u1-adapter"),
            File("/usr/lib/nozzle-stock-u1-adapter/bin/nozzle-stock-u1-adapter"),
        )
        return candidates.firstOrNull { it.canExecute() }?.let { listOf(it.absolutePath) }
    }
}

/** Everything the UI knows about one saved printer. */
class PrinterEntry(val config: PrinterConfig) {
    val status = mutableStateOf(PrinterStatus(PrinterState.CONNECTING, routeFor(runCatching { java.net.URI(config.identity.address.let { if ("://" in it) it else "http://$it" }).host ?: "" }.getOrDefault(""))))
    val capabilities = mutableStateOf<Capabilities?>(null)
    val cameras = mutableStateOf<List<CameraEndpoint>>(emptyList())
    val problem = mutableStateOf<String?>(null)
    @Volatile var session: PrinterSession? = null
    @Volatile var guard: ActionGuard? = null
}

const val EXPORT_ONLY = "export-only"

class Fleet(private val paths: AppPaths, private val scope: CoroutineScope, private val log: (String) -> Unit = {}) {
    private val store = PrinterStore(paths)
    /** Built-in adapters are whatever adapter modules this installation ships (ServiceLoader); none is named here. */
    val registry = AdapterRegistry().apply {
        java.util.ServiceLoader.load(DeviceAdapterProvider::class.java).forEach { p ->
            runCatching { registerBuiltIn(p.create()) }.onFailure { log("adapter not loaded: ${it.message}") }
        }
    }
    val printers = mutableStateMapOf<String, PrinterEntry>()
    val order = mutableStateOf<List<String>>(emptyList())
    val settings = mutableStateOf(DesktopSettings.load(paths.settings))
    private var stockClient: ExternalAdapterClient? = null
    private val pollers = HashMap<String, Job>()

    val stockHelperInstalled: Boolean get() = StockHelperLocator.find() != null
    val stockAccount: AccountAdapter? get() = stockClient

    init {
        applyStockSetting()
        store.load().forEach { add(it, persist = false) }
    }

    /** Registers the Stock helper as an optional, lazily started adapter only when the user enabled it. */
    private fun applyStockSetting() {
        if (!settings.value.stockU1Enabled) return
        val cmd = StockHelperLocator.find() ?: return
        val client = ExternalAdapterClient(cmd, log = { log("stock-u1: $it") })
        stockClient = client
        registry.registerOptional("stock-u1") { client }
    }

    fun setStockEnabled(enabled: Boolean) {
        settings.value = settings.value.copy(stockU1Enabled = enabled)
        PrinterStore.atomicWrite(paths.settings, settings.value.toJson().toString(2), private = false)
        if (enabled) applyStockSetting() else { stockClient?.close(); stockClient = null }
        // Stock printers reconnect (or go offline) according to the new setting; PAXX printers are untouched.
        printers.values.filter { it.config.adapterId == "stock-u1" }.forEach { restart(it.config.identity.id) }
    }

    fun add(config: PrinterConfig, persist: Boolean = true) {
        val entry = PrinterEntry(config)
        printers[config.identity.id] = entry
        order.value = order.value.filter { it != config.identity.id } + config.identity.id
        if (persist) store.save(order.value.mapNotNull { printers[it]?.config })
        startPolling(entry)
    }

    fun update(config: PrinterConfig) { remove(config.identity.id, persist = false); add(config) }

    fun remove(id: String, persist: Boolean = true) {
        pollers.remove(id)?.cancel()
        printers.remove(id)?.session?.let { s -> scope.launch(Dispatchers.IO) { runCatching { s.close() } } }
        order.value = order.value.filter { it != id }
        if (persist) store.save(order.value.mapNotNull { printers[it]?.config })
    }

    private fun restart(id: String) { printers[id]?.let { pollers.remove(id)?.cancel(); it.session = null; it.guard = null; startPolling(it) } }

    private fun startPolling(entry: PrinterEntry) {
        // An export-only printer is a slicing target with no connection: nothing to poll, nothing to control.
        if (entry.config.adapterId == EXPORT_ONLY) {
            entry.capabilities.value = Capabilities.EXPORT_ONLY
            entry.status.value = PrinterStatus(PrinterState.UNKNOWN, ConnectionRoute.NONE, message = "Nozzle slices for this printer but doesn't connect to it.")
            return
        }
        pollers[entry.config.identity.id] = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (entry.session == null) {
                    try {
                        val s = registry.open(entry.config)
                        entry.session = s; entry.guard = ActionGuard(s)
                        entry.capabilities.value = s.capabilities; entry.problem.value = null
                        entry.cameras.value = if (s.capabilities.camera) runCatching { s.cameras() }.getOrDefault(emptyList()) else emptyList()
                    } catch (e: Exception) {
                        entry.problem.value = when {
                            entry.config.adapterId == "stock-u1" && !settings.value.stockU1Enabled -> "Stock U1 support is turned off. Turn it on in Settings to use this printer."
                            entry.config.adapterId == "stock-u1" && !stockHelperInstalled -> "Stock U1 support isn't installed. Install nozzle-stock-u1-adapter to use this printer."
                            else -> e.message ?: "This printer can't be opened."
                        }
                        entry.status.value = entry.status.value.copy(state = PrinterState.OFFLINE, message = entry.problem.value)
                        delay(15_000); continue
                    }
                }
                val s = entry.session ?: continue
                val reading = s.status()
                entry.status.value = reading
                if (reading.state != PrinterState.OFFLINE && entry.cameras.value.isEmpty() && s.capabilities.camera)
                    entry.cameras.value = runCatching { s.cameras() }.getOrDefault(emptyList())
                delay(if (reading.state.isActiveJob) 2_000 else 4_000)
            }
        }
    }

    /** Probes an address with built-in adapters only; the Stock helper is never started by a scan. */
    suspend fun probe(address: String): List<DiscoveredPrinter> = withContext(Dispatchers.IO) { registry.probeBuiltIn(address) }

    fun shutdown() {
        pollers.values.forEach { it.cancel() }
        printers.values.forEach { e -> runCatching { e.session?.close() } }
        stockClient?.close()
    }
}

package net.jamesjennison.klippercompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScreenState(
    val address: String = "", val connected: Boolean = false, val busy: Boolean = false,
    val snapshot: PrinterSnapshot? = null, val catalog: Catalog = Catalog(emptyList(), emptyList(), emptyList(), emptyList()),
    val camera: Bitmap? = null, val cameraNote: String = "", val message: String = "Connect to your printer to begin.",
    val commandNotice: String = "", val lastUpdate: Long = 0, val generation: Int = 0
)
class PrinterModel(
    initialAddress: String = "",
    private val saveAddress: (String) -> Unit = {},
    private val serviceFactory: (String) -> PrinterService = { Moonraker(it) },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val io: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    private val _state = MutableStateFlow(ScreenState(address = initialAddress))
    val state = _state.asStateFlow()
    private var api: PrinterService? = null
    private var job: Job? = null
    private var generation = 0
    private var foreground = false
    private var wantsConnection = false
    private var lastCatalog = 0L
    fun foreground(active: Boolean) {
        foreground = active
        if (!active) { job?.cancel(); api?.close(); _state.value = _state.value.copy(connected = false, snapshot = null, camera = null) }
        else if (wantsConnection) beginLoop()
    }
    fun connect(address: String) {
        if (_state.value.busy) return
        val candidate = try { serviceFactory(address) } catch(e: IllegalArgumentException) { _state.value = _state.value.copy(message = e.message ?: "Invalid address."); return }
        job?.cancel(); api?.close(); generation++
        api = candidate; wantsConnection = true; lastCatalog = 0
        saveAddress(candidate.address)
        _state.value = ScreenState(address = candidate.address, message = "Connecting…", generation = generation)
        if (foreground) beginLoop()
    }
    fun disconnect() {
        if (_state.value.busy) return
        wantsConnection = false; generation++; job?.cancel(); api?.close(); api = null
        _state.value = ScreenState(address = _state.value.address, message = "Disconnected.", generation = generation)
    }
    fun refreshCatalog() { lastCatalog = 0 }
    private fun beginLoop() {
        job?.cancel()
        val service = api ?: return
        val epoch = generation
        job = viewModelScope.launch {
            while (isActive) {
                try {
                    val snapshot = withContext(io) { service.snapshot() }
                    ensureActive()
                    if(epoch != generation) return@launch
                    _state.value = _state.value.copy(connected = true, snapshot = snapshot, lastUpdate = clock(), message = if (_state.value.busy) _state.value.message else if(snapshot.ready) "Live • foreground monitoring" else "Klipper is ${snapshot.state}. Controls unavailable.")
                    if (clock() - lastCatalog > 30_000 || lastCatalog == 0L) {
                        val catalog = withContext(io) { service.catalog() }
                        ensureActive(); if(epoch != generation) return@launch
                        lastCatalog = clock()
                        _state.value = _state.value.copy(catalog = catalog)
                    }
                    val cam = _state.value.catalog.cameras.firstOrNull()
                    if (cam != null && cam.stream.isBlank()) {
                        try {
                            val bitmap = withContext(io) {
                                val bytes = service.image(cam)
                                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                                if(opts.outWidth <= 0 || opts.outHeight <= 0 || opts.outWidth > 8192 || opts.outHeight > 8192) throw ApiFailure("Unsupported camera image.")
                                opts.inJustDecodeBounds = false; opts.inSampleSize = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / 1280)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: throw ApiFailure("Unsupported camera image.")
                            }
                            ensureActive(); if(epoch != generation) return@launch
                            _state.value = _state.value.copy(camera = bitmap, cameraNote = "${cam.name} • refreshed snapshots")
                        } catch(e: CancellationException) { throw e } catch(e: Exception) {
                            _state.value = _state.value.copy(camera = null, cameraNote = "Snapshot unavailable. Check camera configuration and frontend address.")
                        }
                    } else _state.value = _state.value.copy(camera = null, cameraNote = if(cam == null) "No camera configured in Moonraker." else "Live camera")
                } catch(e: CancellationException) { throw e } catch(e: Exception) {
                    if(epoch == generation) _state.value = _state.value.copy(connected = false, snapshot = null, camera = null, message = (if(e is ApiFailure) e.message else "Cannot reach printer.") + " Retrying while this app is open.")
                }
                delay(2_000)
            }
        }
    }
    fun execute(command: PrinterCommand, expectedGeneration: Int) {
        val current = _state.value
        val service = api ?: return
        if (expectedGeneration != generation || !current.connected || current.busy || current.snapshot?.ready != true || clock() - current.lastUpdate > 10_000) {
            _state.value = current.copy(message = "Printer state changed. Refresh before sending a command."); return
        }
        if(command.allowedStates.isNotEmpty() && current.snapshot.state !in command.allowedStates) {
            _state.value = current.copy(message = "This action is unavailable in the current print state."); return
        }
        _state.value = current.copy(busy = true, commandNotice = "Sending command…")
        viewModelScope.launch {
            val message = try {
                withContext(io) {
                    val fresh = service.snapshot()
                    if (!fresh.ready || (command.allowedStates.isNotEmpty() && fresh.state !in command.allowedStates)) throw ApiFailure("Printer state changed.")
                    currentCoroutineContext().ensureActive()
                    service.command(command)
                }
                "Printer acknowledged ${command.title}."
            } catch(e: CancellationException) { throw e } catch(e: Exception) { "Command outcome unknown or rejected. Inspect the printer before trying again. No automatic retry was sent." }
            if(expectedGeneration == generation) _state.value = _state.value.copy(busy = false, commandNotice = message)
        }
    }
    override fun onCleared() { api?.close() }
}

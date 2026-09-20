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
    val commandNotice: String = "", val lastUpdate: Long = 0, val generation: Int = 0,
    val savedPrinters: List<String> = emptyList(),
    val printerConnections: Map<String, PrinterConnection> = emptyMap(),
    val profiles: List<PrinterProfile> = emptyList(), val cameraGeneration: Int = 0,
    val activeMetadata: FileMetadata? = null, val fileMetadata: FileMetadata? = null, val thumbnail: Bitmap? = null,
    val fileNote: String = "", val fileLoading: Boolean = false,
    val history: List<PrintJob> = emptyList(), val historyPageSize: Int = 0, val historyOffset: Int = 0,
    val historyLoading: Boolean = false, val historyNote: String = ""

)
// Every read-only panel/tile builds its own short-lived Moonraker client (or file-transfer
// helper) rather than sharing the main connection's; this is the one place that decides which
// API key it gets, so a newly added call site can't compile while silently going unauthenticated.
fun ScreenState.apiKeyFor(address: String): String = profiles.find { it.address == address }?.apiKey.orEmpty()
fun ScreenState.kindFor(address: String): PrinterKind = profiles.find { it.address == address }?.kind ?: PrinterKind.GENERIC_KLIPPER
fun ScreenState.moonrakerFor(address: String): Moonraker = Moonraker(address, apiKeyFor(address))
class PrinterModel(
    initialAddress: String = "",
    private val saveSettings: (String, List<String>) -> Unit = { _, _ -> },
    serviceFactory: ((String) -> PrinterService)? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    initialPrinters: List<String> = emptyList(),
    initialProfiles: List<PrinterProfile> = emptyList(),
    private val saveProfiles: (String, List<PrinterProfile>) -> Unit = { _, _ -> }

) : ViewModel() {
    private val _state = MutableStateFlow(ScreenState(address = runCatching { Moonraker.parseAddress(initialAddress).toString() }.getOrDefault(""),
        savedPrinters = (initialPrinters + initialAddress).filter { it.isNotBlank() }
            .mapNotNull { runCatching { Moonraker.parseAddress(it).toString() }.getOrNull() }.distinct()))
    // Default factory looks up the profile's API key lazily so tests can still override with a plain fake.
    // Named `resolved...` (not `serviceFactory`) because the constructor parameter of that name stays in
    // scope for every property initializer in this class, and would otherwise shadow a same-named property.
    private val resolvedServiceFactory: (String) -> PrinterService = serviceFactory ?: { address -> Moonraker(address, _state.value.profiles.find { it.address == address }?.apiKey.orEmpty()) }
    init {
        val profiles = initialProfiles.mapNotNull { p -> runCatching { p.copy(address=Moonraker.parseAddress(p.address).toString(), name=p.name.take(80)) }.getOrNull() }.distinctBy { it.address }
        val merged = profiles + _state.value.savedPrinters.filter { a -> profiles.none { it.address == a } }.map { PrinterProfile(it) }
        _state.value = _state.value.copy(profiles=merged, savedPrinters=merged.map { it.address })
    }
    val state = _state.asStateFlow()
    private var detailJob: Job? = null
    private var historyJob: Job? = null
    private var detailEpoch = 0
    private fun persist(address: String = _state.value.address, profiles: List<PrinterProfile> = _state.value.profiles) {
        saveSettings(address, profiles.map { it.address }); saveProfiles(address, profiles)
    }
    fun updateProfile(oldAddress: String, address: String, name: String, apiKey: String, kind: PrinterKind? = null): String? {
        if(_state.value.busy) return "Wait for the current command to finish."
        val normalized = try { Moonraker.parseAddress(address).toString() } catch(_: IllegalArgumentException) { _state.value=_state.value.copy(commandNotice="Enter a valid local printer address.");return "Enter a valid local printer address." }
        val current = _state.value
        if(normalized != oldAddress && current.profiles.any { it.address == normalized }) { _state.value=current.copy(commandNotice="That printer address is already saved.");return "That printer address is already saved." }
        val existing = current.profiles.find { it.address == oldAddress } ?: return "This profile is no longer available."
        val normalizedKey = apiKey.trim().take(200)
        val keyChanged = existing.apiKey != normalizedKey
        // An address change already makes reconcile() tear down and recreate the saved-printer
        // session under its new key; only a same-address key edit needs an explicit nudge here —
        // for the connected printer via disconnect(), for a background one via savedMonitor.remove().
        if(oldAddress == current.address) { if(oldAddress != normalized || keyChanged) disconnect() }
        else if(normalized == oldAddress && keyChanged) savedMonitor.remove(oldAddress)
        val profiles = current.profiles.map { if(it.address == oldAddress) it.copy(address=normalized,name=name.trim().take(80),cameraId=if(normalized==oldAddress) it.cameraId else "",apiKey=normalizedKey,kind=kind ?: it.kind) else it }
        val selected = if(current.address == oldAddress) normalized else _state.value.address
        _state.value = _state.value.copy(address=selected,profiles=profiles,savedPrinters=profiles.map { it.address })
        persist()
        monitorSavedPrinters()
        return null
    }
    fun favoriteProfile(address: String) {
        if(_state.value.busy) return
        val profiles = _state.value.profiles.map { if(it.address==address) it.copy(favorite=!it.favorite) else it }
        _state.value=_state.value.copy(profiles=profiles);persist()
    }
    fun moveProfile(address: String, delta: Int) {
        if(_state.value.busy) return
        val profiles=_state.value.profiles.toMutableList();val from=profiles.indexOfFirst { it.address==address };val to=from+delta
        if(from !in profiles.indices || to !in profiles.indices) return
        profiles.add(to,profiles.removeAt(from));_state.value=_state.value.copy(profiles=profiles,savedPrinters=profiles.map { it.address });persist()
    }
    fun selectCamera(id: String) {
        if(_state.value.catalog.cameras.none { it.id==id }) return
        val profiles=_state.value.profiles.map { if(it.address==_state.value.address) it.copy(cameraId=id) else it }
        _state.value=_state.value.copy(profiles=profiles,camera=null,cameraNote="",cameraGeneration=_state.value.cameraGeneration+1);persist()
    }
    fun selectFile(filename: String) {
        detailJob?.cancel();val ticket=++detailEpoch;val epoch=generation;val service=api ?: return
        _state.value=_state.value.copy(fileMetadata=null,thumbnail=null,fileNote="",fileLoading=true)
        detailJob=viewModelScope.launch {
            try {
                val metadata=withContext(io) { service.metadata(filename) };ensureActive()
                if(epoch!=generation || ticket!=detailEpoch) return@launch
                _state.value=_state.value.copy(fileMetadata=metadata,fileLoading=false)
                metadata.thumbnail?.let { path ->
                    val image=withContext(io) { decodeImage(service.thumbnail(path)) };ensureActive()
                    if(epoch==generation && ticket==detailEpoch) _state.value=_state.value.copy(thumbnail=image)
                }
            } catch(e: CancellationException) { throw e } catch(_: Exception) {
                if(epoch==generation && ticket==detailEpoch) _state.value=_state.value.copy(fileLoading=false,fileNote="Metadata or thumbnail unavailable.")
            }
        }
    }
    fun loadHistory(start: Int = 0) {
        if(start < 0) return
        historyJob?.cancel();val epoch=generation;val service=api ?: return
        _state.value=_state.value.copy(history=emptyList(),historyPageSize=0,historyLoading=true,historyNote="",historyOffset=start)
        historyJob=viewModelScope.launch {
            try {
                val result=withContext(io) { service.history(start) };ensureActive()
                if(epoch==generation) _state.value=_state.value.copy(history=result.jobs,historyPageSize=result.pageSize,historyLoading=false)
            } catch(e: CancellationException) { throw e } catch(_: Exception) {
                if(epoch==generation) _state.value=_state.value.copy(historyLoading=false,historyNote="History unavailable. This printer may not have history enabled.")
            }
        }
    }
    private fun decodeImage(bytes: ByteArray): Bitmap {
        val opts=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)
        if(opts.outWidth !in 1..8192 || opts.outHeight !in 1..8192) throw ApiFailure("Unsupported image.")
        opts.inJustDecodeBounds=false;opts.inSampleSize=maxOf(1,maxOf(opts.outWidth,opts.outHeight)/1024)
        return BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts) ?: throw ApiFailure("Unsupported image.")
    }

    private var api: PrinterService? = null
    private var job: Job? = null
    private var commandJob: Job? = null
    private var generation = 0
    private var foreground = false
    private var wantsConnection = false
    private var startupPending = true
    private val disconnectedPrinters = mutableSetOf<String>()
    private val savedMonitor = SavedPrinterMonitor(viewModelScope, io, resolvedServiceFactory) {
        _state.value = _state.value.copy(printerConnections = it)
    }
    private fun monitorSavedPrinters() {
        savedMonitor.reconcile(if (foreground) _state.value.savedPrinters.toSet() -
            disconnectedPrinters - (if (wantsConnection) setOf(_state.value.address) else emptySet()) else emptySet())
    }
    private var lastCatalog = 0L
    private var metadataAttempt = 0L
    private var metadataFilename = ""
    fun foreground(active: Boolean) {
        if (foreground == active) return
        foreground = active
        if (!active) {
            val interrupted = _state.value.busy
            commandJob?.cancel()
            detailJob?.cancel();historyJob?.cancel(); job?.cancel(); api?.close()
            _state.value = _state.value.copy(connected = false, snapshot = null, camera = null, fileLoading=false, historyLoading=false,
                commandNotice = if (interrupted) "Command interrupted. Outcome unknown; inspect the printer before trying again. No automatic retry was sent." else _state.value.commandNotice)
        }
        else if (startupPending) {
            startupPending = false
            val selected = _state.value.address.ifBlank { _state.value.savedPrinters.firstOrNull().orEmpty() }
            if (selected.isNotBlank()) connect(selected)
        } else if (wantsConnection) beginLoop()
        monitorSavedPrinters()
    }
    fun connect(address: String) {
        if (_state.value.busy) return
        val candidate = try { resolvedServiceFactory(address) } catch(e: IllegalArgumentException) { _state.value = _state.value.copy(message = e.message ?: "Invalid address."); return }
        if (_state.value.connected && candidate.address == _state.value.address) { candidate.close(); return }
        detailJob?.cancel();historyJob?.cancel();job?.cancel(); api?.close(); generation++
        startupPending = false
        disconnectedPrinters.remove(candidate.address)
        savedMonitor.remove(candidate.address)
        api = candidate; wantsConnection = true; lastCatalog = 0;metadataAttempt=0;metadataFilename=""
        val printers = (_state.value.savedPrinters + candidate.address).distinct()
        val profiles = _state.value.profiles + printers.filter { a -> _state.value.profiles.none { it.address==a } }.map { PrinterProfile(it) }
        persist(candidate.address,profiles)
        _state.value = ScreenState(address = candidate.address, message = "Connecting…", generation = generation, savedPrinters = printers, profiles=profiles, printerConnections=_state.value.printerConnections)
        if (foreground) beginLoop()
        monitorSavedPrinters()
    }
    fun disconnect() {
        if (_state.value.busy) return
        startupPending = false
        disconnectedPrinters.add(_state.value.address)
        wantsConnection = false; generation++; detailJob?.cancel();historyJob?.cancel();job?.cancel(); api?.close(); api = null
        _state.value = ScreenState(address = _state.value.address, message = "Disconnected.", generation = generation, savedPrinters = _state.value.savedPrinters, profiles=_state.value.profiles, printerConnections=_state.value.printerConnections)
        monitorSavedPrinters()
    }
    fun forgetPrinter(address: String) {
        if (_state.value.busy || address !in _state.value.savedPrinters) return
        if (address == _state.value.address) {
            disconnect()
            _state.value = _state.value.copy(address = "")
        }
        val printers = _state.value.savedPrinters - address
        val profiles=_state.value.profiles.filter { it.address!=address }
        _state.value = _state.value.copy(savedPrinters = printers,profiles=profiles);persist()
        monitorSavedPrinters()
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
                    if(_state.value.activeMetadata?.filename != snapshot.filename) _state.value=_state.value.copy(activeMetadata=null)
                    if(snapshot.filename.isNotBlank() && (metadataFilename!=snapshot.filename || clock()-metadataAttempt>30_000)) {
                        metadataFilename=snapshot.filename;metadataAttempt=clock()
                        val metadata=withContext(io) { runCatching { service.metadata(snapshot.filename) }.getOrNull() };ensureActive()
                        if(epoch!=generation) return@launch
                        _state.value=_state.value.copy(activeMetadata=metadata)
                    }
                    if (clock() - lastCatalog > 30_000 || lastCatalog == 0L) {
                        val catalog = withContext(io) { service.catalog() }
                        ensureActive(); if(epoch != generation) return@launch
                        lastCatalog = clock()
                        val previousCamera=_state.value.selectedCamera()
                        val updated=_state.value.copy(catalog=catalog)
                        _state.value=if(previousCamera!=updated.selectedCamera()) updated.copy(camera=null,cameraNote="",cameraGeneration=updated.cameraGeneration+1) else updated
                    }
                    val cameraEpoch=_state.value.cameraGeneration
                    val cam = _state.value.selectedCamera()
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
                            if(cameraEpoch != _state.value.cameraGeneration) continue
                            _state.value = _state.value.copy(camera = bitmap, cameraNote = "${cam.name} • refreshed snapshots")
                        } catch(e: CancellationException) { throw e } catch(e: Exception) {
                            if(cameraEpoch == _state.value.cameraGeneration) _state.value = _state.value.copy(camera = null, cameraNote = "Snapshot unavailable. Check camera configuration and frontend address.")
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
        if (!foreground || expectedGeneration != generation || !current.connected || current.busy || current.snapshot?.ready != true || clock() - current.lastUpdate > 10_000) {
            _state.value = current.copy(commandNotice = "Printer state changed. Refresh before sending a command."); return
        }
        if(command.allowedStates.isNotEmpty() && current.snapshot.state !in command.allowedStates) {
            _state.value = current.copy(commandNotice = "This action is unavailable in the current print state."); return
        }
        _state.value = current.copy(busy = true, commandNotice = "Sending command…")
        commandJob = viewModelScope.launch {
            val message = try {
                withContext(io) {
                    val fresh = service.snapshot()
                    if (!fresh.ready || (command.allowedStates.isNotEmpty() && fresh.state !in command.allowedStates)) throw ApiFailure("Printer state changed.")
                    command.heaterRequest?.let { request ->
                        val checked=HeaterControls.prepare(request,service.heaterStatus(request.heater))
                        if(checked!=command) throw ApiFailure("Heater command changed. Review it again.")
                    }
                    command.fanRequest?.let { request ->
                        val checked=FanControls.prepare(request,service.fanStatus(request.fan))
                        if(checked!=command) throw ApiFailure("Fan command changed. Review it again.")
                    }
                    command.speedFlowRequest?.let { request ->
                        val checked=SpeedFlowControls.prepare(request,service.speedFlowStatus())
                        if(checked!=command) throw ApiFailure("Speed/flow command changed. Review it again.")
                    }
                    command.macroRequest?.let { request ->
                        val checked=MacroTools.prepare(request,service.macroStatus(request.name))
                        if(checked!=command) throw ApiFailure("Macro command changed. Review it again.")
                    }
                    command.ledRequest?.let { request ->
                        val checked=LedControls.prepare(request,service.ledStatus(request.led))
                        if(checked!=command) throw ApiFailure("Light command changed. Review it again.")
                    }
                    command.toolRequest?.let { request ->
                        val checked=ToolControls.prepare(request,service.toolStatus())
                        if(checked!=command) throw ApiFailure("Tool command changed. Review it again.")
                    }
                    currentCoroutineContext().ensureActive()
                    service.command(command)
                }
                "Printer acknowledged ${command.title}."
            } catch(e: CancellationException) { throw e } catch(e: Exception) { "Command outcome unknown or rejected. Inspect the printer before trying again. No automatic retry was sent." }
            if(expectedGeneration == generation) _state.value = _state.value.copy(busy = false, commandNotice = message)
        }.also { pending ->
            pending.invokeOnCompletion {
                if(expectedGeneration == generation) _state.value = _state.value.copy(busy = false)
            }
        }
    }
    override fun onCleared() { savedMonitor.stop(); api?.close() }
}

fun ScreenState.selectedCamera(): Camera? {
    val id=profiles.firstOrNull { it.address==address }?.cameraId
    return if(id.isNullOrBlank()) catalog.cameras.firstOrNull() else catalog.cameras.firstOrNull { it.id==id }
}

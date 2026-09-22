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
// Klipper-only panels build these; a BAMBU_LAB printer never reaches them because MainActivity
// hides every control that needs one (BambuPrinterService implements none of the reader interfaces).
fun ScreenState.moonrakerFor(address: String): Moonraker = Moonraker(address, apiKeyFor(address))
// A Bambu profile's address is a bare host - it has no HTTP endpoint for a URL to point at - so it
// is validated by bambuHostAddress rather than Moonraker.parseAddress. Both throw
// IllegalArgumentException, so every call site keeps its existing failure handling.
internal fun normalizedAddress(address: String, kind: PrinterKind): String =
    // PRUSA_LINK is plain HTTP on the local network too, same shape as a Moonraker address (just a
    // different API path) - Moonraker.parseAddress's local-network validation applies unchanged.
    if(kind == PrinterKind.BAMBU_LAB) bambuHostAddress(address) else Moonraker.parseAddress(address).toString()
// The one place that decides which transport a saved printer actually gets. A BAMBU_LAB profile
// speaks nothing Moonraker understands (MQTT/FTPS/port-6000 camera); a PRUSA_LINK profile speaks
// PrusaLink's own digest-authenticated REST API, not Moonraker's JSON-RPC-over-HTTP - both get
// their own service. Every other kind keeps the Moonraker client. Throws IllegalArgumentException
// on a bad address, exactly as Moonraker's own constructor does, so connect() reports it the same way.
internal fun printerServiceFor(profile: PrinterProfile?, address: String): PrinterService = when (profile?.kind) {
    // profile.apiKey is reused as the Bambu access code for BAMBU_LAB and the Prusa Link password
    // for PRUSA_LINK - same class of secret (a control-granting credential) either way, so both
    // get the same encrypted storage slot rather than a new field.
    PrinterKind.BAMBU_LAB -> BambuPrinterService(bambuHostAddress(address), profile.serial, profile.apiKey)
    PrinterKind.PRUSA_LINK -> PrusaLinkPrinterService(address, profile.apiKey)
    else -> Moonraker(address, profile?.apiKey.orEmpty())
}
private fun kindOf(profiles: List<PrinterProfile>, address: String): PrinterKind = profiles.find { it.address == address }?.kind ?: PrinterKind.GENERIC_KLIPPER
// Shared with PrintMonitorService's own consecutive-failure debounce (ConnectionDebounce.kt) -
// same tolerance for the same reason: a single blip (mobile network handoff, VPN re-handshake)
// should not read as a real disconnect in either the foreground dashboard or a background alert.
const val CONSECUTIVE_FAILURE_TOLERANCE = 2
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
    private val _state = MutableStateFlow(ScreenState(address = runCatching { normalizedAddress(initialAddress, kindOf(initialProfiles, initialAddress)) }.getOrDefault(""),
        savedPrinters = (initialPrinters + initialAddress).filter { it.isNotBlank() }
            .mapNotNull { runCatching { normalizedAddress(it, kindOf(initialProfiles, it)) }.getOrNull() }.distinct()))
    // Default factory looks up the profile's API key lazily so tests can still override with a plain fake.
    // Named `resolved...` (not `serviceFactory`) because the constructor parameter of that name stays in
    // scope for every property initializer in this class, and would otherwise shadow a same-named property.
    private val resolvedServiceFactory: (String) -> PrinterService = serviceFactory ?: { address -> printerServiceFor(_state.value.profiles.find { it.address == address }, address) }
    init {
        val profiles = initialProfiles.mapNotNull { p -> runCatching { p.copy(address=normalizedAddress(p.address, p.kind), name=p.name.take(80)) }.getOrNull() }.distinctBy { it.address }
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
    // AddPrinterWizard's own save path - distinct from connect()'s implicit bare-profile
    // creation (which only ever produces PrinterProfile(address), no name/kind/slicing model)
    // and from updateProfile (which requires an existing profile to find by address). The
    // wizard already normalizes and live-tests everything before calling this, so this is a
    // thin, final commit step, not a second round of validation.
    fun addProfile(profile: PrinterProfile): String? {
        if(_state.value.busy) return "Wait for the current command to finish."
        val current = _state.value
        if(current.profiles.any { it.address == profile.address }) return "That printer address is already saved."
        val profiles = current.profiles + profile
        _state.value = current.copy(profiles = profiles, savedPrinters = profiles.map { it.address })
        persist(profiles = profiles)
        monitorSavedPrinters()
        return null
    }
    fun updateProfile(oldAddress: String, address: String, name: String, apiKey: String, kind: PrinterKind? = null, serial: String? = null, slicingModel: SlicingPrinterModel? = null): String? {
        if(_state.value.busy) return "Wait for the current command to finish."
        val current = _state.value
        // The profile is resolved before the address is: which validator applies depends on the kind
        // being saved, and a Bambu profile's bare host is not an address parseAddress can read.
        val existing = current.profiles.find { it.address == oldAddress } ?: return "This profile is no longer available."
        val normalizedKind = kind ?: existing.kind
        val normalized = try { normalizedAddress(address, normalizedKind) } catch(_: IllegalArgumentException) { _state.value=current.copy(commandNotice="Enter a valid local printer address.");return "Enter a valid local printer address." }
        if(normalized != oldAddress && current.profiles.any { it.address == normalized }) { _state.value=current.copy(commandNotice="That printer address is already saved.");return "That printer address is already saved." }
        val normalizedKey = apiKey.trim().take(200)
        val keyChanged = existing.apiKey != normalizedKey
        // An address change already makes reconcile() tear down and recreate the saved-printer
        // session under its new key; only a same-address key edit needs an explicit nudge here —
        // for the connected printer via disconnect(), for a background one via savedMonitor.remove().
        if(oldAddress == current.address) { if(oldAddress != normalized || keyChanged) disconnect() }
        else if(normalized == oldAddress && keyChanged) savedMonitor.remove(oldAddress)
        // Changing which slicing profile family a printer maps to invalidates any previously
        // confirmed firmware declaration - WO-13's declaredFirmwareVersion is specifically a
        // confirmation *for a given slicingModel*, not a fact about the printer in isolation.
        val newSlicingModel = slicingModel ?: existing.slicingModel
        val declaredFirmwareVersion = if (newSlicingModel != existing.slicingModel) "" else existing.declaredFirmwareVersion
        val profiles = current.profiles.map { if(it.address == oldAddress) it.copy(address=normalized,name=name.trim().take(80),cameraId=if(normalized==oldAddress) it.cameraId else "",apiKey=normalizedKey,kind=normalizedKind,serial=(serial ?: it.serial).trim().take(40),slicingModel=newSlicingModel,declaredFirmwareVersion=declaredFirmwareVersion) else it }
        val selected = if(current.address == oldAddress) normalized else _state.value.address
        _state.value = _state.value.copy(address=selected,profiles=profiles,savedPrinters=profiles.map { it.address })
        persist()
        monitorSavedPrinters()
        return null
    }
    // WO-13: a live-only read, deliberately separate from updateProfile - never guesses or
    // defaults a firmware declaration, only ever records what a real printer just reported (see
    // FirmwareIdentity.kt's own header comment on why a stale/cached value is unsafe here).
    // Callable for any saved profile, not just the currently connected one, since firmware needs
    // to be confirmed before a printer is ever actively connected to slice for it.
    fun detectFirmware(address: String, onResult: (Result<FirmwareIdentity>) -> Unit) {
        if (_state.value.profiles.none { it.address == address }) { onResult(Result.failure(ApiFailure("This profile is no longer available."))); return }
        viewModelScope.launch {
            val result = try {
                val identity = withContext(io) {
                    val service = resolvedServiceFactory(address)
                    try { service.firmwareIdentity() } finally { runCatching { service.close() } }
                }
                Result.success(identity)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
            result.getOrNull()?.let { identity ->
                val profiles = _state.value.profiles.map { if (it.address == address) it.copy(declaredFirmwareVersion = identity.version) else it }
                _state.value = _state.value.copy(profiles = profiles)
                persist()
            }
            onResult(result)
        }
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
        // Tolerates a single transient poll failure (a mobile-network handoff, a VPN/Tailscale
        // re-handshake after switching towers) without flipping the dashboard to "Cannot reach
        // printer" - confirmed against a real device dropping every few minutes on Tailscale over
        // 5G, solid on WiFi. Only a second consecutive failure (~2-4s later, given the 2s delay
        // below) is treated as a real disconnect; a single blip that recovers on the very next
        // poll never touches _state.value at all.
        var consecutiveFailures = 0
        job = viewModelScope.launch {
            while (isActive) {
                try {
                    val snapshot = withContext(io) { service.snapshot() }
                    ensureActive()
                    if(epoch != generation) return@launch
                    consecutiveFailures = 0
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
                    consecutiveFailures++
                    if(epoch == generation && consecutiveFailures >= CONSECUTIVE_FAILURE_TOLERANCE) _state.value = _state.value.copy(connected = false, snapshot = null, camera = null, message = (if(e is ApiFailure) e.message else "Cannot reach printer.") + " Retrying while this app is open.")
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
                    command.pandaBreathRequest?.let { request ->
                        val observed=service.pandaBreathStatus()
                        val checked=when(request) {
                            is PandaBreathRequest.SetTarget -> PandaBreathControls.prepareSetTarget(request,observed)
                            is PandaBreathRequest.SetAuto -> PandaBreathControls.prepareAuto(request,observed)
                            is PandaBreathRequest.Dry -> PandaBreathControls.prepareDry(request,observed)
                            is PandaBreathRequest.Stop -> PandaBreathControls.prepareStop(observed)
                        }
                        if(checked!=command) throw ApiFailure("Panda Breath command changed. Review it again.")
                    }
                    command.aceRequest?.let { request ->
                        val observed=service.aceStatus()
                        val checked=when(request) {
                            is AceRequest.Load -> AceControls.prepareLoad(request,observed)
                            is AceRequest.Unload -> AceControls.prepareUnload(request,observed)
                            is AceRequest.UnloadAll -> AceControls.prepareUnloadAll(observed)
                            is AceRequest.DryStart -> AceControls.prepareDryStart(request,observed)
                            is AceRequest.DryStop -> AceControls.prepareDryStop(request,observed)
                            is AceRequest.Switch -> AceControls.prepareSwitch(request,observed)
                        }
                        if(checked!=command) throw ApiFailure("multiACE command changed. Review it again.")
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
    /**
     * Deliberately bypasses every gate execute() applies (foreground, generation, connected,
     * busy, ready, allowedStates). An emergency stop exists precisely for the moment those
     * conditions are wrong - printer busy mid-command, Klippy not ready, a stuck request - so
     * gating it the same way as an ordinary command could block it exactly when it's needed.
     * Referenced against Helix's own emergencyStop action (hooks/useDashboardModel.ts), which
     * fires immediately with no state precondition; this differs only in transport (this app has
     * no WebSocket channel to also fire over, and no second saved URL yet to spray it at - see
     * FEATURE_PARITY_ROADMAP.md's P16 addendum).
     */
    fun emergencyStop() {
        val service = api ?: return
        _state.value = _state.value.copy(commandNotice = "Sending emergency stop…")
        viewModelScope.launch {
            val message = try {
                withContext(io) { service.command(PrinterCommand("Emergency stop", "printer/emergency_stop")); "Printer acknowledged Emergency stop." }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { "Emergency stop outcome unknown or rejected. Inspect the printer before trying again. No automatic retry was sent." }
            _state.value = _state.value.copy(commandNotice = message)
        }
    }
    // The command-notice banner (MainActivity's snackbarHost) has no other owner to clear it -
    // it used to just sit there until the next command overwrote it, effectively a permanent
    // banner for anything the user didn't immediately act on again. Called both by an explicit
    // dismiss tap and by MainActivity's own auto-dismiss timer.
    fun dismissCommandNotice() { _state.value = _state.value.copy(commandNotice = "") }
    override fun onCleared() { savedMonitor.stop(); api?.close() }
}

fun ScreenState.selectedCamera(): Camera? {
    val id=profiles.firstOrNull { it.address==address }?.cameraId
    return if(id.isNullOrBlank()) catalog.cameras.firstOrNull() else catalog.cameras.firstOrNull { it.id==id }
}

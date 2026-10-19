package com.nozzleitall.environment

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel as Queue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.update

/** dropped: this coordinator's cumulative ingress-overflow count (including discovery).
 * recordedDrops: retained collection-history gaps across runs. Neither implies complete radio coverage. */
data class EnvironmentalView(val state: EnvironmentalState, val status: String = "Disabled — no collection", val candidates: List<Candidate> = emptyList(),
    val bindings: List<Binding> = emptyList(), val latest: Map<String, Observation> = emptyMap(), val dropped: Long = 0, val recordedDrops: Long = 0,
    val roomLabels: Map<String, Pair<String, String>> = emptyMap(),
    val discovery: Map<String, DiscoveryReception> = emptyMap(), val sensorLabels: Map<String, String> = emptyMap())

/** No scope, worker, transport, DB open or permission request is created by construction/enable. */
class EnvironmentalCoordinator(control: File, private val history: EnvironmentalHistory, private val transport: () -> BleTransport,
    private val limits: EnvironmentalLimits = EnvironmentalLimits(), private val platformIdentity: String = "unqualified") : AutoCloseable {
    val gate = EnvironmentalGate(control)
    private val mutable = MutableStateFlow(EnvironmentalView(gate.state, if (gate.state == EnvironmentalState.ENABLED_IDLE) "Enabled, idle — press Discover or Start" else if (gate.state == EnvironmentalState.DISABLED_RECOVERY) "Recovery required — control record preserved; Delete / resume cleanup explicitly" else "Disabled — no collection"))
    val view = mutable.asStateFlow()
    @Volatile private var operation: Job? = null
    @Volatile private var operationScope: CoroutineScope? = null
    private class PreparationControl(val serial: Long, val epoch: Long, val revoked: AtomicBoolean = AtomicBoolean(false)) {
        @Volatile var worker: Job? = null
    }
    private class RunControl(val job: Job, val revoked: AtomicBoolean, val serial: Long, val epoch: Long)
    private class StopRequest(val serial: Long, val disable: Boolean, val reason: String)
    @Volatile private var runControl: RunControl? = null
    private val preparations = AtomicReference<PreparationControl?>()
    private val commands = AtomicLong()
    private val stoppedThrough = AtomicLong()
    private var session: RadioSession? = null
    private var gapSession: String? = null
    private var gapDropBase = 0L
    private val foreground = AtomicBoolean(false)
    private val visibilityEpoch = AtomicLong()
    private val disableRequested = AtomicBoolean(false)
    private val disableInFlight = AtomicInteger()
    private val deleteInFlight = AtomicInteger()
    private val droppedReports = AtomicLong()
    private val disposed = AtomicBoolean(false)
    private val collector get() = gate.collectorId
    private val sequence = AtomicLong()
    fun foreground(visible: Boolean) {
        if (visible) foreground.set(true) else loseForeground()
    }
    private fun loseForeground(): PreparationControl? {
        foreground.set(false)
        val epoch = visibilityEpoch.incrementAndGet()
        val preparation = preparations.get()?.takeIf { it.epoch < epoch }
        preparation?.revoked?.set(true)
        preparation?.worker?.cancel()
        if (preparation != null) preparations.compareAndSet(preparation, null)
        val current = runControl?.takeIf { it.epoch < epoch }
        if (current != null) {
            current.revoked.set(true)
            mutable.update { if (runControl === current) it.copy(latest = emptyMap(), candidates = emptyList(), discovery = emptyMap(), status = "Stop requested — app not visible; cleanup pending") else it }
            operationScope?.launch { synchronized(this@EnvironmentalCoordinator) {
                if (runControl === current) stop("Stopped — app not visible; press Start to resume")
            } }
        } else if (preparation != null) mutable.update {
            if (!foreground.get() && runControl == null) it.copy(latest = emptyMap(), candidates = emptyList(), discovery = emptyMap(), status = "Collection preparation revoked — no radio; press Start to retry") else it
        }
        return preparation
    }
    @Synchronized fun enable() {
        check(!disposed.get() && operation == null)
        check(preparations.get() == null) { "Preparation cleanup is pending" }
        check(disableInFlight.get() == 0) { "Disable cleanup is pending" }
        check(!disableRequested.get() || gate.state == EnvironmentalState.DISABLED)
        gate.enable(); if (disableInFlight.get() == 0) disableRequested.set(false)
        mutable.value = mutable.value.copy(state = gate.state, status = "Enabled, idle — no radio or history opened")
    }
    fun stop(reason: String = "Stopped — no collection", disable: Boolean = false) {
        finishStop(prepareStop(reason, disable))
    }
    private fun prepareStop(reason: String, disable: Boolean): StopRequest {
        if (disable) disableInFlight.incrementAndGet()
        val serial = commands.incrementAndGet()
        stoppedThrough.updateAndGet { maxOf(it, serial) }
        val target = runControl?.takeIf { it.serial < serial }
        val preparation = preparations.get()?.takeIf { it.serial < serial }
        if (disable) disableRequested.set(true) // Global Off intent supersedes every run; ordinary Stop owns only target.
        target?.revoked?.set(true)
        preparation?.revoked?.set(true)
        // Main-safe prelude, before dispatcher scheduling or waiting for any persistence lock.
        if (target != null || preparation != null) mutable.update {
            if ((target != null && runControl === target) || (preparation != null && (preparations.get() === preparation || runControl?.revoked === preparation.revoked)))
                it.copy(latest = emptyMap(), candidates = emptyList(), discovery = emptyMap(), status = "Stop requested — cleanup pending") else it
        }
        preparation?.worker?.cancel()
        if (preparation != null) preparations.compareAndSet(preparation, null)
        return StopRequest(serial, disable, reason)
    }
    private fun finishStop(request: StopRequest) {
        val disable = request.disable; val reason = request.reason
        try {
            synchronized(this) {
                if (!disable && ((runControl?.serial ?: Long.MIN_VALUE) > request.serial ||
                    (runControl == null && (preparations.get()?.serial ?: Long.MIN_VALUE) > request.serial))) return // Never owns a younger gesture.
                if (disable) disableRequested.set(true)
                runControl?.revoked?.set(true)
                try { gate.revoke(disable) }
                finally {
                    operation?.cancel(); operation = null; operationScope = null; runControl = null
                    runCatching { session?.close() }; session = null
                    val gapId = gapSession; gapSession = null
                    val drops = droppedReports.get() - gapDropBase
                    val gapFailed = gapId != null && drops > 0 && runCatching { history.recordGap(gapId, System.currentTimeMillis(), drops) }.isFailure
                    mutable.value = mutable.value.copy(state = gate.state, latest = emptyMap(), candidates = emptyList(), discovery = emptyMap(), dropped = droppedReports.get(), status = when {
                        gate.persistenceFailure -> "Persistence failed — stopped in memory, recovery required"
                        gapFailed -> "Stopped — final overflow count could not be persisted; history coverage incomplete"
                        else -> reason
                    })
                }
            }
        } finally { if (disable) disableInFlight.decrementAndGet() }
    }
    /** Explicit UI gesture: immediate atomics on Main; finite owned cleanup survives composition cancellation. */
    fun requestStopAsync(reason: String = "Stopped — no collection", disable: Boolean = false): Job {
        val request = prepareStop(reason, disable)
        val owner = SupervisorJob()
        return CoroutineScope(owner + Dispatchers.IO).launch {
            try { finishStop(request) }
            catch (_: Exception) { synchronized(this@EnvironmentalCoordinator) {
                if (gate.state == EnvironmentalState.DISABLED_RECOVERY && runControl == null)
                    mutable.update { it.copy(status = "Stop requested — persistence/recovery unavailable; no collection") }
            } }
            finally { owner.cancel() }
        }
    }
    /** A reusable app-owned coordinator releases a panel's resources without becoming permanently disposed. */
    fun releaseForegroundAsync(): Job? {
        val preparation = loseForeground()
        val epoch = visibilityEpoch.get()
        if (operation == null && !history.isOpen && preparation == null) return null
        val owner = SupervisorJob()
        return CoroutineScope(owner + Dispatchers.IO).launch {
            try { synchronized(this@EnvironmentalCoordinator) {
                if (!foreground.get() && visibilityEpoch.get() == epoch) {
                    try { if (operation != null) stop("Stopped — app not visible; press Start to resume") }
                    finally { history.close() }
                }
            } } catch (_: Exception) { /* Gate already closed; no hidden retry or provider restart. */ }
            finally { owner.cancel() }
        }
    }
    private fun preparationCurrent(p: PreparationControl) = preparations.get() === p && p.serial > stoppedThrough.get() && !p.revoked.get() && foreground.get() &&
        visibilityEpoch.get() == p.epoch && disableInFlight.get() == 0 && !disableRequested.get() && !disposed.get()
    private fun reservePreparation(): PreparationControl {
        val p = PreparationControl(commands.incrementAndGet(), visibilityEpoch.get())
        check(foreground.get() && disableInFlight.get() == 0 && !disableRequested.get() && !disposed.get() && mutable.value.state == EnvironmentalState.ENABLED_IDLE)
        check(preparations.compareAndSet(null, p)) { "Another preparation is pending" }
        if (!preparationCurrent(p)) { preparations.compareAndSet(p, null); error("Preparation revoked") }
        mutable.update { if (preparations.get() === p) it.copy(status = "Preparing — Stop / cancel remains available") else it }
        return p
    }
    fun discover() = runSynchronousPreparation(Purpose.DISCOVERY)
    fun start() = runSynchronousPreparation(Purpose.COLLECTION)
    private fun runSynchronousPreparation(purpose: Purpose) {
        val p = reservePreparation()
        try { runPreparation(purpose, p) } finally { preparations.compareAndSet(p, null) }
    }
    /** Reserve gesture ownership on Main before scheduling any IO, so a later Stop cancels queued older work. */
    fun requestOperationAsync(purpose: Purpose): Job {
        val p = reservePreparation()
        val owner = SupervisorJob()
        val worker = CoroutineScope(owner + Dispatchers.IO).launch {
            try { runPreparation(purpose, p) }
            catch (_: CancellationException) { /* Revoked gesture; no automatic retry. */ }
            catch (e: Exception) { synchronized(this@EnvironmentalCoordinator) {
                if (preparations.get() === p && runControl == null) mutable.update { it.copy(state = gate.state,
                    status = if (e is QualificationUnavailableException) e.message!! else "Preparation unavailable or cancelled — no automatic retry") }
            } }
        }
        p.worker = worker
        worker.invokeOnCompletion { preparations.compareAndSet(p, null); owner.cancel() }
        if (p.revoked.get()) worker.cancel()
        return worker
    }
    private fun runPreparation(purpose: Purpose, p: PreparationControl) = synchronized(this) {
        check(preparationCurrent(p) && gate.state == EnvironmentalState.ENABLED_IDLE) { "Preparation revoked" }
        if (purpose == Purpose.DISCOVERY) begin(purpose, emptyList(), p = p)
        else {
            val bindings = history.bindings().filterNot { it.archived }
            check(preparationCurrent(p)) { "Collection preparation was revoked" }
            require(bindings.isNotEmpty()) { "Save a sensor first" }
            val qualification = history.assignedQualificationRadios(bindings, platformIdentity, System.currentTimeMillis())
            check(preparationCurrent(p)) { "Collection preparation was revoked" }
            history.retain(System.currentTimeMillis(), limits.retentionDays)
            begin(purpose, bindings, qualification, p)
        }
    }
    private fun begin(purpose: Purpose, bindings: List<Binding>, qualification: QualificationAdmission? = null, p: PreparationControl) {
        val visibleEpoch = p.epoch
        check(preparationCurrent(p))
        val durationMillis = if (purpose == Purpose.DISCOVERY) limits.discoverySeconds * 1000L else
            minOf(86_400_000L, (qualification?.validUntil ?: Long.MAX_VALUE) - System.currentTimeMillis())
        require(durationMillis > 0) { "Physical qualification expired" }
        val grant = gate.begin(purpose, bindings, durationMillis * 1_000_000L)
        val job = SupervisorJob(); operation = job
        val control = RunControl(job, p.revoked, p.serial, p.epoch); runControl = control
        val revocationRequested = control.revoked
        val scope = CoroutineScope(job + Dispatchers.IO)
        operationScope = scope
        val failureQueued = AtomicBoolean(false)
        val queue = Queue<Advertisement>(limits.queueCapacity)
        val runId = newId()
        val runDroppedStart = droppedReports.get()
        gapSession = if (purpose == Purpose.COLLECTION) runId else null
        gapDropBase = runDroppedStart
        val calibration = bindings.associate { it.id to history.calibrated(it, listOf(Reading(Channel.TEMPERATURE, 0.0), Reading(Channel.HUMIDITY, 0.0), Reading(Channel.BATTERY_PERCENT, 0.0), Reading(Channel.BATTERY_VOLTAGE, 0.0))).associateBy { r -> r.channel } }
        mutable.value = mutable.value.copy(state = gate.state, candidates = emptyList(), discovery = emptyMap(), bindings = bindings,
            status = if (purpose == Purpose.DISCOVERY) "Discovering for ${limits.discoverySeconds} seconds — active BLE scan, radio impact; diagnostic only" else "Collecting diagnostic receptions — measurement age unknown; active BLE scan")
        scope.launch {
            try {
                synchronized(this@EnvironmentalCoordinator) {
                    if (runControl !== control) return@launch
                    if (control.serial <= stoppedThrough.get() || !foreground.get() || disableInFlight.get() != 0 || disableRequested.get() || visibilityEpoch.get() != visibleEpoch || revocationRequested.get()) {
                        stop("Stopped — app visibility changed during preparation")
                        return@launch
                    }
                }
                val request = BleScanRequest(purpose == Purpose.DISCOVERY, bindings.map { it.address }.toSet(), qualification?.radios?.singleOrNull()) {
                    job.isActive && control.serial > stoppedThrough.get() && !revocationRequested.get() && disableInFlight.get() == 0 && !disableRequested.get() && !disposed.get() && foreground.get() && visibilityEpoch.get() == visibleEpoch && System.nanoTime() < grant.expires
                }
                val opened = transport().start(request, { report ->
                    // OS scan callbacks may be Main: no locks, disk IO, parsing, or coroutine-per-report here.
                    if (!request.isCurrent()) return@start
                    if (report.address.length !in 1..64 || (report.name?.length ?: 0) > 256) return@start
                    if (report.manufacturer.size > 16 || report.services.size > 16 || report.manufacturer.values.any { it.size > 512 } || report.services.values.any { it.size > 512 }) return@start
                    if (purpose == Purpose.COLLECTION && bindings.none { it.address.equals(report.address, true) }) return@start
                    if (!queue.trySend(report).isSuccess) droppedReports.incrementAndGet()
                }, { failure -> if (failureQueued.compareAndSet(false, true)) scope.launch {
                    synchronized(this@EnvironmentalCoordinator) { if (runControl === control) stop(failure) }
                } })
                if (qualification?.radios?.isNotEmpty() == true && opened.adapterDescription !in qualification.radios) {
                    runCatching { opened.close() }
                    throw QualificationUnavailableException()
                }
                synchronized(this@EnvironmentalCoordinator) {
                    if (request.isCurrent() && gate.valid(grant)) {
                        session = opened
                        mutable.value = mutable.value.copy(status = mutable.value.status + " · " + opened.adapterDescription)
                    }
                    else { opened.close(); if (runControl === control) stop("Stopped — obsolete radio preparation") }
                }
                for (first in queue) {
                    if (!request.isCurrent() || !gate.valid(grant)) continue
                    // At most 200ms coalescing, 32 events/transaction: bounded disk/CPU cost, no sample dedup by value.
                    if (purpose == Purpose.COLLECTION) delay(200)
                    val reports = mutableListOf(first)
                    repeat(31) { queue.tryReceive().getOrNull()?.let { reports += it } }
                    val decodedReports = reports.mapNotNull { report -> BleDecoders.decode(report)?.let { report to it } }
                    synchronized(this@EnvironmentalCoordinator) {
                        if (!request.isCurrent() || !gate.valid(grant)) return@synchronized
                        if (droppedReports.get() != mutable.value.dropped) mutable.update { current ->
                            if (request.isCurrent()) current.copy(dropped = droppedReports.get(), status = "Queue overflow — reception gap, diagnostic history incomplete") else current
                        }
                        val observations = mutableListOf<Observation>()
                        val admittedBindings = mutableListOf<Binding>()
                        for ((report, decoded) in decodedReports) {
                        if (purpose == Purpose.DISCOVERY) {
                            val candidate = Candidate(report.address, decoded.protocol, decoded.model)
                            mutable.update { current ->
                                val existing = current.candidates.firstOrNull { it.address.equals(candidate.address, true) }
                                if (request.isCurrent() && gate.valid(grant) && (existing != null || current.candidates.size < 64)) {
                                    val admitted = existing ?: candidate
                                    current.copy(candidates = if (existing == null) current.candidates + candidate else current.candidates,
                                        // Android ScanResult.getRssi(): [-127,126]; null/127 are unavailable.
                                        discovery = current.discovery + (admitted.address to DiscoveryReception(decoded.readings.toList(), report.observedAt, report.rssiDbm?.takeIf { it in -127..126 })))
                                } else current
                            }
                        } else {
                            val binding = bindings.firstOrNull { it.address.equals(report.address, true) && it.protocol == decoded.protocol && it.model == decoded.model } ?: continue
                            val n = sequence.incrementAndGet()
                            val observation = Observation("$collector/$runId/$n", grant.incarnation, collector, runId, n, binding.id, binding.revision,
                                report.observedAt, System.currentTimeMillis(), report.monotonicNanos, decoder = BleDecoders.VERSION, sourceCounter = decoded.counter, readings = decoded.readings.map { r -> calibration[binding.id]?.get(r.channel)?.let { r.copy(offset = it.offset, calibrationVersion = it.calibrationVersion) } ?: r }, droppedBefore = mutable.value.dropped - runDroppedStart, bootId = gate.bootId)
                            admittedBindings += binding
                            observations += observation
                        }
                        }
                        if (observations.isNotEmpty()) {
                            gate.commitBatch(grant, admittedBindings) { history.appendBatch(observations, gate.incarnation) }
                            mutable.update { current ->
                                if (request.isCurrent() && gate.valid(grant))
                                    current.copy(latest = current.latest + observations.associateBy { it.bindingId }) else current
                            }
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: QualificationUnavailableException) { synchronized(this@EnvironmentalCoordinator) { if (runControl === control) stop(e.message!!) } }
            catch (e: LinkageError) { synchronized(this@EnvironmentalCoordinator) { if (runControl === control) stop("Environmental provider unavailable — installation dependency missing; no automatic retry") } }
            catch (e: Exception) { synchronized(this@EnvironmentalCoordinator) { if (runControl === control && gate.valid(grant)) stop("Environmental operation unavailable — radio, permission or storage failure; no automatic retry") } }
            finally { queue.close() }
        }
        scope.launch { delay(durationMillis); synchronized(this@EnvironmentalCoordinator) { if (runControl === control) stop("Time limit reached — press Discover / Start for a new operation") } }
    }
    @Synchronized fun save(candidate: Candidate, name: String) {
        check(gate.state == EnvironmentalState.DISCOVERING && mutable.value.candidates.contains(candidate))
        check(history.bindings().none { !it.archived && it.address.equals(candidate.address, true) }) { "Sensor already added" }
        stop("Discovery stopped — saving selection")
        history.bind(candidate, name)
        savedBindings()
        mutable.value = mutable.value.copy(status = "Sensor added — press Start monitoring to collect")
    }
    private fun historyAccess(): String {
        if (deleteInFlight.get() != 0) throw EnvironmentalRecoveryException()
        val identity = gate.historyIdentity()
        if (deleteInFlight.get() != 0 || !gate.historyIdentityValid(identity)) throw EnvironmentalRecoveryException()
        return identity
    }
    @Synchronized fun loadHistory(before: Long = Long.MAX_VALUE, id: String = "~"): List<String> { historyAccess(); return history.page(before, id, limits.pageSize) }
    @Synchronized fun savedBindings(): List<Binding> {
        historyAccess()
        val bindings = history.bindings()
        val positions = history.entities("position").associateBy { it.getString("id") }
        val equipment = history.entities("equipment").associateBy { it.getString("id") }
        val placements = history.entities("placement").filter { it.isNull("until") }.associateBy { it.getString("sensorId") }
        val labels = bindings.mapNotNull { b ->
            val placement = placements[b.sensorId] ?: return@mapNotNull null
            val position = positions[placement.getString("positionId")] ?: return@mapNotNull null
            val room = equipment[position.getString("equipmentId")] ?: return@mapNotNull null
            b.id to (room.getString("name") to position.getString("name"))
        }.toMap()
        val sensorLabels = history.entities("sensor").associate { it.getString("id") to it.getString("name") }
        mutable.value = mutable.value.copy(bindings = bindings, roomLabels = labels, sensorLabels = sensorLabels, recordedDrops = history.recordedDrops())
        return bindings
    }
    @Synchronized fun archive(binding: Binding) { historyAccess(); stop(); history.archive(binding); savedBindings() }
    fun export(write: (String) -> Unit) {
        val identity = historyAccess()
        fun authorize() { if (deleteInFlight.get() != 0 || !gate.historyIdentityValid(identity)) throw EnvironmentalRecoveryException() }
        history.export(authorize = ::authorize) { line -> authorize(); write(line) }
    }
    @Synchronized fun retention() { historyAccess(); history.retain(System.currentTimeMillis(), limits.retentionDays) }
    @Synchronized fun metadata(kind: String): List<org.json.JSONObject> { historyAccess(); return history.entities(kind) }
    @Synchronized fun rename(binding: Binding, name: String) { historyAccess(); history.renameSensor(binding.sensorId, name) }
    @Synchronized fun assign(binding: Binding, positionId: String) { historyAccess(); stop("Assignment saved — press Start; declared operating limits remain guarded"); history.assign(binding.sensorId, positionId, System.currentTimeMillis()) }
    @Synchronized fun reside(spoolId: String, positionId: String) { historyAccess(); history.reside(spoolId, positionId, System.currentTimeMillis()) }
    @Synchronized fun configureRoom(binding: Binding, equipmentName: String, positionName: String) {
        historyAccess()
        stop("Stopped — editing room assignment")
        val activePlacement = history.entities("placement").firstOrNull { it.getString("sensorId") == binding.sensorId && it.isNull("until") }
        val previousPosition = activePlacement?.let { placement -> history.entities("position").firstOrNull { it.getString("id") == placement.getString("positionId") } ?: throw QualificationUnavailableException() }
        previousPosition?.let { previous ->
            val previousEquipment = history.entities("equipment").firstOrNull { it.getString("id") == previous.getString("equipmentId") } ?: throw QualificationUnavailableException()
            if (previousEquipment.getString("type") != "room") throw QualificationUnavailableException()
        }
        if (history.entities("equipment").any { it.getString("name") == equipmentName && it.optString("type") != "room" }) throw QualificationUnavailableException()
        val existingEquipment = history.entities("equipment").firstOrNull { it.getString("name") == equipmentName && it.optString("type") == "room" }
        val equipment = Equipment(id = existingEquipment?.getString("id") ?: newId(), name = equipmentName)
        val existingPosition = history.entities("position").firstOrNull { it.getString("equipmentId") == equipment.id && it.getString("name") == positionName }
        // A pre-existing target owns its limits, including deliberately absent limits.
        // Carry the old pair only when creating a new position for renamed labels.
        val envelope = existingPosition ?: previousPosition
        val position = Position(id = existingPosition?.getString("id") ?: newId(), equipmentId = equipment.id, name = positionName,
            minC = envelope?.takeUnless { it.isNull("minC") }?.getDouble("minC"),
            maxC = envelope?.takeUnless { it.isNull("maxC") }?.getDouble("maxC"))
        history.configureDraft(binding, equipment, position, System.currentTimeMillis())
        savedBindings()
        mutable.value = mutable.value.copy(status = "Room assignment saved — press Start to monitor; observations do not certify operating limits")
    }
    @Synchronized fun calibrate(binding: Binding, channel: Channel, offset: Double) {
        historyAccess()
        stop("Stopped — editing calibration")
        history.calibrate(binding, channel, offset)
        mutable.value = mutable.value.copy(status = "Calibration saved — press Start for a new operation")
    }
    @Synchronized fun deleteAll() {
        deleteInFlight.incrementAndGet(); disableInFlight.incrementAndGet()
        try { runCatching { stop(disable = true) }; deleteUnderMonitor() }
        finally { deleteInFlight.decrementAndGet(); disableInFlight.decrementAndGet() }
    }
    /** Deletion belongs to the explicit gesture, not to a cancellable composition scope. */
    fun requestDeleteAsync(): Job {
        deleteInFlight.incrementAndGet(); disableInFlight.incrementAndGet()
        val request = prepareStop("Deletion requested — cleanup pending", true)
        val owner = SupervisorJob()
        return CoroutineScope(owner + Dispatchers.IO).launch {
            try { runCatching { finishStop(request) }; synchronized(this@EnvironmentalCoordinator) { deleteUnderMonitor() } }
            catch (_: Exception) { /* Fixed-safe cleanup status is published by deleteUnderMonitor. */ }
            finally { deleteInFlight.decrementAndGet(); disableInFlight.decrementAndGet(); owner.cancel() }
        }
    }
    private fun deleteUnderMonitor() {
        try {
            // A thrown intent commit exits this try: no store purge is reached.
            gate.deleteIntent()
            history.deleteAll()
            gate.finishDelete()
            droppedReports.set(0); gapDropBase = 0; sequence.set(0)
            mutable.value = EnvironmentalView(gate.state, "Environmental data deleted; external exports are not removed")
        } catch (e: Exception) {
            mutable.value = mutable.value.copy(state = gate.state, latest = emptyMap(), candidates = emptyList(), discovery = emptyMap(), status = "Cleanup incomplete — explicitly resume deletion; no collection")
            throw e
        }
    }
    /** Composition disposal is Main: close only owned resources on IO, never create a worker for untouched off/idle. */
    fun closeAsync() {
        disposed.set(true)
        val preparation = loseForeground()
        if (operation == null && !history.isOpen && preparation == null) return
        val cleanupOwner = SupervisorJob()
        CoroutineScope(cleanupOwner + Dispatchers.IO).launch { try { close() } finally { cleanupOwner.cancel() } }
    }
    @Synchronized override fun close() {
        disposed.set(true)
        val p = preparations.getAndSet(null); p?.revoked?.set(true); p?.worker?.cancel()
        try { if (operation != null) stop() } finally { history.close() }
    }
}

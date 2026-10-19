package com.nozzleitall.environment

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel as Queue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.update

data class EnvironmentalView(val state: EnvironmentalState, val status: String = "Disabled — no collection", val candidates: List<Candidate> = emptyList(),
    val bindings: List<Binding> = emptyList(), val latest: Map<String, Observation> = emptyMap(), val dropped: Long = 0, val recordedDrops: Long = 0)

/** No scope, worker, transport, DB open or permission request is created by construction/enable. */
class EnvironmentalCoordinator(control: File, private val history: EnvironmentalHistory, private val transport: () -> BleTransport,
    private val limits: EnvironmentalLimits = EnvironmentalLimits(), private val platformIdentity: String = "unqualified") : AutoCloseable {
    val gate = EnvironmentalGate(control)
    private val mutable = MutableStateFlow(EnvironmentalView(gate.state, if (gate.state == EnvironmentalState.ENABLED_IDLE) "Enabled, idle — press Discover or Start" else if (gate.state == EnvironmentalState.DISABLED_RECOVERY) "Recovery required — control record preserved; Delete / resume cleanup explicitly" else "Disabled — no collection"))
    val view = mutable.asStateFlow()
    @Volatile private var operation: Job? = null
    @Volatile private var operationScope: CoroutineScope? = null
    private class RunControl(val job: Job, val revoked: AtomicBoolean = AtomicBoolean(false))
    @Volatile private var runControl: RunControl? = null
    private var session: RadioSession? = null
    private var gapSession: String? = null
    private var gapDropBase = 0L
    private val foreground = AtomicBoolean(false)
    private val visibilityEpoch = AtomicLong()
    private val disableRequested = AtomicBoolean(false)
    private val droppedReports = AtomicLong()
    private val disposed = AtomicBoolean(false)
    private val collector get() = gate.collectorId
    private val sequence = AtomicLong()
    fun foreground(visible: Boolean) {
        foreground.set(visible)
        if (!visible) {
            visibilityEpoch.incrementAndGet()
            val current = runControl ?: return
            current.revoked.set(true)
            mutable.update { if (runControl === current) it.copy(latest = emptyMap(), candidates = emptyList(), status = "Stop requested — app not visible; cleanup pending") else it }
            operationScope?.launch { synchronized(this@EnvironmentalCoordinator) {
                if (runControl === current) stop("Stopped — app not visible; press Start to resume")
            } }
        }
    }
    @Synchronized fun enable() {
        check(!disposed.get() && operation == null)
        check(!disableRequested.get() || gate.state == EnvironmentalState.DISABLED)
        gate.enable(); disableRequested.set(false)
        mutable.value = mutable.value.copy(state = gate.state, status = "Enabled, idle — no radio or history opened")
    }
    fun stop(reason: String = "Stopped — no collection", disable: Boolean = false) {
        val target = runControl
        if (disable) disableRequested.set(true)
        target?.revoked?.set(true)
        // Hide live readings immediately, before durable revocation/cancellation.
        if (target != null) mutable.update { if (runControl === target) it.copy(latest = emptyMap(), candidates = emptyList(), status = "Stop requested — cleanup pending") else it }
        synchronized(this) {
            if (!disable && runControl !== target) return // This request never owns a newer Start.
            runControl?.revoked?.set(true)
            try { gate.revoke(disable) }
            finally {
                operation?.cancel(); operation = null; operationScope = null; runControl = null
                runCatching { session?.close() }; session = null
                val gapId = gapSession; gapSession = null
                val drops = droppedReports.get() - gapDropBase
                val gapFailed = gapId != null && drops > 0 && runCatching { history.recordGap(gapId, System.currentTimeMillis(), drops) }.isFailure
                mutable.value = mutable.value.copy(state = gate.state, dropped = droppedReports.get(), status = when {
                    gate.persistenceFailure -> "Persistence failed — stopped in memory, recovery required"
                    gapFailed -> "Stopped — final overflow count could not be persisted; history coverage incomplete"
                    else -> reason
                })
            }
        }
    }
    @Synchronized fun discover() = begin(Purpose.DISCOVERY, emptyList())
    @Synchronized fun start() {
        check(foreground.get() && !disableRequested.get() && !disposed.get() && gate.state == EnvironmentalState.ENABLED_IDLE)
        val bindings = history.bindings().filterNot { it.archived }
        require(bindings.isNotEmpty()) { "Save a sensor first" }
        // Unknown firmware/envelopes permit unassigned diagnostic reception only, never dryer deployment.
        val qualification = history.assignedQualificationRadios(bindings, platformIdentity, System.currentTimeMillis())
        history.retain(System.currentTimeMillis(), limits.retentionDays)
        begin(Purpose.COLLECTION, bindings, qualification)
    }
    private fun begin(purpose: Purpose, bindings: List<Binding>, qualification: QualificationAdmission? = null) {
        val visibleEpoch = visibilityEpoch.get()
        check(foreground.get() && !disableRequested.get() && !disposed.get())
        val durationMillis = if (purpose == Purpose.DISCOVERY) limits.discoverySeconds * 1000L else
            minOf(86_400_000L, (qualification?.validUntil ?: Long.MAX_VALUE) - System.currentTimeMillis())
        require(durationMillis > 0) { "Physical qualification expired" }
        val grant = gate.begin(purpose, bindings, durationMillis * 1_000_000L)
        val job = SupervisorJob(); operation = job
        val control = RunControl(job); runControl = control
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
        mutable.value = mutable.value.copy(state = gate.state, candidates = emptyList(), bindings = bindings,
            status = if (purpose == Purpose.DISCOVERY) "Discovering for ${limits.discoverySeconds} seconds — active BLE scan, radio impact; diagnostic only" else "Collecting diagnostic receptions — measurement age unknown; active BLE scan")
        scope.launch {
            try {
                synchronized(this@EnvironmentalCoordinator) {
                    if (runControl !== control) return@launch
                    if (!foreground.get() || disableRequested.get() || visibilityEpoch.get() != visibleEpoch || revocationRequested.get()) {
                        stop("Stopped — app visibility changed during preparation")
                        return@launch
                    }
                }
                val request = BleScanRequest(purpose == Purpose.DISCOVERY, bindings.map { it.address }.toSet(), qualification?.radios?.singleOrNull()) {
                    job.isActive && !revocationRequested.get() && !disableRequested.get() && !disposed.get() && foreground.get() && visibilityEpoch.get() == visibleEpoch && System.nanoTime() < grant.expires
                }
                val opened = transport().start(request, { report ->
                    // OS scan callbacks may be Main: no locks, disk IO, parsing, or coroutine-per-report here.
                    if (!job.isActive || revocationRequested.get() || visibilityEpoch.get() != visibleEpoch) return@start
                    if (report.address.length !in 1..64 || (report.name?.length ?: 0) > 256) return@start
                    if (report.manufacturer.size > 16 || report.services.size > 16 || report.manufacturer.values.any { it.size > 512 } || report.services.values.any { it.size > 512 }) return@start
                    if (purpose == Purpose.COLLECTION && bindings.none { it.address.equals(report.address, true) }) return@start
                    if (!queue.trySend(report).isSuccess) droppedReports.incrementAndGet()
                }, { failure -> if (failureQueued.compareAndSet(false, true)) scope.launch {
                    synchronized(this@EnvironmentalCoordinator) { if (operation === job) stop(failure) }
                } })
                if (qualification?.radios?.isNotEmpty() == true && opened.adapterDescription !in qualification.radios) {
                    runCatching { opened.close() }
                    error("Physical qualification adapter changed")
                }
                synchronized(this@EnvironmentalCoordinator) {
                    if (gate.valid(grant) && visibilityEpoch.get() == visibleEpoch && !revocationRequested.get()) {
                        session = opened
                        mutable.value = mutable.value.copy(status = mutable.value.status + " · " + opened.adapterDescription)
                    }
                    else { opened.close(); if (operation === job) stop("Stopped — obsolete radio preparation") }
                }
                for (first in queue) {
                    if (revocationRequested.get() || !gate.valid(grant)) continue
                    // At most 200ms coalescing, 32 events/transaction: bounded disk/CPU cost, no sample dedup by value.
                    if (purpose == Purpose.COLLECTION) delay(200)
                    val reports = mutableListOf(first)
                    repeat(31) { queue.tryReceive().getOrNull()?.let { reports += it } }
                    val decodedReports = reports.mapNotNull { report -> BleDecoders.decode(report)?.let { report to it } }
                    synchronized(this@EnvironmentalCoordinator) {
                        if (revocationRequested.get() || !gate.valid(grant)) return@synchronized
                        if (droppedReports.get() != mutable.value.dropped) mutable.update { current ->
                            if (!revocationRequested.get() && visibilityEpoch.get() == visibleEpoch) current.copy(dropped = droppedReports.get(), status = "Queue overflow — reception gap, diagnostic history incomplete") else current
                        }
                        val observations = mutableListOf<Observation>()
                        val admittedBindings = mutableListOf<Binding>()
                        for ((report, decoded) in decodedReports) {
                        if (purpose == Purpose.DISCOVERY) {
                            val candidate = Candidate(report.address, decoded.protocol, decoded.model)
                            mutable.update { current ->
                                if (!revocationRequested.get() && visibilityEpoch.get() == visibleEpoch && gate.valid(grant) &&
                                    current.candidates.none { it.address == candidate.address } && current.candidates.size < 64)
                                    current.copy(candidates = current.candidates + candidate) else current
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
                                if (!revocationRequested.get() && visibilityEpoch.get() == visibleEpoch && gate.valid(grant))
                                    current.copy(latest = current.latest + observations.associateBy { it.bindingId }) else current
                            }
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: LinkageError) { synchronized(this@EnvironmentalCoordinator) { if (operation === job) stop("Environmental provider unavailable — installation dependency missing; no automatic retry") } }
            catch (e: Exception) { synchronized(this@EnvironmentalCoordinator) { if (gate.valid(grant)) stop("Environmental operation unavailable — radio, permission or storage failure; no automatic retry") } }
            finally { queue.close() }
        }
        scope.launch { delay(durationMillis); synchronized(this@EnvironmentalCoordinator) { if (operation === job) stop("Time limit reached — press Discover / Start for a new operation") } }
    }
    @Synchronized fun save(candidate: Candidate, name: String) {
        check(gate.state == EnvironmentalState.DISCOVERING && mutable.value.candidates.contains(candidate))
        stop("Discovery stopped — saving selection")
        history.bind(candidate, name)
        mutable.value = mutable.value.copy(bindings = history.bindings(), status = "Saved only — no collection")
    }
    @Synchronized fun loadHistory(before: Long = Long.MAX_VALUE, id: String = "~") = history.page(before, id, limits.pageSize)
    @Synchronized fun savedBindings(): List<Binding> = history.bindings().also { mutable.value = mutable.value.copy(bindings = it, recordedDrops = history.recordedDrops()) }
    @Synchronized fun archive(binding: Binding) { stop(); history.archive(binding); savedBindings() }
    fun export(write: (String) -> Unit) { history.export(write) }
    @Synchronized fun retention() { history.retain(System.currentTimeMillis(), limits.retentionDays) }
    @Synchronized fun metadata(kind: String) = history.entities(kind)
    @Synchronized fun rename(binding: Binding, name: String) { history.renameSensor(binding.sensorId, name) }
    @Synchronized fun assign(binding: Binding, positionId: String) { stop("Draft assignment saved — qualification required"); history.assign(binding.sensorId, positionId, System.currentTimeMillis()) }
    @Synchronized fun reside(spoolId: String, positionId: String) { history.reside(spoolId, positionId, System.currentTimeMillis()) }
    @Synchronized fun configureRoom(binding: Binding, equipmentName: String, positionName: String) {
        stop("Stopped — editing draft assignment")
        val existingEquipment = history.entities("equipment").firstOrNull { it.getString("name") == equipmentName && it.optString("type") == "room" }
        val equipment = Equipment(id = existingEquipment?.getString("id") ?: newId(), name = equipmentName)
        val existingPosition = history.entities("position").firstOrNull { it.getString("equipmentId") == equipment.id && it.getString("name") == positionName }
        val position = Position(id = existingPosition?.getString("id") ?: newId(), equipmentId = equipment.id, name = positionName)
        history.configureDraft(binding, equipment, position, System.currentTimeMillis())
        mutable.value = mutable.value.copy(status = "Saved draft assignment — thermal qualification required before collection")
    }
    @Synchronized fun calibrate(binding: Binding, channel: Channel, offset: Double) {
        stop("Stopped — editing calibration")
        history.calibrate(binding, channel, offset)
        mutable.value = mutable.value.copy(status = "Calibration saved — press Start for a new operation")
    }
    @Synchronized fun deleteAll() {
        // Stop always closes the in-memory gate/cancels owned work, even if its persistence fails.
        runCatching { stop(disable = true) }
        try {
            // A thrown intent commit exits this try: no store purge is reached.
            gate.deleteIntent()
            history.deleteAll()
            gate.finishDelete()
            mutable.value = EnvironmentalView(gate.state, "Environmental data deleted; external exports are not removed")
        } catch (e: Exception) {
            mutable.value = mutable.value.copy(state = gate.state, latest = emptyMap(), candidates = emptyList(), status = "Cleanup incomplete — explicitly resume deletion; no collection")
            throw e
        }
    }
    /** Composition disposal is Main: close only owned resources on IO, never create a worker for untouched off/idle. */
    fun closeAsync() {
        disposed.set(true)
        foreground(false)
        if (operation == null && !history.isOpen) return
        val cleanupOwner = SupervisorJob()
        CoroutineScope(cleanupOwner + Dispatchers.IO).launch { try { close() } finally { cleanupOwner.cancel() } }
    }
    @Synchronized override fun close() {
        disposed.set(true)
        try { if (operation != null) stop() } finally { history.close() }
    }
}

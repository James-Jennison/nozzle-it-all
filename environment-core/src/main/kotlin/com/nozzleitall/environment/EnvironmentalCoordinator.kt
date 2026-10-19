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
    val bindings: List<Binding> = emptyList(), val latest: Map<String, Observation> = emptyMap(), val dropped: Long = 0)

/** No scope, worker, transport, DB open or permission request is created by construction/enable. */
class EnvironmentalCoordinator(control: File, private val history: EnvironmentalHistory, private val transport: () -> BleTransport,
    private val limits: EnvironmentalLimits = EnvironmentalLimits()) : AutoCloseable {
    val gate = EnvironmentalGate(control)
    private val mutable = MutableStateFlow(EnvironmentalView(gate.state, if (gate.state == EnvironmentalState.ENABLED_IDLE) "Enabled, idle — press Discover or Start" else if (gate.state == EnvironmentalState.DISABLED_RECOVERY) "Recovery required — control record preserved; Delete / resume cleanup explicitly" else "Disabled — no collection"))
    val view = mutable.asStateFlow()
    @Volatile private var operation: Job? = null
    @Volatile private var operationScope: CoroutineScope? = null
    private var session: RadioSession? = null
    private val foreground = AtomicBoolean(false)
    private val visibilityEpoch = AtomicLong()
    private val revocationRequested = AtomicBoolean(false)
    private val droppedReports = AtomicLong()
    private val collector = newId()
    private val sequence = AtomicLong()
    fun foreground(visible: Boolean) {
        foreground.set(visible)
        if (!visible) {
            revocationRequested.set(true)
            visibilityEpoch.incrementAndGet()
            val current = operation ?: return
            mutable.update { it.copy(latest = emptyMap(), candidates = emptyList(), status = "Stopped — app not visible; press Start to resume") }
            operationScope?.launch { synchronized(this@EnvironmentalCoordinator) {
                if (operation === current) stop("Stopped — app not visible; press Start to resume")
            } }
        }
    }
    @Synchronized fun enable() { gate.enable(); mutable.value = mutable.value.copy(state = gate.state, status = "Enabled, idle — no radio or history opened") }
    @Synchronized fun stop(reason: String = "Stopped — no collection", disable: Boolean = false) {
        revocationRequested.set(true)
        // Hide live readings immediately, before durable revocation/cancellation.
        mutable.value = mutable.value.copy(latest = emptyMap(), candidates = emptyList(), status = reason)
        try { gate.revoke(disable) }
        finally {
            operation?.cancel(); operation = null; operationScope = null
            runCatching { session?.close() }; session = null
            mutable.value = mutable.value.copy(state = gate.state, status = if (gate.persistenceFailure) "Persistence failed — stopped in memory, recovery required" else reason)
        }
    }
    @Synchronized fun discover() = begin(Purpose.DISCOVERY, emptyList())
    @Synchronized fun start() {
        check(foreground.get() && gate.state == EnvironmentalState.ENABLED_IDLE)
        val bindings = history.bindings().filterNot { it.archived }
        require(bindings.isNotEmpty()) { "Save a sensor first" }
        // Unknown firmware/envelopes permit unassigned diagnostic reception only, never dryer deployment.
        require(history.entities("placement").none { it.isNull("until") && bindings.any { b -> b.sensorId == it.getString("sensorId") } }) { "Assigned sensors require a qualified thermal/firmware record before collection" }
        history.retain(System.currentTimeMillis(), limits.retentionDays)
        begin(Purpose.COLLECTION, bindings)
    }
    private fun begin(purpose: Purpose, bindings: List<Binding>) {
        val visibleEpoch = visibilityEpoch.get()
        check(foreground.get())
        require(bindings.size <= 64) { "At most 64 active environmental bindings per foreground session" }
        val grant = gate.begin(purpose, bindings, if (purpose == Purpose.DISCOVERY) limits.discoverySeconds * 1_000_000_000L else 86_400_000_000_000)
        val job = SupervisorJob(); operation = job
        val scope = CoroutineScope(job + Dispatchers.IO)
        operationScope = scope
        revocationRequested.set(false)
        val failureQueued = AtomicBoolean(false)
        val queue = Queue<Advertisement>(limits.queueCapacity)
        val runId = newId()
        val calibration = bindings.associate { it.id to history.calibrated(it, listOf(Reading(Channel.TEMPERATURE, 0.0), Reading(Channel.HUMIDITY, 0.0), Reading(Channel.BATTERY_PERCENT, 0.0), Reading(Channel.BATTERY_VOLTAGE, 0.0))).associateBy { r -> r.channel } }
        mutable.value = mutable.value.copy(state = gate.state, candidates = emptyList(), bindings = bindings,
            status = if (purpose == Purpose.DISCOVERY) "Discovering for ${limits.discoverySeconds} seconds — active BLE scan, radio impact; diagnostic only" else "Collecting diagnostic receptions — measurement age unknown; active BLE scan")
        scope.launch {
            try {
                synchronized(this@EnvironmentalCoordinator) {
                    if (!foreground.get() || visibilityEpoch.get() != visibleEpoch || revocationRequested.get()) {
                        stop("Stopped — app visibility changed during preparation")
                        return@launch
                    }
                }
                val opened = transport().start({ report ->
                    // OS scan callbacks may be Main: no locks, disk IO, parsing, or coroutine-per-report here.
                    if (!job.isActive || revocationRequested.get() || visibilityEpoch.get() != visibleEpoch) return@start
                    if (report.address.length !in 1..64 || (report.name?.length ?: 0) > 256) return@start
                    if (report.manufacturer.size > 16 || report.services.size > 16 || report.manufacturer.values.any { it.size > 512 } || report.services.values.any { it.size > 512 }) return@start
                    if (purpose == Purpose.COLLECTION && bindings.none { it.address.equals(report.address, true) }) return@start
                    if (!queue.trySend(report).isSuccess) droppedReports.incrementAndGet()
                }, { failure -> if (failureQueued.compareAndSet(false, true)) scope.launch {
                    synchronized(this@EnvironmentalCoordinator) { if (operation === job) stop(failure) }
                } })
                synchronized(this@EnvironmentalCoordinator) {
                    if (gate.valid(grant) && visibilityEpoch.get() == visibleEpoch && !revocationRequested.get()) session = opened
                    else { opened.close(); if (operation === job) stop("Stopped — obsolete radio preparation") }
                }
                for (first in queue) {
                    if (revocationRequested.get() || !gate.valid(grant)) continue
                    // At most 25ms coalescing, 32 events/transaction: bounded disk/CPU cost, no sample dedup by value.
                    if (purpose == Purpose.COLLECTION) delay(25)
                    val reports = mutableListOf(first)
                    repeat(31) { queue.tryReceive().getOrNull()?.let { reports += it } }
                    val decodedReports = reports.mapNotNull { report -> BleDecoders.decode(report)?.let { report to it } }
                    synchronized(this@EnvironmentalCoordinator) {
                        if (revocationRequested.get() || !gate.valid(grant)) return@synchronized
                        if (droppedReports.get() != mutable.value.dropped) mutable.value = mutable.value.copy(dropped = droppedReports.get(), status = "Queue overflow — reception gap, diagnostic history incomplete")
                        val observations = mutableListOf<Observation>()
                        val admittedBindings = mutableListOf<Binding>()
                        for ((report, decoded) in decodedReports) {
                        if (purpose == Purpose.DISCOVERY) {
                            val candidate = Candidate(report.address, decoded.protocol, decoded.model)
                            val current = mutable.value.candidates
                            if (current.none { it.address == candidate.address } && current.size < 64) mutable.value = mutable.value.copy(candidates = current + candidate)
                        } else {
                            val binding = bindings.firstOrNull { it.address.equals(report.address, true) && it.protocol == decoded.protocol && it.model == decoded.model } ?: continue
                            val n = sequence.incrementAndGet()
                            val observation = Observation("$collector/$runId/$n", grant.incarnation, collector, runId, n, binding.id, binding.revision,
                                report.observedAt, System.currentTimeMillis(), report.monotonicNanos, decoder = BleDecoders.VERSION, sourceCounter = decoded.counter, readings = decoded.readings.map { r -> calibration[binding.id]?.get(r.channel)?.let { r.copy(offset = it.offset, calibrationVersion = it.calibrationVersion) } ?: r }, droppedBefore = mutable.value.dropped)
                            admittedBindings += binding
                            observations += observation
                        }
                        }
                        if (observations.isNotEmpty()) {
                            gate.commitBatch(grant, admittedBindings) { history.appendBatch(observations, gate.incarnation) }
                            mutable.value = mutable.value.copy(latest = mutable.value.latest + observations.associateBy { it.bindingId })
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { synchronized(this@EnvironmentalCoordinator) { if (gate.valid(grant)) stop("Environmental operation unavailable — radio, permission or storage failure; no automatic retry") } }
            finally { queue.close() }
        }
        scope.launch { delay(if (purpose == Purpose.DISCOVERY) limits.discoverySeconds * 1000L else 86_400_000); synchronized(this@EnvironmentalCoordinator) { if (operation === job) stop("Time limit reached — press Discover / Start for a new operation") } }
    }
    @Synchronized fun save(candidate: Candidate, name: String) {
        check(gate.state == EnvironmentalState.DISCOVERING && mutable.value.candidates.contains(candidate))
        stop("Saved only — no collection")
        history.bind(candidate, name)
        mutable.value = mutable.value.copy(bindings = history.bindings())
    }
    @Synchronized fun loadHistory(before: Long = Long.MAX_VALUE, id: String = "~") = history.page(before, id, limits.pageSize)
    @Synchronized fun savedBindings(): List<Binding> = history.bindings().also { mutable.value = mutable.value.copy(bindings = it) }
    @Synchronized fun archive(binding: Binding) { stop(); history.archive(binding); savedBindings() }
    fun export(write: (String) -> Unit) { history.export(write) }
    @Synchronized fun retention() { history.retain(System.currentTimeMillis(), limits.retentionDays) }
    @Synchronized fun metadata(kind: String) = history.entities(kind)
    @Synchronized fun rename(binding: Binding, name: String) { history.renameSensor(binding.sensorId, name) }
    @Synchronized fun assign(binding: Binding, positionId: String) { stop("Draft assignment saved — qualification required"); history.assign(binding.sensorId, positionId, System.currentTimeMillis()) }
    @Synchronized fun reside(spoolId: String, positionId: String) { history.reside(spoolId, positionId, System.currentTimeMillis()) }
    @Synchronized fun configureRoom(binding: Binding, equipmentName: String, positionName: String) {
        stop("Saved draft assignment — thermal qualification required before collection")
        val equipment = Equipment(name = equipmentName)
        val position = Position(equipmentId = equipment.id, name = positionName)
        history.saveEquipment(equipment); history.savePosition(position)
        history.assign(binding.sensorId, position.id, System.currentTimeMillis())
    }
    @Synchronized fun calibrate(binding: Binding, channel: Channel, offset: Double) {
        stop("Calibration saved — press Start for a new operation")
        history.calibrate(binding, channel, offset)
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
    @Synchronized override fun close() { if (operation != null) stop(); history.close() }
}

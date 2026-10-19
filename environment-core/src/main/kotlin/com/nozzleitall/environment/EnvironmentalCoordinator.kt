package com.nozzleitall.environment

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel as Queue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class EnvironmentalView(val state: EnvironmentalState, val status: String = "Disabled — no collection", val candidates: List<Candidate> = emptyList(),
    val bindings: List<Binding> = emptyList(), val latest: Map<String, Observation> = emptyMap(), val dropped: Long = 0)

/** No scope, worker, transport, DB open or permission request is created by construction/enable. */
class EnvironmentalCoordinator(control: File, private val history: EnvironmentalHistory, private val transport: () -> BleTransport) : AutoCloseable {
    val gate = EnvironmentalGate(control)
    private val mutable = MutableStateFlow(EnvironmentalView(gate.state, if (gate.state == EnvironmentalState.ENABLED_IDLE) "Enabled, idle — press Discover or Start" else if (gate.state == EnvironmentalState.DISABLED_RECOVERY) "Recovery required — control record preserved; Delete / resume cleanup explicitly" else "Disabled — no collection"))
    val view = mutable.asStateFlow()
    private var operation: Job? = null
    private var session: RadioSession? = null
    private var foreground = false
    private val collector = newId()
    private val sequence = AtomicLong()
    @Synchronized fun foreground(visible: Boolean) { foreground = visible; if (!visible && operation != null) stop("Stopped — app not visible; press Start to resume") }
    @Synchronized fun enable() { gate.enable(); mutable.value = mutable.value.copy(state = gate.state, status = "Enabled, idle — no radio or history opened") }
    @Synchronized fun stop(reason: String = "Stopped — no collection", disable: Boolean = false) {
        // Hide live readings immediately, before durable revocation/cancellation.
        mutable.value = mutable.value.copy(latest = emptyMap(), candidates = emptyList(), status = reason)
        try { gate.revoke(disable) }
        finally {
            operation?.cancel(); operation = null
            runCatching { session?.close() }; session = null
            mutable.value = mutable.value.copy(state = gate.state, status = if (gate.persistenceFailure) "Persistence failed — stopped in memory, recovery required" else reason)
        }
    }
    @Synchronized fun discover() = begin(Purpose.DISCOVERY, emptyList())
    @Synchronized fun start() {
        check(foreground && gate.state == EnvironmentalState.ENABLED_IDLE)
        val bindings = history.bindings().filterNot { it.archived }
        require(bindings.isNotEmpty()) { "Save a sensor first" }
        // Unknown firmware/envelopes permit unassigned diagnostic reception only, never dryer deployment.
        require(history.entities("placement").none { it.isNull("until") && bindings.any { b -> b.sensorId == it.getString("sensorId") } }) { "Assigned sensors require a qualified thermal/firmware record before collection" }
        begin(Purpose.COLLECTION, bindings)
    }
    private fun begin(purpose: Purpose, bindings: List<Binding>) {
        check(foreground)
        val grant = gate.begin(purpose, bindings, if (purpose == Purpose.DISCOVERY) 45_000_000_000 else 86_400_000_000_000)
        val job = SupervisorJob(); operation = job
        val scope = CoroutineScope(job + Dispatchers.IO)
        val queue = Queue<Advertisement>(256)
        val runId = newId()
        mutable.value = mutable.value.copy(state = gate.state, candidates = emptyList(), bindings = bindings,
            status = if (purpose == Purpose.DISCOVERY) "Discovering for 45 seconds — active BLE scan, radio impact; diagnostic only" else "Collecting diagnostic receptions — measurement age unknown; active BLE scan")
        scope.launch {
            try {
                val opened = transport().start({ report ->
                    if (!gate.valid(grant)) return@start
                    if (report.manufacturer.size > 16 || report.services.size > 16 || report.manufacturer.values.any { it.size > 512 } || report.services.values.any { it.size > 512 }) return@start
                    if (purpose == Purpose.COLLECTION && bindings.none { it.address.equals(report.address, true) }) return@start
                    if (!queue.trySend(report).isSuccess) synchronized(this@EnvironmentalCoordinator) {
                        if (gate.valid(grant)) mutable.value = mutable.value.copy(dropped = mutable.value.dropped + 1, status = "Queue overflow — reception gap, diagnostic history incomplete")
                    }
                }, { failure -> synchronized(this@EnvironmentalCoordinator) { if (gate.valid(grant)) stop(failure) } })
                synchronized(this@EnvironmentalCoordinator) { if (gate.valid(grant)) session = opened else opened.close() }
                for (report in queue) {
                    if (!gate.valid(grant)) continue
                    val decoded = BleDecoders.decode(report) ?: continue
                    synchronized(this@EnvironmentalCoordinator) {
                        if (!gate.valid(grant)) return@synchronized
                        if (purpose == Purpose.DISCOVERY) {
                            val candidate = Candidate(report.address, decoded.protocol, decoded.model)
                            val current = mutable.value.candidates
                            if (current.none { it.address == candidate.address } && current.size < 64) mutable.value = mutable.value.copy(candidates = current + candidate)
                        } else {
                            val binding = bindings.firstOrNull { it.address.equals(report.address, true) && it.protocol == decoded.protocol && it.model == decoded.model } ?: return@synchronized
                            val n = sequence.incrementAndGet()
                            val observation = Observation("$collector/$runId/$n", grant.incarnation, collector, runId, n, binding.id, binding.revision,
                                report.observedAt, System.currentTimeMillis(), report.monotonicNanos, decoder = BleDecoders.VERSION, sourceCounter = decoded.counter, readings = history.calibrated(binding, decoded.readings))
                            gate.commit(grant, binding) { history.append(observation, gate.incarnation) }
                            mutable.value = mutable.value.copy(latest = mutable.value.latest + (binding.id to observation))
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { synchronized(this@EnvironmentalCoordinator) { if (gate.valid(grant)) stop("Environmental operation unavailable — radio, permission or storage failure; no automatic retry") } }
            finally { queue.close() }
        }
        scope.launch { delay(if (purpose == Purpose.DISCOVERY) 45_000 else 86_400_000); synchronized(this@EnvironmentalCoordinator) { if (operation === job) stop("Time limit reached — press Discover / Start for a new operation") } }
    }
    @Synchronized fun save(candidate: Candidate, name: String) {
        check(gate.state == EnvironmentalState.DISCOVERING && mutable.value.candidates.contains(candidate))
        stop("Saved only — no collection")
        history.bind(candidate, name)
        mutable.value = mutable.value.copy(bindings = history.bindings())
    }
    @Synchronized fun loadHistory(before: Long = Long.MAX_VALUE, id: String = "~") = history.page(before, id)
    @Synchronized fun savedBindings(): List<Binding> = history.bindings().also { mutable.value = mutable.value.copy(bindings = it) }
    @Synchronized fun archive(binding: Binding) { stop(); history.archive(binding); savedBindings() }
    @Synchronized fun export(write: (String) -> Unit) { history.export(write) }
    @Synchronized fun retention() { history.retain(System.currentTimeMillis()) }
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
        try { stop(disable = true) } finally {
            gate.deleteIntent()
            history.deleteAll()
            gate.finishDelete()
            mutable.value = EnvironmentalView(gate.state, "Environmental data deleted; external exports are not removed")
        }
    }
    @Synchronized override fun close() { if (operation != null) stop(); history.close() }
}

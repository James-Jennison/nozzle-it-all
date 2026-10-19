package com.nozzleitall.environment

import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
enum class Channel(val wire: String, val unit: String) {
    TEMPERATURE("environment.temperature", "°C"), HUMIDITY("environment.relativeHumidity", "%RH"),
    BATTERY_PERCENT("environment.batteryPercent", "%"), BATTERY_VOLTAGE("environment.batteryVoltage", "V")
}
data class Reading(val channel: Channel, val raw: Double, val offset: Double = 0.0, val calibrationVersion: Int = 0) {
    init { require(raw.isFinite() && offset.isFinite() && calibrationVersion >= 0) }
    val value get() = raw + offset
}
data class Sensor(val id: String = newId(), val name: String)
data class Binding(val id: String = newId(), val sensorId: String, val address: String, val protocol: String,
                   val model: String, val firmware: String? = null, val revision: Long = 1, val archived: Boolean = false)
data class Equipment(val id: String = newId(), val name: String, val type: String = "room")
/** Owner-reviewed per-channel declaration, not an untrusted self-certification endpoint or whole-device badge.
 * radio is the exact RadioSession.adapterDescription observed in the physical diagnostic run, never a friendly name.
 * Evidence references are owner-approved physical artifacts; production has no UI/import path to create these records.
 * OS/user/device/radio identity changes invalidate declarations; legacy generic radio IDs are not migrated.
 */
data class CapabilityQualification(val id: String = newId(), val bindingId: String, val bindingRevision: Long,
    val model: String, val firmware: String, val platform: String, val radio: String,
    val decoder: String, val scanMode: String, val channel: Channel, val calibrationVersion: Int,
    val minC: Double, val maxC: Double, val cadenceMinMillis: Long, val cadenceMaxMillis: Long,
    val verifiedAt: Long, val expiresAt: Long, val evidence: Map<String, String>, val archived: Boolean = false)
data class QualificationAdmission(val radios: Set<String>, val validUntil: Long)
class QualificationUnavailableException : IllegalStateException("Assigned deployment unavailable — position, firmware, platform or radio qualification missing/changed; owner-reviewed physical evidence required")
class EnvironmentalRecoveryException : IllegalStateException("Environmental recovery required — only explicit Delete / resume cleanup is available")
data class Position(val id: String = newId(), val equipmentId: String, val name: String,
                    val minC: Double? = null, val maxC: Double? = null)
data class Placement(val id: String = newId(), val sensorId: String, val positionId: String,
                     val from: Long, val until: Long? = null) {
    init { require(until == null || until > from) }
    fun contains(time: Long) = time >= from && (until == null || time < until)
}
data class SpoolResidence(val id: String = newId(), val spoolId: String, val positionId: String,
                          val from: Long, val until: Long? = null)
data class Observation(val eventId: String, val incarnation: String, val collectorId: String,
    val sessionId: String, val sequence: Long, val bindingId: String, val bindingRevision: Long,
    val observedAt: Long, val receivedAt: Long, val monotonicNanos: Long,
    val measuredAt: Long? = null, val sourceCounter: Int? = null,
    val decoder: String, val readings: List<Reading>, val droppedBefore: Long = 0,
    val clockId: String = "System.nanoTime", val bootId: String = sessionId)
data class Advertisement(val address: String, val name: String?, val manufacturer: Map<Int, ByteArray>,
                         val services: Map<String, ByteArray>, val observedAt: Long, val monotonicNanos: Long,
                         val rssiDbm: Int? = null)
data class Decoded(val protocol: String, val model: String, val readings: List<Reading>, val counter: Int? = null)
data class Candidate(val address: String, val protocol: String, val model: String)
/** Transient discovery reception, not a measurement timestamp or persisted observation. */
data class DiscoveryReception(val readings: List<Reading>, val receivedAt: Long, val rssiDbm: Int?)
data class ProviderDescriptor(val id: String, val name: String, val activeScan: Boolean, val available: Boolean)
object EnvironmentalProviders {
    // Inert descriptors only. Never constructs transports, opens storage, or probes a radio.
    val ble = listOf(ProviderDescriptor("govee-ble", "Govee BLE", true, true),
        ProviderDescriptor("bthome-v2", "BTHome v2 unencrypted (diagnostic)", true, true))
}
interface RadioSession : AutoCloseable { val adapterDescription: String get() = "BLE" }
data class BleScanRequest(val discovery: Boolean, val selectedAddresses: Set<String> = emptySet(),
    val requiredAdapterDescription: String? = null,
    /** Nonblocking host-issued effect fence; drivers must recheck immediately before each owned OS acquisition. */
    val isCurrent: () -> Boolean = { false })
fun interface BleTransport {
    /** Called only inside an explicit foreground operation; owns only its own discovery reference. */
    fun start(request: BleScanRequest, report: (Advertisement) -> Unit, failure: (String) -> Unit): RadioSession
}
interface SqlStore : AutoCloseable {
    fun execute(sql: String, vararg args: Any?)
    fun query(sql: String, vararg args: Any?): List<List<String?>>
    fun <T> transaction(block: () -> T): T
}
fun interface SqlStoreFactory { fun open(): SqlStore }

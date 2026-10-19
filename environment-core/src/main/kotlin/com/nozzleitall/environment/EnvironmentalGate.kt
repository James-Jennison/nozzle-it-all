package com.nozzleitall.environment

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import org.json.JSONObject

enum class EnvironmentalState { DISABLED, DISABLED_RECOVERY, ENABLED_IDLE, DISCOVERING, COLLECTING }
enum class Purpose { DISCOVERY, COLLECTION }
class EnvironmentalGrant internal constructor(internal val issuer: Any, internal val session: String,
    internal val epoch: Long, val purpose: Purpose, internal val expires: Long,
    internal val bindings: Map<String, Long>, val incarnation: String)

/** Single admission/commit lock: revocation cannot race a history transaction. No live grants survive boot. */
class EnvironmentalGate(private val file: File, private val newIdentifier: () -> String = ::newId,
    private val now: () -> Long = System::nanoTime) {
    private val issuer = Any()
    val bootId: String by lazy { newIdentifier() }
    private val session get() = bootId
    private var epoch = 0L
    var incarnation: String = ""; private set
    var collectorId: String = ""; private set
    var state: EnvironmentalState = EnvironmentalState.DISABLED; private set
    var persistenceFailure = false; private set
    var deletePending = false; private set
    init {
        if (file.exists()) runCatching {
            require(file.length() <= 4096)
            val record = JSONObject(file.readText())
            val body = record.getString("body")
            require(record.getString("sha256") == hash(body))
            val control = JSONObject(body)
            require(control.getInt("version") == 1)
            incarnation = UUIDCheck(control.getString("incarnation"))
            collectorId = UUIDCheck(control.getString("collector"))
            epoch = control.getLong("epoch"); require(epoch >= 0)
            val intent = control.getString("intent")
            deletePending = intent == "delete-pending"
            state = when (intent) {
                "enabled" -> EnvironmentalState.ENABLED_IDLE
                "disabled" -> EnvironmentalState.DISABLED
                else -> EnvironmentalState.DISABLED_RECOVERY
            }
        }.onFailure { state = EnvironmentalState.DISABLED_RECOVERY }
    }
    @Synchronized fun enable() { check(state != EnvironmentalState.DISABLED_RECOVERY); persist("enabled"); state = EnvironmentalState.ENABLED_IDLE }
    @Synchronized fun revoke(disable: Boolean = false) {
        check(state != EnvironmentalState.DISABLED_RECOVERY) { "Explicit environmental cleanup/recovery required; control preserved" }
        epoch++
        state = if (disable) EnvironmentalState.DISABLED else if (state == EnvironmentalState.DISABLED) state else EnvironmentalState.ENABLED_IDLE
        try { persist(if (state == EnvironmentalState.ENABLED_IDLE) "enabled" else "disabled") }
        catch (e: Exception) { persistenceFailure = true; state = EnvironmentalState.DISABLED_RECOVERY; throw e }
    }
    @Synchronized fun begin(purpose: Purpose, bindings: List<Binding>, durationNanos: Long): EnvironmentalGrant {
        check(state == EnvironmentalState.ENABLED_IDLE); require(durationNanos in 1..86_400_000_000_000L)
        check(bindings.none { it.archived })
        epoch++
        persist("enabled")
        state = if (purpose == Purpose.DISCOVERY) EnvironmentalState.DISCOVERING else EnvironmentalState.COLLECTING
        return EnvironmentalGrant(issuer, session, epoch, purpose, now() + durationNanos,
            bindings.associate { it.id to it.revision }, incarnation)
    }
    @Synchronized fun valid(grant: EnvironmentalGrant, binding: Binding? = null): Boolean =
        grant.issuer === issuer && grant.session == session && grant.epoch == epoch && grant.incarnation == incarnation &&
        now() < grant.expires && state == (if (grant.purpose == Purpose.DISCOVERY) EnvironmentalState.DISCOVERING else EnvironmentalState.COLLECTING) &&
        (binding == null || (!binding.archived && grant.bindings[binding.id] == binding.revision))
    @Synchronized fun <T> commit(grant: EnvironmentalGrant, binding: Binding, block: () -> T): T {
        check(grant.purpose == Purpose.COLLECTION && valid(grant, binding)) { "Stale or unauthorized environmental operation" }
        return block()
    }
    @Synchronized fun <T> commitBatch(grant: EnvironmentalGrant, bindings: List<Binding>, block: () -> T): T {
        require(bindings.size in 1..32)
        check(grant.purpose == Purpose.COLLECTION && bindings.all { valid(grant, it) }) { "Stale or unauthorized environmental batch" }
        return block()
    }
    @Synchronized fun deleteIntent() {
        if (deletePending) return // Explicit resume uses the already committed intent/incarnation.
        epoch++; state = EnvironmentalState.DISABLED_RECOVERY; incarnation = newIdentifier()
        persist("delete-pending"); deletePending = true
    }
    @Synchronized fun finishDelete() { persist("disabled"); deletePending = false; state = EnvironmentalState.DISABLED }
    private fun persist(intent: String) {
        // Identifier/entropy providers are not touched by disabled startup or descriptor construction.
        if (incarnation.isEmpty()) incarnation = newIdentifier()
        if (collectorId.isEmpty()) collectorId = newIdentifier()
        val body = JSONObject().put("version", 1).put("incarnation", incarnation).put("collector", collectorId).put("epoch", epoch).put("intent", intent).toString()
        file.parentFile.mkdirs()
        if (System.getProperty("os.name").orEmpty().contains("Linux", true)) Files.setPosixFilePermissions(file.parentFile.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        val tmp = File.createTempFile(".control-", ".tmp", file.parentFile)
        try {
            FileOutputStream(tmp).use { it.write(JSONObject().put("body", body).put("sha256", hash(body)).toString().toByteArray()); it.fd.sync() }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(file.parentFile.toPath(), StandardOpenOption.READ).use { it.force(true) }
            persistenceFailure = false
        } finally { if (tmp.exists()) tmp.delete() }
    }
    companion object {
        private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        private fun UUIDCheck(s: String) = java.util.UUID.fromString(s).toString()
    }
}

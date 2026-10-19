package com.nozzleitall.environment

import org.json.JSONArray
import org.json.JSONObject

/** Own lazy database, never the project DB. All access is serialized by the coordinator. */
class EnvironmentalHistory(private val factory: SqlStoreFactory, private val limits: EnvironmentalLimits = EnvironmentalLimits()) : AutoCloseable {
    @Volatile private var db: SqlStore? = null
    val isOpen get() = db != null
    private fun store(): SqlStore {
        db?.let { return it }
        val sql = factory.open()
        try {
            val version = sql.query("PRAGMA user_version").first().first()!!.toInt()
            require(version in 0..1) { "Unsupported environmental history schema; preserved without migration" }
            if (version == 0) sql.transaction {
                sql.execute("CREATE TABLE entities(kind TEXT NOT NULL,id TEXT NOT NULL,json TEXT NOT NULL,PRIMARY KEY(kind,id))")
                sql.execute("CREATE TABLE events(id TEXT PRIMARY KEY, binding TEXT NOT NULL, observed INTEGER NOT NULL, received INTEGER NOT NULL, immutable TEXT NOT NULL)")
                sql.execute("CREATE INDEX events_time ON events(observed,id)")
                sql.execute("CREATE INDEX events_binding ON events(binding,observed,id)")
                sql.execute("CREATE TABLE conflicts(id TEXT PRIMARY KEY, observed INTEGER NOT NULL, reason TEXT NOT NULL)")
                sql.execute("CREATE TABLE gaps(id TEXT PRIMARY KEY, observed INTEGER NOT NULL, dropped INTEGER NOT NULL)")
                sql.execute("PRAGMA user_version=1")
            }
            // Bound allocation including free pages. No WAL or unbounded sidecar.
            sql.execute("PRAGMA journal_mode=DELETE")
            sql.execute("PRAGMA secure_delete=ON")
            val pageSize = sql.query("PRAGMA page_size").first().first()!!.toLong()
            val maximum = limits.historyBytes / pageSize
            require(sql.query("PRAGMA max_page_count=$maximum").first().first()!!.toLong() <= maximum) { "History exceeds configured disk budget; explicit export/retention needed" }
            db = sql
            return sql
        } catch (e: Exception) { sql.close(); throw e }
    }
    @Synchronized fun entities(kind: String): List<JSONObject> = store().query("SELECT json FROM entities WHERE kind=? ORDER BY id", kind).map { JSONObject(it[0]!!) }
    @Synchronized fun save(kind: String, id: String, value: JSONObject) {
        require(kind in setOf("sensor", "binding", "equipment", "position", "placement", "residence", "calibration", "qualification"))
        require(value.toString().length <= 16384)
        val bytes = value.toString().toByteArray().size
        val existingBytes = store().query("SELECT COALESCE(SUM(length(CAST(json AS BLOB))),0) FROM entities WHERE kind=? AND id<>?", kind, id).first().first()!!.toLong()
        require(existingBytes + bytes <= 262144) { "Environmental metadata budget reached; export/delete unused drafts explicitly" }
        store().execute("INSERT OR REPLACE INTO entities(kind,id,json) VALUES(?,?,?)", kind, id, value.toString())
    }
    @Synchronized fun bind(candidate: Candidate, name: String): Binding {
        require(name.isNotBlank() && name.length <= 120)
        val s = Sensor(name = name)
        val b = Binding(sensorId = s.id, address = candidate.address, protocol = candidate.protocol, model = candidate.model)
        store().transaction {
            save("sensor", s.id, JSONObject().put("id", s.id).put("name", name))
            save("binding", b.id, bindingJson(b))
        }
        return b
    }
    @Synchronized fun bindings(): List<Binding> = entities("binding").map { j -> Binding(j.getString("id"), j.getString("sensorId"), j.getString("address"), j.getString("protocol"), j.getString("model"), if (j.isNull("firmware")) null else j.getString("firmware"), j.getLong("revision"), j.optBoolean("archived")) }
    @Synchronized fun renameSensor(id: String, name: String) {
        require(name.isNotBlank() && name.length <= 120)
        require(entities("sensor").any { it.getString("id") == id })
        save("sensor", id, JSONObject().put("id", id).put("name", name))
    }
    @Synchronized fun recordFirmware(binding: Binding, firmware: String): Binding {
        require(firmware.isNotBlank() && firmware.length <= 120)
        require(bindings().any { it == binding && !it.archived })
        return binding.copy(firmware = firmware, revision = binding.revision + 1).also { save("binding", it.id, bindingJson(it)) }
    }
    @Synchronized fun recordQualification(q: CapabilityQualification) {
        val b = bindings().single { it.id == q.bindingId }
        require(!b.archived && q.bindingRevision == b.revision && q.model == b.model && q.firmware == b.firmware)
        require(listOf(q.firmware, q.platform, q.radio, q.decoder).all { it.isNotBlank() && it.length <= 256 })
        require(q.firmware.lowercase() !in setOf("unknown", "unavailable", "unqualified", "unspecified"))
        require(q.scanMode == "active" && q.decoder == BleDecoders.VERSION && q.calibrationVersion >= 0)
        require(q.minC.isFinite() && q.maxC.isFinite() && q.minC <= q.maxC)
        require(q.cadenceMinMillis > 0 && q.cadenceMaxMillis >= q.cadenceMinMillis)
        require(q.verifiedAt > 0 && q.expiresAt > q.verifiedAt)
        // References to owner-reviewed physical artifacts, never a boolean self-certification or a fixture badge.
        require(q.evidence.keys.containsAll(setOf("identity", "firmware", "thermal", "calibration", "cadence", "coexistence", "ownerApproval")))
        require(q.evidence.size <= 16 && q.evidence.all { (k, v) -> k.length <= 80 && v.isNotBlank() && v.length <= 512 })
        save("qualification", q.id, JSONObject().put("id", q.id).put("bindingId", q.bindingId).put("bindingRevision", q.bindingRevision)
            .put("model", q.model).put("firmware", q.firmware).put("platform", q.platform).put("radio", q.radio)
            .put("decoder", q.decoder).put("scanMode", q.scanMode).put("channel", q.channel.wire).put("calibrationVersion", q.calibrationVersion)
            .put("minC", q.minC).put("maxC", q.maxC).put("cadenceMinMillis", q.cadenceMinMillis).put("cadenceMaxMillis", q.cadenceMaxMillis)
            .put("verifiedAt", q.verifiedAt).put("expiresAt", q.expiresAt).put("evidence", JSONObject(q.evidence)).put("archived", q.archived))
    }
    /** Empty for unassigned diagnostics; throws for any assigned capability lacking exact current evidence. */
    @Synchronized fun assignedQualificationRadios(bindings: List<Binding>, platform: String, now: Long): QualificationAdmission {
        val radios = mutableSetOf<String>()
        var validUntil = Long.MAX_VALUE
        for (b in bindings) for (placement in entities("placement").filter { it.getString("sensorId") == b.sensorId && it.isNull("until") }) {
            val p = entities("position").single { it.getString("id") == placement.getString("positionId") }
            require(!p.isNull("minC") && !p.isNull("maxC")) { "Assigned position needs a demonstrated operating envelope" }
            for (channel in listOf(Channel.TEMPERATURE, Channel.HUMIDITY)) {
                val calibration = calibrated(b, listOf(Reading(channel, 0.0))).single().calibrationVersion
                val q = entities("qualification").firstOrNull { q ->
                    !q.optBoolean("archived") && q.getString("bindingId") == b.id && q.getLong("bindingRevision") == b.revision &&
                        b.firmware != null && q.getString("model") == b.model && q.getString("firmware") == b.firmware &&
                        q.getString("platform") == platform && q.getString("decoder") == BleDecoders.VERSION && q.getString("scanMode") == "active" &&
                        q.getString("channel") == channel.wire && q.getInt("calibrationVersion") == calibration &&
                        q.getLong("verifiedAt") <= now && now < q.getLong("expiresAt") &&
                        q.getDouble("minC") <= p.getDouble("minC") && q.getDouble("maxC") >= p.getDouble("maxC")
                } ?: error("Assigned sensors require current channel-specific physical qualification")
                radios += q.getString("radio")
                validUntil = minOf(validUntil, q.getLong("expiresAt"))
            }
        }
        require(radios.size <= 1) { "Assigned capabilities require the same explicitly qualified adapter" }
        return QualificationAdmission(radios, validUntil)
    }
    @Synchronized fun saveEquipment(value: Equipment) {
        require(value.name.isNotBlank() && value.name.length <= 120 && value.type.length <= 80)
        save("equipment", value.id, JSONObject().put("id", value.id).put("name", value.name).put("type", value.type))
    }
    @Synchronized fun savePosition(value: Position) {
        require(value.name.isNotBlank() && value.name.length <= 120)
        require(entities("equipment").any { it.getString("id") == value.equipmentId })
        val min = value.minC; val max = value.maxC
        require(min == null || min.isFinite())
        require(max == null || max.isFinite())
        require(min == null || max == null || min <= max)
        save("position", value.id, JSONObject().put("id", value.id).put("equipmentId", value.equipmentId).put("name", value.name).put("minC", value.minC ?: JSONObject.NULL).put("maxC", value.maxC ?: JSONObject.NULL))
    }
    @Synchronized fun reside(spool: String, position: String, at: Long) {
        require(spool.isNotBlank() && spool.length <= 120)
        require(entities("position").any { it.getString("id") == position })
        store().transaction {
            val old = entities("residence").filter { it.getString("spoolId") == spool }
            require(old.none { it.getLong("from") >= at })
            old.filter { it.isNull("until") }.forEach { it.put("until", at); save("residence", it.getString("id"), it) }
            val id = newId()
            save("residence", id, JSONObject().put("id", id).put("spoolId", spool).put("positionId", position).put("from", at).put("until", JSONObject.NULL))
        }
    }
    @Synchronized fun calibrate(binding: Binding, channel: Channel, offset: Double): Int {
        require(offset.isFinite() && offset in -100.0..100.0)
        require(bindings().any { it.id == binding.id && !it.archived })
        val version = entities("calibration").filter { it.getString("binding") == binding.id && it.getString("channel") == channel.wire }.maxOfOrNull { it.getInt("version") }?.plus(1) ?: 1
        val id = newId()
        save("calibration", id, JSONObject().put("id", id).put("binding", binding.id).put("channel", channel.wire).put("version", version).put("offset", offset).put("createdAt", System.currentTimeMillis()))
        return version
    }
    @Synchronized fun calibrated(binding: Binding, readings: List<Reading>): List<Reading> {
        val versions = entities("calibration").filter { it.getString("binding") == binding.id }.groupBy { it.getString("channel") }.mapValues { it.value.maxBy { j -> j.getInt("version") } }
        return readings.map { r -> versions[r.channel.wire]?.let { r.copy(offset = it.getDouble("offset"), calibrationVersion = it.getInt("version")) } ?: r }
    }
    @Synchronized fun archive(binding: Binding) = save("binding", binding.id, bindingJson(binding.copy(archived = true, revision = binding.revision + 1)))
    @Synchronized fun assign(sensorId: String, positionId: String, at: Long) {
        store().transaction { assignInside(sensorId, positionId, at) }
    }
    @Synchronized fun configureDraft(binding: Binding, equipment: Equipment, position: Position, at: Long) {
        store().transaction {
            saveEquipment(equipment); savePosition(position)
            if (entities("placement").none { it.getString("sensorId") == binding.sensorId && it.getString("positionId") == position.id && it.isNull("until") }) assignInside(binding.sensorId, position.id, at)
        }
    }
    private fun assignInside(sensorId: String, positionId: String, at: Long) {
        require(entities("sensor").any { it.getString("id") == sensorId })
        require(entities("position").any { it.getString("id") == positionId })
            val previous = entities("placement").filter { it.getString("sensorId") == sensorId }
            require(previous.none { it.getLong("from") >= at }) { "Assignment would rewrite existing history" }
            previous.filter { it.isNull("until") }.forEach { it.put("until", at); save("placement", it.getString("id"), it) }
            val id = newId()
            save("placement", id, JSONObject().put("id", id).put("sensorId", sensorId).put("positionId", positionId).put("from", at).put("until", JSONObject.NULL))
    }
    /** ReceivedAt intentionally excluded: a retry must not become a conflicting physical sample. */
    fun immutable(o: Observation): String = ObservationEncoding.encode(o)
    @Synchronized fun append(o: Observation, currentIncarnation: String): String {
        return store().transaction { insert(o, currentIncarnation) }
    }
    @Synchronized fun appendBatch(observations: List<Observation>, currentIncarnation: String): List<String> {
        require(observations.size in 1..32)
        return store().transaction {
            val results = observations.map { insert(it, currentIncarnation) }
            val last = observations.last()
            if (last.droppedBefore > 0) store().execute("INSERT OR REPLACE INTO gaps(id,observed,dropped) VALUES(?,?,?)", last.sessionId, last.observedAt, last.droppedBefore)
            results
        }
    }
    @Synchronized fun recordedDrops(): Long = store().query("SELECT COALESCE(SUM(dropped),0) FROM gaps").first().first()!!.toLong()
    @Synchronized fun recordGap(sessionId: String, observedAt: Long, dropped: Long) {
        require(dropped > 0)
        store().transaction { store().execute("INSERT OR REPLACE INTO gaps(id,observed,dropped) VALUES(?,?,?)", sessionId, observedAt, dropped) }
    }
    @Synchronized fun eventCount(): Long = store().query("SELECT COUNT(*) FROM events").first().first()!!.toLong()
    private fun insert(o: Observation, currentIncarnation: String): String {
        require(o.incarnation == currentIncarnation)
        require(o.readings.size in 1..16)
        val payload = immutable(o)
        return run {
            val existing = store().query("SELECT immutable FROM events WHERE id=?", o.eventId).firstOrNull()?.first()
            when {
                existing == payload -> "duplicate"
                existing != null -> { store().execute("INSERT OR IGNORE INTO conflicts(id,observed,reason) VALUES(?,?,?)", o.eventId, o.observedAt, "Conflicting immutable event; quarantined"); "conflict" }
                else -> { store().execute("INSERT INTO events(id,binding,observed,received,immutable) VALUES(?,?,?,?,?)", o.eventId, o.bindingId, o.observedAt, o.receivedAt, payload); "inserted" }
            }
        }
    }
    @Synchronized fun page(before: Long = Long.MAX_VALUE, beforeId: String = "~", limit: Int = 500): List<String> {
        require(limit in 1..500)
        return store().query("SELECT immutable,received FROM events WHERE observed<? OR (observed=? AND id<?) ORDER BY observed DESC,id DESC LIMIT ?", before, before, beforeId, limit).map { JSONObject(it[0]!!).put("receivedAt", it[1]!!.toLong()).toString() }
    }
    @Synchronized fun retain(now: Long, days: Int = 30) {
        require(days in 1..3650)
        store().transaction {
            store().execute("DELETE FROM events WHERE observed<?", now - days * 86_400_000L)
            store().execute("DELETE FROM conflicts WHERE observed<?", now - days * 86_400_000L)
            store().execute("DELETE FROM gaps WHERE observed<?", now - days * 86_400_000L)
        }
    }
    @Synchronized fun export(write: (String) -> Unit) {
        write(JSONObject().put("format", "nia-environment-history").put("version", 1).toString())
        write(JSONObject().put("knownDroppedReports", recordedDrops()).put("coverage", "Reception history only; physical measurement age and unobserved gaps unknown").toString())
        for (kind in listOf("sensor", "binding", "equipment", "position", "placement", "residence", "calibration", "qualification")) entities(kind).forEach { write(JSONObject().put("kind", kind).put("entity", it).toString()) }
        var time = Long.MAX_VALUE; var id = "~"
        while (true) {
            val page = page(time, id)
            if (page.isEmpty()) break
            page.forEach(write)
            val last = JSONObject(page.last()); time = last.getLong("observedAt"); id = last.getString("eventId")
        }
    }
    @Synchronized fun deleteAll() {
        store().transaction { store().execute("DELETE FROM events"); store().execute("DELETE FROM entities"); store().execute("DELETE FROM conflicts"); store().execute("DELETE FROM gaps") }
        store().execute("VACUUM")
        close()
    }
    @Synchronized override fun close() { db?.close(); db = null }
    private fun bindingJson(b: Binding) = JSONObject().put("id", b.id).put("sensorId", b.sensorId).put("address", b.address).put("protocol", b.protocol).put("model", b.model).put("firmware", b.firmware ?: JSONObject.NULL).put("revision", b.revision).put("archived", b.archived)
}

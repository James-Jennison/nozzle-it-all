package com.nozzleitall.environment

import org.json.JSONArray
import org.json.JSONObject

/** Own lazy database, never the project DB. All access is serialized by the coordinator. */
class EnvironmentalHistory(private val factory: SqlStoreFactory) : AutoCloseable {
    private var db: SqlStore? = null
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
                sql.execute("PRAGMA user_version=1")
            }
            // Bound allocation including free pages. No WAL or unbounded sidecar.
            sql.execute("PRAGMA journal_mode=DELETE")
            sql.execute("PRAGMA secure_delete=ON")
            val pageSize = sql.query("PRAGMA page_size").first().first()!!.toLong()
            sql.execute("PRAGMA max_page_count=${104857600L / pageSize}")
            db = sql
            return sql
        } catch (e: Exception) { sql.close(); throw e }
    }
    @Synchronized fun entities(kind: String): List<JSONObject> = store().query("SELECT json FROM entities WHERE kind=? ORDER BY id", kind).map { JSONObject(it[0]!!) }
    @Synchronized fun save(kind: String, id: String, value: JSONObject) {
        require(kind in setOf("sensor", "binding", "equipment", "position", "placement", "residence", "calibration"))
        require(value.toString().length <= 16384)
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
        save("calibration", id, JSONObject().put("id", id).put("binding", binding.id).put("channel", channel.wire).put("version", version).put("offset", offset))
        return version
    }
    @Synchronized fun calibrated(binding: Binding, readings: List<Reading>): List<Reading> {
        val versions = entities("calibration").filter { it.getString("binding") == binding.id }.groupBy { it.getString("channel") }.mapValues { it.value.maxBy { j -> j.getInt("version") } }
        return readings.map { r -> versions[r.channel.wire]?.let { r.copy(offset = it.getDouble("offset"), calibrationVersion = it.getInt("version")) } ?: r }
    }
    @Synchronized fun archive(binding: Binding) = save("binding", binding.id, bindingJson(binding.copy(archived = true, revision = binding.revision + 1)))
    @Synchronized fun assign(sensorId: String, positionId: String, at: Long) {
        require(entities("sensor").any { it.getString("id") == sensorId })
        require(entities("position").any { it.getString("id") == positionId })
        store().transaction {
            val previous = entities("placement").filter { it.getString("sensorId") == sensorId }
            require(previous.none { it.getLong("from") >= at }) { "Assignment would rewrite existing history" }
            previous.filter { it.isNull("until") }.forEach { it.put("until", at); save("placement", it.getString("id"), it) }
            val id = newId()
            save("placement", id, JSONObject().put("id", id).put("sensorId", sensorId).put("positionId", positionId).put("from", at).put("until", JSONObject.NULL))
        }
    }
    /** ReceivedAt intentionally excluded: a retry must not become a conflicting physical sample. */
    fun immutable(o: Observation): String = JSONObject().put("eventId", o.eventId).put("incarnation", o.incarnation)
        .put("collector", o.collectorId).put("session", o.sessionId).put("sequence", o.sequence)
        .put("binding", o.bindingId).put("bindingRevision", o.bindingRevision).put("observedAt", o.observedAt)
        .put("monotonicNanos", o.monotonicNanos).put("measuredAt", o.measuredAt ?: JSONObject.NULL)
        .put("sourceCounter", o.sourceCounter ?: JSONObject.NULL).put("decoder", o.decoder)
        .put("readings", JSONArray(o.readings.map { JSONObject().put("channel", it.channel.wire).put("raw", it.raw).put("offset", it.offset).put("calibrationVersion", it.calibrationVersion) })).toString()
    @Synchronized fun append(o: Observation, currentIncarnation: String): String {
        require(o.incarnation == currentIncarnation)
        require(o.readings.size in 1..16)
        val payload = immutable(o)
        return store().transaction {
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
        }
    }
    @Synchronized fun export(write: (String) -> Unit) {
        write(JSONObject().put("format", "nia-environment-history").put("version", 1).toString())
        for (kind in listOf("sensor", "binding", "equipment", "position", "placement", "residence", "calibration")) entities(kind).forEach { write(JSONObject().put("kind", kind).put("entity", it).toString()) }
        var time = Long.MAX_VALUE; var id = "~"
        while (true) {
            val page = page(time, id)
            if (page.isEmpty()) break
            page.forEach(write)
            val last = JSONObject(page.last()); time = last.getLong("observedAt"); id = last.getString("eventId")
        }
    }
    @Synchronized fun deleteAll() {
        store().transaction { store().execute("DELETE FROM events"); store().execute("DELETE FROM entities"); store().execute("DELETE FROM conflicts") }
        store().execute("VACUUM")
        close()
    }
    @Synchronized override fun close() { db?.close(); db = null }
    private fun bindingJson(b: Binding) = JSONObject().put("id", b.id).put("sensorId", b.sensorId).put("address", b.address).put("protocol", b.protocol).put("model", b.model).put("firmware", b.firmware ?: JSONObject.NULL).put("revision", b.revision).put("archived", b.archived)
}

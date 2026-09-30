package com.nozzleitall.testgrid

import org.json.JSONArray
import org.json.JSONObject

/** A test that already passed in an earlier run and is not repeated: where its evidence is. */
data class PriorPass(val runId: String, val completedAt: Long, val bundleDigest: String?) {
    fun toJson(): JSONObject = JSONObject().put("runId", runId).put("completedAt", completedAt).putOpt("bundleDigest", bundleDigest)
    companion object { fun fromJson(o: JSONObject) = PriorPass(o.getString("runId"), o.getLong("completedAt"), o.strOrNull("bundleDigest")) }
}

/**
 * Tests that already passed need not be run again at a higher level (the owner's rule: level 4 shouldn't repeat level
 * 2). A test carries over only from a completed physical run of the same suite, against the same printer ([printerKey],
 * which stays on the device), on the same firmware version and Nozzle version, and only when that test's own definition
 * is unchanged ([Suite.testDigest]). A carried test is recorded as SKIPPED with a pointer to the run that passed it; the
 * report grades each test from its newest actual result, so carrying never lowers or invents a grade.
 */
object CarryOver {
    /** What a device remembers about one finished run, to offer its passes later. Never exported. */
    data class RunSummary(val runId: String, val completedAt: Long, val bundleDigest: String?, val suiteId: String, val printerKey: String,
                          val firmwareFamily: String, val firmwareVersion: String, val nozzleVersion: String, val testDigests: Map<String, String>,
                          val passed: Set<String>) {
        fun toJson(): JSONObject = JSONObject().put("runId", runId).put("completedAt", completedAt).putOpt("bundleDigest", bundleDigest)
            .put("suiteId", suiteId).put("printerKey", printerKey).put("firmwareFamily", firmwareFamily).put("firmwareVersion", firmwareVersion)
            .put("nozzleVersion", nozzleVersion).put("testDigests", JSONObject(testDigests.toSortedMap())).put("passed", JSONArray(passed.sorted()))
        companion object {
            fun fromJson(o: JSONObject) = RunSummary(o.getString("runId"), o.getLong("completedAt"), o.strOrNull("bundleDigest"), o.getString("suiteId"),
                o.getString("printerKey"), o.getString("firmwareFamily"), o.getString("firmwareVersion"), o.getString("nozzleVersion"),
                o.getJSONObject("testDigests").let { d -> d.keySet().associateWith { d.getString(it) } }, o.strings("passed").toSet())
        }
    }

    /** The summary of a finished physical run, or null when it can't carry anything (simulated, or not finished). */
    fun summarize(session: RunSession, bundleDigest: String?, printerKey: String, nozzleVersion: String): RunSummary? {
        val r = session.record
        if (r.targetKind != TargetKind.PHYSICAL || r.completedAt == null) return null
        val fw = r.target.optJSONObject("firmware") ?: return null
        val passed = r.tests.filter { it.result == ResultState.PASS && carriedFrom(r, it.testId) == null }.map { it.testId }.toSet()
        return RunSummary(r.runId, r.completedAt!!, bundleDigest, r.suiteId, printerKey, fw.optString("family"), fw.optString("version"), nozzleVersion,
            session.suite.tests.associate { it.id to session.suite.testDigest(it.id) }, passed)
    }

    /** For each test of [suite], the newest earlier pass that may stand in for it on this printer now. */
    fun eligible(history: List<RunSummary>, suite: Suite, snapshot: TargetSnapshot, printerKey: String, nozzleVersion: String): Map<String, PriorPass> {
        val family = snapshot.classified.family
        val version = snapshot.identity?.firmware?.version.orEmpty()
        if (version.isBlank()) return emptyMap()
        val same = history.filter { it.suiteId == suite.id && it.printerKey == printerKey && it.firmwareFamily == family && it.firmwareVersion == version && it.nozzleVersion == nozzleVersion }
            .sortedByDescending { it.completedAt }
        return suite.tests.mapNotNull { t ->
            val digest = suite.testDigest(t.id)
            same.firstOrNull { t.id in it.passed && it.testDigests[t.id] == digest }?.let { t.id to PriorPass(it.runId, it.completedAt, it.bundleDigest) }
        }.toMap()
    }

    /**
     * The carried tests that really can be skipped at [maxLevel]: a test another running test takes input from (a slice
     * or an upload, named by `fromTest`) runs again, because its files belong to this run.
     */
    fun effective(suite: Suite, maxLevel: SafetyLevel, carried: Map<String, PriorPass>): Map<String, PriorPass> {
        var carry = carried.filterKeys { id -> suite.test(id)?.let { it.safetyLevel <= maxLevel } == true }
        while (true) {
            val running = suite.tests.filter { it.safetyLevel <= maxLevel && it.id !in carry }
            val needed = running.flatMap { t -> (t.steps + t.cleanup).mapNotNull { s -> s.params.optString("fromTest").takeIf { it.isNotBlank() } } }.toSet()
            val drop = carry.keys.intersect(needed)
            if (drop.isEmpty()) return carry
            carry = carry - drop
        }
    }

    fun carriedFrom(record: RunRecord, testId: String): PriorPass? =
        record.context.optJSONObject("carried")?.optJSONObject(testId)?.let(PriorPass::fromJson)
}

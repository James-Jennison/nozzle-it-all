package com.nozzleitall.testgrid

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * An evidence bundle: a ZIP of
 *   evidence.json    the run, canonical JSON (format [FORMAT] [MAJOR].[MINOR]), redacted
 *   logs/run.log     the run log, redacted
 *   attachments/...  required photos and files, metadata removed, text redacted
 *   integrity.json   SHA-256 of every other file, the bundle digest over them, the content digest of evidence.json
 *                    with volatile fields removed, and a reserved signing slot that is empty ("unsigned").
 *
 * Bundles are content-hashed, not signed: nothing here proves who produced a bundle. See docs/testgrid/EVIDENCE_FORMAT.md.
 */
class EvidenceBundle(val files: Map<String, ByteArray>) {
    val integrity: JSONObject get() = JSONObject(String(files.getValue(INTEGRITY), Charsets.UTF_8))
    val evidence: JSONObject get() = JSONObject(String(files.getValue(EVIDENCE), Charsets.UTF_8))
    val bundleDigest: String get() = integrity.getString("bundleDigest")
    val contentDigest: String get() = integrity.getString("contentDigest")

    /** Exactly what export writes, for the review screen: every path with its size, hash and (for text) full content. */
    fun preview(): List<PreviewEntry> = files.toSortedMap().map { (path, bytes) ->
        PreviewEntry(path, bytes.size.toLong(), Canon.sha256(bytes), if (isText(path)) String(bytes, Charsets.UTF_8) else null)
    }

    /** Deterministic ZIP: sorted entries, fixed timestamps. The integrity manifest covers content, not ZIP bytes. */
    fun zip(): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z ->
            z.setLevel(9)
            files.toSortedMap().forEach { (path, bytes) ->
                val e = ZipEntry(path); e.timeLocal = FIXED_TIME
                z.putNextEntry(e); z.write(bytes); z.closeEntry()
            }
        }
    }.toByteArray()

    data class PreviewEntry(val path: String, val bytes: Long, val sha256: String, val text: String?)

    companion object {
        const val FORMAT = "nozzle.evidence"
        const val INTEGRITY_FORMAT = "nozzle.evidence-integrity"
        const val MAJOR = 1
        const val MINOR = 0
        const val EVIDENCE = "evidence.json"
        const val LOG = "logs/run.log"
        const val INTEGRITY = "integrity.json"
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        private val FIXED_TIME: LocalDateTime = LocalDateTime.of(1980, 1, 1, 0, 0, 0)
        fun isText(path: String) = path.endsWith(".json") || path.endsWith(".log") || path.endsWith(".txt") || path.endsWith(".md")

        fun integrityFor(content: Map<String, ByteArray>, evidence: JSONObject): JSONObject {
            val hashes = content.toSortedMap().mapValues { Canon.sha256(it.value) }
            return JSONObject().put("format", INTEGRITY_FORMAT).put("version", JSONArray().put(MAJOR).put(MINOR)).put("algorithm", "sha-256")
                .put("files", JSONObject(hashes)).put("bundleDigest", Canon.sha256(Canon.bytes(hashes)))
                .put("contentDigest", Canon.contentDigest(evidence))
                // Versioned extension point. Signing needs a real key-management and verification design first; until
                // then every bundle says plainly that it is unsigned, and readers must not treat it as attributable.
                .put("signing", JSONObject().put("status", "unsigned").put("extension", "nozzle.evidence-signature").put("extensionVersion", 1).put("signatures", JSONArray()))
        }
    }
}

/**
 * Builds a bundle from a completed run. Only named fields leave the device: the target's address, saved name and
 * credentials are never copied, and every string goes through the [Redactor]. If anything private survives, the build
 * fails rather than export it.
 */
object EvidenceBuilder {
    class LeakDetected(val findings: List<String>) : IllegalStateException("Redaction left private data in the bundle: ${findings.joinToString()}. Nothing was exported.")

    fun build(session: RunSession, env: Environment, redactor: Redactor, attachment: (String) -> ByteArray): EvidenceBundle {
        val record = session.record
        require(record.completedAt != null) { "Finish the run before exporting its evidence." }
        require(record.unknown == null) { "Review the unknown outcome before exporting." }
        val suite = session.suite
        val content = sortedMapOf<String, ByteArray>()

        val tests = suite.tests.map { t ->
            val r = record.test(t.id)!!
            val evidence = r.attachments.map { a ->
                val path = "attachments/${t.id}/${a.evidenceId}.${Attachments.extension(a.mime)}"
                val raw = attachment(a.file)
                val bytes = if (a.mime.startsWith("text/") || a.mime == "application/json") redactor.text(String(raw, Charsets.UTF_8)).toByteArray(Charsets.UTF_8) else raw
                content[path] = bytes
                mapOf("id" to a.evidenceId, "file" to path, "mime" to a.mime, "bytes" to bytes.size, "sha256" to Canon.sha256(bytes))
            }
            val missing = t.requiredEvidence.filter { e -> r.attachments.none { it.evidenceId == e.id } &&
                t.steps.none { s -> s.kind == StepKind.OBSERVE && s.params.optString("evidence") == e.id && r.step(s.id)?.status in setOf(StepStatus.PASSED, StepStatus.PARTIAL, StepStatus.FAILED) } }.map { it.id }
            mapOf("id" to t.id, "title" to t.title, "category" to t.category.id, "scope" to t.scope.id, "safetyLevel" to t.safetyLevel.level,
                "state" to r.state.name, "result" to r.result.name, "reason" to r.reason, "startedAt" to r.startedAt, "finishedAt" to r.finishedAt,
                "preconditions" to r.preconditions, "expectedObservations" to t.expectedObservations,
                "steps" to (r.steps + r.cleanup).map { s -> stepJson(s) }, "evidence" to evidence, "missingEvidence" to missing)
        }

        // Grades per scope and category, from that category's own tests only.
        val grades = MaterialScope.entries.associate { scope ->
            scope.id to Category.entries.associate { cat ->
                val ts = suite.tests.filter { it.scope == scope && it.category == cat }
                val results = ts.map { record.test(it.id)!!.result }
                cat.id to mapOf("result" to (if (record.targetKind == TargetKind.SIMULATED && ts.isNotEmpty()) ResultState.UNVERIFIED else CategoryGrades.grade(results)).name,
                    "recordedResult" to CategoryGrades.grade(results).name, "tests" to ts.map { it.id })
            }
        }

        val requirements = suite.tests.mapNotNull { t ->
            val r = record.test(t.id)!!
            if (r.result in setOf(ResultState.SKIPPED, ResultState.BLOCKED, ResultState.UNVERIFIED)) mapOf("test" to t.id, "category" to t.category.id, "result" to r.result.name, "reason" to r.reason) else null
        }

        val slices = record.context.optJSONObject("slices")
        val inputs = slices?.keySet()?.sorted()?.map { k -> slices.getJSONObject(k).let { s ->
            mapOf("test" to k, "model" to s.optString("model"), "modelParts" to s.optJSONArray("modelParts"),
                "profile" to s.optJSONObject("profile"), "gcode" to mapOf("sha256" to s.optString("sha256"), "bytes" to s.optLong("bytes")),
                "simulatedSlicer" to s.optBoolean("simulatedSlicer")) } } ?: emptyList()
        val profile = inputs.firstOrNull()?.get("profile") as? JSONObject
        val profileInfo = profile?.optString("id")?.let { id -> runCatching { session.slicerProfile(id) }.getOrNull() }

        val target = JSONObject(record.target.toString())
        target.put("profile", profile ?: JSONObject.NULL)
        target.put("nozzle", JSONObject().put("diametersMm", JSONArray(profileInfo?.nozzleDiameters ?: emptyList<Double>())))
        target.put("materials", JSONArray(profileInfo?.filamentTypes ?: emptyList<String>()))

        val evidence = JSONObject(mapOf(
            "format" to EvidenceBundle.FORMAT, "version" to listOf(EvidenceBundle.MAJOR, EvidenceBundle.MINOR),
            "run" to mapOf("runId" to record.runId, "startedAt" to record.startedAt, "completedAt" to record.completedAt,
                "maxSafetyLevel" to record.maxLevel, "restarts" to record.restarts, "supersedes" to record.supersedes),
            "producer" to mapOf("app" to env.producer.app, "platform" to env.producer.platform, "version" to env.producer.version,
                "build" to env.producer.build, "applicationId" to env.producer.applicationId, "sourceRevision" to env.producer.sourceRevision),
            "engine" to mapOf("name" to env.engine.name, "commit" to env.engine.commit, "version" to env.engine.version,
                "binarySha256" to env.engine.binarySha256, "pinSha256" to env.engine.pinSha256, "simulated" to env.engine.simulated),
            "suite" to mapOf("id" to suite.id, "version" to suite.version, "formatVersion" to listOf(Suite.MAJOR, suite.formatMinor),
                "digest" to suite.digest, "coverage" to suite.coverage.id, "title" to suite.title),
            "target" to target,
            "inputs" to inputs,
            "tests" to tests,
            "grades" to grades,
            "requirements" to requirements,
            "interventions" to record.interventions.map { mapOf("at" to it.at, "test" to it.testId, "step" to it.stepId, "kind" to it.kind, "text" to it.text) },
            "log" to EvidenceBundle.LOG,
        ).mapValues { toJsonValue(it.value) })

        val redacted = redactor.json(evidence) as JSONObject
        val logText = record.log.joinToString("\n", postfix = "\n") { "+${(it.at - record.startedAt) / 1000.0}s ${it.text}" }
        content[EvidenceBundle.LOG] = redactor.text(logText).toByteArray(Charsets.UTF_8)
        redacted.put("redaction", JSONObject().put("rulesVersion", Redactor.RULES_VERSION).put("counts", JSONObject(redactor.counts)))
        content[EvidenceBundle.EVIDENCE] = Canon.bytes(redacted)

        val leaks = content.filterKeys { EvidenceBundle.isText(it) }.values.flatMap { redactor.leaks(String(it, Charsets.UTF_8)) }.distinct()
        if (leaks.isNotEmpty()) throw LeakDetected(leaks)

        val integrity = EvidenceBundle.integrityFor(content, JSONObject(String(content.getValue(EvidenceBundle.EVIDENCE), Charsets.UTF_8)))
        return EvidenceBundle(content + (EvidenceBundle.INTEGRITY to Canon.bytes(integrity)))
    }

    private fun stepJson(s: StepRecord): Map<String, Any?> = mapOf("id" to s.stepId, "kind" to s.kind, "phase" to s.phase, "status" to s.status.name,
        "detail" to s.detail, "data" to s.data, "startedAt" to s.startedAt, "finishedAt" to s.finishedAt,
        "confirmation" to s.confirmation?.let { mapOf("action" to it.action, "approvedAt" to it.approvedAt) })

    /** Converts Kotlin maps/lists (with nulls) into org.json values so Canon and the redactor see one shape. */
    fun toJsonValue(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray -> v
        is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), toJsonValue(x)) } }
        is Iterable<*> -> JSONArray().also { a -> v.forEach { a.put(toJsonValue(it)) } }
        is Enum<*> -> v.name
        else -> v
    }
}

/** Reads and verifies a bundle. Anything corrupt, tampered, incomplete or of an unsupported major version is rejected. */
object BundleReader {
    sealed class Result {
        data class Valid(val bundle: EvidenceBundle) : Result()
        data class Invalid(val reasons: List<String>) : Result()
    }

    fun read(zip: ByteArray): Result {
        val files = sortedMapOf<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                var total = 0L
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name
                    if (name.startsWith("/") || name.contains("..") || name.contains('\\') || name.isBlank()) return Result.Invalid(listOf("Unsafe path in bundle: $name"))
                    if (name in files) return Result.Invalid(listOf("Duplicate entry: $name"))
                    val bytes = z.readBytes()
                    total += bytes.size
                    if (total > EvidenceBundle.MAX_TOTAL_BYTES) return Result.Invalid(listOf("Bundle exceeds ${EvidenceBundle.MAX_TOTAL_BYTES / (1024 * 1024)} MB."))
                    files[name] = bytes
                }
            }
        } catch (e: Exception) { return Result.Invalid(listOf("Not a readable ZIP: ${e.message}")) }
        if (files.isEmpty()) return Result.Invalid(listOf("Not a readable ZIP (no entries)."))
        return verify(files)
    }

    fun verify(files: Map<String, ByteArray>): Result {
        val reasons = mutableListOf<String>()
        val integrityBytes = files[EvidenceBundle.INTEGRITY] ?: return Result.Invalid(listOf("integrity.json is missing."))
        val integrity = try { JSONObject(String(integrityBytes, Charsets.UTF_8)) } catch (e: Exception) { return Result.Invalid(listOf("integrity.json is not valid JSON.")) }
        if (integrity.optString("format") != EvidenceBundle.INTEGRITY_FORMAT) reasons += "integrity.json has the wrong format."
        if (integrity.optJSONArray("version")?.optInt(0, -1) != EvidenceBundle.MAJOR) reasons += "Unsupported integrity format version."
        if (integrity.optString("algorithm") != "sha-256") reasons += "Unsupported hash algorithm."
        val listed = integrity.optJSONObject("files") ?: return Result.Invalid(reasons + "integrity.json lists no files.")
        val content = files - EvidenceBundle.INTEGRITY
        listed.keySet().forEach { p ->
            val b = content[p]
            if (b == null) reasons += "Listed file missing: $p"
            else if (Canon.sha256(b) != listed.optString(p)) reasons += "Hash mismatch (modified after export): $p"
        }
        content.keys.filter { !listed.has(it) }.forEach { reasons += "File not covered by the integrity manifest: $it" }
        val recomputed = Canon.sha256(Canon.bytes(content.toSortedMap().mapValues { Canon.sha256(it.value) }))
        if (reasons.isEmpty() && recomputed != integrity.optString("bundleDigest")) reasons += "Bundle digest mismatch."
        val evidenceBytes = content[EvidenceBundle.EVIDENCE] ?: return Result.Invalid(reasons + "evidence.json is missing.")
        val ev = try { JSONObject(String(evidenceBytes, Charsets.UTF_8)) } catch (e: Exception) { return Result.Invalid(reasons + "evidence.json is not valid JSON.") }
        if (ev.optString("format") != EvidenceBundle.FORMAT) reasons += "evidence.json has the wrong format."
        val major = ev.optJSONArray("version")?.optInt(0, -1) ?: -1
        if (major != EvidenceBundle.MAJOR) reasons += "Unsupported evidence format version $major (this build reads ${EvidenceBundle.MAJOR})."
        if (Canon.contentDigest(ev) != integrity.optString("contentDigest")) reasons += "Content digest mismatch."
        // Completeness: an unfinished or partial record can't grade anything.
        val run = ev.optJSONObject("run")
        if (run == null || run.isNull("completedAt") || !run.has("completedAt")) reasons += "Incomplete: the run was not completed."
        if (run?.optString("runId").isNullOrBlank()) reasons += "Incomplete: no run id."
        val target = ev.optJSONObject("target")
        if (target == null || TargetKind.parse(target.optString("kind")) == null) reasons += "Incomplete: no target kind."
        if (target?.optJSONObject("firmware")?.optString("family").isNullOrBlank()) reasons += "Incomplete: no firmware family."
        if (ev.optJSONObject("suite")?.optString("id").isNullOrBlank()) reasons += "Incomplete: no suite."
        val tests = ev.optJSONArray("tests")
        if (tests == null || tests.length() == 0) reasons += "Incomplete: no test results."
        else (0 until tests.length()).map { tests.optJSONObject(it) }.forEach { t ->
            if (t == null || ResultState.parse(t.optString("result")) == null || Category.parse(t.optString("category")) == null) reasons += "Incomplete: a test result is malformed."
            t?.optJSONArray("evidence")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.forEach { e -> if (e.optString("file") !in content) reasons += "Attachment missing: ${e.optString("file")}" } }
        }
        if (ev.optJSONObject("producer")?.optString("version").isNullOrBlank()) reasons += "Incomplete: no Nozzle version."
        return if (reasons.isEmpty()) Result.Valid(EvidenceBundle(files.toSortedMap())) else Result.Invalid(reasons.distinct())
    }
}

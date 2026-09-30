package com.nozzleitall.testgrid

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A test's lifecycle. PARTIAL results end in FAILED (not a full pass); the recorded [ResultState] says which. */
enum class RunState(val terminal: Boolean) {
    NOT_STARTED(false), PRECONDITIONS(false), AWAITING_CONFIRMATION(false), RUNNING(false), AWAITING_OBSERVATION(false),
    PASSED(true), FAILED(true), SKIPPED(true), BLOCKED(true), INTERRUPTED(true), OUTCOME_UNKNOWN(true);
}

enum class StepStatus(val done: Boolean) {
    PENDING(false), AWAITING_CONFIRMATION(false),
    /** Written to the journal before a mutating request is sent. Found on restart, it becomes OUTCOME_UNKNOWN. */
    DISPATCHING(false),
    AWAITING_OBSERVATION(false),
    PASSED(true), PARTIAL(true), FAILED(true), SKIPPED(true), BLOCKED(true), OUTCOME_UNKNOWN(true);
}

data class ConfirmationRecord(val action: String, val target: String, val approvedAt: Long)

class StepRecord(
    val stepId: String, val kind: String, val phase: String,
    var status: StepStatus = StepStatus.PENDING, var detail: String = "", var data: JSONObject = JSONObject(),
    var startedAt: Long? = null, var finishedAt: Long? = null, var confirmation: ConfirmationRecord? = null,
)

data class AttachmentRecord(val evidenceId: String, val stepId: String, val file: String, val mime: String, val sha256: String, val bytes: Long)

data class Intervention(val at: Long, val testId: String?, val stepId: String?, val kind: String, val text: String)

data class LogLine(val at: Long, val text: String)

class TestRecord(
    val testId: String,
    var state: RunState = RunState.NOT_STARTED,
    var result: ResultState = ResultState.UNVERIFIED,
    var reason: String = "",
    val steps: MutableList<StepRecord> = mutableListOf(),
    val cleanup: MutableList<StepRecord> = mutableListOf(),
    val preconditions: MutableMap<String, Boolean> = sortedMapOf(),
    val attachments: MutableList<AttachmentRecord> = mutableListOf(),
    var startedAt: Long? = null, var finishedAt: Long? = null,
    var cleanupDone: Boolean = false,
) {
    fun step(id: String): StepRecord? = steps.firstOrNull { it.stepId == id } ?: cleanup.firstOrNull { it.stepId == id }
    val touchedPrinter: Boolean get() = steps.any { it.status != StepStatus.PENDING && it.status != StepStatus.SKIPPED && it.status != StepStatus.BLOCKED }
}

/** The one unresolved unknown outcome, if any. While set, the runner refuses every further action. */
data class UnknownMarker(val testId: String, val stepId: String, val since: Long, val action: String)

/**
 * Everything about one run, persisted after every transition. [context] holds local working values (file paths, the
 * remote filename); the evidence builder copies named fields out of it and never exports it wholesale.
 */
class RunRecord(
    val runId: String,
    val suiteId: String, val suiteVersion: String, val suiteDigest: String,
    val maxLevel: Int,
    val targetKind: TargetKind,
    /** Exportable snapshot of the target (no address, no label): filled by TargetCheck and the identity step. */
    val target: JSONObject,
    val startedAt: Long,
    val tests: List<TestRecord>,
    var completedAt: Long? = null,
    var cursor: Int = 0,
    var unknown: UnknownMarker? = null,
    val interventions: MutableList<Intervention> = mutableListOf(),
    val log: MutableList<LogLine> = mutableListOf(),
    val context: JSONObject = JSONObject(),
    val supersedes: List<String> = emptyList(),
    var restarts: Int = 0,
) {
    fun test(id: String): TestRecord? = tests.firstOrNull { it.testId == id }

    fun toJson(): JSONObject = JSONObject()
        .put("journal", JOURNAL_FORMAT).put("journalVersion", 1)
        .put("runId", runId).put("suiteId", suiteId).put("suiteVersion", suiteVersion).put("suiteDigest", suiteDigest)
        .put("maxLevel", maxLevel).put("targetKind", targetKind.id).put("target", target).put("startedAt", startedAt)
        .putOpt("completedAt", completedAt).put("cursor", cursor).put("restarts", restarts)
        .putOpt("unknown", unknown?.let { JSONObject().put("testId", it.testId).put("stepId", it.stepId).put("since", it.since).put("action", it.action) })
        .put("supersedes", JSONArray(supersedes)).put("context", context)
        .put("interventions", JSONArray(interventions.map { JSONObject().put("at", it.at).putOpt("testId", it.testId).putOpt("stepId", it.stepId).put("kind", it.kind).put("text", it.text) }))
        .put("log", JSONArray(log.map { JSONObject().put("at", it.at).put("text", it.text) }))
        .put("tests", JSONArray(tests.map { t ->
            JSONObject().put("testId", t.testId).put("state", t.state.name).put("result", t.result.name).put("reason", t.reason)
                .putOpt("startedAt", t.startedAt).putOpt("finishedAt", t.finishedAt).put("cleanupDone", t.cleanupDone)
                .put("preconditions", JSONObject(t.preconditions))
                .put("steps", JSONArray(t.steps.map { stepJson(it) })).put("cleanup", JSONArray(t.cleanup.map { stepJson(it) }))
                .put("attachments", JSONArray(t.attachments.map { a -> JSONObject().put("evidenceId", a.evidenceId).put("stepId", a.stepId).put("file", a.file).put("mime", a.mime).put("sha256", a.sha256).put("bytes", a.bytes) }))
        }))

    companion object {
        const val JOURNAL_FORMAT = "nozzle.test-run-journal"

        private fun stepJson(s: StepRecord) = JSONObject().put("stepId", s.stepId).put("kind", s.kind).put("phase", s.phase).put("status", s.status.name)
            .put("detail", s.detail).put("data", s.data).putOpt("startedAt", s.startedAt).putOpt("finishedAt", s.finishedAt)
            .putOpt("confirmation", s.confirmation?.let { JSONObject().put("action", it.action).put("target", it.target).put("approvedAt", it.approvedAt) })

        private fun step(o: JSONObject) = StepRecord(o.getString("stepId"), o.optString("kind"), o.optString("phase", "main"),
            StepStatus.valueOf(o.getString("status")), o.optString("detail"), o.optJSONObject("data") ?: JSONObject(),
            o.optLongOrNull("startedAt"), o.optLongOrNull("finishedAt"),
            o.optJSONObject("confirmation")?.let { ConfirmationRecord(it.optString("action"), it.optString("target"), it.optLong("approvedAt")) })

        private fun JSONObject.optLongOrNull(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null

        fun fromJson(o: JSONObject): RunRecord {
            require(o.optString("journal") == JOURNAL_FORMAT) { "Not a Test Grid run journal." }
            val tests = o.objects("tests").map { t ->
                TestRecord(t.getString("testId"), RunState.valueOf(t.getString("state")), ResultState.valueOf(t.getString("result")), t.optString("reason"),
                    t.objects("steps").map { step(it) }.toMutableList(), t.objects("cleanup").map { step(it) }.toMutableList(),
                    t.optJSONObject("preconditions")?.let { p -> p.keySet().associateWith { p.optBoolean(it) }.toSortedMap() } ?: sortedMapOf(),
                    t.objects("attachments").map { AttachmentRecord(it.optString("evidenceId"), it.optString("stepId"), it.optString("file"), it.optString("mime"), it.optString("sha256"), it.optLong("bytes")) }.toMutableList(),
                    t.optLongOrNull("startedAt"), t.optLongOrNull("finishedAt"), t.optBoolean("cleanupDone"))
            }
            return RunRecord(o.getString("runId"), o.getString("suiteId"), o.getString("suiteVersion"), o.getString("suiteDigest"), o.getInt("maxLevel"),
                TargetKind.parse(o.getString("targetKind")) ?: TargetKind.SIMULATED, o.optJSONObject("target") ?: JSONObject(), o.getLong("startedAt"),
                tests, o.optLongOrNull("completedAt"), o.optInt("cursor"),
                o.optJSONObject("unknown")?.let { UnknownMarker(it.getString("testId"), it.getString("stepId"), it.getLong("since"), it.optString("action")) },
                o.objects("interventions").map { Intervention(it.optLong("at"), it.strOrNull("testId"), it.strOrNull("stepId"), it.optString("kind"), it.optString("text")) }.toMutableList(),
                o.objects("log").map { LogLine(it.optLong("at"), it.optString("text")) }.toMutableList(),
                o.optJSONObject("context") ?: JSONObject(), o.strings("supersedes"), o.optInt("restarts"))
        }
    }
}

/**
 * One run's durable state: journal.json plus the sanitized attachments beside it. Writes are atomic (temp file, fsync,
 * rename), so a crash leaves either the previous or the new journal, never a torn one.
 */
class RunJournal(val dir: File) {
    val file = File(dir, "journal.json")
    val attachmentsDir = File(dir, "attachments")

    fun save(record: RunRecord) {
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create ${dir.name}." }
        val tmp = File(dir, "journal.json.tmp")
        FileOutputStream(tmp).use { it.write(Canon.bytes(record.toJson())); it.fd.sync() }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun load(): RunRecord? = if (file.isFile) RunRecord.fromJson(JSONObject(file.readText())) else null

    fun saveAttachment(name: String, bytes: ByteArray): File {
        check(attachmentsDir.isDirectory || attachmentsDir.mkdirs())
        require(Regex("""^[a-z0-9._-]{1,120}$""").matches(name)) { "Bad attachment name." }
        return File(attachmentsDir, name).also { f -> FileOutputStream(f).use { it.write(bytes); it.fd.sync() } }
    }

    fun attachment(name: String): ByteArray = File(attachmentsDir, name).readBytes()

    /** Removes this run's local state. Called only after the operator exported or discarded the run. */
    fun clear() { dir.deleteRecursively() }
}

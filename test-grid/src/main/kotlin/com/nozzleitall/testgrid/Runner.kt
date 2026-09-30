package com.nozzleitall.testgrid

import net.jamesjennison.klippercompanion.CosmosProfileGeneration
import net.jamesjennison.klippercompanion.ElegooProfiles
import net.jamesjennison.klippercompanion.FirmwareMatchResult
import net.jamesjennison.klippercompanion.SlicingModelCatalog
import net.jamesjennison.klippercompanion.checkCentauriCarbonFirmwareMatch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A colour mix to slice every part with: tools [a] and [b] (1-based, as the app numbers them), [bPercent] of it from
 * [b]. The slicer uses whichever mixing system the app offers for the printer (Full Spectrum on a U1, ColorMix elsewhere).
 */
data class ColourMix(val a: Int, val b: Int, val bPercent: Int) {
    fun toJson(): JSONObject = JSONObject().put("a", a).put("b", b).put("bPercent", bPercent)
    companion object {
        fun fromJson(o: JSONObject?): ColourMix? = o?.let { ColourMix(it.optInt("a"), it.optInt("b"), it.optInt("bPercent", 50)) }
    }
}

data class SliceRequest(val modelId: String, val parts: List<Pair<File, ModelPart>>, val profile: ProfileInfo, val outputName: String, val mix: ColourMix? = null)

sealed class SliceResult {
    /** [mixing]: the colour-mixing system the slice used ("Full Spectrum", "ColorMix"), when the request had a mix. */
    data class Success(val gcode: File, val mixing: String? = null) : SliceResult()
    /** Refused for a safety reason (e.g. the live firmware check): the slicer did the right thing by not slicing. */
    data class Blocked(val reason: String) : SliceResult()
    data class Failed(val message: String) : SliceResult()
}

interface TestSlicer {
    /** True when this slicer fabricates G-code only to exercise the runner. Its output never grades anything. */
    val simulated: Boolean
    fun profile(id: String): ProfileInfo
    fun slice(request: SliceRequest): SliceResult
}

data class Producer(val app: String, val platform: String, val version: String, val build: String, val applicationId: String?, val sourceRevision: String?)

/** [commit]: the pinned nozzle-engine commit; [binarySha256]: the engine library actually loaded, where readable. */
data class EngineInfo(val name: String, val commit: String?, val version: String?, val binarySha256: String?, val pinSha256: String?, val simulated: Boolean)

class Environment(
    val producer: Producer,
    val engine: EngineInfo,
    val models: ModelLibrary,
    val fixture: (String) -> ByteArray?,
    val workDir: File,
    val clock: () -> Long = System::currentTimeMillis,
    val newId: () -> String = { UUID.randomUUID().toString() },
    val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    companion object {
        /** Known-unsafe fixtures bundled for upload_guard steps (testgrid/fixtures/<name>). */
        fun resourceFixture(name: String): ByteArray? =
            if (!Regex("""^[a-z0-9._-]{1,80}$""").matches(name)) null
            else Environment::class.java.classLoader.getResourceAsStream("testgrid/fixtures/$name")?.use { it.readBytes() }
    }
}

/** What the runner needs from a person next. */
sealed class Pending {
    data class Preconditions(val test: TestCase) : Pending()
    /** Show [action] and [target] exactly; approval covers this one step only. */
    data class Confirmation(val test: TestCase, val step: Step, val phase: String, val action: String, val target: String) : Pending()
    data class Observation(val test: TestCase, val step: Step) : Pending()
    data class Attachment(val test: TestCase, val step: Step, val evidence: RequiredEvidence?) : Pending()
    /** A command's outcome is unknown. Nothing else runs until the operator reviews it against a fresh status reading. */
    data class UnknownReview(val test: TestCase?, val marker: UnknownMarker) : Pending()
    object Finished : Pending()
}

class RunRefused(message: String) : IllegalStateException(message)

/**
 * The deterministic step runner. Every transition is persisted before its effects: a mutating step is journaled as
 * DISPATCHING before its request is sent, so a restart finds it and marks it OUTCOME_UNKNOWN instead of sending it
 * again. Approvals are never persisted: after a restart each consequential step must be approved afresh.
 *
 * Rules that no suite can relax:
 *  - a consequential step runs only after [approve] for that exact step, and at most once;
 *  - control steps re-read the printer first and send nothing unless its state allows the action;
 *  - an unknown outcome blocks every further action until [reviewUnknown] records a fresh reading, and the step is
 *    never sent again in this run;
 *  - a category is graded only from its own tests; a dependency failing blocks a test, a dependency passing never
 *    passes one.
 */
class RunSession private constructor(
    val suite: Suite,
    private val target: TestTarget?,
    private val slicer: TestSlicer?,
    private val env: Environment,
    private val journal: RunJournal?,
    val record: RunRecord,
) {
    @Volatile private var interruptRequest: String? = null
    private val tests = suite.tests

    companion object {
        /** The names Nozzle uploads Test Grid files under, in the G-code root. Nothing else is ever a leftover. */
        val LEFTOVER = Regex("""^nozzle-testgrid-[a-z0-9._-]+\.gcode$""")
        const val LEFTOVER_LIMIT = 50
        const val COMPLETE_QUESTION = "The printer no longer shows this print. Did it finish completely, with nothing cut short?"

        /** Starts a new run. [snapshot] comes from TargetCheck.inspect and must have no mismatches for [suite]. */
        fun start(suite: Suite, target: TestTarget, snapshot: TargetSnapshot, slicer: TestSlicer?, env: Environment, journal: RunJournal?,
                  maxLevel: SafetyLevel, supersedes: List<String> = emptyList(), carried: Map<String, PriorPass> = emptyMap()): RunSession {
            val problems = TargetCheck.mismatches(suite, snapshot, env.producer.version)
            if (problems.isNotEmpty()) throw RunRefused(problems.joinToString("\n"))
            if (maxLevel < suite.minSafetyLevel) throw RunRefused("Nothing in ${suite.title} runs at ${maxLevel.label}; its first tests need ${suite.minSafetyLevel.label}.")
            val now = env.clock()
            val record = RunRecord(env.newId(), suite.id, suite.version, suite.digest, maxLevel.level, target.description.kind,
                snapshotJson(snapshot), now, suite.tests.map { t ->
                    TestRecord(t.id, steps = t.steps.map { StepRecord(it.id, it.kindId, "main") }.toMutableList(),
                        cleanup = t.cleanup.map { StepRecord(it.id, it.kindId, "cleanup") }.toMutableList())
                }, supersedes = supersedes)
            val carry = CarryOver.effective(suite, maxLevel, carried)
            if (carry.isNotEmpty()) record.context.put("carried", JSONObject(carry.mapValues { it.value.toJson() }.toSortedMap()))
            return RunSession(suite, target, slicer, env, journal, record).also {
                it.log("Run started: suite ${suite.id} ${suite.version}, highest level allowed ${maxLevel.level}, target ${target.description.kind.id} ${snapshot.classified.detail}")
                it.save()
            }
        }

        /**
         * Reopens a journaled run after the app stopped. A step caught DISPATCHING becomes OUTCOME_UNKNOWN (never resent),
         * pending approvals are dropped, and the restart is recorded as an intervention.
         */
        fun resume(suite: Suite, target: TestTarget?, slicer: TestSlicer?, env: Environment, journal: RunJournal): RunSession? {
            val record = journal.load() ?: return null
            if (record.suiteDigest != suite.digest) throw RunRefused("The saved run used a different version of suite ${record.suiteId}; it can be exported but not continued.")
            val session = RunSession(suite, target, slicer, env, journal, record)
            session.recoverAfterRestart()
            return session
        }

        fun snapshotJson(s: TargetSnapshot): JSONObject = JSONObject()
            .put("kind", s.description.kind.id).put("manufacturer", s.description.manufacturer).put("model", s.description.model)
            .put("printerKind", s.description.printerKind.name).put("adapter", s.description.adapter).put("protocol", s.description.protocol)
            .put("firmware", JSONObject().put("family", s.classified.family).put("app", s.identity?.firmware?.app.orEmpty())
                .put("version", s.identity?.firmware?.version.orEmpty()).put("detail", s.classified.detail))
            .put("capabilities", JSONArray(s.capabilities.sorted()))
            .put("hardware", JSONObject(s.classified.hardware.toSortedMap()))
            .put("toolSlots", s.description.toolSlots)
            .putOpt("slicingModel", s.classified.slicingModel?.name)
    }

    /** The profile a slice step used, for the bundle's nozzle and material fields. */
    fun slicerProfile(id: String): ProfileInfo? = slicer?.profile(id)

    private fun save() { journal?.save(record) }
    private fun now() = env.clock()
    private fun log(text: String) { record.log += LogLine(now(), text) }
    private fun intervene(kind: String, text: String, testId: String? = null, stepId: String? = null) {
        record.interventions += Intervention(now(), testId, stepId, kind, text); log("Intervention ($kind): $text")
    }

    private fun recoverAfterRestart() {
        record.restarts++
        intervene("restart", "Nozzle It All stopped during this run and it was reopened.")
        record.tests.forEach { t ->
            (t.steps + t.cleanup).forEach { s ->
                when (s.status) {
                    StepStatus.DISPATCHING -> {
                        s.status = StepStatus.OUTCOME_UNKNOWN; s.finishedAt = now()
                        s.detail = "Nozzle It All stopped while this command was in flight. Whether the printer received it is unknown; it will not be sent again."
                        if (s.phase == "main") { t.state = RunState.OUTCOME_UNKNOWN; t.result = ResultState.UNVERIFIED; t.reason = s.detail; skipRemaining(t, "Not run after an unknown outcome.") }
                        if (record.unknown == null) record.unknown = UnknownMarker(t.testId, s.stepId, now(), s.confirmation?.action ?: s.kind)
                    }
                    StepStatus.AWAITING_CONFIRMATION -> { s.status = StepStatus.PENDING; s.confirmation = null }
                    else -> {}
                }
            }
            if (t.state == RunState.AWAITING_CONFIRMATION) t.state = RunState.RUNNING
        }
        save()
    }

    // ---- What happens next -------------------------------------------------------------------------------------

    /** The current need without doing any work; null when only automatic work is queued (call [proceed]). */
    fun pending(): Pending? = synchronized(this) { peek() }

    private fun peek(): Pending? {
        record.unknown?.let { return Pending.UnknownReview(suite.test(it.testId), it) }
        if (record.completedAt != null) return Pending.Finished
        val rec = record.tests.getOrNull(record.cursor) ?: return null
        val test = suite.test(rec.testId) ?: return null
        return when (rec.state) {
            RunState.PRECONDITIONS -> Pending.Preconditions(test)
            RunState.AWAITING_CONFIRMATION -> rec.steps.firstOrNull { it.status == StepStatus.AWAITING_CONFIRMATION }?.let { confirmationFor(test, it) }
            RunState.AWAITING_OBSERVATION -> rec.steps.firstOrNull { it.status == StepStatus.AWAITING_OBSERVATION }?.let { operatorPending(test, it) }
            else -> if (rec.state.terminal) rec.cleanup.firstOrNull { it.status == StepStatus.AWAITING_CONFIRMATION }?.let { confirmationFor(test, it) } else null
        }
    }

    /**
     * Runs automatic work (software and read-only steps, test bookkeeping) until a person is needed, and returns what
     * they are needed for. Never sends a consequential request.
     */
    fun proceed(): Pending = synchronized(this) {
        var guard = 0
        while (guard++ < 10_000) {
            takeInterrupt()
            peek()?.let { return it }
            val rec = record.tests.getOrNull(record.cursor)
            if (rec == null) { complete(); return Pending.Finished }
            val test = suite.test(rec.testId)!!
            checkTestTimeout(test, rec)
            when {
                rec.state == RunState.NOT_STARTED -> begin(test, rec)
                rec.state == RunState.RUNNING -> advance(test, rec)
                rec.state.terminal -> advanceCleanup(test, rec)
            }
            save()
        }
        throw IllegalStateException("The runner did not settle.")
    }

    private fun begin(test: TestCase, rec: TestRecord) {
        rec.startedAt = now()
        val block = blockReason(test)
        val skip = skipReason(test)
        when {
            skip != null -> endBeforeStart(rec, RunState.SKIPPED, ResultState.SKIPPED, skip)
            block != null -> endBeforeStart(rec, RunState.BLOCKED, ResultState.BLOCKED, block)
            test.preconditions.isEmpty() -> { rec.state = RunState.RUNNING; log("Test ${test.id} started.") }
            else -> rec.state = RunState.PRECONDITIONS
        }
    }

    private fun skipReason(test: TestCase): String? = when {
        test.safetyLevel.level > record.maxLevel -> "Above the highest safety level chosen for this run (${test.safetyLevel.label})."
        CarryOver.carriedFrom(record, test.id) != null -> CarryOver.carriedFrom(record, test.id)!!.let {
            "Already passed in run ${it.runId.take(8)} (${java.time.Instant.ofEpochMilli(it.completedAt).toString().take(10)}${it.bundleDigest?.let { d -> ", bundle ${d.take(12)}" } ?: ""}); not repeated." }
        else -> test.requiredHardware.firstOrNull { record.target.optJSONObject("hardware")?.optBoolean(it) != true }
            ?.let { "Needs \"$it\" hardware, which was not detected on this printer. Multi-material results are never inferred from single-material ones." }
    }

    private fun blockReason(test: TestCase): String? {
        test.unsupportedSteps.firstOrNull()?.let { return "Step ${it.id} uses \"${it.kindId}\", which needs a newer Nozzle It All." }
        test.dependsOn.forEach { d ->
            val r = record.test(d)?.result
            // A dependency that passed in an earlier run (carried over) counts as passed.
            if (r != ResultState.PASS && r != ResultState.PARTIAL && CarryOver.carriedFrom(record, d) == null) return "Depends on test $d, which is ${r ?: ResultState.UNVERIFIED}."
        }
        val caps = record.target.strings("capabilities").toSet()
        val missing = test.requiredCapabilities.filter { it !in caps }
        if (missing.isNotEmpty()) return "Nozzle It All can't do this on this printer yet (it doesn't offer ${missing.joinToString()}), so the test can't run. It is recorded as blocked, not failed."
        if (target == null && (test.steps + test.cleanup).any { it.kind?.usesPrinter == true }) return "No printer is selected."
        return null
    }

    private fun endBeforeStart(rec: TestRecord, state: RunState, result: ResultState, reason: String) {
        rec.state = state; rec.result = result; rec.reason = reason; rec.finishedAt = now(); rec.cleanupDone = true
        (rec.steps + rec.cleanup).forEach { if (it.status == StepStatus.PENDING) { it.status = if (state == RunState.SKIPPED) StepStatus.SKIPPED else StepStatus.BLOCKED; it.detail = reason } }
        log("Test ${rec.testId}: ${result.name} before starting. $reason")
        record.cursor++
    }

    /** [answers]: precondition id to "met". Automatic checks run here too; any unmet precondition blocks the test. */
    fun answerPreconditions(answers: Map<String, Boolean>): Pending = synchronized(this) {
        refuseIfUnknown()
        val rec = currentRecord() ?: throw RunRefused("No test is waiting for preconditions.")
        val test = suite.test(rec.testId)!!
        if (rec.state != RunState.PRECONDITIONS) throw RunRefused("Test ${test.id} is not waiting for preconditions.")
        val unmet = mutableListOf<String>()
        test.preconditions.forEach { p ->
            val attested = answers[p.id] == true
            val automatic = p.check?.let { automaticPrecondition(it) }
            rec.preconditions[p.id] = attested && automatic?.first != false
            if (!attested) unmet += "\"${p.text}\" was not confirmed by the operator."
            else if (automatic != null && !automatic.first) unmet += automatic.second
        }
        if (unmet.isEmpty()) { rec.state = RunState.RUNNING; log("Test ${test.id}: preconditions met.") }
        else {
            intervene("precondition", unmet.joinToString(" "), test.id)
            rec.state = RunState.BLOCKED; rec.result = ResultState.BLOCKED; rec.reason = "Precondition not met: " + unmet.joinToString(" ")
            rec.finishedAt = now(); rec.cleanupDone = true
            rec.steps.forEach { it.status = StepStatus.BLOCKED; it.detail = "Preconditions not met." }
            rec.cleanup.forEach { it.status = StepStatus.SKIPPED; it.detail = "Test never started." }
            record.cursor++
        }
        save()
        return proceed()
    }

    private fun automaticPrecondition(check: String): Pair<Boolean, String> = try {
        val s = target?.status() ?: return false to "No printer is selected."
        when (check) {
            "printer_idle" -> (s.idle) to "The printer is ${s.state}${if (s.ready) "" else " (not ready)"}, not idle."
            "printer_connected" -> true to ""
            else -> false to "Unknown check $check."
        }
    } catch (e: Exception) { false to "Could not read the printer: ${e.message}" }

    private fun advance(test: TestCase, rec: TestRecord) {
        val idx = rec.steps.indexOfFirst { it.status == StepStatus.PENDING }
        if (idx < 0) { finishTest(test, rec); return }
        val stepRec = rec.steps[idx]
        val step = test.steps[idx]
        val kind = step.kind!!
        when {
            nothingToDo(step, stepRec)?.let { why -> stepRec.status = StepStatus.SKIPPED; stepRec.detail = why; stepRec.finishedAt = now() } != null -> {}
            kind.consequential || step.requiresConfirmation -> { stepRec.status = StepStatus.AWAITING_CONFIRMATION; rec.state = RunState.AWAITING_CONFIRMATION }
            kind.operator -> { stepRec.status = StepStatus.AWAITING_OBSERVATION; rec.state = RunState.AWAITING_OBSERVATION; captureShown(step, stepRec) }
            else -> {
                execute(test, step, stepRec)
                if (stepRec.status in setOf(StepStatus.FAILED, StepStatus.BLOCKED)) { skipRemaining(rec, "Not reached: step ${step.id} did not pass."); finishTest(test, rec) }
            }
        }
    }

    private fun advanceCleanup(test: TestCase, rec: TestRecord) {
        if (rec.cleanupDone) { record.cursor++; return }
        val idx = rec.cleanup.indexOfFirst { it.status == StepStatus.PENDING }
        if (idx < 0) { rec.cleanupDone = true; record.cursor++; return }
        val stepRec = rec.cleanup[idx]; val step = test.cleanup[idx]
        val kind = step.kind!!
        when {
            nothingToDo(step, stepRec)?.let { why -> stepRec.status = StepStatus.SKIPPED; stepRec.detail = why; stepRec.finishedAt = now() } != null -> {}
            kind.consequential || step.requiresConfirmation -> stepRec.status = StepStatus.AWAITING_CONFIRMATION
            kind.operator -> { stepRec.status = StepStatus.SKIPPED; stepRec.detail = "Operator steps are not run during cleanup." }
            else -> execute(test, step, stepRec)
        }
    }

    private fun finishTest(test: TestCase, rec: TestRecord) {
        val st = rec.steps.map { it.status }
        val (state, result) = when {
            StepStatus.FAILED in st -> RunState.FAILED to ResultState.FAIL
            StepStatus.OUTCOME_UNKNOWN in st -> RunState.OUTCOME_UNKNOWN to ResultState.UNVERIFIED
            st.all { it == StepStatus.PASSED } -> RunState.PASSED to ResultState.PASS
            StepStatus.BLOCKED in st && st.none { it == StepStatus.PASSED || it == StepStatus.PARTIAL } -> RunState.BLOCKED to ResultState.BLOCKED
            st.none { it == StepStatus.PASSED || it == StepStatus.PARTIAL } -> RunState.SKIPPED to ResultState.SKIPPED
            else -> RunState.FAILED to ResultState.PARTIAL
        }
        rec.state = state; rec.result = result; rec.finishedAt = now()
        if (rec.reason.isBlank()) rec.reason = rec.steps.firstOrNull { it.status !in setOf(StepStatus.PASSED) }?.let { "${it.stepId}: ${it.status.name.lowercase()}${if (it.detail.isNotBlank()) " - ${it.detail}" else ""}" }.orEmpty()
        val missing = test.requiredEvidence.filter { e -> rec.attachments.none { it.evidenceId == e.id } && test.steps.none { s -> s.kind == StepKind.OBSERVE && s.params.optString("evidence") == e.id && rec.step(s.id)?.status?.done == true && rec.step(s.id)?.status != StepStatus.SKIPPED } }
        if (missing.isNotEmpty() && result == ResultState.PASS) { rec.result = ResultState.PARTIAL; rec.state = RunState.FAILED; rec.reason = "Required evidence missing: ${missing.joinToString { it.id }}" }
        log("Test ${test.id} finished: ${rec.result.name}${if (rec.reason.isNotBlank()) " (${rec.reason})" else ""}")
    }

    private fun skipRemaining(rec: TestRecord, why: String) {
        rec.steps.forEach { if (it.status == StepStatus.PENDING || it.status == StepStatus.AWAITING_CONFIRMATION || it.status == StepStatus.AWAITING_OBSERVATION) { it.status = StepStatus.SKIPPED; it.detail = why } }
    }

    private fun checkTestTimeout(test: TestCase, rec: TestRecord) {
        val limit = test.timeoutSeconds ?: return
        val started = rec.startedAt ?: return
        if (rec.state.terminal || now() - started <= limit * 1000L) return
        skipRemaining(rec, "Test timed out after $limit s.")
        val (state, result) = when (test.onTimeout) {
            TimeoutBehavior.FAIL -> RunState.FAILED to ResultState.FAIL
            TimeoutBehavior.BLOCKED -> RunState.BLOCKED to ResultState.BLOCKED
            TimeoutBehavior.OUTCOME_UNKNOWN -> RunState.OUTCOME_UNKNOWN to ResultState.UNVERIFIED
        }
        rec.state = state; rec.result = result; rec.reason = "Timed out after $limit s."; rec.finishedAt = now()
        intervene("timeout", "Test ${test.id} timed out after $limit s.", test.id)
    }

    private fun currentRecord(): TestRecord? = record.tests.getOrNull(record.cursor)

    private fun refuseIfUnknown() {
        record.unknown?.let { throw RunRefused("The outcome of \"${it.action}\" is unknown. Check the printer and review it before anything else runs.") }
    }

    // ---- Operator actions --------------------------------------------------------------------------------------

    /** Performs the one consequential step awaiting approval, exactly once. */
    fun approve(stepId: String): Pending = synchronized(this) {
        refuseIfUnknown()
        val rec = currentRecord() ?: throw RunRefused("Nothing is waiting for approval.")
        val test = suite.test(rec.testId)!!
        val stepRec = rec.step(stepId)?.takeIf { it.status == StepStatus.AWAITING_CONFIRMATION } ?: throw RunRefused("Step $stepId is not waiting for approval.")
        val step = (test.steps + test.cleanup).first { it.id == stepId }
        val prompt = confirmationFor(test, stepRec)
        stepRec.confirmation = ConfirmationRecord(prompt.action, prompt.target, now())
        log("Operator approved ${test.id}/${step.id}: ${prompt.action}")
        execute(test, step, stepRec)
        if (stepRec.phase == "main" && !rec.state.terminal) {
            rec.state = RunState.RUNNING
            if (stepRec.status == StepStatus.FAILED || stepRec.status == StepStatus.BLOCKED) { skipRemaining(rec, "Not reached: step ${step.id} did not pass."); finishTest(test, rec) }
        }
        save()
        return proceed()
    }

    /** The operator refuses a consequential step. Nothing is sent; the rest of the test is not run. */
    fun decline(stepId: String, note: String = ""): Pending = synchronized(this) {
        val rec = currentRecord() ?: throw RunRefused("Nothing is waiting.")
        val stepRec = rec.step(stepId)?.takeIf { it.status == StepStatus.AWAITING_CONFIRMATION || it.status == StepStatus.AWAITING_OBSERVATION } ?: throw RunRefused("Step $stepId is not waiting.")
        stepRec.status = StepStatus.SKIPPED; stepRec.detail = "Declined by the operator.${if (note.isNotBlank()) " $note" else ""}"; stepRec.finishedAt = now()
        intervene("declined", "Declined ${rec.testId}/$stepId.${if (note.isNotBlank()) " $note" else ""}", rec.testId, stepId)
        if (stepRec.phase == "main") { skipRemaining(rec, "Not run: the operator declined step $stepId."); rec.state = RunState.RUNNING; finishTest(suite.test(rec.testId)!!, rec) }
        save()
        return proceed()
    }

    fun observe(stepId: String, value: String, note: String = ""): Pending = synchronized(this) {
        refuseIfUnknown()
        val rec = currentRecord() ?: throw RunRefused("Nothing is waiting.")
        val test = suite.test(rec.testId)!!
        val stepRec = rec.step(stepId)?.takeIf { it.status == StepStatus.AWAITING_OBSERVATION } ?: throw RunRefused("Step $stepId is not waiting for an observation.")
        val step = test.steps.first { it.id == stepId }
        stepRec.data.strOrNull("askOperator")?.let { q ->
            // An automatic step the printer could no longer answer: the operator's yes/no is the result, recorded as theirs.
            val yes = value.trim().equals("yes", ignoreCase = true)
            stepRec.status = if (yes) StepStatus.PASSED else StepStatus.FAILED
            stepRec.detail = "Confirmed by the operator (the printer could not): \"$q\" ${if (yes) "Yes." else "No."}"
            stepRec.data.put("operatorAnswer", if (yes) "yes" else "no").apply { if (note.isNotBlank()) put("note", note.trim().take(2000)) }
            stepRec.finishedAt = now()
            intervene("operator_confirmed", "${test.id}/$stepId: ${if (yes) "yes" else "no"} to \"$q\"", test.id, stepId)
            log("Operator answered for ${test.id}/$stepId: ${if (yes) "yes" else "no"}")
            rec.state = RunState.RUNNING
            if (!yes) { skipRemaining(rec, "Not reached: step $stepId did not pass."); finishTest(test, rec) }
            save()
            return proceed()
        }
        if (step.kind != StepKind.OBSERVE) throw RunRefused("Step $stepId needs an attachment, not an answer.")
        val (status, detail) = Observations.evaluate(step, value)
        stepRec.status = status; stepRec.detail = detail; stepRec.finishedAt = now(); stepRec.startedAt = stepRec.startedAt ?: stepRec.finishedAt
        val shown = stepRec.data.optJSONObject("shown")
        stepRec.data = JSONObject().put("response", value.trim().take(2000)).put("responseType", step.params.optString("response")).putOpt("shown", shown)
            .apply { if (note.isNotBlank()) put("note", note.trim().take(2000)); step.params.strOrNull("unit")?.let { put("unit", it) } }
        log("Observation ${test.id}/$stepId: ${status.name}")
        rec.state = RunState.RUNNING
        save()
        return proceed()
    }

    /**
     * Re-reads what an observe step shows (params.show) so the operator compares against a fresh value. Read-only. The
     * reading is kept with the answer, so the evidence says what Nozzle showed when the question was answered.
     */
    fun refreshShown(stepId: String) = synchronized(this) {
        val rec = currentRecord() ?: return@synchronized
        val stepRec = rec.step(stepId)?.takeIf { it.status == StepStatus.AWAITING_OBSERVATION } ?: return@synchronized
        captureShown(suite.test(rec.testId)!!.steps.first { it.id == stepId }, stepRec)
        save()
    }

    private fun captureShown(step: Step, s: StepRecord) {
        val show = step.params.strOrNull("show") ?: return
        val t = target ?: return
        val shown = try {
            when (show) {
                "status" -> t.status().let { r -> JSONObject().put("state", r.state).put("ready", r.ready).put("nozzle", r.nozzle).put("nozzleTarget", r.nozzleTarget)
                    .put("bed", r.bed).put("bedTarget", r.bedTarget).put("observedAt", r.observedAtMillis) }
                "slots" -> JSONObject().put("slots", JSONArray(t.materialSlots().take(16)))
                // Camera images are shown live by the UI and never stored; only that a camera was listed is recorded.
                "camera" -> JSONObject().put("cameras", t.cameras().size)
                else -> return
            }
        } catch (e: Exception) { JSONObject().put("error", e.message ?: e.javaClass.simpleName) }
        s.data = JSONObject().put("shown", shown)
    }

    /** Stores a sanitized copy (image metadata removed) of a required photo or file. */
    fun attach(stepId: String, bytes: ByteArray, mime: String): Pending = synchronized(this) {
        refuseIfUnknown()
        val rec = currentRecord() ?: throw RunRefused("Nothing is waiting.")
        val test = suite.test(rec.testId)!!
        val stepRec = rec.step(stepId)?.takeIf { it.status == StepStatus.AWAITING_OBSERVATION } ?: throw RunRefused("Step $stepId is not waiting for an attachment.")
        val step = test.steps.first { it.id == stepId }
        if (step.kind != StepKind.ATTACH) throw RunRefused("Step $stepId needs an answer, not an attachment.")
        val clean = Attachments.sanitize(bytes, mime)
        val evidenceId = step.params.optString("evidence")
        val name = "${test.id}-$evidenceId.${Attachments.extension(clean.mime)}".lowercase()
        journal?.saveAttachment(name, clean.bytes) ?: File(env.workDir, "attachments").also { it.mkdirs() }.resolve(name).writeBytes(clean.bytes)
        rec.attachments.removeAll { it.evidenceId == evidenceId }
        rec.attachments += AttachmentRecord(evidenceId, stepId, name, clean.mime, Canon.sha256(clean.bytes), clean.bytes.size.toLong())
        stepRec.status = StepStatus.PASSED; stepRec.finishedAt = now()
        stepRec.detail = "Attached ${clean.mime}, ${clean.bytes.size} bytes${if (clean.removedMetadata) " (metadata removed)" else ""}."
        rec.state = RunState.RUNNING
        log("Attachment ${test.id}/$evidenceId: ${clean.mime}, ${clean.bytes.size} bytes")
        save()
        return proceed()
    }

    /**
     * Resolves an unknown outcome. Requires a status reading taken now, after the unknown outcome, from a reachable
     * printer. The step stays OUTCOME_UNKNOWN (graded UNVERIFIED) and is not sent again; the run continues with the
     * test's cleanup and then later tests.
     */
    fun reviewUnknown(note: String): StatusReading = synchronized(this) {
        val marker = record.unknown ?: throw RunRefused("There is no unknown outcome to review.")
        if (note.isBlank()) throw RunRefused("Describe what you found on the printer.")
        val t = target ?: throw RunRefused("Reconnect to the printer to check its state first.")
        val reading = try { t.status() } catch (e: Exception) { throw RunRefused("The printer could not be read (${e.message}). Check it directly and try again.") }
        if (reading.observedAtMillis < marker.since) throw RunRefused("That printer reading is older than the unknown outcome. Read the printer again.")
        if (reading.state in setOf("offline", "unknown", "error", "shutdown") && !reading.ready) throw RunRefused("The printer reports ${reading.state}. Resolve that on the printer before continuing.")
        intervene("unknown_review", "Reviewed the unknown outcome of \"${marker.action}\". Printer now ${reading.state}, nozzle ${reading.nozzle}/${reading.nozzleTarget} °C, bed ${reading.bed}/${reading.bedTarget} °C. Operator: ${note.trim().take(2000)}",
            marker.testId, marker.stepId)
        record.test(marker.testId)?.step(marker.stepId)?.let { it.data.put("review", JSONObject().put("state", reading.state).put("note", note.trim().take(2000)).put("reviewedAt", reading.observedAtMillis)) }
        record.unknown = null
        save()
        reading
    }

    /**
     * Whether a failed read-only step may be run again: only in a test that allows it (never one that changes the
     * printer), only while nothing in that test changed the printer, and only while the run hasn't moved on to
     * executing a later test.
     */
    fun canRetry(testId: String, stepId: String): Boolean = synchronized(this) {
        val rec = record.test(testId) ?: return false
        val test = suite.test(testId) ?: return false
        val kind = test.steps.firstOrNull { it.id == stepId }?.kind ?: return false
        val cur = currentRecord()
        val runHasNotMovedOn = cur == null || cur === rec || cur.state == RunState.NOT_STARTED || cur.state == RunState.PRECONDITIONS
        record.completedAt == null && record.unknown == null && runHasNotMovedOn && !test.uncertainResultProhibitsRetry && !kind.mutates && !kind.operator &&
            rec.step(stepId)?.status in setOf(StepStatus.FAILED, StepStatus.BLOCKED) &&
            rec.steps.none { st -> test.steps.first { it.id == st.stepId }.kind?.mutates == true && st.status != StepStatus.PENDING && st.status != StepStatus.SKIPPED }
    }

    fun retry(testId: String, stepId: String): Pending = synchronized(this) {
        if (!canRetry(testId, stepId)) throw RunRefused("Step $testId/$stepId can't be retried.")
        val rec = record.test(testId)!!
        val idx = rec.steps.indexOfFirst { it.stepId == stepId }
        rec.steps.drop(idx).forEach { it.status = StepStatus.PENDING; it.detail = ""; it.data = JSONObject(); it.startedAt = null; it.finishedAt = null }
        rec.state = RunState.RUNNING; rec.result = ResultState.UNVERIFIED; rec.reason = ""; rec.finishedAt = null
        record.cursor = record.tests.indexOf(rec)
        intervene("retry", "Retried read-only step $testId/$stepId.", testId, stepId)
        save()
        return proceed()
    }

    /**
     * Stops the current test safely: nothing further is sent for it except its cleanup, which the operator approves
     * step by step. Safe to call from another thread while a monitor step is polling.
     */
    fun interrupt(reason: String) { interruptRequest = reason.ifBlank { "Interrupted by the operator." } }

    private fun takeInterrupt() {
        val reason = interruptRequest ?: return
        interruptRequest = null
        val rec = currentRecord() ?: return
        if (rec.state.terminal || rec.state == RunState.NOT_STARTED) return
        skipRemaining(rec, "Not run: the test was interrupted.")
        rec.state = RunState.INTERRUPTED
        rec.result = if (rec.steps.any { it.status == StepStatus.FAILED }) ResultState.FAIL else ResultState.UNVERIFIED
        rec.reason = reason; rec.finishedAt = now()
        intervene("interrupt", reason, rec.testId)
        save()
    }

    /** Ends the run. Tests not reached are recorded as SKIPPED ("not run"), never as passed. */
    fun finish(): Pending = synchronized(this) {
        refuseIfUnknown()
        takeInterrupt()
        record.tests.forEach { t ->
            if (!t.state.terminal) {
                if (t.touchedPrinter) { t.state = RunState.INTERRUPTED; t.result = if (t.steps.any { it.status == StepStatus.FAILED }) ResultState.FAIL else ResultState.UNVERIFIED; t.reason = "The run was ended before this test finished." }
                else { t.state = RunState.SKIPPED; t.result = ResultState.SKIPPED; t.reason = "Not run." }
                skipRemaining(t, "Not run: the run was ended.")
                t.finishedAt = now()
            }
            t.cleanup.forEach { if (!it.status.done) { it.status = StepStatus.SKIPPED; it.detail = "Not run: the run was ended." } }
        }
        complete()
        Pending.Finished
    }

    private fun complete() {
        if (record.completedAt == null) { record.completedAt = now(); log("Run completed."); save() }
    }

    // ---- Step execution ----------------------------------------------------------------------------------------

    private fun confirmationFor(test: TestCase, s: StepRecord): Pending.Confirmation {
        val step = (test.steps + test.cleanup).first { it.id == s.stepId }
        val d = target?.description
        val targetText = d?.let { "${it.label} · ${it.manufacturer} ${it.model} (${record.target.optJSONObject("firmware")?.optString("family")}) via ${it.adapter} at ${it.address}" } ?: "no printer"
        return Pending.Confirmation(test, step, s.phase, actionText(step, s), targetText)
    }

    private fun operatorPending(test: TestCase, s: StepRecord): Pending {
        val step = test.steps.first { it.id == s.stepId }
        s.data.strOrNull("askOperator")?.let { q -> return Pending.Observation(test, operatorQuestion(step, q)) }
        return if (step.kind == StepKind.ATTACH) Pending.Attachment(test, step, test.requiredEvidence.firstOrNull { it.id == step.params.optString("evidence") })
        else Pending.Observation(test, step)
    }

    /** The exact action text the operator approves; [s] carries what a step found before asking (leftover names). */
    fun actionText(step: Step, s: StepRecord? = null): String = when (step.kind) {
        StepKind.DELETE_LEFTOVERS -> s?.data?.optJSONArray("candidates")?.let { a -> (0 until a.length()).map { a.getString(it) } }?.takeIf { it.isNotEmpty() }
            ?.let { "Delete ${it.size} earlier Test Grid file(s) from the printer: ${it.joinToString(", ")}. Nothing else is deleted." }
            ?: "Delete earlier Test Grid files (nozzle-testgrid-*.gcode) that the printer no longer has loaded; their names are shown before you approve"
        StepKind.SEND_AND_START -> latestSlice(step)?.let { "Send ${uploadName(step)} (${it.optLong("bytes")} bytes, SHA-256 ${it.optString("sha256").take(16)}…) to the printer and start printing it. The printer will heat, move and extrude." }
            ?: "Send and start the sliced file (no sliced file yet: this step will be blocked)"
        StepKind.UPLOAD -> latestSlice(step)?.let { "Upload ${uploadName(step)} (${it.optLong("bytes")} bytes, SHA-256 ${it.optString("sha256").take(16)}…) to the printer's G-code folder. Nothing is printed." }
            ?: "Upload the sliced file (no sliced file yet: this step will be blocked)"
        StepKind.DELETE_UPLOADED -> latestUpload(step)?.takeIf { !it.optBoolean("deleted") }?.let { "Delete ${it.optString("remotePath")} from the printer: the file this run uploaded, verified by SHA-256 before deletion." }
            ?: "Delete the file this run uploaded (nothing uploaded yet: this step will be skipped)"
        else -> controlAction(step)?.describe() ?: step.title
    }

    /**
     * Why a consequential step has nothing to act on, so it is skipped without asking. Found on the first level-2 run:
     * cleanup asked the operator to approve deleting a file the test had already deleted.
     */
    private fun nothingToDo(step: Step, s: StepRecord): String? = when (step.kind) {
        StepKind.DELETE_UPLOADED -> if (latestUpload(step)?.takeIf { !it.optBoolean("deleted") } == null) "Nothing uploaded by this run is left to delete." else null
        // Read once, before asking, so the approval names the exact files; an empty list is skipped without asking.
        StepKind.DELETE_LEFTOVERS -> try {
            val found = leftovers()
            s.data.put("candidates", JSONArray(found))
            if (found.isEmpty()) "No earlier Test Grid files to delete on the printer." else null
        } catch (e: UnsupportedByTarget) { e.message ?: "This printer connection can't list files." }
          catch (e: Exception) { "Could not list the printer's files (${e.message}); nothing was deleted." }
        else -> null
    }

    /**
     * Earlier Test Grid files that can go: named `nozzle-testgrid-*.gcode` in the G-code root (the names Nozzle uploads
     * under), not the file the printer has loaded (a finished print keeps it; Nozzle never deletes a loaded file), and
     * not a file this run uploaded for a test that hasn't finished.
     */
    private fun leftovers(): List<String> {
        val loaded = printer().status().filename
        val needed = (0 until uploads().length()).map { uploads().getJSONObject(it) }
            .filter { u -> !u.optBoolean("deleted") && record.test(u.optString("test"))?.state?.terminal != true }.map { it.optString("remotePath") }.toSet()
        return printer().files().filter { LEFTOVER.matches(it) && it != loaded && it !in needed }.sorted().take(LEFTOVER_LIMIT)
    }

    private fun controlAction(step: Step): ControlAction? = when (step.kind) {
        StepKind.SET_TEMPERATURE -> ControlAction.SetTemperature(step.params.optString("heater"), step.params.optInt("celsius"))
        StepKind.HOME -> ControlAction.Home
        StepKind.JOG -> ControlAction.Jog(step.params.optString("axis").uppercase(), step.params.optDouble("mm"))
        StepKind.START_PRINT -> latestUpload(step)?.takeIf { !it.optBoolean("deleted") }?.let { ControlAction.StartPrint(it.optString("remotePath")) }
        StepKind.PAUSE -> ControlAction.Pause
        StepKind.RESUME -> ControlAction.Resume
        StepKind.CANCEL -> ControlAction.Cancel
        else -> null
    }

    /**
     * The profile a slice or profile-match step uses: `params.profileWith` names a profile for detected hardware (the
     * first listed that this printer has), otherwise `params.profile`. A Centauri Carbon with CANVAS needs the COSMOS AFC
     * profile even for single-material prints: COSMOS's PRINT_START takes the lane from its TOOL parameter and warns
     * without it (found on the owner's CANVAS printer).
     */
    private fun profileFor(p: JSONObject): String {
        // "@printer": the slicing profile saved for the tester's own printer (a fixture suite can meet any model).
        if (p.optString("profile") == SuiteProfiles.PRINTER) return SuiteProfiles.forPrinter(record.target.optString("slicingModel"))
            ?: throw UnsupportedByTarget("This printer has no slicing profile chosen. Choose one in Edit printer, then start again: Nozzle slices with your printer's own profile.")
        val hw = record.target.optJSONObject("hardware")
        val with = p.optJSONObject("profileWith")
        return with?.keys()?.asSequence()?.sorted()?.firstOrNull { hw?.optBoolean(it) == true }?.let { with.getString(it) } ?: p.optString("profile")
    }

    /** An automatic step handed to the operator as a yes/no question (same id, so the answer lands on that step). */
    private fun operatorQuestion(step: Step, question: String): Step = step.copy(kindId = StepKind.OBSERVE.id, kind = StepKind.OBSERVE,
        params = JSONObject().put("question", question).put("response", "yes_no"), expect = JSONObject().put("equals", "yes"))

    private fun markDeleted(path: String) { (0 until uploads().length()).map { uploads().getJSONObject(it) }.filter { it.optString("remotePath") == path }.forEach { it.put("deleted", true) } }

    // A Bambu slice is a .gcode.3mf bundle and keeps that extension; everything else is .gcode.
    private fun uploadName(step: Step): String {
        val ext = if (latestSlice(step)?.optString("file").orEmpty().endsWith(".gcode.3mf")) ".gcode.3mf" else ".gcode"
        return "nozzle-testgrid-${suite.id}-${step.params.optString("fromTest").ifBlank { "run" }}$ext".replace(Regex("[^a-z0-9._-]"), "-")
    }

    private fun slices(): JSONObject = record.context.optJSONObject("slices") ?: JSONObject().also { record.context.put("slices", it) }
    private fun uploads(): JSONArray = record.context.optJSONArray("uploads") ?: JSONArray().also { record.context.put("uploads", it) }

    private fun latestSlice(step: Step): JSONObject? {
        val from = step.params.optString("fromTest")
        return if (from.isNotBlank()) slices().optJSONObject(from) else record.context.optString("latestSlice").takeIf { it.isNotBlank() }?.let { slices().optJSONObject(it) }
    }

    private fun latestUpload(step: Step): JSONObject? {
        val from = step.params.optString("fromTest")
        val all = (0 until uploads().length()).map { uploads().getJSONObject(it) }
        return all.lastOrNull { from.isBlank() || it.optString("fromTest") == from }
    }

    private fun execute(test: TestCase, step: Step, s: StepRecord) {
        s.startedAt = now()
        val kind = step.kind!!
        if (kind.mutates) { s.status = StepStatus.DISPATCHING; save() }
        val (status, detail) = try { run(test, step, s, kind) } catch (e: UnsupportedByTarget) {
            StepStatus.BLOCKED to (e.message ?: "Not supported by this printer connection.")
        } catch (e: Exception) {
            if (kind.mutates) StepStatus.OUTCOME_UNKNOWN to "The request may have reached the printer: ${e.message ?: e.javaClass.simpleName}"
            else StepStatus.FAILED to (e.message ?: e.javaClass.simpleName)
        }
        s.status = status; s.detail = detail; s.finishedAt = if (status == StepStatus.AWAITING_OBSERVATION) null else now()
        if (status == StepStatus.AWAITING_OBSERVATION) record.test(test.id)!!.state = RunState.AWAITING_OBSERVATION
        log("Step ${test.id}/${step.id} (${kind.id}): ${status.name}${if (detail.isNotBlank()) " - $detail" else ""}")
        if (status == StepStatus.OUTCOME_UNKNOWN) {
            val rec = record.test(test.id)!!
            record.unknown = UnknownMarker(test.id, step.id, now(), s.confirmation?.action ?: actionText(step))
            if (s.phase == "main") {
                skipRemaining(rec, "Not run after an unknown outcome.")
                rec.state = RunState.OUTCOME_UNKNOWN; rec.result = ResultState.UNVERIFIED; rec.reason = "Outcome of ${step.id} unknown: $detail"; rec.finishedAt = now()
            }
        }
        save()
    }

    private fun printer(): TestTarget = target ?: throw UnsupportedByTarget("No printer is selected.")

    private fun run(test: TestCase, step: Step, s: StepRecord, kind: StepKind): Pair<StepStatus, String> {
        val p = step.params; val expect = step.expect
        return when (kind) {
            StepKind.VERIFY_MODEL -> {
                val parts = env.models.materialize(p.optString("model"), File(env.workDir, "models"))
                s.data.put("model", p.optString("model")).put("parts", JSONArray(parts.map { JSONObject().put("file", it.second.file).put("sha256", it.second.sha256).put("tool", it.second.tool) }))
                StepStatus.PASSED to "Model files match their published SHA-256."
            }
            StepKind.SLICE -> {
                val sl = slicer ?: throw UnsupportedByTarget("No slicer is available on this platform.")
                val profile = sl.profile(profileFor(p))
                val parts = env.models.materialize(p.optString("model"), File(env.workDir, "models"))
                val out = "nozzle-testgrid-${suite.id}-${test.id}".replace(Regex("[^a-z0-9._-]"), "-")
                s.data.put("profile", JSONObject().put("id", profile.id).put("name", profile.name).put("sha256", profile.sha256))
                    .put("model", p.optString("model")).put("modelParts", JSONArray(parts.map { JSONObject().put("file", it.second.file).put("sha256", it.second.sha256) }))
                    .put("simulatedSlicer", sl.simulated)
                val mix = ColourMix.fromJson(p.optJSONObject("colourMix"))
                mix?.let { s.data.put("colourMix", it.toJson()) }
                when (val r = sl.slice(SliceRequest(p.optString("model"), parts, profile, out, mix))) {
                    is SliceResult.Success -> {
                        r.mixing?.let { s.data.put("mixingSystem", it) }
                        val sum = GcodeScan.scan(r.gcode)
                        s.data.put("gcode", JSONObject().put("sha256", sum.sha256).put("bytes", sum.bytes).put("lines", sum.lines))
                        val entry = JSONObject().put("test", test.id).put("file", r.gcode.absolutePath).put("sha256", sum.sha256).put("bytes", sum.bytes)
                            .put("profile", s.data.getJSONObject("profile")).put("model", p.optString("model")).put("modelParts", s.data.getJSONArray("modelParts"))
                            .put("simulatedSlicer", sl.simulated)
                        slices().put(test.id, entry); record.context.put("latestSlice", test.id)
                        StepStatus.PASSED to (if (sl.simulated) "Simulated slice (not a real engine run): " else "Sliced: ") + "${sum.bytes} bytes, ${sum.extrusionMoves} extruding moves" +
                            (r.mixing?.let { " (tool ${mix!!.a} + tool ${mix.b} colour mix, through $it)" } ?: "") + "."
                    }
                    is SliceResult.Blocked -> StepStatus.BLOCKED to r.reason
                    is SliceResult.Failed -> StepStatus.FAILED to r.message
                }
            }
            StepKind.SCAN_GCODE -> {
                val slice = latestSlice(step) ?: return StepStatus.BLOCKED to "No sliced G-code from this run."
                val file = File(slice.getString("file"))
                val sum = GcodeScan.scan(file)
                if (sum.sha256 != slice.optString("sha256")) return StepStatus.FAILED to "The sliced file changed after slicing (SHA-256 mismatch)."
                val profile = slicer?.profile(slice.getJSONObject("profile").getString("id"))
                val checks = p.optJSONArray("checks") ?: JSONArray()
                val failures = (0 until checks.length()).mapNotNull { checks.optJSONObject(it) }.mapNotNull { c -> GcodeScan.evaluate(c, sum, profile, record.target.optInt("toolSlots", 1))?.let { "${c.optString("check")}: $it" } }
                s.data.put("summary", JSONObject(sum.toJson())).put("checks", checks)
                if (failures.isEmpty()) StepStatus.PASSED to "All ${checks.length()} G-code checks passed." else StepStatus.FAILED to failures.joinToString(" ")
            }
            StepKind.UPLOAD_GUARD -> {
                val bytes = env.fixture(p.optString("fixture")) ?: return StepStatus.BLOCKED to "Fixture ${p.optString("fixture")} is missing."
                val file = File(env.workDir, "guard-" + p.optString("fixture")).also { it.parentFile.mkdirs(); it.writeBytes(bytes) }
                val expected = file.bufferedReader().useLines { ElegooProfiles.stockElegooCommand(it) }
                    ?: return StepStatus.BLOCKED to "The fixture is not an unsafe file; the check would prove nothing."
                val refusal = printer().uploadPreflight(file)
                s.data.put("fixture", p.optString("fixture")).put("fixtureSha256", Canon.sha256(bytes)).put("command", expected).put("refused", refusal != null)
                when {
                    refusal == null -> StepStatus.FAILED to "The upload path would have accepted a file containing $expected. It must refuse it."
                    refusal.contains(expected) -> StepStatus.PASSED to "The upload path refused the $expected file before sending anything: $refusal"
                    // Refused, but not by the guard (e.g. the printer was busy): proves nothing about the guard.
                    else -> StepStatus.BLOCKED to "The upload path refused for another reason, so the guard was not exercised: $refusal"
                }
            }
            StepKind.READ_IDENTITY -> {
                val id = printer().identity()
                val c = FirmwareFamilies.classify(printer().description, id)
                val want = expect.optString("firmwareFamily").ifBlank { suite.target.firmwareFamily }
                s.data.put("family", c.family).put("app", id.firmware?.app.orEmpty()).put("version", id.firmware?.version.orEmpty())
                    .put("hardware", JSONObject(c.hardware.toSortedMap())).put("detail", c.detail)
                record.target.put("firmware", JSONObject().put("family", c.family).put("app", id.firmware?.app.orEmpty()).put("version", id.firmware?.version.orEmpty()).put("detail", c.detail))
                record.target.put("hardware", JSONObject(c.hardware.toSortedMap()))
                if (c.family == want) StepStatus.PASSED to "Firmware reads as $want (${c.detail})."
                else StepStatus.FAILED to "Firmware reads as ${c.family} (${c.detail}), not $want. Results for one firmware family are never evidence for another."
            }
            StepKind.PROFILE_MATCH -> {
                val id = profileFor(p)
                val model = SlicingModelCatalog.all.firstOrNull { it.assetDir == id }?.model ?: return StepStatus.FAILED to "Profile $id is not a bundled profile."
                s.data.put("profile", id).put("slicingModel", model.name)
                val problem = ElegooProfiles.connectionProblem(model, printer().description.printerKind)
                // expect.refused: the step proves the app keeps a wrong-firmware profile away from this printer.
                if (expect.optBoolean("refused")) return if (problem != null) StepStatus.PASSED to "Refused as expected: $problem"
                    else StepStatus.FAILED to "Profile $id was accepted for this printer; it must be refused."
                problem?.let { return StepStatus.FAILED to it }
                if (ElegooProfiles.isCosmos(model)) {
                    val live = printer().identity().firmware
                    // Every bundled COSMOS pack is the 26.07.0+ ("current") generation.
                    when (val m = checkCentauriCarbonFirmwareMatch(live, CosmosProfileGeneration.CURRENT)) {
                        is FirmwareMatchResult.Match -> StepStatus.PASSED to "COSMOS ${live?.version} matches the current COSMOS profile generation."
                        is FirmwareMatchResult.Mismatch -> StepStatus.FAILED to m.reason
                        is FirmwareMatchResult.Unknown -> StepStatus.BLOCKED to m.reason
                    }
                } else {
                    val family = record.target.optJSONObject("firmware")?.optString("family")
                    if (family != suite.target.firmwareFamily) StepStatus.FAILED to "Profile $id is for ${suite.target.firmwareFamily}; the printer reads as $family."
                    else StepStatus.PASSED to "Profile $id suits a ${printer().description.printerKind.name} printer on $family firmware."
                }
            }
            StepKind.READ_STATUS -> {
                val r = printer().status()
                s.data.put("state", r.state).put("ready", r.ready).put("nozzle", r.nozzle).put("bed", r.bed).put("nozzleTarget", r.nozzleTarget).put("bedTarget", r.bedTarget)
                val problems = mutableListOf<String>()
                if (expect.optBoolean("ready", false) && !r.ready) problems += "the printer is not ready"
                expect.optJSONArray("states")?.let { a -> val ok = (0 until a.length()).map { a.optString(it) }; if (r.state !in ok) problems += "state ${r.state} not in $ok" }
                expect.strings("temperatures").forEach { h -> if ((if (h == "bed") r.bed else r.nozzle)?.isFinite() != true) problems += "no $h temperature reading" }
                if (problems.isEmpty()) StepStatus.PASSED to "Printer ${r.state}; nozzle ${r.nozzle} °C, bed ${r.bed} °C." else StepStatus.FAILED to problems.joinToString("; ")
            }
            StepKind.CHECK_CAPABILITIES -> {
                val caps = printer().declaredCapabilities() + record.target.strings("capabilities")
                val need = (p.strings("required") + test.requiredCapabilities).distinct()
                val missing = need.filter { it !in caps }
                s.data.put("required", JSONArray(need)).put("missing", JSONArray(missing))
                if (missing.isEmpty()) StepStatus.PASSED to "Declares ${need.joinToString()}." else StepStatus.FAILED to "Does not declare ${missing.joinToString()}."
            }
            StepKind.LIST_CAMERAS -> {
                val cams = printer().cameras()
                s.data.put("count", cams.size).put("kinds", JSONArray(cams.map { cameraKind(it.url) }.sorted()))
                if (cams.size >= expect.optInt("min", 1)) StepStatus.PASSED to "${cams.size} camera(s) listed." else StepStatus.FAILED to "${cams.size} camera(s) listed; expected at least ${expect.optInt("min", 1)}."
            }
            StepKind.CAMERA_SNAPSHOT -> {
                val cam = printer().cameras().firstOrNull() ?: return StepStatus.FAILED to "No camera is listed."
                val img = printer().cameraSnapshot(cam)
                val type = Attachments.sniff(img)
                s.data.put("bytes", img.size).put("sha256", Canon.sha256(img)).put("type", type ?: "unknown")
                // The image itself is not kept: it can show the tester's room. Only its size and hash are evidence.
                if (type != null && img.size >= expect.optInt("minBytes", 1000)) StepStatus.PASSED to "Snapshot: $type, ${img.size} bytes (image not stored)."
                else StepStatus.FAILED to "The snapshot was not a usable image (${img.size} bytes)."
            }
            StepKind.LIST_FILES -> {
                val files = printer().files()
                s.data.put("count", files.size)
                val up = latestUpload(step)
                if (expect.optBoolean("uploaded") && up == null) StepStatus.BLOCKED to "Nothing was uploaded by this run."
                else if (expect.optBoolean("uploaded") && up!!.optString("remotePath") !in files) StepStatus.FAILED to "The uploaded file is not listed."
                else StepStatus.PASSED to "${files.size} file(s) listed${if (expect.optBoolean("uploaded")) ", including the uploaded file" else ""}."
            }
            StepKind.READ_MATERIAL_SLOTS -> {
                val slots = printer().materialSlots()
                s.data.put("count", slots.size).put("slots", JSONArray(slots.take(16)))
                val min = expect.optInt("min", 1)
                if (slots.size >= min) StepStatus.PASSED to "${slots.size} material slot(s) reported." else StepStatus.FAILED to "${slots.size} slot(s) reported; expected at least $min."
            }
            StepKind.MONITOR -> monitor(test, step, s)
            StepKind.UPLOAD -> {
                val slice = latestSlice(step) ?: return StepStatus.BLOCKED to "No sliced G-code from this run to upload."
                val file = File(slice.getString("file"))
                if (Canon.sha256(file) != slice.optString("sha256")) return StepStatus.FAILED to "The sliced file changed after slicing; not uploading it."
                when (val r = printer().upload(file, uploadName(step))) {
                    is TransferOutcome.Verified -> {
                        uploads().put(JSONObject().put("remotePath", r.remotePath).put("sha256", r.sha256).put("fromTest", step.params.optString("fromTest")).put("test", test.id))
                        record.context.put("remotePath", r.remotePath)
                        s.data.put("remotePath", r.remotePath).put("sha256", r.sha256).put("bytes", file.length())
                        if (r.sha256 == slice.optString("sha256")) StepStatus.PASSED to "Uploaded and verified by SHA-256."
                        else StepStatus.FAILED to "The printer holds different bytes than were sent (SHA-256 mismatch)."
                    }
                    is TransferOutcome.Refused -> (if (r.sent) StepStatus.FAILED else StepStatus.BLOCKED) to r.reason
                    is TransferOutcome.Unknown -> StepStatus.OUTCOME_UNKNOWN to r.reason
                }
            }
            StepKind.DELETE_UPLOADED -> {
                val up = latestUpload(step)?.takeIf { !it.optBoolean("deleted") } ?: return StepStatus.SKIPPED to "Nothing uploaded by this run is left to delete."
                when (val r = printer().delete(up.getString("remotePath"))) {
                    is TransferOutcome.Verified -> { up.put("deleted", true); StepStatus.PASSED to "Deleted and verified absent." }
                    // A finished print keeps its file loaded, and the app never deletes a loaded file (found on the level-4 run).
                    is TransferOutcome.Refused -> if (!r.sent && runCatching { printer().status().filename }.getOrNull() == up.getString("remotePath")) {
                        s.data.put("leftOnPrinter", up.getString("remotePath"))
                        StepStatus.SKIPPED to "Left on the printer: it still has ${up.getString("remotePath")} loaded from the print, and Nozzle never deletes a loaded file. Delete it on the printer once another file is loaded."
                    } else (if (r.sent) StepStatus.FAILED else StepStatus.BLOCKED) to r.reason
                    is TransferOutcome.Unknown -> StepStatus.OUTCOME_UNKNOWN to r.reason
                }
            }
            StepKind.SEND_AND_START -> {
                val slice = latestSlice(step) ?: return StepStatus.BLOCKED to "No sliced G-code from this run to send."
                val file = File(slice.getString("file"))
                if (Canon.sha256(file) != slice.optString("sha256")) return StepStatus.FAILED to "The sliced file changed after slicing; not sending it."
                val start = ControlAction.StartPrint(uploadName(step))
                val fresh = try { printer().status() } catch (e: Exception) { return StepStatus.BLOCKED to "The printer could not be re-checked (${e.message}). Nothing was sent." }
                if (fresh.state !in start.allowedStates || !fresh.ready) return StepStatus.BLOCKED to "The printer is ${fresh.state}${if (fresh.ready) "" else " and not ready"}; sending a print needs it idle. Nothing was sent."
                when (val r = printer().sendAndStart(file, uploadName(step))) {
                    is TransferOutcome.Verified -> {
                        uploads().put(JSONObject().put("remotePath", r.remotePath).put("sha256", slice.optString("sha256")).put("fromTest", step.params.optString("fromTest")).put("test", test.id).put("started", true))
                        s.data.put("remotePath", r.remotePath).put("sha256", slice.optString("sha256")).put("bytes", file.length())
                        StepStatus.PASSED to "The printer accepted ${r.remotePath} and started printing it."
                    }
                    is TransferOutcome.Refused -> (if (r.sent) StepStatus.FAILED else StepStatus.BLOCKED) to r.reason
                    is TransferOutcome.Unknown -> confirmByState(ControlAction.StartPrint(uploadName(step)), fresh, s)?.let {
                        uploads().put(JSONObject().put("remotePath", uploadName(step)).put("sha256", slice.optString("sha256")).put("fromTest", step.params.optString("fromTest")).put("test", test.id).put("started", true))
                        StepStatus.PASSED to "The printer's reply was lost (${r.reason.take(80)}), but its state confirms it: $it"
                    } ?: (StepStatus.OUTCOME_UNKNOWN to r.reason)
                }
            }
            StepKind.DELETE_LEFTOVERS -> {
                // Only names the operator approved, and only those still eligible now: re-read, never widened.
                val approved = s.data.optJSONArray("candidates")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                val eligible = leftovers().toSet()
                val deleted = JSONArray(); val kept = JSONArray()
                for (path in approved) {
                    if (path !in eligible) { kept.put(JSONObject().put("file", path).put("reason", "no longer eligible (gone, or loaded now)")); continue }
                    when (val r = printer().delete(path)) {
                        is TransferOutcome.Verified -> { deleted.put(path); markDeleted(path) }
                        is TransferOutcome.Refused -> {
                            kept.put(JSONObject().put("file", path).put("reason", r.reason))
                            if (r.sent) { s.data.put("deleted", deleted).put("kept", kept); return StepStatus.FAILED to "The printer refused to delete $path: ${r.reason}" }
                        }
                        is TransferOutcome.Unknown -> {
                            s.data.put("deleted", deleted).put("kept", kept).put("unknown", path)
                            return StepStatus.OUTCOME_UNKNOWN to "Deleting $path: ${r.reason}${if (deleted.length() > 0) " (${deleted.length()} deleted before it)" else ""}"
                        }
                    }
                }
                s.data.put("deleted", deleted).put("kept", kept)
                StepStatus.PASSED to "Deleted ${deleted.length()} earlier Test Grid file(s)${if (kept.length() > 0) "; left ${kept.length()}: " + (0 until kept.length()).joinToString("; ") { kept.getJSONObject(it).let { k -> "${k.getString("file")} (${k.getString("reason")})" } } else ""}."
            }
            StepKind.SET_TEMPERATURE, StepKind.HOME, StepKind.JOG, StepKind.START_PRINT, StepKind.PAUSE, StepKind.RESUME, StepKind.CANCEL -> {
                val action = controlAction(step) ?: return StepStatus.BLOCKED to "Nothing uploaded by this run to start."
                // Re-read immediately before sending; send nothing if the printer is no longer in a state that allows it.
                val fresh = try { printer().status() } catch (e: Exception) { return StepStatus.BLOCKED to "The printer could not be re-checked (${e.message}). Nothing was sent." }
                val ok = fresh.state in action.allowedStates && (fresh.ready || action is ControlAction.Pause || action is ControlAction.Cancel)
                if (!ok) return StepStatus.BLOCKED to "The printer is ${fresh.state}${if (fresh.ready) "" else " and not ready"}; \"${action.describe()}\" needs ${action.allowedStates.sorted().joinToString("/")}. Nothing was sent."
                when (val r = printer().perform(action)) {
                    CommandOutcome.Accepted -> StepStatus.PASSED to "The printer acknowledged: ${action.describe()}"
                    is CommandOutcome.Rejected -> (if (r.sent) StepStatus.FAILED else StepStatus.BLOCKED) to r.reason
                    is CommandOutcome.Unknown -> confirmByState(action, fresh, s)?.let { StepStatus.PASSED to "The printer's reply was lost (${r.reason.take(80)}), but its state confirms it: $it" }
                        ?: (StepStatus.OUTCOME_UNKNOWN to r.reason)
                }
            }
            StepKind.OBSERVE, StepKind.ATTACH -> throw IllegalStateException("Operator steps are not executed automatically.")
        }
    }

    /**
     * After a lost reply, looks for proof in the printer's own state that [action] took effect, polling for up to three
     * minutes (a homing move can outlast the reply). Read-only; the command is never resent. Found on the level-3 run on the
     * real U1: G28 homed the printer but its reply timed out, and the test was recorded as unknown although the printer
     * could have shown the result. Null when nothing proves it; the outcome then stays unknown.
     */
    private fun confirmByState(action: ControlAction, before: StatusReading, s: StepRecord): String? {
        val deadline = now() + 180_000
        while (true) {
            val r = try { printer().status() } catch (e: Exception) { null }
            val proof = r?.let { stateProof(action, before, it) }
            if (proof != null) {
                s.data.put("confirmedByState", JSONObject().put("state", r.state).put("nozzleTarget", r.nozzleTarget).put("bedTarget", r.bedTarget)
                    .put("homedAxes", r.homedAxes).put("position", r.position?.let { JSONArray(it) }).put("observedAt", r.observedAtMillis))
                log("Reply lost; confirmed by printer state: $proof")
                return proof
            }
            if (now() >= deadline || interruptRequest != null) return null
            env.sleep(3000)
        }
    }

    private fun stateProof(a: ControlAction, before: StatusReading, r: StatusReading): String? {
        fun homed(h: String?) = h != null && "x" in h && "y" in h && "z" in h
        return when (a) {
            is ControlAction.SetTemperature -> (if (a.heater == "bed") r.bedTarget else r.nozzleTarget)
                ?.takeIf { kotlin.math.abs(it - a.celsius) < 0.5 }?.let { "the ${a.heater} target reads ${"%.0f".format(java.util.Locale.ROOT, it)} °C" }
            // Only proof when the axes were not all homed before the command: otherwise nothing distinguishes a new homing.
            ControlAction.Home -> if (before.homedAxes != null && !homed(before.homedAxes) && homed(r.homedAxes) && r.idle)
                "all axes now homed (homed axes were \"${before.homedAxes}\")" else null
            is ControlAction.Jog -> {
                val i = "XYZ".indexOf(a.axis); val b = before.position?.getOrNull(i); val p = r.position?.getOrNull(i)
                if (i >= 0 && b != null && p != null && kotlin.math.abs((p - b) - a.mm) < 0.05 && r.idle) "${a.axis} moved from ${"%.2f".format(java.util.Locale.ROOT, b)} to ${"%.2f".format(java.util.Locale.ROOT, p)} mm" else null
            }
            is ControlAction.StartPrint -> if (r.state in setOf("printing", "paused", "complete") && SuiteProfiles.sameFile(r.filename, a.remotePath)) "the printer is ${r.state} ${a.remotePath}" else null
            ControlAction.Pause -> if (r.state == "paused") "the printer is paused" else null
            ControlAction.Resume -> if (r.state == "printing" && before.state == "paused") "the printer is printing again" else null
            ControlAction.Cancel -> if (r.state == "cancelled") "the print is cancelled" else null
        }
    }

    private fun cameraKind(url: String): String = when {
        url.contains("webrtc", true) -> "webrtc"
        url.startsWith("rtsp", true) -> "rtsp"
        url.contains("stream", true) || url.contains("mjpeg", true) -> "mjpeg"
        url.contains("snapshot", true) -> "snapshot"
        else -> "other"
    }

    private fun monitor(test: TestCase, step: Step, s: StepRecord): Pair<StepStatus, String> {
        val p = step.params
        val until = p.optString("until")
        val timeout = (step.timeoutSeconds ?: 60) * 1000L
        val poll = (p.optInt("pollSeconds", 5).coerceIn(1, 600)) * 1000L
        val start = now()
        var first: StatusReading? = null
        var last: StatusReading? = null
        var samples = 0
        while (true) {
            if (interruptRequest != null) return StepStatus.SKIPPED to "Interrupted while monitoring."
            val r = try { printer().status() } catch (e: Exception) { null }
            if (r != null) {
                samples++; if (first == null) first = r; last = r
                val tol = p.optDouble("toleranceC", 3.0)
                val reading = if (p.optString("heater") == "bed") r.bed else r.nozzle
                val met = when (until) {
                    "printing" -> r.state == "printing"
                    "paused" -> r.state == "paused"
                    "complete" -> r.state == "complete"
                    "idle" -> r.idle
                    "progress_increases" -> r.progress != null && first.progress != null && r.progress > first.progress!!
                    "heater_reaches" -> reading != null && kotlin.math.abs(reading - p.optDouble("celsius")) <= tol
                    "heater_below" -> reading != null && reading <= p.optDouble("celsius")
                    else -> false
                }
                if (until == "complete" && r.state in setOf("error", "cancelled")) {
                    s.data.put("samples", samples).put("lastState", r.state)
                    return StepStatus.FAILED to "The print ended as ${r.state}."
                }
                // The job is gone without the printer ever saying it completed (its state was cleared, for example by a
                // restart while Nozzle couldn't reach it): the printer can no longer answer, so the operator does.
                // Found on the owner's Centauri Carbon after a network outage: the step waited 4 h for "complete".
                if (until == "complete" && r.state == "standby") {
                    s.data.put("samples", samples).put("lastState", r.state)
                    // The printer's own job history still knows how the print ended.
                    val file = (0 until uploads().length()).map { uploads().getJSONObject(it) }.lastOrNull { it.optString("test") == test.id }?.optString("remotePath")
                    val recorded = file?.let { runCatching { printer().jobResult(it) }.getOrNull() }
                    if (recorded != null) s.data.put("jobHistory", recorded)
                    when (recorded) {
                        "completed" -> return StepStatus.PASSED to "The printer no longer shows the print live (state standby), and its job history records $file as completed."
                        "cancelled", "error", "klippy_shutdown", "klippy_disconnect", "interrupted" -> return StepStatus.FAILED to "The printer's job history records $file as $recorded."
                    }
                    s.data.put("askOperator", COMPLETE_QUESTION)
                    return StepStatus.AWAITING_OBSERVATION to "The printer no longer reports this print (state standby${if (r.filename.isBlank()) ", no file loaded" else ""}), so it can't confirm it completed. Asking the operator."
                }
                if (met) {
                    s.data.put("samples", samples).put("lastState", r.state).put("progress", r.progress).put("durationMillis", now() - start)
                    return StepStatus.PASSED to "Reached \"$until\" after $samples reading(s)."
                }
            }
            if (now() - start >= timeout) {
                s.data.put("samples", samples).put("lastState", last?.state ?: "unreadable")
                val msg = "Did not reach \"$until\" within ${timeout / 1000} s (last state ${last?.state ?: "unreadable"})."
                return when (step.onTimeout) {
                    TimeoutBehavior.FAIL -> StepStatus.FAILED to msg
                    TimeoutBehavior.BLOCKED -> StepStatus.BLOCKED to msg
                    TimeoutBehavior.OUTCOME_UNKNOWN -> StepStatus.OUTCOME_UNKNOWN to msg
                }
            }
            env.sleep(poll)
        }
    }
}

/** Grades an operator's answer against the step's expectation. */
object Observations {
    fun evaluate(step: Step, raw: String): Pair<StepStatus, String> {
        val value = raw.trim()
        val e = step.expect
        return when (step.params.optString("response")) {
            "yes_no" -> {
                val v = value.lowercase()
                if (v !in setOf("yes", "no")) throw RunRefused("Answer yes or no.")
                val want = e.optString("equals", "yes")
                if (v == want) StepStatus.PASSED to "Answered $v." else StepStatus.FAILED to "Answered $v; expected $want."
            }
            "pass_partial_fail" -> when (value.lowercase()) {
                "pass" -> StepStatus.PASSED to "Operator judged: pass."
                "partial" -> StepStatus.PARTIAL to "Operator judged: partial."
                "fail" -> StepStatus.FAILED to "Operator judged: fail."
                else -> throw RunRefused("Answer pass, partial or fail.")
            }
            "number" -> {
                val n = value.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw RunRefused("Enter a number.")
                val min = e.optDouble("min", Double.NEGATIVE_INFINITY); val max = e.optDouble("max", Double.POSITIVE_INFINITY)
                val unit = step.params.optString("unit")
                if (n in min..max) StepStatus.PASSED to "Measured $n $unit (accepted $min to $max)." else StepStatus.FAILED to "Measured $n $unit; accepted range is $min to $max."
            }
            "choice" -> {
                val choices = step.params.strings("choices")
                if (value !in choices) throw RunRefused("Choose one of: ${choices.joinToString()}.")
                val ok = e.strings("oneOf")
                if (ok.isEmpty() || value in ok) StepStatus.PASSED to "Chose \"$value\"." else StepStatus.FAILED to "Chose \"$value\"; acceptable: ${ok.joinToString()}."
            }
            "text" -> { if (value.isBlank()) throw RunRefused("Describe what you saw."); StepStatus.PASSED to "Recorded." }
            else -> throw RunRefused("Unsupported response type.")
        }
    }
}

package net.jamesjennison.klippercompanion.testgrid

import android.content.Context
import com.nozzleitall.testgrid.EvidenceBuilder
import com.nozzleitall.testgrid.EvidenceBundle
import com.nozzleitall.testgrid.FirmwareFamilies
import com.nozzleitall.testgrid.Pending
import com.nozzleitall.testgrid.Redactor
import com.nozzleitall.testgrid.RunJournal
import com.nozzleitall.testgrid.RunSession
import com.nozzleitall.testgrid.SafetyLevel
import com.nozzleitall.testgrid.SimulatedPrinter
import com.nozzleitall.testgrid.SimulatedSlicer
import com.nozzleitall.testgrid.Suite
import com.nozzleitall.testgrid.SuiteCatalog
import com.nozzleitall.testgrid.TargetCheck
import com.nozzleitall.testgrid.TargetSnapshot
import com.nozzleitall.testgrid.TestSlicer
import com.nozzleitall.testgrid.TestTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.jamesjennison.klippercompanion.PrinterKind
import net.jamesjennison.klippercompanion.PrinterProfile
import org.json.JSONObject
import java.io.File

/** A printer the operator can test: a saved printer, or a simulated one that never grades hardware. */
sealed class TargetOption {
    abstract val title: String
    data class Saved(val profile: PrinterProfile) : TargetOption() { override val title get() = profile.label }
    data class Simulated(val preset: SimulatedPrinter.Preset) : TargetOption() { override val title get() = "Simulated ${preset.manufacturer} ${preset.model} (${preset.name.lowercase().replace('_', ' ')})" }
}

enum class TestModePhase { SELECT_TARGET, SELECT_SUITE, REVIEW_PLAN, RUNNING, REVIEW_EVIDENCE }

data class TestModeState(
    val phase: TestModePhase = TestModePhase.SELECT_TARGET,
    val target: TargetOption? = null,
    val declaredFamily: String? = null,
    val snapshot: TargetSnapshot? = null,
    val targetConfirmed: Boolean = false,
    val suites: List<Suite> = emptyList(),
    val importedSuiteIds: Set<String> = emptySet(),
    val suite: Suite? = null,
    val mismatches: List<String> = emptyList(),
    val maxLevel: SafetyLevel = SafetyLevel.SOFTWARE,
    val pending: Pending? = null,
    /** Bumped on every change to the session, so the results list recomposes. */
    val revision: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
    val bundle: EvidenceBundle? = null,
    val exported: String? = null,
    val resumable: Boolean = false,
)

/**
 * Test Mode's state holder. All session work runs on Dispatchers.IO behind one lock, one call at a time. The run lives
 * in filesDir/testgrid/active (journal, attachments, and meta.json naming the target and suite) until the operator
 * exports or discards it, so closing Test Mode or restarting the app never loses or repeats anything.
 */
class TestModeController private constructor(private val context: Context) {
    /** Test Mode's own scope: closing the window must not orphan a step that is still running or writing the journal. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** The dashboard's saved printers (with credentials), refreshed each time Test Mode opens. */
    @Volatile var savedProfiles: () -> List<PrinterProfile> = { emptyList() }
    private val _state = MutableStateFlow(TestModeState(suites = runCatching { SuiteCatalog.bundled() }.getOrDefault(emptyList())))
    val state: StateFlow<TestModeState> = _state.asStateFlow()
    private val lock = Mutex()
    private val runDir = File(context.filesDir, "testgrid/active")
    private val metaFile = File(runDir, "meta.json")
    private var target: TestTarget? = null
    /** Simulated printers run on simulated time, so a simulated print doesn't take hours. */
    private val simulatedClock = com.nozzleitall.testgrid.VirtualClock()
    var session: RunSession? = null; private set

    init { _state.value = _state.value.copy(resumable = File(runDir, "journal.json").isFile && metaFile.isFile) }

    val targets: List<TargetOption> get() = savedProfiles().map { TargetOption.Saved(it) } + SimulatedPrinter.Preset.entries.map { TargetOption.Simulated(it) }

    private fun update(f: (TestModeState) -> TestModeState) { _state.value = f(_state.value) }

    private fun work(block: suspend () -> Unit) {
        scope.launch {
            lock.withLock {
                update { it.copy(busy = true, error = null) }
                try { withContext(Dispatchers.IO) { block() } }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                // Errors too (a first device run hit an ExceptionInInitializerError while building the bundle): report, don't crash.
                catch (e: Throwable) { update { it.copy(error = (e.cause ?: e).let { c -> c.message ?: c.javaClass.simpleName }) } }
                finally { update { it.copy(busy = false, revision = it.revision + 1, pending = session?.pending() ?: it.pending) } }
            }
        }
    }

    private fun buildTarget(option: TargetOption, declaredFamily: String?): TestTarget = when (option) {
        is TargetOption.Saved -> AndroidTestTarget(option.profile, context.cacheDir, declaredFamily)
        is TargetOption.Simulated -> SimulatedPrinter(option.preset, simulatedClock::now)
    }

    private fun buildSlicer(option: TargetOption): TestSlicer = when (option) {
        is TargetOption.Saved -> AndroidTestSlicer(context, option.profile)
        is TargetOption.Simulated -> SimulatedSlicer({ id, f -> runCatching { context.assets.open("slicer_profiles/$id/$f").use { it.readBytes() } }.getOrNull() }, File(context.cacheDir, "testgrid-sliced"))
    }

    /** Read-only inspection of the chosen printer: identity, status, capabilities, and anything that disqualifies it. */
    fun selectTarget(option: TargetOption, declaredFamily: String? = null) {
        update { it.copy(target = option, declaredFamily = declaredFamily, snapshot = null, targetConfirmed = false, suite = null, mismatches = emptyList()) }
        work {
            val t = buildTarget(option, declaredFamily)
            target = t
            val snap = TargetCheck.inspect(t)
            update { it.copy(snapshot = snap) }
        }
    }

    fun confirmTarget(confirmed: Boolean) = update { it.copy(targetConfirmed = confirmed) }
    fun toSuites() = update { it.copy(phase = TestModePhase.SELECT_SUITE) }
    fun back() = update { s -> s.copy(phase = when (s.phase) { TestModePhase.SELECT_SUITE -> TestModePhase.SELECT_TARGET; TestModePhase.REVIEW_PLAN -> TestModePhase.SELECT_SUITE; else -> s.phase }) }

    fun importSuite(text: String) {
        try {
            val s = Suite.parse(text)
            update { st -> st.copy(suites = st.suites.filter { it.id != s.id } + s, importedSuiteIds = st.importedSuiteIds + s.id, error = null) }
        } catch (e: Exception) { update { it.copy(error = e.message) } }
    }

    fun selectSuite(suite: Suite) {
        val snap = _state.value.snapshot ?: return
        val mismatches = TargetCheck.mismatches(suite, snap, net.jamesjennison.klippercompanion.BuildConfig.VERSION_NAME)
        update { it.copy(suite = suite, mismatches = mismatches, maxLevel = SafetyLevel.SOFTWARE, phase = if (mismatches.isEmpty()) TestModePhase.REVIEW_PLAN else it.phase) }
    }

    fun setMaxLevel(level: SafetyLevel) = update { it.copy(maxLevel = level) }

    fun start() {
        val st = _state.value
        val option = st.target ?: return; val suite = st.suite ?: return; val snap = st.snapshot ?: return
        if (option is TargetOption.Saved && !st.targetConfirmed) { update { it.copy(error = "Confirm that this is the printer in front of you first.") }; return }
        work {
            runDir.deleteRecursively(); runDir.mkdirs()
            val env = AndroidTestEnvironment.create(context, File(runDir, "work"), simulatedEngine = option is TargetOption.Simulated, clock = simulatedClock.takeIf { option is TargetOption.Simulated })
            val t = target ?: buildTarget(option, st.declaredFamily).also { target = it }
            val s = RunSession.start(suite, t, snap, buildSlicer(option), env, RunJournal(runDir), st.maxLevel)
            metaFile.writeText(JSONObject().put("suite", JSONObject(suite.source)).put("imported", suite.id in st.importedSuiteIds)
                .put("target", when (option) { is TargetOption.Saved -> JSONObject().put("type", "saved").put("address", option.profile.address); is TargetOption.Simulated -> JSONObject().put("type", "simulated").put("preset", option.preset.name) })
                .putOpt("declaredFamily", st.declaredFamily).toString())
            session = s
            update { it.copy(phase = TestModePhase.RUNNING, resumable = false) }
            val p = s.proceed()
            update { it.copy(pending = p) }
        }
    }

    /** Reopens the journaled run. A command caught in flight becomes an unknown outcome; approvals must be given again. */
    fun resume() {
        work {
            val meta = JSONObject(metaFile.readText())
            val suite = Suite.parse(meta.getJSONObject("suite"))
            val t = meta.getJSONObject("target")
            val option = if (t.getString("type") == "simulated") TargetOption.Simulated(SimulatedPrinter.Preset.valueOf(t.getString("preset")))
                else TargetOption.Saved(savedProfiles().firstOrNull { it.address == t.getString("address") } ?: throw IllegalStateException("The printer this run used is no longer saved. Export or discard the run."))
            val declared = meta.optString("declaredFamily").ifBlank { null }
            val tt = buildTarget(option, declared).also { target = it }
            val env = AndroidTestEnvironment.create(context, File(runDir, "work"), simulatedEngine = option is TargetOption.Simulated, clock = simulatedClock.takeIf { option is TargetOption.Simulated })
            val s = RunSession.resume(suite, tt, buildSlicer(option), env, RunJournal(runDir)) ?: throw IllegalStateException("The saved run could not be read.")
            session = s
            update { it.copy(phase = if (s.record.completedAt != null) TestModePhase.REVIEW_EVIDENCE else TestModePhase.RUNNING, target = option, suite = suite, declaredFamily = declared, resumable = false) }
            if (s.record.completedAt != null) buildBundle() else update { it.copy(pending = s.proceed()) }
        }
    }

    fun discardSaved() { runDir.deleteRecursively(); session = null; target = null; update { TestModeState(suites = it.suites, importedSuiteIds = it.importedSuiteIds) } }

    fun proceed() = act { it.proceed() }
    fun answerPreconditions(answers: Map<String, Boolean>) = act { it.answerPreconditions(answers) }
    fun approve(stepId: String) = act { it.approve(stepId) }
    fun decline(stepId: String) = act { it.decline(stepId, "Declined in Test Mode.") }
    fun observe(stepId: String, value: String, note: String) = act { it.observe(stepId, value, note) }
    fun attach(stepId: String, bytes: ByteArray, mime: String) = act { it.attach(stepId, bytes, mime) }
    fun reviewUnknown(note: String) = act { it.reviewUnknown(note); it.proceed() }
    fun finishRun() = act { it.finish() }

    /** Safe from any thread and while a step is running: nothing further runs for the current test except its cleanup. */
    fun interrupt() { session?.interrupt("Interrupted by the operator in Test Mode."); proceed() }

    private fun act(block: (RunSession) -> Pending?) {
        val s = session ?: return
        work {
            val p = block(s)
            update { it.copy(pending = p) }
            if (p is Pending.Finished) buildBundle()
        }
    }

    private fun buildBundle() {
        val s = session ?: return
        val t = target ?: return
        val env = AndroidTestEnvironment.create(context, File(runDir, "work"), simulatedEngine = t.description.kind == com.nozzleitall.testgrid.TargetKind.SIMULATED)
        val redactor = Redactor(t.localSecrets() + context.filesDir.absolutePath + context.cacheDir.absolutePath + runDir.absolutePath)
        val bundle = EvidenceBuilder.build(s, env, redactor, RunJournal(runDir)::attachment)
        update { it.copy(phase = TestModePhase.REVIEW_EVIDENCE, bundle = bundle) }
    }

    fun exportTo(uri: android.net.Uri) {
        val b = _state.value.bundle ?: return
        work {
            context.contentResolver.openOutputStream(uri)?.use { it.write(b.zip()) } ?: throw IllegalStateException("Could not open the chosen file.")
            update { it.copy(exported = b.bundleDigest) }
        }
    }

    /** After export (or to abandon a finished run): removes the run from this device. */
    fun clearRun() { discardSaved() }

    companion object {
        // Holds only the application context (see shared()), which lives as long as the process: not a leak.
        @android.annotation.SuppressLint("StaticFieldLeak")
        private var shared: TestModeController? = null

        /**
         * One controller per process, so a run survives Test Mode being closed and reopened and only one session ever
         * writes the journal.
         */
        @Synchronized fun shared(context: Context): TestModeController = shared ?: TestModeController(context.applicationContext).also { shared = it }

        /** Instrumented tests only: forget the process-wide controller (its saved run is removed by the test). */
        @androidx.annotation.VisibleForTesting @Synchronized fun resetShared() { shared = null }

        /** Families the operator can declare for an Elegoo LAN printer, which can't report which firmware it runs. */
        val ELEGOO_DECLARABLE = listOf(FirmwareFamilies.ELEGOO_STOCK, FirmwareFamilies.OPENCENTAURI_PATCHED)
        fun needsDeclaration(option: TargetOption?) = option is TargetOption.Saved && option.profile.kind == PrinterKind.ELEGOO
    }
}

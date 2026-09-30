package com.nozzleitall.testgrid

import java.io.File

/** Shared test scaffolding: a stepping clock, environments, and a fake that reports itself as physical (tests only). */
object Support {
    val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile && File(it, "test-grid").isDirectory }

    fun profiles(): (String, String) -> ByteArray? = { id, f -> File(root, "app/src/main/assets/slicer_profiles/$id/$f").takeIf { it.isFile }?.readBytes() }

    /** Every read advances one second, so ordering is deterministic and timestamps differ between calls. */
    class Clock(var t: Long = 1_790_000_000_000L) { fun now(): Long = t.also { t += 1000 } }

    fun env(work: File, clock: Clock, ids: Iterator<String> = generateSequence(1) { it + 1 }.map { "00000000-0000-4000-8000-%012d".format(it) }.iterator(), version: String = "0.1.0") =
        Environment(Producer("Nozzle It All", "jvm-test", version, "test", null, "0123456789abcdef"),
            EngineInfo("nozzle-engine", "dc86dbf00d1d3239bf0a937cf0eefdb4459054b1", null, null, null, simulated = true),
            ModelLibrary.fromResources(), Environment::resourceFixture, work, clock::now, { ids.next() }, sleep = {})

    fun suite(id: String): Suite = SuiteCatalog.bundled(id) ?: error("no suite $id")

    fun tmp(): File = kotlin.io.path.createTempDirectory("testgrid").toFile().also { it.deleteOnExit() }

    class Started(val session: RunSession, val journal: RunJournal, val env: Environment, val target: TestTarget, val slicer: TestSlicer)

    fun start(suiteId: String, target: TestTarget, clock: Clock, level: SafetyLevel = SafetyLevel.PHYSICAL_PRINT, work: File = tmp(), supersedes: List<String> = emptyList()): Started {
        val env = env(work, clock)
        val slicer = SimulatedSlicer(profiles(), File(work, "sliced"))
        val journal = RunJournal(File(work, "run"))
        val session = RunSession.start(suite(suiteId), target, TargetCheck.inspect(target), slicer, env, journal, level, supersedes)
        return Started(session, journal, env, target, slicer)
    }

    /** A simulated printer that claims to be physical, to exercise the report's handling of physical evidence in tests. */
    class PhysicalFake(val sim: SimulatedPrinter) : TestTarget by sim {
        override val description = sim.description.copy(kind = TargetKind.PHYSICAL, adapter = "android-moonraker", protocol = "moonraker-http")
    }

    /** Test-only operator: approves and answers everything with the expected values. */
    fun drive(s: RunSession, answers: Map<String, String> = emptyMap(), onUnknown: (RunSession) -> Unit = { it.reviewUnknown("Checked the printer in the test.") }): Pending {
        var p = s.proceed()
        var n = 0
        while (p !is Pending.Finished && n++ < 5000) {
            p = when (p) {
                is Pending.Preconditions -> s.answerPreconditions(p.test.preconditions.associate { it.id to true })
                is Pending.Confirmation -> s.approve(p.step.id)
                is Pending.Observation -> s.observe(p.step.id, answers[p.step.id] ?: ScriptedOperator.defaultAnswer(p.step))
                is Pending.Attachment -> s.attach(p.step.id, ScriptedOperator.placeholderPng(), "image/png")
                is Pending.UnknownReview -> { onUnknown(s); s.proceed() }
                Pending.Finished -> p
            }
        }
        return p
    }

    fun build(st: Started): EvidenceBundle = EvidenceBuilder.build(st.session, st.env, Redactor(st.target.localSecrets() + st.env.workDir.absolutePath), st.journal::attachment)
}

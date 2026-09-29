package com.nozzleitall.testgrid

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The owner's rule: a test that already passed isn't run again at a higher level (level 4 shouldn't repeat level 2). */
class CarryOverTest {
    private class Run(val session: RunSession, val bundle: EvidenceBundle, val summary: CarryOver.RunSummary?)

    private fun run(target: TestTarget, clock: Support.Clock, level: SafetyLevel, carried: Map<String, PriorPass> = emptyMap(), suite: Suite = Support.suite("paxx-u1")): Run {
        val work = Support.tmp()
        val env = Support.env(work, clock)
        val journal = RunJournal(File(work, "run"))
        val s = RunSession.start(suite, target, TargetCheck.inspect(target), SimulatedSlicer(Support.profiles(), File(work, "sliced")), env, journal, level, carried = carried)
        assertEquals(Pending.Finished, Support.drive(s))
        val b = EvidenceBuilder.build(s, env, Redactor(target.localSecrets() + work.absolutePath, PublicVocabulary.of(suite)), journal::attachment)
        return Run(s, b, CarryOver.summarize(s, b.bundleDigest, "printer-1", env.producer.version))
    }

    @Test fun passedTestsCarryAndInputsRunAgain() {
        val clock = Support.Clock()
        val target = Support.PhysicalFake(SimulatedPrinter(SimulatedPrinter.Preset.PAXX_U1, clock::now))
        val first = run(target, clock, SafetyLevel.SUPERVISED_CONTROLS)
        val carry = CarryOver.eligible(listOfNotNull(first.summary), Support.suite("paxx-u1"), TargetCheck.inspect(target), "printer-1", "0.1.0")
        assertTrue(carry.keys.containsAll(listOf("identity", "transfer", "controls-idle", "slice-single")))
        val second = run(target, clock, SafetyLevel.PHYSICAL_PRINT, carry)
        val rec = second.session.record
        // Carried: skipped with a pointer to the run that passed it; never re-run, never recorded as a pass.
        listOf("identity", "transfer", "controls-idle", "upload-guard").forEach { id ->
            val t = rec.test(id)!!
            assertEquals(id, ResultState.SKIPPED, t.result)
            assertTrue(t.reason, t.reason.startsWith("Already passed in run ${first.session.record.runId.take(8)}"))
        }
        // The prints take their G-code from slice-single, so it runs again in this run.
        assertEquals(ResultState.PASS, rec.test("slice-single")!!.result)
        listOf("print-single", "print-controls").forEach { assertEquals(it, ResultState.PASS, rec.test(it)!!.result) }
        val tests = second.bundle.evidence.getJSONArray("tests").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        assertEquals(first.session.record.runId, tests.first { it.getString("id") == "transfer" }.getJSONObject("carriedFrom").getString("runId"))
        assertTrue(tests.first { it.getString("id") == "print-single" }.opt("carriedFrom") == null)
        // The report grades each test from its newest actual result: both runs together are a full pass, with no PARTIAL.
        val report = ReportBuilder.build(listOf("a.zip" to first.bundle.zip(), "b.zip" to second.bundle.zip()), listOf(Support.suite("paxx-u1")))
        val row = report.rows.first { it.key.scope == MaterialScope.SINGLE && it.current != null }
        Category.entries.forEach { assertEquals(it.label, "PASS (unreviewed)", ReportBuilder.cell(row, it)) }
        assertEquals(setOf(first.session.record.runId, second.session.record.runId), row.contributors[Category.CONTROLS]!!.map { it.runId }.toSet())
        // A pass that was itself carried isn't remembered as a pass of that run.
        assertTrue("transfer" !in second.summary!!.passed)
    }

    @Test fun aChangedTestOrOtherFirmwareCarriesNothing() {
        val clock = Support.Clock()
        val target = Support.PhysicalFake(SimulatedPrinter(SimulatedPrinter.Preset.PAXX_U1, clock::now))
        val first = run(target, clock, SafetyLevel.REVERSIBLE_FILES)
        val suite = Support.suite("paxx-u1")
        val snap = TargetCheck.inspect(target)
        val changed = Suite.parse(JSONObject(suite.source).apply {
            val tests = getJSONArray("tests"); (0 until tests.length()).map { tests.getJSONObject(it) }.first { it.getString("id") == "transfer" }.put("title", "Changed")
        }.toString())
        val carry = CarryOver.eligible(listOfNotNull(first.summary), changed, snap, "printer-1", "0.1.0")
        assertTrue("identity" in carry); assertNull(carry["transfer"])
        val otherFirmware = first.summary!!.copy(firmwareVersion = "9.9.9")
        assertTrue(CarryOver.eligible(listOf(otherFirmware), suite, snap, "printer-1", "0.1.0").isEmpty())
        assertTrue(CarryOver.eligible(listOfNotNull(first.summary), suite, snap, "another-printer", "0.1.0").isEmpty())
        assertTrue(CarryOver.eligible(listOfNotNull(first.summary), suite, snap, "printer-1", "0.2.0").isEmpty())
        assertEquals(first.summary, CarryOver.RunSummary.fromJson(JSONObject(first.summary.toJson().toString())))
    }

    @Test fun simulatedRunsCarryNothing() {
        val clock = Support.Clock()
        val sim = SimulatedPrinter(SimulatedPrinter.Preset.PAXX_U1, clock::now)
        assertNull(run(sim, clock, SafetyLevel.READ_ONLY).summary)
    }
}

package com.nozzleitall.testgrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class RunnerTest {
    /** Records every request that could change the printer; can die mid-request like a killed app. */
    class Counting(val sim: SimulatedPrinter, var dieOn: ((ControlAction) -> Boolean)? = null) : TestTarget by sim {
        class ProcessDeath : Error("process killed")
        val performed = mutableListOf<ControlAction>()
        val uploaded = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        override fun perform(action: ControlAction): CommandOutcome {
            performed += action
            val r = sim.perform(action)
            if (dieOn?.invoke(action) == true) throw ProcessDeath()
            return r
        }
        override fun upload(file: File, requestedName: String): TransferOutcome { uploaded += requestedName; return sim.upload(file, requestedName) }
        override fun delete(remotePath: String): TransferOutcome { deleted += remotePath; return sim.delete(remotePath) }
    }

    private fun result(s: RunSession, test: String) = s.record.test(test)!!.result
    private fun state(s: RunSession, test: String) = s.record.test(test)!!.state

    @Test fun fullSimulatedPaxxRunCompletesAndEveryTestPasses() {
        val clock = Support.Clock()
        val st = Support.start("paxx-u1", SimulatedPrinter(SimulatedPrinter.Preset.PAXX_U1, clock::now), clock)
        assertEquals(Pending.Finished, Support.drive(st.session))
        st.session.record.tests.forEach { assertEquals(it.testId + " " + it.reason, ResultState.PASS, it.result) }
        assertTrue(st.session.record.completedAt != null)
    }

    @Test fun proceedNeverSendsAConsequentialRequestOnItsOwn() {
        val clock = Support.Clock()
        val t = Counting(SimulatedPrinter(SimulatedPrinter.Preset.PAXX_U1, clock::now))
        val st = Support.start("paxx-u1", t, clock)
        var p = st.session.proceed()
        var confirmations = 0
        while (p !is Pending.Finished) {
            p = when (p) {
                is Pending.Preconditions -> st.session.answerPreconditions(p.test.preconditions.associate { it.id to true })
                is Pending.Observation -> st.session.observe(p.step.id, ScriptedOperator.defaultAnswer(p.step))
                is Pending.Attachment -> st.session.attach(p.step.id, ScriptedOperator.placeholderPng(), "image/png")
                // Decline every consequential step: nothing may reach the printer.
                is Pending.Confirmation -> { confirmations++; assertTrue(p.target.contains("192.168.50.23")); assertTrue(p.action.isNotBlank()); st.session.decline(p.step.id, "test") }
                else -> fail("unexpected $p").let { p }
            }
        }
        assertTrue(confirmations > 5)
        assertTrue(t.performed.isEmpty()); assertTrue(t.uploaded.isEmpty()); assertTrue(t.deleted.isEmpty())
        assertEquals(ResultState.PASS, result(st.session, "slice-single"))
        // Declining the first consequential step: nothing in those tests passed, so they are SKIPPED, never PASS.
        listOf("transfer", "controls-idle", "print-single", "print-controls").forEach { assertEquals(it, ResultState.SKIPPED, result(st.session, it)) }
        assertTrue(st.session.record.interventions.count { it.kind == "declined" } == confirmations)
    }

    @Test fun transitionsFollowTheStateMachine() {
        val clock = Support.Clock()
        val st = Support.start("paxx-u1", SimulatedPrinter(SimulatedPrinter.Preset.PAXX_U1, clock::now), clock)
        val s = st.session
        assertEquals(RunState.NOT_STARTED, state(s, "controls-idle"))
        var p = s.proceed()
        while (!(p is Pending.Preconditions && p.test.id == "controls-idle")) p = when (p) {
            is Pending.Preconditions -> s.answerPreconditions(p.test.preconditions.associate { it.id to true })
            is Pending.Confirmation -> s.approve(p.step.id)
            is Pending.Observation -> s.observe(p.step.id, ScriptedOperator.defaultAnswer(p.step))
            is Pending.Attachment -> s.attach(p.step.id, ScriptedOperator.placeholderPng(), "image/png")
            else -> fail("unexpected $p").let { p }
        }
        assertEquals(RunState.PRECONDITIONS, state(s, "controls-idle"))
        p = s.answerPreconditions(mapOf("idle" to true, "present" to true, "clear" to true))
        assertTrue(p is Pending.Confirmation); p as Pending.Confirmation
        assertEquals("heat-nozzle", p.step.id)
        assertEquals("Heat the active nozzle to 60 °C", p.action)
        assertEquals(RunState.AWAITING_CONFIRMATION, state(s, "controls-idle"))
        p = s.approve("heat-nozzle")
        assertTrue(p is Pending.Observation)
        assertEquals(RunState.AWAITING_OBSERVATION, state(s, "controls-idle"))
        try { s.approve("heat-bed"); fail("must refuse a step that is not awaiting approval") } catch (e: RunRefused) {}
        p = s.observe("screen-target", "no")
        assertEquals(StepStatus.FAILED, s.record.test("controls-idle")!!.step("screen-target")!!.status)
        // An operator observation failing does not stop the remaining steps; the test ends FAIL.
        p = Support.drive(s)
        assertEquals(RunState.FAILED, state(s, "controls-idle"))
        assertEquals(ResultState.FAIL, result(s, "controls-idle"))
    }

    @Test fun testsAboveTheChosenSafetyLevelAreSkippedNotPassed() {
        val clock = Support.Clock()
        val t = Counting(SimulatedPrinter(SimulatedPrinter.Preset.PAXX_U1, clock::now))
        val st = Support.start("paxx-u1", t, clock, level = SafetyLevel.READ_ONLY)
        Support.drive(st.session)
        assertEquals(ResultState.PASS, result(st.session, "telemetry"))
        listOf("transfer", "controls-idle", "print-single", "print-controls", "multi-print").forEach { assertEquals(it, ResultState.SKIPPED, result(st.session, it)) }
        assertTrue(t.performed.isEmpty() && t.uploaded.isEmpty())
    }

    @Test fun anUnmetPreconditionBlocksTheTestAndSendsNothing() {
        val clock = Support.Clock()
        val t = Counting(SimulatedPrinter(SimulatedPrinter.Preset.PAXX_U1, clock::now))
        val st = Support.start("paxx-u1", t, clock)
        var p = st.session.proceed()
        while (!(p is Pending.Preconditions && p.test.id == "controls-idle")) p = when (p) {
            is Pending.Preconditions -> st.session.answerPreconditions(p.test.preconditions.associate { it.id to true })
            is Pending.Confirmation -> st.session.approve(p.step.id)
            is Pending.Observation -> st.session.observe(p.step.id, ScriptedOperator.defaultAnswer(p.step))
            else -> fail("unexpected $p").let { p }
        }
        val before = t.performed.size
        st.session.answerPreconditions(mapOf("idle" to true, "present" to true, "clear" to false))
        assertEquals(ResultState.BLOCKED, result(st.session, "controls-idle"))
        assertEquals(before, t.performed.size)
        assertTrue(st.session.record.interventions.any { it.kind == "precondition" })
    }

    @Test fun controlsAreNotSentWhenThePrinterIsBusy() {
        val clock = Support.Clock()
        val sim = SimulatedPrinter(SimulatedPrinter.Preset.PAXX_U1, clock::now)
        val t = Counting(sim)
        val st = Support.start("paxx-u1", t, clock)
        var p = st.session.proceed()
        while (!(p is Pending.Confirmation && p.step.id == "heat-nozzle")) p = when (p) {
            is Pending.Preconditions -> st.session.answerPreconditions(p.test.preconditions.associate { it.id to true })
            is Pending.Confirmation -> st.session.approve(p.step.id)
            is Pending.Observation -> st.session.observe(p.step.id, ScriptedOperator.defaultAnswer(p.step))
            else -> fail("unexpected $p").let { p }
        }
        // Someone starts a print from the printer's screen between review and approval.
        sim.perform(ControlAction.StartPrint("benchy.gcode"))
        st.session.approve("heat-nozzle")
        val step = st.session.record.test("controls-idle")!!.step("heat-nozzle")!!
        assertEquals(StepStatus.BLOCKED, step.status)
        assertTrue(step.detail.contains("Nothing was sent"))
        assertTrue(t.performed.none { it is ControlAction.SetTemperature })
    }

    @Test fun anUnknownOutcomeIsNeverRetriedAndBlocksEverythingUntilReviewed() {
        val clock = Support.Clock()
        val sim = SimulatedPrinter(SimulatedPrinter.Preset.PAXX_U1, clock::now, mutableSetOf("lost_ack:set_temperature"))
        val t = Counting(sim)
        val st = Support.start("paxx-u1", t, clock)
        val s = st.session
        var p = s.proceed()
        while (p !is Pending.UnknownReview) p = when (p) {
            is Pending.Preconditions -> s.answerPreconditions(p.test.preconditions.associate { it.id to true })
            is Pending.Confirmation -> s.approve(p.step.id)
            is Pending.Observation -> s.observe(p.step.id, ScriptedOperator.defaultAnswer(p.step))
            is Pending.Attachment -> s.attach(p.step.id, ScriptedOperator.placeholderPng(), "image/png")
            else -> fail("unexpected $p").let { p }
        }
        assertEquals("heat-nozzle", p.marker.stepId)
        assertEquals(1, t.performed.count { it == ControlAction.SetTemperature("nozzle", 60) })
        // Every further action is refused until review.
        listOf({ s.approve("heat-nozzle") }, { s.answerPreconditions(emptyMap()) }, { s.observe("screen-target", "yes") }, { s.finish() }).forEach {
            try { it(); fail("must refuse while an outcome is unknown") } catch (e: RunRefused) {}
        }
        assertTrue(s.proceed() is Pending.UnknownReview)
        assertFalse(s.canRetry("controls-idle", "heat-nozzle"))
        try { s.reviewUnknown(""); fail() } catch (e: RunRefused) {}
        val reading = s.reviewUnknown("Printer screen shows the nozzle target at 60 °C.")
        assertTrue(reading.observedAtMillis >= s.record.interventions.last().at - 1000)
        assertNull(s.record.unknown)
        assertEquals(RunState.OUTCOME_UNKNOWN, state(s, "controls-idle"))
        assertEquals(ResultState.UNVERIFIED, result(s, "controls-idle"))
        // Cleanup is offered next (turning the heaters off is not a retry of the unknown command).
        p = s.proceed()
        assertTrue(p is Pending.Confirmation && p.phase == "cleanup" && p.step.id == "cleanup-nozzle")
        Support.drive(s)
        assertEquals(1, t.performed.count { it == ControlAction.SetTemperature("nozzle", 60) })
        assertEquals(StepStatus.SKIPPED, s.record.test("controls-idle")!!.step("heat-bed")!!.status)
    }

    @Test fun aStaleReadingCannotResolveAnUnknownOutcome() {
        val clock = Support.Clock()
        // The printer's own clock lags: its readings predate the unknown outcome.
        val sim = SimulatedPrinter(SimulatedPrinter.Preset.PAXX_U1, { 1L }, mutableSetOf("lost_ack:set_temperature"))
        val st = Support.start("paxx-u1", sim, clock)
        var p = st.session.proceed()
        while (p !is Pending.UnknownReview) p = when (p) {
            is Pending.Preconditions -> st.session.answerPreconditions(p.test.preconditions.associate { it.id to true })
            is Pending.Confirmation -> st.session.approve(p.step.id)
            is Pending.Observation -> st.session.observe(p.step.id, ScriptedOperator.defaultAnswer(p.step))
            else -> fail("unexpected $p").let { p }
        }
        try { st.session.reviewUnknown("looked at it"); fail("stale reading must not resolve") } catch (e: RunRefused) { assertTrue(e.message!!.contains("older")) }
        assertTrue(st.session.record.unknown != null)
    }

    @Test fun aRestartMidCommandMarksItUnknownAndNeverSendsItAgain() {
        val clock = Support.Clock()
        val sim = SimulatedPrinter(SimulatedPrinter.Preset.PAXX_U1, clock::now)
        val t = Counting(sim) { it == ControlAction.Home }
        val work = Support.tmp()
        val st = Support.start("paxx-u1", t, clock, work = work)
        try { Support.drive(st.session); fail("the simulated process death should propagate") } catch (e: Counting.ProcessDeath) {}
        assertEquals(1, t.performed.count { it == ControlAction.Home })
        // The journal on disk shows the command in flight.
        val onDisk = st.journal.load()!!
        assertEquals(StepStatus.DISPATCHING, onDisk.test("controls-idle")!!.step("home")!!.status)
        assertTrue(onDisk.test("controls-idle")!!.step("home")!!.confirmation != null)

        // "App restarts": a new session from the journal.
        val t2 = Counting(sim)
        val resumed = RunSession.resume(Support.suite("paxx-u1"), t2, st.slicer, Support.env(work, clock), RunJournal(File(work, "run")))!!
        val rec = resumed.record.test("controls-idle")!!
        assertEquals(StepStatus.OUTCOME_UNKNOWN, rec.step("home")!!.status)
        assertEquals(RunState.OUTCOME_UNKNOWN, rec.state)
        assertEquals(1, resumed.record.restarts)
        assertTrue(resumed.proceed() is Pending.UnknownReview)
        resumed.reviewUnknown("Toolhead is at home position.")
        Support.drive(resumed)
        assertTrue(t2.performed.none { it == ControlAction.Home })
        assertEquals(ResultState.UNVERIFIED, resumed.record.test("controls-idle")!!.result)
        // Tests after it still ran.
        assertEquals(ResultState.PASS, resumed.record.test("print-single")!!.result)
    }

    @Test fun approvalsDoNotSurviveARestart() {
        val clock = Support.Clock()
        val sim = SimulatedPrinter(SimulatedPrinter.Preset.PAXX_U1, clock::now)
        val work = Support.tmp()
        val st = Support.start("paxx-u1", sim, clock, work = work)
        var p = st.session.proceed()
        while (!(p is Pending.Confirmation && p.step.id == "upload")) p = when (p) {
            is Pending.Preconditions -> st.session.answerPreconditions(p.test.preconditions.associate { it.id to true })
            is Pending.Observation -> st.session.observe(p.step.id, ScriptedOperator.defaultAnswer(p.step))
            else -> fail("unexpected $p").let { p }
        }
        assertEquals(StepStatus.AWAITING_CONFIRMATION, st.journal.load()!!.test("transfer")!!.step("upload")!!.status)
        val resumed = RunSession.resume(Support.suite("paxx-u1"), sim, st.slicer, Support.env(work, clock), RunJournal(File(work, "run")))!!
        val step = resumed.record.test("transfer")!!.step("upload")!!
        assertEquals(StepStatus.PENDING, step.status)
        assertNull(step.confirmation)
        // It must be presented for approval again.
        val again = resumed.proceed()
        assertTrue(again is Pending.Confirmation && again.step.id == "upload")
    }

    @Test fun capabilityAndFirmwareMatchingRefusesTheWrongPrinter() {
        val clock = Support.Clock()
        val paxx = TargetCheck.inspect(SimulatedPrinter(SimulatedPrinter.Preset.PAXX_U1, clock::now))
        val stock = TargetCheck.inspect(SimulatedPrinter(SimulatedPrinter.Preset.STOCK_U1, clock::now))
        val cosmos = TargetCheck.inspect(SimulatedPrinter(SimulatedPrinter.Preset.COSMOS_CC, clock::now))
        assertEquals(FirmwareFamilies.PAXX, paxx.classified.family)
        assertEquals(FirmwareFamilies.SNAPMAKER_STOCK, stock.classified.family)
        assertEquals(FirmwareFamilies.COSMOS, cosmos.classified.family)
        assertTrue(TargetCheck.mismatches(Support.suite("paxx-u1"), paxx, "0.1.0").isEmpty())
        assertTrue(TargetCheck.mismatches(Support.suite("paxx-u1"), stock, "0.1.0").any { it.contains("graded separately") })
        assertTrue(TargetCheck.mismatches(Support.suite("snapmaker-u1-stock"), paxx, "0.1.0").isNotEmpty())
        assertTrue(TargetCheck.mismatches(Support.suite("cosmos-centauri-carbon"), paxx, "0.1.0").isNotEmpty())
        assertTrue(TargetCheck.mismatches(Support.suite("elegoo-centauri-carbon-stock"), cosmos, "0.1.0").isNotEmpty())
        assertTrue(TargetCheck.mismatches(Support.suite("generic-klipper"), cosmos, "0.1.0").isNotEmpty())
        assertTrue(TargetCheck.mismatches(Support.suite("paxx-u1"), paxx, "0.0.9").any { it.contains("needs Nozzle It All 0.1.0") })
        // Missing capability.
        val noCaps = paxx.copy(capabilities = setOf("status"))
        assertTrue(TargetCheck.mismatches(Support.suite("paxx-u1"), noCaps, "0.1.0").any { it.contains("firmware_identity") })
        try { RunSession.start(Support.suite("cosmos-centauri-carbon"), SimulatedPrinter(SimulatedPrinter.Preset.PAXX_U1, clock::now), paxx, null, Support.env(Support.tmp(), clock), null, SafetyLevel.SOFTWARE); fail() } catch (e: RunRefused) {}
    }

    @Test fun multiMaterialTestsAreSkippedWithoutConfirmedHardware() {
        val clock = Support.Clock()
        val st = Support.start("cosmos-centauri-carbon", SimulatedPrinter(SimulatedPrinter.Preset.COSMOS_CC, clock::now), clock)
        Support.drive(st.session)
        listOf("multi-lanes", "multi-slice", "multi-print").forEach {
            assertEquals(it, ResultState.SKIPPED, result(st.session, it))
            assertTrue(st.session.record.test(it)!!.reason.contains("canvas"))
        }
        assertEquals(ResultState.PASS, result(st.session, "print-single"))
        val withCanvas = Support.start("cosmos-centauri-carbon", SimulatedPrinter(SimulatedPrinter.Preset.COSMOS_CC_CANVAS, clock::now), clock)
        Support.drive(withCanvas.session)
        listOf("multi-lanes", "multi-slice", "multi-print").forEach { assertEquals(it, ResultState.PASS, result(withCanvas.session, it)) }
    }

    @Test fun cosmosSuiteRefusesStockProfilesAndLegacyFirmware() {
        val clock = Support.Clock()
        val st = Support.start("cosmos-centauri-carbon", SimulatedPrinter(SimulatedPrinter.Preset.COSMOS_CC, clock::now), clock)
        Support.drive(st.session)
        assertEquals(ResultState.PASS, result(st.session, "stock-profile-refused"))
        assertTrue(st.session.record.test("stock-profile-refused")!!.step("refuse")!!.detail.contains("M729"))
        assertEquals(ResultState.PASS, result(st.session, "upload-guard"))
        assertEquals(ResultState.PASS, result(st.session, "profile-match"))

        val legacy = Support.start("cosmos-centauri-carbon", SimulatedPrinter(SimulatedPrinter.Preset.COSMOS_CC_LEGACY, clock::now), clock)
        Support.drive(legacy.session)
        assertEquals(ResultState.FAIL, result(legacy.session, "profile-match"))
        assertTrue(legacy.session.record.test("profile-match")!!.reason.contains("emergency stop"))
    }

    @Test fun aBrokenUploadGuardFails() {
        val clock = Support.Clock()
        val st = Support.start("cosmos-centauri-carbon", SimulatedPrinter(SimulatedPrinter.Preset.COSMOS_CC, clock::now, mutableSetOf("guard_disabled")), clock)
        Support.drive(st.session)
        assertEquals(ResultState.FAIL, result(st.session, "upload-guard"))
        assertTrue(st.session.record.test("upload-guard")!!.reason.contains("M729"))
    }

    @Test fun stockElegooGcodeFailsTheCosmosScan() {
        val clock = Support.Clock()
        val slicer = SimulatedSlicer(Support.profiles(), Support.tmp())
        val stock = slicer.profile("elegoo_centauri_carbon_canvas")
        val cosmos = slicer.profile("elegoo_centauri_carbon_cosmos")
        val parts = Support.env(Support.tmp(), clock).models.materialize("nozzle-acceptance-v1", Support.tmp())
        val bad = (slicer.slice(SliceRequest("nozzle-acceptance-v1", parts, stock, "bad")) as SliceResult.Success).gcode
        val good = (slicer.slice(SliceRequest("nozzle-acceptance-v1", parts, cosmos, "good")) as SliceResult.Success).gcode
        val check = org.json.JSONObject().put("check", "no_stock_elegoo_commands")
        assertTrue(GcodeScan.evaluate(check, GcodeScan.scan(bad), stock)!!.contains("M729"))
        assertNull(GcodeScan.evaluate(check, GcodeScan.scan(good), cosmos))
        // The legacy/wrong start G-code is caught by the required-macro check too.
        assertTrue(GcodeScan.evaluate(org.json.JSONObject().put("check", "requires_macro").put("macro", "PRINT_START"), GcodeScan.scan(bad), stock) != null)
        assertNull(GcodeScan.evaluate(org.json.JSONObject().put("check", "centered").put("toleranceMm", 5), GcodeScan.scan(good), cosmos))
    }

    @Test fun aFailedDependencyBlocksLaterCategoriesInsteadOfPassingOrFailingThem() {
        val clock = Support.Clock()
        // A slicer that fails: file transfer and printing must not be graded from it either way.
        val sim = SimulatedPrinter(SimulatedPrinter.Preset.PAXX_U1, clock::now)
        val work = Support.tmp()
        val env = Support.env(work, clock)
        val broken = object : TestSlicer by SimulatedSlicer(Support.profiles(), File(work, "s")) { override fun slice(request: SliceRequest) = SliceResult.Failed("engine crashed") }
        val s = RunSession.start(Support.suite("paxx-u1"), sim, TargetCheck.inspect(sim), broken, env, RunJournal(File(work, "run")), SafetyLevel.PHYSICAL_PRINT)
        Support.drive(s)
        assertEquals(ResultState.FAIL, result(s, "slice-single"))
        listOf("transfer", "print-single", "print-controls").forEach { assertEquals(it, ResultState.BLOCKED, result(s, it)) }
        assertEquals(ResultState.PASS, result(s, "telemetry"))
        assertEquals(ResultState.PASS, result(s, "controls-idle"))
    }

    @Test fun interruptingStopsTheTestAndOffersOnlyItsCleanup() {
        val clock = Support.Clock()
        val t = Counting(SimulatedPrinter(SimulatedPrinter.Preset.PAXX_U1, clock::now))
        val st = Support.start("paxx-u1", t, clock)
        val s = st.session
        var p = s.proceed()
        while (!(p is Pending.Observation && p.step.id == "screen-target")) p = when (p) {
            is Pending.Preconditions -> s.answerPreconditions(p.test.preconditions.associate { it.id to true })
            is Pending.Confirmation -> s.approve(p.step.id)
            is Pending.Observation -> s.observe(p.step.id, ScriptedOperator.defaultAnswer(p.step))
            else -> fail("unexpected $p").let { p }
        }
        s.interrupt("Operator saw smoke.")
        p = s.proceed()
        assertEquals(RunState.INTERRUPTED, state(s, "controls-idle"))
        assertEquals(ResultState.UNVERIFIED, result(s, "controls-idle"))
        assertTrue(p is Pending.Confirmation && p.phase == "cleanup")
        assertTrue(t.performed.none { it == ControlAction.Home })
    }

    @Test fun finishingEarlyRecordsUnreachedTestsAsSkippedNeverPassed() {
        val clock = Support.Clock()
        val st = Support.start("paxx-u1", SimulatedPrinter(SimulatedPrinter.Preset.PAXX_U1, clock::now), clock)
        var p = st.session.proceed()
        while (!(p is Pending.Preconditions && p.test.id == "transfer")) p = when (p) {
            is Pending.Preconditions -> st.session.answerPreconditions(p.test.preconditions.associate { it.id to true })
            is Pending.Observation -> st.session.observe(p.step.id, ScriptedOperator.defaultAnswer(p.step))
            else -> fail("unexpected $p").let { p }
        }
        st.session.finish()
        listOf("transfer", "controls-idle", "print-single").forEach { assertEquals(ResultState.SKIPPED, result(st.session, it)) }
        assertTrue(st.session.pending() is Pending.Finished)
    }

    @Test fun retryIsOnlyForReadOnlyStepsInTestsThatAllowIt() {
        val clock = Support.Clock()
        val sim = SimulatedPrinter(SimulatedPrinter.Preset.PAXX_U1, clock::now)
        var fail = true
        val flaky = object : TestTarget by sim { override fun identity(): LiveIdentity { if (fail) throw java.io.IOException("timeout"); return sim.identity() } }
        val env = Support.env(Support.tmp(), clock)
        val s = RunSession.start(Support.suite("paxx-u1"), flaky, TargetCheck.inspect(sim), SimulatedSlicer(Support.profiles(), Support.tmp()), env, null, SafetyLevel.READ_ONLY)
        val p = s.proceed()
        // The identity test failed on a read; the run stopped at the next test's preconditions.
        assertTrue(p is Pending.Preconditions && p.test.id == "telemetry")
        assertEquals(StepStatus.FAILED, s.record.test("identity")!!.step("identity")!!.status)
        assertTrue(s.canRetry("identity", "identity"))
        fail = false
        s.retry("identity", "identity")
        assertEquals(StepStatus.PASSED, s.record.test("identity")!!.step("identity")!!.status)
        assertEquals(ResultState.PASS, s.record.test("identity")!!.result)
        assertTrue(s.record.interventions.any { it.kind == "retry" })
        // Tests that change the printer never allow it.
        assertFalse(s.canRetry("controls-idle", "heat-nozzle"))
        assertFalse(s.canRetry("controls-idle", "nozzle-reached"))
    }

    @Test fun anObservationShowsWhatNozzleReadsAndKeepsItWithTheAnswer() {
        val clock = Support.Clock()
        val st = Support.start("paxx-u1", SimulatedPrinter(SimulatedPrinter.Preset.PAXX_U1, clock::now), clock, level = SafetyLevel.READ_ONLY)
        val s = st.session
        var p = s.proceed()
        while (!(p is Pending.Observation && p.step.id == "matches")) p = when (p) {
            is Pending.Preconditions -> s.answerPreconditions(p.test.preconditions.associate { it.id to true })
            else -> fail("unexpected $p").let { p }
        }
        val step = s.record.test("telemetry")!!.step("matches")!!
        val first = step.data.getJSONObject("shown")
        assertEquals(24.0, first.getDouble("nozzle"), 0.01)
        assertEquals("standby", first.getString("state"))
        s.refreshShown("matches")
        assertTrue(step.data.getJSONObject("shown").getLong("observedAt") > first.getLong("observedAt"))
        s.observe("matches", "yes")
        assertEquals(24.0, step.data.getJSONObject("shown").getDouble("nozzle"), 0.01)
        assertEquals("yes", step.data.getString("response"))
    }
}

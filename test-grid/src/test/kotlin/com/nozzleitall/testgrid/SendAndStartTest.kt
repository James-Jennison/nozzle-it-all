package com.nozzleitall.testgrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Printers that take a file only together with a print start (Bambu LAN, PrusaLink, OctoPrint, Elegoo stock): every
 * fixture suite runs end to end on a simulated printer of that kind, sliced with the printer's own profile.
 */
class SendAndStartTest {
    private fun full(suite: String, preset: SimulatedPrinter.Preset, faults: MutableSet<String> = mutableSetOf()): Support.Started {
        val clock = Support.Clock()
        val st = Support.start(suite, SimulatedPrinter(preset, clock::now, faults), clock)
        assertEquals(Pending.Finished, Support.drive(st.session))
        return st
    }

    @Test fun everySendAndStartSuiteRunsEndToEnd() {
        listOf("bambu-lan" to SimulatedPrinter.Preset.BAMBU_P1S, "prusalink" to SimulatedPrinter.Preset.PRUSA_MK4S,
            "octoprint" to SimulatedPrinter.Preset.OCTOPRINT, "elegoo-centauri-carbon-stock" to SimulatedPrinter.Preset.ELEGOO_CC_STOCK).forEach { (suite, preset) ->
            val st = full(suite, preset)
            // Multi-material tests run only where the saved profile has more than one slot.
            st.session.record.tests.forEach { t ->
                val multi = Support.suite(suite).test(t.testId)!!.scope == MaterialScope.MULTI
                assertTrue("$suite/${t.testId}: ${t.result} ${t.reason}", t.result == ResultState.PASS || (multi && preset.toolSlots == 1 && t.result == ResultState.SKIPPED))
            }
            // Sliced with the printer's own saved profile, not one the suite names.
            val profile = st.session.record.test("slice-single")!!.step("slice")!!.data.getJSONObject("profile").getString("id")
            assertEquals(suite, SuiteProfiles.forPrinter(preset.slicingModel.name), profile)
            val send = st.session.record.test("print-single")!!.step("send")!!
            assertTrue(send.detail, send.detail.startsWith("The printer accepted nozzle-testgrid-$suite-slice-single"))
            assertTrue(send.confirmation!!.action.contains("start printing"))
        }
        // The Elegoo run with CANVAS also printed its two-colour test; a Bambu has no multi-material test yet.
        assertEquals(ResultState.PASS, full("elegoo-centauri-carbon-stock", SimulatedPrinter.Preset.ELEGOO_CC_STOCK).session.record.test("multi-print")!!.result)
        assertTrue(Support.suite("bambu-lan").tests.none { it.scope == MaterialScope.MULTI })
    }

    @Test fun aLostReplyIsConfirmedFromStateOrStaysUnknown() {
        val confirmed = full("prusalink", SimulatedPrinter.Preset.PRUSA_MK4S, mutableSetOf("lost_ack:send_and_start"))
        val step = confirmed.session.record.test("transfer")!!.step("send")!!
        assertEquals(StepStatus.PASSED, step.status)
        assertTrue(step.detail, step.detail.contains("reply was lost") && step.detail.contains("confirms"))

        val clock = Support.Clock()
        val st = Support.start("octoprint", SimulatedPrinter(SimulatedPrinter.Preset.OCTOPRINT, clock::now, mutableSetOf("lost_ack_noeffect:send_and_start")), clock)
        var reviewed = 0
        Support.drive(st.session) { reviewed++; it.reviewUnknown("checked") }
        assertEquals(StepStatus.OUTCOME_UNKNOWN, st.session.record.test("transfer")!!.step("send")!!.status)
        assertTrue(reviewed >= 1)
    }

    @Test fun heatersHomingAndMovesAreNotOfferedAndAPrinterWithoutAProfileIsBlocked() {
        listOf("bambu-lan", "prusalink", "octoprint", "elegoo-centauri-carbon-stock", "opencentauri-patched").forEach { id ->
            val kinds = Support.suite(id).tests.flatMap { it.steps + it.cleanup }.mapNotNull { it.kind }.toSet()
            assertTrue(id, kinds.none { it in setOf(StepKind.SET_TEMPERATURE, StepKind.HOME, StepKind.JOG, StepKind.UPLOAD, StepKind.START_PRINT) })
        }
        val clock = Support.Clock()
        val sim = SimulatedPrinter(SimulatedPrinter.Preset.PRUSA_MK4S, clock::now)
        val noProfile = object : TestTarget by sim { override val description = sim.description.copy(slicingModel = null) }
        val st = Support.start("prusalink", noProfile, clock, level = SafetyLevel.SOFTWARE)
        Support.drive(st.session)
        val slice = st.session.record.test("slice-single")!!.step("slice")!!
        assertEquals(StepStatus.BLOCKED, slice.status)
        assertTrue(slice.detail, slice.detail.contains("no slicing profile chosen"))
    }

    @Test fun multiMaterialFollowsTheSavedProfilesSlotsNotTheFamily() {
        // The owner's point: a Prusa can be single-tool (MK4S) or multi-material (XL 5T, an MMU), so the family alone can't decide.
        val xl = full("prusalink", SimulatedPrinter.Preset.PRUSA_XL_5T)
        xl.session.record.tests.forEach { assertEquals("${it.testId}: ${it.reason}", ResultState.PASS, it.result) }
        assertTrue(xl.session.record.target.getJSONObject("hardware").getBoolean("multi_tool"))
        val mk4s = full("prusalink", SimulatedPrinter.Preset.PRUSA_MK4S)
        assertEquals(ResultState.SKIPPED, mk4s.session.record.test("multi-print")!!.result)
        assertTrue(mk4s.session.record.test("multi-print")!!.reason.contains("multi_tool"))
    }

    @Test fun whereTheAppDoesntStartPrintsYetThoseTestsAreBlockedAndNothingIsSent() {
        // Creality (CFS) and Flashforge (IFS): the app uploads but doesn't start prints until START_VERIFIED.
        listOf("creality-lan" to SimulatedPrinter.Preset.CREALITY_K2, "flashforge-lan" to SimulatedPrinter.Preset.FLASHFORGE_AD5X).forEach { (suite, preset) ->
            val clock = Support.Clock()
            val sim = SimulatedPrinter(preset, clock::now)
            var sent = 0
            val target = object : TestTarget by sim { override fun sendAndStart(file: java.io.File, requestedName: String): TransferOutcome { sent++; return sim.sendAndStart(file, requestedName) } }
            val st = Support.start(suite, target, clock)
            Support.drive(st.session)
            val r = st.session.record
            listOf("telemetry", "slice-single", "multi-lanes", "multi-slice").forEach { assertEquals("$suite/$it: ${r.test(it)!!.reason}", ResultState.PASS, r.test(it)!!.result) }
            listOf("transfer", "print-single", "print-controls", "multi-print").forEach {
                assertEquals("$suite/$it", ResultState.BLOCKED, r.test(it)!!.result)
                assertTrue(r.test(it)!!.reason, r.test(it)!!.reason.contains("can't do this on this printer yet"))
            }
            assertEquals(suite, 0, sent)
        }
    }

    @Test fun printersPortedFromUpstreamHostsRunWhatTheAppCanDoAndBlockTheRest() {
        // Duet, UltiMaker, Repetier-Server and the legacy Flashforge console: start gated off; Duet, Repetier and the
        // legacy console don't read printer state either, so their status test is blocked rather than failed.
        data class Case(val suite: String, val preset: SimulatedPrinter.Preset, val readsState: Boolean)
        listOf(Case("duet-rrf", SimulatedPrinter.Preset.DUET, false), Case("ultimaker-lan", SimulatedPrinter.Preset.ULTIMAKER_S5, true),
            Case("repetier-server", SimulatedPrinter.Preset.REPETIER, false), Case("flashforge-legacy", SimulatedPrinter.Preset.FLASHFORGE_ADVENTURER_4, false)).forEach { c ->
            val clock = Support.Clock()
            val sim = SimulatedPrinter(c.preset, clock::now)
            var sent = 0
            val target = object : TestTarget by sim { override fun sendAndStart(file: java.io.File, requestedName: String): TransferOutcome { sent++; return sim.sendAndStart(file, requestedName) } }
            val st = Support.start(c.suite, target, clock)
            Support.drive(st.session)
            val r = st.session.record
            assertEquals(c.suite, c.suite, r.target.getJSONObject("firmware").getString("family"))
            assertEquals("${c.suite}/slice-single: ${r.test("slice-single")!!.reason}", ResultState.PASS, r.test("slice-single")!!.result)
            assertEquals("${c.suite}/telemetry", if (c.readsState) ResultState.PASS else ResultState.BLOCKED, r.test("telemetry")!!.result)
            listOf("transfer", "print-single", "print-controls").forEach { assertEquals("${c.suite}/$it", ResultState.BLOCKED, r.test(it)!!.result) }
            assertEquals(c.suite, 0, sent)
        }
        // The UltiMaker S5's profile has two nozzles, so its multi-material tests apply (and block at printing).
        val clock = Support.Clock()
        val st = Support.start("ultimaker-lan", SimulatedPrinter(SimulatedPrinter.Preset.ULTIMAKER_S5, clock::now), clock)
        Support.drive(st.session)
        assertEquals(ResultState.PASS, st.session.record.test("multi-slice")!!.result)
        assertEquals(ResultState.BLOCKED, st.session.record.test("multi-print")!!.result)
    }
}

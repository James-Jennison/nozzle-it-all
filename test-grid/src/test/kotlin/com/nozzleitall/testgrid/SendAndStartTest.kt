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
}

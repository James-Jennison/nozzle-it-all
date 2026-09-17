package net.jamesjennison.klippercompanion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpeedFlowControlsTest {
    private val ready = SpeedFlowStatus(true, "complete", 1.0, 1.0, true)
    @Test fun exactDecimalEdgesForSpeedAndFlow() {
        assertEquals("M220 S200", SpeedFlowControls.prepare(SpeedFlowRequest("speed", "200.00"), ready).arguments["script"])
        assertEquals("M220 S10", SpeedFlowControls.prepare(SpeedFlowRequest("speed", "10"), ready).arguments["script"])
        assertEquals("M221 S150", SpeedFlowControls.prepare(SpeedFlowRequest("flow", "150"), ready).arguments["script"])
        assertEquals("M221 S50", SpeedFlowControls.prepare(SpeedFlowRequest("flow", "50"), ready).arguments["script"])
        assertThrows(IllegalArgumentException::class.java) { SpeedFlowControls.prepare(SpeedFlowRequest("speed", "200.000000000000001"), ready) }
        assertThrows(IllegalArgumentException::class.java) { SpeedFlowControls.prepare(SpeedFlowRequest("speed", "9.999999999999999"), ready) }
        assertThrows(IllegalArgumentException::class.java) { SpeedFlowControls.prepare(SpeedFlowRequest("flow", "150.000000000000001"), ready) }
        assertThrows(IllegalArgumentException::class.java) { SpeedFlowControls.prepare(SpeedFlowRequest("flow", "49.999999999999999"), ready) }
    }
    @Test fun invalidOrInjectedInputsRejected() {
        for (value in listOf("-1", "NaN", "1e2", "100;G28", "100\nG28", "0x20", "", "9".repeat(25)))
            assertThrows(IllegalArgumentException::class.java) { SpeedFlowControls.prepare(SpeedFlowRequest("speed", value), ready) }
    }
    @Test fun nonnumericOrMissingTelemetryRejected() {
        for (state in listOf(ready.copy(speedFactor = null), ready.copy(speedFactor = Double.NaN)))
            assertThrows(IllegalArgumentException::class.java) { SpeedFlowControls.prepare(SpeedFlowRequest("speed", "100"), state) }
        for (state in listOf(ready.copy(extrudeFactor = null), ready.copy(extrudeFactor = Double.NaN)))
            assertThrows(IllegalArgumentException::class.java) { SpeedFlowControls.prepare(SpeedFlowRequest("flow", "100"), state) }
    }
    @Test fun staleOrBackgroundReviewRejected() {
        for (state in listOf(ready.copy(ready = false), ready.copy(printState = "printing"), ready.copy(printState = "paused"), ready.copy(printState = "unknown")))
            assertThrows(IllegalArgumentException::class.java) { SpeedFlowControls.prepare(SpeedFlowRequest("speed", "100"), state) }
    }
    @Test fun tamperedMacroOverrideRejected() {
        assertThrows(IllegalArgumentException::class.java) { SpeedFlowControls.prepare(SpeedFlowRequest("speed", "100"), ready.copy(noMacroOverride = false)) }
    }
    @Test fun unsupportedKindRejected() {
        assertThrows(IllegalArgumentException::class.java) { SpeedFlowControls.prepare(SpeedFlowRequest("acceleration", "100"), ready) }
    }
    @Test fun parserDetectsOverriddenCommand() {
        val data = JSONObject("""{"status":{"webhooks":{"state":"ready"},"print_stats":{"state":"complete"},"gcode_move":{"speed_factor":1.0,"extrude_factor":1.0},"configfile":{"settings":{"gcode_macro M220":{}}}}}""")
        assertFalse(SpeedFlowControls.parse(data).noMacroOverride)
    }
    @Test fun parserReadsFactorsFromGcodeMove() {
        val data = JSONObject("""{"status":{"webhooks":{"state":"ready"},"print_stats":{"state":"standby"},"gcode_move":{"speed_factor":0.95,"extrude_factor":1.05},"configfile":{"settings":{}}}}""")
        val status = SpeedFlowControls.parse(data)
        assertEquals(0.95, status.speedFactor!!, 0.0001)
        assertEquals(1.05, status.extrudeFactor!!, 0.0001)
        assertTrue(status.noMacroOverride)
    }
}

package net.jamesjennison.klippercompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LedControlsTest {
    private fun listing(vararg objects: String) = JSONObject().put("objects", JSONArray(objects.toList()))
    private fun query(webhooks: String, led: String, white: Double?) = JSONObject().apply {
        put("status", JSONObject().apply {
            put("webhooks", JSONObject().put("state", webhooks))
            if (white != null) put("led $led", JSONObject().put("color_data", JSONArray().put(JSONArray().put(0).put(0).put(0).put(white))))
        })
    }

    @Test fun catalogFindsOnlyLedObjectsAndStripsThePrefix() {
        val found = LedControls.catalog(listing("toolhead", "led case", "led hotend", "fan_generic case_fan"))
        assertEquals(listOf("case", "hotend"), found)
    }

    @Test fun parseReadsWhiteChannelFromColorData() {
        val status = LedControls.parse("case", query("ready", "case", 0.75))
        assertTrue(status.ready); assertEquals(0.75, status.brightness!!, 0.0001)
        val missing = LedControls.parse("case", query("ready", "other", 0.5))
        assertNull(missing.brightness)
    }

    @Test fun prepareBuildsSetLedWithSyncZeroAndRejectsBadInput() {
        val status = LedStatus("case", true, 1.0)
        val command = LedControls.prepare(LedRequest("case", "42"), status)
        assertEquals("SET_LED LED=case WHITE=0.42 SYNC=0", command.arguments.getValue("script"))
        assertTrue(command.allowedStates.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { LedControls.prepare(LedRequest("case", "101"), status) }
        assertThrows(IllegalArgumentException::class.java) { LedControls.prepare(LedRequest("case", "-1"), status) }
        assertThrows(IllegalArgumentException::class.java) { LedControls.prepare(LedRequest("case", "1;G28"), status) }
        assertThrows(IllegalArgumentException::class.java) { LedControls.prepare(LedRequest("other", "50"), status) }
        assertThrows(IllegalArgumentException::class.java) { LedControls.prepare(LedRequest("case", "50"), status.copy(ready = false)) }
    }

    @Test fun prepareAllowsAnyPrintStateSincePrintStateIsNotChecked() {
        // Lights are cosmetic, unlike heater/fan/macro/speed-flow which require idle states.
        val status = LedStatus("case", true, 0.0)
        val command = LedControls.prepare(LedRequest("case", "0"), status)
        assertEquals("SET_LED LED=case WHITE=0 SYNC=0", command.arguments.getValue("script"))
    }
}

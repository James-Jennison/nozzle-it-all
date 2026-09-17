package net.jamesjennison.klippercompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MacroReviewTest {
    private fun query(webhooks: String, printState: String) = JSONObject(
        """{"status":{"webhooks":{"state":"$webhooks"},"print_stats":{"state":"$printState"}}}"""
    )
    private fun listing(vararg objects: String) = JSONObject().put("objects", JSONArray(objects.toList()))

    @Test fun parseStatusFindsMacroCaseInsensitivelyAmongOtherObjects() {
        val status = MacroTools.parseStatus("PURGE_LINE", query("ready", "standby"), listing("toolhead", "gcode_macro purge_line", "gcode_macro OTHER"))
        assertTrue(status.ready); assertEquals("standby", status.printState); assertTrue(status.available)
        val missing = MacroTools.parseStatus("PURGE_LINE", query("ready", "standby"), listing("toolhead", "gcode_macro OTHER"))
        assertFalse(missing.available)
    }

    @Test fun prepareRejectsWhenNotReadyOrPrintStateIsUnsupported() {
        val defs = MacroTools.definitions("TEMP=0,300,200")
        val request = MacroRequest("TEST", defs, mapOf("TEMP" to "210"))
        assertThrows(IllegalArgumentException::class.java) { MacroTools.prepare(request, MacroStatus(false, "standby", true)) }
        assertThrows(IllegalArgumentException::class.java) { MacroTools.prepare(request, MacroStatus(true, "printing", true)) }
        assertThrows(IllegalArgumentException::class.java) { MacroTools.prepare(request, MacroStatus(true, "standby", false)) }
        MacroTools.allowedStates.forEach { state ->
            val command = MacroTools.prepare(request, MacroStatus(true, state, true))
            assertEquals("TEST TEMP=210", command.arguments.getValue("script"))
        }
    }

    @Test fun preparedCommandCarriesTheSameMacroRequestBuiltLocally() {
        val defs = MacroTools.definitions("COUNT=1,10,2")
        val local = MacroTools.command("TEST", defs, mapOf("COUNT" to "5"))
        val reviewed = MacroTools.prepare(requireNotNull(local.macroRequest), MacroStatus(true, "complete", true))
        assertEquals(local, reviewed)
    }
}

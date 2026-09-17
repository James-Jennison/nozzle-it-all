package net.jamesjennison.klippercompanion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolheadsTest {
    @Test fun discoversAndOrdersExtrudersByIndex() {
        val listed = JSONObject("""{"objects":["webhooks","extruder2","extruder","heater_bed","extruder1","fan"]}""")
        assertEquals(listOf("extruder", "extruder1", "extruder2"), Toolheads.discover(listed))
    }
    @Test fun parsesTemperatureAndTargetPerToolhead() {
        val names = listOf("extruder", "extruder1")
        val result = JSONObject("""{"status":{"extruder":{"temperature":200.1,"target":200.0},"extruder1":{"temperature":25.0,"target":0.0}}}""")
        val readings = Toolheads.parse(names, result)
        assertEquals(2, readings.size)
        assertEquals(ToolheadTemperature("extruder", 200.1, 200.0), readings[0])
        assertEquals(ToolheadTemperature("extruder1", 25.0, 0.0), readings[1])
    }
    @Test fun missingReadingReportsUnknownNotZero() {
        val readings = Toolheads.parse(listOf("extruder3"), JSONObject("""{"status":{}}"""))
        assertNull(readings.single().temperature)
        assertNull(readings.single().target)
    }
    @Test fun rejectsInvalidNamesAndOversizedLists() {
        assertFalse(Toolheads.validExtruder("extruder;DROP"))
        assertFalse(Toolheads.validExtruder("heater_bed"))
        val names = (0..8).joinToString(","){"\"extruder$it\""}
        assertThrows(IllegalArgumentException::class.java) { Toolheads.discover(JSONObject("""{"objects":[$names]}""")) }
    }
}

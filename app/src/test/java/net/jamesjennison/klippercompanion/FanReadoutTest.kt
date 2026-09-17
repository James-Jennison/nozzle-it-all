package net.jamesjennison.klippercompanion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FanReadoutTest {
    @Test fun parsesSpeedAndRpmPerFan() {
        val names = listOf("fan", "fan_generic e1_fan")
        val result = JSONObject("""{"status":{"fan":{"speed":0.5,"rpm":4500.0},"fan_generic e1_fan":{"speed":0.0}}}""")
        val readouts = FanControls.parseReadouts(names, result)
        assertEquals(FanReadout("fan", 0.5, 4500.0), readouts[0])
        assertEquals(FanReadout("fan_generic e1_fan", 0.0, null), readouts[1])
    }
    @Test fun missingReadingReportsUnknown() {
        val readouts = FanControls.parseReadouts(listOf("fan"), JSONObject("""{"status":{}}"""))
        assertNull(readouts.single().speed)
        assertNull(readouts.single().rpm)
    }
    @Test fun rejectsInvalidFanNames() {
        assertThrows(IllegalArgumentException::class.java) {
            FanControls.parseReadouts(listOf("heater_fan hotend_fan"), JSONObject("""{"status":{}}"""))
        }
    }
    @Test fun outOfRangeSpeedIsTreatedAsUnavailable() {
        val result = JSONObject("""{"status":{"fan":{"speed":1.5}}}""")
        assertNull(FanControls.parseReadouts(listOf("fan"), result).single().speed)
    }
}

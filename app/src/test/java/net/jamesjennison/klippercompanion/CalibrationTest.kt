package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class CalibrationTest {
    private val spec = CalibrationSpec(CalibrationKind.TEMPERATURE, 230, -5, 5)
    private fun layers(vararg zs: Float) = zs.joinToString("") { ";LAYER_CHANGE\n;Z:$it\nG1 X1 Y1 E1\n" }

    @Test fun specsRoundTripAndRejectNonsense() {
        assertEquals(spec, CalibrationSpec.decode(spec.encode()))
        assertEquals(CalibrationKind.PRESSURE_ADVANCE, CalibrationSpec.decode("pa")!!.kind)
        assertEquals(CalibrationKind.FLOW, CalibrationSpec.decode("flow")!!.kind)
        for (bad in listOf(null, "", "temp:230:-5", "temp:x:-5:5", "temp:230:0:5", "temp:400:5:5", "temp:230:-50:5", "temp:230:-5:1", "nope")) assertNull(bad, CalibrationSpec.decode(bad))
    }

    @Test fun temperatureStepsPerSectionFromTheHeightOfEachLayer() {
        assertEquals(230, Calibration.temperatureAt(spec, 0.2f)); assertEquals(230, Calibration.temperatureAt(spec, 1.0f))
        assertEquals(230, Calibration.temperatureAt(spec, 5.0f)); assertEquals(225, Calibration.temperatureAt(spec, 11.2f))
        assertEquals(215, Calibration.temperatureAt(spec, 41.0f)); assertEquals(210, Calibration.temperatureAt(spec, 41.2f)); assertEquals(210, Calibration.temperatureAt(spec, 99f)) // clamped to the last section
    }

    @Test fun postProcessInsertsEachTemperatureChangeOnceRightAfterItsLayerMarker() {
        val out = Calibration.postProcess(spec, layers(0.2f, 5f, 10.8f, 11.2f, 15f, 21.4f))
        val m104 = out.lines().filter { it.startsWith("M104") }
        assertEquals(listOf("M104 S225 ; calibration: temperature tower", "M104 S220 ; calibration: temperature tower"), m104)
        val lines = out.lines(); val i = lines.indexOf(";Z:11.2"); assertTrue(lines[i + 1].startsWith("M104 S225"))
        assertTrue("a layer inside the same section adds nothing", out.contains(";Z:15.0\nG1"))
    }

    @Test fun postProcessingKeepsEveryOriginalLineInOrder() {
        val g = "M104 S230\n" + layers(0.2f, 11.2f) + "M84\n"
        val out = Calibration.postProcess(spec, g)
        assertEquals(g.lines().filter { it.isNotEmpty() }, out.lines().filter { it.isNotEmpty() && !it.startsWith("M104 S225") })
    }

    @Test fun pressureAdvanceCommandsAreInsertedExactlyOnceAtTheFirstLayer() {
        val out = Calibration.postProcess(CalibrationSpec(CalibrationKind.PRESSURE_ADVANCE), layers(0.2f, 0.4f, 0.6f))
        assertEquals(1, out.lines().count { it.startsWith("TUNING_TOWER") })
        assertEquals(1, out.lines().count { it.startsWith("SET_VELOCITY_LIMIT") })
        val lines = out.lines(); assertTrue(lines.indexOf(";Z:0.2") < lines.indexOfFirst { it.startsWith("TUNING_TOWER") })
        assertEquals(layers(0.2f), Calibration.postProcess(CalibrationSpec(CalibrationKind.FLOW), layers(0.2f)))
    }

    private fun size(m: TriMesh, axis: Int) = m.maxAlong(axis) - m.minAlong(axis)
    @Test fun generatedModelsHaveTheExpectedSizes() {
        val tower = Calibration.temperatureTower(5)
        assertEquals(Calibration.BASE_HEIGHT + 5 * Calibration.SECTION_HEIGHT, size(tower, 2), 1e-3f)
        assertEquals(0f, tower.minAlong(2), 1e-6f)
        assertEquals(20f, size(Calibration.flowCube(), 0), 1e-3f); assertEquals(20f, size(Calibration.flowCube(), 2), 1e-3f)
        assertEquals(40f, size(Calibration.pressureAdvanceTower(), 2), 1e-3f)
        assertEquals(tower.triangleCount, Calibration.temperatureTower(5).triangleCount)
    }
}

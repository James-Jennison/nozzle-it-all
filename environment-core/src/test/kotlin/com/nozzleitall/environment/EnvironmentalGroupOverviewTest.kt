package com.nozzleitall.environment

import org.junit.Assert.*
import org.junit.Test

class EnvironmentalGroupOverviewTest {
    private val bindings = (0..3).map { Binding("b$it", "s$it", "a$it", "govee-ble", "H5100") }
    @Test fun mapsOnlyUnambiguousNamedS4Corners() {
        val labels = bindings.mapIndexed { i, b -> b.sensorId to "S4 ${EnvironmentalGroupOverview.corners[i]}" }.toMap()
        val group = EnvironmentalSensorGroup("S4", bindings)
        assertEquals(bindings[0], EnvironmentalGroupOverview.s4Positions(group, labels)!!["Back Left"])
        assertNull(EnvironmentalGroupOverview.s4Positions(group.copy(title = "Dryer"), labels))
        assertNull(EnvironmentalGroupOverview.s4Positions(group, labels + ("s1" to "S4 Back Left")))
        assertNull(EnvironmentalGroupOverview.s4Positions(group, labels + ("s1" to "S4 Top")))
        assertEquals(2, EnvironmentalGroupOverview.s4Positions(group.copy(bindings = bindings.take(2)), labels)!!.size)
    }
    @Test fun summaryExcludesMissingStaleFutureAndNonfinitePerChannel() {
        fun reading(t: Long, temp: Double, rh: Double) = EnvironmentalDashboardData.Received(t,
            mapOf(Channel.TEMPERATURE to temp, Channel.HUMIDITY to rh))
        val readings = mapOf("b0" to reading(1000, 40.0, 20.0), "b1" to reading(121000, 44.0, Double.NaN),
            "b2" to reading(999, 90.0, 90.0), "b3" to reading(121001, 0.0, 0.0))
        val s = EnvironmentalGroupOverview.stats(bindings, readings, Channel.TEMPERATURE, 121000)!!
        assertEquals(2, s.count); assertEquals(42.0, s.average, 0.0)
        assertEquals(40.0, s.minimum, 0.0); assertEquals(44.0, s.maximum, 0.0)
        assertEquals(1, EnvironmentalGroupOverview.stats(bindings, readings, Channel.HUMIDITY, 121000)!!.count)
        assertNull(EnvironmentalGroupOverview.stats(bindings, readings, Channel.TEMPERATURE, 250000))
        assertNull(EnvironmentalGroupOverview.stats(bindings, emptyMap(), Channel.TEMPERATURE, 121000))
    }
    @Test fun overlaidSeriesShareAxesAndPreserveGapsWithoutInventingSamples() {
        fun s(id: String, b: String, t: Long, v: Double, seq: Long, run: String = "r") =
            ChartSample(id, b, t, run, 0, mapOf(Channel.TEMPERATURE to v), seq)
        val samples = listOf(s("1", "b0", 1000, 20.0, 1), s("2", "b0", 2000, 21.0, 2),
            s("3", "b1", 2000, 40.0, 1), s("4", "b1", 3000, 42.0, 2, "new"))
        val r = EnvironmentalGroupOverview.trends(samples, bindings, Channel.TEMPERATURE)
        assertEquals(2, r.size)
        assertEquals(r.getValue("b0").low, r.getValue("b1").low, 0.0)
        assertEquals(r.getValue("b0").high, r.getValue("b1").high, 0.0)
        assertEquals(1000L, r.getValue("b1").start); assertEquals(3000L, r.getValue("b0").end)
        assertEquals(.5f, r.getValue("b1").points.first().x, .0001f)
        assertTrue(r.getValue("b0").points.last().connect)
        assertFalse(r.getValue("b1").points.last().connect)
        assertTrue(r.getValue("b0").points.first().y > r.getValue("b1").points.first().y)
        assertTrue(EnvironmentalGroupOverview.trends(samples, bindings, Channel.HUMIDITY).isEmpty())
    }
}

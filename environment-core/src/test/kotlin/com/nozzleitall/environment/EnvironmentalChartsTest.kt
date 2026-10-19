package com.nozzleitall.environment

import org.junit.Assert.*
import org.junit.Test

class EnvironmentalChartsTest {
    private fun sample(i: Int, time: Long = i * 1000L, session: String = "one", dropped: Long = 0) =
        ChartSample("id$i", "sensor", time, session, dropped, mapOf(Channel.TEMPERATURE to i.toDouble()), i.toLong())
    @Test fun boundedMergeDeduplicatesAndOrdersByReceptionTime() {
        val result = EnvironmentalCharts.merge((0..599).map { sample(it) }, listOf(sample(599), sample(600)))
        assertEquals(500, result.size); assertEquals("id101", result.first().id); assertEquals("id600", result.last().id)
    }
    @Test fun timestampsDriveHorizontalScaleAndMissingDataNeverInventsSamples() {
        val series = EnvironmentalCharts.series(listOf(sample(0), sample(1, 1000), sample(2, 10000)), "sensor", Channel.TEMPERATURE)!!
        assertEquals(.1f, series.points[1].x, .0001f)
        assertNull(EnvironmentalCharts.series(listOf(sample(0)), "other", Channel.TEMPERATURE))
        assertNull(EnvironmentalCharts.series(listOf(sample(0)), "sensor", Channel.HUMIDITY))
        val singleton = EnvironmentalCharts.series(listOf(sample(0)), "sensor", Channel.TEMPERATURE)!!
        assertEquals(.5f, singleton.points.single().x); assertFalse(singleton.points.single().connect)
        assertTrue(singleton.high > singleton.low)
    }
    @Test fun sessionDropAndLongGapBreakLines() {
        val series = EnvironmentalCharts.series(listOf(sample(0), sample(1), sample(2, session = "two"),
            sample(3, session = "two", dropped = 1), sample(4, 130000, "two")), "sensor", Channel.TEMPERATURE)!!
        assertEquals(listOf(false, true, false, false, false), series.points.map { it.connect })
    }
    @Test fun historyUsesCalibratedValuesAndReceptionNotMeasurementTime() {
        val row = """{"eventId":"one","binding":"sensor","receivedAt":1234,"observedAt":1000,"session":"s","droppedBefore":2,"readings":[{"channel":"environment.temperature","raw":20,"offset":1.5}]}"""
        val result = EnvironmentalCharts.history(listOf(row)).single()
        assertEquals(1234L, result.time); assertEquals(21.5, result.values.getValue(Channel.TEMPERATURE), 0.0)
        assertEquals(2L, result.dropped)
    }
    @Test fun clockRollbackCannotConnectReorderedSequences() {
        val series = EnvironmentalCharts.series(listOf(sample(0, 1000), sample(1, 2000), sample(2, 1500)), "sensor", Channel.TEMPERATURE)!!
        assertFalse(series.points.last().connect)
    }
    @Test fun cumulativeDropCounterBreaksOnlyAtItsTransition() {
        val series = EnvironmentalCharts.series(listOf(sample(0), sample(1, dropped = 1), sample(2, dropped = 1), sample(3, dropped = 1)), "sensor", Channel.TEMPERATURE)!!
        assertEquals(listOf(false, false, true, true), series.points.map { it.connect })
    }
    @Test fun realHistoryEncodingRetainsSequenceAndConnects() {
        val observations = (1L..2L).map { i -> Observation("e$i", "inc", "collector", "session", i, "sensor", 1,
            i * 1000, i * 1000, i, decoder = "fixture", readings = listOf(Reading(Channel.HUMIDITY, 40.0 + i))) }
        val rows = observations.map { org.json.JSONObject(ObservationEncoding.encode(it)).put("receivedAt", it.receivedAt).toString() }
        val series = EnvironmentalCharts.series(EnvironmentalCharts.history(rows), "sensor", Channel.HUMIDITY)!!
        assertEquals(listOf(false, true), series.points.map { it.connect })
    }
}

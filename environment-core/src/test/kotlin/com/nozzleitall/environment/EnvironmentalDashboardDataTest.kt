package com.nozzleitall.environment

import org.junit.Assert.*
import org.junit.Test

class EnvironmentalDashboardDataTest {
    private val binding = Binding("b", "s", "AA:BB:CC:DD:EE:FF", "govee-ble", "H5100")
    private fun view(state: EnvironmentalState) = EnvironmentalView(state, bindings = listOf(binding))
    @Test fun stoppingKeepsTimestampedDiscoveryReadingWithoutInventingRecordedSamples() {
        val discovery = view(EnvironmentalState.DISCOVERING).copy(discovery = mapOf(
            "aa:bb:cc:dd:ee:ff" to DiscoveryReception(listOf(Reading(Channel.TEMPERATURE, 24.5)), 1234, null)))
        val snapshot = EnvironmentalDashboardData().update(discovery, emptyList())
        val stopped = snapshot.update(view(EnvironmentalState.ENABLED_IDLE), emptyList())
        assertEquals(1234L, stopped.readings.getValue("b").time)
        assertEquals(24.5, stopped.readings.getValue("b").values.getValue(Channel.TEMPERATURE), 0.0)
        assertTrue(stopped.samples.isEmpty())
        assertTrue(EnvironmentalState.ENABLED_IDLE.dashboardStatus().startsWith("Stopped"))
    }
    @Test fun olderHistoryCannotReplaceNewReceptionAndSavedHistoryRestoresAfterRestart() {
        val old = ChartSample("event", "b", 100, "run", 0, mapOf(Channel.TEMPERATURE to 20.0), 1)
        val live = view(EnvironmentalState.DISCOVERING).copy(discovery = mapOf(
            binding.address to DiscoveryReception(listOf(Reading(Channel.TEMPERATURE, 25.0)), 200, null)))
        val data = EnvironmentalDashboardData().update(live, listOf(old))
        assertEquals(25.0, data.readings.getValue("b").values.getValue(Channel.TEMPERATURE), 0.0)
        val restarted = EnvironmentalDashboardData().update(view(EnvironmentalState.ENABLED_IDLE), listOf(old))
        assertEquals(100L, restarted.readings.getValue("b").time)
        assertEquals(listOf(old), restarted.samples)
    }
    @Test fun archivesDeletionAndDisableClearDisplayAndHistoryMemoryIsBounded() {
        val rows = (1..700).map { ChartSample("$it", "b", it.toLong(), "run", 0, mapOf(Channel.HUMIDITY to 42.0), it.toLong()) }
        val data = EnvironmentalDashboardData().update(view(EnvironmentalState.COLLECTING), rows)
        assertEquals(500, data.samples.size)
        assertEquals(700L, data.readings.getValue("b").time)
        assertEquals(EnvironmentalDashboardData(), data.update(view(EnvironmentalState.DISABLED), emptyList()))
        assertEquals(EnvironmentalDashboardData(), data.update(view(EnvironmentalState.ENABLED_IDLE).copy(bindings = emptyList()), emptyList()))
        assertEquals(EnvironmentalDashboardData(), data.update(view(EnvironmentalState.ENABLED_IDLE).copy(bindings = listOf(binding.copy(archived = true))), emptyList()))
    }
}

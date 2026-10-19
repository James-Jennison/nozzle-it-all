package com.nozzleitall.environment

import org.junit.Assert.*
import org.junit.Test

class EnvironmentalSensorGroupsTest {
    private fun binding(id: String) = Binding(id, id, id, "govee-ble", "H5100")
    @Test fun groupsSharedNamesAndOrdersCornersWithoutChangingRecords() {
        val names = mapOf("br" to "S4 Back Right", "room" to "Printing Room", "fl" to "S4 Front Left",
            "u1" to "Snapmaker U1", "bl" to "S4 Back Left", "fr" to "S4 Front Right")
        val bindings = names.keys.map(::binding)
        val result = environmentalSensorGroups(bindings, names)
        assertEquals(listOf("S4", "Other sensors"), result.map { it.title })
        assertEquals(listOf("fl", "fr", "bl", "br"), result[0].bindings.map { it.id })
        assertEquals(listOf("room", "u1"), result[1].bindings.map { it.id })
        assertEquals(result, environmentalSensorGroups(bindings.reversed(), names))
        assertEquals(bindings.toSet(), result.flatMap { it.bindings }.toSet())
    }
    @Test fun noGroupingByModelOrSinglePositionOrDuplicateName() {
        val bindings = listOf(binding("a"), binding("b"), binding("c"))
        for (names in listOf(emptyMap(), mapOf("a" to "S4 Front Left"),
            mapOf("a" to "S4 Front Left", "b" to "S4 Front Left"))) {
            val result = environmentalSensorGroups(bindings, names)
            assertEquals(1, result.size); assertNull(result.single().title)
        }
    }
    @Test fun caseAndWhitespaceAreToleratedButDifferentPrefixesAndArchivesAreNotMerged() {
        val names = mapOf("a" to "S4 Front Left", "b" to "s4  BACK  RIGHT", "c" to "S40 Front Right", "d" to "S4 Back Left")
        val result = environmentalSensorGroups(names.keys.map(::binding).map { if (it.id == "d") it.copy(archived = true) else it }, names)
        assertEquals(listOf("a", "b"), result.first().bindings.map { it.id })
        assertEquals(listOf("c"), result.last().bindings.map { it.id })
        assertTrue(environmentalSensorGroups(emptyList(), names).isEmpty())
    }
}

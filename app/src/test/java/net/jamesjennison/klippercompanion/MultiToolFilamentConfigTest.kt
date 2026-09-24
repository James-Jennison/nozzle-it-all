package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

class MultiToolFilamentConfigTest {
    private val pla = MaterialProfile("bundled-pla", "PLA", "PLA", colorHex = "#FFFFFF", tempNozzleC = 210, tempBedC = 60, source = MaterialSource.BUNDLED)
    private val red = MaterialProfile("custom-red", "Red PLA", "PLA", colorHex = "#FF0000", tempNozzleC = 200, tempBedC = 60, source = MaterialSource.CUSTOM)
    private val blue = MaterialProfile("custom-blue", "Blue PETG", "PETG", colorHex = "#0000FF", tempNozzleC = 240, tempBedC = 80, source = MaterialSource.CUSTOM)

    @Test fun buildsARealFourSlotOverrideFromRealMaterials() {
        val overrides = MultiToolFilamentConfig.overridesFor(1.75, listOf(red, blue, null, null), pla)
        assertEquals("1.75,1.75,1.75,1.75", overrides.getValue("filament_diameter"))
        assertEquals("#FF0000;#0000FF;#FFFFFF;#FFFFFF", overrides.getValue("filament_colour"))
        assertEquals("PLA;PETG;PLA;PLA", overrides.getValue("filament_type"))
        assertEquals("200,240,210,210", overrides.getValue("nozzle_temperature"))
        assertEquals("200,240,210,210", overrides.getValue("nozzle_temperature_initial_layer"))
    }

    @Test fun aMaterialWithNoDeclaredTempFallsBackToTheFallbackMaterialsTemp() {
        val noTemp = MaterialProfile("spoolman-x", "Mystery", "PLA", colorHex = "#00FF00", tempNozzleC = null, tempBedC = null, source = MaterialSource.SPOOLMAN)
        val overrides = MultiToolFilamentConfig.overridesFor(1.75, listOf(noTemp), pla)
        assertEquals("210", overrides.getValue("nozzle_temperature"))
    }

    @Test fun aMaterialWithNoColorFallsBackToWhite() {
        val noColor = MaterialProfile("spoolman-y", "Mystery", "PLA", colorHex = null, tempNozzleC = 210, tempBedC = 60, source = MaterialSource.SPOOLMAN)
        val overrides = MultiToolFilamentConfig.overridesFor(1.75, listOf(noColor), pla)
        assertEquals("#FFFFFF", overrides.getValue("filament_colour"))
    }

    @Test fun singleSlotStillProducesARealOneEntryOverride() {
        val overrides = MultiToolFilamentConfig.overridesFor(1.75, listOf(red), pla)
        assertEquals("1.75", overrides.getValue("filament_diameter"))
        assertEquals("#FF0000", overrides.getValue("filament_colour"))
    }

    @Test fun emptySlotListIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { MultiToolFilamentConfig.overridesFor(1.75, emptyList(), pla) }
    }

    @Test fun wholeNumberDiameterHasNoTrailingZero() {
        val overrides = MultiToolFilamentConfig.overridesFor(2.0, listOf(pla, pla), pla)
        assertEquals("2,2", overrides.getValue("filament_diameter"))
    }

    @Test fun flushMatrixIsAlwaysSquareForTheRealSlotCountWithAZeroDiagonal() {
        val pla = BUNDLED_MATERIAL_PROFILES.first()
        for (n in listOf(2, 4, 5)) {
            val o = MultiToolFilamentConfig.overridesFor(1.75, List(n) { pla }, pla)
            val matrix = o.getValue("flush_volumes_matrix").split(',').map { it.toInt() }
            assertEquals(n * n, matrix.size)
            for (i in 0 until n) for (j in 0 until n) assertEquals(if (i == j) 0 else MultiToolFilamentConfig.FLUSH_BETWEEN_TOOLS_MM3, matrix[i * n + j])
            assertEquals(n * 2, o.getValue("flush_volumes_vector").split(',').size)
        }
    }
}

package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test

// Phase 3 (Consumer Slicer Plan §11): MaterialProfile's own pure logic - Spoolman-sourced profile
// conversion and the real OrcaSlicer config-key overrides a material actually drives at slice
// time. SlicingCoordinatorDeviceTest covers the real, device-verified round trip (a chosen
// material's temps actually landing in real sliced G-code); this covers the deterministic
// mapping logic without needing the native engine.
class MaterialsTest {
    @Test fun bundledProfilesHaveRealSaneDefaultsForEveryCommonMaterial() {
        val types = BUNDLED_MATERIAL_PROFILES.map { it.type }.toSet()
        assertEquals(setOf("PLA", "PETG", "ABS", "TPU"), types)
        for (profile in BUNDLED_MATERIAL_PROFILES) {
            assertEquals(MaterialSource.BUNDLED, profile.source)
            assertNotNull(profile.tempNozzleC); assertNotNull(profile.tempBedC)
            assertTrue("${profile.type} nozzle temp should be a real FDM value", profile.tempNozzleC!! in 150..300)
            assertTrue("${profile.type} bed temp should be a real FDM value", profile.tempBedC!! in 0..120)
        }
    }

    @Test fun spoolmanSpoolConvertsToARealMaterialProfile() {
        val spool = SpoolmanSpool(
            id = 42, remainingWeight = 500.0, totalWeight = 1000.0, archived = false,
            filamentName = "PETG Basic", material = "PETG", colorHex = "FF7043", vendorName = "Bambu Lab",
            tempNozzleC = 240, tempBedC = 80,
        )
        val profile = spool.toMaterialProfile()
        assertEquals("spoolman-42", profile.id)
        assertEquals("Bambu Lab PETG Basic", profile.displayName)
        assertEquals("PETG", profile.type)
        assertEquals("Bambu Lab", profile.manufacturer)
        assertEquals("FF7043", profile.colorHex)
        assertEquals(240, profile.tempNozzleC)
        assertEquals(80, profile.tempBedC)
        assertEquals(MaterialSource.SPOOLMAN, profile.source)
    }

    // A real, common case: a Spoolman filament entry with no settings_extruder_temp/
    // settings_bed_temp configured - the resulting MaterialProfile must stay null there, not
    // default to an invented value.
    @Test fun spoolmanSpoolWithNoConfiguredTemperaturesStaysNull() {
        val spool = SpoolmanSpool(id = 1, remainingWeight = null, totalWeight = null, archived = false,
            filamentName = null, material = "PLA", colorHex = null, vendorName = null)
        val profile = spool.toMaterialProfile()
        assertNull(profile.tempNozzleC); assertNull(profile.tempBedC)
        assertTrue(profile.toOverrides().isEmpty())
    }

    // Real OrcaSlicer config keys, verified against this app's own bundled profile packs
    // (slicer_profiles/*/filament.json) - nozzle_temperature/nozzle_temperature_initial_layer
    // for the hotend, and all four plate-type bed-temp keys (since which one a given machine
    // profile actually references depends on its own bed_type setting).
    @Test fun toOverridesProducesTheRealConfigKeys() {
        val profile = MaterialProfile("test", "Test PLA", "PLA", tempNozzleC = 205, tempBedC = 55, source = MaterialSource.CUSTOM)
        val overrides = profile.toOverrides()
        assertEquals("205", overrides["nozzle_temperature"])
        assertEquals("205", overrides["nozzle_temperature_initial_layer"])
        for (plate in listOf("cool_plate_temp", "eng_plate_temp", "hot_plate_temp", "textured_plate_temp")) {
            assertEquals("55", overrides[plate])
            assertEquals("55", overrides["${plate}_initial_layer"])
        }
        assertEquals(10, overrides.size)
    }

    @Test fun toOverridesOmitsAnUnsetTemperatureEntirelyRatherThanGuessing() {
        val nozzleOnly = MaterialProfile("test", "Test", "PLA", tempNozzleC = 210, tempBedC = null, source = MaterialSource.CUSTOM)
        assertEquals(mapOf("nozzle_temperature" to "210", "nozzle_temperature_initial_layer" to "210"), nozzleOnly.toOverrides())
        val neither = MaterialProfile("test", "Test", "PLA", tempNozzleC = null, tempBedC = null, source = MaterialSource.CUSTOM)
        assertTrue(neither.toOverrides().isEmpty())
    }
}

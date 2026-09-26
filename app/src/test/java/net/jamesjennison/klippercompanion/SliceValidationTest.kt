package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test
import java.io.File

// Phase 5 (Consumer Slicer Plan §16): deterministic coverage for the real slice-time validation
// checks - both the pure decision logic and parsing against every bundled profile pack's own real
// machine.json/filament.json (not synthetic fixtures for the parse step, the same discipline
// BedShapeTest already uses for printable_area).
class SliceValidationTest {
    @Test fun machineLimitsAcceptBothArrayAndPlainStringForms() {
        assertEquals(MachineLimits(0.07, 0.3), parseMachineLimits("""{"min_layer_height":["0.07"],"max_layer_height":["0.3"]}"""))
        assertEquals(MachineLimits(0.07, 0.3), parseMachineLimits("""{"min_layer_height":"0.07","max_layer_height":"0.3"}"""))
        assertEquals(MachineLimits(null, null), parseMachineLimits("""{}"""))
    }

    @Test fun limitsAcceptEveryFormVendorsWriteThem() {
        assertEquals(MachineLimits(0.08, 0.3), parseMachineLimits("""{"min_layer_height":"0.08,0.08","max_layer_height":"0.3,0.3"}"""))
        assertEquals(MachineLimits(0.07, 0.28), parseMachineLimits("""{"min_layer_height":["0.07"],"max_layer_height":["0.28"]}"""))
        assertEquals(MachineLimits(0.07, 0.28), parseMachineLimits("""{"min_layer_height":"0.07","max_layer_height":"0.28"}"""))
        assertEquals(FilamentTemperatureRange(190, 240), parseFilamentTemperatureRange("""{"nozzle_temperature_range_low":"190,190","nozzle_temperature_range_high":["240"]}"""))
    }

    @Test fun everyBundledMachineJsonParsesToRealLayerHeightLimits() {
        val root = File("src/main/assets/slicer_profiles")
        val machineFiles = root.listFiles()?.mapNotNull { dir -> File(dir, "machine.json").takeIf { it.exists() } }.orEmpty()
        assertTrue("expected to find bundled machine.json files to test against", machineFiles.isNotEmpty())
        for (file in machineFiles) {
            val limits = parseMachineLimits(file.readText())
            assertNotNull("${file.path}: expected a real min_layer_height", limits.minLayerHeightMm)
            assertNotNull("${file.path}: expected a real max_layer_height", limits.maxLayerHeightMm)
            assertTrue("${file.path}: min should be less than max", limits.minLayerHeightMm!! < limits.maxLayerHeightMm!!)
        }
    }

    @Test fun everyBundledFilamentJsonParsesToRealTemperatureRanges() {
        val root = File("src/main/assets/slicer_profiles")
        val filamentFiles = root.listFiles()?.mapNotNull { dir -> File(dir, "filament.json").takeIf { it.exists() } }.orEmpty()
        assertTrue(filamentFiles.isNotEmpty())
        for (file in filamentFiles) {
            val range = parseFilamentTemperatureRange(file.readText())
            assertNotNull("${file.path}: expected a real nozzle_temperature_range_low", range.lowC)
            assertNotNull("${file.path}: expected a real nozzle_temperature_range_high", range.highC)
            assertTrue("${file.path}: low should be less than high", range.lowC!! < range.highC!!)
        }
    }

    @Test fun layerHeightWithinRangeProducesNoIssue() {
        val limits = MachineLimits(0.08, 0.32)
        assertTrue(validateSliceConfiguration(limits, 0.2, null, null).isEmpty())
    }

    @Test fun layerHeightBelowMinimumIsABlockingIssue() {
        val limits = MachineLimits(0.08, 0.32)
        val issues = validateSliceConfiguration(limits, 0.05, null, null)
        assertEquals(1, issues.size)
        assertTrue(issues[0] is SliceValidationIssue.LayerHeightOutOfRange)
        assertTrue(issues[0].blocking)
    }

    @Test fun layerHeightAboveMaximumIsABlockingIssue() {
        val limits = MachineLimits(0.08, 0.32)
        val issues = validateSliceConfiguration(limits, 0.35, null, null)
        assertEquals(1, issues.size)
        assertTrue(issues[0].blocking)
    }

    @Test fun unknownMachineLimitsProducesNoLayerHeightIssue() {
        // No real data known yet (e.g. a printer with no bundled pack resolved) - absence of
        // data must never be treated as "out of range."
        assertTrue(validateSliceConfiguration(null, 999.0, null, null).isEmpty())
        assertTrue(validateSliceConfiguration(MachineLimits(null, null), 999.0, null, null).isEmpty())
    }

    @Test fun materialTemperatureOutsideProfileRangeIsANonBlockingWarning() {
        val range = FilamentTemperatureRange(190, 230)
        val petg = MaterialProfile("bundled-petg", "PETG", "PETG", tempNozzleC = 240, tempBedC = 80, source = MaterialSource.BUNDLED)
        val issues = validateSliceConfiguration(null, 0.2, range, petg)
        assertEquals(1, issues.size)
        assertTrue(issues[0] is SliceValidationIssue.MaterialTemperatureOutsideProfileRange)
        assertFalse("a declared-range mismatch is real but not a hard block - PETG often legitimately runs hotter", issues[0].blocking)
    }

    @Test fun materialTemperatureWithinProfileRangeProducesNoIssue() {
        val range = FilamentTemperatureRange(190, 230)
        val pla = MaterialProfile("bundled-pla", "PLA", "PLA", tempNozzleC = 210, tempBedC = 60, source = MaterialSource.BUNDLED)
        assertTrue(validateSliceConfiguration(null, 0.2, range, pla).isEmpty())
    }

    @Test fun materialWithNoConfiguredTemperatureProducesNoIssue() {
        val range = FilamentTemperatureRange(190, 230)
        val unknown = MaterialProfile("custom", "Mystery", "Unknown", tempNozzleC = null, tempBedC = null, source = MaterialSource.CUSTOM)
        assertTrue(validateSliceConfiguration(null, 0.2, range, unknown).isEmpty())
    }

    @Test fun realBundledPetgAndAbsExceedTheGenericKlipperProfilesDeclaredRange() {
        // Confirms this check has real teeth today, not just in theory: this app's own bundled
        // PETG (240C)/ABS (250C) MaterialProfile presets genuinely exceed generic_klipper's own
        // bundled filament.json range (190-230C, a PLA-centric default reused across every
        // profile pack) - a real, currently-true mismatch, not a hypothetical.
        val range = parseFilamentTemperatureRange(File("src/main/assets/slicer_profiles/generic_klipper/filament.json").readText())
        val petg = BUNDLED_MATERIAL_PROFILES.single { it.type == "PETG" }
        val abs = BUNDLED_MATERIAL_PROFILES.single { it.type == "ABS" }
        val pla = BUNDLED_MATERIAL_PROFILES.single { it.type == "PLA" }
        assertFalse(validateSliceConfiguration(null, 0.2, range, petg).isEmpty())
        assertFalse(validateSliceConfiguration(null, 0.2, range, abs).isEmpty())
        assertTrue("PLA should be within the bundled range", validateSliceConfiguration(null, 0.2, range, pla).isEmpty())
    }

    @Test fun bothIssuesCanBePresentAtOnce() {
        val limits = MachineLimits(0.08, 0.32)
        val range = FilamentTemperatureRange(190, 230)
        val petg = MaterialProfile("bundled-petg", "PETG", "PETG", tempNozzleC = 240, tempBedC = 80, source = MaterialSource.BUNDLED)
        val issues = validateSliceConfiguration(limits, 0.05, range, petg)
        assertEquals(2, issues.size)
        assertTrue(issues.any { it is SliceValidationIssue.LayerHeightOutOfRange })
        assertTrue(issues.any { it is SliceValidationIssue.MaterialTemperatureOutsideProfileRange })
    }
}

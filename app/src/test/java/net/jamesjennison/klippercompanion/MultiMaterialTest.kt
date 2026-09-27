package net.jamesjennison.klippercompanion

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MultiMaterialTest {
    private fun m(id: String, type: String, nozzle: Int? = 210, bed: Int? = 60) = MaterialProfile(id, id, type, tempNozzleC = nozzle, tempBedC = bed, source = MaterialSource.BUNDLED)
    private val toolchanger = MultiToolFamily.TOOLCHANGER

    @Test fun familyFollowsTheMachineNotJustTheToolCount() {
        assertEquals(MultiToolFamily.SINGLE, multiToolFamily(SlicingPrinterModel.SNAPMAKER_U1, 1))
        assertEquals(MultiToolFamily.TOOLCHANGER, multiToolFamily(SlicingPrinterModel.SNAPMAKER_U1, 4))
        assertEquals(MultiToolFamily.TOOLCHANGER, multiToolFamily(SlicingPrinterModel.PRUSA_XL_5T, 5))
        // A single-nozzle Prusa (MK4 + MMU) feeds one nozzle from several spools, so it is a filament swap, not tools.
        assertEquals(MultiToolFamily.FILAMENT_SWAP, multiToolFamily(SlicingPrinterModel.PRUSA_GENERIC, 5))
        assertEquals(MultiToolFamily.TOOLCHANGER, multiToolFamily(null, 3)) // unknown printer keeps the previous default
        assertEquals(MultiToolFamily.FILAMENT_SWAP, multiToolFamily(SlicingPrinterModel.BAMBU_GENERIC, 4))
    }

    @Test fun compatibleMaterialsProduceNoWarnings() {
        assertTrue(MaterialCompatibility.warnings(listOf(m("a", "PLA"), m("b", "PLA", 215, 60)), toolchanger).isEmpty())
        assertTrue(MaterialCompatibility.warnings(listOf(m("a", "PLA")), toolchanger).isEmpty())
        assertTrue("the same material twice is not a mix", MaterialCompatibility.warnings(listOf(m("a", "PLA"), m("a", "PLA")), toolchanger).isEmpty())
    }

    @Test fun lowAndHighTemperatureFamiliesWarn() {
        val w = MaterialCompatibility.warnings(listOf(m("a", "PLA", 210, 60), m("b", "ABS", 250, 100)), toolchanger)
        assertTrue(w.any { it.contains("PLA") && it.contains("ABS") && it.contains("bond") })
        assertTrue(w.any { it.contains("Nozzle temperatures differ by 40") })
        assertTrue(w.any { it.contains("Bed temperatures differ by 40") })
    }

    @Test fun flexibleWithRigidWarnsDifferentlyPerFamily() {
        val mats = listOf(m("a", "PLA"), m("b", "TPU"))
        assertTrue(MaterialCompatibility.warnings(mats, MultiToolFamily.FILAMENT_SWAP).any { it.contains("jams") })
        assertTrue(MaterialCompatibility.warnings(mats, toolchanger).any { it.contains("bond poorly") })
    }

    @Test fun unknownTemperaturesAndVariantNamesAreTolerated() {
        assertTrue(MaterialCompatibility.warnings(listOf(m("a", "PLA", null, null), m("b", "PLA-CF", null, null)), toolchanger).isEmpty())
        assertTrue(MaterialCompatibility.warnings(listOf(m("a", "PLA"), m("b", "ABS-GF", 250, 60)), toolchanger).any { it.contains("ABS") })
    }

    @Test fun toolColorsUseTheMaterialsColourElseADistinctDefault() {
        assertEquals("#123456", toolColorHex(0, m("a", "PLA").copy(colorHex = "#123456")))
        assertEquals(DEFAULT_TOOL_COLORS[1], toolColorHex(1, null))
        assertEquals(DEFAULT_TOOL_COLORS[0], toolColorHex(DEFAULT_TOOL_COLORS.size, null))
        assertEquals(DEFAULT_TOOL_COLORS.size, DEFAULT_TOOL_COLORS.toSet().size)
    }

    @Test fun gcodeStatsReadPerToolUsageChangesAndEstimateThePurge() {
        val f = File.createTempFile("stats", ".gcode").apply { writeText(
            "; filament used [mm] = 1241.54, 1226.54, 0.00, 0.00\n; filament used [g] = 3.70, 3.66, 0.00, 0.00\n; total filament used [g] = 7.36\n" +
            "; estimated printing time (normal mode) = 14m 35s\n; total filament change = 100\n; filament_density = 1.24,1.24\n" +
            "; enable_prime_tower = 1\n; flush_volumes_matrix = 0,84,84,84,84,0,84,84,84,84,0,84,84,84,84,0\n") }
        val s = GcodeStatsParser.parse(f)
        assertEquals(2468.08, s.filamentUsedMm!!, 0.01); assertEquals(100, s.toolchanges); assertEquals(listOf(0, 1), s.toolsUsed)
        assertEquals(listOf(3.70, 3.66, 0.0, 0.0), s.perToolGrams)
        assertEquals(100 * 84 / 1000.0 * 1.24, s.estimatedPurgeGrams()!!, 1e-6)
        assertNull(s.purgeNote(MultiToolFamily.FILAMENT_SWAP)); assertTrue(s.purgeNote(MultiToolFamily.TOOLCHANGER)!!.contains("Independent tools"))
        val noTower = GcodeStatsParser.parse(File.createTempFile("stat2", ".gcode").apply { writeText("; total filament change = 12\n; enable_prime_tower = 0\n") })
        assertEquals(0.0, noTower.estimatedPurgeGrams()!!, 0.0); assertTrue(noTower.purgeNote(MultiToolFamily.FILAMENT_SWAP)!!.contains("No prime tower"))
        assertNull(GcodeStatsParser.parse(File.createTempFile("stat3", ".gcode").apply { writeText("; total filament change = 3\n") }).estimatedPurgeGrams()) // no matrix to estimate from
    }

    @Test fun gcodeStatsReadBambusLayout() {
        // Bambu profiles on the Snapmaker Orca engine: time in the header block, per-filament weights with no total.
        val s = GcodeStatsParser.parse(File.createTempFile("bambu", ".gcode").apply { writeText(
            "; model printing time: 8m 52s; total estimated time: 15m 47s\n; total layer number: 100\n; filament used [mm] = 1298.51\n; filament used [g] = 3.94, 0.50\n") })
        assertEquals("15m 47s", s.printTime); assertEquals(4.44, s.filamentUsedGrams!!, 1e-9)
    }

    @Test fun towerAndFlushControlsAreOnlyOfferedWhereTheyDoSomething() {
        val towerKeys = listOf("enable_prime_tower", "prime_tower_width", "flush_multiplier", "flush_into_infill", "flush_into_objects", "flush_into_support")
        for (family in listOf(null, MultiToolFamily.SINGLE, MultiToolFamily.TOOLCHANGER)) assertTrue(family.toString(), SettingsCatalog.visible(SettingTier.EXPERT, "", family).none { it.key in towerKeys })
        assertEquals(towerKeys.toSet(), SettingsCatalog.visible(SettingTier.EXPERT, "", MultiToolFamily.FILAMENT_SWAP).map { it.key }.filter { it in towerKeys }.toSet())
    }

    @Test fun previewTracksToolChangesAndTagsSegmentsWithTheirTool() {
        val g = "G90\nM83\nG0 X0 Y0 Z0.2\nT0\nG1 X10 E1\nT1\nG1 X20 E1\nG0 Z0.4\nT0\nG1 X30 E1\nT0\n"
        val p = GcodePreview.parse(java.io.ByteArrayInputStream(g.toByteArray()))
        assertEquals(listOf(0, 1, 0), p.segments.map { it.tool })
        assertEquals(listOf(ToolChange(0, 1), ToolChange(1, 0)), p.toolChanges)
        assertEquals(setOf(0, 1), p.toolsUsed)
    }
}

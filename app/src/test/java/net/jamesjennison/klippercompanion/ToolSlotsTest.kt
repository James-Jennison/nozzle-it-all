package net.jamesjennison.klippercompanion

import net.jamesjennison.klippercompanion.project.ProjectObject
import net.jamesjennison.klippercompanion.project.withMaterial
import net.jamesjennison.klippercompanion.project.withToolSlot
import org.junit.Assert.*
import org.junit.Test
import java.io.File

// Phase 8 (§11, §16), first real increment. Proves parseToolCount against every bundled
// machine.json this app actually ships (not a synthetic fixture) - same discipline
// BedShapeTest.kt already uses for parseBedShape.
class ToolSlotsTest {
    @Test fun everyBundledMachineJsonReportsARealToolCount() {
        val root = File("src/main/assets/slicer_profiles")
        val machineFiles = root.listFiles()?.mapNotNull { dir -> File(dir, "machine.json").takeIf { it.exists() } }.orEmpty()
        assertTrue("expected to find bundled machine.json files to test against", machineFiles.isNotEmpty())
        for (file in machineFiles) {
            val count = parseToolCount(file.readText())
            assertTrue("${file.parentFile?.name}: expected a real, positive tool count", count >= 1)
        }
    }
    // The U1's four real toolheads: four nozzle_diameter entries in slicer_profiles/snapmaker_u1/machine.json.
    @Test fun snapmakerU1RealProfileReportsFourTools() {
        val count = parseToolCount(File("src/main/assets/slicer_profiles/snapmaker_u1/machine.json").readText())
        assertEquals(4, count)
    }
    @Test fun everyOtherBundledProfileIsSingleExtruderToday() {
        for (dir in listOf("bambu_generic", "prusa_generic", "generic_klipper", "elegoo_centauri_carbon_cosmos")) {
            val count = parseToolCount(File("src/main/assets/slicer_profiles/$dir/machine.json").readText())
            assertEquals(dir, 1, count)
        }
    }
    // machine.json declares one extruder; an AMS printer's pack adds its filament slots (BambuAms).
    @Test fun bambuAmsPacksDeclareFourFilamentSlots() {
        val pack = slicingProfilePack(SlicingPrinterModel.BAMBU_X1_CARBON, null) ?: throw AssertionError("no X1 Carbon pack")
        assertEquals(4, pack.toolCountOf(File("src/main/assets/slicer_profiles/bambu_x1_carbon/machine.json").readText()))
    }
    @Test fun missingOrMalformedExtruderColourFallsBackToOneRatherThanGuessingHigher() {
        assertEquals(1, parseToolCount("""{}"""))
        assertEquals(1, parseToolCount("""{"nozzle_diameter": []}"""))
        assertEquals(1, parseToolCount("""{"nozzle_diameter": "not-an-array"}"""))
    }
    @Test fun toolSlotsForASingleExtruderIsOneSingleExtruderSlot() {
        val slots = toolSlotsFor(ToolSetup(nozzles = 1, slots = 1))
        assertEquals(listOf(ToolSlot(0, ToolCapability.SINGLE_EXTRUDER)), slots)
    }
    @Test fun toolSlotsForFourToolheadsIsFourIndependentToolSlots() {
        val slots = toolSlotsFor(ToolSetup(nozzles = 4, slots = 4))
        assertEquals(4, slots.size)
        assertEquals((0..3).toList(), slots.map { it.index })
        assertTrue(slots.all { it.capability == ToolCapability.INDEPENDENT_TOOL })
    }
    // Four spools through one nozzle (AMS, CANVAS): filament-swap slots, the same decision multiToolFamily makes.
    @Test fun toolSlotsForFourSpoolsOnOneNozzleAreFilamentSwapSlots() {
        val setup = ToolSetup(nozzles = 1, slots = 4)
        assertEquals(MultiToolFamily.FILAMENT_SWAP, setup.family)
        assertTrue(toolSlotsFor(setup).all { it.capability == ToolCapability.AMS_SLOT })
    }
    // The tool count is the physical nozzles, whatever extruder_colour holds (a Creality single-nozzle profile stores
    // "#FCE94F" as one string; the Sermoon D3 Pro two colours as one string).
    @Test fun extruderColourNeverAddsTools() {
        assertEquals(1, parseToolCount("""{"nozzle_diameter": ["0.4"], "extruder_colour": ["#FFFFFF", "#000000", "#FF0000"]}"""))
        assertEquals(1, parseToolCount(File("src/main/assets/slicer_profiles/creality_sermoon_d3_pro/machine.json").readText()))
    }
    // Every catalogue pack classifies from its own nozzles and slots: toolchangers and IDEX printers are independent tools,
    // spools through one nozzle are a filament swap.
    @Test fun catalogueFamiliesComeFromEachPacksOwnNozzlesAndSlots() {
        fun family(model: SlicingPrinterModel, gen: CosmosProfileGeneration? = null): MultiToolFamily {
            val pack = slicingProfilePack(model, gen) ?: throw AssertionError("no pack for $model")
            val machine = File("src/main/assets/${pack.machinePath}").readText()
            return pack.toolSetupOf(machine).family
        }
        for (m in listOf(SlicingPrinterModel.SNAPMAKER_U1, SlicingPrinterModel.PRUSA_XL_5T, SlicingPrinterModel.SNAPMAKER_J1,
                SlicingPrinterModel.FLASHFORGE_CREATOR_5, SlicingPrinterModel.RATRIG_RATRIG_V_CORE_4_IDEX_300, SlicingPrinterModel.BAMBU_H2D))
            assertEquals(m.name, MultiToolFamily.TOOLCHANGER, family(m))
        for (m in listOf(SlicingPrinterModel.BAMBU_X1_CARBON, SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_CANVAS))
            assertEquals(m.name, MultiToolFamily.FILAMENT_SWAP, family(m))
        assertEquals(MultiToolFamily.FILAMENT_SWAP, family(SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_COSMOS_CANVAS, CosmosProfileGeneration.CURRENT))
        for (m in listOf(SlicingPrinterModel.CREALITY_K1, SlicingPrinterModel.PRUSA_MK4S, SlicingPrinterModel.PRUSA_XL))
            assertEquals(m.name, MultiToolFamily.SINGLE, family(m))
    }

    // Phase 8 follow-up (WO-27): real base filament_diameter, parsed from every bundled
    // filament.json this app actually ships - same discipline as everyToolCountTest above.
    @Test fun everyBundledFilamentJsonReportsARealBaseFilamentDiameter() {
        val root = File("src/main/assets/slicer_profiles")
        val filamentFiles = root.listFiles()?.mapNotNull { dir -> File(dir, "filament.json").takeIf { it.exists() } }.orEmpty()
        assertTrue("expected to find bundled filament.json files to test against", filamentFiles.isNotEmpty())
        for (file in filamentFiles) {
            val diameter = parseBaseFilamentDiameter(file.readText())
            assertTrue("${file.parentFile?.name}: expected a real, plausible filament diameter", diameter in 1.0..5.0)
        }
    }
    @Test fun snapmakerU1RealFilamentJsonReports175mm() {
        val diameter = parseBaseFilamentDiameter(File("src/main/assets/slicer_profiles/snapmaker_u1/filament.json").readText())
        assertEquals(1.75, diameter, 0.001)
    }
    @Test fun missingOrMalformedFilamentDiameterFallsBackTo175() {
        assertEquals(1.75, parseBaseFilamentDiameter("""{}"""), 0.001)
        assertEquals(1.75, parseBaseFilamentDiameter("""{"filament_diameter": []}"""), 0.001)
        assertEquals(1.75, parseBaseFilamentDiameter("""{"filament_diameter": ["not-a-number"]}"""), 0.001)
    }

    // Phase 8 follow-up (WO-28): the pure logic ProjectEditorScreen's own real per-object
    // assignment UI relies on, extracted specifically so it's testable without a Compose harness.
    private val red = MaterialProfile("custom-red", "Red PLA", "PLA", colorHex = "#FF0000", source = MaterialSource.CUSTOM)
    private val blue = MaterialProfile("custom-blue", "Blue PLA", "PLA", colorHex = "#0000FF", source = MaterialSource.CUSTOM)
    private fun projectObject(id: String, toolSlotIndex: Int?, material: MaterialProfile?) =
        ProjectObject(id = id, projectId = "p", sourceFileUri = "file:///$id.stl").withToolSlot(toolSlotIndex).withMaterial(material)

    @Test fun singleToolTargetProducesNoAssignmentsAtAll() {
        val (indices, materials) = multiToolSliceInputsFor(listOf(projectObject("a", 1, red)), toolCount = 1)
        assertTrue(indices.isEmpty()); assertTrue(materials.isEmpty())
    }
    @Test fun multiToolTargetBuildsRealIndexParallelToolAssignments() {
        val objects = listOf(projectObject("a", 1, red), projectObject("b", 2, blue))
        val (indices, materials) = multiToolSliceInputsFor(objects, toolCount = 4)
        assertEquals(listOf(1, 2), indices)
        assertEquals(listOf(red.id, blue.id, null, null), materials.map { it?.id })
    }
    @Test fun unassignedObjectDefaultsToToolOne() {
        val objects = listOf(projectObject("a", null, red))
        val (indices, _) = multiToolSliceInputsFor(objects, toolCount = 2)
        assertEquals(listOf(1), indices)
    }
    @Test fun aSlotWithNoAssignedObjectHasANullMaterialNotACrash() {
        val objects = listOf(projectObject("a", 1, red))
        val (_, materials) = multiToolSliceInputsFor(objects, toolCount = 3)
        assertEquals(3, materials.size)
        assertNull(materials[1]); assertNull(materials[2])
    }
    @Test fun emptyObjectListProducesEmptyAssignmentsNotAnException() {
        val (indices, materials) = multiToolSliceInputsFor(emptyList(), toolCount = 4)
        assertTrue(indices.isEmpty())
        assertEquals(4, materials.size)
        assertTrue(materials.all { it == null })
    }

    // Colour mixing (0.2.0, requirement 5): colourMixFeaturesFor must reproduce
    // com.nozzleitall.printer.ext.ProfileFeatures.of's own real gating rule for every PrinterKind Android actually
    // has - a U1 family gets Full Spectrum, any other real multi-slot target gets ColorMix, and a single-tool
    // target (any kind) gets neither. Mirrors PrusaColorMixTest.colorMixIsForEveryMultiSlotPrinterWithoutFullSpectrum
    // on the Desktop side, since Android has no PrinterFamily/familyHint of its own to test that rule against directly.
    @Test fun stockU1WithMultipleToolsGetsFullSpectrum() {
        assertEquals(setOf(com.nozzleitall.printer.ext.Snapmaker.FULL_SPECTRUM), colourMixFeaturesFor(PrinterKind.SNAPMAKER_U1, 4))
    }
    @Test fun paxxU1WithMultipleToolsGetsFullSpectrum() {
        assertEquals(setOf(com.nozzleitall.printer.ext.Snapmaker.FULL_SPECTRUM), colourMixFeaturesFor(PrinterKind.SNAPMAKER_U1_PAXX, 4))
    }
    @Test fun anyOtherMultiSlotTargetGetsColorMixNotFullSpectrum() {
        assertEquals(setOf(com.nozzleitall.printer.ext.Prusa.COLOR_MIX), colourMixFeaturesFor(PrinterKind.PRUSA_LINK, 5))
        assertEquals(setOf(com.nozzleitall.printer.ext.Prusa.COLOR_MIX), colourMixFeaturesFor(PrinterKind.GENERIC_KLIPPER, 2))
    }
    @Test fun aSingleToolTargetGetsNeitherMixingSystem() {
        assertTrue(colourMixFeaturesFor(PrinterKind.SNAPMAKER_U1, 1).isEmpty())
        assertTrue(colourMixFeaturesFor(PrinterKind.GENERIC_KLIPPER, 1).isEmpty())
        assertTrue(colourMixFeaturesFor(PrinterKind.BAMBU_LAB, 1).isEmpty())
    }
}

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
    // The one bundled profile that actually declares more than one real extruder today -
    // slicer_profiles/snapmaker_u1/machine.json's own extruder_colour array (real multiACE
    // hardware, confirmed by reading the file directly, not assumed).
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
        assertEquals(1, parseToolCount("""{"extruder_colour": []}"""))
        assertEquals(1, parseToolCount("""{"extruder_colour": "not-an-array"}"""))
    }
    @Test fun toolSlotsForASingleExtruderIsOneSingleExtruderSlot() {
        val slots = toolSlotsFor(1)
        assertEquals(listOf(ToolSlot(0, ToolCapability.SINGLE_EXTRUDER)), slots)
    }
    @Test fun toolSlotsForFourToolsIsFourIndependentToolSlots() {
        val slots = toolSlotsFor(4)
        assertEquals(4, slots.size)
        assertEquals((0..3).toList(), slots.map { it.index })
        assertTrue(slots.all { it.capability == ToolCapability.INDEPENDENT_TOOL })
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
}

package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Bed type and nozzle flow follow Snapmaker Orca's printer-card rules (PrinterSetup). */
class PrinterSetupTest {
    private fun machine(id: String) = JSONObject(File(ProfileCatalog.materialize(Files.createTempDirectory("ps").toFile(), id), "machine.json").readText())

    @Test fun bedTypesFollowSnapmakerOrca() {
        val u1 = PrinterSetup.beds(machine("snapmaker_u1"))
        assertTrue(u1.enabled); assertEquals("Textured PEI Plate", u1.default)
        assertEquals(7, u1.choices.size) // support_multi_bed_types: the U1's seven-plate list
        assertTrue(u1.choices.any { it.value == "Graphic Effect Plate" })
        val x1c = PrinterSetup.beds(machine("bambu_x1_carbon"))
        assertTrue(x1c.enabled); assertEquals("Cool Plate", x1c.default)
        val klipper = PrinterSetup.beds(machine("generic_klipper"))
        assertFalse(klipper.enabled); assertEquals("High Temp Plate", klipper.default)
        // A U1 without multi-bed support gets its three plates, and a non-U1 default falls back to Textured PEI.
        val u1Only3 = PrinterSetup.beds(JSONObject().put("printer_model", "Snapmaker U1").put("support_multi_bed_types", "0"))
        assertEquals(listOf("Textured PEI Plate", "High Temp Plate", "Graphic Effect Plate"), u1Only3.choices.map { it.value })
    }

    @Test fun highFlowIsOfferedOnlyWherePrintersDeclareIt() {
        assertTrue(PrinterSetup.supportsHighFlow(JSONObject().put("printer_flow_support", org.json.JSONArray(listOf("standard", "high_flow")))))
        assertFalse(PrinterSetup.supportsHighFlow(machine("generic_klipper")))
        assertEquals(4, PrinterSetup.nozzles(machine("snapmaker_u1")).size)
    }
}

/** The Snapmaker U1's profile family from Snapmaker Orca: nozzle sizes, its process presets, slicing on each. */
class PrinterLibraryTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    @Test fun u1FamilyMatchesSnapmakerOrca() {
        val lib = PrinterLibrary.of("snapmaker_u1")!!
        assertEquals(listOf("0.2", "0.4", "0.6", "0.8"), lib.machines.map { it.nozzle })
        val m04 = lib.machineFor("0.4")
        assertEquals("0.20mm Standard", lib.processes[m04.defaultProcess]!!.label)
        assertTrue(lib.processesFor(m04).any { it.label == "0.10mm Color Mixing" })
        assertEquals("Snapmaker PLA Basic @U1", lib.filaments[m04.defaultFilament]!!.name)
        val dir = lib.materialize(Files.createTempDirectory("lib").toFile(), m04, null)
        assertTrue(PrinterSetup.supportsHighFlow(JSONObject(File(dir, "machine.json").readText())))
    }

    @Test fun aSixTenthsNozzleSlicesWithItsOwnProfiles() {
        org.junit.Assume.assumeTrue(SliceEngine.locateEngine() != null)
        val lib = PrinterLibrary.of("snapmaker_u1")!!
        val m06 = lib.machineFor("0.6")
        val tmp = Files.createTempDirectory("lib06").toFile()
        val dir = lib.materialize(tmp, m06, null)
        val cube = MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())
        val obj = com.nozzleitall.project.ModelObject(1, "cube", cube, com.nozzleitall.project.Transform.translate(125.0, 125.0, 0.0))
        val producer = com.nozzleitall.project.ProjectManifest.Producer("t", "desktop", "t")
        val m = com.nozzleitall.project.ProjectManifest("p", 1, "n", producer, producer, 1, com.nozzleitall.project.ProjectManifest.PrinterTarget("Snapmaker U1", profileId = "snapmaker_u1"),
            listOf(com.nozzleitall.project.ProjectManifest.PlateEntry(1, "Plate 1", listOf(com.nozzleitall.project.ProjectManifest.ObjectEntry(1, "cube", 1)))))
        val out = SliceEngine(SliceEngine.locateEngine()!!, File(tmp, "s").apply { mkdirs() }).slice(SliceRequest(com.nozzleitall.project.Project3mf(listOf(obj), emptyMap(), m),
            dir, QualityPreset.STANDARD, false, 15, listOf(com.nozzleitall.project.ProjectManifest.MaterialSlot(1, "PLA", colorHex = "#FFFFFF")), applyPreset = false)) { _, _ -> }
        assertTrue("$out", out is SliceOutcome.Done)
        val g = (out as SliceOutcome.Done).gcode.readText()
        assertTrue(Regex("^; nozzle_diameter = 0\\.6", RegexOption.MULTILINE).containsMatchIn(g))
        assertTrue("its own process: 0.30 mm layers", Regex("^; layer_height = 0\\.3\\b", RegexOption.MULTILINE).containsMatchIn(g))
    }
}

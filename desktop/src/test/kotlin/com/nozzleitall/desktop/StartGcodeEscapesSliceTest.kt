package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Profiles keep G-code in Orca's serialized form, where a scalar string may carry C-style escapes ("\\n" in the JSON
 * text, as Snapmaker Orca's own U1 machines do). The engine's profile load unescapes it as Orca's does
 * (ConfigOptionString::deserialize), so the start G-code reaches the printer one command per line.
 */
class StartGcodeEscapesSliceTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    private fun slice(dir: File, bedW: Float, bedD: Float, tmp: File): List<String> {
        val bin = SliceEngine.locateEngine()
        assumeTrue("slicing engine available", bin != null)
        val cube = MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())
        val obj = ModelObject(1, "cube", cube, Transform.translate(bedW / 2.0 - 10, bedD / 2.0 - 10, 0.0))
        val producer = ProjectManifest.Producer("t", "desktop", "t")
        val m = ProjectManifest("p", 1, "n", producer, producer, 1, ProjectManifest.PrinterTarget("t"),
            listOf(ProjectManifest.PlateEntry(1, "Plate 1", listOf(ProjectManifest.ObjectEntry(1, "cube", 1)))))
        val out = SliceEngine(bin!!, File(tmp, "slices").apply { mkdirs() }).slice(
            SliceRequest(Project3mf(listOf(obj), emptyMap(), m), dir, QualityPreset.STANDARD, false, 15, emptyList())) { _, _ -> }
        assertTrue("$out", out is SliceOutcome.Done)
        val lines = (out as SliceOutcome.Done).gcode.readLines()
        assertTrue("the slice wrote G-code", lines.size > 100)
        return lines
    }

    /** The executable lines: no comments, and no config dump at the end. */
    private fun commands(lines: List<String>) = lines.map { it.substringBefore(';').trim() }.filter { it.isNotEmpty() }

    @Test fun u1LibraryMachineRunsPrintStartOnItsOwnLine() {
        val tmp = Files.createTempDirectory("esc-u1").toFile()
        val lib = PrinterLibrary.of("snapmaker_u1")!!
        val machine = lib.machineFor("0.4")
        // The library keeps Snapmaker Orca's escaped text; this test is only meaningful while it does.
        assertTrue(lib.json("machine", machine.id)!!.getString("machine_start_gcode").contains("\\n"))
        val dir = lib.materialize(File(tmp, "cache"), machine, null)
        val prof = ProfileCatalog.byId("snapmaker_u1")!!
        val cmds = commands(slice(dir, prof.bedW, prof.bedD, tmp))
        assertTrue("PRINT_START is its own command", cmds.any { it == "PRINT_START" })
        assertTrue("PRINT_END is its own command", cmds.any { it == "PRINT_END" })
        assertTrue("no command carries an escaped line break", cmds.none { "\\n" in it })
    }

    @Test fun ender3V3SePackRunsItsStartGcode() {
        val tmp = Files.createTempDirectory("esc-e3").toFile()
        val prof = ProfileCatalog.byId("creality_ender_3_v3_se")!!
        val dir = ProfileCatalog.materialize(File(tmp, "cache"), prof.id)
        // The pack carries escaped strings (file_start_gcode); its start G-code's first command must still run alone.
        val machine = JSONObject(File(dir, "machine.json").readText())
        assertTrue(machine.toString().contains("\\\\n"))
        val first = machine.getString("machine_start_gcode").lineSequence().map { it.substringBefore(';').trim() }.first { it.isNotEmpty() }
        val cmds = commands(slice(dir, prof.bedW, prof.bedD, tmp))
        assertTrue("$first is its own command", cmds.any { it == first })
        assertTrue("G28 homes", cmds.any { it == "G28" || it.startsWith("G28 ") })
        assertTrue("no command carries an escaped line break", cmds.none { "\\n" in it })
    }
}

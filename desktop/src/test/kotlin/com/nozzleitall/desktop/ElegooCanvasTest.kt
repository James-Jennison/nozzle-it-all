package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** OpenCentauri's COSMOS AFC profile (CANVAS on COSMOS): four-colour slices use AFC's tool change, never M600 or old G-code. */
class ElegooCanvasTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    @Test fun afcProfileSlicesFourColoursWithAfcToolChanges() {
        assumeTrue("slicing engine available", SliceEngine.locateEngine() != null)
        val prof = ProfileCatalog.byId("elegoo_centauri_carbon_cosmos_afc")!!
        assertEquals(4, prof.tools)
        val tmp = Files.createTempDirectory("afc").toFile()
        val cube = MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())
        // Four faces-worth of colours: triangles painted filament 2, 3, 4 in turn, the rest filament 1.
        val paint = Array<String?>(cube.triangleCount) { k -> when (k % 4) { 1 -> "8"; 2 -> "0C"; 3 -> "1C"; else -> null } }
        val obj = ModelObject(1, "cube", Mesh(cube.vertices, cube.triangles, paint), Transform.translate(prof.bedW / 2.0 - 10, prof.bedD / 2.0 - 10, 0.0))
        val producer = ProjectManifest.Producer("t", "desktop", "t")
        val m = ProjectManifest("p", 1, "n", producer, producer, 1, ProjectManifest.PrinterTarget(prof.model, profileId = prof.id),
            listOf(ProjectManifest.PlateEntry(1, "Plate 1", listOf(ProjectManifest.ObjectEntry(1, "cube", 1, paintSlots = listOf(1, 2, 3, 4))))))
        val slots = listOf("#FFFFFF", "#E53935", "#1E88E5", "#FDD835").mapIndexed { i, c -> ProjectManifest.MaterialSlot(i + 1, "PLA", colorHex = c) }
        val out = SliceEngine(SliceEngine.locateEngine()!!, File(tmp, "slices").apply { mkdirs() }).slice(
            SliceRequest(Project3mf(listOf(obj), emptyMap(), m), ProfileCatalog.materialize(File(tmp, "cache"), prof.id), QualityPreset.STANDARD, false, 15, slots)) { _, _ -> }
        assertTrue("$out", out is SliceOutcome.Done)
        val g = (out as SliceOutcome.Done).gcode.readText()
        val changes = Regex("^T([0-3]) PURGE_LENGTH=([0-9.]+)", RegexOption.MULTILINE).findAll(g).toList()
        assertTrue("AFC tool changes: ${changes.size}", changes.size > 10)
        assertEquals(setOf("1", "2", "3"), changes.map { it.groupValues[1] }.toSet() - "0")
        assertTrue(g.contains("PRINT_START EXTRUDER=") && g.contains("TOOL="))
        assertFalse("no manual M600 swaps", Regex("^M600", RegexOption.MULTILINE).containsMatchIn(g))
        assertFalse("no pre-26.07 COSMOS commands (they e-stop)", Regex("^M(729|8213)\\b", RegexOption.MULTILINE).containsMatchIn(g))
    }
}

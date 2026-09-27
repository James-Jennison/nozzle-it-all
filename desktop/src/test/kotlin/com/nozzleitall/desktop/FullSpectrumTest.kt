package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Snapmaker Full Spectrum through the engine: Snapmaker Orca's own mixing and matching code, and mixes that print. */
class FullSpectrumTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val library = listOf("#00C3FF", "#F54399", "#FAE727", "#9A9A9A")

    private fun engineReady() = assumeTrue("slicing engine available", SliceEngine.locateEngine() != null)

    @Test fun aMixIsANumberedSlotWithSnapmakersLabel() {
        engineReady()
        val m = FullSpectrum.add(library, "", 2, 4, 67)
        assertEquals(5, m.addedId)
        val row = m.rows.single()
        assertEquals("F2 33%+F4 67%", row.label)
        assertEquals(5, row.id)
        assertTrue(m.definitions.startsWith("2,4,1,1,67"))
        assertEquals(m.rows, FullSpectrum.display(library, m.definitions).rows)
        assertTrue(FullSpectrum.remove(library, m.definitions, 5).rows.none { it.enabled })
    }

    @Test fun colourMatchMapsLikeSnapmakerOrca() {
        engineReady()
        // The owner's Snapmaker Orca screenshot: cyan, magenta and yellow reuse slots 1-3; grey, green and orange become mixes 7-9.
        val targets = listOf("#00C3FF", "#F54399", "#FAE727", "#9A9A9A", "#88CC22", "#FF8844").mapIndexed { i, c -> c to listOf(i + 1) }
        val r = FullSpectrum.match("manual", (library + listOf("#88CC22", "#FF8844")).map { it to "PLA" }, targets, "", listOf(1, 2, 3, 4))
        val bySlot = r.results.associate { it.targetHex.uppercase() to it }
        assertTrue(bySlot.getValue("#00C3FF").pure); assertEquals(1, bySlot.getValue("#00C3FF").slot)
        assertEquals(2, bySlot.getValue("#F54399").slot); assertEquals(3, bySlot.getValue("#FAE727").slot)
        assertTrue(r.results.filter { !it.pure }.all { it.slot > 4 && it.components.isNotEmpty() })
        assertEquals(listOf(5), bySlot.getValue("#88CC22").sourceIds) // mapped back to the model's own colour numbers
    }

    @Test fun aPaintedMixPrintsWithItsTwoFilaments() {
        engineReady()
        val tmp = Files.createTempDirectory("fs").toFile()
        val cube = MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())
        val paint = Array<String?>(cube.triangleCount) { "8" } // the whole cube is the file's filament 2
        val prof = ProfileCatalog.byId("snapmaker_u1")!!
        val defs = FullSpectrum.add(library, "", 2, 4, 50).definitions
        val obj = ModelObject(1, "cube", Mesh(cube.vertices, cube.triangles, paint), Transform.translate(prof.bedW / 2.0 - 10, prof.bedD / 2.0 - 10, 0.0))
        val producer = ProjectManifest.Producer("t", "desktop", "t")
        val m = ProjectManifest("p", 1, "n", producer, producer, 1, ProjectManifest.PrinterTarget(prof.model, profileId = prof.id),
            listOf(ProjectManifest.PlateEntry(1, "Plate 1", listOf(ProjectManifest.ObjectEntry(1, "cube", 1, paintSlots = listOf(1, 5))))))
        val slots = library.mapIndexed { i, c -> ProjectManifest.MaterialSlot(i + 1, "PLA", colorHex = c) }
        val out = SliceEngine(SliceEngine.locateEngine()!!, File(tmp, "slices").apply { mkdirs() }).slice(
            SliceRequest(Project3mf(listOf(obj), emptyMap(), m), ProfileCatalog.materialize(File(tmp, "cache"), prof.id), QualityPreset.STANDARD,
                false, 15, slots, extraOverrides = mapOf(FullSpectrum.DEFINITIONS_KEY to defs))) { _, _ -> }
        assertTrue("$out", out is SliceOutcome.Done)
        val tools = (out as SliceOutcome.Done).gcode.readLines().mapNotNull { Regex("^T(\\d+)\\s*$").find(it)?.groupValues?.get(1)?.toInt() }
        // Mix 5 = slots 2 and 4 alternating layer by layer: T1 and T3, many changes between them, nothing else.
        assertEquals(setOf(1, 3), tools.toSet())
        assertTrue("alternates: ${tools.size} selections", tools.zipWithNext().count { (a, b) -> a != b } > 20)
    }
}

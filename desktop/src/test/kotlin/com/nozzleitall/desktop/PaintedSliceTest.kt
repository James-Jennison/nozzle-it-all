package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** A painted model prints its colours: the paint reaches the engine through a 3MF, renumbered to the chosen slots. */
class PaintedSliceTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    private fun paintedCube(): Mesh {
        val cube = MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())
        val b = cube.bounds()
        // The top face and the +X side are the file's filament 2; everything else is the object's own.
        val paint = Array<String?>(cube.triangleCount) { k ->
            val zs = (0..2).map { cube.vertices[cube.triangles[k * 3 + it] * 3 + 2] }; val xs = (0..2).map { cube.vertices[cube.triangles[k * 3 + it] * 3] }
            if (zs.all { it >= b[5] - 1e-3f } || xs.all { it >= b[3] - 1e-3f }) "8" else null
        }
        return Mesh(cube.vertices, cube.triangles, paint)
    }

    @Test fun paintIsRenumberedToSlots() {
        val (mesh, highest) = SliceEngine.paintInSlots(paintedCube(), listOf(1, 3), 1)
        assertEquals(3, highest)
        assertEquals(setOf("0C", null), mesh.paint!!.toSet()) // filament 2 -> slot 3
    }

    @Test fun paintedCubeSlicesInTwoColours() {
        val bin = SliceEngine.locateEngine()
        assumeTrue("slicing engine available", bin != null)
        val tmp = Files.createTempDirectory("paint").toFile()
        val prof = ProfileCatalog.byId("snapmaker_u1")!!
        val obj = ModelObject(1, "cube", paintedCube(), Transform.translate(prof.bedW / 2.0 - 10, prof.bedD / 2.0 - 10, 0.0))
        val producer = ProjectManifest.Producer("t", "desktop", "t")
        val m = ProjectManifest("p", 1, "n", producer, producer, 1, ProjectManifest.PrinterTarget(prof.model, profileId = prof.id),
            listOf(ProjectManifest.PlateEntry(1, "Plate 1", listOf(ProjectManifest.ObjectEntry(1, "cube", 1, paintSlots = listOf(1, 3))))))
        val slots = listOf("#FFFFFF", "#FF0000", "#0000FF", "#00FF00").mapIndexed { i, c -> ProjectManifest.MaterialSlot(i + 1, "PLA", colorHex = c) }
        val out = SliceEngine(bin!!, File(tmp, "slices").apply { mkdirs() }).slice(
            SliceRequest(Project3mf(listOf(obj), emptyMap(), m), ProfileCatalog.materialize(File(tmp, "cache"), prof.id), QualityPreset.STANDARD, false, 15, slots)) { _, _ -> }
        assertTrue("$out", out is SliceOutcome.Done)
        val done = out as SliceOutcome.Done
        val tools = done.gcode.readLines().mapNotNull { Regex("^T(\\d+)\\s*$").find(it)?.groupValues?.get(1)?.toInt() }.toSet()
        assertTrue("unpainted in slot 1 (T0) and painted in slot 3 (T2): $tools", tools.containsAll(listOf(0, 2)))
        assertFalse("slot 2 isn't used: $tools", 1 in tools)
        assertTrue(done.stats.toolChanges > 0)
    }
}

/** A turned object is measured as it sits: a long bar turned 90 degrees is narrow in X and long in Y. */
class FootprintTest {
    @Test fun rotationChangesTheFootprint() {
        val v = floatArrayOf(0f, 0f, 0f, 100f, 0f, 0f, 100f, 10f, 0f, 0f, 10f, 0f, 0f, 0f, 5f, 100f, 10f, 5f)
        val item = PrepItem(1, "bar", Mesh(v, intArrayOf(0, 1, 2, 0, 2, 3, 4, 1, 5)), 135f, 135f)
        assertEquals(100f, item.footprintW, 1e-3f); assertEquals(10f, item.footprintD, 1e-3f)
        item.rotZ = 90f
        assertEquals(10f, item.footprintW, 1e-3f); assertEquals(100f, item.footprintD, 1e-3f)
        item.rotZ = 45f
        assertEquals((110 / Math.sqrt(2.0)).toFloat(), item.footprintW, 1e-2f)
    }
}

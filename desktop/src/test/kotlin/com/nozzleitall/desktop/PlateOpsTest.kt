package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.Mesh
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Orca's plate tools: Split (ported its_split) and Arrange, Auto orient and Cut run by the engine. */
class PlateOpsTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val cube = MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())
    private fun moved(m: Mesh, dx: Float) = Mesh(FloatArray(m.vertices.size) { if (it % 3 == 0) m.vertices[it] + dx else m.vertices[it] }, m.triangles, m.paint)
    private fun joined(a: Mesh, b: Mesh) = Mesh(a.vertices + b.vertices, a.triangles + b.triangles.map { it + a.vertexCount }, arrayOfNulls<String>(a.triangleCount) + Array(b.triangleCount) { "8" })

    @Test fun splitFindsSeparatePartsAndKeepsTheirPaint() {
        assertEquals("an STL cube repeats its corners but is one part", 1, PlateOps.split(cube).size)
        val parts = PlateOps.split(joined(cube, moved(cube, 40f)))
        assertEquals(2, parts.size)
        assertEquals(listOf(12, 12), parts.map { it.triangleCount })
        assertNull(parts[0].paint); assertEquals(setOf("8"), parts[1].paint!!.toSet())
        assertEquals(40f, parts[1].bounds()[0] - parts[0].bounds()[0], 1e-4f)
    }

    @Test fun engineOrientsCutsAndArranges() {
        assumeTrue("slicing engine available", SliceEngine.locateEngine() != null)
        val work = Files.createTempDirectory("plate").toFile()
        // A cube tilted 30 degrees comes back standing on a face.
        val a = Math.toRadians(30.0)
        val tilted = Mesh(FloatArray(cube.vertices.size) { i -> val v = cube.vertices; val b = i - i % 3
            when (i % 3) { 1 -> (v[b + 1] * cos(a) - v[b + 2] * sin(a)).toFloat(); 2 -> (v[b + 1] * sin(a) + v[b + 2] * cos(a)).toFloat(); else -> v[i] } }, cube.triangles)
        val upright = PlateOps.orient(tilted, work)
        val zs = (0 until upright.vertexCount).map { upright.vertices[it * 3 + 2] }.sorted()
        assertEquals("four corners on the bed", zs[0], zs[3], 1e-3f)
        assertEquals(20f, zs.last() - zs.first(), 1e-3f)
        // Cut at half height: two capped 10 mm halves.
        val (upper, lower) = PlateOps.cut(cube, cube.bounds()[2] + 10f, work)
        listOf(upper!!, lower!!).forEach { h -> val b = h.bounds(); assertEquals(10f, b[5] - b[2], 1e-3f); assertTrue(h.triangleCount >= 12) }
        // Three cubes on top of each other are spread out on the U1's plate, clear of one another.
        val dir = ProfileCatalog.materialize(File(work, "cache"), "snapmaker_u1")
        val items = List(3) { PrepItem(it + 1, "c$it", cube, 135f, 135f) }
        val placed = PlateOps.arrange(listOf("machine.json", "process.json", "filament.json").map { File(dir, it) }, emptyMap(), items, 270f, 270f, work = work)
        assertTrue(placed.all { it.plate == 0 })
        val centres = placed.map { (135 + it.dx) to (135 + it.dy) }
        for (i in centres.indices) for (j in i + 1 until centres.size) {
            val d = maxOf(abs(centres[i].first - centres[j].first), abs(centres[i].second - centres[j].second))
            assertTrue("cubes $i and $j apart: $centres", d >= 20.0)
        }
        // More than a plate's worth doesn't fit: the rest go to another plate.
        val many = PlateOps.arrange(listOf("machine.json", "process.json", "filament.json").map { File(dir, it) }, emptyMap(), List(200) { PrepItem(it + 1, "c$it", cube, 135f, 135f) }, 270f, 270f, work = work)
        assertTrue(many.any { it.plate > 0 })
    }
}

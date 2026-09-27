package com.nozzleitall.desktop

import net.jamesjennison.klippercompanion.FlushVolumes

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.*
import java.io.File
import java.nio.file.Files

/** The engine gets the flushing volumes Prepare works out (the calculation itself is tested in :domain FlushVolumesTest). */
class FlushVolumesSliceTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "schemas/fixtures").isDirectory }

    /** A two-colour slice on a single-nozzle CANVAS printer: the engine gets the colour-based matrix, or the user's, cropped. */
    @Test fun sliceUsesColourFlushOrTheEditedMatrix() {
        val bin = SliceEngine.locateEngine()
        assumeTrue("slicing engine available", bin != null)
        val tmp = Files.createTempDirectory("flush").toFile()
        val prof = ProfileCatalog.byId("elegoo_centauri_carbon_2_canvas")!!
        val cube = MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())
        val top = cube.bounds()[5]
        val paint = Array<String?>(cube.triangleCount) { k -> if ((0..2).all { cube.vertices[cube.triangles[k * 3 + it] * 3 + 2] >= top - 1e-3f }) "8" else null }
        val obj = ModelObject(1, "cube", Mesh(cube.vertices, cube.triangles, paint), Transform.translate(prof.bedW / 2.0 - 10, prof.bedD / 2.0 - 10, 0.0))
        val producer = ProjectManifest.Producer("t", "desktop", "t")
        val m = ProjectManifest("p", 1, "n", producer, producer, 1, ProjectManifest.PrinterTarget(prof.model, profileId = prof.id),
            listOf(ProjectManifest.PlateEntry(1, "Plate 1", listOf(ProjectManifest.ObjectEntry(1, "cube", 1, paintSlots = listOf(1, 2))))))
        val slots = listOf("#000000", "#FFFFFF", "#FF0000").mapIndexed { i, c -> ProjectManifest.MaterialSlot(i + 1, "PLA", colorHex = c) }
        val dir = ProfileCatalog.materialize(File(tmp, "cache"), prof.id)
        fun header(flush: List<Int>?): String {
            val out = SliceEngine(bin!!, File(tmp, "slices").apply { mkdirs() }).slice(
                SliceRequest(Project3mf(listOf(obj), emptyMap(), m), dir, QualityPreset.STANDARD, false, 15, slots, flushMatrix = flush)) { _, _ -> }
            assertTrue("$out", out is SliceOutcome.Done)
            return (out as SliceOutcome.Done).gcode.readLines().first { it.startsWith("; flush_volumes_matrix = ") }.substringAfter("= ")
        }
        // The CC2's own slicer (ElegooSlicer) overrides black to white with 900 mm³.
        val setup = SliceEngine.flushSetup(dir, slots.take(2))
        assertEquals(FlushVolumes.Method.ELEGOO, setup.method)
        val auto = setup.matrix(listOf("#000000", "#FFFFFF"))
        assertEquals(900, auto[1])
        assertEquals(auto.joinToString(","), header(null))
        // A 3-slot edited matrix, sliced with the two slots in use: its top-left 2 x 2.
        assertEquals("0,555,111,0", header(listOf(0, 555, 7, 111, 0, 8, 9, 10, 0)))
    }
}

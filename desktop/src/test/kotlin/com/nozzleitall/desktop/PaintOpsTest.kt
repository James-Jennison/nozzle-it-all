package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Orca's paint tools through the engine: support / seam / colour / fuzzy strokes, the 3MF they are stored in, and a support-enforced print. */
class PaintOpsTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private fun engineReady() = assumeTrue("slicing engine available", SliceEngine.locateEngine() != null)

    private fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): Pair<List<Float>, List<Int>> {
        val v = listOf(x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1)
        val q = listOf(intArrayOf(0, 3, 2, 1), intArrayOf(4, 5, 6, 7), intArrayOf(0, 1, 5, 4), intArrayOf(2, 3, 7, 6), intArrayOf(0, 4, 7, 3), intArrayOf(1, 2, 6, 5))
        return v to q.flatMap { listOf(it[0], it[1], it[2], it[0], it[2], it[3]) }
    }

    /** A T: a 10 mm stem under a 30 mm bar, so the bar's underside overhangs on both sides. */
    private fun tee(): Mesh {
        val (v1, t1) = box(0f, 0f, 0f, 10f, 10f, 10f); val (v2, t2) = box(-10f, 0f, 10f, 20f, 10f, 20f)
        return Mesh((v1 + v2).toFloatArray(), (t1 + t2.map { it + 8 }).toIntArray())
    }

    private val underside = PaintOps.Stroke(doubleArrayOf(-5.0, 5.0, -100.0), doubleArrayOf(0.0, 0.0, 1.0), 4.0, 1)

    @Test fun aStrokePaintsOnlyItsKindAndSurvivesA3mfRoundTrip() {
        engineReady()
        val painted = PaintOps.stroke(tee(), PaintOps.Kind.SUPPORT, listOf(underside))
        assertTrue(painted.supportPaint!!.any { it != null }); assertNull(painted.paint); assertNull(painted.seamPaint)
        val back = ThreeMf.read(ThreeMf.write(Project3mf(listOf(ModelObject(1, "tee", painted)))).let { bytes ->
            Files.createTempFile("pt", ".3mf").toFile().apply { writeBytes(bytes); deleteOnExit() } }).objects.single().mesh
        assertArrayEquals(painted.supportPaint, back.supportPaint)
        // The other kinds are independent.
        val both = PaintOps.stroke(painted, PaintOps.Kind.COLOUR, listOf(underside.copy(state = 3)))
        assertTrue(both.paint!!.any { it != null }); assertArrayEquals(painted.supportPaint, both.supportPaint)
    }

    @Test fun eraseAndFillAndClear() {
        engineReady()
        val painted = PaintOps.stroke(tee(), PaintOps.Kind.SEAM, listOf(underside))
        val erased = PaintOps.stroke(painted, PaintOps.Kind.SEAM, listOf(underside.copy(state = 0, radius = 20.0)))
        assertNull("a brush bigger than the painted area leaves nothing", erased.seamPaint)
        val filled = PaintOps.stroke(tee(), PaintOps.Kind.FUZZY, listOf(underside), PaintOps.Mode.FILL)
        assertEquals("fill takes the flat underside of the bar (two triangles)", 2, filled.fuzzyPaint!!.count { it != null })
    }

    @Test fun splitAndMergeKeepEveryKindOfPaint() {
        engineReady()
        val painted = PaintOps.stroke(PaintOps.stroke(tee(), PaintOps.Kind.SUPPORT, listOf(underside)), PaintOps.Kind.FUZZY, listOf(underside), PaintOps.Mode.FILL)
        val merged = MeshIO.merge(listOf(painted, painted))
        assertEquals(painted.supportPaint!!.count { it != null } * 2, merged.supportPaint!!.count { it != null })
        assertEquals(painted.fuzzyPaint!!.count { it != null } * 2, merged.fuzzyPaint!!.count { it != null })
    }

    @Test fun anEnforcedOverhangPrintsSupportAndAnUnpaintedOneDoesNot() {
        engineReady()
        val tmp = Files.createTempDirectory("pp").toFile()
        val prof = ProfileCatalog.byId("snapmaker_u1")!!
        fun support(mesh: Mesh): Boolean {
            val obj = ModelObject(1, "tee", mesh, Transform.translate(prof.bedW / 2.0, prof.bedD / 2.0, 0.0))
            val producer = ProjectManifest.Producer("t", "desktop", "t")
            val m = ProjectManifest("p", 1, "n", producer, producer, 1, ProjectManifest.PrinterTarget(prof.model, profileId = prof.id),
                listOf(ProjectManifest.PlateEntry(1, "Plate 1", listOf(ProjectManifest.ObjectEntry(1, "tee", 1)))))
            val out = SliceEngine(SliceEngine.locateEngine()!!, File(tmp, "slices").apply { mkdirs() }).slice(
                SliceRequest(Project3mf(listOf(obj), emptyMap(), m), ProfileCatalog.materialize(File(tmp, "cache"), prof.id), QualityPreset.STANDARD, false, 15,
                    listOf(ProjectManifest.MaterialSlot(1, "PLA", colorHex = "#FFFFFF")), extraOverrides = mapOf("enable_support" to "1", "support_type" to "normal(manual)"))) { _, _ -> }
            assertTrue("$out", out is SliceOutcome.Done)
            return (out as SliceOutcome.Done).gcode.readLines().any { it.startsWith(";TYPE:Support") }
        }
        assertFalse("manual support with no enforcer: no support", support(tee()))
        assertTrue("an enforcer on the underside prints support under it", support(PaintOps.stroke(tee(), PaintOps.Kind.SUPPORT, listOf(underside))))
    }
}

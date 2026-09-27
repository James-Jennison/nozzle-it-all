package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** PrusaSlicer 2.9.6's ColorMix through the engine: its own normalising, numbering, presets, and blends that print. */
class PrusaColorMixTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val xl = listOf("#FF0000", "#0000FF", "#FFFF00", "#FFFFFF", "#000000").map { it to "PLA" }
    private fun engineReady() = assumeTrue("slicing engine available", SliceEngine.locateEngine() != null)
    private fun blend(id: Int, vararg c: Pair<Int, Double>) = PrusaColorMix.Virtual(id, "fullspectrum", c.map { PrusaColorMix.Component(it.first, it.second) })

    @Test fun blendsNormaliseToPrusasLayerCycle() {
        engineReady()
        val v = PrusaColorMix.normalize(xl, listOf(blend(6, 1 to 2.0 / 3, 2 to 1.0 / 3), blend(7, 3 to 1.0))).single()
        assertEquals(listOf(1, 2, 1), v.cycle)
        assertNotNull(v.effectiveHex)
        assertEquals(8, PrusaColorMix.nextId(5, listOf(blend(6, 1 to .5, 2 to .5), blend(7, 1 to .5, 3 to .5), blend(9, 2 to .5, 3 to .5))))
        assertTrue(PrusaColorMix.presets(xl).isNotEmpty())
        // The sidecar round-trips in PrusaSlicer's format.
        val back = PrusaColorMix.readSidecar(PrusaColorMix.sidecar(xl.map { it.first }, listOf(v)).toByteArray())!!
        assertEquals(5, back.first); assertEquals(v.components, back.second.single().components)
    }

    @Test fun anObjectOnABlendPrintsItsCycle() {
        engineReady()
        val tmp = Files.createTempDirectory("cm").toFile()
        val prof = ProfileCatalog.byId("prusa_xl_5t")!!
        val cube = MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())
        val obj = ModelObject(1, "cube", cube, Transform.translate(prof.bedW / 2.0 - 10, prof.bedD / 2.0 - 10, 0.0))
        val producer = ProjectManifest.Producer("t", "desktop", "t")
        val m = ProjectManifest("p", 1, "n", producer, producer, 1, ProjectManifest.PrinterTarget(prof.model, profileId = prof.id),
            listOf(ProjectManifest.PlateEntry(1, "Plate 1", listOf(ProjectManifest.ObjectEntry(1, "cube", 6)))))
        val slots = xl.mapIndexed { i, (c, t) -> ProjectManifest.MaterialSlot(i + 1, t, colorHex = c) }
        val sidecar = PrusaColorMix.sidecar(xl.map { it.first }, listOf(blend(6, 1 to 2.0 / 3, 2 to 1.0 / 3)))
        val out = SliceEngine(SliceEngine.locateEngine()!!, File(tmp, "slices").apply { mkdirs() }).slice(
            SliceRequest(Project3mf(listOf(obj), emptyMap(), m), ProfileCatalog.materialize(File(tmp, "cache"), prof.id), QualityPreset.STANDARD, false, 15, slots,
                virtualExtruders = sidecar)) { _, _ -> }
        assertTrue("$out", out is SliceOutcome.Done)
        val tools = (out as SliceOutcome.Done).gcode.readLines().mapNotNull { Regex("^T(\\d+)(?:\\s+[A-Z][^;]*)?\\s*(?:;.*)?$").find(it)?.groupValues?.get(1)?.toInt() }
        assertEquals(setOf(0, 1), tools.toSet())
        // Layer by layer: E1, E2, E1, E1, E2, E1 ... (tool changes only where the cycle changes extruder).
        val changes = tools.zipWithNext().count { (a, b) -> a != b }
        assertTrue("the blend alternates: $changes changes", changes > 40)
        assertTrue("tool changes are counted for the XL's T syntax", out.stats.toolChanges > 40)
    }

    @Test fun colorMixIsForEveryMultiSlotPrinterWithoutFullSpectrum() {
        val fs = com.nozzleitall.printer.ext.Snapmaker.FULL_SPECTRUM; val cm = com.nozzleitall.printer.ext.Prusa.COLOR_MIX
        fun of(id: String) = ProfileCatalog.byId(id)!!.let { com.nozzleitall.printer.ext.ProfileFeatures.of(it.familyHint, it.tools) }
        assertEquals(setOf(fs), of("snapmaker_u1"))
        assertEquals(setOf(cm), of("prusa_xl_5t"))
        assertEquals(emptySet<String>(), of("prusa_mk4s"))
        // Not a Prusa rule: any other multi-slot printer (here a generic family with four slots) gets ColorMix.
        assertEquals(setOf(cm), com.nozzleitall.printer.ext.ProfileFeatures.of("klipper", 4))
        assertEquals(setOf(fs), com.nozzleitall.printer.ext.ProfileFeatures.ofPrinter(setOf(fs), 4))
        assertEquals(setOf(cm), com.nozzleitall.printer.ext.ProfileFeatures.ofPrinter(emptySet(), 2))
    }
}


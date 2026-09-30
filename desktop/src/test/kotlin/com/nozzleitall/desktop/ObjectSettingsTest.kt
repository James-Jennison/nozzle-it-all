package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Per-object settings: upstream's menu, and the engine printing one object with its own settings. */
class ObjectSettingsTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    @Test fun upstreamsMenuIsAllOfThisEnginesPerObjectSettings() {
        val cats = ObjectSettings.categories()
        assertEquals(listOf("Quality", "Strength", "Speed", "Support"), cats.map { it.first })
        // get_visible_options orders by priority: layer height leads Quality, the brim leads Support.
        assertEquals("layer_height", cats.first { it.first == "Quality" }.second.map { it.key }.first { it != "bridge_density" })
        assertEquals("brim_type", cats.first { it.first == "Support" }.second.first().key)
        assertTrue(cats.sumOf { it.second.size } > 80)
        assertEquals(mapOf("wall_loops" to "3"), ObjectSettings.usable(mapOf("wall_loops" to "3", "fill_density" to "20%", "printer_model" to "x")))
    }

    private fun slice(settings: Map<String, String>?, tmp: File): SliceOutcome {
        val prof = ProfileCatalog.byId("snapmaker_u1")!!
        val cube = MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())
        val objs = listOf(1 to -25.0, 2 to 25.0).map { (id, dx) -> ModelObject(id, "cube$id", cube, Transform.translate(prof.bedW / 2.0 - 10 + dx, prof.bedD / 2.0 - 10, 0.0)) }
        val producer = ProjectManifest.Producer("t", "desktop", "t")
        val m = ProjectManifest("p", 1, "n", producer, producer, 1, ProjectManifest.PrinterTarget(prof.model, profileId = prof.id),
            listOf(ProjectManifest.PlateEntry(1, "Plate 1", listOf(ProjectManifest.ObjectEntry(1, "cube1", 1), ProjectManifest.ObjectEntry(2, "cube2", 1, settings = settings.orEmpty())))))
        val slots = listOf(ProjectManifest.MaterialSlot(1, "PLA", colorHex = "#FFFFFF"))
        return SliceEngine(SliceEngine.locateEngine()!!, File(tmp, "slices").apply { mkdirs() }).slice(
            SliceRequest(Project3mf(objs, emptyMap(), m), ProfileCatalog.materialize(File(tmp, "cache"), prof.id), QualityPreset.STANDARD, false, 15, slots)) { _, _ -> }
    }

    @Test fun oneObjectPrintsWithItsOwnSettings() {
        assumeTrue("slicing engine available", SliceEngine.locateEngine() != null)
        val tmp = Files.createTempDirectory("objset").toFile()
        val plain = slice(null, tmp) as SliceOutcome.Done
        val solid = slice(mapOf("sparse_infill_density" to "100%", "wall_loops" to "5"), tmp)
        assertTrue("$solid", solid is SliceOutcome.Done)
        solid as SliceOutcome.Done
        // The plate prints at 15 %: one cube solid uses clearly more filament, but not two cubes' worth more.
        assertTrue("${plain.stats.grams} -> ${solid.stats.grams}", solid.stats.grams!! > plain.stats.grams!! * 1.3)
        // A key that isn't an object setting is refused with a readable message, not silently ignored.
        val bad = slice(mapOf("printer_model" to "x"), tmp)
        assertTrue("$bad", bad is SliceOutcome.Failed && "can't be set for one object" in bad.message)
    }
}

package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.printer.ext.MixedFilamentFormat
import com.nozzleitall.project.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Mixed filaments (OrcaSlicer 2.5.0's) through the engine: its blend colours, recipes, renumbering, and mixes that print. */
class MixedFilamentTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val library = listOf("#00C3FF", "#F54399", "#FAE727", "#9A9A9A")
    private val typed get() = library.map { it to "PLA" }
    private fun engineReady() = assumeTrue("slicing engine available", SliceEngine.locateEngine() != null)
    private fun row(vararg c: Pair<Int, Int>) = MixedFilamentFormat.Row(c.map { it.first }, c.map { it.second })

    @Test fun aMixIsASlotAfterTheLoadedOnesWithItsBlendedColour() {
        engineReady()
        val rows = MixedFilament.add(typed, emptyList(), row(2 to 7, 4 to 3))!!
        val r = rows.single()
        assertEquals(5, r.filament)
        assertEquals(listOf(2, 4), r.components)
        assertTrue(r.hex.matches(Regex("#[0-9A-F]{6}")))
        assertEquals("F2 70% + F4 30%", r.label)
        // The same fixed blend is not added twice.
        assertNull(MixedFilament.add(typed, rows, row(2 to 7, 4 to 3)))
        assertEquals(rows, MixedFilament.display(typed, rows))
        assertTrue(MixedFilament.remove(typed, rows, 0).isEmpty())
        assertEquals(6, MixedFilament.add(typed, rows, row(1 to 1, 3 to 1))!!.last().filament)
    }

    @Test fun deletingALoadedFilamentRenumbersAndFlagsMixes() {
        engineReady()
        val rows = listOf(row(2 to 1, 3 to 1), row(1 to 1, 2 to 1))
        val after = MixedFilament.deletePhysical(typed, rows, 1)
        assertEquals(listOf(1, 2), after[0].components)
        assertTrue("a mix that used the removed filament says so", after[1].problems.isNotEmpty())
    }

    @Test fun matchFindsARecipeAndPresetsCoverEveryPair() {
        engineReady()
        val m = MixedFilament.match(typed + listOf("#88CC22" to "PLA", "#FFFFFF" to "PLA"), "#88CC22")
        assertTrue(m.deltaE < 6)
        assertEquals(6, MixedFilament.presets(typed).size / 3)
    }

    @Test fun aPaintedMixPrintsWithItsTwoFilaments() {
        engineReady()
        val tmp = Files.createTempDirectory("mf").toFile()
        val cube = MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())
        val paint = Array<String?>(cube.triangleCount) { "8" } // the whole cube is the file's filament 2
        val prof = ProfileCatalog.byId("snapmaker_u1")!!
        val rows = MixedFilament.add(typed, emptyList(), row(2 to 1, 4 to 1))!!
        val obj = ModelObject(1, "cube", Mesh(cube.vertices, cube.triangles, paint), Transform.translate(prof.bedW / 2.0 - 10, prof.bedD / 2.0 - 10, 0.0))
        val producer = ProjectManifest.Producer("t", "desktop", "t")
        val m = ProjectManifest("p", 1, "n", producer, producer, 1, ProjectManifest.PrinterTarget(prof.model, profileId = prof.id),
            listOf(ProjectManifest.PlateEntry(1, "Plate 1", listOf(ProjectManifest.ObjectEntry(1, "cube", 1, paintSlots = listOf(1, 5))))))
        val slots = library.mapIndexed { i, c -> ProjectManifest.MaterialSlot(i + 1, "PLA", colorHex = c) }
        val out = SliceEngine(SliceEngine.locateEngine()!!, File(tmp, "slices").apply { mkdirs() }).slice(
            SliceRequest(Project3mf(listOf(obj), emptyMap(), m), ProfileCatalog.materialize(File(tmp, "cache"), prof.id), QualityPreset.STANDARD,
                false, 15, slots, extraOverrides = MixedFilament.sliceOverrides(typed, rows))) { _, _ -> }
        assertTrue("$out", out is SliceOutcome.Done)
        val tools = (out as SliceOutcome.Done).gcode.readLines().mapNotNull { Regex("^T(\\d+)\\s*$").find(it)?.groupValues?.get(1)?.toInt() }
        // Mix 5 = slots 2 and 4: only T1 and T3 print the cube (a prime tower may use others), and they alternate.
        assertTrue("both components print: ${tools.toSet()}", tools.toSet().containsAll(setOf(1, 3)))
        assertTrue("alternates: ${tools.size} selections", tools.zipWithNext().count { (a, b) -> a != b } > 20)
    }

    @Test fun mixedFilamentsAreForEveryMultiSlotPrinterExceptCanvas() {
        val f = MixedFilamentFormat.FEATURE
        fun of(id: String) = ProfileCatalog.byId(id)!!.let { com.nozzleitall.printer.ext.ProfileFeatures.of(it.familyHint, it.tools) }
        assertEquals(setOf(f), of("snapmaker_u1"))
        assertEquals(setOf(f), of("prusa_xl_5t"))
        assertEquals(emptySet<String>(), of("prusa_mk4s"))
        assertEquals(setOf(f), com.nozzleitall.printer.ext.ProfileFeatures.of("klipper", 4))
        assertEquals(setOf("x", f), com.nozzleitall.printer.ext.ProfileFeatures.ofPrinter(setOf("x"), 4))
        for (id in com.nozzleitall.printer.ext.ProfileFeatures.CANVAS_PROFILES) {
            val p = ProfileCatalog.byId(id)!!
            assertEquals(id, 4, p.tools)
            assertEquals(id, emptySet<String>(), com.nozzleitall.printer.ext.ProfileFeatures.of(p.familyHint, p.tools, p.id))
            assertEquals(id, emptySet<String>(), com.nozzleitall.printer.ext.ProfileFeatures.ofPrinter(emptySet(), p.tools, p.id))
        }
        assertEquals("every CANVAS profile is covered", com.nozzleitall.printer.ext.ProfileFeatures.CANVAS_PROFILES,
            ProfileCatalog.all.filter { "CANVAS" in it.name }.map { it.id }.toSet())
    }

    @Test fun eachSlotSlicesWithItsOwnFilamentProfile() {
        engineReady()
        val lib = FilamentLibrary.forProfile("snapmaker_u1")
        val fs = lib.first { it.name == "Snapmaker PLA Full Spectrum @U1 0.4 nozzle" }
        assertEquals("Snapmaker PLA Full Spectrum @U1", fs.family)
        assertEquals("Snapmaker PLA Basic @U1", FilamentLibrary.bestFor("snapmaker_u1", "Snapmaker", "PLA")?.name)
        val tmp = Files.createTempDirectory("fp").toFile()
        val cube = MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())
        val prof = ProfileCatalog.byId("snapmaker_u1")!!
        val paint = Array<String?>(cube.triangleCount) { k -> if (k % 2 == 0) "8" else null }
        val obj = ModelObject(1, "cube", Mesh(cube.vertices, cube.triangles, paint), Transform.translate(prof.bedW / 2.0 - 10, prof.bedD / 2.0 - 10, 0.0))
        val producer = ProjectManifest.Producer("t", "desktop", "t")
        val m = ProjectManifest("p", 1, "n", producer, producer, 1, ProjectManifest.PrinterTarget(prof.model, profileId = prof.id),
            listOf(ProjectManifest.PlateEntry(1, "Plate 1", listOf(ProjectManifest.ObjectEntry(1, "cube", 1, paintSlots = listOf(1, 2))))))
        val petg = lib.first { it.name.startsWith("Snapmaker PETG Translucent") }
        val petgTemp = FilamentLibrary.profileJson("snapmaker_u1", petg.id)!!.getJSONArray("nozzle_temperature").getString(0).toInt()
        val baseTemp = org.json.JSONObject(File(ProfileCatalog.materialize(File(tmp, "cache"), prof.id), "filament.json").readText()).getJSONArray("nozzle_temperature").getString(0).toInt()
        assertNotEquals(baseTemp, petgTemp)
        val slots = listOf(ProjectManifest.MaterialSlot(1, "PLA", colorHex = "#FFFFFF"), ProjectManifest.MaterialSlot(2, "PETG", colorHex = "#00C3FF", filamentProfile = petg.id))
        // One value per slot (Orca 2.5.0's layout): each slot's own profile's first value, the printer filament's where it has none.
        val matte = lib.first { it.name == "Snapmaker PLA Matte @U1" }
        val combined = FilamentLibrary.combine(org.json.JSONObject(File(ProfileCatalog.materialize(File(tmp, "cache"), prof.id), "filament.json").readText()),
            listOf(null, FilamentLibrary.profileJson("snapmaker_u1", matte.id)))
        assertFalse(combined.has("filament_flow_step_size"))
        assertEquals(2, combined.getJSONArray("filament_flow_ratio").length())
        assertEquals("1", combined.getJSONArray("filament_flow_ratio").get(0).toString())
        val out = SliceEngine(SliceEngine.locateEngine()!!, File(tmp, "slices").apply { mkdirs() }).slice(
            SliceRequest(Project3mf(listOf(obj), emptyMap(), m), ProfileCatalog.materialize(File(tmp, "cache"), prof.id), QualityPreset.STANDARD, false, 15, slots,
                // PETG does not print on the Cool Plate, which Orca 2.5.0 checks before it slices.
                extraOverrides = mapOf("curr_bed_type" to "Textured PEI Plate"))) { _, _ -> }
        assertTrue("$out", out is SliceOutcome.Done)
        val g = (out as SliceOutcome.Done).gcode.readText()
        // What the printer is told: each tool heated to its own filament's temperature (Snapmaker prints only the first
        // value of flow-variant settings in the G-code header, so the header can't show this).
        val heated = Regex("^M10[49] (?:T\\d+ )?S(\\d+)", RegexOption.MULTILINE).findAll(g).map { it.groupValues[1].toInt() }.toSet()
        assertTrue("slot 2 PETG temperature $petgTemp is used (base $baseTemp): $heated; ${g.lines().filter { it.startsWith("M10") || it.startsWith("T") }.take(12)}; tools=${Regex("^T(\\d+)", RegexOption.MULTILINE).findAll(g).map { it.groupValues[1] }.groupingBy { it }.eachCount()} temps=${Regex("^M10[49] .*", RegexOption.MULTILINE).findAll(g).map { it.value }.distinct().take(20).toList()}", petgTemp in heated)
        assertTrue("slot 1 keeps the printer filament's temperature: $heated", baseTemp in heated)
    }
}

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
        // Snapmaker's segmented layout: a profile declaring two flow types owns two entries of each flow-variant setting.
        val matte = lib.first { it.name == "Snapmaker PLA Matte @U1" }
        val combined = FilamentLibrary.combine(org.json.JSONObject(File(ProfileCatalog.materialize(File(tmp, "cache"), prof.id), "filament.json").readText()),
            listOf(null, FilamentLibrary.profileJson("snapmaker_u1", matte.id)))
        assertEquals(listOf("1", "2"), (0 until 2).map { combined.getJSONArray("filament_flow_step_size").getString(it) })
        assertEquals(listOf("1", "1", "0.99"), (0 until 3).map { combined.getJSONArray("filament_flow_ratio").get(it).toString() })
        val out = SliceEngine(SliceEngine.locateEngine()!!, File(tmp, "slices").apply { mkdirs() }).slice(
            SliceRequest(Project3mf(listOf(obj), emptyMap(), m), ProfileCatalog.materialize(File(tmp, "cache"), prof.id), QualityPreset.STANDARD, false, 15, slots)) { _, _ -> }
        assertTrue("$out", out is SliceOutcome.Done)
        val g = (out as SliceOutcome.Done).gcode.readText()
        // What the printer is told: each tool heated to its own filament's temperature (Snapmaker prints only the first
        // value of flow-variant settings in the G-code header, so the header can't show this).
        val heated = Regex("^M10[49] (?:T\\d+ )?S(\\d+)", RegexOption.MULTILINE).findAll(g).map { it.groupValues[1].toInt() }.toSet()
        assertTrue("slot 2's PETG temperature is used: $heated", petgTemp in heated)
        assertTrue("slot 1 keeps the printer filament's temperature: $heated", baseTemp in heated)
    }

    private val typed get() = library.map { it to "PLA" }

    @Test fun deletingAMixRenumbersTheRestLikeSnapmaker() {
        engineReady()
        var d = FullSpectrum.add(library, "", 1, 2, 50).definitions
        d = FullSpectrum.add(library, d, 2, 3, 50).definitions
        d = FullSpectrum.add(library, d, 3, 4, 50).definitions
        val r = FullSpectrum.remove(library, d, 5)
        assertEquals(mapOf(5 to 0, 6 to 5, 7 to 6), r.remap)
        assertEquals(listOf(5, 6), r.rows.filter { it.enabled }.map { it.id })
        // Clean-up after a match keeps only what's used, renumbered.
        val c = FullSpectrum.cleanup(library, d, listOf(1, 2, 7))
        assertEquals(mapOf(5 to 0, 6 to 0, 7 to 5), c.remap)
    }

    @Test fun theEditorsFourModes() {
        engineReady()
        fun dialog(vararg kv: Pair<String, Any>) = org.json.JSONObject().apply { kv.forEach { (k, v) -> put(k, if (v is List<*>) org.json.JSONArray(v) else v) } }
        assertEquals("F1 50%+F2 25%+F4 25%", FullSpectrum.save(typed, "", dialog("mode" to "cycle", "pattern" to "1124"), null).rows.last().label)
        val bad = runCatching { FullSpectrum.save(typed, "", dialog("mode" to "cycle", "pattern" to "12,x"), null) }.exceptionOrNull()
        assertTrue("${bad?.message}", bad?.message?.contains("Only digits") == true || bad?.message?.contains("Invalid characters") == true)
        assertEquals("F1 70%+F2 20%+F4 10%", FullSpectrum.save(typed, "", dialog("mode" to "ratio", "filaments" to listOf(1, 2, 4), "weights" to listOf(70, 20, 10)), null).rows.last().label)
        assertEquals("F4->F2", FullSpectrum.save(typed, "", dialog("mode" to "gradient", "filaments" to listOf(2, 4), "direction" to 1), null).rows.last().label)
        val m = FullSpectrum.matchOne(typed, "#88CC22", 15)
        assertEquals("match", m.dialog.getString("mode"))
        assertTrue(m.deltaE < 10)
        assertTrue(FullSpectrum.save(typed, "", m.dialog, null).rows.single().enabled)
        assertTrue(FullSpectrum.presets(typed, "match").isNotEmpty())
    }
}


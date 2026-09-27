package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * CANVAS multi-colour on the Elegoo Centauri Carbon: OpenCentauri's COSMOS AFC profile (AFC's tool change, never M600 or
 * old G-code), and Elegoo's own stock-firmware Centauri Carbon and Centauri Carbon 2 profiles from ElegooSlicer (every
 * filament change is Elegoo's M6211 swap-and-purge, with ElegooSlicer's start and end G-code).
 */
class ElegooCanvasTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    private val colours = listOf("#FFFFFF", "#E53935", "#1E88E5", "#FDD835")

    /** A 20 mm cube with a quarter of its triangles in each of four filaments, sliced on [profileDir]. */
    private fun sliceFourColours(prof: PrinterProfileInfo, profileDir: File, tmp: File, filamentProfiles: List<String?> = List(4) { null }, applyPreset: Boolean = true): String {
        val cube = MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())
        val paint = Array<String?>(cube.triangleCount) { k -> when (k % 4) { 1 -> "8"; 2 -> "0C"; 3 -> "1C"; else -> null } }
        val obj = ModelObject(1, "cube", Mesh(cube.vertices, cube.triangles, paint), Transform.translate(prof.bedW / 2.0 - 10, prof.bedD / 2.0 - 10, 0.0))
        val producer = ProjectManifest.Producer("t", "desktop", "t")
        val m = ProjectManifest("p", 1, "n", producer, producer, 1, ProjectManifest.PrinterTarget(prof.model, profileId = prof.id),
            listOf(ProjectManifest.PlateEntry(1, "Plate 1", listOf(ProjectManifest.ObjectEntry(1, "cube", 1, paintSlots = listOf(1, 2, 3, 4))))))
        val slots = colours.mapIndexed { i, c -> ProjectManifest.MaterialSlot(i + 1, "PLA", colorHex = c, filamentProfile = filamentProfiles[i]) }
        val out = SliceEngine(SliceEngine.locateEngine()!!, File(tmp, "slices").apply { mkdirs() }).slice(
            SliceRequest(Project3mf(listOf(obj), emptyMap(), m), profileDir, QualityPreset.STANDARD, false, 15, slots, applyPreset = applyPreset)) { _, _ -> }
        assertTrue("$out", out is SliceOutcome.Done)
        return (out as SliceOutcome.Done).gcode.readText()
    }

    /** ElegooSlicer's CANVAS G-code: M6211 swap-and-purge then T<n> on every change, its start/end blocks, no M600. */
    private fun assertElegooCanvas(g: String, tag: String) {
        val start = Regex("^M6211 A1 L200 T([0-3]) Q\\d+ R\\d+ S\\d+\\s*\\nT([0-3])\\s*$", RegexOption.MULTILINE).find(g)
        assertNotNull("ElegooSlicer's start G-code loads the first filament with M6211", start)
        assertEquals(start!!.groupValues[1], start.groupValues[2])
        // (The cooling buffer may put the new filament's fan speed, M106, between the swap and the T.)
        val changes = Regex("^M6211 T([0-3]) L([0-9.]+) M\\d+ N\\d+ Q\\d+ R\\d+ S\\d+\\s*\\n(?:M106 [^\\n]*\\n)*T([0-3])\\s*$", RegexOption.MULTILINE).findAll(g).toList()
        assertTrue("M6211 tool changes: ${changes.size}", changes.size > 10)
        assertEquals(setOf("0", "1", "2", "3"), changes.map { it.groupValues[1] }.toSet())
        // The first is the prime tower's priming change to the filament already loaded (nothing to purge, as in ElegooSlicer);
        // every real change purges the flush volume at ElegooSlicer's flush multiplier 1 (84 mm³ of 1.75 mm filament).
        assertEquals(start.groupValues[1], changes.first().groupValues[1])
        val flush = 84.0 / (Math.PI / 4 * 1.75 * 1.75)
        changes.forEachIndexed { i, c ->
            assertEquals("M6211 and T name the same slot", c.groupValues[1], c.groupValues[3])
            if (i > 0) assertEquals("purge length: ${c.value}", flush, c.groupValues[2].toDouble(), 0.01)
        }
        assertEquals("every change is Elegoo's block", changes.size, Regex("^;==========${tag}_CHANGE_FILAMENT_GCODE(_CCB)?==========", RegexOption.MULTILINE).findAll(g).count())
        assertTrue("ElegooSlicer's start G-code", g.contains(";===== ${tag}_START_GCODE"))
        // The machines' default plate (Textured PEI, as ElegooSlicer selects it) sets the bed temperature, not Cool Plate's 35.
        assertTrue("textured PEI plate", Regex("^; curr_bed_type = Textured PEI Plate", RegexOption.MULTILINE).containsMatchIn(g))
        val bed = Regex("^M190 S(\\d+)", RegexOption.MULTILINE).find(g)!!.groupValues[1].toInt()
        assertTrue("Elegoo PLAs on textured PEI are 60-65 °C: $bed", bed in 60..65)
        assertTrue("ElegooSlicer's end G-code", g.contains(";===== ${tag}_END_GCODE"))
        assertFalse("no manual M600 swaps", Regex("^M600", RegexOption.MULTILINE).containsMatchIn(g))
        assertFalse("no COSMOS/AFC macros", Regex("^(PRINT_START|PRINT_END)\\b|PURGE_LENGTH=", RegexOption.MULTILINE).containsMatchIn(g))
    }

    @Test fun elegooStockCanvasProfilesSliceFourColoursWithM6211() {
        assumeTrue("slicing engine available", SliceEngine.locateEngine() != null)
        for ((id, tag, suffix) in listOf(Triple("elegoo_centauri_carbon_canvas", "CC", "ecc"), Triple("elegoo_centauri_carbon_2_canvas", "CC2", "ecc2"))) {
            val prof = ProfileCatalog.byId(id)!!
            assertEquals(4, prof.tools)
            assertFalse("plain printer names", Regex("(?i)slicer|orca").containsMatchIn(prof.name))
            val tmp = Files.createTempDirectory(id).toFile()
            // The Web App's path: the bundled 0.4 mm pack, as ElegooSlicer's defaults (no Nozzle quality preset on top).
            assertElegooCanvas(sliceFourColours(prof, ProfileCatalog.materialize(File(tmp, "cache"), id), tmp, applyPreset = false), tag)
            // The desktop's path: the printer's family from ElegooSlicer, each slot with its own Elegoo filament preset.
            val lib = PrinterLibrary.of(id)!!
            assertEquals(listOf("0.2", "0.4", "0.6", "0.8"), lib.machines.map { it.nozzle })
            val m04 = lib.machineFor("0.4")
            assertEquals("Elegoo PLA @${tag.replace("CC", "ECC")}", lib.filaments[m04.defaultFilament]!!.name)
            assertEquals("0.20mm Standard", lib.processes[m04.defaultProcess]!!.label)
            assertEquals("each filament preset once", m04.filaments.size, m04.filaments.toSet().size)
            assertNotNull("PLA+ is its own preset", lib.filamentsFor(m04).firstOrNull { it.name == "Elegoo PLA+ @${tag.replace("CC", "ECC")}" })
            val dir = lib.materialize(File(tmp, "cache"), m04, null)
            assertEquals("the pack is the family's 0.4 mm machine", org.json.JSONObject(File(ProfileCatalog.materialize(File(tmp, "cache"), id), "machine.json").readText()).similar(
                org.json.JSONObject(File(dir, "machine.json").readText())), true)
            val perSlot = listOf("elegoo_pla_$suffix", "elegoo_pla_matte_$suffix", "elegoo_pla_silk_$suffix", "elegoo_pla_basic_$suffix")
            perSlot.forEach { assertNotNull(it, lib.filaments[it]) }
            val g = sliceFourColours(prof, dir, tmp, perSlot, applyPreset = false)
            assertElegooCanvas(g, tag)
        }
    }

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

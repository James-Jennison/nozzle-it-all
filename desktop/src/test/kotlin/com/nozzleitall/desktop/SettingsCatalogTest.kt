package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.ProfileCatalog
import com.nozzleitall.desktop.settings.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SettingsCatalogTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val catalog = SettingsCatalog.bundled

    @Test fun everyEngineSettingIsGroupedOrDeliberatelyHidden() {
        val schema = JSONObject(File(root, "schemas/slicing/settings-schema.json").readText()).getJSONArray("options")
        val layout = JSONObject(File(root, "schemas/slicing/settings-groups.json").readText())
        val hidden = layout.getJSONArray("hidden").let { a -> (0 until a.length()).map { Regex(a.getString(it)) } }
        val grouped = catalog.groups.flatMap { g -> g.settings.map { it.key } }.toSet()
        val lost = (0 until schema.length()).map { schema.getJSONObject(it) }
            .filter { it.getString("mode") != "develop" && hidden.none { h -> h.matches(it.getString("key")) } && it.getString("key") !in grouped }
            .map { it.getString("scope") + ":" + it.getString("key") }
        assertTrue("settings with no group: $lost", lost.isEmpty())
        assertEquals("each setting appears once", grouped.size, catalog.groups.sumOf { it.settings.size })
        assertTrue(catalog.all.size > 500) // 567 on the Snapmaker Orca base (engine/snapmaker/ENGINE_PIN.json)
    }

    @Test fun everyGroupIsInExactlyOneTabOfItsScope() {
        val tabbed = catalog.tabs.values.flatten().flatMap { t -> t.groups.map { g -> assertEquals(t.scope, g.scope); g.id } }
        assertEquals(catalog.groups.map { it.id }.sorted(), tabbed.sorted())
        assertEquals(listOf("Quality", "Strength", "Speed", "Supports", "Multi-material", "Others"), catalog.tabs.getValue(Scope.PROCESS).map { it.title })
    }

    @Test fun credentialsAreNeverShownAsSettings() {
        val shown = catalog.groups.flatMap { g -> g.settings.map { it.key } }
        listOf("printhost_apikey", "printhost_password", "printhost_user", "print_host").forEach { assertFalse(it, it in shown) }
    }

    @Test fun searchFindsByWordsInLabelOrHelp() {
        assertEquals("layer_height", catalog.search("layer height").first().second.key)
        assertTrue(catalog.search("gyroid").any { it.second.key == "sparse_infill_pattern" })
        assertTrue(catalog.search("zzqq nothing").isEmpty())
    }

    @Test fun valuesAreValidatedAndSerializedTheWayTheEngineReadsThem() {
        val walls = catalog.byKey.getValue("wall_loops")
        assertNull(walls.problem("3")); assertNotNull(walls.problem("2.5")); assertNotNull(walls.problem("-1")); assertNotNull(walls.problem("lots"))
        val pattern = catalog.byKey.getValue("sparse_infill_pattern")
        assertNull(pattern.problem("gyroid")); assertNotNull(pattern.problem("spaghetti"))
        val temps = catalog.byKey.getValue("nozzle_temperature") // one per filament
        assertTrue(temps.isList); assertEquals("210,215", temps.serialize("210, 215")); assertEquals("210, 215", temps.display("210,215"))
        val gcode = catalog.byKey.getValue("filament_start_gcode") // a text list: quoted, ;-separated
        val text = "M104 S\"200\"\nG92 E0"
        assertEquals(text, gcode.display(gcode.serialize(text)))
        assertEquals(listOf("a;b", "c"), SettingDef.parseStrings("\"a;b\";\"c\""))
    }

    @Test fun profileValuesUseTheEngineSyntax() {
        val dir = ProfileCatalog.materialize(Files.createTempDirectory("pv").toFile(), "snapmaker_u1")
        val v = ProfileValues.read(dir)
        assertNotNull(v["layer_height"])
        val start = catalog.byKey.getValue("filament_start_gcode")
        v["filament_start_gcode"]?.let { assertFalse(start.display(it).startsWith("\"")) }
        assertTrue(v["nozzle_diameter"]!!.split(',').size == 4) // U1: four toolheads
    }

    /** A profile file lists only what it changes; the rest are engine defaults, so no shown setting may be blank or invalid. */
    @Test fun everyShownSettingHasAValidValue() {
        val dir = ProfileCatalog.materialize(Files.createTempDirectory("pv").toFile(), "snapmaker_u1")
        val v = ProfileValues.read(dir, catalog)
        val bad = catalog.groups.flatMap { it.settings }.mapNotNull { d ->
            val value = v[d.key] ?: return@mapNotNull "${d.key}: no value"
            d.problem(d.display(value))?.let { "${d.key} = '$value': $it" }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
}

/** Changes made in All settings reach the engine exactly: a number, a choice, and a per-material G-code text. */
class SettingsReachTheEngineTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    @org.junit.Test fun overridesAreAppliedBySlicing() {
        val bin = com.nozzleitall.desktop.prepare.SliceEngine.locateEngine()
        org.junit.Assume.assumeTrue("slicing engine available", bin != null)
        val cat = SettingsCatalog.bundled
        val gcodeText = "; NOZZLE-MARKER \"quoted\"; semi\nG92 E0"
        val overrides = mapOf(
            "wall_loops" to cat.byKey.getValue("wall_loops").serialize("4"),
            "seam_position" to cat.byKey.getValue("seam_position").serialize("back"),
            "filament_start_gcode" to cat.byKey.getValue("filament_start_gcode").serialize(gcodeText))
        val tmp = Files.createTempDirectory("ov").toFile()
        val cube = com.nozzleitall.desktop.prepare.MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())
        val prof = ProfileCatalog.byId("snapmaker_u1")!!
        val obj = com.nozzleitall.project.ModelObject(1, "cube", cube, com.nozzleitall.project.Transform.translate(prof.bedW / 2.0 - 10, prof.bedD / 2.0 - 10, 0.0))
        val m = com.nozzleitall.project.ProjectManifest("p", 1, "n", com.nozzleitall.project.ProjectManifest.Producer("t", "desktop", "t"),
            com.nozzleitall.project.ProjectManifest.Producer("t", "desktop", "t"), 1, com.nozzleitall.project.ProjectManifest.PrinterTarget(prof.model, profileId = prof.id),
            listOf(com.nozzleitall.project.ProjectManifest.PlateEntry(1, "Plate 1", listOf(com.nozzleitall.project.ProjectManifest.ObjectEntry(1, "cube", 1)))))
        val out = com.nozzleitall.desktop.prepare.SliceEngine(bin!!, File(tmp, "slices").apply { mkdirs() }).slice(
            com.nozzleitall.desktop.prepare.SliceRequest(com.nozzleitall.project.Project3mf(listOf(obj), emptyMap(), m), ProfileCatalog.materialize(File(tmp, "cache"), prof.id),
                com.nozzleitall.desktop.prepare.QualityPreset.STANDARD, false, 15, listOf(com.nozzleitall.project.ProjectManifest.MaterialSlot(1, "PLA", colorHex = "#FFFFFF")),
                extraOverrides = overrides)) { _, _ -> }
        assertTrue("$out", out is com.nozzleitall.desktop.prepare.SliceOutcome.Done)
        val g = (out as com.nozzleitall.desktop.prepare.SliceOutcome.Done).gcode.readText()
        assertTrue("wall loops applied", Regex("; wall_loops = 4\\b").containsMatchIn(g))
        assertTrue("seam applied", g.contains("; seam_position = back"))
        assertTrue("material start G-code emitted verbatim", g.contains("; NOZZLE-MARKER \"quoted\"; semi"))
    }
}

/** Printers the desktop engine can't slice yet can't be chosen (engine/snapmaker/unsupported-profiles.json). */
class UnsupportedProfilesTest {
    @org.junit.Test fun newestBambuMachinesAreHiddenAndEverythingElseIsListed() {
        val hidden = setOf("bambu_h2c", "bambu_h2d", "bambu_h2d_pro", "bambu_h2s", "bambu_p2s", "bambu_x2d")
        assertEquals(hidden, ProfileCatalog.unsupported.keys)
        assertTrue(ProfileCatalog.all.none { it.id in hidden })
        assertNotNull(ProfileCatalog.byId("bambu_x1_carbon"))
        assertEquals(370, ProfileCatalog.all.size)
    }
}

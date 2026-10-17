package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.project.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

/**
 * The object tools Orca has beyond the plate ops: parts (negative volumes, modifiers, support blockers / enforcers), height ranges, variable
 * layer height (a profile and the engine's adaptive one), per-axis size and mirroring; and the settings rules the engine reports. The
 * engine tests need the slicing engine (NOZZLE_ENGINE) and skip without it.
 */
class ObjectToolsTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val cube = MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())
    private val producer = ProjectManifest.Producer("t", "desktop", "t")

    private fun item(sx: Float = 1f, sy: Float = 1f, sz: Float = 1f, rot: Float = 0f) = PrepItem(1, "cube", cube, 135f, 135f, rot).also { it.sx = sx; it.sy = sy; it.sz = sz }

    private fun request(tmp: File, entry: ProjectManifest.ObjectEntry, placement: Transform = Transform.translate(125.0, 125.0, 0.0)): SliceRequest {
        val prof = ProfileCatalog.byId("snapmaker_u1")!!
        val m = ProjectManifest("p", 1, "n", producer, producer, 1, ProjectManifest.PrinterTarget(prof.model, profileId = prof.id), listOf(ProjectManifest.PlateEntry(1, "Plate 1", listOf(entry))))
        return SliceRequest(Project3mf(listOf(ModelObject(1, "cube", cube, placement)), emptyMap(), m), ProfileCatalog.materialize(File(tmp, "cache"), prof.id), QualityPreset.STANDARD, false, 15,
            listOf(ProjectManifest.MaterialSlot(1, "PLA", colorHex = "#FFFFFF")))
    }

    private fun slice(tmp: File, entry: ProjectManifest.ObjectEntry, placement: Transform = Transform.translate(125.0, 125.0, 0.0)): SliceOutcome =
        SliceEngine(SliceEngine.locateEngine()!!, File(tmp, "slices").apply { mkdirs() }).slice(request(tmp, entry, placement)) { _, _ -> }

    private fun done(o: SliceOutcome) = (o as? SliceOutcome.Done) ?: fail("$o").let { error("unreachable") }

    @Test fun partsRangesAndLayerProfilesSurviveTheManifest() {
        val entry = ProjectManifest.ObjectEntry(1, "cube", 1,
            parts = listOf(ProjectManifest.ObjectPart("negative", "cylinder", listOf(1.0, 2.0, 3.0), listOf(4.0, 5.0, 6.0)),
                ProjectManifest.ObjectPart("modifier", "box", listOf(0.0, 0.0, 0.0), listOf(5.0, 5.0, 5.0), mapOf("sparse_infill_density" to "100%"))),
            heightRanges = listOf(ProjectManifest.HeightRange(5.0, 10.0, mapOf("layer_height" to "0.1"))), layerProfile = listOf(0.0, 0.2, 10.0, 0.1))
        val m = ProjectManifest("p", 1, "n", producer, producer, 1, null, listOf(ProjectManifest.PlateEntry(1, "Plate 1", listOf(entry))))
        val back = ProjectManifest.parse(m.toJson().toString()).plates.single().objects.single()
        assertEquals(entry.parts, back.parts); assertEquals(entry.heightRanges, back.heightRanges); assertEquals(entry.layerProfile, back.layerProfile)
        assertEquals("modifier:box:0.0,0.0,0.0:5.0,5.0,5.0:sparse_infill_density=100%", entry.parts[1].spec())
        assertEquals("negative:cylinder:1.0,2.0,3.0:4.0,5.0,6.0", entry.parts[0].spec())
        // A manifest without them (older projects, other platforms) still reads.
        val plain = ProjectManifest.parse(m.copy(plates = listOf(ProjectManifest.PlateEntry(1, "Plate 1", listOf(ProjectManifest.ObjectEntry(1, "cube", 1))))).toJson().toString())
        assertTrue(plain.plates.single().objects.single().let { it.parts.isEmpty() && it.heightRanges.isEmpty() && it.layerProfile.isEmpty() })
    }

    @Test fun sizeAndMirrorMoveTheFootprintHeightAndVolume() {
        val stretched = item(sx = 2f, sy = 0.5f, sz = 3f)
        assertEquals(40f, stretched.footprintW, 1e-3f); assertEquals(10f, stretched.footprintD, 1e-3f); assertEquals(60f, stretched.height, 1e-3f)
        assertEquals(item().volume * 3.0, stretched.volume, 1.0)
        val turned = item(sx = 2f, rot = 90f)   // the long side now runs along Y
        assertEquals(20f, turned.footprintW, 1e-3f); assertEquals(40f, turned.footprintD, 1e-3f)
        // A mirrored object takes the same room.
        val mirrored = item(sx = -2f)
        assertEquals(40f, mirrored.footprintW, 1e-3f)
        // Placement: the matrix carries each axis's length, and a mirror makes it left-handed.
        val m = item(sx = 2f, sy = 0.5f, sz = 3f).placement().m
        assertEquals(2.0, m[0], 1e-9); assertEquals(0.5, m[4], 1e-9); assertEquals(3.0, m[8], 1e-9)
        val flipped = item(sx = -1f).placement().m
        assertTrue("det < 0 for a mirror", flipped[0] * flipped[4] - flipped[1] * flipped[3] < 0)
        // A mirrored height sits on the bed through its other end.
        val z = item(sz = -1f).placement().m
        assertEquals(-1.0, z[8], 1e-9); assertEquals(bounds(cube)[5].toDouble(), z[11], 1e-6)
    }
    private fun bounds(m: com.nozzleitall.project.Mesh) = m.bounds()

    @Test fun theSliceRequestCarriesSizeMirrorPartsRangesAndProfile() {
        val tmp = Files.createTempDirectory("objreq").toFile()
        val entry = ProjectManifest.ObjectEntry(1, "cube", 1, parts = listOf(ProjectManifest.ObjectPart("blocker", "box", listOf(0.0, 0.0, 0.0), listOf(5.0, 5.0, 5.0))),
            heightRanges = listOf(ProjectManifest.HeightRange(5.0, 10.0, mapOf("layer_height" to "0.1", "wall_loops" to "4"))), layerProfile = listOf(0.0, 0.2, 10.0, 0.2, 10.0, 0.1, 20.0, 0.1))
        val text = SliceEngine.nativeRequest(request(tmp, entry, item(sx = 1f, sy = -0.5f, sz = 2f).placement()), tmp, File(tmp, "o.gcode"))
        val lines = text.lines()
        assertTrue(lines.toString(), lines.any { it.startsWith("object_scale\t1\t1\t0.5\t2") && it.endsWith("\ty") })
        assertTrue(lines.contains("object_part\t1\tblocker:box:0.0,0.0,0.0:5.0,5.0,5.0"))
        assertTrue(lines.contains("object_range\t1\t5.0\t10.0\tlayer_height\t0.1")); assertTrue(lines.contains("object_range\t1\t5.0\t10.0\twall_loops\t4"))
        assertTrue(lines.contains("object_layers\t1\t0.0,0.2,10.0,0.2,10.0,0.1,20.0,0.1"))
        // Nothing extra for a plain object.
        val plain = SliceEngine.nativeRequest(request(tmp, ProjectManifest.ObjectEntry(1, "cube", 1)), tmp, File(tmp, "o.gcode"))
        assertTrue(plain.lines().none { it.startsWith("object_scale") || it.startsWith("object_part") || it.startsWith("object_range") || it.startsWith("object_layers") })
    }

    @Test fun theEnginePrintsPartsRangesAndLayerProfiles() {
        assumeTrue("slicing engine available", SliceEngine.locateEngine() != null)
        val tmp = Files.createTempDirectory("objtools").toFile()
        val plain = done(slice(tmp, ProjectManifest.ObjectEntry(1, "cube", 1)))
        // A negative volume through the cube takes material away.
        val holed = done(slice(tmp, ProjectManifest.ObjectEntry(1, "cube", 1, parts = listOf(ProjectManifest.ObjectPart("negative", "box", listOf(0.0, 0.0, 0.0), listOf(12.0, 12.0, 40.0))))))
        assertTrue("${plain.stats.grams} -> ${holed.stats.grams}", holed.stats.grams!! < plain.stats.grams!!)
        // A modifier applies its settings inside it only: solid infill in the middle uses more filament.
        val solid = done(slice(tmp, ProjectManifest.ObjectEntry(1, "cube", 1, parts = listOf(ProjectManifest.ObjectPart("modifier", "box", listOf(0.0, 0.0, 0.0), listOf(12.0, 12.0, 40.0), mapOf("sparse_infill_density" to "100%"))))))
        assertTrue("${plain.stats.grams} -> ${solid.stats.grams}", solid.stats.grams!! > plain.stats.grams!! * 1.05)
        // Support blockers and enforcers are accepted.
        listOf("blocker", "enforcer").forEach { kind -> done(slice(tmp, ProjectManifest.ObjectEntry(1, "cube", 1, parts = listOf(ProjectManifest.ObjectPart(kind, "sphere", listOf(0.0, 0.0, 0.0), listOf(6.0, 6.0, 6.0)))))) }
        // A finer height range adds layers.
        val ranged = done(slice(tmp, ProjectManifest.ObjectEntry(1, "cube", 1, heightRanges = listOf(ProjectManifest.HeightRange(5.0, 10.0, mapOf("layer_height" to "0.1"))))))
        assertTrue("${plain.stats.layers} -> ${ranged.stats.layers}", ranged.stats.layers!! > plain.stats.layers!!)
        // So does a variable layer height profile.
        val profiled = done(slice(tmp, ProjectManifest.ObjectEntry(1, "cube", 1, layerProfile = listOf(0.0, 0.2, 10.0, 0.2, 10.0, 0.1, 20.0, 0.1))))
        assertTrue("${plain.stats.layers} -> ${profiled.stats.layers}", profiled.stats.layers!! > plain.stats.layers!!)
        // A range setting that is not an object setting is refused readably.
        val bad = slice(tmp, ProjectManifest.ObjectEntry(1, "cube", 1, heightRanges = listOf(ProjectManifest.HeightRange(5.0, 10.0, mapOf("nozzle_temperature" to "200")))))
        assertTrue("$bad", bad is SliceOutcome.Failed && "height range" in bad.message)
    }

    @Test fun theEnginePrintsASizedAndMirroredObject() {
        assumeTrue("slicing engine available", SliceEngine.locateEngine() != null)
        val tmp = Files.createTempDirectory("objsize").toFile()
        val entry = ProjectManifest.ObjectEntry(1, "cube", 1)
        val plain = done(slice(tmp, entry, item().placement()))
        // Twice as long in X and half as tall: the print is about as much material and taller layers count differ.
        val sized = item(sx = 2f, sz = 0.5f)
        val big = done(slice(tmp, entry, sized.placement()))
        assertTrue("${plain.stats.layers} -> ${big.stats.layers}", big.stats.layers!! < plain.stats.layers!!)
        // A mirrored object still prints (a left-handed placement goes to the engine as a mirror).
        val mirrored = done(slice(tmp, entry, item(sx = -1f).placement()))
        assertEquals(plain.stats.layers, mirrored.stats.layers)
        assertEquals(plain.stats.grams!!, mirrored.stats.grams!!, plain.stats.grams!! * 0.02)
    }

    @Test fun theEngineMakesAdaptiveLayers() {
        assumeTrue("slicing engine available", SliceEngine.locateEngine() != null)
        val tmp = Files.createTempDirectory("objadapt").toFile()
        // A cone: steep near the base... shallow near the tip.
        val s = 24; val h = 30f; val r = 15f
        val verts = ArrayList<Float>(); val tris = ArrayList<Int>()
        verts += listOf(0f, 0f, 0f, 0f, 0f, h)
        for (i in 0 until s) { val a = 2 * Math.PI * i / s; verts += listOf((r * Math.cos(a)).toFloat(), (r * Math.sin(a)).toFloat(), 0f) }
        for (i in 0 until s) { val a = 2 + i; val b = 2 + (i + 1) % s; tris += listOf(1, a, b); tris += listOf(0, b, a) }
        val cone = com.nozzleitall.project.Mesh(verts.toFloatArray(), tris.toIntArray())
        val dir = ProfileCatalog.materialize(File(tmp, "cache"), "snapmaker_u1")
        val files = listOf("machine.json", "process.json", "filament.json").map { File(dir, it) }
        val profile = PlateOps.adaptiveLayers(files, emptyMap(), cone, Triple(1.0, 1.0, 1.0), 0.9, tmp)
        assertTrue(profile.size >= 4 && profile.size % 2 == 0)
        val zs = profile.filterIndexed { i, _ -> i % 2 == 0 }; val hs = profile.filterIndexed { i, _ -> i % 2 == 1 }
        assertEquals(zs.sorted(), zs); assertTrue("reaches the top: $zs", zs.max() > 28.0); assertTrue(hs.all { it > 0 })
        // Taller by 2 in Z, the same shape needs to cover 60 mm.
        val tall = PlateOps.adaptiveLayers(files, emptyMap(), cone, Triple(1.0, 1.0, 2.0), 0.9, tmp)
        assertTrue(tall.filterIndexed { i, _ -> i % 2 == 0 }.max() > 58.0)
    }

    @Test fun theSettingsRulesReachTheScreens() {
        assumeTrue("slicing engine available", SliceEngine.locateEngine() != null)
        val tmp = Files.createTempDirectory("objrules").toFile()
        val dir = ProfileCatalog.materialize(File(tmp, "cache"), "snapmaker_u1")
        val files = listOf("machine.json", "filament.json", "process.json").map { File(dir, it) }
        // Setting Overrides: an own retraction length is a checked override; an unset z hop shows the printer's value; a zero length locks the others.
        val own = OptionRules.states(files, mapOf("filament_retraction_length" to "0.8", "filament_z_hop" to "nil"), scope = "filament")
        assertTrue(own.overrides.getValue("filament_retraction_length").let { it.checked && it.fieldEnabled })
        assertTrue(own.overrides.getValue("filament_z_hop").let { !it.checked && !it.fieldEnabled && it.inherited.isNotBlank() })
        val none = OptionRules.states(files, mapOf("filament_retraction_length" to "0"), scope = "filament")
        assertFalse(none.overrides.getValue("filament_z_hop").checkboxEnabled); assertTrue(none.overrides.getValue("filament_retraction_length").checkboxEnabled)
        // The IDEX tool layout: Left / Right on one gantry (a stored rear value is normalised), four corners on two.
        val one = OptionRules.states(files, mapOf("is_imex" to "1", "imex_gantry_count" to "1", "imex_tool_layout" to "rear-right"), scope = "printer")
        assertEquals(listOf("front-left" to "Left", "front-right" to "Right"), one.choices["imex_tool_layout"])
        assertEquals("front-right", one.forced["imex_tool_layout"])
        val two = OptionRules.states(files, mapOf("is_imex" to "1", "imex_gantry_count" to "2"), scope = "printer")
        assertEquals(4, two.choices.getValue("imex_tool_layout").size)
        assertTrue(OptionRules.states(files, emptyMap(), scope = "printer").choices.isEmpty())
        // The mixed sub-layer warning needs both a variable layer height and mixed sub-layers.
        val mixed = mapOf("enable_mixed_color_sublayer" to "1")
        assertTrue(OptionRules.checks(files, mixed, variableLayer = true).notices.any { it.id == "mixed_sublayer_variable_layer" })
        assertTrue(OptionRules.checks(files, mixed, variableLayer = false).notices.none { it.id == "mixed_sublayer_variable_layer" })
        assertTrue(OptionRules.checks(files, emptyMap(), variableLayer = true).notices.none { it.id == "mixed_sublayer_variable_layer" })
        // States for a process rule: no walls greys out the outer wall speed.
        assertFalse(OptionRules.states(files, mapOf("wall_loops" to "0"), scope = "process").state("outer_wall_speed").enabled)
    }

    @Test fun anObjectWithHeightRangesOrAProfileHasAVariableLayerHeight() {
        val a = item()
        assertFalse(a.hasVariableLayer)
        a.ranges += ProjectManifest.HeightRange(1.0, 2.0, mapOf("layer_height" to "0.1")); assertTrue(a.hasVariableLayer)
        val b = item(); b.layerProfile.addAll(listOf(0.0, 0.2, 10.0, 0.2)); assertFalse("two points are a flat profile", b.hasVariableLayer)
        b.layerProfile.addAll(listOf(10.0, 0.1, 20.0, 0.1)); assertTrue(b.hasVariableLayer)
        val c = item(sx = 2f).also { it.copyShapeFrom(a) }
        assertEquals(1f, c.sx); assertEquals(1, c.ranges.size)
        // Silence unused warnings for the helper used to compare magnitudes.
        assertTrue(abs(1.0) > 0)
    }
}

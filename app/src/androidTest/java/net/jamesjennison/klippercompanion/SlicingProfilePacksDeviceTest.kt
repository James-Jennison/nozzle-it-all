package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.orcaslicer.engine.NativeEngine
import java.io.File

// WO-13 Phase 4: proves the real, bundled per-printer profile packs actually work through the
// real engine, not just that their JSON resolves offline. The Centauri Carbon/COSMOS pack is the
// safety-critical one - see assets/slicer_profiles/PROVENANCE.md and FirmwareIdentity.kt.
@RunWith(AndroidJUnit4::class)
class SlicingProfilePacksDeviceTest {
    private fun sliceCube(model: SlicingPrinterModel, cosmosGeneration: CosmosProfileGeneration? = null): String {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(appContext.cacheDir, "cube.stl")
        testContext.assets.open("cube.stl").use { it.copyTo(input.outputStream()) }
        val output = File(appContext.cacheDir, "profile_pack_output_${model.name}.gcode")
        output.delete()
        val pack = slicingProfilePack(model, cosmosGeneration) ?: throw AssertionError("no profile pack for $model/$cosmosGeneration")
        val profilePaths = pack.materialize(appContext)
        NativeEngine.nativeSliceFile(input.absolutePath, output.absolutePath, profilePaths.toTypedArray(), emptyArray(), emptyArray(), 0.0, 0.0, 0.0, 1.0)
        assertTrue("expected real g-code output for $model", output.exists() && output.length() > 1000)
        return output.readText()
    }
    @Test fun centauriCarbonCosmosProfileProducesRealCosmosStartGcode() {
        val gcode = sliceCube(SlicingPrinterModel.ELEGOO_CENTAURI_CARBON, CosmosProfileGeneration.CURRENT)
        // The exact, safety-critical thing this whole data model exists to guarantee: the real
        // COSMOS macros appear in the output, and the old, incompatible M729/M8213-era sequence
        // does not (that's the sequence COSMOS 26.07.0+ rejects with a hard emergency stop).
        assertTrue("expected COSMOS's own PRINT_START macro", gcode.contains("PRINT_START"))
        assertTrue("expected COSMOS's own PRINT_END macro", gcode.contains("PRINT_END"))
        assertFalse("must not contain the old, COSMOS-incompatible M729", gcode.contains("M729"))
        assertFalse("must not contain the old, COSMOS-incompatible M8213", gcode.contains("M8213"))
    }
    // Real bug found on the Razr 2026 (owner: "I always want errors investigated"): the project editor stores
    // each model under a random UUID filename and the engine names its objects after that file, so a Klipper
    // `EXCLUDE_OBJECT_DEFINE NAME=<uuid>.stl...` line reaches GcodePreview. A UUID containing a letter followed
    // by 8+ digits (`b36836200`) used to be tokenized as the numeric word B=36836200, failing the whole sliced
    // preview with "Unsupported coordinate magnitude." Uses that exact real failing filename through the same
    // multi-object JNI path the project editor uses, then runs the real output through the real preview parser.
    @Test fun uuidNamedObjectsInCosmosExcludeObjectLinesDoNotBreakThePreviewParser() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(appContext.cacheDir, "7f08782c-8753-4a81-9941-b36836200d1c.stl")
        testContext.assets.open("cube.stl").use { it.copyTo(input.outputStream()) }
        val output = File(appContext.cacheDir, "uuid_named_cosmos.gcode")
        output.delete()
        val pack = slicingProfilePack(SlicingPrinterModel.ELEGOO_CENTAURI_CARBON, CosmosProfileGeneration.CURRENT) ?: throw AssertionError("no COSMOS profile pack")
        NativeEngine.nativeSliceMultiObject(
            arrayOf(input.absolutePath), doubleArrayOf(0.0), doubleArrayOf(0.0), doubleArrayOf(0.0), doubleArrayOf(1.0), intArrayOf(0),
            output.absolutePath, pack.materialize(appContext).toTypedArray(), emptyArray(), emptyArray(),
        )
        assertTrue("expected real g-code output", output.exists() && output.length() > 1000)
        assertTrue("test premise: the COSMOS pack must actually emit the UUID-named EXCLUDE_OBJECT line this guards",
            output.readText().contains("EXCLUDE_OBJECT_DEFINE NAME=7f08782c-8753-4a81-9941-b36836200d1c"))
        val toolpath = output.inputStream().buffered().use { GcodePreview.parse(it) }
        assertTrue("expected real extrusion segments", toolpath.segments.isNotEmpty())
    }
    // Every model in the generated catalog (scripts/bundle_vendor_profiles.py) must load and slice through the real engine;
    // a pack that fails is reported by name so one bad Bambu/Prusa profile is easy to find. COSMOS needs a firmware
    // generation and has its own tests above.
    @Test fun everyBundledCatalogModelSlicesRealGcode() {
        val failures = mutableListOf<String>()
        SlicingModelCatalog.all.filter { it.model != SlicingPrinterModel.ELEGOO_CENTAURI_CARBON }.forEach { info ->
            try {
                val gcode = sliceCube(info.model)
                if (!gcode.contains("G1")) failures += "${info.label}: no G1 moves in output"
            } catch (t: Throwable) { failures += "${info.label}: ${t.javaClass.simpleName}: ${t.message}" }
        }
        assertTrue("models that failed to slice:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
    // Custom machine (bed, origin, start/end G-code): a real slice through the real engine must use them.
    @Test fun customMachineBedAndGcodeReachTheRealEngine() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(appContext.cacheDir, "cube.stl"); testContext.assets.open("cube.stl").use { it.copyTo(input.outputStream()) }
        for ((centre, label) in listOf(false to "corner", true to "centre")) {
            val custom = CustomMachine(180.0, 160.0, 150.0, originAtCenter = centre, startGcode = "MY_CUSTOM_START_MARKER_$label\nG90\nG92 E0", endGcode = "MY_CUSTOM_END_MARKER_$label")
            val pack = slicingProfilePack(SlicingPrinterModel.GENERIC_KLIPPER, null, custom)!!
            val shape = pack.readBedShape(appContext)
            assertEquals("the bed the app reads is the custom one ($label)", if (centre) -90f else 0f, shape.points.minOf { it.first }, 0f)
            assertEquals(150f, shape.heightMm, 0f)
            val output = File(appContext.cacheDir, "custom_machine_$label.gcode"); output.delete()
            NativeEngine.nativeSliceFile(input.absolutePath, output.absolutePath, pack.materialize(appContext).toTypedArray(), emptyArray(), emptyArray(), 0.0, 0.0, 0.0, 1.0)
            val gcode = output.readText()
            assertTrue("custom start G-code is in the output ($label)", gcode.contains("MY_CUSTOM_START_MARKER_$label"))
            assertTrue("custom end G-code is in the output ($label)", gcode.contains("MY_CUSTOM_END_MARKER_$label"))
            assertFalse("the profile's own START_PRINT must be gone ($label)", gcode.contains("START_PRINT EXTRUDER_TEMP"))
        }
    }
    @Test fun snapmakerU1ProfileSlicesRealGcode() {
        val gcode = sliceCube(SlicingPrinterModel.SNAPMAKER_U1)
        assertTrue(gcode.contains("G1"))
    }
    @Test fun genericKlipperProfileSlicesRealGcode() {
        val gcode = sliceCube(SlicingPrinterModel.GENERIC_KLIPPER)
        assertTrue(gcode.contains("G1"))
    }
    @Test fun bambuGenericProfileSlicesRealGcode() {
        val gcode = sliceCube(SlicingPrinterModel.BAMBU_GENERIC)
        assertTrue(gcode.contains("G1"))
    }
    @Test fun prusaGenericProfileSlicesRealGcode() {
        val gcode = sliceCube(SlicingPrinterModel.PRUSA_GENERIC)
        assertTrue(gcode.contains("G1"))
    }

    // The Prusa XL 5T pack (flattened by scripts/flatten_orca_profile.py): five toolheads on a 360 mm bed.
    @Test fun prusaXl5tProfileSlicesRealGcodeAndIsAFiveToolMachineOnA360mmBed() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(5, toolCountFor(SlicingPrinterModel.PRUSA_XL_5T, null, appContext))
        val bed = bedShapeFor(SlicingPrinterModel.PRUSA_XL_5T, null, appContext)!!.points
        assertEquals(360f, bed.maxOf { it.first }); assertEquals(360f, bed.maxOf { it.second })
        assertTrue(sliceCube(SlicingPrinterModel.PRUSA_XL_5T).contains("G1"))
    }

    @Test fun aTwoToolPrusaXlSliceChangesToolsAndBothToolsExtrude() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        fun cube(name: String) = File(appContext.cacheDir, name).also { f -> testContext.assets.open("cube.stl").use { it.copyTo(f.outputStream()) } }
        val a = cube("xl-a.stl"); val b = cube("xl-b.stl"); val out = File(appContext.cacheDir, "xl-two-tool.gcode").also { it.delete() }
        val pack = slicingProfilePack(SlicingPrinterModel.PRUSA_XL_5T, null)!!
        val pla = BUNDLED_MATERIAL_PROFILES.first { it.id == "bundled-pla" }; val petg = BUNDLED_MATERIAL_PROFILES.first { it.id == "bundled-petg" }
        val overrides = MultiToolFilamentConfig.overridesFor(1.75, listOf(pla, petg, pla, pla, pla), pla)
        NativeEngine.nativeSliceMultiObject(arrayOf(a.absolutePath, b.absolutePath), doubleArrayOf(-30.0, 30.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0), intArrayOf(1, 2),
            out.absolutePath, pack.materialize(appContext).toTypedArray(), overrides.keys.toTypedArray(), overrides.values.toTypedArray())
        val toolpath = out.inputStream().buffered().use { GcodePreview.parse(it) }
        assertTrue("tool 2 (T1) must appear in the XL's own toolchange G-code, tools: ${toolpath.toolsUsed}", 1 in toolpath.toolsUsed)
        assertTrue(toolpath.toolChanges.size > 10)
        val stats = GcodeStatsParser.parse(out)
        assertTrue("both tools extrude: ${stats.perToolGrams}", stats.toolsUsed.containsAll(listOf(0, 1)))
        assertEquals("the engine keeps the prime tower off on independent-tool machines, even though the XL process profile enables it", false, stats.primeTower)
    }
}

package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.orcaslicer.engine.NativeEngine
import java.io.File
import java.util.zip.ZipFile

// Bambu multi-colour (AMS): two cubes on AMS slots 1 and 2 of a Bambu Lab X1 Carbon, sliced into a .gcode.3mf bundle
// through nativeSliceMultiObjectBambuBundleTools with the per-slot filament config SlicingCoordinator.sliceProject
// builds (MultiToolFilamentConfig.overridesFor + FlushVolumes). The bundle must switch AMS slots in its G-code (the
// profile's change_filament_gcode: M620 S<n>A) and list both filaments, with their colours, in slice_info.
@RunWith(AndroidJUnit4::class)
class BambuMultiColourDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun cube(name: String): File {
        val input = File(context.cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open("cube.stl").use { it.copyTo(input.outputStream()) }
        return input
    }

    private fun slice(outputName: String, tools: IntArray): File {
        val pack = slicingProfilePack(SlicingPrinterModel.BAMBU_X1_CARBON, null) ?: throw AssertionError("no bundled X1 Carbon pack")
        val slots = pack.filamentSlots ?: throw AssertionError("the X1 Carbon pack should declare its AMS slots")
        val profilePaths = pack.materialize(context)
        val pla = BUNDLED_MATERIAL_PROFILES.first { it.id == "bundled-pla" }
        val materials = listOf(pla.copy(colorHex = "#FF0000"), pla.copy(colorHex = "#00FF00")) + List(slots - 2) { null }
        val baseFilament = JSONObject(File(profilePaths[2]).readText())
        val flush = FlushVolumes.setup(JSONObject(File(profilePaths[0]).readText()), List(slots) { baseFilament })
        val overrides = MultiToolFilamentConfig.overridesFor(parseBaseFilamentDiameter(File(profilePaths[2]).readText()), materials, pla, flush)
        val a = cube("ams-a-$outputName.stl")
        val b = cube("ams-b-$outputName.stl")
        val output = File(context.cacheDir, outputName).apply { delete() }
        NativeEngine.nativeSliceMultiObjectBambuBundleTools(
            arrayOf(a.absolutePath, b.absolutePath),
            doubleArrayOf(-30.0, 30.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0), tools,
            output.absolutePath, profilePaths.toTypedArray(), overrides.keys.toTypedArray(), overrides.values.toTypedArray(),
            arrayOf("", ""), arrayOf("", ""),
        )
        assertTrue("expected a real bundle, got ${output.length()} bytes", output.exists() && output.length() > 5000)
        return output
    }

    private fun entry(bundle: File, name: String): String = ZipFile(bundle).use { zip ->
        zip.getInputStream(zip.getEntry(name) ?: throw AssertionError("no $name")).bufferedReader().readText()
    }

    private fun amsSwitches(gcode: String) = Regex("(?m)^\\s*M620 S(\\d+)A").findAll(gcode).map { it.groupValues[1] }.toSet()

    @Test fun twoObjectsOnTwoAmsSlotsSwitchSlotsAndListBothFilaments() {
        val bundle = slice("ams-two.gcode.3mf", intArrayOf(1, 2))
        val switches = amsSwitches(entry(bundle, "Metadata/plate_1.gcode"))
        assertTrue("expected AMS slots 0 and 1 in the G-code, got $switches", switches.containsAll(setOf("0", "1")))
        val sliceInfo = entry(bundle, "Metadata/slice_info.config")
        val filaments = Regex("<filament [^>]*>").findAll(sliceInfo).map { it.value }.toList()
        assertEquals("expected two used filaments in slice_info: $filaments", 2, filaments.size)
        assertTrue(filaments.toString(), filaments.any { it.contains("color=\"#FF0000\"") && it.contains("type=\"PLA\"") })
        assertTrue(filaments.toString(), filaments.any { it.contains("color=\"#00FF00\"") })
    }

    @Test fun twoObjectsOnOneSlotStayOnThatSlot() {
        val bundle = slice("ams-one.gcode.3mf", intArrayOf(1, 1))
        val switches = amsSwitches(entry(bundle, "Metadata/plate_1.gcode"))
        assertFalse("expected no switch to AMS slot 1, got $switches", switches.contains("1"))
        val filaments = Regex("<filament [^>]*>").findAll(entry(bundle, "Metadata/slice_info.config")).toList()
        assertEquals(1, filaments.size)
    }
}

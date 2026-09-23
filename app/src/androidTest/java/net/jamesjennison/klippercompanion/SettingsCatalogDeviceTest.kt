package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.orcaslicer.engine.NativeEngine
import java.io.File

// Phase 9a acceptance: every advanced control maps to a real config override the real engine accepts and
// honours. Each catalog key is sliced with every choice/boundary value on the generic Klipper pack.
@RunWith(AndroidJUnit4::class)
class SettingsCatalogDeviceTest {
    private val testContext get() = InstrumentationRegistry.getInstrumentation().context
    private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun slice(overrides: Map<String, String>): String {
        val input = File(appContext.cacheDir, "cube.stl"); testContext.assets.open("cube.stl").use { it.copyTo(input.outputStream()) }
        val out = File(appContext.cacheDir, "catalog_out.gcode").also { it.delete() }
        val pack = slicingProfilePack(SlicingPrinterModel.GENERIC_KLIPPER, null)!!
        NativeEngine.nativeResetCancel()
        NativeEngine.nativeSliceFile(input.absolutePath, out.absolutePath, pack.materialize(appContext).toTypedArray(), overrides.keys.toTypedArray(), overrides.values.toTypedArray(), 0.0, 0.0, 0.0, 1.0)
        assertTrue("no output for $overrides", out.exists() && out.length() > 1000)
        return out.readText()
    }
    private fun samples(d: SettingDef): List<String> = when (val t = d.type) {
        is SettingType.IntRange -> listOf(t.min, (t.min + t.max) / 2, t.max).map { SettingsCatalog.validate(d, it.toString())!! }
        is SettingType.Decimal -> listOf(t.min.coerceAtLeast(0.1), (t.min + t.max) / 4, t.max).map { SettingsCatalog.validate(d, it.toString())!! }
        is SettingType.Percent -> listOf("10%")
        SettingType.Toggle -> listOf("0", "1")
        is SettingType.Choice -> t.options.map { it.second }
    }
    @Test fun everyCatalogKeyIsAcceptedByTheRealEngine() {
        val failures = mutableListOf<String>()
        for (d in SettingsCatalog.all) for (v in samples(d)) {
            try { slice(mapOf(d.key to v)) } catch (e: Exception) { failures += "${d.key}=$v: ${e.message}" }
        }
        assertTrue("engine rejected catalog values:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
    @Test fun overridesChangeTheGcodeTheyClaimTo() {
        assertTrue(slice(mapOf("wall_loops" to "1")).lines().count { it.trim() == ";TYPE:Inner wall" } < slice(mapOf("wall_loops" to "4")).lines().count { it.trim() == ";TYPE:Inner wall" })
        assertNotEquals(slice(mapOf("sparse_infill_pattern" to "gyroid")), slice(mapOf("sparse_infill_pattern" to "grid")))
        assertTrue(slice(mapOf("outer_wall_speed" to "30")).contains("F1800"))
    }
}

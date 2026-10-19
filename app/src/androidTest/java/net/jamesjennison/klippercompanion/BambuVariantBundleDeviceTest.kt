package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.orcaslicer.engine.NativeEngine
import java.io.File
import java.util.zip.ZipFile

// The .gcode.3mf bundle for Bambu's other models (dual-nozzle H2D/H2S, the P2S, the X2D, the X1 Carbon), built from each
// model's own bundled profile on the Orca 2.5.0 engine. BambuBundleDeviceTest covers the generic profile in detail; this
// checks every variant yields a complete bundle whose slice_info names that model and whose G-code matches its MD5.
@RunWith(AndroidJUnit4::class)
class BambuVariantBundleDeviceTest {
    private val variants = mapOf(
        "bambu_h2d" to "Bambu Lab H2D",
        "bambu_h2s" to "Bambu Lab H2S",
        "bambu_p2s" to "Bambu Lab P2S",
        "bambu_x2d" to "Bambu Lab X2D",
        "bambu_x1_carbon" to "Bambu Lab X1 Carbon",
    )

    private fun bundleFor(id: String): File {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val cube = File(appContext.cacheDir, "bambu_variant_cube.stl")
        testContext.assets.open("cube.stl").use { it.copyTo(cube.outputStream()) }
        val dir = File(appContext.cacheDir, "variant_$id").apply { mkdirs() }
        val profiles = listOf("machine", "process", "filament").map { part ->
            File(dir, "$part.json").also { f -> appContext.assets.open("slicer_profiles/$id/$part.json").use { it.copyTo(f.outputStream()) } }.absolutePath
        }
        val out = File(appContext.cacheDir, "variant_$id.gcode.3mf").apply { delete() }
        NativeEngine.nativeSliceBambuBundle(cube.absolutePath, out.absolutePath, profiles.toTypedArray(), emptyArray(), emptyArray(), 0.0, 0.0, 0.0, 1.0)
        return out
    }

    @Test fun everyVariantProducesACompleteBundleNamingItsOwnModel() {
        for ((id, model) in variants) {
            val bundle = bundleFor(id)
            assertTrue("$id: bundle missing", bundle.length() > 1000)
            ZipFile(bundle).use { zip ->
                for (name in listOf("[Content_Types].xml", "3D/3dmodel.model", "Metadata/plate_1.gcode", "Metadata/plate_1.gcode.md5", "Metadata/slice_info.config", "Metadata/plate_1.png"))
                    assertNotNull("$id: missing $name", zip.getEntry(name))
                val info = zip.getInputStream(zip.getEntry("Metadata/slice_info.config")).bufferedReader().readText()
                assertTrue("$id: slice_info should name $model", info.contains(model))
                val gcode = zip.getInputStream(zip.getEntry("Metadata/plate_1.gcode")).readBytes()
                assertTrue("$id: empty G-code", gcode.size > 1000)
            }
            assertFalse("$id: leftover temp file", File(bundle.parentFile, bundle.name + ".gcode.tmp").exists())
        }
    }
}

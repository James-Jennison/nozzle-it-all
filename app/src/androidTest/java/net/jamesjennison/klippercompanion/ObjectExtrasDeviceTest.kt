package net.jamesjennison.klippercompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.orcaslicer.engine.NativeEngine
import java.io.File

// Phase 9d acceptance: painted strokes and modifier/blocker volumes reach the real engine and change the G-code
// the way their names promise - not merely "the call didn't throw".
@RunWith(AndroidJUnit4::class)
class ObjectExtrasDeviceTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    // 10x10x10 stem with a 24x24x4 slab on top: the slab underside is a real overhang needing support.
    private fun mushroom(): TriMesh {
        fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): FloatArray {
            val c = (0 until 8).map { i -> floatArrayOf(if (i and 1 != 0) x1 else x0, if (i and 2 != 0) y1 else y0, if (i and 4 != 0) z1 else z0) }
            fun q(a: Int, b: Int, cc: Int, d: Int) = listOf(a, b, cc, a, cc, d)
            val faces = listOf(q(0, 2, 3, 1), q(4, 5, 7, 6), q(0, 1, 5, 4), q(1, 3, 7, 5), q(3, 2, 6, 7), q(2, 0, 4, 6)).flatten()
            return faces.flatMap { c[it].toList() }.toFloatArray()
        }
        return TriMesh(box(7f, 7f, 0f, 17f, 17f, 10f) + box(0f, 0f, 10f, 24f, 24f, 14f))
    }
    private fun stl(name: String, mesh: TriMesh) = File(ctx.cacheDir, name).also { MeshEdit.writeBinaryStl(mesh, it) }
    private fun cube(): File { val f = File(ctx.cacheDir, "extras-cube.stl"); InstrumentationRegistry.getInstrumentation().context.assets.open("cube.stl").use { it.copyTo(f.outputStream()) }; return f }

    private fun slice(file: File, overrides: Map<String, String>, paint: String = "", volumes: String = ""): String {
        val out = File(ctx.cacheDir, "extras_out.gcode").also { it.delete() }
        val pack = slicingProfilePack(SlicingPrinterModel.GENERIC_KLIPPER, null)!!
        NativeEngine.nativeResetCancel()
        NativeEngine.nativeSliceMultiObjectEx(
            arrayOf(file.absolutePath), doubleArrayOf(0.0), doubleArrayOf(0.0), doubleArrayOf(0.0), doubleArrayOf(1.0), intArrayOf(0),
            out.absolutePath, pack.materialize(ctx).toTypedArray(), overrides.keys.toTypedArray(), overrides.values.toTypedArray(),
            arrayOf(paint), arrayOf(volumes),
        )
        return out.readText()
    }
    private fun count(gcode: String, type: String) = gcode.lines().count { it.trim() == ";TYPE:$type" }
    private val manualSupport = mapOf("enable_support" to "1", "support_type" to "normal(manual)")
    private val autoSupport = mapOf("enable_support" to "1", "support_type" to "normal(auto)")

    @Test fun anEnforcerStrokeUnderTheOverhangCreatesSupportWhereNoneWouldOtherwiseExist() = runBlocking<Unit> {
        val file = stl("mushroom.stl", mushroom())
        val g = MeshLoader.load(file.absolutePath)
        assertEquals("baseline: manual support with nothing painted prints no support", 0, count(slice(file, manualSupport), "Support"))
        val cx = (g.minX + g.maxX) / 2f; val cy = (g.minY + g.maxY) / 2f
        val stroke = PaintStroke(PaintKind.SUPPORT_ENFORCER, g.objectFrame(floatArrayOf(cx + 9f, cy, g.minZ - 10f)), floatArrayOf(0f, 0f, 1f), 4f)
        assertNotNull("test premise: the ray must hit the slab underside", MeshEdit.rayHit(MeshEdit.fromGeometry(g), floatArrayOf(cx + 9f, cy, g.minZ - 10f), stroke.dir))
        val painted = slice(file, manualSupport, paint = PaintCodec.encode(listOf(stroke)))
        assertTrue("painted enforcer must produce support", count(painted, "Support") > 0)
    }

    @Test fun aBlockerVolumeUnderTheSlabRemovesTheAutoSupport() = runBlocking<Unit> {
        val file = stl("mushroom_b.stl", mushroom())
        val g = MeshLoader.load(file.absolutePath)
        assertTrue("baseline: auto support under the slab", count(slice(file, autoSupport), "Support") > 0)
        val cx = (g.minX + g.maxX) / 2f; val cy = (g.minY + g.maxY) / 2f
        val blocker = ShapeVolume(VolumeKind.SUPPORT_BLOCKER, VolumeShape.BOX, g.objectFrame(floatArrayOf(cx, cy, g.minZ + 5f)), floatArrayOf(40f, 40f, 14f))
        assertEquals(0, count(slice(file, autoSupport, volumes = VolumeCodec.encode(listOf(blocker))), "Support"))
    }

    @Test fun aModifierVolumeOverridesTheSettingsInsideItsRegion() = runBlocking<Unit> {
        val file = cube(); val g = MeshLoader.load(file.absolutePath)
        val base = mapOf("wall_loops" to "1")
        val baseline = count(slice(file, base), "Inner wall") + count(slice(file, base), "Outer wall")
        val cx = (g.minX + g.maxX) / 2f; val cy = (g.minY + g.maxY) / 2f; val cz = (g.minZ + g.maxZ) / 2f
        val mod = ShapeVolume(VolumeKind.MODIFIER, VolumeShape.BOX, g.objectFrame(floatArrayOf(cx, cy, cz)), floatArrayOf(200f, 200f, 200f), mapOf("wall_loops" to "5"))
        val gcode = slice(file, base, volumes = VolumeCodec.encode(listOf(mod)))
        assertTrue("modifier wall_loops=5 must add walls (baseline $baseline)", count(gcode, "Inner wall") > 0)
    }

    @Test fun volumesLandWhereTheyAreAskedNotOffsetByTheBed() = runBlocking<Unit> {
        // A blocker only slightly bigger than the slab must remove all support; one shifted clear of it must not.
        val file = stl("mushroom_pos.stl", mushroom()); val g = MeshLoader.load(file.absolutePath)
        val cx = (g.minX + g.maxX) / 2f; val cy = (g.minY + g.maxY) / 2f
        fun blocker(dx: Float) = VolumeCodec.encode(listOf(ShapeVolume(VolumeKind.SUPPORT_BLOCKER, VolumeShape.BOX, g.objectFrame(floatArrayOf(cx + dx, cy, g.minZ + 5f)), floatArrayOf(34f, 34f, 14f))))
        assertEquals(0, count(slice(file, autoSupport, volumes = blocker(0f)), "Support"))
        assertTrue(count(slice(file, autoSupport, volumes = blocker(60f)), "Support") > 0)
    }

    @Test fun seamStrokesAreAcceptedAndChangeTheSeamPlacement() = runBlocking<Unit> {
        val file = cube(); val g = MeshLoader.load(file.absolutePath)
        val plain = slice(file, mapOf("seam_position" to "aligned"))
        val cx = (g.minX + g.maxX) / 2f; val cz = (g.minZ + g.maxZ) / 2f
        val strokes = listOf(PaintStroke(PaintKind.SEAM_ENFORCER, g.objectFrame(floatArrayOf(g.minX - 20f, g.minY + 1f, cz)), floatArrayOf(1f, 0f, 0f), 6f))
        val seamed = slice(file, mapOf("seam_position" to "aligned"), paint = PaintCodec.encode(strokes))
        assertTrue(seamed.length > 1000)
        assertNotEquals("a painted seam must move the seam", plain, seamed)
    }

    @Test fun invalidExtrasAreIgnoredNotFatal() = runBlocking<Unit> {
        val file = cube()
        val gcode = slice(file, emptyMap(), paint = "junk;9,1,2,3,4,5,6,1", volumes = "nonsense:box:0,0,0:1,1,1;modifier:box:0,0,0:0,0,0:")
        assertTrue(gcode.length > 1000)
    }
}

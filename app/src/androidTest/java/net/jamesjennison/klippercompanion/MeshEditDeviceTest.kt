package net.jamesjennison.klippercompanion

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.jamesjennison.klippercompanion.project.AppDatabase
import net.jamesjennison.klippercompanion.project.ProjectViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.orcaslicer.engine.NativeEngine
import java.io.File

// Phase 9c: mesh edits must survive the real pipeline - saved into the project, reloaded by the native mesh
// loader (what the 3D view draws) and sliced by the real engine (what prints), all agreeing on the new shape.
@RunWith(AndroidJUnit4::class)
class MeshEditDeviceTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "test-mesh-edit.db"
    private var openDb: AppDatabase? = null
    @After fun cleanup() { openDb?.close(); ctx.deleteDatabase(dbName); File(ctx.filesDir, "projects").deleteRecursively() }

    private fun slab(): TriMesh { // 2 x 10 x 10 mm plate standing on its thin edge
        fun tri(a: Int, b: Int, c: Int, d: Int) = listOf(a, b, c, a, c, d)
        val corners = (0 until 8).map { i -> floatArrayOf(if (i and 1 != 0) 2f else 0f, if (i and 2 != 0) 10f else 0f, if (i and 4 != 0) 10f else 0f) }
        val faces = listOf(tri(0, 2, 3, 1), tri(4, 5, 7, 6), tri(0, 1, 5, 4), tri(1, 3, 7, 5), tri(3, 2, 6, 7), tri(2, 0, 4, 6)).flatten()
        return TriMesh(faces.flatMap { corners[it].toList() }.toFloatArray())
    }

    private fun slicedMaxHeight(stl: File): Float {
        val out = File(ctx.cacheDir, "meshedit.gcode").also { it.delete() }
        val pack = slicingProfilePack(SlicingPrinterModel.GENERIC_KLIPPER, null)!!
        NativeEngine.nativeResetCancel()
        NativeEngine.nativeSliceFile(stl.absolutePath, out.absolutePath, pack.materialize(ctx).toTypedArray(), emptyArray(), emptyArray(), 0.0, 0.0, 0.0, 1.0)
        return out.inputStream().buffered().use { GcodePreview.parse(it) }.heights.max()
    }

    @Test fun layingAFaceFlatIsSavedReloadedAndSlicedAsTheNewShape(): Unit = runBlocking<Unit> {
        val source = File(ctx.cacheDir, "slab.stl"); MeshEdit.writeBinaryStl(slab(), source)
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName).build().also { openDb = it }
        val vm = ProjectViewModel(ctx, db.projectDao()); vm.newProject("Edit")
        val added = vm.addObject(Uri.fromFile(source))
        val before = MeshLoader.load(File(Uri.parse(added.sourceFileUri).path!!).absolutePath)
        assertEquals(10f, before.maxZ - before.minZ, 0.05f)
        val standingHeight = slicedMaxHeight(File(Uri.parse(added.sourceFileUri).path!!))
        assertEquals(10f, standingHeight, 0.3f)

        vm.replaceObjectMesh(added.id, MeshEdit.layOnFace(MeshEdit.fromGeometry(before), floatArrayOf(-1f, 0f, 0f)))
        val edited = vm.objects.value.single()
        assertNotEquals(added.sourceFileUri, edited.sourceFileUri)
        val after = MeshLoader.load(File(Uri.parse(edited.sourceFileUri).path!!).absolutePath)
        assertEquals(2f, after.maxZ - after.minZ, 0.05f); assertEquals(10f, after.maxX - after.minX, 0.05f)
        assertEquals(2f, slicedMaxHeight(File(Uri.parse(edited.sourceFileUri).path!!)), 0.3f)

        assertTrue(vm.undo()) // undo swaps back to the original file, which is still on disk
        assertEquals(added.sourceFileUri, vm.objects.value.single().sourceFileUri)
        assertTrue(File(Uri.parse(added.sourceFileUri).path!!).exists())
    }

    @Test fun mirroringMovesTheAsymmetricFeatureToTheOtherSide(): Unit = runBlocking<Unit> {
        // a right-triangle prism: the vertical face is on -X; mirrored it must end up on +X
        val prism = TriMesh(floatArrayOf(
            0f,0f,0f, 0f,10f,0f, 10f,10f,0f,  0f,0f,0f, 10f,10f,0f, 10f,0f,0f,     // bottom z=0
            0f,0f,5f, 10f,0f,5f, 10f,10f,5f,  0f,0f,5f, 10f,10f,5f, 0f,10f,5f,     // top z=5
            0f,0f,0f, 10f,0f,0f, 10f,0f,5f,  0f,0f,0f, 10f,0f,5f, 0f,0f,5f,        // y=0
            10f,0f,0f, 10f,10f,0f, 10f,10f,5f, 10f,0f,0f, 10f,10f,5f, 10f,0f,5f,   // x=10
            10f,10f,0f, 0f,10f,0f, 0f,10f,5f, 10f,10f,0f, 0f,10f,5f, 10f,10f,5f,   // y=10
            0f,10f,0f, 0f,0f,0f, 0f,0f,5f, 0f,10f,0f, 0f,0f,5f, 0f,10f,5f,         // x=0
        ))
        val mirrored = MeshEdit.mirror(prism, 0)
        val f = File(ctx.cacheDir, "mirror.stl"); MeshEdit.writeBinaryStl(mirrored, f)
        val g = MeshLoader.load(f.absolutePath)
        assertEquals(10f, g.maxX - g.minX, 0.05f); assertEquals(5f, g.maxZ - g.minZ, 0.05f)
        assertEquals(mirrored.triangleCount, g.triangleCount)
    }
}

@RunWith(AndroidJUnit4::class)
class MeshCutDeviceTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "test-mesh-cut.db"
    private var openDb: AppDatabase? = null
    @After fun cleanup() { openDb?.close(); ctx.deleteDatabase(dbName); File(ctx.filesDir, "projects").deleteRecursively() }

    private fun cube(): File { val f = File(ctx.cacheDir, "cut-cube.stl"); InstrumentationRegistry.getInstrumentation().context.assets.open("cube.stl").use { it.copyTo(f.outputStream()) }; return f }
    private fun volume(m: TriMesh): Double { var s = 0.0; for (t in 0 until m.triangleCount) { val b = t * 9; val v = m.v
        s += (v[b] * (v[b+4]*v[b+8]-v[b+5]*v[b+7]) - v[b+1] * (v[b+3]*v[b+8]-v[b+5]*v[b+6]) + v[b+2] * (v[b+3]*v[b+7]-v[b+4]*v[b+6])) / 6.0 }; return s }

    @Test fun cuttingAtMidHeightGivesTwoCappedSolidsWhoseVolumesAddUp(): Unit = runBlocking<Unit> {
        val g = MeshLoader.load(cube().absolutePath)
        val whole = MeshEdit.fromGeometry(g)
        val z = (g.minZ + g.maxZ) / 2f
        val (upper, lower) = MeshEdit.cut(whole, z)
        assertNotNull(upper); assertNotNull(lower)
        assertEquals((g.maxZ - g.minZ) / 2f, upper!!.maxAlong(2) - upper.minAlong(2), 0.05f)
        assertEquals((g.maxZ - g.minZ) / 2f, lower!!.maxAlong(2) - lower.minAlong(2), 0.05f)
        assertEquals("caps must close both halves", volume(whole), volume(upper) + volume(lower), volume(whole) * 0.01)
        assertEquals(0f, upper.minAlong(2), 1e-3f); assertEquals(0f, lower.minAlong(2), 1e-3f)
        // a plane that misses the part leaves one half empty
        val (above, below) = MeshEdit.cut(whole, g.maxZ + 5f)
        assertNull(above); assertNotNull(below)
    }

    @Test fun cutObjectReplacesOneObjectWithTwoAndUndoRestoresIt(): Unit = runBlocking<Unit> {
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName).build().also { openDb = it }
        val vm = ProjectViewModel(ctx, db.projectDao()); vm.newProject("Cut")
        val a = vm.addObject(Uri.fromFile(cube()))
        val g = MeshLoader.load(File(Uri.parse(a.sourceFileUri).path!!).absolutePath)
        val (upper, lower) = MeshEdit.cut(MeshEdit.fromGeometry(g), (g.minZ + g.maxZ) / 2f)
        val parts = vm.cutObject(a.id, lower, upper, 30f)
        assertEquals(2, parts.size); assertEquals(2, vm.objects.value.size)
        assertEquals(a.id, parts[0].id); assertEquals(a.offsetXMm + 30f, parts[1].offsetXMm)
        parts.forEach { assertTrue(File(Uri.parse(it.sourceFileUri).path!!).length() > 84) }
        assertTrue(vm.undo()); assertEquals(listOf(a.id), vm.objects.value.map { it.id }); assertEquals(a.sourceFileUri, vm.objects.value.single().sourceFileUri)
    }
}

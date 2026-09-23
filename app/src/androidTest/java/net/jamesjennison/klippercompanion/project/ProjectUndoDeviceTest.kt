package net.jamesjennison.klippercompanion.project

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.jamesjennison.klippercompanion.ModelTransform
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProjectUndoDeviceTest {
    private val dbName = "test-project-undo.db"
    private var openDb: AppDatabase? = null
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun vm(): ProjectViewModel { openDb?.close(); val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName).build(); openDb = db; return ProjectViewModel(ctx, db.projectDao()) }
    private fun cube(): Uri { val f = File(ctx.cacheDir, "undo-cube.stl"); InstrumentationRegistry.getInstrumentation().context.assets.open("cube.stl").use { it.copyTo(f.outputStream()) }; return Uri.fromFile(f) }
    @After fun cleanup() { openDb?.close(); ctx.deleteDatabase(dbName); File(ctx.filesDir, "projects").deleteRecursively() }

    @Test fun undoingARemoveRestoresTheObjectItsFileAndItsPersistedRow(): Unit = runBlocking<Unit> {
        val vm = vm(); val project = vm.newProject("U"); val a = vm.addObject(cube())
        vm.removeObject(a.id); assertTrue(vm.objects.value.isEmpty())
        assertTrue(vm.undo())
        assertEquals(listOf(a.id), vm.objects.value.map { it.id })
        assertTrue(File(Uri.parse(vm.objects.value.single().sourceFileUri).path!!).length() > 0)
        val reopened = vm().also { assertTrue(it.loadProject(project.id)) }
        assertEquals(listOf(a.id), reopened.objects.value.map { it.id })
    }

    @Test fun transformDragsCoalesceAndUndoRestoresPreDragPlacementRedoReapplies(): Unit = runBlocking<Unit> {
        val vm = vm(); vm.newProject("T"); val a = vm.addObject(cube())
        repeat(20) { vm.updateObjectTransform(a.id, ModelTransform(offsetXMm = it.toFloat(), offsetYMm = 0f, rotationZDeg = 0f, scale = 1f)) }
        assertEquals(19f, vm.objects.value.single().offsetXMm)
        assertTrue(vm.undo()); assertEquals(0f, vm.objects.value.single().offsetXMm)
        assertTrue(vm.redo()); assertEquals(19f, vm.objects.value.single().offsetXMm)
    }

    @Test fun batchArrangeIsOneUndoStepAndDuplicateSurvivesRemovingTheOriginal(): Unit = runBlocking<Unit> {
        val vm = vm(); val project = vm.newProject("B"); val a = vm.addObject(cube()); val dup = vm.duplicateObject(a.id)!!
        vm.updateTransforms(mapOf(a.id to ModelTransform(10f, 0f, 0f, 1f), dup.id to ModelTransform(-10f, 0f, 0f, 1f)))
        assertTrue(vm.undo()); assertEquals(listOf(0f, 20f), vm.objects.value.map { it.offsetXMm })
        vm.removeObject(a.id)
        val file = File(Uri.parse(vm.objects.value.single().sourceFileUri).path!!)
        assertTrue("duplicate must keep its model file after the original is removed", file.exists())
        vm().also { assertTrue(it.loadProject(project.id)); assertTrue(file.exists()) }
    }

    @Test fun orphanedFilesArePrunedOnReopenButKeptWhileUndoIsPossible(): Unit = runBlocking<Unit> {
        val v = vm(); val project = v.newProject("P"); val a = v.addObject(cube())
        val file = File(Uri.parse(a.sourceFileUri).path!!)
        v.removeObject(a.id); assertTrue("kept for undo", file.exists())
        vm().also { it.loadProject(project.id) }; assertFalse("orphan pruned on reopen", file.exists())
    }
}

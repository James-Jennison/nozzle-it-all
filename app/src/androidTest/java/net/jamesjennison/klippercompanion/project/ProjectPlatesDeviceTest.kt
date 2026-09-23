package net.jamesjennison.klippercompanion.project

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assert.assertThrows
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProjectPlatesDeviceTest {
    private val dbName = "test-project-plates.db"
    private var openDb: AppDatabase? = null
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun vm(): ProjectViewModel { openDb?.close(); val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName).build(); openDb = db; return ProjectViewModel(ctx, db.projectDao()) }
    private fun cube(): Uri { val f = File(ctx.cacheDir, "plates-cube.stl"); InstrumentationRegistry.getInstrumentation().context.assets.open("cube.stl").use { it.copyTo(f.outputStream()) }; return Uri.fromFile(f) }
    @After fun cleanup() { openDb?.close(); ctx.deleteDatabase(dbName); File(ctx.filesDir, "projects").deleteRecursively() }

    @Test fun newProjectsStartWithOnePlateAndObjectsLandOnTheActivePlate(): Unit = runBlocking<Unit> {
        val vm = vm(); vm.newProject("P")
        assertEquals(listOf("Plate 1"), vm.plates.value.map { it.name })
        val a = vm.addObject(cube())
        val second = vm.addPlate()
        assertEquals("Plate 2", second.name); assertEquals(second.id, vm.activePlateId.value)
        assertTrue("active plate is empty", vm.objects.value.isEmpty())
        val b = vm.addObject(cube())
        assertEquals(listOf(b.id), vm.objects.value.map { it.id })
        vm.selectPlate(vm.plates.value.first().id)
        assertEquals(listOf(a.id), vm.objects.value.map { it.id }); assertEquals(2, vm.allObjects.value.size)
    }

    @Test fun movingObjectsBetweenPlatesPersistsIsUndoableAndEmptyPlatesRemove(): Unit = runBlocking<Unit> {
        val v = vm(); val project = v.newProject("M"); val a = v.addObject(cube()); val p2 = v.addPlate()
        v.selectPlate(v.plates.value.first().id)
        v.moveObjectToPlate(a.id, p2.id)
        assertTrue(v.objects.value.isEmpty())
        assertFalse("plate with an object cannot be removed", v.removePlate(p2.id))
        assertTrue(v.undo()); assertEquals(listOf(a.id), v.objects.value.map { it.id })
        v.moveObjectToPlate(a.id, p2.id)
        val reopened = vm().also { assertTrue(it.loadProject(project.id)) }
        assertEquals(2, reopened.plates.value.size)
        reopened.selectPlate(p2.id); assertEquals(listOf(a.id), reopened.objects.value.map { it.id })
        reopened.moveObjectToPlate(a.id, reopened.plates.value.first().id)
        assertTrue(reopened.removePlate(p2.id)); assertEquals(1, reopened.plates.value.size)
        assertFalse("the last plate cannot be removed", reopened.removePlate(reopened.plates.value.single().id))
    }

    @Test fun legacyObjectsWithNoPlateBelongToTheFirstPlate(): Unit = runBlocking<Unit> {
        val v = vm(); val project = v.newProject("L")
        val legacy = ProjectObject(id = "legacy", projectId = project.id, sourceFileUri = "file:///x.stl", plateId = null)
        openDb!!.projectDao().upsertObjects(listOf(legacy))
        val reopened = vm().also { assertTrue(it.loadProject(project.id)) }
        assertEquals(listOf("legacy"), reopened.objects.value.map { it.id })
    }
}

@RunWith(AndroidJUnit4::class)
class ProjectArchiveDeviceTest {
    private val dbName = "test-project-archive.db"
    private var openDb: AppDatabase? = null
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun vm(): ProjectViewModel { openDb?.close(); val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName).build(); openDb = db; return ProjectViewModel(ctx, db.projectDao()) }
    private fun cube(): Uri { val f = File(ctx.cacheDir, "archive-cube.stl"); InstrumentationRegistry.getInstrumentation().context.assets.open("cube.stl").use { it.copyTo(f.outputStream()) }; return Uri.fromFile(f) }
    @After fun cleanup() { openDb?.close(); ctx.deleteDatabase(dbName); File(ctx.filesDir, "projects").deleteRecursively() }

    @Test fun exportedMultiPlateProjectImportsAsAnIndependentCopyWithRealModelFiles(): Unit = runBlocking<Unit> {
        val source = vm(); val original = source.newProject("Roundtrip")
        val a = source.addObject(cube()); source.updateObjectTransform(a.id, net.jamesjennison.klippercompanion.ModelTransform(12f, -4f, 30f, 1.5f))
        val plate2 = source.addPlate(); source.addObject(cube())
        val bytes = java.io.ByteArrayOutputStream().also { source.exportArchive(it) }.toByteArray()

        val target = vm()
        val imported = target.importArchive(java.io.ByteArrayInputStream(bytes))
        assertNotEquals(original.id, imported.id); assertEquals("Roundtrip", imported.name)
        assertEquals(listOf("Plate 1", "Plate 2"), target.plates.value.map { it.name })
        assertEquals(2, target.allObjects.value.size)
        assertTrue("no id is reused", target.allObjects.value.none { o -> o.id == a.id } && target.plates.value.none { it.id == plate2.id })
        val first = target.objects.value.single()
        assertEquals(12f, first.offsetXMm); assertEquals(-4f, first.offsetYMm); assertEquals(30f, first.rotationZDeg); assertEquals(1.5f, first.scale)
        target.allObjects.value.forEach { assertTrue(File(Uri.parse(it.sourceFileUri).path!!).length() > 0) }
        target.selectPlate(target.plates.value[1].id); assertEquals(1, target.objects.value.size)
        val reopened = vm().also { assertTrue(it.loadProject(imported.id)) }
        assertEquals(2, reopened.allObjects.value.size)
    }

    @Test fun malformedArchivesAreRejectedWithoutCreatingAProject(): Unit = runBlocking<Unit> {
        val v = vm()
        assertThrows(net.jamesjennison.klippercompanion.project.InvalidProjectArchive::class.java) { runBlocking { v.importArchive(java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3))) } }
        assertNull(v.project.value)
        assertTrue(ctx.cacheDir.listFiles()!!.none { it.name.startsWith("import-") })
    }
}

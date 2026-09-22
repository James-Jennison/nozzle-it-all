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

// Phase 1 (Consumer Slicer Plan §16): proves ProjectViewModel's Room round-trip is real, not
// just that the calls don't throw - a file-backed (not in-memory) test database, closed and
// reopened as a genuinely separate AppDatabase/ProjectViewModel instance between writes and
// reads, the closest an instrumented test gets to "survives process death" without an actual
// process restart. Also proves imported model files are really copied into this project's own
// persisted storage (ProjectFileStore), not just referenced by a URI that could vanish.
@RunWith(AndroidJUnit4::class)
class ProjectViewModelDeviceTest {
    private val dbName = "test-project-viewmodel.db"
    private var openDb: AppDatabase? = null

    private fun freshViewModel(): ProjectViewModel {
        openDb?.close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        openDb = db
        return ProjectViewModel(context, db.projectDao())
    }

    private fun cubeUri(): Uri {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val local = File(appContext.cacheDir, "project-vm-cube.stl")
        testContext.assets.open("cube.stl").use { it.copyTo(local.outputStream()) }
        return Uri.fromFile(local)
    }

    @After fun cleanup() {
        openDb?.close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(dbName)
        File(context.filesDir, "projects").deleteRecursively()
    }

    @Test fun addDuplicateAndRemoveObjectsRoundTripAcrossADatabaseReopen() = runBlocking {
        val vm1 = freshViewModel()
        val project = vm1.newProject("Test Project")
        val objectA = vm1.addObject(cubeUri())
        val objectB = vm1.addObject(cubeUri())
        assertEquals(2, vm1.objects.value.size)

        // The imported file must be a real, separate persisted copy per object - not a shared
        // reference back to the same cache file, which a later cache eviction (or a second
        // import from the same source) could silently corrupt for both objects at once.
        assertNotEquals(objectA.sourceFileUri, objectB.sourceFileUri)
        assertTrue("expected a real persisted copy of object A", File(Uri.parse(objectA.sourceFileUri).path!!).exists())
        assertTrue("expected a real persisted copy of object B", File(Uri.parse(objectB.sourceFileUri).path!!).exists())

        val duplicate = vm1.duplicateObject(objectA.id)
        assertNotNull(duplicate)
        assertEquals(objectA.sourceFileUri, duplicate!!.sourceFileUri) // same file, new placement
        assertEquals(objectA.offsetXMm + 20f, duplicate.offsetXMm)
        assertEquals(3, vm1.objects.value.size)

        vm1.updateObjectTransform(objectB.id, ModelTransform(offsetXMm = 15f, rotationZDeg = 90f, scale = 1.5f))
        vm1.removeObject(objectA.id)
        assertEquals(2, vm1.objects.value.size)
        assertFalse("expected object A's persisted file to be deleted", File(Uri.parse(objectA.sourceFileUri).path!!).exists())

        // Reopen as a genuinely separate ViewModel/database instance - proves this round-tripped
        // through real storage, not just an in-memory list this test itself is holding.
        val vm2 = freshViewModel()
        val loaded = vm2.loadProject(project.id)
        assertTrue(loaded)
        assertEquals("Test Project", vm2.project.value?.name)
        val reloadedIds = vm2.objects.value.map { it.id }.toSet()
        assertEquals(setOf(objectB.id, duplicate.id), reloadedIds)
        val reloadedB = vm2.objects.value.single { it.id == objectB.id }
        assertEquals(ModelTransform(offsetXMm = 15f, rotationZDeg = 90f, scale = 1.5f), reloadedB.transform())
        assertTrue("expected object B's persisted file to survive the reopen", File(Uri.parse(reloadedB.sourceFileUri).path!!).exists())
    }

    @Test fun deletingTheProjectRemovesItsObjectsAndPersistedFiles() = runBlocking {
        val vm = freshViewModel()
        val project = vm.newProject("Doomed Project")
        val obj = vm.addObject(cubeUri())
        val filePath = File(Uri.parse(obj.sourceFileUri).path!!)
        assertTrue(filePath.exists())

        vm.deleteProject()
        assertNull(vm.project.value)
        assertTrue(vm.objects.value.isEmpty())
        assertFalse("expected the project's persisted files to be deleted too", filePath.exists())

        val vm2 = freshViewModel()
        assertFalse("expected the project to be gone from Room too", vm2.loadProject(project.id))
    }

    @Test fun rejectsAnUnsupportedFileType() = runBlocking {
        val vm = freshViewModel()
        vm.newProject("Rejects Bad Files")
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val badFile = File(appContext.cacheDir, "not-a-model.txt")
        badFile.writeText("not a model")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { vm.addObject(Uri.fromFile(badFile)) }
        }
        assertTrue(vm.objects.value.isEmpty())
    }
}

package net.jamesjennison.klippercompanion.project

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.jamesjennison.klippercompanion.ModelTransform
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Phase 0 (Consumer Slicer Plan §16): proves the real Project/ProjectObject schema actually
// round-trips through Room on a real device, ahead of any UI writing to it yet - "empty, unused
// infrastructure" still needs to be infrastructure that works, not just code that compiles.
// In-memory database (not AppDatabase.get()'s persistent one): a real, isolated Room instance
// per test run, matching Room's own testing guidance - no cross-test state, no leftover file.
@RunWith(AndroidJUnit4::class)
class ProjectPersistenceDeviceTest {
    private lateinit var db: AppDatabase

    @Before fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After fun closeDb() { db.close() }

    @Test fun projectAndObjectsRoundTripThroughRoom() = runBlocking {
        val dao = db.projectDao()
        val project = Project(id = "p1", name = "Test Project", createdAt = 1000L, modifiedAt = 1000L)
        dao.upsertProject(project)

        val transform = ModelTransform(offsetXMm = 5f, offsetYMm = -3f, rotationZDeg = 90f, scale = 1.5f)
        val obj = ProjectObject(
            id = "o1", projectId = "p1", sourceFileUri = "content://fake/model.stl",
        ).withTransform(transform)
        dao.upsertObjects(listOf(obj))

        val loaded = dao.getProject("p1")
        assertEquals(project, loaded)

        val loadedObjects = dao.getObjectsForProject("p1")
        assertEquals(1, loadedObjects.size)
        assertEquals(transform, loadedObjects.single().transform())
    }

    @Test fun deletingProjectCascadesToItsObjects() = runBlocking {
        val dao = db.projectDao()
        dao.upsertProject(Project(id = "p1", name = "Cascade Test", createdAt = 0L, modifiedAt = 0L))
        dao.upsertObjects(listOf(ProjectObject(id = "o1", projectId = "p1", sourceFileUri = "content://fake/a.stl")))
        assertEquals(1, dao.getObjectsForProject("p1").size)

        dao.deleteProject(dao.getProject("p1")!!)

        assertEquals(0, dao.getObjectsForProject("p1").size)
        assertNull(dao.getProject("p1"))
    }

    @Test fun observeProjectsEmitsInsertedProject() = runBlocking {
        val dao = db.projectDao()
        dao.upsertProject(Project(id = "p1", name = "Flow Test", createdAt = 0L, modifiedAt = 0L))
        val projects = dao.observeProjects().first()
        assertEquals(1, projects.size)
        assertEquals("Flow Test", projects.single().name)
    }
}

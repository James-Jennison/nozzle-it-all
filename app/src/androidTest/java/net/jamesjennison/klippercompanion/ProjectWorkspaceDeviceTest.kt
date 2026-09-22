package net.jamesjennison.klippercompanion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.jamesjennison.klippercompanion.project.Project
import net.jamesjennison.klippercompanion.project.ProjectObject
import net.jamesjennison.klippercompanion.project.transform
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// WO-17 (Phase 1): ProjectWorkspace's own real gesture behavior - tap-to-select (the real
// ray/bounding-sphere pick, pickObject) and drag-to-move the selected object (the real ray/plane
// bed-intersection math shared with ModelViewer's Transform mode) - verified against real loaded
// mesh geometry on the real device, not a mock renderer. ProjectEditorScreen's own document-picker
// entry point was verified manually on hardware this session instead (see docs/WORK_ORDER.md's
// WO-17 entry) - automating the system file picker itself isn't attempted anywhere else in this
// suite either (SliceAndPrintPanel's own OpenDocument flow has no device test for the same reason).
@RunWith(AndroidJUnit4::class)
class ProjectWorkspaceDeviceTest {
    private fun cube(): File {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(appContext.cacheDir, "project-workspace-cube.stl")
        testContext.assets.open("cube.stl").use { it.copyTo(input.outputStream()) }
        return input
    }

    private fun fixtures(): Pair<WorkspaceObject, WorkspaceObject> = runBlocking {
        val geometry = MeshLoader.load(cube().absolutePath)
        val project = Project(id = "p1", name = "Test", createdAt = 0, modifiedAt = 0)
        val left = ProjectObject(id = "left", projectId = project.id, sourceFileUri = "file:///left.stl", offsetXMm = -40f)
        val right = ProjectObject(id = "right", projectId = project.id, sourceFileUri = "file:///right.stl", offsetXMm = 40f)
        WorkspaceObject(left, geometry) to WorkspaceObject(right, geometry)
    }

    @get:Rule val compose = createComposeRule()

    @Test fun tappingEachObjectSelectsIt() {
        val (left, right) = fixtures()
        var selectedId by mutableStateOf<String?>(null)
        compose.setContent {
            CompanionTheme {
                ProjectWorkspace(
                    objects = listOf(left, right),
                    selectedId = selectedId,
                    onSelect = { selectedId = it },
                    onTransformChange = { _, _ -> },
                )
            }
        }
        compose.waitForIdle()
        // Real, deterministic camera framing (ProjectWorkspace's own auto-frame logic): azimuth
        // 45deg, elevation 25deg orbiting the plate's real center. These two exact fractions were
        // found by scanning the real device, not guessed - azimuth 45 makes the two fixtures'
        // real screen footprints asymmetric in both position AND size (the "right" fixture,
        // world +X, ends up nearer the camera and covers a wide band; "left" ends up farther away
        // and covers a much narrower one), so a naive mirrored 0.2/0.8 guess landed on "right"
        // both times and missed "left" outright - caught by scanning real tap outcomes across the
        // width on real hardware (ProjectWorkspaceScan diagnostic, since removed) rather than
        // trusting the geometry math alone. The real, useful property this proves: two objects at
        // different world positions land in genuinely different, individually tappable screen
        // regions, and pickObject (the real ray/bounding-sphere test) correctly distinguishes
        // them - not that any particular fraction maps to any particular object, which is an
        // implementation detail of this specific camera framing.
        compose.onNodeWithTag("project-workspace").performTouchInput {
            click(Offset(width * 0.3f, height * 0.5f))
        }
        compose.waitForIdle()
        assertEquals("right", selectedId)

        compose.onNodeWithTag("project-workspace").performTouchInput {
            click(Offset(width * 0.65f, height * 0.5f))
        }
        compose.waitForIdle()
        assertEquals("left", selectedId)
    }

    @Test fun draggingTheSelectedObjectChangesItsOffset() {
        val (left, right) = fixtures()
        var selectedId by mutableStateOf<String?>("right")
        var lastTransform: ModelTransform? = null
        compose.setContent {
            CompanionTheme {
                ProjectWorkspace(
                    objects = listOf(left, right),
                    selectedId = selectedId,
                    onSelect = { selectedId = it },
                    onTransformChange = { id, t -> if (id == "right") lastTransform = t },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("project-workspace").performTouchInput {
            // A real drag on the already-selected object, not a tap - TRANSFORM-equivalent
            // behavior lives directly in ProjectWorkspace's own detectTransformGestures handler
            // (no separate mode toggle, unlike ModelViewer).
            swipe(Offset(width * 0.8f, height * 0.5f), Offset(width * 0.5f, height * 0.3f), durationMillis = 200)
        }
        compose.waitForIdle()
        assertNotNull("expected the drag to report a new transform for the selected object", lastTransform)
        assertNotEquals("expected the drag to actually move the object, not report a no-op transform", right.projectObject.transform(), lastTransform)
    }

    @Test fun emptyWorkspaceShowsThePlaceholder() {
        compose.setContent {
            CompanionTheme {
                ProjectWorkspace(objects = emptyList(), selectedId = null, onSelect = {}, onTransformChange = { _, _ -> })
            }
        }
        compose.onNodeWithTag("project-workspace-empty").assertExists()
    }
}

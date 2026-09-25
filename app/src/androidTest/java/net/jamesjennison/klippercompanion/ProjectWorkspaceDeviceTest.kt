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
        // Sweep the plate (a grid of taps) instead of two hand-tuned fractions: those were found on one phone's aspect
        // ratio and miss on a tablet (Pixel Tablet). Each object must be reachable by some tap.
        val hit = mutableSetOf<String>()
        for (row in listOf(0.3f, 0.4f, 0.5f, 0.6f, 0.7f)) for (i in 1..19) {
            selectedId = null
            compose.waitForIdle()
            compose.onNodeWithTag("project-workspace").performTouchInput { click(Offset(width * i / 20f, height * row)) }
            compose.waitForIdle()
            selectedId?.let { hit += it }
            if (hit.size == 2) break
        }
        assertEquals(setOf("left", "right"), hit)
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
            // behavior lives directly in ProjectWorkspace's own detectTransformGestures handler.
            // Default interactionMode (MOVE) - see interactionModeRotateTurnsAOneFingerSwipeIntoRotationInsteadOfAMove
            // below for the WO-30 follow-up ROTATE mode toggle.
            swipe(Offset(width * 0.8f, height * 0.5f), Offset(width * 0.5f, height * 0.3f), durationMillis = 200)
        }
        compose.waitForIdle()
        assertNotNull("expected the drag to report a new transform for the selected object", lastTransform)
        assertNotEquals("expected the drag to actually move the object, not report a no-op transform", right.projectObject.transform(), lastTransform)
    }

    // WO-30 follow-up (owner: "I should be able to rotate the model just by swiping around the
    // box, not having to necessarily pinch and rotate") - in ROTATE mode, the exact same kind of
    // one-finger swipe draggingTheSelectedObjectChangesItsOffset above used to move the object
    // should instead rotate it (and leave its position alone), no second finger required.
    @Test fun interactionModeRotateTurnsAOneFingerSwipeIntoRotationInsteadOfAMove() {
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
                    interactionMode = WorkspaceInteractionMode.ROTATE,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("project-workspace").performTouchInput {
            swipe(Offset(width * 0.8f, height * 0.5f), Offset(width * 0.5f, height * 0.3f), durationMillis = 200)
        }
        compose.waitForIdle()
        assertNotNull("expected the swipe to report a new transform for the selected object", lastTransform)
        assertNotEquals("expected a one-finger swipe in ROTATE mode to actually rotate the object", 0f, lastTransform!!.rotationZDeg)
        assertEquals("expected ROTATE mode to leave the object's own position alone - only MOVE mode should touch offset", right.projectObject.transform().offsetXMm, lastTransform!!.offsetXMm, 0.001f)
        assertEquals(right.projectObject.transform().offsetYMm, lastTransform!!.offsetYMm, 0.001f)
    }

    // WO-30 follow-up (owner: "Why am I unable to pinch to shrink or rotate the model?") -
    // draggingTheSelectedObjectChangesItsOffset above only ever exercised the single-finger pan
    // component of detectTransformGestures; scale/rotation had no real device coverage at all.
    @Test fun pinchOutOnTheSelectedObjectIncreasesItsScale() {
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
            val center = Offset(width * 0.5f, height * 0.5f)
            pinch(center - Offset(30f, 0f), center - Offset(140f, 0f), center + Offset(30f, 0f), center + Offset(140f, 0f), durationMillis = 300)
        }
        compose.waitForIdle()
        assertNotNull("expected the pinch to report a new transform for the selected object", lastTransform)
        // Real bug this test is specifically built to catch (owner-reported: "it allows me to
        // pinch momentarily then jumps back to full size"): detectTransformGestures reports each
        // callback's zoom as incremental-since-the-last-callback, not cumulative-since-gesture-
        // start. A version of the handler that (re-)reads its "current" scale from this
        // composable's own possibly-stale `objects` parameter on every callback - instead of
        // accumulating locally - ends up reporting only the single most recent, often tiny,
        // increment (finger separation here grows from 60px to 280px, a ~4.7x ratio; the last of
        // ~15-30 synthetic steps alone is only a few percent) rather than the real cumulative
        // ~4.7x. A loose "not exactly 1.0" check can't tell these apart - both are technically
        // "not 1.0" - so this asserts the real, much larger, cumulative figure specifically.
        assertTrue("expected the full cumulative pinch (~4.7x finger-distance growth) to be reflected in scale, not just its last tiny increment - got ${lastTransform!!.scale}", lastTransform!!.scale > 2f)
    }

    @Test fun twoFingerTwistOnTheSelectedObjectChangesItsRotation() {
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
            val center = Offset(width * 0.5f, height * 0.5f)
            val r = 100f
            down(0, center + Offset(r, 0f))
            down(1, center + Offset(-r, 0f))
            val steps = 10
            for (i in 1..steps) {
                val angle = (Math.PI / 2) * i / steps
                val cosA = kotlin.math.cos(angle).toFloat(); val sinA = kotlin.math.sin(angle).toFloat()
                moveTo(0, center + Offset(r * cosA, r * sinA))
                moveTo(1, center + Offset(-r * cosA, -r * sinA))
            }
            up(0); up(1)
        }
        compose.waitForIdle()
        assertNotNull("expected the twist to report a new transform for the selected object", lastTransform)
        // Same class of real bug as the pinch test above (owner-reported: "attempted rotation has
        // no effect") - a handler that re-reads a possibly-stale base rotation each callback
        // instead of accumulating locally would report only the last of 10 ~9-degree increments
        // (comfortably clearing a loose "not exactly 0" check) rather than the real cumulative
        // ~90 degrees, so this asserts the real cumulative figure specifically.
        // abs(): this test's own job is proving the cumulative-magnitude bug stays fixed, not
        // pinning down which sign is visually "correct" - that's a separate, real, owner-verified
        // fix (rotationZDeg = current - rotation, not + rotation) with its own comment at the
        // call site in ProjectWorkspace.kt.
        assertTrue("expected the full cumulative twist (~90 degrees) to be reflected in rotation, not just its last tiny increment - got ${lastTransform!!.rotationZDeg}", kotlin.math.abs(lastTransform!!.rotationZDeg) > 45f)
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

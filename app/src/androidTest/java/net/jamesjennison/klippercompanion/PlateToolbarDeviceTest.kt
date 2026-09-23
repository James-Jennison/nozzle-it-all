package net.jamesjennison.klippercompanion

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.jamesjennison.klippercompanion.project.AppDatabase
import net.jamesjennison.klippercompanion.project.ProjectViewModel
import net.jamesjennison.klippercompanion.project.transform
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// WO-30 (owner request: "Desktop-like power adapted to mobile" - a real EasyPrint-style plate
// toolbar): device coverage for ProjectEditorScreen's new left-hand toolbar (Duplicate/Hide/
// Reset/Layout), the "Models N/N" switcher, and the live dimensions readout, all added on top of
// the plate this app already persists in its real singleton AppDatabase (same seeding discipline
// as ProjectEditorScreenDeviceTest.kt - no separate test database exists for this screen).
@RunWith(AndroidJUnit4::class)
class PlateToolbarDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val createdProjectIds = mutableListOf<String>()

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun cubeUri(name: String): Uri {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val local = File(context().cacheDir, name)
        testContext.assets.open("cube.stl").use { it.copyTo(local.outputStream()) }
        return Uri.fromFile(local)
    }

    private fun seedProject(name: String, objectCount: Int): Pair<String, List<String>> = runBlocking {
        val vm = ProjectViewModel(context(), AppDatabase.get(context()).projectDao())
        val project = vm.newProject(name)
        val ids = (1..objectCount).map { i -> vm.addObject(cubeUri("$name-$i.stl")).id }
        createdProjectIds += project.id
        project.id to ids
    }

    @After fun cleanup() = runBlocking {
        val vm = ProjectViewModel(context(), AppDatabase.get(context()).projectDao())
        createdProjectIds.forEach { id -> if (vm.loadProject(id)) vm.deleteProject() }
        createdProjectIds.clear()
    }

    private fun openScreen(projectId: String) {
        val address = "http://klipper-toolbar.local/"
        val state = ScreenState(address = address, connected = true, profiles = listOf(PrinterProfile(address, "Klipper", kind = PrinterKind.GENERIC_KLIPPER, slicingModel = SlicingPrinterModel.GENERIC_KLIPPER)))
        compose.setContent { CompanionTheme { ProjectEditorScreen(projectId, null, state, { _, _ -> }, {}) } }
    }

    @Test fun duplicateButtonAddsARealSecondObjectFromTheSelectedOne() {
        val (projectId, objectIds) = seedProject("DuplicateProject", 1)
        openScreen(projectId)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-object-${objectIds[0]}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-object-${objectIds[0]}").performScrollTo()
        compose.onNodeWithTag("project-object-${objectIds[0]}").onChildren().filterToOne(hasText("Select")).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-model-index").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-duplicate-object").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Models", substring = true).fetchSemanticsNodes().any { node -> node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.any { it.text.contains("/2") } } }
    }

    @Test fun platesCanBeAddedObjectsMovedBetweenThemAndTheSliceButtonNamesThePlate() {
        val (projectId, objectIds) = seedProject("PlatesProject", 2)
        openScreen(projectId)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-object-${objectIds[0]}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-plate-add").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-plate-1").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-object-${objectIds[0]}").fetchSemanticsNodes().isEmpty() } // new plate is empty
        compose.onNodeWithTag("project-plate-0").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-object-${objectIds[0]}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-object-${objectIds[0]}").performScrollTo()
        compose.onNodeWithTag("project-object-${objectIds[0]}").onChildren().filterToOne(hasText("Select")).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Selected").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("project-move-to-plate").onChildren().onFirst().assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("project-move-to-plate").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-move-to-Plate 2", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-move-to-Plate 2", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-object-${objectIds[0]}").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("project-plate-1").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-object-${objectIds[0]}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-slice").assertTextContains("Slice Plate 2", substring = true)
    }

    @Test fun editToolsApplyToTheSelectedObjectAndModesShowGuidance() {
        val (projectId, objectIds) = seedProject("ToolsProject", 1)
        openScreen(projectId)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-object-${objectIds[0]}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-object-${objectIds[0]}").performScrollTo()
        compose.onNodeWithTag("project-object-${objectIds[0]}").onChildren().filterToOne(hasText("Select")).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Selected").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("project-tool-orient").onChildren().onFirst().assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("project-tool-mirror-x").performScrollTo().performClick()
        compose.waitUntil(8000) { compose.onAllNodesWithTag("project-tool-message").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-tool-message").assertTextContains("Mirror X applied", substring = true)
        compose.onNodeWithTag("project-tool-measure").performScrollTo().performClick()
        compose.onNodeWithTag("project-tool-message").assertTextContains("Tap point A", substring = true)
        compose.onNodeWithTag("project-tool-face").performScrollTo().performClick()
        compose.onNodeWithTag("project-tool-message").assertTextContains("Tap the face", substring = true)
        compose.onNodeWithTag("project-tool-face").performClick() // toggles off again
        compose.waitUntil(3000) { compose.onAllNodesWithTag("project-tool-message").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun cutDialogSplitsTheSelectedObjectIntoTwoParts() {
        val (projectId, objectIds) = seedProject("CutUiProject", 1)
        openScreen(projectId)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-object-${objectIds[0]}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-object-${objectIds[0]}").performScrollTo()
        compose.onNodeWithTag("project-object-${objectIds[0]}").onChildren().filterToOne(hasText("Select")).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Selected").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(8000) { runCatching { compose.onNodeWithTag("project-tool-cut").onChildren().onFirst().assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("project-tool-cut").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-cut-apply", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-cut-readout", useUnmergedTree = true).assertTextContains("Cut at", substring = true)
        compose.onNodeWithTag("project-cut-apply", useUnmergedTree = true).performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("Cut into 2", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun dbObject(projectId: String, objectId: String) = runBlocking {
        val vm = ProjectViewModel(context(), AppDatabase.get(context()).projectDao())
        vm.loadProject(projectId); vm.allObjects.value.first { it.id == objectId }
    }

    @Test fun paintModeDragsPaintStrokesOntoTheSelectedModelAndClearRemovesThem() {
        val (projectId, objectIds) = seedProject("PaintUiProject", 1)
        openScreen(projectId)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-object-${objectIds[0]}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-object-${objectIds[0]}").performScrollTo()
        compose.onNodeWithTag("project-object-${objectIds[0]}").onChildren().filterToOne(hasText("Select")).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Selected").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(8000) { runCatching { compose.onNodeWithTag("project-tool-paint").onChildren().onFirst().assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("project-tool-paint").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-paint-controls").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-paint-kind-support_blocker").performScrollTo().performClick()
        compose.onNodeWithTag("project-workspace").performScrollTo().performTouchInput {
            swipe(Offset(width * 0.42f, height * 0.5f), Offset(width * 0.58f, height * 0.55f), durationMillis = 400)
        }
        var strokes = emptyList<PaintStroke>()
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline && strokes.isEmpty()) { Thread.sleep(200); strokes = PaintCodec.decode(dbObject(projectId, objectIds[0]).paintJson) }
        assertTrue("expected the drag to store paint strokes", strokes.isNotEmpty())
        assertTrue(strokes.all { it.kind == PaintKind.SUPPORT_BLOCKER })
        compose.onNodeWithTag("project-paint-clear").performScrollTo().performClick()
        val cleared = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < cleared && dbObject(projectId, objectIds[0]).paintJson != null) Thread.sleep(200)
        assertNull(dbObject(projectId, objectIds[0]).paintJson)
    }

    @Test fun regionsCanBeAddedEditedAndDeletedOnTheSelectedModel() {
        val (projectId, objectIds) = seedProject("RegionUiProject", 1)
        openScreen(projectId)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-object-${objectIds[0]}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-object-${objectIds[0]}").performScrollTo()
        compose.onNodeWithTag("project-object-${objectIds[0]}").onChildren().filterToOne(hasText("Select")).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Selected").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(8000) { compose.onAllNodesWithTag("project-region-add").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-region-add").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("region-save", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("region-save", useUnmergedTree = true).assertIsNotEnabled() // a modifier needs at least one setting
        compose.onNodeWithTag("region-infill", useUnmergedTree = true).performTextInput("80")
        compose.onNodeWithTag("region-sx", useUnmergedTree = true).performTextClearance(); compose.onNodeWithTag("region-sx", useUnmergedTree = true).performTextInput("25")
        compose.onNodeWithTag("region-save", useUnmergedTree = true).assertIsEnabled().performClick()
        compose.waitUntil(8000) { compose.onAllNodesWithTag("project-region-0").fetchSemanticsNodes().isNotEmpty() }
        val volumes = VolumeCodec.decode(dbObject(projectId, objectIds[0]).volumesJson)
        assertEquals(1, volumes.size); assertEquals(25f, volumes[0].size[0]); assertEquals("80%", volumes[0].overrides["sparse_infill_density"])
        compose.onNodeWithTag("project-region-delete-0").performScrollTo().performClick()
        compose.waitUntil(8000) { compose.onAllNodesWithTag("project-region-0").fetchSemanticsNodes().isEmpty() }
        assertNull(dbObject(projectId, objectIds[0]).volumesJson)
    }

    @Test fun undoAndRedoButtonsRestoreARemovedObject() {
        val (projectId, objectIds) = seedProject("UndoProject", 2)
        openScreen(projectId)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-object-${objectIds[0]}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-undo").performScrollTo().onChildren().onFirst().assertIsNotEnabled()
        compose.onNodeWithTag("project-object-${objectIds[0]}").performScrollTo()
        compose.onNodeWithTag("project-object-${objectIds[0]}").onChildren().filterToOne(hasText("Select")).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Selected").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-remove-object").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-object-${objectIds[0]}").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("project-undo").onChildren().onFirst().assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("project-undo").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-object-${objectIds[0]}").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("project-redo").onChildren().onFirst().assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("project-redo").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-object-${objectIds[0]}").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun hideRemovesFromWorkspaceAndShowBringsItBack() {
        val (projectId, objectIds) = seedProject("HideProject", 2)
        openScreen(projectId)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-object-${objectIds[0]}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-object-${objectIds[0]}").performScrollTo()
        compose.onNodeWithTag("project-object-${objectIds[0]}").onChildren().filterToOne(hasText("Select")).performClick()
        // Real race, not just a slow render: "Hide" is disabled until selectedId actually
        // propagates, and a click on a disabled IconButton is silently discarded - no amount of
        // waiting afterward recovers it, so this waits for the row's own "Selected" text (proof
        // selectedId really reached this composition) before touching the toolbar at all.
        compose.waitUntil(5000) { compose.onAllNodesWithText("Selected").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-hide-object").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-hide-object").performScrollTo().performClick()
        // Hiding the selected object clears the selection and drops it from the plate's own
        // visible count - "Models 1/1" (only the untouched second object remains selectable).
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-object-toggle-hidden-${objectIds[0]}").fetchSemanticsNodes().any { node -> node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.any { it.text == "Show" } } }
        compose.onNodeWithTag("project-object-toggle-hidden-${objectIds[0]}").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-object-toggle-hidden-${objectIds[0]}").fetchSemanticsNodes().any { node -> node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.any { it.text == "Hide" } } }
    }

    @Test fun modelSwitcherCyclesSelectionAcrossObjects() {
        val (projectId, objectIds) = seedProject("SwitcherProject", 2)
        openScreen(projectId)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-object-${objectIds[0]}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-object-${objectIds[0]}").performScrollTo()
        compose.onNodeWithTag("project-object-${objectIds[0]}").onChildren().filterToOne(hasText("Select")).performClick()
        // Real race: the switcher's own "Models N/2" index is derived from selectedId - clicking
        // Next before selectedId has actually propagated reads a stale/absent selection
        // (currentIndex == -1), which Next's own "already at the end, wrap to 0" branch turns
        // into a same-object no-op, silently discarding the click's intended effect. Waiting for
        // the row's own "Selected" text first (proof selectedId really landed) avoids that.
        compose.waitUntil(5000) { compose.onAllNodesWithText("Selected").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-model-index").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-model-next").performScrollTo().performClick()
        compose.onNodeWithTag("project-model-index").assertTextContains("2/2", substring = true)
        compose.onNodeWithTag("project-model-next").performScrollTo().performClick()
        compose.onNodeWithTag("project-model-index").assertTextContains("1/2", substring = true)
    }

    @Test fun resetButtonRestoresTheObjectsIdentityTransform() {
        val (projectId, objectIds) = seedProject("ResetProject", 1)
        openScreen(projectId)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-object-${objectIds[0]}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-object-${objectIds[0]}").performScrollTo()
        compose.onNodeWithTag("project-object-${objectIds[0]}").onChildren().filterToOne(hasText("Select")).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-workspace").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-workspace").performScrollTo().performTouchInput {
            swipe(Offset(width * 0.8f, height * 0.5f), Offset(width * 0.5f, height * 0.3f), durationMillis = 200)
        }
        compose.waitForIdle()
        val moved = runBlocking {
            val vm = ProjectViewModel(context(), AppDatabase.get(context()).projectDao())
            vm.loadProject(projectId)
            vm.objects.value.first { it.id == objectIds[0] }.transform()
        }
        assertNotEquals("expected the drag to actually move the object first", ModelTransform(), moved)
        compose.onNodeWithTag("project-reset-object").performScrollTo().performClick()
        // Real async write (ProjectViewModel.updateObjectTransform -> Room, via scope.launch) -
        // compose.waitForIdle() only settles the UI thread, not this background coroutine, so a
        // single immediate DB read can race it (same real gotcha WO-29's own device test hit).
        // Polls instead of a single read.
        var reset = ModelTransform(offsetXMm = Float.NaN)
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            reset = runBlocking {
                val vm = ProjectViewModel(context(), AppDatabase.get(context()).projectDao())
                vm.loadProject(projectId)
                vm.objects.value.first { it.id == objectIds[0] }.transform()
            }
            if (reset == ModelTransform()) break
            Thread.sleep(200)
        }
        assertEquals("expected Reset to restore the identity transform", ModelTransform(), reset)
    }
}

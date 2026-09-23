package net.jamesjennison.klippercompanion

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.jamesjennison.klippercompanion.project.AppDatabase
import net.jamesjennison.klippercompanion.project.ProjectViewModel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// Phase 8 follow-up (§11, §16, WO-28's own disclosed gap): the first real Compose UI coverage
// for ProjectEditorScreen's per-object material+tool assignment picker - WO-28 shipped this UI
// backed only by a unit-tested pure function (multiToolSliceInputsFor), with no device test of
// the screen itself (this screen had none before this entry). Seeds real projects into the
// screen's own real singleton AppDatabase (ProjectEditorScreen.kt itself calls
// AppDatabase.get(context.applicationContext) with no injection point - there is no separate
// test database to use here, matching ProjectViewModelDeviceTest's own real-Room discipline but
// against the actual production singleton), then renders the real screen against them.
@RunWith(AndroidJUnit4::class)
class ProjectEditorScreenDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val createdProjectIds = mutableListOf<String>()

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun cubeUri(name: String): Uri {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val local = File(context().cacheDir, name)
        testContext.assets.open("cube.stl").use { it.copyTo(local.outputStream()) }
        return Uri.fromFile(local)
    }

    // Seeds a real project with two real objects directly into the screen's own real database -
    // the same ProjectViewModel/AppDatabase.get() pair ProjectEditorScreen itself uses, so what
    // this test writes is exactly what the screen will read. Returns the real object ids too, so
    // tests can target a specific row's own testTag directly rather than relying on Compose
    // semantics-tree traversal order for an ambiguous "onFirst() of several same-text nodes".
    private fun seedProject(name: String): Pair<String, List<String>> = runBlocking {
        val vm = ProjectViewModel(context(), AppDatabase.get(context()).projectDao())
        val project = vm.newProject(name)
        val a = vm.addObject(cubeUri("$name-a.stl"))
        val b = vm.addObject(cubeUri("$name-b.stl"))
        createdProjectIds += project.id
        project.id to listOf(a.id, b.id)
    }

    @After fun cleanup() = runBlocking {
        val vm = ProjectViewModel(context(), AppDatabase.get(context()).projectDao())
        createdProjectIds.forEach { id -> if (vm.loadProject(id)) vm.deleteProject() }
        createdProjectIds.clear()
    }

    @Test fun controlSingleToolMaterialDialogOpensOnClick() {
        val (projectId, _) = seedProject("ControlProject")
        val address = "http://klipper-control.local/"
        val state = ScreenState(address = address, connected = true, profiles = listOf(PrinterProfile(address, "Klipper", kind = PrinterKind.GENERIC_KLIPPER, slicingModel = SlicingPrinterModel.GENERIC_KLIPPER)))
        compose.setContent { CompanionTheme { ProjectEditorScreen(projectId, null, state, { _, _ -> }, {}) } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-choose-material").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-choose-material").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Choose a material").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Choose a material").assertExists()
    }

    @Test fun singleToolTargetShowsOnlyTheSharedMaterialPickerNoPerObjectAssign() {
        val (projectId, _) = seedProject("SingleToolProject")
        val address = "http://klipper.local/"
        val state = ScreenState(address = address, connected = true, profiles = listOf(PrinterProfile(address, "Klipper", kind = PrinterKind.GENERIC_KLIPPER, slicingModel = SlicingPrinterModel.GENERIC_KLIPPER)))
        compose.setContent { CompanionTheme { ProjectEditorScreen(projectId, null, state, { _, _ -> }, {}) } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-choose-material").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-choose-material").assertExists()
        compose.onAllNodesWithTag("project-multitool-hint").assertCountEquals(0)
    }

    @Test fun multiToolTargetShowsPerObjectAssignInsteadOfTheSharedPicker() {
        val (projectId, _) = seedProject("MultiToolProject")
        val address = "http://u1.local/"
        val state = ScreenState(address = address, connected = true, profiles = listOf(PrinterProfile(address, "U1", kind = PrinterKind.SNAPMAKER_U1_PAXX, slicingModel = SlicingPrinterModel.SNAPMAKER_U1)))
        compose.setContent { CompanionTheme { ProjectEditorScreen(projectId, null, state, { _, _ -> }, {}) } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-multitool-hint").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("project-choose-material").assertCountEquals(0)
        // Every real object on the plate gets its own real "Assign" control, not just the first.
        compose.onAllNodesWithText("Assign").assertCountEquals(2)
    }

    @Test fun assigningAToolAndMaterialUpdatesThatObjectsOwnRowNotTheOthers() {
        val (projectId, objectIds) = seedProject("AssignProject")
        val firstObjectId = objectIds.first()
        val address = "http://u1-assign.local/"
        val state = ScreenState(address = address, connected = true, profiles = listOf(PrinterProfile(address, "U1", kind = PrinterKind.SNAPMAKER_U1_PAXX, slicingModel = SlicingPrinterModel.SNAPMAKER_U1)))
        compose.setContent { CompanionTheme { ProjectEditorScreen(projectId, null, state, { _, _ -> }, {}) } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-object-assign-$firstObjectId").fetchSemanticsNodes().isNotEmpty() }

        // Both objects start on the real default (tool 1, no material).
        compose.onAllNodesWithText("No material · Tool 1", substring = true).assertCountEquals(2)

        compose.onNodeWithTag("project-object-assign-$firstObjectId").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Assign material and tool", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-object-tool-3", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // The dialog's tool-slot row and material buttons can sit below the fold too, exactly
        // like the row-level "Assign" button above - performScrollTo() is required here as well.
        compose.onNodeWithTag("project-object-tool-3", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("object-material-bundled-petg", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("object-material-bundled-petg", useUnmergedTree = true).performScrollTo().performClick()

        // Only the assigned object's own row changed - a real, isolated per-object update, not a
        // project-wide side effect leaking onto the second, untouched object. Real async write
        // (ProjectViewModel.setObjectMaterial -> Room, via scope.launch) - a longer window than
        // the other, purely-in-memory UI assertions in this file.
        compose.waitUntil(10000) { compose.onAllNodesWithText("PETG · Tool 3", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("No material · Tool 1", substring = true).assertCountEquals(1)
    }
}

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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// Phase 8 close-out: family model, compatibility warnings, prime-tower control, and the review's toolchange/purge stats
// and per-tool preview - all through the real editor and a real two-tool Snapmaker U1 slice.
@RunWith(AndroidJUnit4::class)
class MultiMaterialDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val created = mutableListOf<String>()
    private fun pla() = BUNDLED_MATERIAL_PROFILES.first { it.id == "bundled-pla" }
    private fun abs() = BUNDLED_MATERIAL_PROFILES.first { it.id == "bundled-abs" }
    @After fun cleanup() = runBlocking { val vm = ProjectViewModel(ctx, AppDatabase.get(ctx).projectDao()); created.forEach { if (vm.loadProject(it)) vm.deleteProject() }; created.clear() }

    private fun seed(name: String, second: MaterialProfile = abs()): String = runBlocking {
        fun cube(n: String) = File(ctx.cacheDir, n).also { f -> InstrumentationRegistry.getInstrumentation().context.assets.open("cube.stl").use { it.copyTo(f.outputStream()) } }
        val vm = ProjectViewModel(ctx, AppDatabase.get(ctx).projectDao())
        val project = vm.newProject(name); created += project.id
        val a = vm.addObject(Uri.fromFile(cube("mm-a.stl"))); val b = vm.addObject(Uri.fromFile(cube("mm-b.stl")))
        vm.updateObjectTransform(a.id, ModelTransform(-30f, 0f, 0f, 1f)); vm.updateObjectTransform(b.id, ModelTransform(30f, 0f, 0f, 1f))
        vm.setObjectMaterial(a.id, pla(), 1); vm.setObjectMaterial(b.id, second, 2)
        project.id
    }
    private fun open(projectId: String) {
        val address = "http://u1-mm.local/"
        val state = ScreenState(address = address, connected = true, profiles = listOf(PrinterProfile(address, "U1", kind = PrinterKind.SNAPMAKER_U1_PAXX, slicingModel = SlicingPrinterModel.SNAPMAKER_U1)))
        compose.setContent { CompanionTheme { ProjectEditorScreen(projectId, null, state, { _, _ -> }, {}) } }
    }
    private fun waitReview() = compose.waitUntil(180000) { compose.onAllNodesWithTag("project-slice-stats").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag("project-slice-error").fetchSemanticsNodes().isNotEmpty() }

    @Test fun theEditorNamesTheMachineFamilyAndWarnsAboutMixingPlaAndAbs() {
        open(seed("MMWarn"))
        compose.waitUntil(10000) { compose.onAllNodesWithTag("project-multimaterial-family").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-multimaterial-family").performScrollTo().assertTextContains("Independent tools")
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-multimaterial-warning-0").fetchSemanticsNodes().isNotEmpty() }
        val warnings = (0..3).filter { compose.onAllNodesWithTag("project-multimaterial-warning-$it").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("PLA with ABS must raise at least the bonding and temperature warnings, got ${warnings.size}", warnings.size >= 2)
    }

    @Test fun compatibleMaterialsRaiseNoWarnings() {
        open(seed("MMOk", second = BUNDLED_MATERIAL_PROFILES.first { it.id == "bundled-pla" }.copy(id = "bundled-pla2", displayName = "PLA 2")))
        compose.waitUntil(10000) { compose.onAllNodesWithTag("project-multimaterial-family").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("project-multimaterial-warning-0").assertCountEquals(0)
    }

    @Test fun aRealTwoToolSliceShowsToolchangesPerToolUsageAndAPurgeNoteWithoutAPrimeTower() {
        open(seed("MMSlice"))
        compose.waitUntil(10000) { compose.onAllNodesWithTag("project-slice").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("project-slice").assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("project-slice").performClick()
        waitReview()
        compose.onAllNodesWithTag("project-slice-error").assertCountEquals(0)
        compose.onNodeWithTag("project-slice-toolchanges").assertTextContains("Toolchanges:", substring = true)
        val changes = compose.onNodeWithTag("project-slice-toolchanges").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text.substringAfter(": ").trim().toInt()
        assertTrue("two tools alternating layer by layer must change tool many times, got $changes", changes > 10)
        compose.onNodeWithTag("project-slice-pertool").assertTextContains("T1", substring = true).assertTextContains("T2", substring = true)
        compose.onNodeWithTag("project-slice-purge-note").assertTextContains("Independent tools", substring = true)
        compose.onAllNodesWithTag("project-slice-purge").assertCountEquals(0) // nothing is purged on a toolchanger, so no estimate is shown
        compose.onAllNodesWithTag("sliced-preview-legend").assertCountEquals(1)
    }

    @Test fun aToolchangerOffersNoPrimeTowerAndSaysWhy() {
        open(seed("MMTower"))
        compose.waitUntil(10000) { compose.onAllNodesWithTag("project-no-purge-note").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("project-prime-tower").assertCountEquals(0)
        compose.onNodeWithTag("project-no-purge-note").assertTextContains("does not purge", substring = true)
    }

    @Test fun materialPaintIsOfferedOnAMultiToolMachineAndStoresTheChosenTool() {
        val projectId = seed("MMPaint")
        // One object at the centre of the view, so the swipe below lands on it (the seed spreads two objects apart).
        val objectId = runBlocking {
            val vm = ProjectViewModel(ctx, AppDatabase.get(ctx).projectDao()); vm.loadProject(projectId)
            val ids = vm.allObjects.value.map { it.id }; vm.removeObject(ids[1]); vm.updateObjectTransform(ids[0], ModelTransform()); ids[0]
        }
        open(projectId)
        compose.waitUntil(10000) { compose.onAllNodesWithTag("project-object-$objectId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-object-$objectId").performScrollTo()
        compose.onNodeWithTag("project-object-$objectId").onChildren().filterToOne(hasText("Select")).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Selected").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(8000) { runCatching { compose.onNodeWithTag("project-tool-paint").onChildren().onFirst().assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("project-tool-paint").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-paint-kind-material").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-paint-kind-material").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-paint-tool-3").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-paint-tool-3").performScrollTo().performClick()
        compose.onNodeWithTag("project-workspace").performScrollTo().performTouchInput { swipe(androidx.compose.ui.geometry.Offset(width * 0.42f, height * 0.5f), androidx.compose.ui.geometry.Offset(width * 0.58f, height * 0.55f), durationMillis = 400) }
        var strokes = emptyList<PaintStroke>()
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline && strokes.isEmpty()) {
            Thread.sleep(200)
            strokes = runBlocking { val vm = ProjectViewModel(ctx, AppDatabase.get(ctx).projectDao()); vm.loadProject(projectId); PaintCodec.decode(vm.allObjects.value.first { it.id == objectId }.paintJson) }
        }
        assertTrue("expected material strokes", strokes.isNotEmpty())
        assertTrue(strokes.all { it.kind == PaintKind.MATERIAL && it.tool == 3 })
    }

    @Test fun aSingleToolMachineHasNoMaterialPaintOption() {
        val projectId = runBlocking {
            val vm = ProjectViewModel(ctx, AppDatabase.get(ctx).projectDao()); val p = vm.newProject("MMSingle"); created += p.id
            val f = File(ctx.cacheDir, "mm-s.stl").also { f -> InstrumentationRegistry.getInstrumentation().context.assets.open("cube.stl").use { it.copyTo(f.outputStream()) } }
            vm.addObject(Uri.fromFile(f)); p.id
        }
        val address = "http://k-mm.local/"
        val state = ScreenState(address = address, connected = true, profiles = listOf(PrinterProfile(address, "K", kind = PrinterKind.GENERIC_KLIPPER, slicingModel = SlicingPrinterModel.GENERIC_KLIPPER)))
        compose.setContent { CompanionTheme { ProjectEditorScreen(projectId, null, state, { _, _ -> }, {}) } }
        val oid = runBlocking { val vm = ProjectViewModel(ctx, AppDatabase.get(ctx).projectDao()); vm.loadProject(projectId); vm.allObjects.value.first().id }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("project-object-$oid").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-object-$oid").performScrollTo()
        compose.onNodeWithTag("project-object-$oid").onChildren().filterToOne(hasText("Select")).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Selected").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(8000) { runCatching { compose.onNodeWithTag("project-tool-paint").onChildren().onFirst().assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("project-tool-paint").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("project-paint-kind-support_enforcer").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("project-paint-kind-material").assertCountEquals(0)
    }
}

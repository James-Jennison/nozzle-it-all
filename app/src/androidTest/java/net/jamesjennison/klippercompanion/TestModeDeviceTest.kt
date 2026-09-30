package net.jamesjennison.klippercompanion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import net.jamesjennison.klippercompanion.testgrid.TestModeController
import net.jamesjennison.klippercompanion.testgrid.TestModeScreen
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Test Mode against a simulated printer only: no saved printer is passed in, so no real printer can be reached. Covers
 * the workflow from target selection through the per-step approval card to the redacted evidence preview.
 */
class TestModeDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val runDir get() = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "testgrid/active")

    @Before fun clean() { runDir.deleteRecursively(); TestModeController.resetShared() }
    @After fun cleanUp() { runDir.deleteRecursively(); TestModeController.resetShared() }

    private fun click(tag: String) { compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }; compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun await(tag: String, ms: Long = 60_000) = compose.waitUntil(ms) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitEnabled(tag: String) = compose.waitUntil(20_000) { runCatching { compose.onNodeWithTag(tag).assertIsEnabled(); true }.getOrDefault(false) }

    private fun openSimulatedPaxxSuite() {
        compose.setContent { CompanionTheme { TestModeScreen(emptyList()) {} } }
        compose.onNodeWithTag("test-mode-banner").assertIsDisplayed()
        click("target-0") // SimulatedPrinter.Preset.PAXX_U1 is first when no printer is saved
        await("target-firmware")
        compose.onNodeWithTag("target-firmware").assertTextContains("paxx-extended", substring = true)
        awaitEnabled("to-suites"); click("to-suites")
        click("suite-paxx-u1")
        await("start-run")
    }

    @Test fun softwareOnlyRunEndsInARedactedEvidencePreview() {
        openSimulatedPaxxSuite()
        click("start-run") // level 0 is the default
        await("bundle-digest", 120_000)
        compose.onNodeWithTag("preview-evidence.json").assertExists()
        compose.onNodeWithTag("preview-integrity.json").assertExists()
        val text = compose.onAllNodes(hasTestTag("preview-evidence.json")).fetchSemanticsNodes().joinToString { it.config.toString() }
        assertTrue(!text.contains("192.168.50.23"))
    }

    @Test fun consequentialStepsNeedTheirOwnApprovalWithActionAndTargetShown() {
        openSimulatedPaxxSuite()
        click("level-2")
        click("start-run")
        // Telemetry asks for a precondition and an observation first.
        click("precondition-present"); click("preconditions-met")
        click("answer-yes"); click("submit-observation")
        click("answer-yes"); click("submit-observation") // camera live view
        // Transfer: preconditions, then the approval card for the upload.
        click("precondition-idle"); click("preconditions-met")
        await("confirm-card")
        compose.onNodeWithTag("confirm-action").assertTextContains("Upload nozzle-testgrid-paxx-u1-slice-single.gcode", substring = true)
        compose.onNodeWithTag("confirm-target-text").assertTextContains("Simulated U1", substring = true)
        click("decline-step")
        // The test's own cleanup is offered next, as its own approval; ending the run records it as not run.
        await("confirm-card")
        awaitEnabled("end-run"); click("end-run")
        await("bundle-digest", 120_000)
    }
}

package net.jamesjennison.klippercompanion

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SpeedFlowPanelDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val ready = ScreenState(address = "http://fixture.local/", connected = true, snapshot = PrinterSnapshot(true, "complete"))
    private val factory: (String) -> SpeedFlowReader = { object : SpeedFlowReader {
        override fun speedFlowStatus() = SpeedFlowStatus(true, "complete", 1.0, 1.0, true)
        override fun close() {}
    } }
    private fun review() { compose.onNodeWithTag("review-speedflow").performScrollTo().performClick() }
    private fun awaitCommand() { compose.waitUntil(5000) { compose.onAllNodesWithTag("speedflow-script").fetchSemanticsNodes().isNotEmpty() } }
    @Test fun speedReviewDefaultsTo100AndRequiresSeparateConfirmation() {
        val sent = mutableListOf<PrinterCommand>()
        compose.setContent { CompanionTheme { SpeedFlowPanel(ready, { c, _ -> sent.add(c) }, {}, factory) } }
        review(); awaitCommand(); assertEquals(0, sent.size)
        compose.onNodeWithTag("speedflow-script").assertTextEquals("M220 S100")
        compose.onNodeWithTag("confirm-speedflow").performScrollTo().performClick()
        assertEquals(1, sent.size); assertEquals("speed", sent.single().speedFlowRequest?.kind)
        compose.onNodeWithTag("confirm-speedflow").assertDoesNotExist()
    }
    @Test fun switchingToFlowUsesFlowRangeAndCommand() {
        val sent = mutableListOf<PrinterCommand>()
        compose.setContent { CompanionTheme { SpeedFlowPanel(ready, { c, _ -> sent.add(c) }, {}, factory) } }
        compose.onNodeWithTag("speedflow-flow").performClick()
        compose.onNodeWithTag("speedflow-value").performTextReplacement("120")
        review(); awaitCommand()
        compose.onNodeWithTag("speedflow-script").assertTextEquals("M221 S120")
    }
    @Test fun editingValueInvalidatesPendingConfirmation() {
        compose.setContent { CompanionTheme { SpeedFlowPanel(ready, { _, _ -> error("Do not dispatch") }, {}, factory) } }
        review(); awaitCommand()
        compose.onNodeWithTag("speedflow-value").performScrollTo().performTextReplacement("150")
        compose.onNodeWithTag("confirm-speedflow").assertDoesNotExist()
    }
    @Test fun printStartingInvalidatesPreparedReview() {
        val state = mutableStateOf(ready)
        compose.setContent { CompanionTheme { SpeedFlowPanel(state.value, { _, _ -> error("Do not dispatch") }, {}, factory) } }
        review(); awaitCommand()
        compose.onNodeWithTag("speedflow-notice").assertExists()
        compose.runOnIdle { state.value = ready.copy(snapshot = PrinterSnapshot(true, "printing")) }
        compose.onNodeWithTag("confirm-speedflow").assertDoesNotExist()
        compose.onNodeWithTag("review-speedflow").performScrollTo().assertIsNotEnabled()
    }
}

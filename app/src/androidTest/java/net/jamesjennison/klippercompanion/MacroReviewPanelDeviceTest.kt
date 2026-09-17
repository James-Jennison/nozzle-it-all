package net.jamesjennison.klippercompanion

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MacroReviewPanelDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val ready = ScreenState(address = "http://fixture.local/", connected = true, snapshot = PrinterSnapshot(true, "complete"))
    private val command = MacroTools.command("TEST", MacroTools.definitions("TEMP=0,300,200"), mapOf("TEMP" to "210"))
    private fun factory(available: Boolean = true): (String) -> MacroReader = { object : MacroReader {
        override fun macroStatus(name: String) = MacroStatus(true, "complete", available)
        override fun close() {}
    } }
    private fun awaitCommand() { compose.waitUntil(5000) { compose.onAllNodesWithTag("macro-script").fetchSemanticsNodes().isNotEmpty() } }
    private fun awaitNotice() { compose.waitUntil(5000) { compose.onAllNodesWithTag("macro-notice").fetchSemanticsNodes().isNotEmpty() } }
    @Test fun checksLiveAvailabilityThenRequiresSeparateConfirmation() {
        val sent = mutableListOf<PrinterCommand>()
        compose.setContent { CompanionTheme { MacroReviewPanel(command, ready, { c, _ -> sent.add(c) }, {}, factory()) } }
        awaitCommand(); assertEquals(0, sent.size)
        compose.onNodeWithTag("macro-script").assertTextEquals("TEST TEMP=210")
        compose.onNodeWithTag("confirm-macro").performScrollTo().performClick()
        assertEquals(1, sent.size); assertEquals("TEST", sent.single().macroRequest?.name)
        compose.onNodeWithTag("confirm-macro").assertDoesNotExist()
    }
    @Test fun macroNoLongerOnThePrinterBlocksConfirmation() {
        compose.setContent { CompanionTheme { MacroReviewPanel(command, ready, { _, _ -> error("Do not dispatch") }, {}, factory(available = false)) } }
        awaitNotice()
        compose.onNodeWithTag("confirm-macro").assertDoesNotExist()
    }
    @Test fun printStartingInvalidatesPreparedReview() {
        val state = mutableStateOf(ready)
        compose.setContent { CompanionTheme { MacroReviewPanel(command, state.value, { _, _ -> error("Do not dispatch") }, {}, factory()) } }
        awaitCommand()
        compose.runOnIdle { state.value = ready.copy(snapshot = PrinterSnapshot(true, "printing")) }
        compose.onNodeWithTag("confirm-macro").assertDoesNotExist()
        compose.onNodeWithTag("recheck-macro").performScrollTo().assertIsNotEnabled()
    }
}

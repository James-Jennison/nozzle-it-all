package net.jamesjennison.klippercompanion

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LedPanelDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val ready = ScreenState(address = "http://fixture.local/", connected = true, snapshot = PrinterSnapshot(true, "printing"))
    private val factory: (String) -> LedReader = { object : LedReader {
        override fun leds() = listOf("case", "hotend")
        override fun ledStatus(led: String) = LedStatus(led, true, 1.0)
        override fun close() {}
    } }
    private fun load() { compose.onNodeWithTag("load-leds").performScrollTo().performClick() }
    private fun awaitOptions() { compose.waitUntil(5000) { compose.onAllNodesWithTag("led-option-case").fetchSemanticsNodes().isNotEmpty() } }
    private fun awaitCommand() { compose.waitUntil(5000) { compose.onAllNodesWithTag("led-script").fetchSemanticsNodes().isNotEmpty() } }
    @Test fun availableDuringPrintingSelectsALightAndRequiresSeparateConfirmation() {
        val sent = mutableListOf<PrinterCommand>()
        compose.setContent { CompanionTheme { LedPanel(ready, { c, _ -> sent.add(c) }, {}, factory) } }
        // Unlike heater/fan/macro controls, lights stay usable while printing.
        compose.onNodeWithTag("load-leds").performScrollTo().assertIsEnabled()
        load(); awaitOptions()
        compose.onNodeWithTag("led-option-case").performScrollTo().performClick()
        compose.onNodeWithTag("led-value").performScrollTo().performTextReplacement("50")
        compose.onNodeWithTag("review-led").performScrollTo().performClick()
        awaitCommand(); assertEquals(0, sent.size)
        compose.onNodeWithTag("led-script").assertTextEquals("SET_LED LED=case WHITE=0.5 SYNC=0")
        compose.onNodeWithTag("confirm-led").performScrollTo().performClick()
        assertEquals(1, sent.size); assertEquals("case", sent.single().ledRequest?.led)
        compose.onNodeWithTag("confirm-led").assertDoesNotExist()
    }
    @Test fun switchingLightsInvalidatesPendingConfirmation() {
        compose.setContent { CompanionTheme { LedPanel(ready, { _, _ -> error("Do not dispatch") }, {}, factory) } }
        load(); awaitOptions()
        compose.onNodeWithTag("led-option-hotend").performScrollTo().performClick()
        compose.onNodeWithTag("review-led").performScrollTo().performClick()
        awaitCommand()
        compose.onNodeWithTag("led-option-case").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-led").assertDoesNotExist()
    }
    @Test fun disconnectingClosesTheReaderAndDisablesControls() {
        val state = mutableStateOf(ready)
        compose.setContent { CompanionTheme { LedPanel(state.value, { _, _ -> error("Do not dispatch") }, {}, factory) } }
        load(); awaitOptions()
        compose.runOnIdle { state.value = ready.copy(connected = false) }
        compose.onNodeWithTag("load-leds").performScrollTo().assertIsNotEnabled()
    }
}

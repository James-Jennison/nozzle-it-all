package net.jamesjennison.klippercompanion

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BambuPrintPanelDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val state = ScreenState(address = "192.168.1.50", connected = true, generation = 7, snapshot = PrinterSnapshot(true, "standby"))
    private fun fixture(path: String) = Uri.parse("content://net.jamesjennison.klippercompanion.test.gcodefixture/$path")

    @Test fun confirmingASlicedShareSendsOnePrintRequestThroughExecute() {
        val sent = mutableListOf<Pair<PrinterCommand, Int>>();var closed = false
        compose.setContent { CompanionTheme { BambuPrintPanel(fixture("bambu"), state, { c, g -> sent.add(c to g) }, { closed = true }) } }
        compose.onNodeWithText("fixture.gcode.3mf").assertIsDisplayed()
        assertTrue(sent.isEmpty())
        compose.onNodeWithTag("bambu-print-confirm").performClick()
        compose.waitUntil(10000) { sent.isNotEmpty() }
        val (command, generation) = sent.single()
        assertEquals(7, generation)
        assertEquals("fixture.gcode.3mf", command.bambuPrintRequest?.remoteName)
        assertTrue(command.bambuPrintRequest?.file?.isFile == true)
        // The same staleness/allowed-state gate every other mutating command goes through.
        assertEquals(setOf("standby", "complete", "cancelled", "error"), command.allowedStates)
        compose.waitUntil(5000) { closed }
    }

    @Test fun anUnslicedShareOffersNoPrintButton() {
        val sent = mutableListOf<PrinterCommand>()
        compose.setContent { CompanionTheme { BambuPrintPanel(fixture("sample"), state, { c, _ -> sent.add(c) }, {}) } }
        compose.onNodeWithText("Unsupported file").assertIsDisplayed()
        compose.onAllNodesWithTag("bambu-print-confirm").assertCountEquals(0)
        assertTrue(sent.isEmpty())
    }
}

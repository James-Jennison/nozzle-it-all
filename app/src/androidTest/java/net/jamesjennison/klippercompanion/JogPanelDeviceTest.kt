package net.jamesjennison.klippercompanion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

// Phase 7 (WO-24): real relative-move G-code, dispatched immediately (no review-then-confirm
// step - see JogPanel.kt's own header comment for why) but only while the printer is idle/ready.
class JogPanelDeviceTest {
    @get:Rule val compose = createComposeRule()

    private fun readyState() = ScreenState(address = "http://fixture.local/", connected = true, snapshot = PrinterSnapshot(true, "standby"))

    @Test fun defaultStepMovesTenMillimeters() {
        val sent = mutableListOf<PrinterCommand>()
        compose.setContent { CompanionTheme { JogPanel(readyState(), { command, _ -> sent.add(command) }, {}) } }
        compose.onNodeWithTag("jog-x-plus").performClick()
        assertEquals(1, sent.size)
        assertEquals("G91\nG1 X10.00 F3000\nG90", sent.first().arguments.getValue("script"))
    }

    @Test fun changingStepSizeChangesTheSentDistance() {
        val sent = mutableListOf<PrinterCommand>()
        compose.setContent { CompanionTheme { JogPanel(readyState(), { command, _ -> sent.add(command) }, {}) } }
        compose.onNodeWithTag("jog-step-1.0").performClick()
        compose.onNodeWithTag("jog-y-minus").performClick()
        assertEquals("G91\nG1 Y-1.00 F3000\nG90", sent.single().arguments.getValue("script"))
    }

    @Test fun homeAllSendsRealG28() {
        val sent = mutableListOf<PrinterCommand>()
        compose.setContent { CompanionTheme { JogPanel(readyState(), { command, _ -> sent.add(command) }, {}) } }
        compose.onNodeWithTag("jog-home-all").performClick()
        assertEquals("G28", sent.single().arguments.getValue("script"))
    }

    @Test fun homeSingleAxisSendsRealG28WithAxisLetter() {
        val sent = mutableListOf<PrinterCommand>()
        compose.setContent { CompanionTheme { JogPanel(readyState(), { command, _ -> sent.add(command) }, {}) } }
        compose.onNodeWithTag("jog-home-z").performClick()
        assertEquals("G28 Z", sent.single().arguments.getValue("script"))
    }

    @Test fun controlsAreDisabledWhilePrinting() {
        val sent = mutableListOf<PrinterCommand>()
        val printing = ScreenState(address = "http://fixture.local/", connected = true, snapshot = PrinterSnapshot(true, "printing"))
        compose.setContent { CompanionTheme { JogPanel(printing, { command, _ -> sent.add(command) }, {}) } }
        compose.onNodeWithTag("jog-x-plus").assertIsNotEnabled()
        compose.onNodeWithText("Jogging requires an idle, connected printer.").assertExists()
        assertTrue(sent.isEmpty())
    }
}

package net.jamesjennison.klippercompanion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FanStatusPanelDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun loadsAndDisplaysFanReadouts() {
        val reader = object : FanReadoutReader {
            override fun fanReadouts() = listOf(FanReadout("fan", 0.5, 4500.0), FanReadout("fan_generic e1_fan", 0.0, null))
            override fun close() {}
        }
        compose.setContent { CompanionTheme { FanStatusPanel("http://fixture.local/", true, {}, { reader }) } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("fanstatus-fan").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("50% · 4500 RPM").assertExists()
        compose.onNodeWithText("0%").assertExists()
    }
    @Test fun noFansShowsExplicitEmptyState() {
        val reader = object : FanReadoutReader {
            override fun fanReadouts() = emptyList<FanReadout>()
            override fun close() {}
        }
        compose.setContent { CompanionTheme { FanStatusPanel("http://fixture.local/", true, {}, { reader }) } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("fanstatus-status").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("No manual fans detected.").assertExists()
    }
    @Test fun disconnectedShowsExplicitMessageWithoutQuerying() {
        var queried = false
        val reader = object : FanReadoutReader {
            override fun fanReadouts(): List<FanReadout> { queried = true; throw ApiFailure("Should not be called") }
            override fun close() {}
        }
        compose.setContent { CompanionTheme { FanStatusPanel("http://fixture.local/", false, {}, { reader }) } }
        compose.onNodeWithText("Disconnected. Connect to read fan status.").assertExists()
        assertFalse(queried)
    }
}

package net.jamesjennison.klippercompanion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ToolheadsPanelDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun loadsAndDisplaysAllToolheads() {
        val reader = object : ToolheadReader {
            override fun toolheadTemperatures() = listOf(ToolheadTemperature("extruder", 220.0, 220.0), ToolheadTemperature("extruder1", 25.0, 0.0))
            override fun close() {}
        }
        compose.setContent { CompanionTheme { ToolheadsPanel("http://fixture.local/", true, {}, { reader }) } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("toolhead-extruder").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("toolhead-extruder1").assertExists()
        compose.onNodeWithText("220°C / target 220°C").assertExists()
    }
    @Test fun noToolheadsShowsExplicitEmptyState() {
        val reader = object : ToolheadReader {
            override fun toolheadTemperatures() = emptyList<ToolheadTemperature>()
            override fun close() {}
        }
        compose.setContent { CompanionTheme { ToolheadsPanel("http://fixture.local/", true, {}, { reader }) } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("toolheads-status").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("No toolheads detected.").assertExists()
    }
    @Test fun disconnectedShowsExplicitMessageWithoutQuerying() {
        var queried = false
        val reader = object : ToolheadReader {
            override fun toolheadTemperatures(): List<ToolheadTemperature> { queried = true; throw ApiFailure("Should not be called") }
            override fun close() {}
        }
        compose.setContent { CompanionTheme { ToolheadsPanel("http://fixture.local/", false, {}, { reader }) } }
        compose.onNodeWithText("Disconnected. Connect to read toolhead temperatures.").assertExists()
        assertFalse(queried)
    }
}

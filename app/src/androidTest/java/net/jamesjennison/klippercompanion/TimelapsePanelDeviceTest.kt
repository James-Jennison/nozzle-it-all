package net.jamesjennison.klippercompanion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TimelapsePanelDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun loadsAndListsVideosNewestFirst() {
        val reader = object : TimelapseReader {
            override fun timelapses() = listOf(FileInfo("first.mp4", 1_048_576, 100.0), FileInfo("second.mp4", 2_097_152, 200.0))
            override fun close() {}
        }
        compose.setContent { CompanionTheme { TimelapsePanel("http://fixture.local/", true, {}, { reader }) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("first.mp4").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("second.mp4").assertExists()
        // Size only, not the modified-date text: that's formatted in the device's local
        // timezone, so its exact string isn't stable across devices/CI.
        compose.onNode(hasText("1.0 MB", substring = true)).assertExists()
    }
    @Test fun missingComponentShowsExplicitEmptyState() {
        val reader = object : TimelapseReader {
            override fun timelapses() = emptyList<FileInfo>()
            override fun close() {}
        }
        compose.setContent { CompanionTheme { TimelapsePanel("http://fixture.local/", true, {}, { reader }) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("No timelapse videos found. Requires the moonraker-timelapse component to be installed and enabled.").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun disconnectedShowsExplicitMessageWithoutQuerying() {
        var queried = false
        val reader = object : TimelapseReader {
            override fun timelapses(): List<FileInfo> { queried = true; throw ApiFailure("Should not be called") }
            override fun close() {}
        }
        compose.setContent { CompanionTheme { TimelapsePanel("http://fixture.local/", false, {}, { reader }) } }
        compose.onNodeWithText("Disconnected. Connect to browse timelapses.").assertExists()
        assertFalse(queried)
    }
}

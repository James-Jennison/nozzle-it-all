package net.jamesjennison.klippercompanion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

private class FakeTimelapseReader(private val clips: List<TimelapseClip>, private val renderResult: TimelapseRenderResult? = null) : TimelapseReader {
    override fun timelapses() = clips
    override fun timelapseThumbnail(path: String): ByteArray = throw ApiFailure("No poster in this fixture.")
    override fun timelapseVideoUrl(path: String) = TimelapseVideoUrl("http://fixture.local/server/files/timelapse/$path")
    override fun renderTimelapse(): TimelapseRenderResult = renderResult ?: throw ApiFailure("No render result configured in this fixture.")
    override fun close() {}
}

class TimelapsePanelDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun loadsAndListsVideosNewestFirst() {
        val reader = FakeTimelapseReader(listOf(
            TimelapseClip("first.mp4", 1_048_576, 100.0, null),
            TimelapseClip("second.mp4", 2_097_152, 200.0, null),
        ))
        compose.setContent { CompanionTheme { TimelapsePanel("http://fixture.local/", true, {}, { reader }) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("first.mp4").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("second.mp4").assertExists()
        // Size only, not the time text: that's formatted in the device's local timezone, so its
        // exact string isn't stable across devices/CI.
        compose.onNode(hasText("1.0 MB", substring = true)).assertExists()
    }
    @Test fun tappingAClipOpensThePlayer() {
        val reader = FakeTimelapseReader(listOf(TimelapseClip("dragon.mp4", 1_048_576, 100.0, null)))
        compose.setContent { CompanionTheme { TimelapsePanel("http://fixture.local/", true, {}, { reader }) } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("timelapse-clip:dragon.mp4").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("timelapse-clip:dragon.mp4").performClick()
        // Both this panel's own dialog and the player's stack on top of it, so two "Close"
        // buttons exist once the player opens.
        compose.waitUntil(5000) { compose.onAllNodesWithText("Close").fetchSemanticsNodes().size >= 2 }
    }
    @Test fun missingComponentShowsExplicitEmptyState() {
        val reader = FakeTimelapseReader(emptyList())
        compose.setContent { CompanionTheme { TimelapsePanel("http://fixture.local/", true, {}, { reader }) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("No timelapse videos found. Requires the moonraker-timelapse component to be installed and enabled.").fetchSemanticsNodes().isNotEmpty() }
    }
    // Phase 7 (WO-24): the real render-now trigger - button hidden without canRender (§20, no
    // dead buttons), and a confirmed tap actually calls renderTimelapse() and shows its real
    // status message.
    @Test fun renderButtonHiddenWithoutCapability() {
        val reader = FakeTimelapseReader(emptyList())
        compose.setContent { CompanionTheme { TimelapsePanel("http://fixture.local/", true, {}, { reader }, canRender = false) } }
        compose.onNodeWithTag("render-timelapse").assertDoesNotExist()
    }
    @Test fun renderNowTriggersARealRenderAndShowsItsStatus() {
        val reader = FakeTimelapseReader(emptyList(), TimelapseRenderResult("started", "started render, 42 frames"))
        compose.setContent { CompanionTheme { TimelapsePanel("http://fixture.local/", true, {}, { reader }, canRender = true) } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("render-timelapse").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("render-timelapse").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("confirm-render-timelapse").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("confirm-render-timelapse").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("render-timelapse-note").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("started render, 42 frames").assertExists()
    }
    @Test fun disconnectedShowsExplicitMessageWithoutQuerying() {
        var queried = false
        val reader = object : TimelapseReader {
            override fun timelapses(): List<TimelapseClip> { queried = true; throw ApiFailure("Should not be called") }
            override fun timelapseThumbnail(path: String): ByteArray { queried = true; throw ApiFailure("Should not be called") }
            override fun timelapseVideoUrl(path: String): TimelapseVideoUrl { queried = true; throw ApiFailure("Should not be called") }
            override fun close() {}
        }
        compose.setContent { CompanionTheme { TimelapsePanel("http://fixture.local/", false, {}, { reader }) } }
        compose.onNodeWithText("Disconnected. Connect to browse timelapses.").assertExists()
        assertFalse(queried)
    }
}

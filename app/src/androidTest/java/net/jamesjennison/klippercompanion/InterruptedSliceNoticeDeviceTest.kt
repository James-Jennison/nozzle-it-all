package net.jamesjennison.klippercompanion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InterruptedSliceNoticeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val marker get() = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "slice-in-progress")
    @After fun cleanup() { marker.delete() }

    @Test fun startingTheAppAfterAKilledSliceExplainsWhatHappenedOnce() {
        marker.writeText("1")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("interrupted-slice-text").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("interrupted-slice-ok").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("interrupted-slice-text").fetchSemanticsNodes().isEmpty() }
        assertFalse("the marker is consumed", marker.exists())
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onAllNodesWithTag("interrupted-slice-text").assertCountEquals(0)
    }
}

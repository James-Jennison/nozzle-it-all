package net.jamesjennison.klippercompanion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class LivePrinterReadOnlyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun connectAndReadActualPrinterWithoutSendingCommands() {
        val endpoint = InstrumentationRegistry.getArguments().getString("printerUrl")
        assumeTrue("Explicit owner-approved printerUrl is required", !endpoint.isNullOrBlank())
        compose.onNodeWithTag("nav-3").performClick()
        compose.onNodeWithText("Moonraker or frontend address").performTextReplacement(endpoint!!)
        compose.onNodeWithTag("connect-printer").performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("CONNECTED").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("CONNECTED").assertExists()
        compose.onNodeWithText("Macros").performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("Run").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Files").performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("Start print").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Monitor").performClick()
        compose.onNodeWithText("Camera", substring = false).performScrollTo()
        compose.waitUntil(30000) { compose.onAllNodesWithText("fps", substring=true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("fps", substring=true).assertExists()
        // No print controls or macro buttons are invoked by this test.
    }
}

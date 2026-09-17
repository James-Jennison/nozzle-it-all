package net.jamesjennison.klippercompanion
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConsoleCommandDeviceTest {
    @get:Rule val compose = createComposeRule()
    private fun factory(address: String) = object : ConsoleReader {
        override fun console() = ConsoleBatch(emptyList())
        override fun close() {}
    }
    private fun ready() { compose.waitUntil(10000) { compose.onAllNodesWithText("Recent cache", substring = true).fetchSemanticsNodes().isNotEmpty() } }

    @Test fun commandEntryIsHiddenWhenExecuteIsOmitted() {
        compose.setContent { CompanionTheme { ConsolePanel("http://fixture.local/", true, {}, ::factory) } }
        ready()
        compose.onNodeWithTag("console-command").assertDoesNotExist()
        compose.onNodeWithText("Send command").assertDoesNotExist()
    }

    @Test fun reviewThenConfirmSendsExactlyOneCommand() {
        val sent = mutableListOf<PrinterCommand>()
        compose.setContent { CompanionTheme { ConsolePanel("http://fixture.local/", true, {}, ::factory, ready = true, execute = { c, _ -> sent.add(c) }, generation = 3) } }
        ready()
        compose.onNodeWithTag("console-command").performTextReplacement("G28")
        compose.onNodeWithTag("review-console-command").performClick()
        compose.onNodeWithTag("console-command-script").assertTextEquals("G28")
        assertEquals(0, sent.size)
        compose.onNodeWithTag("confirm-console-command").performClick()
        assertEquals(1, sent.size); assertEquals("G28", sent.single().arguments.getValue("script"))
        compose.onNodeWithTag("confirm-console-command").assertDoesNotExist()
    }

    @Test fun editingAfterReviewClearsThePreparedCommand() {
        compose.setContent { CompanionTheme { ConsolePanel("http://fixture.local/", true, {}, ::factory, ready = true, execute = { _, _ -> error("Do not dispatch") }) } }
        ready()
        compose.onNodeWithTag("console-command").performTextReplacement("G28")
        compose.onNodeWithTag("review-console-command").performClick()
        compose.onNodeWithTag("console-command-script").assertExists()
        compose.onNodeWithTag("console-command").performTextReplacement("G28 X0")
        compose.onNodeWithTag("confirm-console-command").assertDoesNotExist()
    }

    @Test fun multilineInputIsRejectedBeforeAnyReviewIsShown() {
        compose.setContent { CompanionTheme { ConsolePanel("http://fixture.local/", true, {}, ::factory, ready = true, execute = { _, _ -> error("Do not dispatch") }) } }
        ready()
        compose.onNodeWithTag("console-command").performTextInput("M104 S200\nG28")
        compose.onNodeWithTag("review-console-command").performClick()
        compose.onNodeWithTag("console-command-notice").assertExists()
        compose.onNodeWithTag("console-command-script").assertDoesNotExist()
    }

    @Test fun notReadyDisablesSendEvenWhenConnected() {
        compose.setContent { CompanionTheme { ConsolePanel("http://fixture.local/", true, {}, ::factory, ready = false, execute = { _, _ -> error("Do not dispatch") }) } }
        ready()
        compose.onNodeWithTag("console-command").assertIsNotEnabled()
    }
}

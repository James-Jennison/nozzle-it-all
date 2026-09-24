package net.jamesjennison.klippercompanion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsBackupDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun theBackupDialogNeedsAPassphraseOfEightCharactersBeforeItOffersToSave() {
        compose.onNodeWithTag("nav-4").performClick()
        compose.waitUntil(8000) { compose.onAllNodesWithTag("backup-printers").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("backup-printers").performScrollTo().performClick()
        compose.onNodeWithTag("backup-dialog").assertExists()
        compose.onNodeWithTag("backup-continue").assertIsNotEnabled()
        compose.onNodeWithTag("backup-passphrase").performTextInput("short")
        compose.onNodeWithTag("backup-continue").assertIsNotEnabled()
        compose.onNodeWithTag("backup-passphrase").performTextInput("erpassphrase")
        compose.onNodeWithTag("backup-continue").assertIsEnabled()
    }
}

package net.jamesjennison.klippercompanion

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CompanionScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun pauseRequiresExplicitConfirmationAndGoBackDoesNotDispatch() {
        var sent = 0
        compose.setContent { MaterialTheme { CompanionScreen(ScreenState(address="http://fixture.local/", connected=true, snapshot=PrinterSnapshot(true,"printing")), {}, {}, {}, { _,_->sent++ }) } }
        compose.onNodeWithText("Pause").performScrollTo().performClick()
        assertEquals(0,sent)
        compose.onNodeWithText("Go back").performClick()
        assertEquals(0,sent)
        compose.onNodeWithText("Pause").performScrollTo().performClick()
        compose.onNodeWithText("Confirm").performClick()
        assertEquals(1,sent)
    }
    @Test fun offlineDisablesPrintControls() {
        compose.setContent { MaterialTheme { CompanionScreen(ScreenState(address="http://fixture.local/", connected=false, snapshot=PrinterSnapshot(true,"printing")), {}, {}, {}, { _,_->error("Must not dispatch") }) } }
        compose.onNodeWithText("Pause").performScrollTo().assertIsNotEnabled()
    }
}

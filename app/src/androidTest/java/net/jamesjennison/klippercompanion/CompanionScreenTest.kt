package net.jamesjennison.klippercompanion

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CompanionScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun preferencePairPersistsTogetherAndMalformedTypesDoNotCrash() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("profile-test-${java.util.UUID.randomUUID()}", 0)
        try {
            PrinterPreferences.save(prefs, "http://first.local/", listOf("http://first.local/", "http://second.local/"))
            assertEquals("http://first.local/", PrinterPreferences.address(prefs))
            assertEquals(2, PrinterPreferences.printers(prefs).size)
            PrinterPreferences.save(prefs, "", listOf("http://second.local/"))
            assertEquals("", PrinterPreferences.address(prefs))
            assertEquals(listOf("http://second.local/"), PrinterPreferences.printers(prefs))
            prefs.edit().putInt("address", 1).putInt("savedPrinters", 2).commit()
            assertEquals("", PrinterPreferences.address(prefs))
            assertEquals(emptyList<String>(), PrinterPreferences.printers(prefs))
        } finally { prefs.edit().clear().commit() }
    }
    @Test fun pauseRequiresExplicitConfirmationAndGoBackDoesNotDispatch() {
        var sent = 0
        compose.setContent { CompanionTheme { CompanionScreen(ScreenState(address="http://fixture.local/", connected=true, snapshot=PrinterSnapshot(true,"printing")), {}, {}, {}, { _,_->sent++ }) } }
        compose.onNodeWithText("Pause").performScrollTo().performClick()
        assertEquals(0,sent)
        compose.onNodeWithText("Go back").performClick()
        assertEquals(0,sent)
        compose.onNodeWithText("Pause").performScrollTo().performClick()
        compose.onNodeWithText("Confirm").performClick()
        assertEquals(1,sent)
    }
    @Test fun offlineDisablesPrintControls() {
        compose.setContent { CompanionTheme { CompanionScreen(ScreenState(address="http://fixture.local/", connected=false, snapshot=PrinterSnapshot(true,"printing")), {}, {}, {}, { _,_->error("Must not dispatch") }) } }
        compose.onNodeWithText("Pause").performScrollTo().assertIsNotEnabled()
    }
    @Test fun savedProfileConnectAndForgetUseTheirExactAddresses() {
        val first = "http://first.local/"; val second = "http://second.local/"
        var connected = ""; var forgotten = ""
        compose.setContent { CompanionTheme { CompanionScreen(ScreenState(address=first, savedPrinters=listOf(first,second)), { connected = it }, {}, {}, { _,_->error("Must not dispatch") }, { forgotten = it }) } }
        compose.onNodeWithTag("nav-3").performClick()
        compose.onNodeWithTag("saved-forget:$first").performScrollTo().performClick()
        assertEquals(first, forgotten)
        compose.onNodeWithTag("saved-connect:$second").performScrollTo().performClick()
        assertEquals(second, connected)
    }
    @Test fun compactLargeTextKeepsConfirmationAndNavigationReachable() {
        var sent = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                CompanionTheme { Box(Modifier.width(320.dp)) {
                    CompanionScreen(ScreenState(address="http://fixture.local/", connected=true,
                        snapshot=PrinterSnapshot(true,"printing", filename="A very long model filename with multiple words.gcode", progress=.47f)), {}, {}, {}, { _,_->sent++ })
                } }
            }
        }
        compose.onNodeWithText("Pause").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Go back").assertIsDisplayed().performClick()
        assertEquals(0, sent)
        compose.onNodeWithTag("nav-3").assertIsDisplayed().performClick()
        compose.onNodeWithTag("connect-printer").performScrollTo().assertIsDisplayed()
    }

    @Test fun rejectedConfirmationFeedbackStaysVisibleWhileConnected() {
        val message = "Printer state changed. Refresh before sending a command."
        compose.setContent { CompanionTheme {
            var state by remember { mutableStateOf(ScreenState(address="http://fixture.local/", connected=true, snapshot=PrinterSnapshot(true,"printing"))) }
            CompanionScreen(state, {}, {}, {}, { _,_-> state = state.copy(commandNotice=message) })
        } }
        compose.onNodeWithText("Pause").performScrollTo().performClick()
        compose.onNodeWithText("Confirm").performClick()
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithTag("nav-1").performClick()
        compose.onNodeWithText(message).assertIsDisplayed()
    }

}

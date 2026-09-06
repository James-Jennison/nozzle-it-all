package net.jamesjennison.klippercompanion

import androidx.compose.material3.MaterialTheme
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
    @Test fun savedProfileConnectAndForgetUseTheirExactAddresses() {
        val first = "http://first.local/"; val second = "http://second.local/"
        var connected = ""; var forgotten = ""
        compose.setContent { MaterialTheme { CompanionScreen(ScreenState(address=first, savedPrinters=listOf(first,second)), { connected = it }, {}, {}, { _,_->error("Must not dispatch") }, { forgotten = it }) } }
        compose.onNodeWithTag("nav-3").performClick()
        compose.onNodeWithTag("saved-forget:$first").performScrollTo().performClick()
        assertEquals(first, forgotten)
        compose.onNodeWithTag("saved-connect:$second").performScrollTo().performClick()
        assertEquals(second, connected)
    }
}

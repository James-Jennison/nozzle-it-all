package net.jamesjennison.klippercompanion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

/** UI fixture only: no real printer service, preferences, or commands. */
class AutoConnectDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun independentConnectionStatesAndExplicitTargetSelection() {
        val first = "http://first.local/"
        val second = "http://second.local/"
        var selected = ""
        compose.setContent { CompanionTheme {
            CompanionScreen(ScreenState(address=first, connected=true,
                snapshot=PrinterSnapshot(true,"standby"), savedPrinters=listOf(first,second),
                printerConnections=mapOf(second to PrinterConnection(false,"Unavailable • retrying while open"))),
                {selected=it}, {}, {}, {_,_->error("Must not dispatch")})
        } }
        compose.onNodeWithTag("nav-4").performClick()
        compose.onNodeWithTag("saved-status:$first").performScrollTo().assertTextEquals("Connected • standby")
        compose.onNodeWithTag("saved-status:$second").performScrollTo().assertTextContains("Unavailable", substring=true)
        assertEquals("", selected)
        compose.onNodeWithTag("saved-connect:$second").performScrollTo().performClick()
        assertEquals(second, selected)
    }
}

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
        compose.onNodeWithTag("saved-status:$first").performScrollTo().assertTextEquals("Ready")
        compose.onNodeWithTag("saved-status:$second").performScrollTo().assertTextContains("Offline", substring=true) // the shared glossary word for a printer Nozzle can't reach
        assertEquals("", selected)
        compose.waitForIdle()
        // Semantic click: the coordinate tap is unreliable on the Galaxy S25 (see CompanionScreenTest).
        compose.onNodeWithTag("saved-connect:$second").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(second, selected)
    }
}

package net.jamesjennison.klippercompanion

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class PrinterTilesDeviceTest {
    @get:Rule val compose=createComposeRule()
    @Test fun tileOpensExactPrinterFullDashboardAndReturnsToOverview() {
        val first="http://first.local/";val second="http://second.local/"
        var selected="";var sends=0
        val a=PrinterSnapshot(true,"printing",filename="first.gcode",progress=.25f)
        val b=PrinterSnapshot(true,"paused",filename="second.gcode",progress=.8f)
        compose.setContent {
            var state by remember { mutableStateOf(ScreenState(address=first,connected=true,snapshot=a,
                savedPrinters=listOf(first,second),profiles=listOf(PrinterProfile(first,"First printer"),PrinterProfile(second,"Second printer")),
                printerConnections=mapOf(second to PrinterConnection(true,"paused",b)))) }
            CompanionTheme { CompanionScreen(state,{ target -> selected=target;state=state.copy(address=target,snapshot=b,generation=1,
                printerConnections=mapOf(first to PrinterConnection(true,"printing",a))) },{},{},{_,_->sends++}) }
        }
        compose.onNodeWithTag("printer-tile:$first").assertExists()
        compose.onNodeWithTag("printer-tile:$second").performScrollTo().performClick()
        assertEquals(second,selected)
        compose.onNodeWithText("second.gcode").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Resume").performScrollTo().assertIsEnabled()
        assertEquals(0,sends)
        compose.onNodeWithTag("all-printers").performScrollTo().performClick()
        compose.onNodeWithTag("printer-tile:$first").assertExists()
        compose.onNodeWithTag("printer-tile:$second").assertExists()
        assertEquals(0,sends)
    }
    @Test fun offlineOverviewHasNoPrinterControls() {
        compose.setContent { CompanionTheme { CompanionScreen(ScreenState(),{},{},{},{_,_->error("No commands")}) } }
        compose.onNodeWithText("No printers connected. Saved printers reconnect while the app is open.").assertExists()
        compose.onNodeWithText("Pause").assertDoesNotExist()
    }
}

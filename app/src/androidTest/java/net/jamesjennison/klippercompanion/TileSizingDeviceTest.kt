package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TileSizingDeviceTest {
    @get:Rule val compose=createComposeRule()
    @Test fun rowsStayFullWidthAndStackedRegardlessOfContentWidthOrFontScale() {
        val width=mutableStateOf(360f);val fontScale=mutableStateOf(1f)
        val tiles=listOf(
            PrinterTile("first","Printer with a much longer display name","printing",PrinterSnapshot(true,"printing","long-file-name-that-wraps-across-two-lines.gcode"),null),
            PrinterTile("second","Short","complete",PrinterSnapshot(true,"complete","old.gcode"),null))
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,fontScale.value)) {
                CompanionTheme { Box(Modifier.width(width.value.dp)) { PrinterTiles(tiles,true,{},cameraContent={Spacer(Modifier.fillMaxSize())}) } }
            }
        }
        for(w in listOf(320f,360f))for(scale in listOf(1f,1.2f,1.5f)) {
            compose.runOnIdle{width.value=w;fontScale.value=scale}
            val a=compose.onNodeWithTag("printer-tile:first").fetchSemanticsNode().boundsInRoot
            val b=compose.onNodeWithTag("printer-tile:second").fetchSemanticsNode().boundsInRoot
            assertEquals(a.width,b.width,1f);assertEquals(a.left,b.left,1f)
            assertTrue("second tile must stack below first, never beside it",a.bottom<=b.top)
        }
    }
    @Test fun resizedRowsActivateVisibleCameraRegions() {
        val tiles=listOf(
            PrinterTile("first","First","standby",PrinterSnapshot(true,"standby"),null),
            PrinterTile("second","Second","complete",PrinterSnapshot(true,"complete"),null))
        compose.setContent { CompanionTheme { Box(Modifier.width(360.dp)) { PrinterTiles(tiles,true,{}) } } }
        compose.waitUntil(5000){compose.onAllNodesWithText("Camera unavailable").fetchSemanticsNodes().size==2}
        compose.onAllNodesWithText("Camera unavailable").assertCountEquals(2)
    }

    @Test fun connectedDashboardActivatesCameraSlotsInLazyRows() {
        val state=mutableStateOf(ScreenState())
        compose.setContent { CompanionTheme { CompanionScreen(state.value,{},{},{},{_,_->error("No commands")}) } }
        compose.runOnIdle {
            val first="http://first.local/";val second="http://second.local/"
            state.value=ScreenState(address=first,connected=true,snapshot=PrinterSnapshot(true,"standby"),savedPrinters=listOf(first,second),printerConnections=mapOf(second to PrinterConnection(true,"complete",PrinterSnapshot(true,"complete"))))
        }
        compose.waitUntil(5000){compose.onAllNodesWithText("Camera unavailable").fetchSemanticsNodes().size==2}
    }

}

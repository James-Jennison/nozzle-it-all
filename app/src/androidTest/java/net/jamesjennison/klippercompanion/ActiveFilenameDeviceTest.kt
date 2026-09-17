package net.jamesjennison.klippercompanion

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class ActiveFilenameDeviceTest {
    @get:Rule val compose=createComposeRule()
    @Test fun retainedFilenameClearsOnCompletionInTileAndDetailAndReturnsForNextJob() {
        val address="http://fixture.local/"
        val snapshot=mutableStateOf(PrinterSnapshot(true,"printing","retained.gcode",.5f))
        compose.setContent { CompanionTheme { CompanionScreen(ScreenState(address=address,connected=true,snapshot=snapshot.value,savedPrinters=listOf(address)),{},{},{},{_,_->error("No commands expected")},tileCamera={}) } }
        compose.onNodeWithText("retained.gcode").assertExists()
        compose.runOnIdle {snapshot.value=snapshot.value.copy(state="complete",progress=1f)}
        compose.onNodeWithText("retained.gcode").assertDoesNotExist()
        compose.onNodeWithText("No active file").assertExists()
        compose.onNodeWithText("0%").assertExists()
        compose.onNodeWithText("Standby").assertExists()
        compose.openFixtureDashboard()
        compose.onNodeWithText("No active file").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0%").assertExists()
        compose.onNodeWithText("Standby").assertExists()
        compose.runOnIdle {snapshot.value=snapshot.value.copy(state="paused")}
        compose.onNodeWithText("retained.gcode").assertIsDisplayed()
        compose.runOnIdle {snapshot.value=snapshot.value.copy(state="complete")}
        compose.onNodeWithText("retained.gcode").assertDoesNotExist()
        compose.onNodeWithText("No active file").assertIsDisplayed()
        compose.runOnIdle {snapshot.value=snapshot.value.copy(state="printing",filename="next.gcode",progress=.01f)}
        compose.onNodeWithText("next.gcode").assertIsDisplayed()
    }
}

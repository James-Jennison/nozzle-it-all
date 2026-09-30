package net.jamesjennison.klippercompanion

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class ActiveFilenameDeviceTest {
    @get:Rule val compose=createComposeRule()
    // WO-16: the FIRST onNodeWithText("0%").assertExists() (before openFixtureDashboard, still
    // on the Home tile view) was simply wrong, not device-dependent flakiness - confirmed via a
    // full semantics-tree dump (Compose's printToLog) against a real failure: PrinterTiles.kt's
    // own rendering (`if (activeFilename != null) { ...show filename + "<n>%"... } else {
    // Text("No active file") }`) never emits a percentage Text node at all once there's no
    // active file - by design, not a bug. Fixed to assert that node genuinely doesn't exist
    // instead. The SECOND occurrence (after openFixtureDashboard, which clicks into the
    // printer's own detail view) is a real, different screen - MainActivity.kt's detail hero
    // card always shows a percentage (`state.snapshot?.let {"${...}%"} ?: "—"`, tripping only
    // if snapshot itself were null, which it never is here) - so assertExists there was already
    // correct and is left unchanged. A third real bug in the same test: the second
    // onNodeWithText("Standby") check (also after openFixtureDashboard) expected the same
    // casing as the tile view's "Standby" (PrinterTiles.kt titlecases displayState), but the
    // detail view uppercases it instead (MainActivity.kt's hero card) - fixed to expect
    // "STANDBY" there.
    @Test fun retainedFilenameClearsOnCompletionInTileAndDetailAndReturnsForNextJob() {
        val address="http://fixture.local/"
        val snapshot=mutableStateOf(PrinterSnapshot(true,"printing","retained.gcode",.5f))
        compose.setContent { CompanionTheme { CompanionScreen(ScreenState(address=address,connected=true,snapshot=snapshot.value,savedPrinters=listOf(address)),{},{},{},{_,_->error("No commands expected")},tileCamera={}) } }
        compose.onNodeWithText("retained.gcode").assertExists()
        compose.runOnIdle {snapshot.value=snapshot.value.copy(state="complete",progress=1f)}
        compose.onNodeWithText("retained.gcode").assertDoesNotExist()
        compose.onNodeWithText("No active file").assertExists()
        compose.onNodeWithText("0%").assertDoesNotExist()
        // Shared family vocabulary (FamilyTerms.kt): a completed job reads "Finished", not the old raw "standby".
        compose.onNodeWithText("Finished").assertExists()
        compose.openFixtureDashboard()
        compose.onNodeWithText("No active file").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0%").assertExists()
        // The detail view opened by openFixtureDashboard (MainActivity.kt's hero card) shows the same shared
        // label uppercased ("FINISHED"); the tile view uses sentence case ("Finished").
        compose.onNodeWithText("FINISHED").assertExists()
        compose.runOnIdle {snapshot.value=snapshot.value.copy(state="paused")}
        compose.onNodeWithText("retained.gcode").assertIsDisplayed()
        compose.runOnIdle {snapshot.value=snapshot.value.copy(state="complete")}
        compose.onNodeWithText("retained.gcode").assertDoesNotExist()
        compose.onNodeWithText("No active file").assertIsDisplayed()
        compose.runOnIdle {snapshot.value=snapshot.value.copy(state="printing",filename="next.gcode",progress=.01f)}
        compose.onNodeWithText("next.gcode").assertIsDisplayed()
    }
}

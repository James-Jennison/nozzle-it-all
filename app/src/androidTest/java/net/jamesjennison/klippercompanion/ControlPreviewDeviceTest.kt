package net.jamesjennison.klippercompanion
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
class ControlPreviewDeviceTest {
 @get:Rule val compose=createComposeRule()
 private fun launch(){compose.setContent{CompanionTheme{ControlPreviewPanel{}}}}
 private fun select(text:String){compose.onNodeWithText(text).performScrollTo().performClick()}
 private fun preview(){compose.onNodeWithTag("preview-control").performScrollTo().performClick()}
 @Test fun simulationBlocksPrintingAndNeverExposesSend() {
  launch();select("Printing");preview()
  compose.onNodeWithTag("control-error").assertTextContains("Controls are blocked",substring=true)
  compose.onNodeWithTag("control-script").assertDoesNotExist()
  compose.onNodeWithText("Confirm").assertDoesNotExist()
 }
 @Test fun coldExtrusionAndUnhomedMotionShowActionableErrors() {
  launch();select("Cold nozzle");select("Extrude / retract");preview()
  compose.onNodeWithTag("control-error").assertTextContains("too cold",substring=true)
  select("Unhomed");select("Move X");preview()
  compose.onNodeWithTag("control-error").assertTextContains("Home all axes",substring=true)
 }
 @Test fun presetPreviewClearsOnChangeAndRejectsInjectedValue() {
  launch();select("PETG example");preview()
  compose.onNodeWithTag("control-script").assertTextContains("TARGET=230",substring=true)
  compose.onNodeWithTag("control-value").performScrollTo().performTextReplacement("200;G28")
  compose.onNodeWithTag("control-script").assertDoesNotExist();preview()
  compose.onNodeWithTag("control-error").assertTextContains("plain number",substring=true)
 }
 @Test fun actualScreenRoutesOnlyToLocalPreviewWithoutExecuteCallback() {
  var sent=0
  compose.setContent{CompanionTheme{CompanionScreen(ScreenState(address="http://fixture.local/"),{},{},{},{_,_->sent++})}}
  compose.onNodeWithTag("nav-1").performClick()
  compose.onNodeWithTag("advanced-control-preview").performScrollTo().performClick()
  preview();compose.onNodeWithTag("control-script").assertExists();assertEquals(0,sent)
 }
}

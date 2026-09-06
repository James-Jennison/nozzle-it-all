package net.jamesjennison.klippercompanion
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
class FileChangeDeviceTest {
 @get:Rule val compose=createComposeRule()
 private fun launch(){compose.setContent{CompanionTheme{FileChangePanel{}}}}
 private fun click(text:String){compose.onNodeWithText(text).performScrollTo().performClick()}
 private fun review(){compose.onNodeWithTag("review-file-change").performScrollTo().performClick()}
 private fun confirm(){compose.onNodeWithTag("confirm-file-change").performScrollTo().performClick()}
 private fun awaitNotice(text:String){compose.waitUntil(10000){compose.onAllNodesWithText(text,substring=true).fetchSemanticsNodes().isNotEmpty()}}
 @Test fun uploadRequiresReviewAndConfirmation() {
  launch();compose.onNodeWithTag("confirm-file-change").assertDoesNotExist();review();compose.onNodeWithText("Upload example (10 bytes) to uploaded.gcode?").assertExists();confirm();awaitNotice("Simulated upload completed.")
  compose.onNodeWithTag("confirm-file-change").assertDoesNotExist();click("Refresh simulated files");compose.onNode(hasText("uploaded.gcode") and !hasSetTextAction()).assertExists()
 }
 @Test fun collisionsAndEditedDraftsCannotConfirm() {
  launch();compose.onNodeWithTag("file-change-destination").performScrollTo().performTextReplacement("example.gcode");review()
  compose.onNodeWithTag("file-change-notice").assertTextContains("Destination already exists",substring=true);compose.onNodeWithTag("confirm-file-change").assertDoesNotExist()
  compose.onNodeWithTag("file-change-destination").performTextReplacement("new.gcode");review()
  compose.onNodeWithTag("file-change-destination").performScrollTo().performTextReplacement("changed.gcode");compose.onNodeWithTag("confirm-file-change").assertDoesNotExist()
 }
 @Test fun staleListRejectsDelete() {
  launch();click("Delete");review();click("Simulate concurrent change");confirm();awaitNotice("File list changed")
  click("Refresh simulated files");compose.onNode(hasText("example.gcode") and !hasSetTextAction()).assertExists()
 }
 @Test fun lostAckIsUnknownUntilExplicitRefresh() {
  launch();click("Lost acknowledgement");review();confirm();awaitNotice("Outcome unknown");compose.onNodeWithTag("review-file-change").assertIsNotEnabled()
  compose.onNodeWithTag("confirm-file-change").assertDoesNotExist();click("Refresh simulated files");compose.onNode(hasText("uploaded.gcode") and !hasSetTextAction()).assertExists()
 }
 @Test fun cancelDuringConfirmLeavesFilesUnchanged() {
  launch();review()
  compose.onNodeWithTag("confirm-file-change").performScrollTo()
  compose.onNodeWithTag("confirm-file-change").performClick()
  compose.onNodeWithTag("cancel-file-change").performScrollTo().performClick()
  awaitNotice("Cancelled before commit")
  compose.onNodeWithTag("confirm-file-change").assertDoesNotExist()
  click("Refresh simulated files")
  compose.onNode(hasText("uploaded.gcode") and !hasSetTextAction()).assertDoesNotExist()
  compose.onNode(hasText("example.gcode") and !hasSetTextAction()).assertExists()
 }
 @Test fun interruptedUploadRequiresRefreshAndLeavesNoFile() {
  launch();click("Interrupted");review();confirm();awaitNotice("Transfer interrupted before commit")
  compose.onNodeWithTag("review-file-change").assertIsNotEnabled()
  click("Refresh simulated files")
  compose.onNode(hasText("uploaded.gcode") and !hasSetTextAction()).assertDoesNotExist()
  compose.onNodeWithTag("review-file-change").assertIsEnabled()
 }
 @Test fun renameAndDeleteRequireSeparateConfirmations() {
  launch();click("Rename")
  compose.onNodeWithTag("file-change-destination").performScrollTo().performTextReplacement("renamed.gcode")
  review();confirm();awaitNotice("Simulated rename completed.")
  click("Refresh simulated files")
  compose.onNode(hasText("renamed.gcode") and !hasSetTextAction()).assertExists()
  click("Delete")
  compose.onNodeWithTag("file-change-source").performScrollTo().performTextReplacement("renamed.gcode")
  review();confirm();awaitNotice("Simulated delete completed.")
  click("Refresh simulated files")
  compose.onNode(hasText("renamed.gcode") and !hasSetTextAction()).assertDoesNotExist()
 }
}

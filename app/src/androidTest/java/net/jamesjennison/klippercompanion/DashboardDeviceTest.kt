package net.jamesjennison.klippercompanion
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
class DashboardDeviceTest {
 @get:Rule val compose=createComposeRule()
 @Test fun lightCardsHaveDistinctBackground() {
  var distinct=false
  compose.setContent {CompanionTheme(dark=false) { distinct=androidx.compose.material3.MaterialTheme.colorScheme.surface != androidx.compose.material3.MaterialTheme.colorScheme.background }}
  compose.runOnIdle {assertTrue(distinct)}
 }
 @Test fun hiddenCardsKeepConnectionAndCommandFeedback() {
  compose.setContent {CompanionTheme(dark=false,accent="Blue") {CompanionScreen(ScreenState(address="http://fixture.local/",commandNotice="Fixture command result"),{},{},{},{_,_->},appearance=DashboardOptions(hidden=DashboardOptions.cards.toSet()))}}
  compose.openFixtureDashboard(connected=false)
  compose.onNodeWithText("OFFLINE").assertIsDisplayed()
  compose.onNodeWithTag("command-notice").assertIsDisplayed()
  compose.onNodeWithText("Camera").assertDoesNotExist()
  compose.onNodeWithTag("customize-dashboard").assertIsDisplayed()
 }
 @Test fun editorChangesThemeAndPersistsRoundTrip() {
  var result=DashboardOptions()
  compose.setContent {var value by remember {mutableStateOf(result)};CompanionTheme {DashboardEditor(value,{value=it;result=it},{})}}
  compose.onNodeWithText("Light").performClick();compose.onNodeWithText("Lavender").performClick()
  compose.onNodeWithText("Move Camera down").performScrollTo().performClick()
  assertEquals("Light",result.mode);assertEquals("Lavender",result.accent)
  assertEquals("Print",result.order.first());assertEquals(result,DashboardOptions.decode(result.encode()))
 }
}

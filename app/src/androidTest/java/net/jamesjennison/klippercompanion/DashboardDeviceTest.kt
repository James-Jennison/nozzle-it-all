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
 // WO-16: this test never set savedPrinters, so openFixtureDashboard()'s search for a
 // "saved-connect:$address" tag could never find one - CompanionScreen only renders that tag
 // per entry in state.savedPrinters (MainActivity.kt's `items(state.savedPrinters, ...)`), with
 // no fallback for a bare address. A deterministic bug, not device-dependent flakiness - it
 // happened to still fail-fast identically everywhere it ran, just wasn't caught as a real bug
 // until compared against a passing sibling test (ActiveFilenameDeviceTest) that does set
 // savedPrinters correctly.
 @Test fun hiddenCardsKeepConnectionAndCommandFeedback() {
  compose.setContent {CompanionTheme(dark=false,accent="Blue") {CompanionScreen(ScreenState(address="http://fixture.local/",savedPrinters=listOf("http://fixture.local/"),commandNotice="Fixture command result"),{},{},{},{_,_->},appearance=DashboardOptions(hidden=DashboardOptions.cards.toSet()))}}
  compose.openFixtureDashboard(connected=false)
  compose.onNodeWithText("OFFLINE").performScrollTo().assertIsDisplayed()
  compose.onNodeWithTag("command-notice").assertIsDisplayed()
  compose.onNodeWithText("Camera").assertDoesNotExist()
  compose.onNodeWithTag("customize-dashboard").performScrollTo().assertIsDisplayed()
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

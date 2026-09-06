package net.jamesjennison.klippercompanion
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger
class ConsoleDeviceTest {
 @get:Rule val compose=createComposeRule()
 private val reads=AtomicInteger();private val closes=AtomicInteger()
 private fun factory(address:String)=object:ConsoleReader {
  override fun console():ConsoleBatch {reads.incrementAndGet();return ConsoleBatch(listOf(ConsoleEntry(1.0,"response","ok $address"),ConsoleEntry(2.0,"response","!! heater fault"),ConsoleEntry(3.0,"command","STATUS")))}
  override fun close(){closes.incrementAndGet()}
 }
 private fun ready(){compose.waitUntil(10000){compose.onAllNodesWithText("Recent cache",substring=true).fetchSemanticsNodes().isNotEmpty()}}
 @Test fun searchErrorFilterAndCopyOnlyMatchingText() {
  var copied=""
  compose.setContent{CompanionTheme{ConsolePanel("http://fixture.local/",true,{},::factory,{copied=it})}}
  ready();compose.onNodeWithTag("console-errors").performClick();compose.onNodeWithTag("console-search").performTextReplacement("heater")
  compose.onNodeWithText("1 matching messages",substring=true).assertExists()
  compose.onNodeWithTag("console-copy").performClick();assertTrue(copied.contains("heater fault"));assertFalse(copied.contains("STATUS"))
  compose.onNodeWithTag("console-search").performTextReplacement("missing");compose.onNodeWithTag("console-copy").assertIsNotEnabled()
 }
 @Test fun pauseStopsPollingResumeAndCloseReleaseReader() {
  var open by mutableStateOf(true)
  compose.setContent{CompanionTheme{if(open)ConsolePanel("http://fixture.local/",true,{open=false},::factory)}}
  ready();compose.onNodeWithText("Pause updates").performClick()
  compose.waitUntil(5000){closes.get()>0};val pausedReads=reads.get();Thread.sleep(3300);assertEquals(pausedReads,reads.get())
  compose.onNodeWithText("Resume updates").performClick();compose.waitUntil(5000){reads.get()>pausedReads}
  compose.onNodeWithText("Close").performClick();compose.waitUntil(5000){closes.get()>=2}
 }
 @Test fun pausedDisconnectClearsMessagesAndAddressSwitchDoesNotLeak() {
  var connected by mutableStateOf(true);var address by mutableStateOf("http://first.local/")
  compose.setContent{CompanionTheme{ConsolePanel(address,connected,{},::factory)}}
  ready();compose.onNodeWithText("Pause updates").performClick();compose.runOnIdle{connected=false}
  compose.onNodeWithText("Disconnected.",substring=true).assertExists();compose.onNodeWithTag("console-copy").assertIsNotEnabled()
  compose.runOnIdle{address="http://second.local/";connected=true};ready()
  compose.onAllNodesWithText("ok http://first.local/",substring=true).assertCountEquals(0)
  compose.onNodeWithText("ok http://second.local/",substring=true).assertExists()
 }
 @Test fun stoppedLifecycleClosesReaderAndResumingRefreshes() {
  val owner=object:LifecycleOwner{val registry=LifecycleRegistry(this);override val lifecycle:Lifecycle get()=registry}
  compose.runOnIdle{owner.registry.currentState=Lifecycle.State.RESUMED}
  compose.setContent{CompositionLocalProvider(LocalLifecycleOwner provides owner){CompanionTheme{ConsolePanel("http://fixture.local/",true,{},::factory)}}}
  ready();compose.runOnIdle{owner.registry.currentState=Lifecycle.State.CREATED}
  compose.waitUntil(5000){closes.get()>0};val count=reads.get();Thread.sleep(3300);assertEquals(count,reads.get())
  compose.runOnIdle{owner.registry.currentState=Lifecycle.State.RESUMED};compose.waitUntil(5000){reads.get()>count}
 }
 @Test fun realPrinterConsoleIsReadOnlyAndBounded() {
  val endpoint=androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("printerUrl")
  org.junit.Assume.assumeTrue("Explicit read-only endpoint required",!endpoint.isNullOrBlank())
  Moonraker(endpoint!!).use{api->val batch=api.console();assertTrue(batch.entries.size<=200);assertTrue(batch.entries.all{it.message.length<=2048})}
 }
 @Test fun unavailableConsoleIsHonestAndFixtureLayoutCanBeInspected() {
  compose.setContent{CompanionTheme{ConsolePanel("http://fixture.local/",true,{},::factory)}}
  ready()
  val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
  fun focused(){instrumentation.uiAutomation.executeShellCommand("dumpsys window").use{fd->val raw=java.io.FileInputStream(fd.fileDescriptor).bufferedReader().readText();val focus=raw.lineSequence().filter{it.contains("mCurrentFocus=")&&!it.trim().endsWith("=null")}.toList();check(focus.size==1 && focus.single().contains("net.jamesjennison.klippercompanion/")){"Companion not focused; capture refused"}}}
  focused();val bitmap=compose.onNode(isDialog()).captureToImage().asAndroidBitmap();focused()
  java.io.File(instrumentation.targetContext.cacheDir,"p11-console-fixture.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
 }
 @Test fun unavailableEndpointDisplaysFailureWithoutCommandEntry() {
  compose.setContent{CompanionTheme{ConsolePanel("http://fixture.local/",true,{}, {object:ConsoleReader{override fun console():ConsoleBatch=throw ApiFailure("fixture unavailable");override fun close(){}}})}}
  compose.waitUntil(10000){compose.onAllNodesWithText("Console unavailable.",substring=true).fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithTag("console-copy").assertIsNotEnabled();compose.onNodeWithText("Send").assertDoesNotExist()
 }
 @Test fun actualControlScreenRoutesToReadOnlyConsoleDuringPrint() {
  var sent=0
  compose.setContent{CompanionTheme{CompanionScreen(ScreenState(address="http://fixture.local/",connected=true,snapshot=PrinterSnapshot(true,"printing")),{},{},{},{_,_->sent++},consoleFactory=::factory)}}
  compose.onNodeWithTag("nav-1").performClick();compose.onNodeWithTag("open-console").performScrollTo().performClick()
  ready();assertTrue(reads.get()>0);assertEquals(0,sent);compose.onNodeWithText("Close").performClick()
  compose.onNodeWithTag("open-console").assertExists();assertEquals(0,sent)
 }
}

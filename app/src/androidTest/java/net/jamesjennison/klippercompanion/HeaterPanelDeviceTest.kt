package net.jamesjennison.klippercompanion

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HeaterPanelDeviceTest {
    @get:Rule val compose=createComposeRule()
    private val ready=ScreenState(address="http://fixture.local/",connected=true,snapshot=PrinterSnapshot(true,"complete",activeExtruder="extruder1"))
    private val factory:(String)->HeaterReader={object:HeaterReader {
        override fun heaterStatus(heater:String)=HeaterStatus(heater,"extruder1",true,"complete",34.0,0.0,0.0,if(heater=="heater_bed")100.0 else 300.0,true)
        override fun close(){}
    }}
    private fun review(){compose.onNodeWithTag("review-heater").performScrollTo().performClick()}
    private fun awaitCommand(){compose.waitUntil(5000){compose.onAllNodesWithTag("heater-script").fetchSemanticsNodes().isNotEmpty()}}
    @Test fun bedReviewRequiresSeparateConfirmationAndCannotReplay() {
        val sent=mutableListOf<PrinterCommand>()
        compose.setContent{CompanionTheme{HeaterPanel(ready,{c,_->sent.add(c)},{},factory)}}
        compose.onNodeWithTag("heater-value").performTextReplacement("40")
        review();awaitCommand();assertEquals(0,sent.size)
        compose.onNodeWithTag("heater-script").assertTextEquals("SET_HEATER_TEMPERATURE HEATER=heater_bed TARGET=40")
        compose.onNodeWithTag("confirm-heater").performScrollTo().performClick()
        assertEquals(1,sent.size);assertEquals("heater_bed",sent.single().heaterRequest?.heater)
        compose.onNodeWithTag("confirm-heater").assertDoesNotExist()
    }
    @Test fun activeNozzleUsesExactToolAndEditingInvalidatesConfirmation() {
        compose.setContent{CompanionTheme{HeaterPanel(ready,{_,_->error("Do not dispatch")},{},factory)}}
        compose.onNodeWithText("Active nozzle · extruder1").performScrollTo().performClick()
        compose.onNodeWithTag("heater-value").performScrollTo().performTextReplacement("40")
        review();awaitCommand()
        compose.onNodeWithTag("heater-script").assertTextEquals("SET_HEATER_TEMPERATURE HEATER=extruder1 TARGET=40")
        compose.onNodeWithTag("heater-value").performScrollTo().performTextReplacement("41")
        compose.onNodeWithTag("confirm-heater").assertDoesNotExist()
    }
    @Test fun printStartingInvalidatesPreparedHeating() {
        val state=mutableStateOf(ready)
        compose.setContent{CompanionTheme{HeaterPanel(state.value,{_,_->error("Do not dispatch")},{},factory)}}
        review();awaitCommand()
        compose.onNodeWithTag("heater-notice").assertExists()
        compose.runOnIdle{state.value=ready.copy(snapshot=PrinterSnapshot(true,"printing",activeExtruder="extruder1"))}
        compose.onNodeWithTag("confirm-heater").assertDoesNotExist()
        compose.onNodeWithTag("heater-notice").assertDoesNotExist()
        compose.onNodeWithTag("review-heater").performScrollTo().assertIsNotEnabled()
    }
    @Test fun backgroundClearsNoticeAndConfirmationWithoutReplayOnResume() {
        val owner=object:LifecycleOwner{val registry=LifecycleRegistry(this);override val lifecycle:Lifecycle get()=registry}
        compose.runOnIdle{owner.registry.currentState=Lifecycle.State.RESUMED}
        compose.setContent{CompositionLocalProvider(LocalLifecycleOwner provides owner){CompanionTheme{HeaterPanel(ready,{_,_->error("Do not dispatch")},{},factory)}}}
        review();awaitCommand();compose.onNodeWithTag("heater-notice").assertExists()
        compose.runOnIdle{owner.registry.currentState=Lifecycle.State.CREATED}
        compose.onNodeWithTag("heater-notice").assertDoesNotExist()
        compose.onNodeWithTag("confirm-heater").assertDoesNotExist()
        compose.onNodeWithTag("review-heater").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle{owner.registry.currentState=Lifecycle.State.RESUMED}
        compose.onNodeWithTag("heater-notice").assertDoesNotExist()
        compose.onNodeWithTag("confirm-heater").assertDoesNotExist()
        compose.onNodeWithTag("review-heater").assertIsEnabled()
    }
}

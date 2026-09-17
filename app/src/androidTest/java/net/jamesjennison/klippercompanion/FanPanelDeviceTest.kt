package net.jamesjennison.klippercompanion

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FanPanelDeviceTest {
    @get:Rule val compose=createComposeRule()
    private val ready=ScreenState(address="http://fixture.local/",connected=true,snapshot=PrinterSnapshot(true,"standby",activeExtruder="extruder1"))
    private val factory:(String)->FanReader={object:FanReader {
        override fun fans()=listOf("fan","fan_generic e1_fan")
        override fun fanStatus(fan:String)=FanStatus(fan,"extruder1",true,"standby",0.0,true,true)
        override fun close(){}
    }}
    private fun select(){
        compose.onNodeWithTag("load-fans").performScrollTo().performClick()
        compose.waitUntil(5000){compose.onAllNodesWithTag("fan-option-fan_generic e1_fan").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("fan-option-fan_generic e1_fan").performScrollTo().performClick()
    }
    private fun review(){compose.onNodeWithTag("review-fan").performScrollTo().performClick();compose.waitUntil(5000){compose.onAllNodesWithTag("fan-script").fetchSemanticsNodes().isNotEmpty()}}
    @Test fun explicitSelectionAndSeparateSingleUseConfirmation() {
        val sent=mutableListOf<PrinterCommand>()
        compose.setContent{CompanionTheme{FanPanel(ready,{c,_->sent.add(c)},{},factory)}}
        compose.onNodeWithTag("review-fan").performScrollTo().assertIsNotEnabled();select()
        compose.onNodeWithTag("fan-value").performScrollTo().performTextReplacement("25")
        review();compose.onNodeWithTag("fan-script").assertTextEquals("SET_FAN_SPEED FAN=e1_fan SPEED=0.25");assertTrue(sent.isEmpty())
        compose.onNodeWithTag("confirm-fan").performScrollTo().performClick();assertEquals(1,sent.size);compose.onNodeWithTag("confirm-fan").assertDoesNotExist()
    }
    @Test fun expiryAndToolChangeInvalidateWithoutDispatch() {
        var now=1000L;val state=mutableStateOf(ready)
        compose.setContent{CompanionTheme{FanPanel(state.value,{_,_->error("Do not dispatch")},{},factory,{now})}}
        select();review();now+=5001
        compose.onNodeWithTag("confirm-fan").performScrollTo().performClick();compose.onNodeWithTag("confirm-fan").assertDoesNotExist()
        review();compose.runOnIdle{state.value=ready.copy(snapshot=PrinterSnapshot(true,"standby",activeExtruder="extruder2"))}
        compose.onNodeWithTag("confirm-fan").assertDoesNotExist();compose.onNodeWithTag("fan-notice").assertDoesNotExist();compose.onNodeWithTag("review-fan").performScrollTo().assertIsNotEnabled()
    }
    @Test fun longOverflowIsRejectedRatherThanTruncatedIntoValidInput() {
        compose.setContent{CompanionTheme{FanPanel(ready,{_,_->error("Do not dispatch")},{},factory)}};select()
        compose.onNodeWithTag("fan-value").performScrollTo().performTextReplacement("100.000000000000000000000000001")
        compose.onNodeWithTag("review-fan").performScrollTo().performClick()
        compose.waitUntil(5000){compose.onAllNodesWithText("Enter a plain percentage from 0 to 100.").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("confirm-fan").assertDoesNotExist()
    }
}

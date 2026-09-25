package net.jamesjennison.klippercompanion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

fun ComposeContentTestRule.openFixtureDashboard(address: String = "http://fixture.local/", connected: Boolean = true) {
    onNodeWithTag("nav-0").performClick()
    if(connected) {
        val tag = "printer-tile:${Moonraker.parseAddress(address)}"
        onNodeWithTag("screen-list").performScrollToNode(hasTestTag(tag))
        onNodeWithTag(tag).performClick()
    } else {
        // The old single-field "type an address, tap Connect" flow this used to drive
        // ("connect-printer") was removed when AddPrinterWizard replaced it entirely (WO-13) -
        // reaching an offline printer's own detail view now goes through the Settings tab's
        // saved-printers list instead, same as a real user would. Requires the caller's own
        // ScreenState to list `address` in savedPrinters.
        val tag = "saved-connect:$address"
        onNodeWithTag("nav-4").performClick()
        onNodeWithTag("screen-list").performScrollToNode(hasTestTag(tag))
        waitForIdle()
        // Semantic click: on the Galaxy S25 the coordinate tap after the scroll never registered,
        // so the detail view never opened (the next assertion found no OFFLINE label).
        onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitForIdle()
    }
}

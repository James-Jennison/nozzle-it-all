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
        onNodeWithTag("nav-4").performClick()
        onNodeWithTag("screen-list").performScrollToNode(hasTestTag("connect-printer"))
        onNodeWithTag("connect-printer").performClick()
    }
}

package net.jamesjennison.klippercompanion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class EnvironmentNavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun environmentReturnsToItsOriginAndKeepsNavigationSelected() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".environment"))
        for (origin in listOf(0, 4)) {
            compose.onNodeWithTag("nav-$origin").performClick()
            compose.onNodeWithTag("open-environment").performScrollTo().performClick()
            compose.onNodeWithTag("environment-panel").assertExists()
            compose.onNodeWithTag("nav-$origin").assertIsSelected()
            compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("environment-panel").assertDoesNotExist()
            compose.onNodeWithTag("nav-$origin").assertIsSelected()
            compose.onNodeWithTag("open-environment").assertExists()
        }
    }
}

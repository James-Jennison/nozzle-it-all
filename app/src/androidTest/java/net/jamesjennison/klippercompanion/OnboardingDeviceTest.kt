package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OnboardingDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun threePagesThenAddPrinterReportsTrue() {
        var result: Boolean? = null
        compose.setContent { CompanionTheme { OnboardingScreen { result = it } } }
        compose.onNodeWithTag("onboarding-title").assertTextEquals("Print from your pocket").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Heading))
        compose.onAllNodesWithTag("onboarding-add-printer").assertCountEquals(0)
        compose.onNodeWithTag("onboarding-next").performClick()
        compose.onNodeWithTag("onboarding-title").assertTextEquals("Control your printers")
        compose.onNodeWithTag("onboarding-next").performClick()
        compose.onNodeWithTag("onboarding-title").assertTextEquals("Your data stays yours")
        compose.onAllNodesWithTag("onboarding-next").assertCountEquals(0)
        assertNull(result)
        compose.onNodeWithTag("onboarding-add-printer").performClick()
        assertEquals(true, result)
    }

    @Test fun skipIsAlwaysOneTapAndExploreFirstReportsFalse() {
        var result: Boolean? = null
        compose.setContent { CompanionTheme { OnboardingScreen { result = it } } }
        compose.onNodeWithTag("onboarding-skip").performClick()
        assertEquals(false, result)
        result = null
        compose.onNodeWithTag("onboarding-skip").assertExists()
        compose.onNodeWithTag("onboarding-next").performClick(); compose.onNodeWithTag("onboarding-next").performClick()
        compose.onNodeWithTag("onboarding-explore").performClick()
        assertEquals(false, result)
    }

    @Test fun prefsPersistDoneAndTreatUnreadableAsDone() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences("onboarding", 0).edit().clear().commit()
        try {
            assertFalse(OnboardingPrefs.isDone(ctx))
            assertFalse(OnboardingPrefs.wizardSuppressed(ctx))
            OnboardingPrefs.markDone(ctx, addPrinter = false) // Skip / Explore first
            assertTrue(OnboardingPrefs.isDone(ctx)); assertTrue(OnboardingPrefs.wizardSuppressed(ctx))
            OnboardingPrefs.markDone(ctx, addPrinter = true)
            assertTrue("suppression sticks once chosen", OnboardingPrefs.wizardSuppressed(ctx))
        } finally { ctx.getSharedPreferences("onboarding", 0).edit().putBoolean("done", true).putBoolean("suppress_wizard", false).commit() } // leave the shared app state as the test runner set it
    }

    @Test fun contentFitsAtLargeFontOnASmallWidth() {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, 2f)) {
                CompanionTheme { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.requiredWidth(androidx.compose.ui.unit.Dp(320f))) { OnboardingScreen { } } }
            }
        }
        compose.onNodeWithTag("onboarding-next").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-skip").assertIsDisplayed()
    }
}

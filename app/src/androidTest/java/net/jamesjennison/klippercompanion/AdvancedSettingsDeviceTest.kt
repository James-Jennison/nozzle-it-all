package net.jamesjennison.klippercompanion

import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdvancedSettingsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var overrides by mutableStateOf<Map<String, String>>(emptyMap())
    private val store get() = CustomProfileStore(InstrumentationRegistry.getInstrumentation().targetContext)
    @After fun cleanup() { store.all().filter { it.name.startsWith("T9a") }.forEach { store.delete(it.name) } }

    private fun show() = compose.setContent { CompanionTheme { androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) { AdvancedSettingsPanel(overrides, "GENERIC_KLIPPER") { overrides = it } } } }

    @Test fun tiersRevealMoreSettingsAndSearchNarrowsThem() {
        show()
        compose.onNodeWithTag("setting-wall_loops").assertExists()
        compose.onAllNodesWithTag("setting-travel_speed").assertCountEquals(0) // expert-only
        compose.onNodeWithTag("advanced-tier-expert").performClick()
        compose.onNodeWithTag("setting-travel_speed").assertExists()
        compose.onNodeWithTag("advanced-search").performTextInput("vase")
        compose.onNodeWithTag("setting-spiral_mode").assertExists(); compose.onAllNodesWithTag("setting-wall_loops").assertCountEquals(0)
        compose.onNodeWithTag("advanced-search").performTextClearance(); compose.onNodeWithTag("advanced-search").performTextInput("zzzz")
        compose.onNodeWithTag("advanced-empty").assertExists()
    }

    @Test fun validValuesBecomeOverridesInvalidOnesDoNotAndResetClears() {
        show()
        compose.onNodeWithTag("setting-input-wall_loops").performTextInput("99")
        compose.waitForIdle(); assertTrue(overrides.isEmpty())
        compose.onNodeWithTag("setting-input-wall_loops").performTextClearance(); compose.onNodeWithTag("setting-input-wall_loops").performTextInput("5")
        compose.waitUntil(3000) { overrides["wall_loops"] == "5" }
        compose.onNodeWithTag("setting-reset-wall_loops").performScrollTo().performClick()
        compose.waitUntil(3000) { overrides.isEmpty() }
    }

    @Test fun savedProfileAppliesAndComparesAgainstCurrent() {
        overrides = mapOf("wall_loops" to "5", "brim_width" to "0")
        show()
        compose.onNodeWithTag("advanced-profile-name").performScrollTo().performTextInput("T9a strong")
        compose.onNodeWithTag("advanced-profile-save").performScrollTo().performClick()
        compose.waitUntil(3000) { store.all().any { it.name == "T9a strong" } }
        assertEquals(mapOf("wall_loops" to "5", "brim_width" to "0"), store.all().first { it.name == "T9a strong" }.overrides)
        compose.runOnIdle { overrides = mapOf("wall_loops" to "2") }
        compose.onNodeWithTag("advanced-profile-compare-T9a strong").performScrollTo().performClick()
        compose.onNodeWithTag("advanced-compare").assertTextContains("Current vs T9a strong", substring = true)
        compose.onNodeWithTag("advanced-profile-apply-T9a strong").performScrollTo().performClick()
        compose.waitUntil(3000) { overrides == mapOf("wall_loops" to "5", "brim_width" to "0") }
    }
}

package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.nozzleitall.environment.*
import net.jamesjennison.klippercompanion.environment.GoveeDiscoveryCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GoveeDiscoveryCardDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun importedExtendedIdentityFillsCorrectCardWithoutMacSelection() {
        val first = Candidate("AA:BB:CC:DD:EE:FF", "govee-ble", "H5110")
        val second = Candidate("AB:BB:CC:DD:EE:FF", "govee-ble", "H5110")
        val directory = mutableStateOf<List<GoveeDeviceName>>(emptyList())
        val saved = mutableListOf<String>()
        compose.setContent { MaterialTheme { Column {
            listOf(first, second).forEach { candidate ->
                val name = directory.value.singleOrNull { it.matches(candidate) }?.name ?: candidate.defaultName
                GoveeDiscoveryCard(candidate, null, name, false, false, {}, { saved += name })
            }
        } } }
        compose.runOnIdle { directory.value = listOf(
            GoveeDeviceName("11:22:AA:BB:CC:DD:EE:FF", "H5110", "Front left"),
            GoveeDeviceName("33:44:AB:BB:CC:DD:EE:FF", "H5110", "Rear right")) }
        compose.onNodeWithTag("govee-name-${first.address}").assertTextContains("Front left")
        compose.onNodeWithTag("govee-name-${second.address}").assertTextContains("Rear right")
        compose.onNodeWithTag("govee-add-${first.address}").performClick()
        compose.onNodeWithTag("govee-add-${second.address}").performClick()
        compose.runOnIdle { assertEquals(listOf("Front left", "Rear right"), saved) }
    }
    @Test fun namesAreIndependentAndSaveUsesLatestDraft() {
        val selected = mutableListOf<Pair<String, String>>()
        compose.setContent {
            val drafts = remember { mutableStateMapOf("one" to "Sensor one", "two" to "Sensor two") }
            MaterialTheme { Column {
                listOf("one", "two").forEach { address ->
                    GoveeDiscoveryCard(Candidate(address, "govee-ble", "H5100"), null, drafts.getValue(address), false, false,
                        { drafts[address] = it }, { selected += address to drafts.getValue(address) })
                }
            } }
        }
        compose.onNodeWithTag("govee-name-one").performTextReplacement("Office shelf")
        compose.onNodeWithTag("govee-name-two").assertTextContains("Sensor two")
        compose.onNodeWithTag("govee-add-one").performClick()
        compose.onNodeWithTag("govee-name-two").performTextReplacement("Garage shelf")
        compose.onNodeWithTag("govee-add-two").performClick()
        compose.runOnIdle { assertEquals(listOf("one" to "Office shelf", "two" to "Garage shelf"), selected) }
        compose.onAllNodesWithText("Readings unavailable").assertCountEquals(2)
        compose.onAllNodesWithText("Signal: unavailable · Last radio reception: unavailable").assertCountEquals(2)
        compose.onAllNodes(hasText("unqualified", substring = true)).assertCountEquals(0)
    }
    @Test fun addedSensorCannotBeAddedAgainAndActualReceptionIsShown() {
        compose.setContent { MaterialTheme {
            GoveeDiscoveryCard(Candidate("one", "govee-ble", "H5100"), DiscoveryReception(listOf(Reading(Channel.TEMPERATURE, 23.5)), 1000, -55),
                "Office shelf", true, false, {}, { error("Already-added action must remain disabled") })
        } }
        compose.onNodeWithTag("govee-add-one").assertIsNotEnabled()
        compose.onNodeWithTag("govee-name-one").assertIsNotEnabled()
        compose.onNodeWithText("Already added").assertExists()
        compose.onNode(hasText("23.5", substring = true)).assertExists()
        compose.onNode(hasText("-55 dBm", substring = true)).assertExists()
        compose.onNode(hasText("1970-01-01T00:00:01Z", substring = true)).assertExists()
    }
}

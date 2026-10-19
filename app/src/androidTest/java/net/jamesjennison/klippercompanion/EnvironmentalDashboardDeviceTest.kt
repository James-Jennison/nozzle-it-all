package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.nozzleitall.environment.*
import net.jamesjennison.klippercompanion.environment.EnvironmentalDashboard
import org.junit.Rule
import org.junit.Test

class EnvironmentalDashboardDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun s4IllustrationSummarizesRealReadingsAndDrawsSharedTrends() {
        val names = EnvironmentalGroupOverview.corners.map { "S4 $it" }
        val bindings = names.indices.map { Binding("b$it", "s$it", "address$it", "govee-ble", "H5100") }
        val samples = bindings.flatMapIndexed { index, b -> (0..2).map { i ->
            ChartSample("${b.id}-$i", b.id, 1000L + i * 1000, "r", 0,
                mapOf(Channel.TEMPERATURE to 40.0 + index, Channel.HUMIDITY to 20.0 + index), i.toLong())
        } }
        // Keep the captured Canvas above the real phone's navigation-bar contrast scrim.
        compose.setContent { MaterialTheme { Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) {
            EnvironmentalDashboard(EnvironmentalView(EnvironmentalState.ENABLED_IDLE, bindings = bindings,
                sensorLabels = bindings.mapIndexed { i, b -> b.sensorId to names[i] }.toMap()), samples, historyMode = true)
        } } }
        compose.onNodeWithTag("s4-dryer-illustration").assertIsDisplayed()
        compose.onNodeWithText("Average  41.5 °C").assertExists()
        compose.onNodeWithText("Range  40.0 – 43.0 °C").assertExists()
        compose.onNodeWithTag("s4-group-trend-TEMPERATURE").performScrollTo().assertIsDisplayed()
        val plotted = compose.onNodeWithTag("s4-group-trend-TEMPERATURE").captureToImage().asAndroidBitmap()
        val traceOutput = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "s4-traces.png")
        traceOutput.outputStream().use { plotted.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val colors = listOf(0xFFB59AFF.toInt(), 0xFF35B9C5.toInt(), 0xFFFFC66D.toInt(), 0xFFFF91B5.toInt())
        colors.forEach { color ->
            var pixels = 0
            for (y in 0 until plotted.height) for (x in 0 until plotted.width) {
                val p = plotted.getPixel(x, y)
                if (kotlin.math.abs(android.graphics.Color.red(p) - android.graphics.Color.red(color)) < 10 &&
                    kotlin.math.abs(android.graphics.Color.green(p) - android.graphics.Color.green(color)) < 10 &&
                    kotlin.math.abs(android.graphics.Color.blue(p) - android.graphics.Color.blue(color)) < 10) pixels++
            }
            org.junit.Assert.assertTrue("Each actual trend stroke renders inside tagged Canvas: color=$color pixels=$pixels width=${plotted.width} height=${plotted.height}", pixels > plotted.width)
        }
        compose.onNode(hasContentDescription("S4 TEMPERATURE trends: 4 sensors", substring = true)).assertExists()
        compose.onNodeWithTag("s4-group-trend-HUMIDITY").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("s4-dryer-illustration").performScrollTo()
        val output = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "s4-synthetic.png")
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        org.junit.Assert.assertTrue("Diagnostic screenshot retained for visual inspection", output.length() > 1000)
    }
    @Test fun obviousNamedGroupHasHeadingAndOrderedCards() {
        val names = listOf("S4 Back Left", "Printing Room", "S4 Front Right", "S4 Back Right", "S4 Front Left")
        val bindings = names.indices.map { Binding("b$it", "s$it", "address$it", "govee-ble", "H5100") }
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            EnvironmentalDashboard(EnvironmentalView(EnvironmentalState.ENABLED_IDLE, bindings = bindings,
                sensorLabels = bindings.mapIndexed { i, b -> b.sensorId to names[i] }.toMap()), emptyList())
        } } }
        compose.onNodeWithText("S4", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Other sensors", useUnmergedTree = true).assertExists()
        val cards = compose.onAllNodesWithTag("environment-sensor-card").fetchSemanticsNodes()
        val expected = listOf("Printing Room")
        org.junit.Assert.assertEquals(expected.size, cards.size)
        cards.zip(expected).forEach { (node, name) ->
            org.junit.Assert.assertTrue(node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].any { it.text == name })
        }
        compose.onNodeWithText("S4 Front Left · trends").assertExists()
        compose.onNodeWithTag("s4-dryer-illustration").assertExists()
        compose.onNodeWithTag("s4-position-Back-Right").performClick()
        compose.onNodeWithTag("s4-position-Back-Right").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Charts shown"))
        compose.onNodeWithText("S4 Back Right · trends").assertExists()
    }
    @Test fun unmappableS4KeepsAllCardsAndEmptyHistoryHasNoEpochDate() {
        val names = mutableStateOf(listOf("S4 Front Left", "S4 Top"))
        val bindings = (0..1).map { Binding("b$it", "s$it", "a$it", "govee-ble", "H5100") }
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            EnvironmentalDashboard(EnvironmentalView(EnvironmentalState.ENABLED_IDLE, bindings = bindings,
                sensorLabels = bindings.mapIndexed { i, b -> b.sensorId to names.value[i] }.toMap()), emptyList(), historyMode = true)
        } } }
        compose.onNodeWithTag("s4-dryer-illustration").assertDoesNotExist()
        compose.onAllNodesWithTag("environment-sensor-card").assertCountEquals(2)
        compose.runOnIdle { names.value = listOf("S4 Front Left", "S4 Back Right") }
        compose.onNodeWithText("Saved history · no saved group readings").assertExists()
        compose.onAllNodesWithText("Not assigned").assertCountEquals(2)
    }
    @Test fun newReceptionContributesImmediatelyWithoutWaitingForClockTick() {
        val bindings = (0..1).map { Binding("b$it", "s$it", "a$it", "govee-ble", "H5100") }
        val view = mutableStateOf(EnvironmentalView(EnvironmentalState.DISCOVERING, bindings = bindings,
            sensorLabels = mapOf("s0" to "S4 Front Left", "s1" to "S4 Back Right")))
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            EnvironmentalDashboard(view.value, emptyList())
        } } }
        compose.runOnIdle { view.value = view.value.copy(discovery = mapOf("a0" to DiscoveryReception(
            listOf(Reading(Channel.TEMPERATURE, 41.0)), System.currentTimeMillis(), null))) }
        compose.onNodeWithText("Average  41.0 °C").assertExists()
        compose.onNodeWithText("Future timestamp").assertDoesNotExist()
    }
    @Test fun explicitSavedHistoryCanRenderWhileMonitoringIsOff() {
        val binding = Binding("b", "s", "AA:BB:CC:DD:EE:FF", "govee-ble", "H5100")
        val sample = ChartSample("event", "b", 1000, "run", 0, mapOf(Channel.TEMPERATURE to 23.0), 1)
        val state = mutableStateOf(EnvironmentalState.DISABLED)
        val history = mutableStateOf(true)
        compose.setContent { MaterialTheme { EnvironmentalDashboard(
            EnvironmentalView(state.value, bindings = listOf(binding)), listOf(sample), historyMode = history.value) } }
        compose.onNodeWithText("23.0°C").assertExists()
        compose.runOnIdle { state.value = EnvironmentalState.DISABLED_RECOVERY }
        compose.onNodeWithText("23.0°C").assertExists()
        compose.runOnIdle { history.value = false }
        compose.onNodeWithText("23.0°C").assertDoesNotExist()
    }
    @Test fun lastReceptionAdvancesAndSurvivesStopWithoutCreatingCharts() {
        val binding = Binding("b", "s", "AA:BB:CC:DD:EE:FF", "govee-ble", "H5100")
        val state = mutableStateOf(EnvironmentalView(EnvironmentalState.DISCOVERING, bindings = listOf(binding),
            discovery = mapOf(binding.address to DiscoveryReception(listOf(Reading(Channel.TEMPERATURE, 24.0)), 1000, null))))
        val history = mutableStateOf(false)
        compose.setContent { MaterialTheme { EnvironmentalDashboard(state.value, emptyList(), historyMode = history.value) } }
        compose.onNodeWithText("24.0°C").assertExists()
        compose.runOnIdle {
            state.value = state.value.copy(discovery = mapOf(binding.address to
                DiscoveryReception(listOf(Reading(Channel.TEMPERATURE, 25.0)), 2000, null)))
        }
        compose.onNodeWithText("25.0°C").assertExists()
        compose.runOnIdle { state.value = state.value.copy(state = EnvironmentalState.ENABLED_IDLE, discovery = emptyMap()) }
        compose.onNodeWithText("25.0°C").assertExists()
        compose.onNodeWithTag("environment-trend-TEMPERATURE").assertDoesNotExist()
        compose.runOnIdle { history.value = true }
        compose.onNodeWithText("25.0°C").assertDoesNotExist()
        compose.onNodeWithText("No saved reading in this page").assertExists()
        compose.onNodeWithTag("environment-trend-TEMPERATURE").assertDoesNotExist()
        compose.runOnIdle { history.value = false }
        compose.onNodeWithText("25.0°C").assertExists()
    }
    @Test fun realSamplesRenderBothAccessibleChartsAndSensorName() {
        val binding = Binding(id = "fixture", sensorId = "sensor", address = "AA:BB:CC:DD:EE:FF", protocol = "govee-ble", model = "H5100")
        val samples = (0..2).map { i -> ChartSample("id$i", binding.id, 1000L + i * 1000, "session", 0,
            mapOf(Channel.TEMPERATURE to 20.0 + i, Channel.HUMIDITY to 40.0 + i), i.toLong()) }
        var primary = 0
        compose.setContent { MaterialTheme {
            val color = MaterialTheme.colorScheme.primary.toArgb()
            SideEffect { primary = color }
            Column(Modifier.verticalScroll(rememberScrollState())) {
            EnvironmentalDashboard(EnvironmentalView(EnvironmentalState.ENABLED_IDLE, bindings = listOf(binding),
                sensorLabels = mapOf("sensor" to "Office shelf")), samples, historyMode = true)
        } } }
        compose.onNodeWithText("Office shelf").assertExists()
        compose.onNodeWithTag("environment-sensor-card").assertExists()
        compose.onNodeWithTag("environment-trend-TEMPERATURE").performScrollTo().assertIsDisplayed()
        val chart = compose.onNodeWithTag("environment-trend-TEMPERATURE").captureToImage().asAndroidBitmap()
        org.junit.Assert.assertTrue(chart.width > 100 && chart.height > 100)
        var colored = 0
        var firstRow = chart.height; var lastRow = -1
        for (y in 0 until chart.height) for (x in 0 until chart.width) {
            val pixel = chart.getPixel(x, y)
            if (android.graphics.Color.alpha(pixel) > 128 &&
                kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(primary)) < 16 &&
                kotlin.math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(primary)) < 16 &&
                kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(primary)) < 16) {
                colored++; firstRow = minOf(firstRow, y); lastRow = maxOf(lastRow, y)
            }
        }
        org.junit.Assert.assertTrue("Real colored trend strokes, not an empty Canvas or only three dots", colored > chart.width)
        org.junit.Assert.assertTrue("Known rising series spans the chart vertically", lastRow - firstRow > chart.height / 3)
        compose.onNode(hasContentDescription("Temperature: 3 samples", substring = true)).assertExists()
        compose.onNodeWithTag("environment-trend-HUMIDITY").performScrollTo().assertIsDisplayed()
        compose.onNode(hasContentDescription("Humidity: 3 samples", substring = true)).assertExists()
        val output = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "dashboard-synthetic.png")
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun emptyViewHasNoInventedCharts() {
        compose.setContent { MaterialTheme { EnvironmentalDashboard(EnvironmentalView(EnvironmentalState.ENABLED_IDLE), emptyList()) } }
        compose.onNodeWithText("Your environment, at a glance").assertExists()
        compose.onNodeWithTag("environment-trend-TEMPERATURE").assertDoesNotExist()
    }
    @Test fun firstHumidityReadingHasOneTimeLabelAndNoInventedTemperature() {
        val binding = Binding("b", "s", "AA:BB:CC:DD:EE:FF", "govee-ble", "H5100")
        val sample = ChartSample("event", "b", 1000, "run", 0, mapOf(Channel.HUMIDITY to 42.0), 1)
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            EnvironmentalDashboard(EnvironmentalView(EnvironmentalState.ENABLED_IDLE, bindings = listOf(binding)), listOf(sample), historyMode = true)
        } } }
        compose.onNodeWithText("No recorded temperature trend yet").assertExists()
        compose.onNodeWithTag("environment-trend-TEMPERATURE").assertDoesNotExist()
        compose.onNodeWithTag("environment-trend-HUMIDITY").performScrollTo().assertIsDisplayed()
        val time = java.time.format.DateTimeFormatter.ofPattern("MMM d HH:mm:ss").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(1000))
        compose.onAllNodesWithText(time).assertCountEquals(1)
    }
}

package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.Column
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
    @Test fun lastReceptionAdvancesAndSurvivesStopWithoutCreatingCharts() {
        val binding = Binding("b", "s", "AA:BB:CC:DD:EE:FF", "govee-ble", "H5100")
        val state = mutableStateOf(EnvironmentalView(EnvironmentalState.DISCOVERING, bindings = listOf(binding),
            discovery = mapOf(binding.address to DiscoveryReception(listOf(Reading(Channel.TEMPERATURE, 24.0)), 1000, null))))
        compose.setContent { MaterialTheme { EnvironmentalDashboard(state.value, emptyList()) } }
        compose.onNodeWithText("24.0°C").assertExists()
        compose.runOnIdle {
            state.value = state.value.copy(discovery = mapOf(binding.address to
                DiscoveryReception(listOf(Reading(Channel.TEMPERATURE, 25.0)), 2000, null)))
        }
        compose.onNodeWithText("25.0°C").assertExists()
        compose.runOnIdle { state.value = state.value.copy(state = EnvironmentalState.ENABLED_IDLE, discovery = emptyMap()) }
        compose.onNodeWithText("25.0°C").assertExists()
        compose.onNodeWithTag("environment-trend-TEMPERATURE").assertDoesNotExist()
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
}

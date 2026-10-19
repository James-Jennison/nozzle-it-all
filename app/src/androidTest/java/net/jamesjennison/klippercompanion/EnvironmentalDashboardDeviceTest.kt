package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.nozzleitall.environment.*
import net.jamesjennison.klippercompanion.environment.EnvironmentalDashboard
import org.junit.Rule
import org.junit.Test

class EnvironmentalDashboardDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun realSamplesRenderBothAccessibleChartsAndSensorName() {
        val binding = Binding(id = "fixture", sensorId = "sensor", address = "AA:BB:CC:DD:EE:FF", protocol = "govee-ble", model = "H5100")
        val samples = (0..2).map { i -> ChartSample("id$i", binding.id, 1000L + i * 1000, "session", 0,
            mapOf(Channel.TEMPERATURE to 20.0 + i, Channel.HUMIDITY to 40.0 + i), i.toLong()) }
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            EnvironmentalDashboard(EnvironmentalView(EnvironmentalState.ENABLED_IDLE, bindings = listOf(binding),
                sensorLabels = mapOf("sensor" to "Office shelf")), samples, historyMode = true)
        } } }
        compose.onNodeWithText("Office shelf").assertExists()
        compose.onNodeWithTag("environment-sensor-card").assertExists()
        compose.onNodeWithTag("environment-trend-TEMPERATURE").performScrollTo().assertIsDisplayed()
        compose.onNode(hasContentDescription("Temperature: 3 samples", substring = true)).assertExists()
        compose.onNodeWithTag("environment-trend-HUMIDITY").performScrollTo().assertIsDisplayed()
        compose.onNode(hasContentDescription("Humidity: 3 samples", substring = true)).assertExists()
        val output = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "dashboard-synthetic.png")
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun emptyViewHasNoInventedCharts() {
        compose.setContent { MaterialTheme { EnvironmentalDashboard(EnvironmentalView(EnvironmentalState.ENABLED_IDLE), emptyList()) } }
        compose.onNodeWithText("Discover and add sensors, or open local history to restore saved sensors.").assertExists()
        compose.onNodeWithTag("environment-trend-TEMPERATURE").assertDoesNotExist()
    }
}

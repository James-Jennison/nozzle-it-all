package com.nozzleitall.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.screens.EnvironmentalDashboard
import com.nozzleitall.desktop.ui.NozzleTheme
import com.nozzleitall.environment.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.swing.SwingUtilities

/** Offscreen software-rendered fixture, not installed-app/GPU/physical-radio proof. */
class EnvironmentalDashboardRenderTest {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun desktopDashboardRendersActualSeriesToDiagnosticImage() {
        val binding = Binding("fixture", "sensor", "AA:BB:CC:DD:EE:FF", "govee-ble", "H5100")
        val samples = (0..10).map { i -> ChartSample("id$i", binding.id, 1000L + i * 1000, "session", 0,
            mapOf(Channel.TEMPERATURE to 20.0 + i / 10.0, Channel.HUMIDITY to 40.0 + i / 10.0), i.toLong()) }
        val output = File("build/reports/dashboard-synthetic.png")
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(width = 900, height = 1000) {
                NozzleTheme("dark") { Column(Modifier.fillMaxSize().background(Color(0xFF101418)).padding(16.dp)) {
                    EnvironmentalDashboard(EnvironmentalView(EnvironmentalState.ENABLED_IDLE, bindings = listOf(binding),
                        sensorLabels = mapOf("sensor" to "Office shelf")), samples, historyMode = true)
                } }
            }
            try {
                scene.render().close()
                scene.render(1_000_000_000L).use { image ->
                    image.encodeToData()!!.use { data -> output.parentFile.mkdirs(); output.writeBytes(data.bytes) }
                }
            } finally { scene.close() }
        }
        val image = javax.imageio.ImageIO.read(output)
        assertEquals(900, image.width); assertEquals(1000, image.height)
        var accentPixels = 0
        for (y in 180 until 650) for (x in 20 until 880) {
            val color = java.awt.Color(image.getRGB(x, y))
            if (color.blue - color.green > 25 && color.red - color.green > 25) accentPixels++
        }
        assertTrue("Visible purple chart strokes in rendered dashboard", accentPixels > 1000)
    }
}

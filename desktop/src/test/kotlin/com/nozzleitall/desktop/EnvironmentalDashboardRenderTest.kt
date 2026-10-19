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
    @Test fun packagedArtworkDecodesAndMatchesAndroid() {
        val bytes = javaClass.classLoader.getResourceAsStream("environment/s4-dryer.png")!!.use { it.readBytes() }
        assertArrayEquals(bytes, File("../app/src/main/res/drawable-nodpi/s4_dryer.png").readBytes())
        val image = javax.imageio.ImageIO.read(bytes.inputStream())
        assertEquals(1402, image.width); assertEquals(1122, image.height)
        assertTrue(image.colorModel.hasAlpha())
        assertEquals(image.width.toFloat() / image.height, com.nozzleitall.desktop.screens.s4ArtworkAspect, .0001f)
    }
    @Test fun anchorGeometryTracksFittedArtworkAtEveryLayoutBranch() {
        for (width in listOf(320f, 480f, 640f)) for (scale in listOf(1f, 1.3f, 2f)) {
            val height = (if (width >= 480 && scale <= 1.2f) 260f else 350f) * scale
            val rect = com.nozzleitall.desktop.screens.s4ArtworkRect(width, height, 48f, 4f)
            assertEquals(com.nozzleitall.desktop.screens.s4ArtworkAspect, rect.width / rect.height, .0001f)
            assertTrue(rect.left >= 48f - .001f && rect.top >= 4f - .001f)
            com.nozzleitall.desktop.screens.s4ArtworkAnchors.forEach {
                assertTrue(rect.contains(androidx.compose.ui.geometry.Offset(rect.left + it.x * rect.width, rect.top + it.y * rect.height)))
            }
        }
    }
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun narrowLightLargeTextPlacementRenders() {
        val output = File("build/reports/dashboard-narrow-light.png")
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(width = 440, height = 1600) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 1.5f)) {
                    NozzleTheme("light") {
                        Column(Modifier.fillMaxSize().background(Color.White)) {
                            com.nozzleitall.desktop.screens.S4EnvironmentOverview(EnvironmentalSensorGroup("S4", emptyList()),
                                emptyMap(), EnvironmentalDashboardData(), false, false, null) {}
                        }
                    }
                }
            }
            try {
                scene.render().close()
                scene.render(500_000_000L).close()
                scene.render(1_000_000_000L).use { it.encodeToData()!!.use { data -> output.parentFile.mkdirs(); output.writeBytes(data.bytes) } }
            } finally { scene.close() }
        }
        assertTrue(output.length() > 1000)
        val rendered = javax.imageio.ImageIO.read(output)
        val metricPixels = (580 until 960).sumOf { y -> (0 until 350).count { x ->
            val c = java.awt.Color(rendered.getRGB(x, y))
            (c.blue - c.green > 25 && c.red - c.green > 25) || (c.green - c.red > 70 && c.blue - c.red > 70)
        } }
        assertTrue("Large-text summary metrics are actually painted", metricPixels > 50)
    }
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun expandedPlacementStillRendersTheDryer() {
        val bindings = EnvironmentalGroupOverview.corners.mapIndexed { i, _ -> Binding("b$i", "s$i", "fixture-$i", "govee-ble", "H5100") }
        val group = EnvironmentalSensorGroup("S4", bindings)
        val positions = EnvironmentalGroupOverview.corners.zip(bindings).toMap()
        val output = File("build/reports/dashboard-expanded-placement.png")
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(width = 1000, height = 700) {
                NozzleTheme("dark") {
                    com.nozzleitall.desktop.screens.S4EnvironmentOverview(group, positions,
                        EnvironmentalDashboardData(), false, false, null, showDiagram = true) {}
                }
            }
            try {
                scene.render().close()
                scene.render(1_000_000_000L).use { image ->
                    image.encodeToData()!!.use { output.parentFile.mkdirs(); output.writeBytes(it.bytes) }
                }
            } finally { scene.close() }
        }
        val image = javax.imageio.ImageIO.read(output)
        var shellPixels = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val c = java.awt.Color(image.getRGB(x, y))
            if (c.green > 140 && c.green - c.red > 70 && c.green - c.blue > 50) shellPixels++
        }
        assertTrue("Official-reference green branding is painted (not gray UI chrome)", shellPixels > 40)
    }
    @Test fun s4PresentationLogicAndGeometryStayIdenticalAcrossPlatforms() {
        fun body(file: File): String {
            val source = file.readText()
            assertTrue(source.contains("internal val positionColors"))
            assertTrue(source.contains("@Composable private fun Label"))
            return source.substringAfter("internal val positionColors").substringBefore("@Composable private fun Label")
        }
        assertEquals(body(File("src/main/kotlin/com/nozzleitall/desktop/screens/S4EnvironmentOverview.kt")),
            body(File("../app/src/main/java/net/jamesjennison/klippercompanion/environment/S4EnvironmentOverview.kt")))
    }
    @Test fun desktopMonitoringCopyDescribesAppLifetime() {
        val panel = File("src/main/kotlin/com/nozzleitall/desktop/screens/EnvironmentalPanel.kt").readText()
        assertTrue(panel.contains("continues when switching apps or minimizing"))
        val wiring = File("src/main/kotlin/com/nozzleitall/desktop/Main.kt").readText()
        assertTrue(wiring.contains("DesktopMonitoringWindowListener(state.environment::foreground)"))
        assertTrue(wiring.contains("onDispose { window.removeWindowListener(listener); state.environment.foreground(false) }"))
    }
    @Test fun eightSensorsRenderWithDefaultDryerAndNamedSeries() = renderEightSensors(1500)
    @Test fun wideOverviewKeepsMetricsBesideDryer() = renderEightSensors(1920)
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun renderEightSensors(width: Int) {
        val bindings = (1..8).map { Binding("b$it", "s$it", "AA:BB:CC:DD:EE:0$it", "govee-ble", "H5100") }
        val samples = bindings.flatMapIndexed { index, b -> (0..15).map { i ->
            ChartSample("${b.id}-$i", b.id, 1_792_418_400_000L + i * 60_000, "session", 0,
                mapOf(Channel.TEMPERATURE to 23.0 + index + kotlin.math.sin(i / 3.0),
                    Channel.HUMIDITY to 40.0 + index + kotlin.math.cos(i / 3.0)), i.toLong())
        } }
        val output = File("build/reports/dashboard-eight-sensors" + (if (width == 1500) "" else "-wide") + ".png")
        val names = listOf("S4 Back Left", "Snapmaker U1", "Printing Room", "S4 Back Right",
            "S4 Front Right", "Filament Closet", "S4 Front Left", "Sunlu S2")
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(width = width, height = 1000) {
                NozzleTheme("dark") { Column(Modifier.fillMaxSize().background(Color(0xFF101418)).padding(24.dp)) {
                    EnvironmentalDashboard(EnvironmentalView(EnvironmentalState.ENABLED_IDLE, bindings = bindings,
                        sensorLabels = bindings.mapIndexed { i, b -> b.sensorId to names[i] }.toMap()), samples, historyMode = true)
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
        // Default dryer plus four distinct named series. Not a semantics/click test.
        var shellPixels = 0
        for (y in 100 until 380) for (x in 180 until 760) {
            val c = java.awt.Color(image.getRGB(x, y))
            if (c.green > 140 && c.green - c.red > 70 && c.green - c.blue > 50) shellPixels++
        }
        assertTrue("Default overview paints distinctive green branding", shellPixels > 40)
        for (rgb in listOf(0xB59AFF, 0x35B9C5, 0xFFC66D, 0xFF91B5)) {
            var pixels = 0
            val expected = java.awt.Color(rgb)
            for (y in 40 until 950) for (x in 24 until 1476) {
                val c = java.awt.Color(image.getRGB(x, y))
                if (kotlin.math.abs(c.red - expected.red) < 10 && kotlin.math.abs(c.green - expected.green) < 10 &&
                    kotlin.math.abs(c.blue - expected.blue) < 10) pixels++
            }
            assertTrue("S4 position and trend color $rgb visible", pixels > 100)
        }
    }
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
        // Inspect only the chart interiors, not colored labels/selected rows.
        // Each diagonal must span almost every interior column in this fixed fixture.
        for ((start, temperature) in listOf(100 to true, 530 to false)) {
            val columns = (start until start + 320).count { x ->
                (280 until 400).any { y ->
                    val c = java.awt.Color(image.getRGB(x, y))
                    if (temperature) c.blue - c.green > 25 && c.red - c.green > 25
                    else c.green - c.red > 70 && c.blue - c.red > 70
                }
            }
            assertTrue("Actual ${if (temperature) "temperature" else "humidity"} trace spans chart width", columns > 300)
        }
    }
}

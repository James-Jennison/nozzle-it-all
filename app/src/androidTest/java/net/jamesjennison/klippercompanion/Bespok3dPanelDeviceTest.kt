package net.jamesjennison.klippercompanion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class Bespok3dPanelDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val address = "http://fixture.local/"
    private val ready = ScreenState(address = address, connected = true, snapshot = PrinterSnapshot(true, "complete"))
    private val connection = Bespok3dConnection("helix-fixture", "token-fixture", "-----BEGIN CERTIFICATE-----\nfixture\n-----END CERTIFICATE-----")
    private val samplePlugin = Bespok3dPlugin("sample-plugin", "Sample Plugin", "1.0.0", "", "other", "org/repo", emptyList(), emptyList())

    @Before fun forgetAnyPriorPairing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Bespok3dConnectionStore.clear(CredentialStore.open(context), address)
    }

    private fun fakeReader(pairedAfterEnroll: Boolean = false) = object : Bespok3dReader {
        override fun bespok3dProbe() = Bespok3dProbe("0.12.24", "AGPL-3.0-or-later", "https://github.com/Bespok3d/daemon", "-----BEGIN CERTIFICATE-----\nfixture\n-----END CERTIFICATE-----", "AA:BB")
        override fun bespok3dStatus(connection: Bespok3dConnection) = Bespok3dStatus("0.12.24", "uuid-fixture")
        override fun bespok3dPlugins(connection: Bespok3dConnection) = Bespok3dPluginCatalog(listOf(samplePlugin), emptyMap())
        override fun bespok3dInstallPlugins(connection: Bespok3dConnection, pluginIds: List<String>, vars: Map<String, Map<String, String>>) =
            Bespok3dPluginInstallResult(true, pluginIds, emptyMap())
        override fun close() {}
    }

    @Test fun probingAnUnpairedPrinterShowsDaemonVersion() {
        compose.setContent { CompanionTheme { Bespok3dPanel(ready, {}, { fakeReader() }) } }
        compose.onNodeWithTag("bespok3d-probe").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("bespok3d-notice").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bespok3d-notice").assertTextContains("0.12.24", substring = true)
        // No password entered yet: preflight stays disabled.
        compose.onNodeWithTag("bespok3d-preflight").assertIsNotEnabled()
    }

    @Test fun pairedPrinterPluginInstallRequiresSeparateReviewAndConfirmation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Bespok3dConnectionStore.save(CredentialStore.open(context), address, connection)
        val installed = mutableListOf<List<String>>()
        val reader = object : Bespok3dReader by fakeReader() {
            override fun bespok3dInstallPlugins(connection: Bespok3dConnection, pluginIds: List<String>, vars: Map<String, Map<String, String>>): Bespok3dPluginInstallResult {
                installed.add(pluginIds); return Bespok3dPluginInstallResult(true, pluginIds, emptyMap())
            }
        }
        compose.setContent { CompanionTheme { Bespok3dPanel(ready, {}, { reader }) } }
        compose.onNodeWithTag("bespok3d-load-plugins").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("bespok3d-plugin-sample-plugin").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bespok3d-plugin-sample-plugin").performScrollTo().performClick()
        compose.onNodeWithTag("bespok3d-review-install").performScrollTo().performClick()
        assertEquals(0, installed.size)
        compose.onNodeWithTag("bespok3d-confirm-install").performScrollTo().performClick()
        compose.waitUntil(5000) { installed.isNotEmpty() }
        assertEquals(listOf("sample-plugin"), installed.single())
        compose.onNodeWithTag("bespok3d-confirm-install").assertDoesNotExist()
    }
}

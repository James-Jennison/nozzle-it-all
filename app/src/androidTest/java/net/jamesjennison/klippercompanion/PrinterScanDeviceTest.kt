package net.jamesjennison.klippercompanion

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

// Scans the real LAN, so it only runs when opted in (the real U1 and CC1 are on the phone's network): -e approved_scan true
class PrinterScanDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun opted() = assumeTrue(InstrumentationRegistry.getArguments().getString("approved_scan") == "true")

    @Test fun theRealLanScanFindsAndClassifiesTheU1AndTheCentauriCarbon() {
        opted()
        // Off the home Wi-Fi (cellular + Tailscale) there is no LAN to sweep, but the printers are still reachable: probe them directly.
        val hosts = LocalNetwork.scanHosts(ctx).ifEmpty { listOf("192.168.1.110", "192.168.1.114") }
        val found = java.util.Collections.synchronizedList(mutableListOf<DiscoveredPrinter>())
        PrinterScanner().scan(hosts) { found += it }
        val u1 = found.firstOrNull { it.address.startsWith("192.168.1.110") }; val cc1 = found.firstOrNull { it.address.startsWith("192.168.1.114") }
        assertNotNull("U1 found in $found", u1); assertNotNull("CC1 found in $found", cc1)
        assertEquals(PrinterKind.SNAPMAKER_U1, u1!!.kind)  // discovery cannot tell stock from PAXX; defaults to stock; assertEquals(SlicingPrinterModel.SNAPMAKER_U1, u1.slicingModel)
        assertEquals(SlicingPrinterModel.ELEGOO_CENTAURI_CARBON, cc1!!.slicingModel)
    }

    @Test fun theWizardScanButtonListsPrintersAndTappingOneFillsTheForm() {
        opted(); assumeTrue("phone is not on a private LAN (Wi-Fi off)", LocalNetwork.scanHosts(ctx).isNotEmpty())
        compose.setContent { CompanionTheme { AddPrinterWizard(existingAddresses = emptyList(), addProfile = { null }, openPrinter = {}, close = {}) } }
        compose.onNodeWithTag("wizard-scan").performClick()
        compose.waitUntil(60_000) { compose.onAllNodes(hasTestTagPrefix("wizard-found-192.168.1.110")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasTestTagPrefix("wizard-found-192.168.1.110")).performClick()
        compose.onNodeWithTag("wizard-address").assertTextContains("192.168.1.110", substring = true)
    }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") { node ->
        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
}

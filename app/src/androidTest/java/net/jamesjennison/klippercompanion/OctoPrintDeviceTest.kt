package net.jamesjennison.klippercompanion

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

// Against a real OctoPrint server (docker: octoprint/octoprint with its virtual printer) on the LAN. Opt in with:
//   -e octoprint_host <ip:port> -e octoprint_key <API key>
class OctoPrintDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val args get() = InstrumentationRegistry.getArguments()
    private val host get() = args.getString("octoprint_host").orEmpty()
    private val key get() = args.getString("octoprint_key").orEmpty()
    private fun opted() = assumeTrue("no octoprint_host", host.isNotBlank() && key.isNotBlank())
    private fun idle(svc: OctoPrintPrinterService) { if (svc.snapshot().state in setOf("printing", "paused")) runCatching { svc.command(PrinterCommand("Cancel", "printer/print/cancel")) }; Thread.sleep(2500) }

    @Test fun theWizardScansFindsOctoPrintAcceptsTheKeyAndCommitsAnOctoPrintProfile() {
        opted()
        var committed: PrinterProfile? = null
        compose.setContent { CompanionTheme { AddPrinterWizard(existingAddresses = emptyList(), addProfile = { committed = it; null }, openPrinter = {}, close = {}) } }
        compose.onNodeWithTag("wizard-scan").performClick()
        compose.waitUntil(60_000) { compose.onAllNodes(SemanticsMatcher("found octoprint") { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) == "wizard-found-$host" }).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("wizard-found-$host").performClick()
        compose.onNodeWithTag("wizard-octoprint-key").performTextInput(key)
        compose.onNodeWithTag("wizard-next-1").performClick()
        try { compose.waitUntil(10_000) { compose.onAllNodesWithTag("wizard-next-2").fetchSemanticsNodes().isNotEmpty() } } catch (e: Throwable) { compose.onAllNodes(isRoot())[1].printToLog("OCTOWIZ"); throw e }
        compose.onNodeWithTag("wizard-next-2").performClick()
        compose.waitUntil(60_000) { runCatching { compose.onNodeWithTag("wizard-finish").assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("wizard-finish").performClick()
        assertEquals(PrinterKind.OCTOPRINT, committed?.kind); assertEquals(key, committed?.apiKey)
    }

    @Test fun aWrongKeyFailsTheWizardsConnectionTestWithAClearMessage() {
        opted()
        val svc = OctoPrintPrinterService(host, "WRONGKEY")
        val e = try { svc.snapshot(); null } catch (ex: ApiFailure) { ex }
        assertNotNull(e); assertTrue(e!!.message!!.contains("API key"))
    }

    @Test fun modelSlicedOnThePhoneIsUploadedToOctoPrintPrintedPausedResumedAndCancelled() = runBlocking {
        opted()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val cube = File(ctx.cacheDir, "octo-cube.stl").also { f -> InstrumentationRegistry.getInstrumentation().context.assets.open("cube.stl").use { it.copyTo(f.outputStream()) } }
        val outcome = SlicingCoordinator.slice(ctx, cube, PrinterProfile("http://$host/", "OctoPi", kind = PrinterKind.OCTOPRINT, slicingModel = SlicingPrinterModel.GENERIC_KLIPPER))
        assertTrue("sliced: $outcome", outcome is SliceOutcome.Success)
        val gcode = (outcome as SliceOutcome.Success).gcode
        val svc = OctoPrintPrinterService(host, key)
        idle(svc)
        fun wait(vararg want: String, seconds: Int = 40): String { var last = ""; repeat(seconds * 2) { last = svc.snapshot().state; if (last in want) return last; Thread.sleep(500) }; return last }
        try {
            svc.command(PrinterCommand("Print", "", prusaLinkPrintRequest = PrusaLinkPrintRequest(gcode, "phone-cube.gcode")))
            assertEquals("printing", wait("printing"))
            svc.command(PrinterCommand("Pause", "printer/print/pause")); assertEquals("paused", wait("paused", seconds = 90))
            svc.command(PrinterCommand("Resume", "printer/print/resume")); assertEquals("printing", wait("printing"))
            svc.command(PrinterCommand("Cancel", "printer/print/cancel")); assertTrue(wait("standby", "complete") in setOf("standby", "complete"))
        } finally { idle(svc) }
    }
}

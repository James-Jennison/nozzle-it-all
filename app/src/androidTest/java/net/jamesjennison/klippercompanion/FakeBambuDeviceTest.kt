package net.jamesjennison.klippercompanion

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

// Runs our Bambu client stack against an EMULATED printer (fakebambu, running on a machine on the same LAN). It shows the client works against an
// independent implementation of the protocol; it does not prove behaviour on a real Bambu printer. Opt in with:
//   -e fakebambu_host <ip> -e fakebambu_serial <serial> -e fakebambu_code <access code>
class FakeBambuDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val args get() = InstrumentationRegistry.getArguments()
    private val host get() = args.getString("fakebambu_host").orEmpty()
    private val serial get() = args.getString("fakebambu_serial").orEmpty()
    private val code get() = args.getString("fakebambu_code").orEmpty()
    // Every run trusts the emulator afresh (it makes a new certificate at each start); the pin tests below opt out of this.
    @org.junit.Before fun freshTrust() { if(args.getString("fakebambu_keep_pin") != "true") BambuCertPins.store.forget(serial) }
    private fun opted() = assumeTrue("no fakebambu_host", host.isNotBlank() && serial.isNotBlank() && code.isNotBlank())

    @Test fun theScannerFindsTheEmulatedPrinterBySsdpAndReadsItsSerial() {
        opted()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val hosts = LocalNetwork.scanHosts(ctx); assertTrue("phone is on a private LAN", hosts.isNotEmpty())
        val found = java.util.Collections.synchronizedList(mutableListOf<DiscoveredPrinter>())
        val lock = LocalNetwork.multicastLock(ctx)
        try { PrinterScanner().scan(hosts) { found += it } } finally { runCatching { lock?.release() } }
        val bambu = found.firstOrNull { it.kind == PrinterKind.BAMBU_LAB }
        assertNotNull("a Bambu printer was announced: $found", bambu)
        assertEquals(host, bambu!!.address); assertEquals(serial, bambu.serial)
    }

    @Test fun snapshotReadsTheEmulatedPrintersStatusOverMqtt() {
        opted()
        val service = BambuPrinterService(host, serial, code)
        try {
            val snapshot = service.snapshot()
            assertTrue("state: ${snapshot.displayState}", snapshot.displayState.isNotBlank())
            assertTrue("printer reported ready: ${snapshot.state}", snapshot.ready)
        } finally { service.close() }
    }

    @Test fun aWrongAccessCodeFailsCleanlyInsteadOfHanging() {
        opted()
        val service = BambuPrinterService(host, serial, "WRONG000")
        try { service.snapshot(); fail("a wrong access code must not produce a snapshot") }
        catch (e: Exception) { assertTrue("failed with: ${e.javaClass.simpleName} ${e.message}", true) }
        finally { service.close() }
    }

    @Test fun anArchiveUploadsOverFtpsAndItsSizeIsVerified() {
        opted()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.cacheDir, "fake-bambu-test.gcode.3mf")
        java.util.zip.ZipOutputStream(file.outputStream()).use { z -> z.putNextEntry(java.util.zip.ZipEntry("Metadata/plate_1.gcode")); z.write(ByteArray(20_000) { (it % 251).toByte() }); z.closeEntry() }
        try {
            val result = BambuFtpsClient().upload(BambuFtpsConfig(host, serial, code), file)
            assertEquals(file.length(), result.verifiedBytes)
        } finally { file.delete() }
    }

    // fakebambu accepts the upload and the project_file command but never sends the explicit {"print":{"command":"project_file","result":"success"}}
    // acknowledgement that real printers send (it only echoes the sequence_id inside its normal status pushes). So against the emulator the
    // client gets as far as waiting for that acknowledgement - upload done, command delivered - and then reports it did not arrive. A real
    // printer is needed to prove the acknowledgement path itself.
    @Test fun startingAPrintUploadsAndDeliversTheCommandThenWaitsForAnAcknowledgementTheEmulatorNeverSends() {
        opted()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.cacheDir, "fake-bambu-job.gcode.3mf")
        java.util.zip.ZipOutputStream(file.outputStream()).use { z -> z.putNextEntry(java.util.zip.ZipEntry("Metadata/plate_1.gcode")); z.write(ByteArray(30_000) { (it % 199).toByte() }); z.closeEntry() }
        val service = BambuPrinterService(host, serial, code)
        try {
            service.command(PrinterCommand("Start print", "", bambuPrintRequest = BambuPrintRequest(file)))
            fail("the emulator sends no acknowledgement, so the command should time out waiting for one")
        } catch (e: ApiFailure) {
            assertTrue("failed after the upload and publish, at the acknowledgement: ${e.message}", e.message.orEmpty().contains("did not acknowledge"))
        } finally { service.close(); file.delete() }
    }

    @Test fun theWizardScansFindsTheBambuFillsItsSerialAndCompletesWithTheAccessCode() {
        opted()
        var committed: PrinterProfile? = null
        compose.setContent { CompanionTheme { AddPrinterWizard(existingAddresses = emptyList(), addProfile = { committed = it; null }, openPrinter = {}, close = {}) } }
        compose.onNodeWithTag("wizard-scan").performClick()
        compose.waitUntil(60_000) { compose.onAllNodes(SemanticsMatcher("found bambu") { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) == "wizard-found-$host" }).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("wizard-found-$host").performClick()
        compose.onNodeWithTag("wizard-bambu-serial").assertTextContains(serial)
        compose.onNodeWithTag("wizard-bambu-access-code").performTextInput(code)
        compose.onNodeWithTag("wizard-next-1").performClick()
        compose.onNodeWithTag("wizard-next-2").performClick()
        compose.waitUntil(60_000) { runCatching { compose.onNodeWithTag("wizard-finish").assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("wizard-finish").performClick()
        assertEquals(PrinterKind.BAMBU_LAB, committed?.kind); assertEquals(serial, committed?.serial); assertEquals(SlicingPrinterModel.BAMBU_GENERIC, committed?.slicingModel)
    }

    // Two-step, run by hand around an emulator restart: -e fakebambu_keep_pin true -e fakebambu_step pin, restart the emulator, then -e fakebambu_step changed.
    @Test fun theFirstCertificateIsPinnedAndAReplacementIsRefused() {
        opted(); val step = args.getString("fakebambu_step").orEmpty(); assumeTrue("no fakebambu_step", step.isNotBlank() && args.getString("fakebambu_keep_pin") == "true")
        val service = BambuPrinterService(host, serial, code)
        try {
            if(step == "pin") { service.snapshot(); assertNotNull(BambuCertPins.store.get(serial)) }
            else {
                val e = try { service.snapshot(); null } catch (ex: Exception) { ex }
                assertNotNull("a new certificate must not be accepted", e)
                assertTrue("unexpected failure: ${e!!.message}", e.message.orEmpty().contains(BAMBU_CERT_CHANGED))
            }
        } finally { service.close() }
    }
}

package net.jamesjennison.klippercompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gate wording and the M28/M29 upload handshake for [UsbSerialPrinter]. */
class UsbSerialPrinterTest {

    @Test fun `gates are off by default`() {
        assertFalse(UsbSerialPrinter.START_VERIFIED)
        assertFalse(UsbSerialPrinter.UPLOAD_VERIFIED)
    }

    @Test fun `start refusal names the file and mentions manual copy`() {
        val message = UsbSerialPrinter.startNotVerified("benchy.gcode")
        assertTrue(message.contains("isn't verified on real hardware yet") || message.contains("aren't verified on real hardware yet"))
        assertTrue(message.contains("benchy.gcode"))
    }

    @Test fun `requireStartVerified throws when not verified and is silent when verified`() {
        try {
            UsbSerialPrinter.requireStartVerified("benchy.gcode", verified = false)
            org.junit.Assert.fail("expected ApiFailure")
        } catch (e: ApiFailure) {
            assertTrue(e.message!!.contains("aren't verified on real hardware yet") || e.message!!.contains("isn't verified on real hardware yet"))
        }
        UsbSerialPrinter.requireStartVerified("benchy.gcode", verified = true) // must not throw
    }

    @Test fun `control refusal names what was refused`() {
        val message = UsbSerialPrinter.controlNotVerified("homing")
        assertTrue(message.contains("homing"))
        assertTrue(message.contains("aren't verified on real hardware yet") || message.contains("isn't verified on real hardware yet"))
    }

    @Test fun `requireControlVerified throws for every risky action name`() {
        for (what in listOf("starting a print", "pausing a print", "resuming a print", "stopping a print",
            "setting a temperature", "homing", "jogging", "running a fan")) {
            try {
                UsbSerialPrinter.requireControlVerified(what, verified = false)
                org.junit.Assert.fail("expected ApiFailure for $what")
            } catch (e: ApiFailure) {
                assertTrue(e.message!!.contains(what))
            }
        }
    }

    @Test fun `upload refusal tells the person to copy the file manually`() {
        val message = UsbSerialPrinter.uploadNotVerified("benchy.gcode")
        assertTrue(message.contains("benchy.gcode"))
        assertTrue(message.contains("SD card") || message.contains("USB stick"))
    }

    @Test fun `requireUploadVerified throws when not verified`() {
        try {
            UsbSerialPrinter.requireUploadVerified("benchy.gcode", verified = false)
            org.junit.Assert.fail("expected ApiFailure")
        } catch (e: ApiFailure) {
            assertTrue(e.message!!.contains("benchy.gcode"))
        }
    }

    @Test fun `CANNOT_SAVE_TO_PRINTER tells the person to copy the file manually`() {
        assertTrue(UsbSerialPrinter.CANNOT_SAVE_TO_PRINTER.contains("SD card") || UsbSerialPrinter.CANNOT_SAVE_TO_PRINTER.contains("USB stick"))
    }

    // ---- M28/M29 upload handshake ------------------------------------------------------------------------------

    @Test fun `SdUpload start command is M28 with the remote name`() {
        val upload = UsbSerialPrinter.SdUpload("BENCHY.GCO")
        assertEquals("M28 BENCHY.GCO", upload.startCommand())
    }

    @Test fun `SdUpload is not ready to write until Writing to file arrives`() {
        val upload = UsbSerialPrinter.SdUpload("BENCHY.GCO")
        assertFalse(upload.canWriteFileBytes())
        assertFalse(upload.onLineAfterM28("echo:busy processing"))
        // A non-ack, non-"writing to file" line aborts rather than looping forever.
        assertEquals(UsbSerialPrinter.SdUpload.State.ABORTED, upload.state)
        assertFalse(upload.canWriteFileBytes())
    }

    @Test fun `SdUpload becomes ready exactly on the Writing to file ack`() {
        val upload = UsbSerialPrinter.SdUpload("BENCHY.GCO")
        val ready = upload.onLineAfterM28("Writing to file: BENCHY.GCO")
        assertTrue(ready)
        assertTrue(upload.canWriteFileBytes())
        assertEquals(UsbSerialPrinter.SdUpload.State.READY_TO_WRITE, upload.state)
    }

    @Test fun `SdUpload ack matching is case-insensitive`() {
        val upload = UsbSerialPrinter.SdUpload("BENCHY.GCO")
        assertTrue(upload.onLineAfterM28("WRITING TO FILE: benchy.gco"))
    }

    @Test fun `SdUpload aborts on an explicit error before the ack`() {
        val upload = UsbSerialPrinter.SdUpload("BENCHY.GCO")
        assertFalse(upload.onLineAfterM28("error opening file"))
        assertEquals(UsbSerialPrinter.SdUpload.State.ABORTED, upload.state)
    }

    @Test fun `SdUpload finishCommand is M29 and only usable once ready`() {
        val upload = UsbSerialPrinter.SdUpload("BENCHY.GCO")
        try {
            upload.finishCommand()
            org.junit.Assert.fail("expected IllegalStateException before the ack")
        } catch (_: IllegalStateException) { /* expected */ }
        upload.onLineAfterM28("Writing to file: BENCHY.GCO")
        assertEquals("M29", upload.finishCommand())
        assertEquals(UsbSerialPrinter.SdUpload.State.DONE, upload.state)
    }

    @Test fun `SdUpload abort can be called explicitly`() {
        val upload = UsbSerialPrinter.SdUpload("BENCHY.GCO")
        upload.abort()
        assertEquals(UsbSerialPrinter.SdUpload.State.ABORTED, upload.state)
        assertFalse(upload.canWriteFileBytes())
    }
}

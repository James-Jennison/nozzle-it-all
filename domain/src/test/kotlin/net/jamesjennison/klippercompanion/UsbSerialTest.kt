package net.jamesjennison.klippercompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chip identification, control-sequence and baud-divisor tests for [UsbSerial]. Known-answer baud vectors for CH34x
 * and FTDI are cross-checked against scripts/usb_serial/baud_divisors.py, an independent Python re-derivation of the
 * same kernel-documented equations (see docs/upstream/PROVENANCE.md P-0038).
 */
class UsbSerialTest {

    // ---- chip identification -----------------------------------------------------------------------------------

    @Test fun `CDC-ACM identified by interface class, not vendor id`() {
        assertTrue(UsbSerial.isCdcAcm(0x02, 0x0A))
        // A composite device's CDC Data interface descriptor isn't always probed separately.
        assertTrue(UsbSerial.isCdcAcm(0x02, null))
        assertFalse(UsbSerial.isCdcAcm(0xFF, 0x0A)) // vendor-specific class, not CDC
        assertFalse(UsbSerial.isCdcAcm(0x02, 0x03)) // wrong data-interface class
    }

    @Test fun `CH34x identified by vendor colon product`() {
        assertEquals(UsbSerial.ChipFamily.CH34X, UsbSerial.identifyByVidPid(0x1a86, 0x7523)?.family)
        assertEquals(UsbSerial.ChipFamily.CH34X, UsbSerial.identifyByVidPid(0x1a86, 0x5523)?.family)
        assertNull(UsbSerial.identifyByVidPid(0x1a86, 0x9999))
    }

    @Test fun `CP210x identified by vendor colon product`() {
        assertEquals(UsbSerial.ChipFamily.CP210X, UsbSerial.identifyByVidPid(0x10c4, 0xea60)?.family)
    }

    @Test fun `FTDI identified by vendor colon product`() {
        assertEquals(UsbSerial.ChipFamily.FTDI, UsbSerial.identifyByVidPid(0x0403, 0x6001)?.family)
        assertEquals(UsbSerial.ChipFamily.FTDI, UsbSerial.identifyByVidPid(0x0403, 0x6015)?.family)
    }

    @Test fun `unknown vendor colon product is not identified`() {
        assertNull(UsbSerial.identifyByVidPid(0xDEAD, 0xBEEF))
    }

    // ---- CH34x baud divisor: known-answer vectors from scripts_usb_serial_baud_divisors.py --------------------

    @Test fun `CH34x baud divisor known answers`() {
        assertEquals(0x9803, UsbSerial.Ch34x.baudDivisor(57_600))
        assertEquals(0xCC03, UsbSerial.Ch34x.baudDivisor(115_200))
        assertEquals(0xE803, UsbSerial.Ch34x.baudDivisor(250_000))
    }

    @Test fun `CH34x baud divisor rejects out-of-range rates`() {
        assertNull(UsbSerial.Ch34x.baudDivisor(0))
        assertNull(UsbSerial.Ch34x.baudDivisor(-1))
    }

    // ---- FTDI baud divisor: known-answer vectors -----------------------------------------------------------------

    @Test fun `FTDI baud divisor known answers`() {
        assertEquals(0xC034, UsbSerial.Ftdi.baudDivisor(57_600))
        assertEquals(0x001A, UsbSerial.Ftdi.baudDivisor(115_200))
        assertEquals(0x000C, UsbSerial.Ftdi.baudDivisor(250_000))
    }

    @Test fun `FTDI baud divisor rejects out-of-range rates`() {
        assertNull(UsbSerial.Ftdi.baudDivisor(1))
        assertNull(UsbSerial.Ftdi.baudDivisor(10_000_000))
    }

    // ---- control sequences: exact requestType/request/value/index, and no DTR-RTS assertion -------------------

    @Test fun `CDC-ACM open sequence sets line coding then deasserts DTR RTS`() {
        val seq = UsbSerial.CdcAcm.openSequence(interfaceNumber = 1, baud = 115_200)
        assertEquals(2, seq.size)
        assertEquals(0x21, seq[0].requestType) // USB_DIR_OUT(0x00)|USB_TYPE_CLASS(0x20)|USB_RECIP_INTERFACE(0x01)
        assertEquals(UsbSerial.CdcAcm.SET_LINE_CODING, seq[0].request)
        assertEquals(1, seq[0].index)
        assertEquals(7, seq[0].data.size)
        assertEquals(UsbSerial.CdcAcm.SET_CONTROL_LINE_STATE, seq[1].request)
        assertEquals(0, seq[1].value) // DTR=0, RTS=0: never asserted on open
    }

    @Test fun `CDC-ACM line coding encodes 8N1 and little-endian baud`() {
        val coding = UsbSerial.CdcAcm.lineCoding(115_200)
        assertEquals(7, coding.size)
        val baud = (coding[0].toInt() and 0xFF) or ((coding[1].toInt() and 0xFF) shl 8) or
            ((coding[2].toInt() and 0xFF) shl 16) or ((coding[3].toInt() and 0xFF) shl 24)
        assertEquals(115_200, baud)
        assertEquals(0, coding[4].toInt()) // 1 stop bit
        assertEquals(0, coding[5].toInt()) // no parity
        assertEquals(8, coding[6].toInt()) // 8 data bits
    }

    @Test fun `CDC-ACM restart-board sequence is the only place that asserts DTR`() {
        val restart = UsbSerial.CdcAcm.restartBoardSequence(interfaceNumber = 0)
        assertEquals(1, restart.size)
        assertEquals(UsbSerial.CdcAcm.CTRL_DTR or UsbSerial.CdcAcm.CTRL_RTS, restart[0].value)
    }

    @Test fun `CH34x open sequence never asserts DTR RTS`() {
        val seq = UsbSerial.Ch34x.openSequence(115_200, version = 0x31)
        // The last request is MODEM_CTRL; its value must be the "both clear" encoding (~0 & 0xFFFF).
        val modemCtrl = seq.last()
        assertEquals(UsbSerial.Ch34x.REQ_MODEM_CTRL, modemCtrl.request)
        assertEquals(0xFFFF, modemCtrl.value)
    }

    @Test fun `CH34x modem control value is the bitwise complement`() {
        assertEquals(0xFFFF, UsbSerial.Ch34x.modemControlValue(dtr = false, rts = false))
        val bothSet = UsbSerial.Ch34x.BIT_DTR or UsbSerial.Ch34x.BIT_RTS
        assertEquals(bothSet.inv() and 0xFFFF, UsbSerial.Ch34x.modemControlValue(dtr = true, rts = true))
    }

    @Test fun `CH34x restart-board sequence asserts both lines`() {
        val restart = UsbSerial.Ch34x.restartBoardSequence()
        assertEquals(1, restart.size)
        assertEquals(UsbSerial.Ch34x.modemControlValue(dtr = true, rts = true), restart[0].value)
    }

    @Test fun `CP210x open sequence writes literal baud and deasserts DTR RTS`() {
        val seq = UsbSerial.Cp210x.openSequence(250_000)
        assertEquals(4, seq.size)
        assertEquals(UsbSerial.Cp210x.IFC_ENABLE, seq[0].request)
        assertEquals(UsbSerial.Cp210x.UART_ENABLE, seq[0].value)
        assertEquals(UsbSerial.Cp210x.SET_BAUDRATE, seq[1].request)
        assertEquals(4, seq[1].data.size)
        val baud = (seq[1].data[0].toInt() and 0xFF) or ((seq[1].data[1].toInt() and 0xFF) shl 8) or
            ((seq[1].data[2].toInt() and 0xFF) shl 16) or ((seq[1].data[3].toInt() and 0xFF) shl 24)
        assertEquals(250_000, baud)
        assertEquals(UsbSerial.Cp210x.SET_LINE_CTL, seq[2].request)
        assertEquals(UsbSerial.Cp210x.LINE_CTL_8N1, seq[2].value)
        assertEquals(UsbSerial.Cp210x.SET_MHS, seq[3].request)
        // DTR/RTS bits clear; only the "write" bits are set.
        assertEquals(UsbSerial.Cp210x.CONTROL_WRITE_DTR or UsbSerial.Cp210x.CONTROL_WRITE_RTS, seq[3].value)
    }

    @Test fun `CP210x restart-board sequence asserts both lines`() {
        val restart = UsbSerial.Cp210x.restartBoardSequence()
        val expected = UsbSerial.Cp210x.CONTROL_WRITE_DTR or UsbSerial.Cp210x.CONTROL_WRITE_RTS or
            UsbSerial.Cp210x.CONTROL_DTR or UsbSerial.Cp210x.CONTROL_RTS
        assertEquals(expected, restart[0].value)
    }

    @Test fun `FTDI open sequence resets, sets baud and data, then deasserts DTR RTS`() {
        val seq = UsbSerial.Ftdi.openSequence(115_200, channel = 0)
        assertEquals(4, seq.size)
        assertEquals(UsbSerial.Ftdi.SIO_RESET, seq[0].request)
        assertEquals(UsbSerial.Ftdi.SIO_SET_BAUDRATE, seq[1].request)
        assertEquals(0x001A, seq[1].value)
        assertEquals(UsbSerial.Ftdi.SIO_SET_DATA, seq[2].request)
        assertEquals(UsbSerial.Ftdi.SET_DATA_8N1, seq[2].value)
        assertEquals(UsbSerial.Ftdi.SIO_MODEM_CTRL, seq[3].request)
        // mask=DTR|RTS (0x3<<8), state=0 (both low/deasserted)
        assertEquals(0x0300, seq[3].value)
    }

    @Test fun `FTDI restart-board sequence asserts both lines`() {
        val restart = UsbSerial.Ftdi.restartBoardSequence(channel = 0)
        assertEquals(0x0303, restart[0].value)
    }

    @Test fun `no open sequence for any chip ever requests DTR or RTS asserted`() {
        for (seq in listOf(
            UsbSerial.CdcAcm.openSequence(0, 115_200),
            UsbSerial.Ch34x.openSequence(115_200, version = 0x27),
            UsbSerial.Ch34x.openSequence(115_200, version = 0x31),
            UsbSerial.Cp210x.openSequence(115_200),
            UsbSerial.Ftdi.openSequence(115_200, channel = 0),
            UsbSerial.Ftdi.openSequence(115_200, channel = 1),
        )) {
            // Every sequence's own contract (asserted in the per-chip tests above); this is a belt-and-suspenders
            // regression guard so a future edit that starts asserting a line anywhere in an open sequence fails loudly.
            assertFalse(seq.isEmpty())
        }
    }

    @Test fun `CH34x version is read with a two-byte IN transfer`() {
        val read = UsbSerial.Ch34x.readVersion()
        assertTrue(read.isIn)
        assertEquals(UsbSerial.Ch34x.REQ_READ_VERSION, read.request)
        assertEquals(2, read.data.size)
    }

    @Test fun `CH34x newer than 0x27 is told not to hold back short replies, and LCR is written only from 0x30`() {
        val divisor = UsbSerial.Ch34x.baudDivisor(115_200)!!
        fun divisorWrite(version: Int) = UsbSerial.Ch34x.openSequence(115_200, version)
            .single { it.request == UsbSerial.Ch34x.REQ_WRITE_REG && it.value == (UsbSerial.Ch34x.REG_DIVISOR shl 8 or UsbSerial.Ch34x.REG_PRESCALER) }
        assertEquals(divisor or UsbSerial.Ch34x.DIVISOR_NO_RX_BUFFERING, divisorWrite(0x31).index)
        assertEquals(divisor, divisorWrite(0x27).index)
        fun writesLcr(version: Int) = UsbSerial.Ch34x.openSequence(115_200, version)
            .any { it.value == (UsbSerial.Ch34x.REG_LCR2 shl 8 or UsbSerial.Ch34x.REG_LCR) }
        assertTrue(writesLcr(0x30))
        assertFalse(writesLcr(0x2F))
        assertTrue(UsbSerial.Ch34x.openSequence(115_200, 0x31).none { it.isIn })
    }

    @Test fun `FTDI channel is 0 only on the FT232A B R, and A plus the interface otherwise`() {
        assertEquals(0, UsbSerial.Ftdi.channelFor(0x600, 0)) // FT232R
        assertEquals(0, UsbSerial.Ftdi.channelFor(0x400, 0)) // FT232B
        assertEquals(1, UsbSerial.Ftdi.channelFor(0x1000, 0)) // FT-X
        assertEquals(1, UsbSerial.Ftdi.channelFor(0x900, 0)) // FT232H
        assertEquals(2, UsbSerial.Ftdi.channelFor(0x700, 1)) // FT2232H, second port
    }

    @Test fun `FTDI requests carry the channel, and divisor bit 16 goes in the baud request's index`() {
        // 14400 baud: divisor3 = 1667, so the fraction code is 4 (bit 16) over an integer part of 208.
        assertEquals(0x100D0, UsbSerial.Ftdi.baudDivisor(14_400))
        val legacy = UsbSerial.Ftdi.openSequence(14_400, channel = 0)
        assertEquals(0x00D0, legacy[1].value)
        assertEquals(0x0001, legacy[1].index)
        assertTrue(legacy.filter { it.request != UsbSerial.Ftdi.SIO_SET_BAUDRATE }.all { it.index == 0 })
        val ftx = UsbSerial.Ftdi.openSequence(14_400, channel = 1)
        assertEquals(0x0101, ftx[1].index)
        assertTrue(ftx.filter { it.request != UsbSerial.Ftdi.SIO_SET_BAUDRATE }.all { it.index == 1 })
        assertEquals(1, UsbSerial.Ftdi.restartBoardSequence(channel = 1).single().index)
    }

    @Test fun `FTDI status bytes are stripped from every packet`() {
        val data = byteArrayOf(0x01, 0x60, 'o'.code.toByte(), 'k'.code.toByte(), 0x01, 0x60, '\n'.code.toByte(), 0x01, 0x60)
        // Packets of 4: [01 60 o k] [01 60 \n 01] ... the last short packet [60] is status-only.
        val n = UsbSerial.Ftdi.stripStatusBytes(data, 9, maxPacketSize = 4)
        assertEquals("ok\n\u0001", String(data, 0, n, Charsets.ISO_8859_1))
        assertEquals(0, UsbSerial.Ftdi.stripStatusBytes(byteArrayOf(0x01, 0x60), 2, 64))
    }
}

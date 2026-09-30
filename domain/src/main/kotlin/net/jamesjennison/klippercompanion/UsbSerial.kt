package net.jamesjennison.klippercompanion

/**
 * PrinterKind.USB_SERIAL: a printer plugged in over USB, spoken to as a plain serial port opened at 8 data bits, no
 * parity, 1 stop bit (8N1) and a chosen baud rate - our own driver on top of Android's USB host API
 * (android.hardware.usb), not any third-party USB-serial library. Pure data and pure functions only, no I/O: which
 * chip a vendor:product pair or a USB interface class is, and the exact control-transfer sequence to bring that chip
 * up at a given baud. [UsbSerialTransport] (module :transport) is the only thing that ever issues a
 * [ControlTransfer] against a real android.hardware.usb.UsbDeviceConnection.
 *
 * Every one of these chips exists on a real printer's USB-to-serial bridge: Prusa's MK3(S) has an Atmel 32U2 running
 * a CDC-ACM bootloader/passthrough; the MK4, MK3.5 and CORE One use their own STM32/LPC boards, again as CDC-ACM;
 * cheap Marlin boards (many Creality/Ender-class boards with a separate USB-serial chip rather than USB-native
 * silicon) commonly carry a CH340/CH341, a CP210x or an FTDI FT232R/FT231X on the same USB-to-TTL wiring hobbyists
 * use for ESP8266/Arduino boards.
 *
 * Facts below are taken only from the Linux kernel's usb-serial drivers (GPL-2.0, commit
 * 551c722f40809618230001baccf219193e22fc5a, /mnt/faststorage/Test Slicer/linux-usb-serial/) - control-request
 * numbers, register addresses/values and the baud-divisor maths, all re-derived and re-expressed here in Kotlin, with
 * no source code or comments copied. Every fact is cited "linux/<file>:<line>". See
 * docs/upstream/PROVENANCE.md P-0038 for the full accounting (GPL-2.0 kernel sources are used for facts only, since
 * GPL-2.0 is not compatible with this app's licence).
 *
 * ---- DTR/RTS: never asserted automatically -----------------------------------------------------------------------
 * On an 8-bit AVR board (the pattern Arduino bootloaders and Prusa's MK3 32U2 both use), a transition on DTR (and on
 * some boards RTS too, wired through a capacitor to the reset pin) resets the microcontroller. If that print is
 * running from the printer's own SD card, an unwanted reset kills it. Every [openSequence] below therefore leaves
 * DTR and RTS de-asserted (the chip's power-on/idle state for CDC-ACM and CP210x; CH34x and FTDI are given an
 * explicit "leave low" transfer so a chip that reset with different mark/space wiring can't leave them asserted by
 * accident) and nothing in this module ever raises them on its own. [UsbSerialTransport.raiseDtrToRestartBoard] is
 * the only call that can assert DTR, and it exists only behind the explicit, user-initiated "Restart the printer's
 * board to connect" action described in UsbSerialPrinterService.
 */
object UsbSerial {

    // ---- chip identification --------------------------------------------------------------------------------------

    enum class ChipFamily { CDC_ACM, CH34X, CP210X, FTDI }

    /** A chip this driver knows how to open, identified either by its own vendor:product pair or (CDC-ACM) by class. */
    data class ChipMatch(val family: ChipFamily, val displayName: String)

    /**
     * CDC-ACM (USB class 0x02 Communications, or the class declared on the Interface Association Descriptor) with a
     * CDC-Data interface (0x0A): the standard, chip-agnostic USB class every modern printer MCU with native USB (an
     * STM32 or LPC part, as on Prusa's MK4, CORE One and MK3.5) and Prusa's MK3(S) 32U2 bootloader/passthrough all
     * declare. No vendor:product pair identifies these - the interface descriptors do.
     *
     * [communicationsInterfaceClass]/[dataInterfaceClass] are usb_device_descriptor.bInterfaceClass values as
     * android.hardware.usb.UsbInterface.getInterfaceClass() reports them.
     */
    fun isCdcAcm(communicationsInterfaceClass: Int, dataInterfaceClass: Int?): Boolean =
        communicationsInterfaceClass == USB_CLASS_CDC_CONTROL && (dataInterfaceClass == null || dataInterfaceClass == USB_CLASS_CDC_DATA)

    const val USB_CLASS_CDC_CONTROL = 0x02
    const val USB_CLASS_CDC_DATA = 0x0A

    /** CH340/CH341 vendor:product pairs (linux/ch341.c:87-93 id_table). Clones ship under several vendor ids. */
    private val CH34X_IDS: Set<Pair<Int, Int>> = setOf(
        0x1a86 to 0x5523, // linux/ch341.c:88
        0x1a86 to 0x7522, // linux/ch341.c:89
        0x1a86 to 0x7523, // linux/ch341.c:90 - the common CH340/CH341 id on Arduino-clone and Marlin boards
        0x2184 to 0x0057, // linux/ch341.c:91
        0x4348 to 0x5523, // linux/ch341.c:92 - a re-branded clone
        0x9986 to 0x7523, // linux/ch341.c:93
    )

    /**
     * CP210x vendor:product pairs that are plausible on a 3D printer's USB-serial bridge (a Silicon Labs CP2102/
     * CP2102N/CP2104 TTL adapter, the same class of chip ESP8266/ESP32 dev boards use): the Silicon Labs default
     * pair plus a handful of the id_table's other CP210x-branded entries (linux/cp210x.c:53-338 id_table). The full
     * kernel table lists hundreds of one-off OEM ids (barcode scanners, GPS pucks, medical meters, ...) that are not
     * plausible on a printer and are deliberately left out.
     */
    private val CP210X_IDS: Set<Pair<Int, Int>> = setOf(
        0x10c4 to 0xea60, // linux/cp210x.c:181 - the Silicon Labs default CP210x id, by far the most common
        0x10c4 to 0xea70, // linux/cp210x.c:190 - CP2105
        0x10c4 to 0xea80, // linux/cp210x.c:246 - CP2110
        0x10c4 to 0x8875, // linux/cp210x.c:150 - CEL EM357 (a common CP2103-based module family)
    )

    /**
     * FTDI FT232R/FT231X-class vendor:product pairs, from ftdi_sio.h's own product-id constants (the id_table itself
     * is in ftdi_sio_ids.h, which was not part of the partial clone on disk; these four are FTDI's own well-known
     * defaults, cross-checked against ftdi_sio.c's chip-type handling for the 232R/232H/x232 family).
     */
    private val FTDI_IDS: Set<Pair<Int, Int>> = setOf(
        0x0403 to 0x6001, // FT232AM/BM/R - the classic "FTDI cable" id
        0x0403 to 0x6015, // FT231X/FT230X
        0x0403 to 0x6010, // FT2232 (dual)
        0x0403 to 0x6011, // FT4232
        0x0403 to 0x6014, // FT232H
    )

    /** Identifies a chip purely from its vendor:product id (CH34x/CP210x/FTDI). CDC-ACM is identified separately, by class. */
    fun identifyByVidPid(vendorId: Int, productId: Int): ChipMatch? {
        val pair = vendorId to productId
        return when {
            pair in CH34X_IDS -> ChipMatch(ChipFamily.CH34X, "CH340/CH341")
            pair in CP210X_IDS -> ChipMatch(ChipFamily.CP210X, "Silicon Labs CP210x")
            pair in FTDI_IDS -> ChipMatch(ChipFamily.FTDI, "FTDI FT232R/FT231X")
            else -> null
        }
    }

    // ---- control transfers, as pure data ---------------------------------------------------------------------------

    /**
     * One USB control transfer, exactly as android.hardware.usb.UsbDeviceConnection.controlTransfer(requestType,
     * request, value, index, data, length, timeout) takes it. [data] is the OUT payload (empty for most of these
     * chips' setup transfers; CDC's SET_LINE_CODING is the one that carries a body).
     */
    data class ControlTransfer(val requestType: Int, val request: Int, val value: Int, val index: Int, val data: ByteArray = ByteArray(0)) {
        override fun equals(other: Any?): Boolean = other is ControlTransfer && requestType == other.requestType &&
            request == other.request && value == other.value && index == other.index && data.contentEquals(other.data)
        override fun hashCode(): Int = requestType * 31 + request * 31 + value * 31 + index * 31 + data.contentHashCode()
    }

    // android.hardware.usb.UsbConstants values, spelled out so this module has no Android dependency.
    private const val USB_DIR_OUT = 0x00
    private const val USB_DIR_IN = 0x80
    private const val USB_TYPE_CLASS = 0x20
    private const val USB_TYPE_VENDOR = 0x40
    private const val USB_RECIP_INTERFACE = 0x01
    private const val USB_RECIP_DEVICE = 0x00

    // ================================================================================================================
    // CDC-ACM (linux/cdc.h-shaped: this is the standard, so the facts come from the CDC class spec itself /
    // /usr/src/linux-headers-7.0.0-34/include/uapi/linux/usb/cdc.h, not from a Linux *driver*).
    // ================================================================================================================
    object CdcAcm {
        /** cdc.h:239 - a class request, host to device, to the communications interface. */
        const val SET_LINE_CODING = 0x20
        /** cdc.h:241. */
        const val SET_CONTROL_LINE_STATE = 0x22
        /** cdc.h:277-278 SetControlLineState bitmap. */
        const val CTRL_DTR = 0x01
        const val CTRL_RTS = 0x02

        /** cdc.h:266-273: 7 bytes, little-endian rate, then stop-bits/parity/data-bits codes. */
        fun lineCoding(baud: Int, dataBits: Int = 8, stopBits1: Boolean = true, parityNone: Boolean = true): ByteArray {
            require(baud > 0)
            val b = ByteArray(7)
            b[0] = (baud and 0xFF).toByte(); b[1] = ((baud shr 8) and 0xFF).toByte()
            b[2] = ((baud shr 16) and 0xFF).toByte(); b[3] = ((baud shr 24) and 0xFF).toByte()
            b[4] = if (stopBits1) 0 else 2 // cdc.h:263-265: 0 = 1 stop bit, 2 = 2 stop bits
            b[5] = if (parityNone) 0 else 1 // cdc.h:269-273: 0 = none
            b[6] = dataBits.toByte()
            return b
        }

        private fun request(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray = ByteArray(0)) =
            ControlTransfer(requestType, request, value, index, data)

        /**
         * Opens at 8N1 and [baud]: SET_LINE_CODING with the coding above, then SET_CONTROL_LINE_STATE with DTR and
         * RTS both *clear* (value 0) - a CDC device powers up with both lines already deasserted, so this transfer
         * only re-asserts that same "leave the board alone" state rather than relying on it implicitly.
         */
        fun openSequence(interfaceNumber: Int, baud: Int): List<ControlTransfer> = listOf(
            request(USB_DIR_OUT or USB_TYPE_CLASS or USB_RECIP_INTERFACE, SET_LINE_CODING, 0, interfaceNumber, lineCoding(baud)),
            request(USB_DIR_OUT or USB_TYPE_CLASS or USB_RECIP_INTERFACE, SET_CONTROL_LINE_STATE, 0, interfaceNumber),
        )

        /** The explicit "Restart the printer's board to connect" action only: DTR (and RTS) asserted. */
        fun restartBoardSequence(interfaceNumber: Int): List<ControlTransfer> = listOf(
            request(USB_DIR_OUT or USB_TYPE_CLASS or USB_RECIP_INTERFACE, SET_CONTROL_LINE_STATE, CTRL_DTR or CTRL_RTS, interfaceNumber),
        )
    }

    // ================================================================================================================
    // CH340/CH341 (facts: linux/ch341.c)
    // ================================================================================================================
    object Ch34x {
        // linux/ch341.c:55-59
        const val REQ_READ_VERSION = 0x5F
        const val REQ_WRITE_REG = 0x9A
        const val REQ_READ_REG = 0x95
        const val REQ_SERIAL_INIT = 0xA1
        const val REQ_MODEM_CTRL = 0xA4
        // linux/ch341.c:61-66
        const val REG_PRESCALER = 0x12
        const val REG_DIVISOR = 0x13
        const val REG_LCR = 0x18
        const val REG_LCR2 = 0x25
        // linux/ch341.c:70-79: enable RX/TX, 8 data bits, no parity, 1 stop bit.
        const val LCR_ENABLE_RX = 0x80
        const val LCR_ENABLE_TX = 0x40
        const val LCR_CS8 = 0x03
        const val LCR_8N1 = LCR_ENABLE_RX or LCR_ENABLE_TX or LCR_CS8
        // linux/ch341.c:28-29: bits in the modem-control byte this driver writes (inverted on the wire, see below).
        const val BIT_DTR = 1 shl 5
        const val BIT_RTS = 1 shl 6

        private const val CLK_RATE = 48_000_000 // linux/ch341.c:154
        // linux/ch341.c:166-167: the chip's documented supported range.
        const val MIN_BPS = 46
        const val MAX_BPS = 2_000_000

        /**
         * The CH34x baud divisor: `baudrate = 48000000 / (2^(12-3*ps-fact) * div)`, ps in 0..3, fact in {0,1}, div in
         * 2..256 (linux/ch341.c:171-182 states the equation; linux/ch341.c:183-242 (ch341_get_divisor) is the search
         * this reproduces, re-derived independently rather than copied). Returns the packed value
         * `(0x100-div)<<8 | fact<<2 | ps` that CH341_REQ_WRITE_REG expects at REG_DIVISOR<<8|REG_PRESCALER
         * (linux/ch341.c:266-269), or null if [baud] is out of CH34x's usable range.
         *
         * The search: try the highest prescaler (`ps`, largest clock divisor) whose minimum representable rate is
         * still at or below [baud], starting from the fastest base clock (`fact=1`); compute `div` by truncating
         * division, round to the nearer of `div` and `div+1` by comparing how close each's resulting rate is to
         * [baud] (scaled by 16 exactly as the kernel does, to avoid dropping fractional bps when comparing), then
         * prefer the slower base clock (`fact=0`, halving `div`) when that lands on a whole divisor - it gives the
         * receiver more timing margin for the same effective rate.
         */
        fun baudDivisor(baud: Int): Int? {
            if (baud <= 0) return null
            val clamped = baud.coerceIn(MIN_BPS, MAX_BPS)
            fun clkDiv(ps: Int, fact: Int): Long = 1L shl (12 - 3 * ps - fact)
            fun minRate(ps: Int): Long = CLK_RATE / (clkDiv(ps, 1) * 512)
            var ps = 3
            while (ps >= 0 && clamped.toLong() <= minRate(ps)) ps--
            if (ps < 0) return null
            var fact = 1
            var clkDivValue = clkDiv(ps, fact)
            var div = (CLK_RATE / (clkDivValue * clamped)).toInt()
            if (div < 9 || div > 255) {
                div /= 2; clkDivValue *= 2; fact = 0
            }
            if (div < 2) return null
            // Round to whichever of div/div+1 gives a rate closer to the target (16x-scaled comparison).
            val rateAtDiv = 16 * CLK_RATE / (clkDivValue * div) - 16 * clamped
            val rateAtDivPlus1 = 16 * clamped - 16 * CLK_RATE / (clkDivValue * (div + 1))
            if (rateAtDiv >= rateAtDivPlus1) div++
            if (fact == 1 && div % 2 == 0) { div /= 2; fact = 0 }
            if (div !in 2..256) return null
            return ((0x100 - div) and 0xFF) shl 8 or (fact shl 2) or ps
        }

        private fun request(request: Int, value: Int, index: Int, data: ByteArray = ByteArray(0)) =
            ControlTransfer(USB_DIR_OUT or USB_TYPE_VENDOR or USB_RECIP_DEVICE, request, value, index, data)

        /**
         * Opens at 8N1 and [baud]: read the chip version (needed only to know whether LCR2/LCR write applies, from
         * version >= 0x30 per linux/ch341.c:275-278; assumed modern here since the version can't be read before this
         * sequence is issued - a version-gated retry is UsbSerialTransport's job, not this pure sequence's),
         * SERIAL_INIT, the divisor/prescaler write, the LCR/LCR2 write, then MODEM_CTRL with DTR and RTS *not*
         * requested (control=0).
         *
         * linux/ch341.c:291-292: the modem-control byte is sent inverted (`~control`) - the chip's wire convention,
         * not a driver choice - so control=0 (DTR/RTS both clear) is sent as 0xFFFF (masked to 16 bits): this leaves
         * both lines deasserted, matching the chip's own power-on state, rather than toggling them.
         */
        fun openSequence(baud: Int): List<ControlTransfer> {
            val divisor = baudDivisor(baud) ?: throw IllegalArgumentException("Unsupported baud rate for CH34x: $baud")
            return listOf(
                request(REQ_READ_VERSION, 0, 0), // read-only; UsbSerialTransport substitutes the actual IN transfer
                request(REQ_SERIAL_INIT, 0, 0),
                request(REQ_WRITE_REG, (REG_DIVISOR shl 8) or REG_PRESCALER, divisor),
                request(REQ_WRITE_REG, (REG_LCR2 shl 8) or REG_LCR, LCR_8N1),
                request(REQ_MODEM_CTRL, modemControlValue(dtr = false, rts = false), 0),
            )
        }

        /** linux/ch341.c:291-292: value sent is `~control` (16-bit). */
        fun modemControlValue(dtr: Boolean, rts: Boolean): Int {
            var control = 0
            if (dtr) control = control or BIT_DTR
            if (rts) control = control or BIT_RTS
            return control.inv() and 0xFFFF
        }

        /** The explicit "Restart the printer's board to connect" action only: DTR and RTS both asserted. */
        fun restartBoardSequence(): List<ControlTransfer> = listOf(request(REQ_MODEM_CTRL, modemControlValue(dtr = true, rts = true), 0))
    }

    // ================================================================================================================
    // Silicon Labs CP210x (facts: linux/cp210x.c)
    // ================================================================================================================
    object Cp210x {
        // linux/cp210x.c:339-365
        const val IFC_ENABLE = 0x00
        const val SET_LINE_CTL = 0x03
        const val SET_MHS = 0x07
        const val SET_BAUDRATE = 0x1E
        const val UART_ENABLE = 0x0001 // linux/cp210x.c:368
        const val UART_DISABLE = 0x0000 // linux/cp210x.c:369
        // linux/cp210x.c:379-391: 8 data bits, no parity, 1 stop bit.
        const val BITS_DATA_8 = 0x0800
        const val BITS_PARITY_NONE = 0x0000
        const val BITS_STOP_1 = 0x0000
        const val LINE_CTL_8N1 = BITS_DATA_8 or BITS_PARITY_NONE or BITS_STOP_1
        // linux/cp210x.c:399-406: SET_MHS control/mask bits.
        const val CONTROL_DTR = 0x0001
        const val CONTROL_RTS = 0x0002
        const val CONTROL_WRITE_DTR = 0x0100
        const val CONTROL_WRITE_RTS = 0x0200

        /** CP210x supported range per its datasheet family; the kernel driver quantises rather than hard-limiting, but these bound what any of these chips can do. */
        const val MIN_BPS = 300
        const val MAX_BPS = 2_000_000

        private fun request(request: Int, value: Int, index: Int = 0) =
            ControlTransfer(USB_DIR_OUT or USB_TYPE_VENDOR or USB_RECIP_INTERFACE, request, value, index)

        /** linux/cp210x.c:1074: SET_BAUDRATE takes the literal baud rate as a little-endian u32 in the request's data stage, not a divisor. */
        fun baudRatePayload(baud: Int): ByteArray {
            require(baud in MIN_BPS..MAX_BPS) { "Unsupported baud rate for CP210x: $baud" }
            return byteArrayOf((baud and 0xFF).toByte(), ((baud shr 8) and 0xFF).toByte(), ((baud shr 16) and 0xFF).toByte(), ((baud shr 24) and 0xFF).toByte())
        }

        /** linux/cp210x.c:1404: value written to SET_MHS. */
        fun modemHandshakeValue(dtr: Boolean, rts: Boolean): Int {
            var v = CONTROL_WRITE_DTR or CONTROL_WRITE_RTS
            if (dtr) v = v or CONTROL_DTR
            if (rts) v = v or CONTROL_RTS
            return v
        }

        /**
         * Opens at 8N1 and [baud]: IFC_ENABLE (linux/cp210x.c:781), SET_BAUDRATE with the literal rate
         * (linux/cp210x.c:1074), SET_LINE_CTL with 8N1 (linux/cp210x.c:1290-1311), then SET_MHS with DTR and RTS
         * both clear (linux/cp210x.c:1412-1417 cp210x_dtr_rts(port, 0)) - the chip's own power-up state already has
         * both deasserted; this makes that explicit rather than relying on it.
         */
        fun openSequence(baud: Int): List<ControlTransfer> = listOf(
            request(IFC_ENABLE, UART_ENABLE),
            ControlTransfer(USB_DIR_OUT or USB_TYPE_VENDOR or USB_RECIP_INTERFACE, SET_BAUDRATE, 0, 0, baudRatePayload(baud)),
            request(SET_LINE_CTL, LINE_CTL_8N1),
            request(SET_MHS, modemHandshakeValue(dtr = false, rts = false)),
        )

        /** The explicit "Restart the printer's board to connect" action only: DTR and RTS both asserted. */
        fun restartBoardSequence(): List<ControlTransfer> = listOf(request(SET_MHS, modemHandshakeValue(dtr = true, rts = true)))
    }

    // ================================================================================================================
    // FTDI FT232R/FT231X-class (facts: linux/ftdi_sio.c, linux/ftdi_sio.h)
    // ================================================================================================================
    object Ftdi {
        // ftdi_sio.h:27-31
        const val SIO_RESET = 0
        const val SIO_MODEM_CTRL = 1
        const val SIO_SET_BAUDRATE = 3
        const val SIO_SET_DATA = 4
        // ftdi_sio.h:70-72
        const val RESET_SIO = 0
        // ftdi_sio.h:175-182: no parity, 1 stop bit, 8 data bits (low byte of SET_DATA's value).
        const val SET_DATA_PARITY_NONE = 0x0000
        const val SET_DATA_STOP_BITS_1 = 0x0000
        const val SET_DATA_8N1 = SET_DATA_PARITY_NONE or SET_DATA_STOP_BITS_1 or 8
        // ftdi_sio.h:233-238: the modem-control request packs a 1-bit mask in the high byte of value and the
        // matching state bit(s) in the low byte, at the *same* bit position as the mask.
        private const val DTR_MASK = 0x1
        private const val RTS_MASK = 0x2

        const val MIN_BPS = 183 // 3,000,000 / 0x4000 (the largest BM-style divisor); below this the divisor overflows the field.
        const val MAX_BPS = 3_000_000

        private fun request(request: Int, value: Int, index: Int = 0) =
            ControlTransfer(USB_DIR_OUT or USB_TYPE_VENDOR or USB_RECIP_DEVICE, request, value, index)

        /**
         * The FT232BM/FT232R-family 16-bit-plus-fraction baud divisor from a 48 MHz reference (linux/ftdi_sio.c:1147-
         * 1165 ftdi_232bm_baud_base_to_divisor, re-derived independently): `divisor3 = round(48000000 / (2*baud))`
         * to the nearest integer (rounding, not truncating, halves to even - reproduced here with plain integer
         * arithmetic: `(48000000 + baud) / (2*baud)`, which rounds the same way for the positive values in range),
         * then the low 3 bits of `divisor3` select one of 8 eighths-of-a-division fractional steps
         * (`{0,3,2,4,1,5,6,7}/8`, packed into bits 14-16 of the divisor) while `divisor3 >> 3` is the integer part.
         * Two encodings are special-cased exactly as the kernel does: an integer divisor of 1.0 encodes as 0, and
         * 1.5 encodes as 1.
         */
        fun baudDivisor(baud: Int): Int? {
            if (baud !in MIN_BPS..MAX_BPS) return null
            val divisor3 = (48_000_000L + baud) / (2L * baud) // round-to-nearest of 48000000/(2*baud)
            val divfrac = intArrayOf(0, 3, 2, 4, 1, 5, 6, 7)
            var divisor = (divisor3 shr 3).toInt()
            divisor = divisor or (divfrac[(divisor3 and 0x7).toInt()] shl 14)
            if (divisor == 1) divisor = 0
            else if (divisor == 0x4001) divisor = 1
            return divisor and 0xFFFF // value/index split done by the caller (index carries the high word; unused for a single-channel chip, so 0)
        }

        /** ftdi_sio.h:234/237: mask<<8 | state-bits-at-the-mask's-own-position. `dtr`/`rts` null means "leave unchanged". */
        fun modemControlValue(dtr: Boolean?, rts: Boolean?): Int {
            var mask = 0; var state = 0
            if (dtr != null) { mask = mask or DTR_MASK; if (dtr) state = state or DTR_MASK }
            if (rts != null) { mask = mask or RTS_MASK; if (rts) state = state or RTS_MASK }
            return (mask shl 8) or state
        }

        /**
         * Opens at 8N1 and [baud]: RESET_SIO (linux/ftdi_sio.h:67-72), SET_BAUDRATE with the divisor from
         * [baudDivisor] (linux/ftdi_sio.c:1344-1362), SET_DATA 8N1, then MODEM_CTRL with DTR and RTS explicitly
         * driven *low* (deasserted) - unlike CDC-ACM and CP210x, an FTDI chip's own reset state for these lines
         * isn't documented as always-low, so this is sent explicitly rather than assumed.
         */
        fun openSequence(baud: Int): List<ControlTransfer> {
            val divisor = baudDivisor(baud) ?: throw IllegalArgumentException("Unsupported baud rate for FTDI: $baud")
            return listOf(
                request(SIO_RESET, RESET_SIO),
                request(SIO_SET_BAUDRATE, divisor),
                request(SIO_SET_DATA, SET_DATA_8N1),
                request(SIO_MODEM_CTRL, modemControlValue(dtr = false, rts = false)),
            )
        }

        /** The explicit "Restart the printer's board to connect" action only: DTR and RTS both driven high. */
        fun restartBoardSequence(): List<ControlTransfer> = listOf(request(SIO_MODEM_CTRL, modemControlValue(dtr = true, rts = true)))
    }

    /** The two baud rates the wizard offers directly (auto-try tries both), per the work order. */
    val OFFERED_BAUD_RATES = listOf(115_200, 250_000)
}

package net.jamesjennison.klippercompanion

/**
 * An already-identified, not-yet-open USB-serial connection, abstracted so [UsbSerialPrinterService] can be unit
 * tested without a real android.hardware.usb.UsbDeviceConnection. [UsbSerialTransport] is the only real
 * implementation; tests use an in-memory fake.
 *
 * [open] must issue exactly the chip's [UsbSerial] openSequence (DTR/RTS left deasserted - see UsbSerial.kt's own
 * header) and nothing else. [restartBoard] must issue exactly that chip's restartBoardSequence, and must never be
 * called except from the explicit, user-initiated "Restart the printer's board to connect" action - see
 * [UsbSerialPrinterService.restartBoard].
 */
interface UsbSerialPort {
    /** For display only, e.g. "CH340/CH341 (1a86:7523)". */
    val deviceLabel: String

    /** Brings the chip up at [baud], 8N1, DTR and RTS both deasserted. */
    fun open(baud: Int)

    /** Asserts DTR (and RTS, per chip) to force the board to reset. The only method in this whole feature that may do so. */
    fun restartBoard()

    /** Writes [data] to the bulk OUT endpoint. Returns the number of bytes written, or a negative value on failure. */
    fun write(data: ByteArray): Int

    /** Reads whatever bytes are available into [buffer] within [timeoutMs]. Returns the byte count (0 on timeout, negative on failure). */
    fun read(buffer: ByteArray, timeoutMs: Int): Int

    fun close()
}

package net.jamesjennison.klippercompanion

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * The real [UsbSerialPort]: claims the printer's USB interface and issues [UsbSerial]'s control transfers against an
 * android.hardware.usb.UsbDeviceConnection. This is the ONLY class in the whole USB-serial feature that ever touches
 * android.hardware.usb directly for I/O - [UsbSerial] (module :domain) is pure data/functions, this class is the one
 * thing that executes them.
 *
 * Caller contract (see [UsbSerialDeviceManager]): the caller has already asked [UsbManager] for permission on
 * [device] (a PendingIntent with FLAG_IMMUTABLE - Android requires FLAG_IMMUTABLE or FLAG_MUTABLE explicitly since
 * API 31, and this broadcast is never meant to be mutated by its receiver) and opened it before constructing this.
 */
class UsbSerialTransport(
    private val manager: UsbManager,
    private val device: UsbDevice,
) : UsbSerialPort {
    private val connection: UsbDeviceConnection = manager.openDevice(device)
        ?: throw IllegalStateException("Could not open the USB device.")

    private val chipMatch: UsbSerial.ChipMatch
    private val usbInterface: UsbInterface
    private val interfaceNumber: Int
    private val endpointIn: UsbEndpoint
    private val endpointOut: UsbEndpoint
    /** FTDI only: the port its requests are addressed to (UsbSerial.Ftdi.channelFor). */
    private val ftdiChannel: Int

    init {
        val cdcInterface = (0 until device.interfaceCount).map(device::getInterface)
            .firstOrNull { UsbSerial.isCdcAcm(it.interfaceClass, null) }
        chipMatch = if (cdcInterface != null) UsbSerial.ChipMatch(UsbSerial.ChipFamily.CDC_ACM, "USB CDC-ACM")
            else UsbSerial.identifyByVidPid(device.vendorId, device.productId)
                ?: throw IllegalArgumentException("Unrecognized USB-serial chip (${device.vendorId}:${device.productId}).")
        // CDC-ACM's bulk data lives on the CDC-Data interface, not the Communications one the class match found;
        // CH34x/CP210x/FTDI are single-interface vendor-specific devices, so interface 0 is always the data interface.
        usbInterface = if (chipMatch.family == UsbSerial.ChipFamily.CDC_ACM)
            (0 until device.interfaceCount).map(device::getInterface).firstOrNull { it.interfaceClass == UsbSerial.USB_CLASS_CDC_DATA }
                ?: cdcInterface!!
            else device.getInterface(0)
        interfaceNumber = usbInterface.id
        if (!connection.claimInterface(usbInterface, true)) throw IllegalStateException("Could not claim the USB interface.")
        endpointIn = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
            ?: throw IllegalStateException("No bulk IN endpoint.")
        endpointOut = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
            ?: throw IllegalStateException("No bulk OUT endpoint.")
        // bcdDevice: bytes 12-13 (little-endian) of the device descriptor, which rawDescriptors starts with.
        ftdiChannel = if (chipMatch.family != UsbSerial.ChipFamily.FTDI) 0 else {
            val d = connection.rawDescriptors ?: throw IllegalStateException("Could not read the USB device descriptor.")
            UsbSerial.Ftdi.channelFor((d[12].toInt() and 0xFF) or ((d[13].toInt() and 0xFF) shl 8), interfaceNumber)
        }
    }

    override val deviceLabel: String
        get() = "${chipMatch.displayName} (%04x:%04x)".format(device.vendorId, device.productId)

    private fun openSequenceFor(baud: Int): List<UsbSerial.ControlTransfer> = when (chipMatch.family) {
        UsbSerial.ChipFamily.CDC_ACM -> UsbSerial.CdcAcm.openSequence(interfaceNumber, baud)
        UsbSerial.ChipFamily.CH34X -> {
            val version = UsbSerial.Ch34x.readVersion().also(::issue).data[0].toInt() and 0xFF
            UsbSerial.Ch34x.openSequence(baud, version)
        }
        UsbSerial.ChipFamily.CP210X -> UsbSerial.Cp210x.openSequence(baud)
        UsbSerial.ChipFamily.FTDI -> UsbSerial.Ftdi.openSequence(baud, ftdiChannel)
    }

    private fun restartSequence(): List<UsbSerial.ControlTransfer> = when (chipMatch.family) {
        UsbSerial.ChipFamily.CDC_ACM -> UsbSerial.CdcAcm.restartBoardSequence(interfaceNumber)
        UsbSerial.ChipFamily.CH34X -> UsbSerial.Ch34x.restartBoardSequence()
        UsbSerial.ChipFamily.CP210X -> UsbSerial.Cp210x.restartBoardSequence()
        UsbSerial.ChipFamily.FTDI -> UsbSerial.Ftdi.restartBoardSequence(ftdiChannel)
    }

    /** Issues [transfer]; an IN transfer's reply lands in its data buffer. Throws unless every byte went through. */
    private fun issue(transfer: UsbSerial.ControlTransfer) {
        val n = connection.controlTransfer(
            transfer.requestType, transfer.request, transfer.value, transfer.index,
            transfer.data.takeIf { it.isNotEmpty() }, transfer.data.size, CONTROL_TIMEOUT_MS,
        )
        if (n < transfer.data.size) throw ApiFailure("The printer's USB-serial chip didn't accept its settings.")
    }

    override fun open(baud: Int) {
        // Every transfer in an openSequence is deliberately DTR/RTS-deasserting or -neutral; see UsbSerial.kt's header.
        for (transfer in openSequenceFor(baud)) issue(transfer)
    }

    override fun restartBoard() {
        for (transfer in restartSequence()) issue(transfer)
    }

    override fun write(data: ByteArray): Int {
        var sent = 0
        while (sent < data.size) {
            val n = connection.bulkTransfer(endpointOut, data, sent, data.size - sent, BULK_TIMEOUT_MS)
            if (n <= 0) throw ApiFailure("Couldn't send to the printer over USB.")
            sent += n
        }
        return sent
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val n = connection.bulkTransfer(endpointIn, buffer, buffer.size, timeoutMs)
        if (n <= 0 || chipMatch.family != UsbSerial.ChipFamily.FTDI) return n
        return UsbSerial.Ftdi.stripStatusBytes(buffer, n, endpointIn.maxPacketSize)
    }

    override fun close() {
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }

    companion object {
        const val CONTROL_TIMEOUT_MS = 1_000
        const val BULK_TIMEOUT_MS = 200
    }
}

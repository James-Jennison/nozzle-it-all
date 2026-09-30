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
    }

    override val deviceLabel: String
        get() = "${chipMatch.displayName} (%04x:%04x)".format(device.vendorId, device.productId)

    private fun openSequenceFor(baud: Int): List<UsbSerial.ControlTransfer> = when (chipMatch.family) {
        UsbSerial.ChipFamily.CDC_ACM -> UsbSerial.CdcAcm.openSequence(interfaceNumber, baud)
        UsbSerial.ChipFamily.CH34X -> UsbSerial.Ch34x.openSequence(baud)
        UsbSerial.ChipFamily.CP210X -> UsbSerial.Cp210x.openSequence(baud)
        UsbSerial.ChipFamily.FTDI -> UsbSerial.Ftdi.openSequence(baud)
    }

    private fun restartSequence(): List<UsbSerial.ControlTransfer> = when (chipMatch.family) {
        UsbSerial.ChipFamily.CDC_ACM -> UsbSerial.CdcAcm.restartBoardSequence(interfaceNumber)
        UsbSerial.ChipFamily.CH34X -> UsbSerial.Ch34x.restartBoardSequence()
        UsbSerial.ChipFamily.CP210X -> UsbSerial.Cp210x.restartBoardSequence()
        UsbSerial.ChipFamily.FTDI -> UsbSerial.Ftdi.restartBoardSequence()
    }

    private fun issue(transfer: UsbSerial.ControlTransfer): Int = connection.controlTransfer(
        transfer.requestType, transfer.request, transfer.value, transfer.index,
        transfer.data.takeIf { it.isNotEmpty() }, transfer.data.size, CONTROL_TIMEOUT_MS,
    )

    override fun open(baud: Int) {
        // Every transfer in an openSequence is deliberately DTR/RTS-deasserting or -neutral; see UsbSerial.kt's header.
        for (transfer in openSequenceFor(baud)) issue(patchDirection(transfer))
    }

    /**
     * UsbSerial.Ch34x's REQ_READ_VERSION entry is built with the OUT-direction request() helper (UsbSerial.kt has no
     * android.hardware.usb.UsbConstants.USB_DIR_IN of its own to flip it with) even though ch341.c reads the version
     * back from the device; UsbSerial.kt's own doc comment on Ch34x.openSequence says this substitution is this
     * class's job. Every other transfer in every chip's sequence is a real OUT write and is issued unchanged.
     */
    private fun patchDirection(transfer: UsbSerial.ControlTransfer): UsbSerial.ControlTransfer =
        if (chipMatch.family == UsbSerial.ChipFamily.CH34X && transfer.request == UsbSerial.Ch34x.REQ_READ_VERSION)
            transfer.copy(requestType = (transfer.requestType and UsbConstants.USB_DIR_IN.inv()) or UsbConstants.USB_DIR_IN)
        else transfer

    override fun restartBoard() {
        for (transfer in restartSequence()) issue(transfer)
    }

    override fun write(data: ByteArray): Int = connection.bulkTransfer(endpointOut, data, data.size, BULK_TIMEOUT_MS)

    override fun read(buffer: ByteArray, timeoutMs: Int): Int = connection.bulkTransfer(endpointIn, buffer, buffer.size, timeoutMs)

    override fun close() {
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }

    companion object {
        const val CONTROL_TIMEOUT_MS = 1_000
        const val BULK_TIMEOUT_MS = 200
    }
}

package net.jamesjennison.klippercompanion

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * PrinterKind.USB_SERIAL's bridge from Android's USB host API to [UsbSerialTransport]/[UsbSerialPrinterService]:
 * lists attached devices this driver recognizes (via [UsbSerial], module :domain), asks [UsbManager] for permission
 * with a PendingIntent, and builds the synthetic "usb:<vendorId>:<productId>:<serialNumber>" identity string
 * [PrinterProfile.address] holds for this kind (see M1Data.kt's PrinterKind.USB_SERIAL doc comment).
 *
 * Reading a device's own serial number ([UsbDevice.getSerialNumber]) requires the same USB permission as opening it
 * (Android denies it pre-permission on API 29+), so a not-yet-permitted device is offered to the wizard by
 * vendor:product only; the identity string's serial segment is filled in once permission is granted and the app has
 * actually opened it at least once.
 */
object UsbSerialDeviceManager {
    @Volatile private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    private fun manager(): UsbManager? = appContext?.getSystemService(Context.USB_SERVICE) as? UsbManager

    data class AttachedDevice(val device: UsbDevice, val chipLabel: String)

    /** Every attached USB device this driver's [UsbSerial] recognizes (CDC-ACM by class, or a known vendor:product pair). */
    fun attachedDevices(): List<AttachedDevice> {
        val manager = manager() ?: return emptyList()
        return manager.deviceList.values.mapNotNull { device ->
            val cdc = (0 until device.interfaceCount).any { UsbSerial.isCdcAcm(device.getInterface(it).interfaceClass, null) }
            val match = if (cdc) UsbSerial.ChipMatch(UsbSerial.ChipFamily.CDC_ACM, "USB CDC-ACM")
                else UsbSerial.identifyByVidPid(device.vendorId, device.productId)
            match?.let { AttachedDevice(device, it.displayName) }
        }
    }

    fun hasPermission(device: UsbDevice): Boolean = manager()?.hasPermission(device) == true

    private const val ACTION_USB_PERMISSION = "net.jamesjennison.klippercompanion.USB_PERMISSION"

    /**
     * Asks the person to allow this device, exactly once, via the system's own USB permission dialog. FLAG_IMMUTABLE
     * (required since API 31, and correct here regardless: nothing about this broadcast should ever be mutated by its
     * receiver) on the PendingIntent. [onResult] is called on the main thread with whether permission was granted.
     */
    fun requestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        val context = appContext ?: run { onResult(false); return }
        val manager = manager() ?: run { onResult(false); return }
        if (manager.hasPermission(device)) { onResult(true); return }
        val flags = PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                runCatching { context.unregisterReceiver(this) }
                onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
        manager.requestPermission(device, pendingIntent)
    }

    /** "usb:<vendorId>:<productId>:<serialNumber>" - see M1Data.kt's PrinterKind.USB_SERIAL doc comment. Requires permission to read the serial number. */
    fun identity(device: UsbDevice): String {
        val serial = if (hasPermission(device)) runCatching { device.serialNumber }.getOrNull().orEmpty() else ""
        return "usb:${device.vendorId}:${device.productId}:$serial"
    }

    /** Parses the synthetic address back into (vendorId, productId, serialNumber), or null if it isn't one of ours. */
    fun parseIdentity(address: String): Triple<Int, Int, String>? {
        if (!address.startsWith("usb:")) return null
        val parts = address.removePrefix("usb:").split(':', limit = 3)
        if (parts.size < 2) return null
        val vendorId = parts[0].toIntOrNull() ?: return null
        val productId = parts[1].toIntOrNull() ?: return null
        val serial = parts.getOrElse(2) { "" }
        return Triple(vendorId, productId, serial)
    }

    /** The currently-attached device matching [address]'s vendor:product (and serial, if the address carries one and more than one device shares that pair). */
    fun findDevice(address: String): UsbDevice? {
        val (vendorId, productId, serial) = parseIdentity(address) ?: return null
        val candidates = manager()?.deviceList?.values?.filter { it.vendorId == vendorId && it.productId == productId } ?: return null
        if (serial.isBlank() || candidates.size <= 1) return candidates.firstOrNull()
        return candidates.firstOrNull { hasPermission(it) && runCatching { it.serialNumber }.getOrNull() == serial } ?: candidates.firstOrNull()
    }

    /**
     * Builds a [UsbSerialPort] factory for [address] (a "usb:<vendorId>:<productId>:<serialNumber>" identity string).
     * The device must already be attached and permitted - [UsbSerialPrinterService] opens lazily on first use
     * ([UsbSerialPrinterService.ensureStarted]), by which point the wizard/Edit-printer flow has already walked the
     * person through [requestPermission].
     */
    fun portFactory(address: String): () -> UsbSerialPort = {
        val manager = manager() ?: throw ApiFailure("USB is unavailable.")
        val device = findDevice(address) ?: throw ApiFailure("The USB printer isn't plugged in.")
        if (!manager.hasPermission(device)) throw ApiFailure("Nozzle It All doesn't have permission to use this USB device yet. Reconnect it in Edit printer.")
        UsbSerialTransport(manager, device)
    }
}

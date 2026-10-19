package net.jamesjennison.klippercompanion.environment

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.ScanFilter
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.bluetooth.BluetoothAdapter
import android.database.sqlite.SQLiteDatabase
import com.nozzleitall.environment.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class AndroidEnvironmentalStore(file: File) : SqlStore {
    private val db: SQLiteDatabase
    init { file.parentFile.mkdirs(); db = SQLiteDatabase.openOrCreateDatabase(file, null) }
    override fun execute(sql: String, vararg args: Any?) {
        if (sql.startsWith("PRAGMA")) db.rawQuery(sql, null).use { it.moveToFirst() }
        else db.execSQL(sql, args)
    }
    override fun query(sql: String, vararg args: Any?): List<List<String?>> = db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c ->
        buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
    }
    override fun <T> transaction(block: () -> T): T { db.beginTransaction(); try { val value = block(); db.setTransactionSuccessful(); return value } finally { db.endTransaction() } }
    override fun close() = db.close()
}

class AndroidBleTransport(private val context: Context) : BleTransport {
    @SuppressLint("MissingPermission")
    override fun start(request: BleScanRequest, report: (Advertisement) -> Unit, failure: (String) -> Unit): RadioSession {
        check(request.isCurrent()) { "Obsolete BLE operation" }
        // Read only during an explicit scan grant. App/user/device-scoped, no CONNECT or raw adapter address.
        val scopedId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        val fingerprint = scopedId?.takeIf { it.isNotBlank() }?.let { id ->
            java.security.MessageDigest.getInstance("SHA-256").digest("${android.os.Build.MODEL}|${android.os.Build.FINGERPRINT}|$id".toByteArray()).joinToString("") { "%02x".format(it) }
        }
        val description = "Android BLE · radio ${fingerprint ?: "unavailable"}"
        if (request.requiredAdapterDescription != null && (fingerprint == null || request.requiredAdapterDescription != description)) throw QualificationUnavailableException()
        check(request.isCurrent()) { "Obsolete BLE operation" }
        val filters = if (request.discovery) null else request.selectedAddresses.map { ScanFilter.Builder().setDeviceAddress(it).build() }.also { require(it.isNotEmpty()) }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("Bluetooth unavailable")
        check(adapter.isEnabled) { "Bluetooth off" }
        val scanner = adapter.bluetoothLeScanner ?: error("BLE scan unavailable")
        val closed = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        fun fail(reason: String) { if (failed.compareAndSet(false, true)) failure(reason) }
        val callback = object : ScanCallback() {
            override fun onScanFailed(errorCode: Int) { fail("BLE unavailable — scan failed; press Start to retry") }
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (closed.get() || failed.get() || !request.isCurrent()) return
                try {
                    val record = result.scanRecord ?: return
                    val manufacturer = record.manufacturerSpecificData
                    if (manufacturer.size() > 16 || (record.serviceData?.size ?: 0) > 16) return
                    if ((0 until manufacturer.size()).any { manufacturer.valueAt(it).size > 512 } || record.serviceData.orEmpty().values.any { it.size > 512 }) return
                    report(Advertisement(result.device.address, record.deviceName,
                        (0 until manufacturer.size()).associate { manufacturer.keyAt(it) to manufacturer.valueAt(it).copyOf() },
                        record.serviceData.orEmpty().mapKeys { it.key.uuid.toString() }.mapValues { it.value.copyOf() },
                        System.currentTimeMillis(), System.nanoTime()))
                } catch (_: SecurityException) { fail("BLE permission revoked — stopped") }
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED && intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) fail("Bluetooth off — stopped; no automatic restart")
            }
        }
        // Protected platform action, sent by the Bluetooth UID rather than SYSTEM_UID.
        var receiverRegistered = false
        try {
            check(request.isCurrent()) { "Obsolete BLE operation" }
            androidx.core.content.ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
            check(request.isCurrent() && !failed.get()) { "Obsolete BLE operation" }
            scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build(), callback)
        } catch (e: Exception) {
            closed.set(true)
            if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
            throw e
        }
        return object : RadioSession {
            override val adapterDescription = description
            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                try { scanner.stopScan(callback) } finally { context.unregisterReceiver(receiver) }
            }
        }
    }
}

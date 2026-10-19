package com.nozzleitall.desktop.environment

import com.nozzleitall.environment.*
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.atomic.AtomicBoolean

class JdbcEnvironmentalStore(file: File) : SqlStore {
    private val connection: Connection
    private val statements = object : LinkedHashMap<String, java.sql.PreparedStatement>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, java.sql.PreparedStatement>): Boolean {
            if (size <= 32) return false
            eldest.value.close(); return true
        }
    }
    init {
        file.parentFile.mkdirs()
        java.nio.file.Files.setPosixFilePermissions(file.parentFile.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        java.nio.file.Files.setPosixFilePermissions(file.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
    }
    private fun statement(sql: String, args: Array<out Any?>): java.sql.PreparedStatement {
        val s = statements.getOrPut(sql) { connection.prepareStatement(sql) }
        s.clearParameters(); args.forEachIndexed { i, v -> s.setObject(i + 1, v) }; return s
    }
    override fun execute(sql: String, vararg args: Any?) {
        if (sql == "VACUUM") { statements.values.forEach { it.close() }; statements.clear() }
        if (sql.startsWith("PRAGMA") || sql == "VACUUM") connection.prepareStatement(sql).use { s -> args.forEachIndexed { i, v -> s.setObject(i + 1, v) }; s.execute() }
        else { val s = statement(sql, args); if (s.execute()) s.resultSet?.close() }
    }
    override fun query(sql: String, vararg args: Any?): List<List<String?>> = statement(sql, args).executeQuery().use { r ->
        buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) }
    }
    override fun <T> transaction(block: () -> T): T {
        connection.autoCommit = false
        var failed: Throwable? = null
        try { val result = block(); connection.commit(); return result }
        catch (e: Throwable) {
            failed = e
            // SQLITE_FULL can roll back automatically. Preserve the original failure if rollback then has no transaction.
            try { connection.rollback() } catch (cleanup: Throwable) { e.addSuppressed(cleanup) }
            // SQLite may invalidate the prepared VM on a failed step (notably SQLITE_FULL).
            statements.values.forEach { s -> try { s.close() } catch (cleanup: Throwable) { e.addSuppressed(cleanup) } }
            statements.clear()
            throw e
        } finally {
            try { connection.autoCommit = true } catch (cleanup: Throwable) { if (failed != null) failed.addSuppressed(cleanup) else throw cleanup }
        }
    }
    override fun close() { statements.values.forEach { it.close() }; statements.clear(); connection.close() }
}

@DBusInterfaceName("org.bluez.Adapter1")
interface BluezEnvironmentalAdapter : DBusInterface {
    fun SetDiscoveryFilter(filter: Map<String, Variant<*>>)
    fun StartDiscovery()
    fun StopDiscovery()
}

/** One private D-Bus connection, one owned discovery reference. Never powers, pairs, removes or unbinds. */
class LinuxBleTransport(private val requestedAdapter: String? = null) : BleTransport {
    override fun start(request: BleScanRequest, report: (Advertisement) -> Unit, failure: (String) -> Unit): RadioSession {
        check(request.isCurrent()) { "Obsolete BLE operation" }
        val closed = AtomicBoolean(false)
        val bus = DBusConnectionBuilder.forSystemBus().withShared(false)
            .withDisconnectCallback(object : org.freedesktop.dbus.connections.IDisconnectCallback {
                override fun disconnectOnError(error: java.io.IOException) { if (!closed.get()) failure("BlueZ connection lost — stopped; no automatic restart") }
            }).build()
        val acquired = AtomicBoolean(false)
        var adapter: BluezEnvironmentalAdapter? = null
        val metadata = object : LinkedHashMap<String, Pair<String, String?>>(64, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String?>>) = size > 64
        }
        try {
            val manager = bus.getRemoteObject("org.bluez", "/", ObjectManager::class.java)
            val bluezOwner = bus.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", org.freedesktop.dbus.interfaces.DBus::class.java).GetNameOwner("org.bluez")
            val adapters = manager.GetManagedObjects().entries.filter { it.value.containsKey("org.bluez.Adapter1") }.sortedBy { it.key.path }
            val path = (if (requestedAdapter == null) adapters.firstOrNull { it.value["org.bluez.Adapter1"]?.get("Powered")?.value == true }
                else adapters.firstOrNull { it.key.path == requestedAdapter })?.key?.path
                ?: error("Bluetooth adapter unavailable")
            require(request.requiredAdapterDescription == null || request.requiredAdapterDescription == "BlueZ $path") { "Adapter not qualified for this assignment" }
            adapter = bus.getRemoteObject("org.bluez", path, BluezEnvironmentalAdapter::class.java)
            // D-Bus daemon combines client filters. Host still admits only fresh authorized reports.
            check(request.isCurrent()) { "Obsolete BLE operation" }
            adapter.SetDiscoveryFilter(mapOf("Transport" to Variant("le"), "DuplicateData" to Variant(true)))
            fun process(devicePath: String, changed: Map<String, Variant<*>>) {
                if (closed.get() || !acquired.get() || !request.isCurrent()) return
                if (!devicePath.startsWith("$path/dev_") || ("ManufacturerData" !in changed && "ServiceData" !in changed)) return
                try {
                    val pathAddress = devicePath.substringAfterLast("/dev_").replace('_', ':')
                    if (!request.discovery && request.selectedAddresses.none { it.equals(pathAddress, true) }) return
                    val info = synchronized(metadata) { metadata[devicePath] } ?: run {
                        val props = bus.getRemoteObject("org.bluez", devicePath, Properties::class.java).GetAll("org.bluez.Device1")
                        val address = props["Address"]?.value as? String ?: return
                        val name = props["Name"]?.value as? String
                        if (address.length > 64 || (name?.length ?: 0) > 256) return
                        (address to name).also {
                            if (!request.discovery || name?.let { n -> listOf("H5100", "H5110", "H5074", "H5179").any(n::contains) } == true || (changed["ServiceData"]?.value as? Map<*, *>)?.containsKey("0000fcd2-0000-1000-8000-00805f9b34fb") == true) synchronized(metadata) { metadata[devicePath] = it }
                        }
                    }
                    val address = info.first
                    if (!request.discovery && request.selectedAddresses.none { it.equals(address, true) }) return
                    val name = info.second
                    fun bytes(value: Any?): ByteArray? = when (value) {
                        is ByteArray -> value.takeIf { it.size <= 512 }?.copyOf()
                        is List<*> -> value.takeIf { it.size <= 512 }?.map { (it as Number).toByte() }?.toByteArray()
                        else -> null
                    }
                    fun raw(key: String): Map<*, *> = changed[key]?.value as? Map<*, *> ?: emptyMap<Any, Any>()
                    val manufacturer = raw("ManufacturerData").takeIf { it.size <= 16 }?.entries?.mapNotNull { (k, v) ->
                        val payload = bytes((v as? Variant<*>)?.value ?: v) ?: return@mapNotNull null
                        (k as? Number)?.toInt()?.let { it to payload }
                    }?.toMap() ?: emptyMap()
                    val services = raw("ServiceData").takeIf { it.size <= 16 }?.entries?.mapNotNull { (k, v) ->
                        val payload = bytes((v as? Variant<*>)?.value ?: v) ?: return@mapNotNull null
                        (k as? String)?.let { it to payload }
                    }?.toMap() ?: emptyMap()
                    report(Advertisement(address, name, manufacturer, services, System.currentTimeMillis(), System.nanoTime()))
                } catch (_: Exception) { /* Removed transient device or malformed property: no nearby-device logging. */ }
            }
            bus.addSigHandler(Properties.PropertiesChanged::class.java) { s ->
                if (s.source != bluezOwner) return@addSigHandler
                if ("Name" in s.propertiesChanged || "Name" in s.propertiesRemoved || "Address" in s.propertiesChanged) synchronized(metadata) { metadata.remove(s.path) }
                if (s.interfaceName == "org.bluez.Adapter1" && s.path == path && s.propertiesChanged["Powered"]?.value == false) failure("Bluetooth off — stopped; no automatic restart")
                if (s.interfaceName == "org.bluez.Device1") process(s.path, s.propertiesChanged)
            }
            bus.addSigHandler(ObjectManager.InterfacesAdded::class.java) { s -> if (s.source == bluezOwner) s.interfaces["org.bluez.Device1"]?.let { process(s.signalSource.path, it) } }
            bus.addSigHandler(ObjectManager.InterfacesRemoved::class.java) { s -> if (s.source == bluezOwner) synchronized(metadata) { metadata.remove(s.signalSource.path) } }
            bus.addSigHandler(org.freedesktop.dbus.interfaces.DBus.NameOwnerChanged::class.java) { s ->
                if (s.source == "org.freedesktop.DBus" && s.name == "org.bluez" && s.oldOwner.isNotEmpty() && s.oldOwner != s.newOwner && !closed.get()) failure("BlueZ service changed — stopped; no automatic restart")
            }
            check(request.isCurrent()) { "Obsolete BLE operation" }
            adapter.StartDiscovery(); acquired.set(true)
            val ownedAdapter = adapter
            return object : RadioSession {
                override val adapterDescription = "BlueZ $path"
                override fun close() {
                    if (!closed.compareAndSet(false, true)) return
                    try { if (acquired.compareAndSet(true, false)) ownedAdapter.StopDiscovery() }
                    finally { bus.close() }
                }
            }
        } catch (e: Exception) {
            closed.set(true)
            try { if (acquired.compareAndSet(true, false)) adapter?.StopDiscovery() } finally { bus.close() }
            throw e
        }
    }
}

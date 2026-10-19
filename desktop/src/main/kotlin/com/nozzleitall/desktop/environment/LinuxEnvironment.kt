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
    override fun execute(sql: String, vararg args: Any?) { val s = statement(sql, args); if (s.execute()) s.resultSet?.close() }
    override fun query(sql: String, vararg args: Any?): List<List<String?>> = statement(sql, args).executeQuery().use { r ->
        buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) }
    }
    override fun <T> transaction(block: () -> T): T { connection.autoCommit = false; try { val result = block(); connection.commit(); return result } catch (e: Throwable) { connection.rollback(); throw e } finally { connection.autoCommit = true } }
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
        val bus = DBusConnectionBuilder.forSystemBus().build()
        val acquired = AtomicBoolean(false)
        var adapter: BluezEnvironmentalAdapter? = null
        val closed = AtomicBoolean(false)
        try {
            val manager = bus.getRemoteObject("org.bluez", "/", ObjectManager::class.java)
            val adapters = manager.GetManagedObjects().entries.filter { it.value.containsKey("org.bluez.Adapter1") }.sortedBy { it.key.path }
            val path = (if (requestedAdapter == null) adapters.firstOrNull { it.value["org.bluez.Adapter1"]?.get("Powered")?.value == true }
                else adapters.firstOrNull { it.key.path == requestedAdapter })?.key?.path
                ?: error("Bluetooth adapter unavailable")
            adapter = bus.getRemoteObject("org.bluez", path, BluezEnvironmentalAdapter::class.java)
            // D-Bus daemon combines client filters. Host still admits only fresh authorized reports.
            adapter.SetDiscoveryFilter(mapOf("Transport" to Variant("le"), "DuplicateData" to Variant(true)))
            fun process(devicePath: String, changed: Map<String, Variant<*>>) {
                if (closed.get() || !acquired.get()) return
                if (!devicePath.startsWith("$path/dev_") || ("ManufacturerData" !in changed && "ServiceData" !in changed)) return
                try {
                    val props = bus.getRemoteObject("org.bluez", devicePath, Properties::class.java).GetAll("org.bluez.Device1")
                    val address = props["Address"]?.value as? String ?: return
                    if (!request.discovery && request.selectedAddresses.none { it.equals(address, true) }) return
                    val name = props["Name"]?.value as? String
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
                if (s.interfaceName == "org.bluez.Adapter1" && s.path == path && s.propertiesChanged["Powered"]?.value == false) failure("Bluetooth off — stopped; no automatic restart")
                if (s.interfaceName == "org.bluez.Device1") process(s.path, s.propertiesChanged)
            }
            bus.addSigHandler(ObjectManager.InterfacesAdded::class.java) { s -> s.interfaces["org.bluez.Device1"]?.let { process(s.signalSource.path, it) } }
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

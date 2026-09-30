package com.nozzleitall.desktop

import com.nozzleitall.printer.*
import com.nozzleitall.project.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class DesktopAcceptanceTest {
    private fun tmp() = Files.createTempDirectory("nozzle").toFile().apply { deleteOnExit() }
    private fun paths() = tmp().let { AppPaths(File(it, "config"), File(it, "data"), File(it, "cache")).ensure() }

    @Test fun dataLocationsAreNozzlesOwnAndNeverAnotherSlicers() {
        val linux = AppPaths.resolve(env = emptyMap(), home = "/home/u", os = "Linux")
        assertEquals("/home/u/.config/nozzle-it-all", linux.config.path)
        assertEquals("/home/u/.local/share/nozzle-it-all", linux.data.path)
        assertEquals("/home/u/.cache/nozzle-it-all", linux.cache.path)
        val win = AppPaths.resolve(env = mapOf("APPDATA" to "C:\\Users\\u\\AppData\\Roaming", "LOCALAPPDATA" to "C:\\Users\\u\\AppData\\Local"), home = "C:\\Users\\u", os = "Windows 11")
        assertTrue(win.config.path.contains("Nozzle It All"))
        for (p in listOf(linux, win)) for (dir in listOf(p.config, p.data, p.cache))
            AppPaths.foreignFolderNames.forEach { assertFalse("$dir overlaps $it", dir.path.split('/', '\\').any { seg -> seg.equals(it, true) }) }
    }

    @Test fun printerSecretsAreSeparateAndOwnerOnly() {
        val p = paths()
        val store = PrinterStore(p)
        val cfg = PrinterConfig(PrinterIdentity("a", "Workshop", "Snapmaker U1", PrinterFamily.PAXX_U1, "http://192.168.1.40"), "paxx-lan", "secret-key")
        store.save(listOf(cfg))
        assertFalse(p.printers.readText().contains("secret-key"))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(p.secrets.toPath())))
        // Fields written by a newer version survive a save by this one.
        p.printers.writeText(JSONObject(p.printers.readText()).apply { getJSONArray("printers").getJSONObject(0).put("futureField", 7) }.toString())
        store.save(store.load())
        assertEquals(7, JSONObject(p.printers.readText()).getJSONArray("printers").getJSONObject(0).getInt("futureField"))
        assertEquals("secret-key", store.load().single().secret)
    }

    // --- PAXX baseline isolation ---

    @Test fun paxxOnlyFleetNeverRegistersOrStartsStockSupport() {
        val p = paths()
        PrinterStore(p).save(listOf(PrinterConfig(PrinterIdentity("u1", "U1", "Snapmaker U1", PrinterFamily.PAXX_U1, "http://127.0.0.1:9"), "paxx-lan")))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val fleet = Fleet(p, scope)
        assertTrue("Stock support is off by default", !fleet.settings.value.stockU1Enabled)
        assertTrue(fleet.registry.optionalIds.isEmpty())
        assertTrue(fleet.registry.startedOptionalIds.isEmpty())
        fleet.shutdown(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test fun missingStockSupportOnlyAffectsStockPrinters() {
        val p = paths()
        // Stock support "enabled" but its helper points at nothing; a PAXX printer must still be served by the PAXX adapter.
        p.settings.writeText(DesktopSettings(stockU1Enabled = true).toJson().toString())
        PrinterStore(p).save(listOf(
            PrinterConfig(PrinterIdentity("paxx", "PAXX", "Snapmaker U1", PrinterFamily.PAXX_U1, "http://127.0.0.1:9"), "paxx-lan"),
            PrinterConfig(PrinterIdentity("stock", "Stock", "Snapmaker U1", PrinterFamily.STOCK_U1, "http://127.0.0.1:9"), "stock-u1")))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val fleet = Fleet(p, scope, stockHelper = { null }) // not installed, whatever this machine has in /opt
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && (fleet.printers["paxx"]?.capabilities?.value == null || fleet.printers["stock"]?.problem?.value == null)) Thread.sleep(100)
        assertNotNull("PAXX printer opened through the PAXX adapter", fleet.printers["paxx"]!!.capabilities.value)
        assertFalse(fleet.printers["paxx"]!!.capabilities.value!!.requiresVendorAccount)
        assertNull(fleet.printers["paxx"]!!.problem.value)
        assertNotNull("Stock printer reports why it can't connect", fleet.printers["stock"]!!.problem.value)
        fleet.shutdown(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}

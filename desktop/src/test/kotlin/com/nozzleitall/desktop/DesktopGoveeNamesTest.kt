package com.nozzleitall.desktop

import com.nozzleitall.desktop.environment.DesktopGoveeNames
import com.nozzleitall.environment.*
import org.junit.Assert.*
import org.junit.Test

class DesktopGoveeNamesTest {
    @Test fun failedForgetCannotSilentlyReloadThePreservedDiskDirectory() {
        val parent = java.nio.file.Files.createTempDirectory("nia-forget-failure").toFile()
        val file = java.io.File(parent, "names.json")
        val backup = java.io.File(parent, "old-names.json")
        GoveeNameCache(file).write(listOf(GoveeDeviceName("AA:BB:CC:DD:EE:FF", "H5100", "Old name")))
        java.nio.file.Files.move(file.toPath(), backup.toPath())
        check(file.mkdir())
        val sentinel = java.io.File(file, "fixture").apply { writeText("fixture") }
        val store = DesktopGoveeNames(GoveeNameCache(file))
        assertThrows(GoveeNameException::class.java) { store.forget() }
        sentinel.delete(); file.delete()
        java.nio.file.Files.move(backup.toPath(), file.toPath())
        assertThrows(GoveeNameException::class.java) { store.cachedDevices() }
        assertTrue(store.devices.value.isEmpty())
        store.forget(); assertTrue(store.cachedDevices().isEmpty())
    }
    @Test fun cacheFailureIsStickyUntilExplicitRefreshAndDoesNotRepeatedlyReadDisk() {
        val file = java.nio.file.Files.createTempDirectory("nia-cache-failure").resolve("directory.json").toFile()
        file.writeText("corrupt")
        val store = DesktopGoveeNames(GoveeNameCache(file)) { _, _ -> listOf(GoveeDeviceName("AA:BB:CC:DD:EE:FF", "H5100", "Refreshed")) }
        assertThrows(GoveeNameException::class.java) { store.cachedDevices() }
        GoveeNameCache(file).write(listOf(GoveeDeviceName("AA:BB:CC:DD:EE:FF", "H5100", "Do not silently reload")))
        assertThrows(GoveeNameException::class.java) { store.cachedDevices() }
        assertTrue(store.devices.value.isEmpty())
        store.import("synthetic-fixture", store.begin())
        assertEquals("Refreshed", store.cachedDevices().single().name)
    }
    @Test fun pendingForgetCannotRepublishNamesWhileWaitingForStorageLock() {
        val file = java.nio.file.Files.createTempDirectory("nia-cache-forget-race").resolve("directory.json").toFile()
        GoveeNameCache(file).write(listOf(GoveeDeviceName("AA:BB:CC:DD:EE:FF", "H5100", "Old name")))
        val store = DesktopGoveeNames(GoveeNameCache(file))
        val mutex = store.javaClass.getDeclaredField("storageLock").apply { isAccessible = true }.get(store)
        val worker = Thread { store.forget() }
        synchronized(mutex) {
            worker.start()
            val end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (worker.state != Thread.State.BLOCKED && System.nanoTime() < end) Thread.sleep(1)
            assertEquals(Thread.State.BLOCKED, worker.state)
            assertTrue(store.cachedDevices().isEmpty())
        }
        worker.join(5000); assertFalse(worker.isAlive)
        assertTrue(store.cachedDevices().isEmpty()); assertTrue(GoveeNameCache(file).read().isEmpty())
    }
    @Test fun newProcessRestoresDirectoryWithoutKeyOrNetworkAndForgetPersists() {
        val file = java.nio.file.Files.createTempDirectory("nia-desktop-names").resolve("directory.json").toFile()
        val first = DesktopGoveeNames(GoveeNameCache(file)) { _, _ -> listOf(GoveeDeviceName("AA:BB:CC:DD:EE:FF", "H5100", "Shelf")) }
        first.import("synthetic-fixture", first.begin())
        val restarted = DesktopGoveeNames(GoveeNameCache(file)) { _, _ -> error("Discovery must not request cloud names") }
        assertTrue(restarted.devices.value.isEmpty()) // No startup IO.
        assertEquals("Shelf", restarted.cachedDevices().single().name)
        restarted.forget()
        assertTrue(DesktopGoveeNames(GoveeNameCache(file)).cachedDevices().isEmpty())
    }
    @Test fun importedExtendedIdentitiesResolveFullAddressesWithoutManualSelection() {
        val directory = DesktopGoveeNames { _, _ -> listOf(
            GoveeDeviceName("11:22:AA:BB:CC:DD:EE:FF", "H5110", "Front left"),
            GoveeDeviceName("33:44:AB:BB:CC:DD:EE:FF", "H5110", "Rear right")) }
        directory.import("synthetic-fixture", directory.begin())
        assertEquals("Front left", directory.devices.value.singleOrNull {
            it.matches(Candidate("AA:BB:CC:DD:EE:FF", "govee-ble", "H5110")) }?.name)
        assertEquals("Rear right", directory.devices.value.singleOrNull {
            it.matches(Candidate("AB:BB:CC:DD:EE:FF", "govee-ble", "H5110")) }?.name)
    }
    @Test fun explicitImportAndForgetRejectStaleNames() {
        lateinit var directory: DesktopGoveeNames
        var cancelDuringFetch = false
        directory = DesktopGoveeNames { key, current ->
            assertEquals("synthetic-fixture", key)
            if (cancelDuringFetch) directory.forget() else assertTrue(current())
            listOf(GoveeDeviceName("AA:BB:CC:DD:EE:FF", "H5100", "Front left"))
        }
        assertTrue(directory.devices.value.isEmpty())
        assertEquals(1, directory.import("synthetic-fixture", directory.begin()))
        assertEquals("Front left", directory.devices.value.single().name)
        cancelDuringFetch = true
        assertThrows(GoveeNameException::class.java) { directory.import("synthetic-fixture", directory.begin()) }
        assertTrue(directory.devices.value.isEmpty())
    }
}

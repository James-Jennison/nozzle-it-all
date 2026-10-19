package com.nozzleitall.desktop

import com.nozzleitall.desktop.environment.DesktopGoveeNames
import com.nozzleitall.environment.*
import org.junit.Assert.*
import org.junit.Test

class DesktopGoveeNamesTest {
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

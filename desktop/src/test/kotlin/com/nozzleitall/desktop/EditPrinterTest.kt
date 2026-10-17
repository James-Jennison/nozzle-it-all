package com.nozzleitall.desktop

import com.nozzleitall.printer.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Changing a saved printer's address (and name, model, credentials) keeps its identity and is saved. */
class EditPrinterTest {
    private fun paths(): AppPaths { val tmp = Files.createTempDirectory("edit-printer").toFile(); return AppPaths(File(tmp, "c"), File(tmp, "d"), File(tmp, "k")).ensure() }

    @Test fun anEditedAddressIsKeptUnderTheSameIdAndSurvivesARestart() {
        val p = paths()
        PrinterStore(p).save(listOf(
            PrinterConfig(PrinterIdentity("u1", "U1", "Snapmaker U1", PrinterFamily.PAXX_U1, "http://192.168.1.50", "snapmaker_u1"), "paxx-lan", secret = "key-1"),
            PrinterConfig(PrinterIdentity("cc1", "CC1", "Elegoo CC1", PrinterFamily.ELEGOO, "192.168.1.114"), "elegoo")))
        val scope = CoroutineScope(Dispatchers.Default)
        val fleet = Fleet(p, scope)
        val before = fleet.printers.getValue("u1").config
        fleet.update(before.copy(identity = before.identity.copy(address = "http://192.168.1.77", displayName = "Workshop U1")))
        assertEquals(listOf("u1", "cc1"), fleet.order.value)   // still first on the Printers page
        val after = fleet.printers.getValue("u1").config
        assertEquals("http://192.168.1.77", after.identity.address); assertEquals("Workshop U1", after.identity.displayName)
        assertEquals("snapmaker_u1", after.identity.profileId); assertEquals("key-1", after.secret)   // the profile and credential stay
        fleet.shutdown(); scope.coroutineContext[Job]?.cancel()
        val reloaded = PrinterStore(p).load().associateBy { it.identity.id }
        assertEquals("http://192.168.1.77", reloaded.getValue("u1").identity.address); assertEquals("192.168.1.114", reloaded.getValue("cc1").identity.address)
    }
}

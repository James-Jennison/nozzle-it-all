package com.nozzleitall.desktop

import com.nozzleitall.printer.*
import com.nozzleitall.project.*
import com.nozzleitall.desktop.workspace.AdvancedWorkspace
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
        for (p in listOf(linux, win)) for (dir in listOf(p.config, p.data, p.cache, p.workspaceProfile))
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

    // --- Advanced Workspace round trips ---

    private fun cube() = Mesh(floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f), intArrayOf(0, 2, 1, 0, 1, 3, 1, 2, 3, 0, 3, 2))
    private fun manifest() = ProjectManifest("proj-1", 3, "Test", ProjectManifest.Producer("Nozzle It All", "desktop", "t"), ProjectManifest.Producer("Nozzle It All", "desktop", "t"), 1,
        ProjectManifest.PrinterTarget("Snapmaker U1", "PAXX"), listOf(ProjectManifest.PlateEntry(1, "Plate 1", listOf(ProjectManifest.ObjectEntry(1, "Cube", 2)))),
        listOf(ProjectManifest.MaterialSlot(1, "PLA", colorHex = "#FFFFFF"), ProjectManifest.MaterialSlot(2, "PETG", colorHex = "#FF0000")),
        extensions = JSONObject().put("android", JSONObject().put("keep", true)))
    private fun project() = Project3mf(listOf(ModelObject(1, "Cube", cube(), Transform.translate(50.0, 50.0, 0.0))), mapOf("Title" to "Test"), manifest(),
        mapOf("Metadata/project_settings.config" to "{}".toByteArray()))

    private fun session(p: AppPaths): Pair<AdvancedWorkspace, AdvancedWorkspace.Session> {
        val file = File(p.projects, "Test.3mf"); ThreeMf.writeAtomically(project(), file)
        val ws = AdvancedWorkspace(p); return ws to ws.begin(file)
    }

    @Test fun unchangedSessionChangesNothing() {
        val p = paths(); val (ws, s) = session(p)
        assertEquals(AdvancedWorkspace.ReturnResult.Unchanged, ws.collect(s, 3, project()))
    }

    @Test fun workspaceEditsComeBackWithNozzleMetadataAndUnknownFieldsIntact() {
        val p = paths(); val (ws, s) = session(p)
        // Simulate the workspace moving the object and adding its own settings file, keeping Nozzle's entries (as Orca does).
        val edited = ThreeMf.read(s.workingCopy).let { it.copy(objects = it.objects.map { o -> o.copy(placement = Transform.translate(80.0, 60.0, 0.0)) },
            passthrough = it.passthrough + ("Metadata/model_settings.config" to "<config/>".toByteArray())) }
        s.workingCopy.writeBytes(ThreeMf.write(edited))
        val r = ws.collect(s, 3, project()) as AdvancedWorkspace.ReturnResult.Changed
        assertFalse(r.restoredManifest)
        assertEquals(Transform.translate(80.0, 60.0, 0.0), r.project.objects.single().placement)
        assertEquals(2, r.project.manifest!!.plates.single().objects.single().materialSlot)
        assertTrue(r.project.manifest!!.extensions.getJSONObject("android").getBoolean("keep"))
        assertEquals(4, r.project.manifest!!.revision)
        assertTrue(r.project.passthrough.containsKey("Metadata/model_settings.config"))
        assertTrue(r.project.passthrough.containsKey("Metadata/project_settings.config"))
    }

    @Test fun strippedNozzleDetailsAreRestoredAndFlagged() {
        val p = paths(); val (ws, s) = session(p)
        val bare = ThreeMf.read(s.workingCopy).copy(manifest = null, passthrough = emptyMap())
        s.workingCopy.writeBytes(ThreeMf.write(bare.copy(metadata = bare.metadata - ProjectManifest.META_PROJECT_ID)))
        val r = ws.collect(s, 3, project()) as AdvancedWorkspace.ReturnResult.Changed
        assertTrue(r.restoredManifest)
        assertEquals("proj-1", r.project.manifest!!.projectId)
    }

    @Test fun interruptedOrDamagedSaveIsRefusedAndOriginalKept() {
        val p = paths(); val (ws, s) = session(p)
        val bytes = s.workingCopy.readBytes()
        s.workingCopy.writeBytes(bytes.copyOf(bytes.size / 3))
        val r = ws.collect(s, 3, project())
        assertTrue(r is AdvancedWorkspace.ReturnResult.Refused)
        assertEquals(3, ThreeMf.read(s.originalFile).manifest!!.revision)
    }

    @Test fun aDifferentProjectIsRefused() {
        val p = paths(); val (ws, s) = session(p)
        s.workingCopy.writeBytes(ThreeMf.write(project().copy(manifest = manifest().copy(projectId = "someone-else"))))
        assertTrue(ws.collect(s, 3, project()) is AdvancedWorkspace.ReturnResult.Refused)
    }

    @Test fun editsInBothPlacesAreAConflictNotAnOverwrite() {
        val p = paths(); val (ws, s) = session(p)
        s.workingCopy.writeBytes(ThreeMf.write(project().copy(objects = project().objects.map { it.copy(placement = Transform.translate(1.0, 1.0, 0.0)) })))
        assertTrue(ws.collect(s, currentRevision = 5, nozzleCopy = project()) is AdvancedWorkspace.ReturnResult.Conflict)
    }

    @Test fun newerSessionFormatIsRefused() {
        val p = paths(); val (ws, s) = session(p)
        s.sessionFile.writeText(JSONObject(s.sessionFile.readText()).put("version", org.json.JSONArray().put(2).put(0)).toString())
        s.workingCopy.writeBytes(ThreeMf.write(project().copy(metadata = mapOf("Title" to "changed"))))
        val r = ws.collect(s, 3, project())
        assertTrue(r is AdvancedWorkspace.ReturnResult.Refused)
        assertTrue((r as AdvancedWorkspace.ReturnResult.Refused).message.contains("matching versions"))
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
        val fleet = Fleet(p, scope)
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && (fleet.printers["paxx"]?.capabilities?.value == null || fleet.printers["stock"]?.problem?.value == null)) Thread.sleep(100)
        assertNotNull("PAXX printer opened through the PAXX adapter", fleet.printers["paxx"]!!.capabilities.value)
        assertFalse(fleet.printers["paxx"]!!.capabilities.value!!.requiresVendorAccount)
        assertNull(fleet.printers["paxx"]!!.problem.value)
        assertNotNull("Stock printer reports why it can't connect", fleet.printers["stock"]!!.problem.value)
        fleet.shutdown(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}

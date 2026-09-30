package com.nozzleitall.desktop

import com.nozzleitall.desktop.prepare.*
import com.nozzleitall.printer.*
import com.nozzleitall.project.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Nozzle is multi-vendor: capability-driven screens, independent adapters, profiles without connections. */
class MultiVendorAcceptanceTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private fun paths() = Files.createTempDirectory("mv").toFile().let { AppPaths(File(it, "config"), File(it, "data"), File(it, "cache")).ensure() }

    @Test fun adaptersComeOnlyFromTheModulesShipped() {
        val fleet = Fleet(paths(), CoroutineScope(Dispatchers.Default))
        val expected = (System.getProperty("nozzle.adapters") ?: "paxx,octoprint,prusa,bambu,elegoo").split(",").map { it.trim() }.toSet()
        val families = fleet.registry.builtInIds.flatMap { fleet.registry.adapter(it).families }.toSet()
        assertTrue("PAXX U1 is always there", PrinterFamily.PAXX_U1 in families)
        if ("octoprint" in expected) assertTrue(PrinterFamily.OCTOPRINT in families) else assertFalse(PrinterFamily.OCTOPRINT in families)
        if ("prusa" in expected) assertTrue(PrinterFamily.PRUSA in families) else assertFalse(PrinterFamily.PRUSA in families)
        if ("bambu" in expected) assertTrue(PrinterFamily.BAMBU_LAB in families) else assertFalse(PrinterFamily.BAMBU_LAB in families)
        if ("elegoo" in expected) assertTrue(PrinterFamily.ELEGOO in families) else assertFalse(PrinterFamily.ELEGOO in families)
        // No built-in adapter may use a vendor cloud; the registry refuses them, and Stock U1 is never among them.
        assertTrue(fleet.registry.builtInIds.none { fleet.registry.adapter(it).mayUseVendorCloud })
        assertFalse("stock-u1" in fleet.registry.builtInIds)
        fleet.shutdown()
    }

    @Test fun aPrinterWhoseAdapterIsMissingOnlyAffectsItself() {
        val p = paths()
        PrinterStore(p).save(listOf(
            PrinterConfig(PrinterIdentity("paxx", "PAXX", "Snapmaker U1", PrinterFamily.PAXX_U1, "http://127.0.0.1:9"), "paxx-lan"),
            PrinterConfig(PrinterIdentity("acme", "Acme", "Acme 1", PrinterFamily("acme"), "http://127.0.0.1:9"), "acme-adapter-not-installed"),
            PrinterConfig(PrinterIdentity("exp", "Shelf printer", "Voron 2.4", PrinterFamily.EXPORT_ONLY, "", "generic_klipper"), EXPORT_ONLY)))
        val fleet = Fleet(p, CoroutineScope(Dispatchers.Default))
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && (fleet.printers["paxx"]!!.capabilities.value == null || fleet.printers["acme"]!!.problem.value == null)) Thread.sleep(100)
        assertNotNull(fleet.printers["paxx"]!!.capabilities.value)
        assertNotNull(fleet.printers["acme"]!!.problem.value)
        assertEquals(ConnectionRoute.NONE, fleet.printers["exp"]!!.status.value.route)
        assertEquals(Capabilities.EXPORT_ONLY, fleet.printers["exp"]!!.capabilities.value)
        fleet.shutdown()
    }

    @Test fun screensFollowCapabilitiesNotVendorNames() {
        // Family constants may be used where a family is chosen (adding a printer), for the optional Stock support
        // switch, and where printers saved by older versions are read; screens that show or control printers must decide
        // from capabilities only.
        val allowed = setOf("AddPrinterDialog.kt", "Fleet.kt", "PrinterStore.kt")
        val pattern = Regex("PrinterFamily\\.(PAXX_U1|STOCK_U1|BAMBU_LAB|PRUSA|ELEGOO|KLIPPER|OCTOPRINT)|family\\s*==|\\.family\\.id\\s*==|\"(bambu|prusa|octoprint|paxx)")
        val offenders = File(root, "desktop/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" && it.name !in allowed }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l -> if (pattern.containsMatchIn(l.substringBefore("//"))) "${f.name}:${i + 1}: ${l.trim()}" else null }
        }.toList()
        assertTrue("Vendor-name branching in screens:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test fun profilesAreIndexedWithTheirSource() {
        assertTrue("at least the flagship and the main families", ProfileCatalog.all.size > 100)
        listOf("snapmaker_u1", "prusa_generic", "prusa_xl_5t", "bambu_generic", "generic_klipper").forEach { assertNotNull(it, ProfileCatalog.byId(it)) }
        val index = JSONObject(File(root, "app/src/main/assets/slicer_profiles/index.json").readText())
        val source = index.getJSONObject("source")
        assertTrue(source.getString("commit").matches(Regex("[0-9a-f]{40}")))
        assertTrue(source.getString("licence").contains("AGPL"))
        assertEquals(4, ProfileCatalog.byId("snapmaker_u1")!!.tools)
        assertEquals(5, ProfileCatalog.byId("prusa_xl_5t")!!.tools)
    }

    @Test fun projectsCarryProfileIdentityButNeverCredentialsOrAddresses() {
        val m = ProjectManifest("p", 1, "n", ProjectManifest.Producer("a", "desktop", "v"), ProjectManifest.Producer("a", "desktop", "v"), 1,
            ProjectManifest.PrinterTarget("Prusa MK4", printerId = "local-id", profileId = "prusa_generic", family = "prusa"))
        val back = ProjectManifest.parse(m.toJson())
        assertEquals("prusa_generic", back.printer!!.profileId); assertEquals("prusa", back.printer!!.family)
        // The manifest schema has no field for addresses, access codes, API keys or tokens.
        val text = m.toJson().toString().lowercase()
        listOf("address", "apikey", "api_key", "accesscode", "password", "token", "secret").forEach { assertFalse(it, text.contains(it)) }
    }

    @Test fun publicSiteDoesNotCallUntestedFamiliesVerified() {
        val html = File(root, "site-src/pages/printers.html").readText()
        val rows = Regex("<tr><th scope=\"row\">(.*?)</th><td>(.*?)</td>", RegexOption.DOT_MATCHES_ALL).findAll(html).associate { it.groupValues[1] to it.groupValues[2] }
        rows.filterKeys { k -> listOf("stock", "Bambu", "OctoPrint", "PrusaLink").any { k.contains(it, true) } }.forEach { (k, v) ->
            assertFalse("$k is claimed verified", v.contains("Verified on hardware"))
        }
    }

    /**
     * Slicing needs only a profile: representative printers from four makers slice with nothing connected. Uses the
     * native nozzle-engine (the Web App and Android engine source) when built; the Orca-fork CLI is only a fallback.
     */
    @Test fun representativeProfilesSliceWithNoPrinterConnected() {
        val bin = SliceEngine.locateEngine()
        assumeTrue("slicing engine binary available", bin != null)
        val p = paths()
        val cube = MeshIO.readStl(File(root, "site-src/assets/test-cube-20mm.stl").readBytes())
        println("slicing with ${bin!!.absolutePath}")
        for (id in listOf("snapmaker_u1", "prusa_generic", "bambu_generic", "generic_klipper", "prusa_xl_5t")) {
            val prof = ProfileCatalog.byId(id)!!
            val obj = ModelObject(1, "cube", cube, Transform.translate(prof.bedW / 2.0 - 10, prof.bedD / 2.0 - 10, 0.0))
            val manifest = ProjectManifest("p-$id", 1, id, ProjectManifest.Producer("t", "desktop", "t"), ProjectManifest.Producer("t", "desktop", "t"), 1,
                ProjectManifest.PrinterTarget(prof.model, profileId = id), listOf(ProjectManifest.PlateEntry(1, "Plate 1", listOf(ProjectManifest.ObjectEntry(1, "cube", 1)))))
            val out = SliceEngine(bin!!, p.slices).slice(SliceRequest(Project3mf(listOf(obj), emptyMap(), manifest), ProfileCatalog.materialize(p.cache, id),
                QualityPreset.STANDARD, false, 15, listOf(ProjectManifest.MaterialSlot(1, "PLA", colorHex = "#FFFFFF")))) { _, _ -> }
            assertTrue("$id: $out", out is SliceOutcome.Done)
            val stats = (out as SliceOutcome.Done).stats
            assertTrue("$id layers", (stats.layers ?: 0) > 50)
            assertTrue("$id grams", (stats.grams ?: 0.0) > 1.0)
            println("$id: ${stats.layers} layers, ${stats.grams} g, ${stats.seconds} s")
        }
    }
}

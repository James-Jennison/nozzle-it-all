package com.nozzleitall.printer

import com.nozzleitall.printer.external.AdapterProtocol
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The shared core is vendor-neutral: PAXX U1 is the flagship adapter, not an assumption of the core. */
class VendorNeutralCoreTest {
    private val main = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "printer-api/src/main/kotlin") }.first { it.isDirectory }

    @Test fun coreSourcesMakeNoVendorAssumptions() {
        // Vendor names may appear only in the family registry (PrinterFamily's known ids) and in namespaced extensions (ext/).
        val vendorWords = Regex("\\b(U1|Snapmaker|PAXX|Bambu|Prusa|extruder[0-9]|print_task_config|multiACE|AMS)\\b")
        val offenders = main.walkTopDown().filter { it.isFile && it.extension == "kt" && "/ext/" !in it.path && it.name != "Glossary.kt" }.flatMap { f ->
            var inFamily = false
            f.readLines().mapIndexedNotNull { i, raw ->
                val line = raw.substringBefore("//").trim()
                if (line.startsWith("value class PrinterFamily")) inFamily = true
                if (inFamily && line.startsWith("override fun toString")) inFamily = false
                if (inFamily || line.startsWith("*") || line.startsWith("/*")) null
                else if (vendorWords.containsMatchIn(line)) "${f.name}:${i + 1}: $line" else null
            }
        }.toList()
        assertTrue("Vendor assumptions in the shared core:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test fun aNewVendorNeedsNoChangeToTheCoreOrOtherAdapters() {
        val acme = PrinterFamily("acme-printers")
        val adapter = object : DeviceAdapter {
            override val id = "acme"; override val displayName = "Acme"; override val families = setOf(acme); override val mayUseVendorCloud = false
            override fun probe(address: String): DiscoveredPrinter? = null
            override fun open(config: PrinterConfig): PrinterSession = throw UnsupportedOperationException()
        }
        val registry = AdapterRegistry()
        registry.registerBuiltIn(adapter)
        assertEquals(setOf(acme), registry.adapter("acme").families)
        // Unknown families still read and write, and get a sensible label.
        assertEquals("acme-printers", acme.label)
        val id = PrinterIdentity("x", "Acme 1", "Acme 1", acme, "http://10.0.0.5", "acme_1")
        assertEquals(id, AdapterProtocol.decodeIdentity(AdapterProtocol.encode(id)))
    }

    @Test fun olderSavedFamiliesStillRead() {
        assertEquals(PrinterFamily.PAXX_U1, PrinterFamily.parse("PAXX"))
        assertEquals(PrinterFamily.STOCK_U1, PrinterFamily.parse("STOCK_U1"))
        assertEquals(PrinterFamily.KLIPPER, PrinterFamily.parse("KLIPPER"))
        assertEquals(PrinterFamily.BAMBU_LAB, PrinterFamily.parse("bambu-lab"))
    }

    @Test fun capabilitiesTravelWithTheirSchemaAndUnknownKeysAreIgnored() {
        val c = Capabilities(uploadAndStart = true, pausePrint = true, camera = false, acceptedOutputs = setOf("gcode.3mf"), vendorExtensions = setOf("bambu.ams"))
        val json = AdapterProtocol.encode(c)
        assertEquals(Capabilities.SCHEMA_VERSION, json.getInt("schema"))
        assertEquals(c, AdapterProtocol.decodeCapabilities(JSONObject(json.toString()).put("future_capability", true)))
    }

    @Test fun vendorExtensionsCrossTheProtocolUnchanged() {
        val s = PrinterStatus(PrinterState.READY, ConnectionRoute.LAN, extensions = mapOf("acme.widget" to mapOf("level" to 3, "names" to listOf("a", "b"))))
        assertEquals(s.extensions, AdapterProtocol.decodeStatus(JSONObject(AdapterProtocol.encode(s).toString())).extensions)
    }

    @Test fun exportOnlyTargetsOfferNothingLive() {
        val e = Capabilities.EXPORT_ONLY
        assertFalse(e.uploadJob || e.uploadAndStart || e.startPrint || e.anyControl || e.camera || e.vendorCloud)
    }

    @Test fun rawStatesFromEveryProtocolMapOntoTheSharedVocabulary() {
        mapOf("standby" to PrinterState.READY, "operational" to PrinterState.READY, "printing" to PrinterState.PRINTING, "paused" to PrinterState.PAUSED,
            "complete" to PrinterState.FINISHED, "cancelled" to PrinterState.CANCELLED, "shutdown" to PrinterState.ERROR, "attention" to PrinterState.ERROR,
            "startup" to PrinterState.STARTING, "busy" to PrinterState.UNKNOWN).forEach { (raw, expected) -> assertEquals(raw, expected, PrinterState.fromRaw(raw)) }
        assertEquals(PrinterState.OFFLINE, PrinterState.fromRaw("printing", connected = false))
    }
}

package net.jamesjennison.klippercompanion

import org.json.JSONObject

/** A printer found by scanning the local network. [address] is in the form the Add printer wizard accepts. */
data class DiscoveredPrinter(
    val address: String,
    val kind: PrinterKind,
    val name: String,
    val slicingModel: SlicingPrinterModel?,
    val serial: String = "",
    val detail: String = "",
)

/** Pure parsing and classification for network discovery; the probing itself lives in :transport. */
object PrinterDiscovery {
    // Verified against a real Snapmaker U1 ("1.6.0.267_20260815150420", no app field) and a real Centauri Carbon
    // running COSMOS (app "OpenCentauri Cosmos"); see Moonraker.firmwareIdentity.
    private val U1_VERSION = Regex("""^\d+\.\d+\.\d+\.\d+_\d{10,14}$""")

    /** [hasAfc]: Klipper reports an `AFC` object (Armored Turtle's AFC, which drives the Elegoo CANVAS on COSMOS). */
    /** [paxx]: the printer's config files include PAXX's `extended/` folder (see [hasExtendedConfig]). */
    fun classifyMoonraker(hostname: String, app: String, softwareVersion: String, address: String, hasAfc: Boolean = false, paxx: Boolean = false): DiscoveredPrinter {
        val name = hostname.trim().take(80).ifBlank { address }
        return when {
            isCosmos(app) && hasAfc ->
                DiscoveredPrinter(address, PrinterKind.GENERIC_KLIPPER, name, SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_COSMOS_CANVAS, detail = "Elegoo Centauri Carbon with CANVAS ($softwareVersion)")
            isCosmos(app) ->
                DiscoveredPrinter(address, PrinterKind.GENERIC_KLIPPER, name, SlicingPrinterModel.ELEGOO_CENTAURI_CARBON, detail = "Elegoo Centauri Carbon ($softwareVersion)")
            isU1Version(softwareVersion) && paxx ->
                DiscoveredPrinter(address, PrinterKind.SNAPMAKER_U1_PAXX, name, SlicingPrinterModel.SNAPMAKER_U1, detail = "Snapmaker U1 with PAXX extended firmware ($softwareVersion)")
            isU1Version(softwareVersion) ->
                // No PAXX `extended/` config folder (or it could not be read): stock firmware, the common case.
                DiscoveredPrinter(address, PrinterKind.SNAPMAKER_U1, name, SlicingPrinterModel.SNAPMAKER_U1, detail = "Snapmaker U1 ($softwareVersion)")
            else -> DiscoveredPrinter(address, PrinterKind.GENERIC_KLIPPER, name, SlicingPrinterModel.GENERIC_KLIPPER, detail = "Klipper / Moonraker" + softwareVersion.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty())
        }
    }

    /**
     * The slicing profile a printer type implies on its own, when there is exactly one: both U1 firmwares use the
     * Snapmaker U1 pack. Null for every other type (Generic Klipper covers many printers; a COSMOS Centauri Carbon is
     * chosen by its profile and confirmed by a firmware read).
     */
    fun defaultSlicingModel(kind: PrinterKind): SlicingPrinterModel? = when (kind) {
        PrinterKind.SNAPMAKER_U1, PrinterKind.SNAPMAKER_U1_PAXX -> SlicingPrinterModel.SNAPMAKER_U1
        else -> null
    }

    fun isU1Version(softwareVersion: String): Boolean = U1_VERSION.matches(softwareVersion.trim())

    /**
     * `server/files/list?root=config`'s result: does it hold the `extended/` folder PAXX's extended firmware installs
     * (extended/extended2.cfg, extended/moonraker/...)? Stock U1 firmware has no such folder. Verified against the
     * owner's PAXX U1.
     */
    fun hasExtendedConfig(configFiles: org.json.JSONArray?): Boolean {
        val files = configFiles ?: return false
        return (0 until minOf(files.length(), 10_000)).any { files.optJSONObject(it)?.optString("path").orEmpty().startsWith("extended/") }
    }

    fun isCosmos(app: String): Boolean = app.contains("cosmos", ignoreCase = true) || app.contains("opencentauri", ignoreCase = true)

    /** `printer/objects/list`'s result: does Klipper have an `AFC` object? */
    fun hasAfcObject(objectsList: JSONObject?): Boolean {
        val objects = objectsList?.optJSONArray("objects") ?: return false
        return (0 until minOf(objects.length(), 10_000)).any { objects.optString(it) == "AFC" }
    }

    /** An Elegoo printer that answered Elegoo's own LAN discovery (UDP 3000 "M99999" or UDP 52700 method 7000). */
    fun elegoo(address: String, name: String, model: String, centauriCarbon2: Boolean, detail: String): DiscoveredPrinter =
        DiscoveredPrinter(address, PrinterKind.ELEGOO, name.trim().take(80).ifBlank { model.ifBlank { "Elegoo printer" } }.take(80),
            if (centauriCarbon2) SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_2_CANVAS else SlicingPrinterModel.ELEGOO_CENTAURI_CARBON_CANVAS, detail = detail.take(160))

    /** PrusaLink's GET /api/version, or null when the reply is not from PrusaLink. */
    fun parsePrusaLinkVersion(body: String, address: String): DiscoveredPrinter? {
        val json = try { JSONObject(body) } catch (_: Exception) { return null }
        val text = json.optString("text").ifBlank { json.optString("original") }
        if (!text.contains("PrusaLink", ignoreCase = true) && !json.optString("server").contains("PrusaLink", ignoreCase = true)) return null
        return DiscoveredPrinter(address, PrinterKind.PRUSA_LINK, "Prusa printer", SlicingPrinterModel.PRUSA_GENERIC, detail = text.ifBlank { "PrusaLink" }.take(80))
    }

    /** OctoPrint's web page (GET /): the API needs a key, but the login/UI page names itself. */
    fun parseOctoPrintPage(body: String, address: String): DiscoveredPrinter? {
        if (!body.contains("OctoPrint", ignoreCase = true)) return null
        val title = Regex("<title>([^<]{1,80})</title>", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.trim().orEmpty()
        return DiscoveredPrinter(address, PrinterKind.OCTOPRINT, title.ifBlank { "OctoPrint" }.take(80), SlicingPrinterModel.GENERIC_KLIPPER, detail = "OctoPrint - needs an API key")
    }

    /**
     * A Creality K1 / K2 / Hi answering `GET /info` on port 80 with its `model` and `mac` (CrealityCfs.parseInfo; Orca
     * CrealityHostDiscovery.cpp probe_info). Null when the body isn't one. The model code suggests a slicing pack.
     */
    fun parseCrealityInfo(body: String, address: String): DiscoveredPrinter? {
        val info = CrealityCfs.parseInfo(body) ?: return null
        val known = CrealityCfs.CFS_MODELS[info.model]
        val name = info.hostname.ifBlank { "Creality " + (known ?: info.model) }.take(80)
        val cfsC = if (CrealityCfs.isK1Family(info.model) && info.model != "K1_CFS-C") " - choose the CFS-C profile if one is attached" else ""
        return DiscoveredPrinter(address, PrinterKind.CREALITY, name, CrealityCfs.slicingModelFor(info.model),
            detail = "Creality ${known ?: "printer (model ${info.model})"}$cfsC".take(160))
    }

    /** A Flashforge printer's reply to its UDP discovery probe (FlashforgeIfs.parseDiscovery), from [address]. */
    fun flashforge(address: String, found: FlashforgeIfs.Discovered): DiscoveredPrinter =
        DiscoveredPrinter(address, PrinterKind.FLASHFORGE, found.name.ifBlank { "Flashforge printer" }.take(80),
            if (found.name.contains("AD5X", ignoreCase = true)) SlicingPrinterModel.FLASHFORGE_AD5X else null, found.serial,
            "Flashforge - needs its access code")

    /** One Bambu SSDP NOTIFY / search reply (headers `Location`, `USN` = serial, `DevName.bambu.com`, `DevModel.bambu.com`). */
    fun parseBambuSsdp(message: String): DiscoveredPrinter? {
        val headers = message.lineSequence().mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() } }.toMap()
        val location = headers["location"]?.removePrefix("http://")?.substringBefore('/')?.substringBefore(':')?.takeIf { it.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) } ?: return null
        val serial = headers["usn"]?.takeIf { it.matches(Regex("""[A-Za-z0-9]{8,24}""")) } ?: return null
        if (headers["nt"]?.contains("bambulab", ignoreCase = true) != true && headers["st"]?.contains("bambulab", ignoreCase = true) != true && headers["devmodel.bambu.com"] == null) return null
        val model = headers["devmodel.bambu.com"].orEmpty()
        return DiscoveredPrinter(location, PrinterKind.BAMBU_LAB, headers["devname.bambu.com"]?.take(80)?.ifBlank { null } ?: "Bambu Lab $serial".take(80), SlicingPrinterModel.BAMBU_GENERIC, serial, "Bambu Lab" + model.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty() + " - needs its access code")
    }
}

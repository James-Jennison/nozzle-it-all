package net.jamesjennison.klippercompanion

import android.content.Context
import java.io.File

// WO-13 Phase 4: which bundled OrcaSlicer profile (machine/process/filament, each already
// flattened from its real upstream inherits chain - see assets/slicer_profiles/PROVENANCE.md)
// applies to a given printer. Kept separate from SlicingPrinterModel itself so the mapping from
// "what kind of printer" to "which asset files" is one obvious place, not scattered.
// filamentSlots: the pack's filament slot count when machine.json can't say it (the CANVAS packs: one nozzle fed by four
// lanes - ElegooProfiles.filamentSlots; Bambu printers with an AMS - BambuAms.filamentSlots; Prusa MMU3 - PrusaMmu;
// CFS, ACE, IFS - FilamentChangers);
// null means machine.json's own count (ToolSlots.kt's parseToolCount).
internal data class SlicingProfilePack(val assetDir: String, val cosmosGeneration: CosmosProfileGeneration? = null, val custom: CustomMachine? = null, val filamentSlots: Int? = null) {
    val machinePath get() = "$assetDir/machine.json"
    /** The machine profile as it will be used: the bundled file, with this printer's custom bed/G-code applied to a copy when set. */
    fun machineText(context: Context): String = machineTextFrom(context.assets.open(machinePath).use { it.reader().readText() })
    /** [raw] (the bundled machine.json) as it will be used: with this printer's custom machine applied when set. */
    fun machineTextFrom(raw: String): String = custom?.let { applyCustomMachine(raw, it) } ?: raw
    val processPath get() = "$assetDir/process.json"
    val filamentPath get() = "$assetDir/filament.json"
}

// Only CosmosProfileGeneration.CURRENT is bundled right now (see PROVENANCE.md's "Real, flagged
// gap") - a printer whose live firmware resolves to LEGACY has no pack to select at all, which
// callers must treat as unslicable for that printer, not silently fall back to CURRENT.
// custom applies to every model except the Elegoo Centauri Carbon packs tied to one firmware (COSMOS, with or without
// CANVAS, and Elegoo's own firmware - ElegooProfiles.firmwareFor): their start/end G-code is safety-critical and never overridden.
internal fun slicingProfilePack(model: SlicingPrinterModel, cosmosGeneration: CosmosProfileGeneration?, custom: CustomMachine? = null): SlicingProfilePack? {
    val dir = "slicer_profiles/${SlicingModelCatalog.info(model).assetDir}"
    return when (ElegooProfiles.firmwareFor(model)) {
        ElegooProfileFirmware.COSMOS ->
            if (cosmosGeneration == CosmosProfileGeneration.CURRENT) SlicingProfilePack(dir, CosmosProfileGeneration.CURRENT, filamentSlots = ElegooProfiles.filamentSlots(model))
            else null
        ElegooProfileFirmware.ELEGOO_STOCK -> SlicingProfilePack(dir, filamentSlots = ElegooProfiles.filamentSlots(model))
        // Every other model is one row of the generated catalog (scripts/bundle_vendor_profiles.py).
        // Bambu printers that take an AMS get its slots (BambuAms), Prusa printers with an MMU3 its five (PrusaMmu), Creality
        // CFS, Anycubic ACE and Flashforge IFS printers four (FilamentChangers).
        // A custom machine's declared filament changer lanes (Klipper AFC, Happy Hare, ...) come first.
        null -> SlicingProfilePack(dir, custom = custom, filamentSlots = custom?.filamentSlots ?: BambuAms.filamentSlots(model) ?: PrusaMmu.filamentSlots(model) ?: FilamentChangers.filamentSlots(model))
    }
}

// Copies a pack's three asset files into real filesystem files under cacheDir - the native
// engine's ConfigBase::load() needs real paths, not an AssetManager stream. Re-copied on every
// call rather than cached: these are tiny (a few KB each) and copying is cheap next to the slice
// itself, so there's no reason to risk a stale on-disk copy surviving an app update.
// WO-15 part E follow-up: the real bed size/shape for a printer, straight from the same
// machine.json every slice already applies - not a second, invented bed definition. Returns
// null exactly when slicingProfilePack() itself would (no bundled pack for this
// model/firmware-generation combination yet, e.g. a Centauri Carbon on LEGACY firmware).
internal fun bedShapeFor(model: SlicingPrinterModel, cosmosGeneration: CosmosProfileGeneration?, context: Context, custom: CustomMachine? = null): BedShape? =
    slicingProfilePack(model, cosmosGeneration, custom)?.readBedShape(context)

// The print profiles (process presets) this pack offers - its processes.json (scripts/bundle_process_presets.py), or
// just its own process.json for a pack without one.
internal fun SlicingProfilePack.processPresets(context: Context): ProcessPresets {
    runCatching { context.assets.open("$assetDir/processes.json").use { it.reader().readText() } }.getOrNull()?.let { return ProcessPresets.parse(it) }
    val own = org.json.JSONObject(context.assets.open(processPath).use { it.reader().readText() })
    fun first(key: String) = own.optJSONArray(key)?.optString(0) ?: own.optString(key)
    return ProcessPresets.single(own.optString("name").ifBlank { "Default" }, processPath, first("layer_height").toDoubleOrNull(), ProcessPresets.percent(first("sparse_infill_density")))
}

// processAsset: the print profile's asset file (ProcessPreset.file), the pack's own process.json when null.
internal fun SlicingProfilePack.materialize(context: Context, processAsset: String? = null): List<String> {
    val dir = File(context.cacheDir, "slicer-profiles-active").apply { mkdirs() }
    return listOf(machinePath to "machine.json", (processAsset ?: processPath) to "process.json", filamentPath to "filament.json").map { (assetPath, name) ->
        val out = File(dir, name)
        if (assetPath == machinePath && custom != null) out.writeText(machineText(context))
        else context.assets.open(assetPath).use { input -> out.outputStream().use { input.copyTo(it) } }
        out.absolutePath
    }
}

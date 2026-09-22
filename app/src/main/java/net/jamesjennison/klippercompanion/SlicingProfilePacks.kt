package net.jamesjennison.klippercompanion

import android.content.Context
import java.io.File

// WO-13 Phase 4: which bundled OrcaSlicer profile (machine/process/filament, each already
// flattened from its real upstream inherits chain - see assets/slicer_profiles/PROVENANCE.md)
// applies to a given printer. Kept separate from SlicingPrinterModel itself so the mapping from
// "what kind of printer" to "which asset files" is one obvious place, not scattered.
internal data class SlicingProfilePack(val assetDir: String, val cosmosGeneration: CosmosProfileGeneration? = null) {
    val machinePath get() = "$assetDir/machine.json"
    val processPath get() = "$assetDir/process.json"
    val filamentPath get() = "$assetDir/filament.json"
}

// Only CosmosProfileGeneration.CURRENT is bundled right now (see PROVENANCE.md's "Real, flagged
// gap") - a printer whose live firmware resolves to LEGACY has no pack to select at all, which
// callers must treat as unslicable for that printer, not silently fall back to CURRENT.
internal fun slicingProfilePack(model: SlicingPrinterModel, cosmosGeneration: CosmosProfileGeneration?): SlicingProfilePack? = when (model) {
    SlicingPrinterModel.SNAPMAKER_U1 -> SlicingProfilePack("slicer_profiles/snapmaker_u1")
    SlicingPrinterModel.BAMBU_GENERIC -> SlicingProfilePack("slicer_profiles/bambu_generic")
    SlicingPrinterModel.PRUSA_GENERIC -> SlicingProfilePack("slicer_profiles/prusa_generic")
    SlicingPrinterModel.GENERIC_KLIPPER -> SlicingProfilePack("slicer_profiles/generic_klipper")
    SlicingPrinterModel.ELEGOO_CENTAURI_CARBON ->
        if (cosmosGeneration == CosmosProfileGeneration.CURRENT) SlicingProfilePack("slicer_profiles/elegoo_centauri_carbon_cosmos", CosmosProfileGeneration.CURRENT)
        else null
}

// Copies a pack's three asset files into real filesystem files under cacheDir - the native
// engine's ConfigBase::load() needs real paths, not an AssetManager stream. Re-copied on every
// call rather than cached: these are tiny (a few KB each) and copying is cheap next to the slice
// itself, so there's no reason to risk a stale on-disk copy surviving an app update.
// WO-15 part E follow-up: the real bed size/shape for a printer, straight from the same
// machine.json every slice already applies - not a second, invented bed definition. Returns
// null exactly when slicingProfilePack() itself would (no bundled pack for this
// model/firmware-generation combination yet, e.g. a Centauri Carbon on LEGACY firmware).
internal fun bedShapeFor(model: SlicingPrinterModel, cosmosGeneration: CosmosProfileGeneration?, context: Context): BedShape? =
    slicingProfilePack(model, cosmosGeneration)?.readBedShape(context)

internal fun SlicingProfilePack.materialize(context: Context): List<String> {
    val dir = File(context.cacheDir, "slicer-profiles-active").apply { mkdirs() }
    return listOf(machinePath, processPath, filamentPath).map { assetPath ->
        val out = File(dir, assetPath.substringAfterLast('/'))
        context.assets.open(assetPath).use { input -> out.outputStream().use { input.copyTo(it) } }
        out.absolutePath
    }
}

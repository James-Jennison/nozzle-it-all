package net.jamesjennison.klippercompanion

import android.content.Context
import org.orcaslicer.engine.NativeEngine
import java.io.File

// WO-13 Phase 2/3: turns a shared model file + a target printer profile into either a real
// sliced .gcode File (ready to hand to the existing Bambu/generic-Klipper print-start paths) or
// a clear reason it can't proceed. Never itself uploads or starts a print - that stays with
// whatever already-established, confirm-gated path the caller uses afterward.
sealed class SliceOutcome {
    data class Success(val gcode: File) : SliceOutcome()
    // A live firmware check blocked slicing outright - see FirmwareIdentity.kt. Distinct from
    // Failed so a caller can show this with the same weight as the rest of that safety model,
    // not as an ordinary "slicing failed" error.
    data class FirmwareBlocked(val reason: String) : SliceOutcome()
    data class Failed(val message: String) : SliceOutcome()
}

object SlicingCoordinator {
    // Builds its own short-lived PrinterService for the live firmware check, the same ad hoc
    // pattern NozzlePrinterWidget and PrinterModel.detectFirmware already use, rather than
    // requiring the caller's own active connection - slicing should work from the share-intent
    // entry point even before the target printer's dashboard has connected.
    suspend fun slice(context: Context, modelFile: File, profile: PrinterProfile): SliceOutcome {
        val model = profile.slicingModel ?: return SliceOutcome.Failed("This printer has no slicing profile selected. Choose one from Edit printer first.")
        val cosmosGeneration = if (model == SlicingPrinterModel.ELEGOO_CENTAURI_CARBON) {
            // checkCentauriCarbonFirmwareMatch treats a null generation as "this call site isn't
            // about a Centauri Carbon profile at all" and returns Match unconditionally (see its
            // own test coverage in FirmwareIdentityTest) - correct for a generic caller, but wrong
            // here: this branch is *always* about a Centauri Carbon profile, so a null
            // declaredCosmosProfileGeneration specifically means "never confirmed," which must
            // block, not silently pass through as "nothing to check." Caught and fixed before
            // this ever ran against the real printer tonight - see SlicingCoordinatorDeviceTest's
            // centauriCarbonProfileWithNoDeclaredFirmwareIsBlockedEvenAgainstTheRealPrinter.
            val declared = profile.declaredCosmosProfileGeneration
                ?: return SliceOutcome.FirmwareBlocked("This printer's firmware has never been confirmed. Use \"Detect firmware now\" in Edit printer before slicing for it.")
            val service = printerServiceFor(profile, profile.address)
            val live = try { service.firmwareIdentity() } catch (e: Exception) { null } finally { runCatching { service.close() } }
            when (val match = checkCentauriCarbonFirmwareMatch(live, declared)) {
                is FirmwareMatchResult.Mismatch -> return SliceOutcome.FirmwareBlocked(match.reason)
                is FirmwareMatchResult.Unknown -> return SliceOutcome.FirmwareBlocked(match.reason)
                FirmwareMatchResult.Match -> {}
            }
            // Recomputed from the live reading just confirmed matching, not the persisted
            // declaration - the two agree here by construction (Match above), but the live value
            // is the one actually trusted for which profile pack gets selected.
            live?.let { cosmosRequiresCurrentProfile(it.version) }?.let { if (it) CosmosProfileGeneration.CURRENT else CosmosProfileGeneration.LEGACY }
        } else null
        val pack = slicingProfilePack(model, cosmosGeneration)
            ?: return SliceOutcome.Failed("No bundled slicer profile exists yet for this printer's confirmed firmware.")
        return try {
            val profilePaths = pack.materialize(context)
            val outputDir = File(context.cacheDir, "sliced-output").apply { mkdirs() }
            outputDir.listFiles()?.forEach { it.delete() }
            val output = File(outputDir, modelFile.nameWithoutExtension.take(80) + ".gcode")
            NativeEngine.nativeSliceFile(modelFile.absolutePath, output.absolutePath, profilePaths.toTypedArray(), emptyArray(), emptyArray())
            SliceOutcome.Success(output)
        } catch (e: Exception) { SliceOutcome.Failed(e.message ?: "Slicing failed.") }
    }
}

// The shared document's own name, accepted only if it looks like a real 3D model file this
// engine can load (STL/3MF/OBJ - Model::read_from_file's own supported formats, see
// slic3r_engine.cpp). Mirrors bambuPrintName's shape (BambuPrintPanel.kt) deliberately, since
// both gate a share-intent panel on "is this the right kind of file."
internal fun sliceableModelName(raw: String?): String {
    val name = raw?.trim().orEmpty()
    if (name.length !in 1..200 || '/' in name || '\\' in name || name.any { it.code < 32 }) return ""
    // .gcode.3mf is Bambu's already-sliced bundle format (bambuPrintName's own turf, not this
    // one's) - excluded explicitly even though it shares the plain .3mf suffix, so a shared
    // pre-sliced Bambu file still routes to BambuPrintPanel, not here.
    if (name.endsWith(".gcode.3mf", ignoreCase = true)) return ""
    return if (listOf(".stl", ".3mf", ".obj").any { name.endsWith(it, ignoreCase = true) }) name else ""
}

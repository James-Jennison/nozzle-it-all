package net.jamesjennison.klippercompanion

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    // Real bug hit live tonight, from the actual UI (SliceAndPrintPanel's LaunchedEffect calls
    // slice() directly, on whatever dispatcher its caller is running on - Compose's Main
    // dispatcher by default): every network call inside this function used to run un-dispatched,
    // throwing NetworkOnMainThreadException the moment a live firmware read was attempted.
    // SlicingCoordinatorDeviceTest never caught this because it drives slice() via runBlocking
    // in an instrumented test process, not Android's actual main looper - a real gap in what
    // that test coverage was actually proving. The whole body now runs on Dispatchers.IO.
    suspend fun slice(context: Context, modelFile: File, profile: PrinterProfile): SliceOutcome = withContext(Dispatchers.IO) {
        val model = profile.slicingModel ?: return@withContext SliceOutcome.Failed("This printer has no slicing profile selected. Choose one from Edit printer first.")
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
                ?: return@withContext SliceOutcome.FirmwareBlocked("This printer's firmware has never been confirmed. Use \"Detect firmware now\" in Edit printer before slicing for it.")
            val service = printerServiceFor(profile, profile.address)
            // The real exception is captured and surfaced below, not swallowed into a generic
            // message - a live read can fail for very different, actionable reasons (printer
            // offline, wrong address, a transient network blip, an auth failure), and "Could not
            // read the printer's current firmware" alone gives neither the owner nor a future
            // debugging session anything to act on. Real incident: this blocked a real slice
            // against a printer that curl confirmed was fully reachable a minute later - almost
            // certainly a transient blip, but the vague message made that impossible to tell from
            // a real problem (wrong port, wrong address, auth) without re-deriving it by hand.
            var readFailure: String? = null
            val live = try { service.firmwareIdentity() } catch (e: Exception) { readFailure = e.message ?: e.javaClass.simpleName; null } finally { runCatching { service.close() } }
            if (live == null) return@withContext SliceOutcome.FirmwareBlocked("Could not read this printer's current firmware before slicing for a Centauri Carbon/COSMOS profile: ${readFailure ?: "no response"}. Check the connection and try again.")
            when (val match = checkCentauriCarbonFirmwareMatch(live, declared)) {
                is FirmwareMatchResult.Mismatch -> return@withContext SliceOutcome.FirmwareBlocked(match.reason)
                is FirmwareMatchResult.Unknown -> return@withContext SliceOutcome.FirmwareBlocked(match.reason)
                FirmwareMatchResult.Match -> {}
            }
            // Recomputed from the live reading just confirmed matching, not the persisted
            // declaration - the two agree here by construction (Match above), but the live value
            // is the one actually trusted for which profile pack gets selected.
            live?.let { cosmosRequiresCurrentProfile(it.version) }?.let { if (it) CosmosProfileGeneration.CURRENT else CosmosProfileGeneration.LEGACY }
        } else null
        val pack = slicingProfilePack(model, cosmosGeneration)
            ?: return@withContext SliceOutcome.Failed("No bundled slicer profile exists yet for this printer's confirmed firmware.")
        return@withContext try {
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

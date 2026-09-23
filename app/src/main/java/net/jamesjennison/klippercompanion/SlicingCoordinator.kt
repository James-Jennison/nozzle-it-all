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
    // overrides: real OrcaSlicer config keys (layer_height, sparse_infill_density "NN%",
    // enable_support "0"/"1", ...) applied on top of the resolved profile pack - see
    // SliceCustomization.kt for the specific, bounded set this app's own UI actually exposes.
    // Not a general escape hatch for arbitrary config keys from outside this codebase; the JNI
    // bridge itself will happily accept any key libslic3r recognizes, but nothing here validates
    // one from an untrusted source.
    // paintSessionHandle: WO-14 part D - when non-null (the owner actually painted at least one
    // support stroke via ModelViewer's Paint mode, see PaintUiState), slices through
    // NativeEngine.nativeSlicePaintSession instead of nativeSliceFile, reusing that session's
    // own already-loaded, already-painted in-memory model - not a second load from modelFile,
    // and not a serialization round-trip for the painted state. Every safety check above
    // (firmware confirmation, profile pack resolution) is unchanged either way; only the very
    // last native call differs.
    // transform: WO-15 part E - the real object placement (move/rotate/scale) the owner set via
    // ModelViewer's Transform mode (ModelTransform.kt). Ignored when paintSessionHandle is
    // non-null - a paint session already froze its own transform at open time (see
    // engine::open_paint_session), so nativeSlicePaintSession takes none here; passing one
    // separately at slice time for a painted model would silently disagree with what was
    // actually painted.
    // Phase 6 (Consumer Slicer Plan §16): for a Bambu Lab target, produces a real .gcode.3mf
    // bundle (engine::slice_bambu_bundle/nativeSliceBambuBundle) instead of plain .gcode - the
    // shape BambuPrinterService.startPrint()/BambuPrintRequest already require, matching what
    // BambuPrintPanel's own share-intent path already uploads+prints. Not available with a paint
    // session (nativeSlicePaintSession only ever produces plain .gcode - painting a Bambu target
    // isn't wired up yet, same real gap noted on SlicingCoordinator's own paintSessionHandle
    // doc); callers pass null for a Bambu target in that case, which this doesn't itself enforce
    // (ModelViewer/SliceAndPrintPanel already keep Paint mode and target-printer independent, so
    // this is defense-in-depth, not the only guard).
    suspend fun slice(context: Context, modelFile: File, profile: PrinterProfile, overrides: Map<String, String> = emptyMap(), paintSessionHandle: Long? = null, transform: ModelTransform = ModelTransform()): SliceOutcome = withContext(Dispatchers.IO) {
        when (val resolved = resolveProfilePaths(context, profile)) {
            is ProfileResolution.Blocked -> return@withContext resolved.outcome
            is ProfileResolution.Ready -> return@withContext try {
                val bambuTarget = profile.kind == PrinterKind.BAMBU_LAB
                val output = freshOutputFile(context, modelFile.nameWithoutExtension.take(80), bambuBundle = bambuTarget)
                when {
                    paintSessionHandle != null -> NativeEngine.nativeSlicePaintSession(paintSessionHandle, output.absolutePath, resolved.profilePaths.toTypedArray(), overrides.keys.toTypedArray(), overrides.values.toTypedArray())
                    bambuTarget -> NativeEngine.nativeSliceBambuBundle(
                        modelFile.absolutePath, output.absolutePath, resolved.profilePaths.toTypedArray(), overrides.keys.toTypedArray(), overrides.values.toTypedArray(),
                        transform.offsetXMm.toDouble(), transform.offsetYMm.toDouble(), transform.rotationZDeg.toDouble(), transform.scale.toDouble(),
                    )
                    else -> NativeEngine.nativeSliceFile(
                        modelFile.absolutePath, output.absolutePath, resolved.profilePaths.toTypedArray(), overrides.keys.toTypedArray(), overrides.values.toTypedArray(),
                        transform.offsetXMm.toDouble(), transform.offsetYMm.toDouble(), transform.rotationZDeg.toDouble(), transform.scale.toDouble(),
                    )
                }
                SliceOutcome.Success(output)
            } catch (e: Exception) { SliceOutcome.Failed(e.message ?: "Slicing failed.") }
        }
    }

    // Phase 1 (Consumer Slicer Plan §16): the real multi-object counterpart to slice() above -
    // same firmware confirmation/profile-pack resolution (resolveProfilePaths, shared, not a
    // second copy), but calls engine::slice_multi_object via nativeSliceMultiObject instead of
    // slicing one file. No paint-session support here - a paint session is tied to one already-
    // loaded single-object model (engine::open_paint_session), and painting a specific object
    // within a multi-object project isn't built yet (see docs/WORK_ORDER.md's WO-17 "still open"
    // list). Does not itself check for overlapping objects - same real, deliberate gap
    // slice_multi_object's own native-side comment documents; collision detection is a separate
    // UI concern, still open.
    // Phase 6 follow-up (WO-23): for a Bambu Lab target, produces a real multi-object .gcode.3mf
    // bundle (engine::slice_multi_object_bambu_bundle/nativeSliceMultiObjectBambuBundle) instead
    // of plain .gcode - the same single-object branch slice() above already has, extended to the
    // multi-object case (ProjectEditorScreen.kt).
    suspend fun sliceProject(context: Context, objects: List<Pair<File, ModelTransform>>, profile: PrinterProfile, overrides: Map<String, String> = emptyMap()): SliceOutcome = withContext(Dispatchers.IO) {
        if (objects.isEmpty()) return@withContext SliceOutcome.Failed("Add at least one object to this project before slicing.")
        when (val resolved = resolveProfilePaths(context, profile)) {
            is ProfileResolution.Blocked -> return@withContext resolved.outcome
            is ProfileResolution.Ready -> return@withContext try {
                val bambuTarget = profile.kind == PrinterKind.BAMBU_LAB
                val output = freshOutputFile(context, "project", bambuBundle = bambuTarget)
                if (bambuTarget) {
                    NativeEngine.nativeSliceMultiObjectBambuBundle(
                        objects.map { it.first.absolutePath }.toTypedArray(),
                        objects.map { it.second.offsetXMm.toDouble() }.toDoubleArray(),
                        objects.map { it.second.offsetYMm.toDouble() }.toDoubleArray(),
                        objects.map { it.second.rotationZDeg.toDouble() }.toDoubleArray(),
                        objects.map { it.second.scale.toDouble() }.toDoubleArray(),
                        output.absolutePath, resolved.profilePaths.toTypedArray(), overrides.keys.toTypedArray(), overrides.values.toTypedArray(),
                    )
                } else {
                    NativeEngine.nativeSliceMultiObject(
                        objects.map { it.first.absolutePath }.toTypedArray(),
                        objects.map { it.second.offsetXMm.toDouble() }.toDoubleArray(),
                        objects.map { it.second.offsetYMm.toDouble() }.toDoubleArray(),
                        objects.map { it.second.rotationZDeg.toDouble() }.toDoubleArray(),
                        objects.map { it.second.scale.toDouble() }.toDoubleArray(),
                        output.absolutePath, resolved.profilePaths.toTypedArray(), overrides.keys.toTypedArray(), overrides.values.toTypedArray(),
                    )
                }
                SliceOutcome.Success(output)
            } catch (e: Exception) { SliceOutcome.Failed(e.message ?: "Slicing failed.") }
        }
    }

    private fun freshOutputFile(context: Context, baseName: String, bambuBundle: Boolean = false): File {
        val outputDir = File(context.cacheDir, "sliced-output").apply { mkdirs() }
        outputDir.listFiles()?.forEach { it.delete() }
        return File(outputDir, if (bambuBundle) "$baseName.gcode.3mf" else "$baseName.gcode")
    }

    private sealed class ProfileResolution {
        data class Ready(val profilePaths: List<String>) : ProfileResolution()
        data class Blocked(val outcome: SliceOutcome) : ProfileResolution()
    }

    // The firmware-confirmation + profile-pack resolution both slice() and sliceProject() need -
    // factored out so the real Centauri Carbon live-firmware-match logic (and its own hard-won
    // real bug fixes, see the git history on the branch this shipped on) exists in exactly one
    // place, not two that could quietly drift apart.
    private suspend fun resolveProfilePaths(context: Context, profile: PrinterProfile): ProfileResolution {
        val model = profile.slicingModel ?: return ProfileResolution.Blocked(SliceOutcome.Failed("This printer has no slicing profile selected. Choose one from Edit printer first."))
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
                ?: return ProfileResolution.Blocked(SliceOutcome.FirmwareBlocked("This printer's firmware has never been confirmed. Use \"Detect firmware now\" in Edit printer before slicing for it."))
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
            if (live == null) return ProfileResolution.Blocked(SliceOutcome.FirmwareBlocked("Could not read this printer's current firmware before slicing for a Centauri Carbon/COSMOS profile: ${readFailure ?: "no response"}. Check the connection and try again."))
            when (val match = checkCentauriCarbonFirmwareMatch(live, declared)) {
                is FirmwareMatchResult.Mismatch -> return ProfileResolution.Blocked(SliceOutcome.FirmwareBlocked(match.reason))
                is FirmwareMatchResult.Unknown -> return ProfileResolution.Blocked(SliceOutcome.FirmwareBlocked(match.reason))
                FirmwareMatchResult.Match -> {}
            }
            // Recomputed from the live reading just confirmed matching, not the persisted
            // declaration - the two agree here by construction (Match above), but the live value
            // is the one actually trusted for which profile pack gets selected.
            live?.let { cosmosRequiresCurrentProfile(it.version) }?.let { if (it) CosmosProfileGeneration.CURRENT else CosmosProfileGeneration.LEGACY }
        } else null
        val pack = slicingProfilePack(model, cosmosGeneration)
            ?: return ProfileResolution.Blocked(SliceOutcome.Failed("No bundled slicer profile exists yet for this printer's confirmed firmware."))
        return ProfileResolution.Ready(pack.materialize(context))
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

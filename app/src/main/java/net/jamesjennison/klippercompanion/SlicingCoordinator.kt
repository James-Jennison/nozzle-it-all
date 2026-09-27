package net.jamesjennison.klippercompanion

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
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
    data object Cancelled : SliceOutcome()
}

/** Per-object paint strokes and volumes in ObjectExtras.kt's text formats ("" = none). */
data class ObjectExtrasText(val paint: String = "", val volumes: String = "")

object SlicingCoordinator {
    // The native engine runs one slice at a time (single cancel registration), so slices are serialized.
    private val sliceLock = kotlinx.coroutines.sync.Mutex()
    fun cancel() = NativeEngine.nativeCancelSlice()

    // Memory pressure. A slice killed by Android's low-memory killer cannot be caught in-process, so a marker file is
    // written for its duration; finding it at the next start means the last slice was interrupted.
    private fun marker(context: Context) = File(context.filesDir, "slice-in-progress")
    private fun markStarted(context: Context) { runCatching { marker(context).writeText(System.currentTimeMillis().toString()) } }
    private fun markFinished(context: Context) { runCatching { marker(context).delete() } }
    /** True once if the previous slice never finished (process killed, most likely for memory); clears the marker. */
    fun consumeInterruptedSlice(context: Context): Boolean {
        val f = marker(context)
        if (!f.exists()) return false
        f.delete()
        runCatching { File(context.cacheDir, "sliced-output").listFiles()?.forEach { if (it.name.endsWith(".tmp") || it.name.endsWith(".gcode.tmp")) it.delete() } }
        return true
    }
    /** Replaceable in tests. The system's own low-memory flag: starting a multi-hundred-MB slice now would likely be killed. */
    @Volatile var systemLowMemory: (Context) -> Boolean = { c ->
        android.app.ActivityManager.MemoryInfo().also { (c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) }.lowMemory
    }
    const val LOW_MEMORY_MESSAGE = "Android is low on memory right now, so slicing would probably be stopped. Close other apps and try again."

    /** Removes every earlier slice result; a multi-plate run calls this once, then keeps each plate's file. */
    fun clearOutputs(context: Context) { File(context.cacheDir, "sliced-output").listFiles()?.forEach { it.delete() } }
    /** Engine status percent 0-100 for the slice currently (or last) running. */
    fun progress(): Int = NativeEngine.nativeSliceProgress()
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
    suspend fun slice(context: Context, modelFile: File, profile: PrinterProfile, overrides: Map<String, String> = emptyMap(), paintSessionHandle: Long? = null, transform: ModelTransform = ModelTransform()): SliceOutcome = withContext(Dispatchers.IO) { sliceLock.withLock { if (systemLowMemory(context.applicationContext)) return@withContext SliceOutcome.Failed(LOW_MEMORY_MESSAGE); NativeEngine.nativeResetCancel(); markStarted(context.applicationContext); SliceService.start(context.applicationContext); try {
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
            } catch (e: java.util.concurrent.CancellationException) { SliceOutcome.Cancelled
            } catch (e: Exception) { SliceOutcome.Failed(e.message ?: "Slicing failed.") }
        }
    } finally { markFinished(context.applicationContext); SliceService.stop(context.applicationContext) } } }

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
    // toolSlotIndices (Phase 8 follow-up, §11, WO-25/WO-26): parallel to `objects` (index i is
    // that object's own real, 1-based filament/tool assignment - see ToolSlots.kt and
    // NativeEngine.nativeSliceMultiObject's own doc comment for the real 1-based convention),
    // applied only on the plain-gcode Moonraker path today (nativeSliceMultiObject) - the Bambu
    // bundle path doesn't take per-object tool assignment yet (every bundled Bambu profile is
    // single-extruder today, so there is nothing real to assign there - see WORK_ORDER.md's own
    // note on this). Defaults to empty, meaning "every object keeps the printer's default
    // extruder" - unchanged behavior for every caller that predates this parameter.
    // slotMaterials (Phase 8 follow-up, §11, §16, WO-27): index i is real tool slot (i+1)'s own
    // assigned MaterialProfile (null = unassigned, falls back to `overrides`' own material - see
    // below). When non-empty, this builds the real multi-slot filament config
    // (MultiToolFilamentConfig.overridesFor - the general form of the exact recipe
    // ToolAssignmentSlicingDeviceTest proved end to end in WO-26) from the target's own real
    // bundled filament.json base diameter, merged into `overrides`. Left empty (the default, and
    // every caller before this parameter existed), this is the same single-material slice every
    // project already produces - no multi-slot config is generated at all.
    suspend fun sliceProject(context: Context, objects: List<Pair<File, ModelTransform>>, profile: PrinterProfile, overrides: Map<String, String> = emptyMap(), toolSlotIndices: List<Int> = emptyList(), slotMaterials: List<MaterialProfile?> = emptyList(), extras: List<ObjectExtrasText> = emptyList(), outputTag: String? = null): SliceOutcome = withContext(Dispatchers.IO) { sliceLock.withLock { if (systemLowMemory(context.applicationContext)) return@withContext SliceOutcome.Failed(LOW_MEMORY_MESSAGE); NativeEngine.nativeResetCancel(); markStarted(context.applicationContext); SliceService.start(context.applicationContext); try {
        if (objects.isEmpty()) return@withContext SliceOutcome.Failed("Add at least one object to this project before slicing.")
        require(toolSlotIndices.isEmpty() || toolSlotIndices.size == objects.size) { "toolSlotIndices must be empty or match objects in length." }
        when (val resolved = resolveProfilePaths(context, profile)) {
            is ProfileResolution.Blocked -> return@withContext resolved.outcome
            is ProfileResolution.Ready -> return@withContext try {
                val bambuTarget = profile.kind == PrinterKind.BAMBU_LAB
                val output = freshOutputFile(context, if (outputTag != null) "project-$outputTag" else "project", bambuBundle = bambuTarget, keepOthers = outputTag != null)
                if (bambuTarget) {
                    NativeEngine.nativeSliceMultiObjectBambuBundleEx(
                        objects.map { it.first.absolutePath }.toTypedArray(),
                        objects.map { it.second.offsetXMm.toDouble() }.toDoubleArray(),
                        objects.map { it.second.offsetYMm.toDouble() }.toDoubleArray(),
                        objects.map { it.second.rotationZDeg.toDouble() }.toDoubleArray(),
                        objects.map { it.second.scale.toDouble() }.toDoubleArray(),
                        output.absolutePath, resolved.profilePaths.toTypedArray(), overrides.keys.toTypedArray(), overrides.values.toTypedArray(),
                        objects.indices.map { extras.getOrNull(it)?.paint.orEmpty() }.toTypedArray(), objects.indices.map { extras.getOrNull(it)?.volumes.orEmpty() }.toTypedArray(),
                    )
                } else {
                    val slots = toolSlotIndices.ifEmpty { List(objects.size) { 0 } }
                    val effectiveOverrides = if (slotMaterials.isEmpty()) overrides else {
                        // resolved.profilePaths is materialized in SlicingProfilePack.materialize()'s
                        // own fixed order (machine, process, filament) - index 0/2 are the real
                        // machine/filament files this exact slice is about to load.
                        val realToolCount = resolved.pack.toolCountOf(File(resolved.profilePaths[0]).readText())
                        // A real config inconsistency, not a cosmetic one: filament_diameter's own
                        // array length must match the target's real declared extruder count
                        // (ToolSlots.kt's own parseToolCount) or libslic3r's Print::validate()
                        // rejects the slice outright ("Flush volumes matrix do not match to the
                        // correct size!" - hit live building this feature, not assumed). Caught
                        // here with an actionable message instead of surfacing that raw engine
                        // error to a caller who passed the wrong number of slots.
                        require(slotMaterials.size == realToolCount) {
                            "slotMaterials must have exactly $realToolCount entries for this printer (one per its real declared tool slot), got ${slotMaterials.size}."
                        }
                        val baseFilamentJson = File(resolved.profilePaths[2]).readText()
                        val baseDiameter = parseBaseFilamentDiameter(baseFilamentJson)
                        val fallback = slotMaterials.filterNotNull().firstOrNull()
                            ?: BUNDLED_MATERIAL_PROFILES.first { it.id == "bundled-pla" }
                        // Flushing volumes the printer's own slicer's way, with its nozzle volume (FlushVolumes, every slot on the base filament).
                        val flush = runCatching {
                            val base = org.json.JSONObject(baseFilamentJson)
                            FlushVolumes.setup(org.json.JSONObject(File(resolved.profilePaths[0]).readText()), List(slotMaterials.size) { base })
                        }.getOrDefault(FlushVolumes.Setup())
                        overrides + MultiToolFilamentConfig.overridesFor(baseDiameter, slotMaterials, fallback, flush)
                    }
                    NativeEngine.nativeSliceMultiObjectEx(
                        objects.map { it.first.absolutePath }.toTypedArray(),
                        objects.map { it.second.offsetXMm.toDouble() }.toDoubleArray(),
                        objects.map { it.second.offsetYMm.toDouble() }.toDoubleArray(),
                        objects.map { it.second.rotationZDeg.toDouble() }.toDoubleArray(),
                        objects.map { it.second.scale.toDouble() }.toDoubleArray(),
                        slots.toIntArray(),
                        output.absolutePath, resolved.profilePaths.toTypedArray(), effectiveOverrides.keys.toTypedArray(), effectiveOverrides.values.toTypedArray(),
                        objects.indices.map { extras.getOrNull(it)?.paint.orEmpty() }.toTypedArray(), objects.indices.map { extras.getOrNull(it)?.volumes.orEmpty() }.toTypedArray(),
                    )
                }
                SliceOutcome.Success(output)
            } catch (e: java.util.concurrent.CancellationException) { SliceOutcome.Cancelled
            } catch (e: Exception) { SliceOutcome.Failed(e.message ?: "Slicing failed.") }
        }
    } finally { markFinished(context.applicationContext); SliceService.stop(context.applicationContext) } } }

    private fun freshOutputFile(context: Context, baseName: String, bambuBundle: Boolean = false, keepOthers: Boolean = false): File {
        val outputDir = File(context.cacheDir, "sliced-output").apply { mkdirs() }
        // A multi-plate run keeps every plate's result; only a stale file with this exact name is replaced.
        outputDir.listFiles()?.forEach { if (!keepOthers || it.name.startsWith(baseName + ".")) it.delete() }
        return File(outputDir, if (bambuBundle) "$baseName.gcode.3mf" else "$baseName.gcode")
    }

    private sealed class ProfileResolution {
        data class Ready(val profilePaths: List<String>, val pack: SlicingProfilePack) : ProfileResolution()
        data class Blocked(val outcome: SliceOutcome) : ProfileResolution()
    }

    // The firmware-confirmation + profile-pack resolution both slice() and sliceProject() need -
    // factored out so the real Centauri Carbon live-firmware-match logic (and its own hard-won
    // real bug fixes, see the git history on the branch this shipped on) exists in exactly one
    // place, not two that could quietly drift apart.
    private suspend fun resolveProfilePaths(context: Context, profile: PrinterProfile): ProfileResolution {
        val model = profile.slicingModel ?: return ProfileResolution.Blocked(SliceOutcome.Failed("This printer has no slicing profile selected. Choose one from Edit printer first."))
        // A Centauri Carbon profile only ever slices for a printer connected on its own firmware's protocol: an Elegoo-firmware
        // profile (M729 start G-code) never for a Moonraker/COSMOS printer, a COSMOS profile never for an Elegoo-firmware one.
        ElegooProfiles.connectionProblem(model, profile.kind)?.let { return ProfileResolution.Blocked(SliceOutcome.FirmwareBlocked(it)) }
        val cosmosGeneration = if (ElegooProfiles.isCosmos(model)) {
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
        // A printer saved with a profile the engine can't slice yet (engine/snapmaker/unsupported-profiles.json) says so.
        SlicingEngineSupport.unsupportedReason(model)?.let { return ProfileResolution.Blocked(SliceOutcome.Failed(it)) }
        val pack = slicingProfilePack(model, cosmosGeneration, profile.customMachine)
            ?: return ProfileResolution.Blocked(SliceOutcome.Failed("No bundled slicer profile exists yet for this printer's confirmed firmware."))
        return ProfileResolution.Ready(pack.materialize(context), pack)
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

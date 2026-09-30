package net.jamesjennison.klippercompanion.testgrid

import android.content.Context
import com.nozzleitall.testgrid.Canon
import com.nozzleitall.testgrid.EngineInfo
import com.nozzleitall.testgrid.Environment
import com.nozzleitall.testgrid.ModelLibrary
import com.nozzleitall.testgrid.Producer
import com.nozzleitall.testgrid.ProfileInfo
import com.nozzleitall.testgrid.SliceRequest
import com.nozzleitall.testgrid.SliceResult
import com.nozzleitall.testgrid.StlGeometry
import com.nozzleitall.testgrid.TestSlicer
import com.nozzleitall.testgrid.VirtualClock
import kotlinx.coroutines.runBlocking
import com.nozzleitall.printer.ext.Prusa
import com.nozzleitall.printer.ext.PrusaColorMixFormat
import com.nozzleitall.printer.ext.Snapmaker
import net.jamesjennison.klippercompanion.AndroidColorMix
import net.jamesjennison.klippercompanion.AndroidFullSpectrum
import net.jamesjennison.klippercompanion.BUNDLED_MATERIAL_PROFILES
import net.jamesjennison.klippercompanion.ColourMixEngineError
import net.jamesjennison.klippercompanion.BuildConfig
import net.jamesjennison.klippercompanion.CosmosProfileGeneration
import net.jamesjennison.klippercompanion.ElegooProfiles
import net.jamesjennison.klippercompanion.ModelTransform
import net.jamesjennison.klippercompanion.OpenSourceNotice
import net.jamesjennison.klippercompanion.PrinterProfile
import net.jamesjennison.klippercompanion.SliceOutcome
import net.jamesjennison.klippercompanion.SlicingCoordinator
import net.jamesjennison.klippercompanion.SlicingModelCatalog
import net.jamesjennison.klippercompanion.colourMixFeaturesFor
import net.jamesjennison.klippercompanion.readToolCount
import net.jamesjennison.klippercompanion.slicingProfilePack
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Bundled profile packs, read from the APK's assets: the same bytes the engine slices with. */
fun assetProfile(context: Context, id: String): ProfileInfo {
    require(Regex("""^[a-z0-9_]{1,80}$""").matches(id)) { "Bad profile id." }
    return ProfileInfo.load(id) { f -> runCatching { context.assets.open("slicer_profiles/$id/$f").use { it.readBytes() } }.getOrNull() }
}

/**
 * Slices with the real on-device engine through SlicingCoordinator, so the firmware gate applies exactly as for any
 * other slice: a COSMOS profile is sliced only after a live read confirms COSMOS and the matching profile generation.
 * [printer] is the saved printer the suite targets; only its slicing model is replaced by the suite's profile.
 */
class AndroidTestSlicer(private val context: Context, private val printer: PrinterProfile,
                        private val liveSlots: () -> List<net.jamesjennison.klippercompanion.FilamentSlot> = { emptyList() }) : TestSlicer {
    override val simulated = false
    override fun profile(id: String): ProfileInfo = assetProfile(context, id)

    override fun slice(request: SliceRequest): SliceResult {
        val model = SlicingModelCatalog.all.firstOrNull { it.assetDir == request.profile.id }?.model ?: return SliceResult.Failed("Profile ${request.profile.id} is not bundled.")
        val profile = printer.copy(slicingModel = model)
        var mixing: String? = null
        val mix = request.mix
        val outcome = runBlocking {
            if (mix != null) {
                val slots = slicingProfilePack(model, CosmosProfileGeneration.CURRENT.takeIf { ElegooProfiles.isCosmos(model) }, profile.customMachine)?.readToolCount(context) ?: 1
                // The same rule the project editor uses to offer mixing: Full Spectrum on a U1, ColorMix on any other printer with 2+ slots.
                val features = colourMixFeaturesFor(printer.kind, slots)
                if (mix.a !in 1..slots || mix.b !in 1..slots) return@runBlocking SliceOutcome.Failed("The colour mix uses tools ${mix.a} and ${mix.b}, but the profile has $slots tool slot(s).")
                val pla = BUNDLED_MATERIAL_PROFILES.first { it.id == "bundled-pla" }
                val colours = liveSlots().associate { it.tool + 1 to it.colorHex?.takeIf { c -> c.isNotBlank() } }
                val materials = (1..slots).map { if (it == mix.a || it == mix.b) pla.copy(colorHex = colours[it] ?: pla.colorHex) else null }
                val physical = (1..slots).map { (materials[it - 1]?.colorHex ?: "#FFFFFF") to (materials[it - 1]?.type ?: "PLA") }
                val offsets = StlGeometry.partOffsets(request.parts.map { StlGeometry.bounds(it.first.readBytes()) })
                val parts = request.parts.mapIndexed { i, p -> p.first to ModelTransform(offsets[i].first.toFloat(), offsets[i].second.toFloat()) }
                try {
                    when {
                        Snapmaker.FULL_SPECTRUM in features -> {
                            // The project editor's "+ Add 50/50 mix": AndroidFullSpectrum.add, then every object on the new mix's id.
                            val added = AndroidFullSpectrum.add(physical.map { it.first }, "", mix.a, mix.b, mix.bPercent)
                            val id = added.addedId ?: return@runBlocking SliceOutcome.Failed("Full Spectrum added no mix.")
                            mixing = "Full Spectrum"
                            sliceColourMix(model, parts, parts.map { id }, materials, mixedFilamentDefinitions = added.definitions)
                        }
                        Prusa.COLOR_MIX in features -> {
                            // The project editor's "+ Add 50/50 blend": the next virtual id, normalised by the engine.
                            val draft = PrusaColorMixFormat.Virtual(AndroidColorMix.nextId(slots, emptyList()), "blend",
                                listOf(PrusaColorMixFormat.Component(mix.a, (100 - mix.bPercent) / 100.0), PrusaColorMixFormat.Component(mix.b, mix.bPercent / 100.0)))
                            val virtual = AndroidColorMix.normalize(physical, listOf(draft))
                            val id = virtual.firstOrNull()?.id ?: return@runBlocking SliceOutcome.Failed("ColorMix dropped the 50/50 blend.")
                            mixing = "ColorMix"
                            sliceColourMix(model, parts, parts.map { id }, materials, virtualExtruders = PrusaColorMixFormat.sliceRequestJson(virtual))
                        }
                        else -> return@runBlocking SliceOutcome.FirmwareBlocked("Nozzle doesn't offer colour mixing for this printer (${printer.kind.name}, $slots tool slot(s)).")
                    }
                } catch (e: ColourMixEngineError) { SliceOutcome.Failed("Colour mixing: ${e.message}") }
            } else if (request.parts.size == 1) SlicingCoordinator.slice(context, request.parts[0].first, profile)
            else {
                // Two things the project editor already does, found missing on the first real multi-material slice: each
                // part keeps its place relative to the others (the engine centres every object, then applies its offset),
                // and every tool slot gets a material entry, or the engine collapses all parts onto the first tool.
                val offsets = StlGeometry.partOffsets(request.parts.map { StlGeometry.bounds(it.first.readBytes()) })
                val tools = request.parts.map { it.second.tool }
                val slots = slicingProfilePack(model, CosmosProfileGeneration.CURRENT.takeIf { ElegooProfiles.isCosmos(model) }, profile.customMachine)?.readToolCount(context) ?: 1
                if (tools.any { it !in 1..slots }) return@runBlocking SliceOutcome.Failed("This model needs tools ${tools.distinct().sorted()}, but the profile has $slots tool slot(s).")
                val pla = BUNDLED_MATERIAL_PROFILES.first { it.id == "bundled-pla" }
                // Each slot takes the colour the printer reports as loaded there, so the flush volumes between colours are
                // worked out for what is actually printed (with generic white everywhere, black to cyan bled on a CANVAS print).
                val colours = liveSlots().associate { it.tool + 1 to it.colorHex?.takeIf { c -> c.isNotBlank() } }
                SlicingCoordinator.sliceProject(context, request.parts.mapIndexed { i, p -> p.first to ModelTransform(offsets[i].first.toFloat(), offsets[i].second.toFloat()) }, profile,
                    toolSlotIndices = tools, slotMaterials = if (slots > 1) (1..slots).map { if (it in tools) pla.copy(colorHex = colours[it] ?: pla.colorHex) else null } else emptyList(), outputTag = "testgrid")
            }
        }
        return when (outcome) {
            is SliceOutcome.Success -> SliceResult.Success(outcome.gcode, mixing)
            is SliceOutcome.FirmwareBlocked -> SliceResult.Blocked(outcome.reason)
            is SliceOutcome.Failed -> SliceResult.Failed(outcome.message)
            SliceOutcome.Cancelled -> SliceResult.Failed("Slicing was cancelled.")
        }
    }

    /**
     * Colour mixing (0.2.0, requirement 3): a slice option for Full Spectrum ([mixedFilamentDefinitions]) and
     * ColorMix ([virtualExtruders]), through the exact same SlicingCoordinator.sliceProject path the project
     * editor's own colour-mixing UI uses (ProjectEditorScreen.kt's sliceOnePlate) - not a parallel test-only
     * pipeline. slice() calls it for a request with a colour mix (the suites' mix-slice test); the device tests
     * call it directly.
     */
    fun sliceColourMix(
        model: net.jamesjennison.klippercompanion.SlicingPrinterModel,
        parts: List<Pair<File, ModelTransform>>,
        toolSlotIndices: List<Int>,
        slotMaterials: List<net.jamesjennison.klippercompanion.MaterialProfile?>,
        mixedFilamentDefinitions: String? = null,
        virtualExtruders: String = "",
    ): SliceOutcome = runBlocking {
        SlicingCoordinator.sliceProject(
            context, parts, printer.copy(slicingModel = model), toolSlotIndices = toolSlotIndices, slotMaterials = slotMaterials,
            mixedFilamentDefinitions = mixedFilamentDefinitions, virtualExtruders = virtualExtruders, outputTag = "testgrid-colourmix",
        )
    }
}

object AndroidTestEnvironment {
    /** SHA-256 of the engine library this install actually loads: extracted on disk, or read straight from the APK. */
    fun engineBinarySha256(context: Context): String? = runCatching {
        val info = context.applicationInfo
        val onDisk = File(info.nativeLibraryDir, "libslic3rengine.so")
        if (onDisk.isFile) Canon.sha256(onDisk)
        else ZipFile(info.sourceDir).use { z ->
            val entry = z.entries().asSequence().firstOrNull { it.name.startsWith("lib/") && it.name.endsWith("/libslic3rengine.so") } ?: return@use null
            z.getInputStream(entry).use { input ->
                val d = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; d.update(buf, 0, n) }
                d.digest().joinToString("") { "%02x".format(it) }
            }
        }
    }.getOrNull()

    fun create(context: Context, workDir: File, simulatedEngine: Boolean, clock: VirtualClock? = null): Environment {
        val version = runCatching { org.orcaslicer.engine.NativeEngine.nativeGetVersion() }.getOrNull()
        return Environment(
            Producer("Nozzle It All", "android", BuildConfig.VERSION_NAME, BuildConfig.BUILD_TYPE, BuildConfig.APPLICATION_ID, BuildConfig.SOURCE_REVISION.ifBlank { null }),
            EngineInfo(OpenSourceNotice.ENGINE_NAME, OpenSourceNotice.ENGINE_COMMIT, version, engineBinarySha256(context), null, simulatedEngine),
            ModelLibrary.fromResources(), Environment::resourceFixture, workDir,
            clock = clock?.let { it::now } ?: System::currentTimeMillis, sleep = clock?.let { it::sleep } ?: { Thread.sleep(it) })
    }
}

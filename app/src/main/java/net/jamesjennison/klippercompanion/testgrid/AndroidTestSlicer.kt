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
import com.nozzleitall.testgrid.TestSlicer
import com.nozzleitall.testgrid.VirtualClock
import kotlinx.coroutines.runBlocking
import net.jamesjennison.klippercompanion.BuildConfig
import net.jamesjennison.klippercompanion.ModelTransform
import net.jamesjennison.klippercompanion.OpenSourceNotice
import net.jamesjennison.klippercompanion.PrinterProfile
import net.jamesjennison.klippercompanion.SliceOutcome
import net.jamesjennison.klippercompanion.SlicingCoordinator
import net.jamesjennison.klippercompanion.SlicingModelCatalog
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
class AndroidTestSlicer(private val context: Context, private val printer: PrinterProfile) : TestSlicer {
    override val simulated = false
    override fun profile(id: String): ProfileInfo = assetProfile(context, id)

    override fun slice(request: SliceRequest): SliceResult {
        val model = SlicingModelCatalog.all.firstOrNull { it.assetDir == request.profile.id }?.model ?: return SliceResult.Failed("Profile ${request.profile.id} is not bundled.")
        val profile = printer.copy(slicingModel = model)
        val outcome = runBlocking {
            if (request.parts.size == 1) SlicingCoordinator.slice(context, request.parts[0].first, profile)
            else SlicingCoordinator.sliceProject(context, request.parts.map { it.first to ModelTransform() }, profile,
                toolSlotIndices = request.parts.map { it.second.tool }, outputTag = "testgrid")
        }
        return when (outcome) {
            is SliceOutcome.Success -> SliceResult.Success(outcome.gcode)
            is SliceOutcome.FirmwareBlocked -> SliceResult.Blocked(outcome.reason)
            is SliceOutcome.Failed -> SliceResult.Failed(outcome.message)
            SliceOutcome.Cancelled -> SliceResult.Failed("Slicing was cancelled.")
        }
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

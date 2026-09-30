package net.jamesjennison.klippercompanion

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * A Bambu Lab printer (PrinterKind.BAMBU_LAB). Nothing here speaks Moonraker:
 * status and control ride MQTT over TLS on 8883 (BambuMqttConnection), uploads
 * ride implicit-TLS FTPS on 990 (BambuFtpsClient), and the chamber camera comes
 * off the proprietary port-6000 stream re-served as MJPEG on loopback
 * (BambuChamberCamera).
 *
 * Everything PrinterService exposes that a Bambu printer in LAN mode does not -
 * heaters, fans, bed mesh, macros, config files, history, timelapses, tools,
 * the Bespok3d bridge - is deliberately left unimplemented so it falls through
 * to the interface's own default ApiFailure, exactly as Moonraker leaves the
 * reader interfaces it does not implement.
 *
 * Like the rest of this app it holds no persistent printer connection: each
 * snapshot() runs one bounded connect/pushall/read/close probe. The only
 * long-lived thing is the loopback MJPEG server, and only once cameras() has
 * been called; close() stops it again.
 */
class BambuPrinterService(
    private val host: String,
    private val serial: String,
    private val accessCode: String,
    private val probe: BambuStatusProbe = BambuStatusProbe(),
    private val ftps: BambuFtpsClient = BambuFtpsClient(),
) : PrinterService, FilamentSlotReader {

    override val address: String get() = host

    private val config get() = BambuStatusProbeConfig(host, serial, accessCode)
    private val camera = BambuChamberCamera()
    private val sequence = AtomicLong(System.currentTimeMillis() % 100_000)

    override fun snapshot(): PrinterSnapshot {
        val report = await(probe.probe(config), PROBE_TIMEOUT_MS, "The printer did not return its status.")
        return try {
            BambuSnapshot.parse(JSONObject(report))
        } catch (e: org.json.JSONException) {
            throw ApiFailure("Printer state is incomplete.")
        }
    }

    /** The AMS trays from one status report (BambuAmsTrays); read-only, the same bounded probe as snapshot(). */
    override fun filamentSlots(): FilamentSlotStatus {
        val report = await(probe.probe(config), PROBE_TIMEOUT_MS, "The printer did not return its status.")
        val slots = try { BambuAmsTrays.parse(JSONObject(report)) } catch (e: org.json.JSONException) { throw ApiFailure("Printer state is incomplete.") }
        return FilamentSlotStatus(slots, "the printer's AMS")
    }

    /**
     * A Bambu printer in LAN mode exposes no file list and no macros. The camera
     * is started from here the same way Moonraker.catalog() reads its own webcam
     * list - wrapped, so a failure is a warning rather than a lost catalog. The
     * first refresh after connecting therefore blocks for up to 15s waiting for a
     * first frame; every later one reuses the already-running loopback server.
     */
    override fun catalog(): Catalog {
        val warnings = mutableListOf("Bambu Lab printers do not expose a file list or macros over the LAN.")
        val cameras = try {
            cameras()
        } catch (e: Exception) { warnings.add(e.message ?: "The chamber camera is unavailable."); emptyList() }
        return Catalog(emptyList(), emptyList(), cameras, warnings)
    }

    /**
     * Starts (or reuses) the loopback MJPEG server in front of the printer's
     * chamber camera. The returned URLs are absolute loopback URLs; render them
     * with this app's MjpegCamera by passing [cameraAddress] as its `address`,
     * which is what makes Moonraker.cameraUrl's same-host check pass.
     */
    @Synchronized
    override fun cameras(): List<Camera> {
        // The URL carries a token minted by start(), so a live server's URL is
        // remembered rather than rebuilt; a stopped one is started on demand.
        val live = runningStreamUrl.takeIf { camera.isRunning() } ?: try {
            camera.start(host, serial, accessCode).also { runningStreamUrl = it }
        } catch (e: BambuConnectException) {
            throw ApiFailure(e.message ?: "The chamber camera is unavailable.")
        }
        // The entry carries its own loopback origin: the printer's host and the camera's are
        // different by design, and Moonraker.cameraUrl insists they match.
        return listOf(Camera("Chamber", camera.snapshotUrl.orEmpty(), live, "bambu-chamber", "bambu-chamber", cameraAddress))
    }

    @Volatile private var runningStreamUrl: String? = null

    /** Loopback origin to hand MjpegCamera as its `address`, or "" when stopped. */
    val cameraAddress: String get() = camera.origin.orEmpty()

    override fun image(camera: Camera): ByteArray {
        val url = camera.snapshot.takeIf { it.startsWith("http://127.0.0.1:") }
            ?: throw ApiFailure("Camera unavailable.")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 5_000
            instanceFollowRedirects = false
        }
        try {
            if (connection.responseCode != 200) throw ApiFailure("Camera unavailable (HTTP ${connection.responseCode}).")
            val data = connection.inputStream.use { it.readBounded(MAX_IMAGE_BYTES) }
            if (data.size > MAX_IMAGE_BYTES) throw ApiFailure("Camera image is too large.")
            return data
        } catch (e: IOException) {
            throw ApiFailure("Camera unavailable.")
        } finally {
            connection.disconnect()
        }
    }

    override fun command(command: PrinterCommand) {
        val request = command.bambuPrintRequest
        if (request != null) { startPrint(request); return }
        // The dashboard's pause/resume/cancel buttons carry Moonraker-shaped paths; they mean the same thing on a Bambu.
        val control = when (command.path) {
            "printer/print/pause" -> BambuPrintProtocol.Control.PAUSE
            "printer/print/resume" -> BambuPrintProtocol.Control.RESUME
            "printer/print/cancel" -> BambuPrintProtocol.Control.STOP
            else -> throw ApiFailure("Unsupported command for a Bambu Lab printer.")
        }
        sendControl(control)
    }

    /**
     * Publishes pause/resume/stop and returns once it has been sent. Unlike a print start there is no acknowledgement to wait for here: the
     * dashboard's next status read is what shows whether the printer obeyed (state becomes paused / printing / idle).
     */
    private fun sendControl(control: BambuPrintProtocol.Control) {
        val payload = BambuPrintProtocol.buildControlPayload(sequence.incrementAndGet().toString(), control)
        val connection = BambuMqttConnection(object : BambuMqttConnection.Listener {
            override fun onReport(payload: String) = Unit
            override fun onStateChange(state: String, message: String?) = Unit
        })
        try {
            await(connection.connect(host, BambuMqttConnection.DEFAULT_PORT, serial, accessCode), CONNECT_TIMEOUT_MS, "Could not reach the printer.")
            await(connection.publish(payload), CONNECT_TIMEOUT_MS, "Could not send the command to the printer.")
        } finally {
            connection.close()
        }
    }

    /**
     * Uploads an already-sliced .gcode.3mf and asks the printer to print it. Without an AMS mapping (always, until
     * BambuAms.AMS_PRINT_VERIFIED) that is single-material from the external spool - see BambuPrintProtocol's header.
     */
    private fun startPrint(request: BambuPrintRequest) {
        val plate = bambuBundleSliceInfo(request.file)?.let(BambuPrintProtocol::slicePlate)
        // An H2C dynamic-nozzle-map file needs a nozzle-mapping handshake this app doesn't do (BambuPrintProtocol).
        if (plate?.dynamicNozzleMap == true) throw ApiFailure(BambuPrintProtocol.DYNAMIC_NOZZLE_MAP_NOT_SUPPORTED)
        // A multi-filament bundle needs an AMS mapping. Until one has been confirmed on a real printer
        // (BambuAms.AMS_PRINT_VERIFIED) it is refused before anything moves; after that, only with a mapping.
        val used = plate?.filaments.orEmpty()
        val multiFilament = used.size > 1
        if (multiFilament && !BambuAms.AMS_PRINT_VERIFIED) throw ApiFailure(BambuPrintProtocol.MULTI_MATERIAL_NOT_SUPPORTED)
        val mapped = BambuAms.AMS_PRINT_VERIFIED && request.amsMapping.isNotEmpty()
        if (multiFilament && (!mapped || used.any { it.tool !in request.amsMapping })) throw ApiFailure("Match each filament in this file to an AMS tray before printing.")
        // One entry per project filament (not a fixed four), checked against each filament's nozzle on a two-nozzle printer.
        val dualNozzle = plate?.dualNozzle == true || request.amsMapping.values.any { it.extruder == BambuAmsTrays.DEPUTY_EXTRUDER }
        val filamentMaps = plate?.filamentMaps.orEmpty()
        val amsMapping: List<BambuAmsTrays.BambuTray?> = if (!mapped) emptyList() else {
            BambuAms.mappingProblem(request.amsMapping, filamentMaps, dualNozzle)?.let { throw ApiFailure(it) }
            val count = maxOf(plate?.projectFilamentCount ?: 0, (request.amsMapping.keys.maxOrNull() ?: -1) + 1)
            List(count) { tool -> request.amsMapping[tool] }
        }
        val remoteName = request.remoteName
        val md5 = md5Hex(request.file)
        try {
            ftps.upload(BambuFtpsConfig(host, serial, accessCode), request.file, remoteName)
        } catch (e: Exception) {
            throw ApiFailure("Could not upload ${remoteName}: ${e.message ?: "the printer refused the transfer"}")
        }

        val sequenceId = sequence.incrementAndGet().toString()
        val payload = BambuPrintProtocol.buildProjectFilePayload(
            BambuPrintProtocol.ProjectFileCommand(
                sequenceId = sequenceId,
                fileName = remoteName,
                subtaskName = remoteName.removeSuffix(".gcode.3mf").removeSuffix(".GCODE.3MF"),
                md5 = md5,
                bedType = request.bedType,
                bedLeveling = request.bedLeveling,
                flowCalibration = request.flowCalibration,
                timelapse = request.timelapse,
                amsMapping = amsMapping,
                filamentMaps = filamentMaps,
                dualNozzle = dualNozzle,
            )
        )

        val acknowledged = CompletableFuture<BambuPrintProtocol.Acknowledgement>()
        val connection = BambuMqttConnection(object : BambuMqttConnection.Listener {
            override fun onReport(payload: String) {
                val result = BambuPrintProtocol.acknowledgement(payload, sequenceId)
                if (result != BambuPrintProtocol.Acknowledgement.NOT_MATCHING) acknowledged.complete(result)
            }

            override fun onStateChange(state: String, message: String?) {
                if (state == "disconnected" && !acknowledged.isDone) {
                    acknowledged.completeExceptionally(
                        BambuConnectException("print-disconnected", message ?: "The printer closed the connection")
                    )
                }
            }
        })
        try {
            await(connection.connect(host, BambuMqttConnection.DEFAULT_PORT, serial, accessCode),
                CONNECT_TIMEOUT_MS, "Could not reach the printer.")
            await(connection.publish(payload), CONNECT_TIMEOUT_MS, "Could not send the print command.")
            val result = await(acknowledged, ACK_TIMEOUT_MS, "The printer did not acknowledge the print command.")
            if (result != BambuPrintProtocol.Acknowledgement.SUCCESS) {
                throw ApiFailure("The printer rejected the print command; inspect printer state.")
            }
        } finally {
            connection.close()
        }
    }

    override fun close() {
        camera.stop()
        runningStreamUrl = null
    }

    private fun <T> await(future: CompletableFuture<T>, timeoutMs: Long, fallback: String): T = try {
        future.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        future.cancel(true)
        throw ApiFailure(fallback)
    } catch (e: ExecutionException) {
        throw ApiFailure(e.cause?.message ?: fallback)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw ApiFailure(fallback)
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 8_000L
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val ACK_TIMEOUT_MS = 20_000L
        const val MAX_IMAGE_BYTES = 4_000_000

        fun md5Hex(file: File): String {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(32_768)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { String.format(Locale.US, "%02X", it) }
        }

        fun java.io.InputStream.readBounded(maximum: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32_768)
            while (out.size() <= maximum) {
                val read = read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}


/**
 * Bambu's MQTT status report -> this app's PrinterSnapshot. Pure, so it can be
 * tested straight against a captured report (see BambuSnapshotTest).
 */
object BambuSnapshot {

    /**
     * gcode_state -> this app's print state, which is Klipper's print_stats.state.
     *
     * PREPARE covers heating and bed levelling, which Klipper reports as part of
     * printing; treating it as idle would make the dashboard look asleep while
     * the machine is visibly busy. Matches Helix's own PRINT_STATE table.
     */
    private val PRINT_STATE = mapOf(
        "IDLE" to "standby",
        "PREPARE" to "printing",
        "SLICING" to "printing",
        "RUNNING" to "printing",
        "PAUSE" to "paused",
        "FINISH" to "complete",
        "FAILED" to "error",
    )

    /** Bambu sends numbers as strings about as often as it sends them as numbers. */
    fun number(report: JSONObject, key: String): Double? {
        if (!report.has(key) || report.isNull(key)) return null
        return when (val value = report.get(key)) {
            is Number -> value.toDouble().takeIf { it.isFinite() }
            is String -> value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
            else -> null
        }
    }

    fun parse(report: JSONObject): PrinterSnapshot {
        val print = report.optJSONObject("print") ?: throw org.json.JSONException("No print section")
        val state = PRINT_STATE[print.optString("gcode_state").uppercase()] ?: "standby"
        // subtask_name is the print job's name; the P-series usually leaves
        // gcode_file blank, so it is the only human-readable label there is.
        val filename = print.optString("subtask_name").ifBlank { print.optString("gcode_file") }
        val progress = (number(print, "mc_percent") ?: 0.0).coerceIn(0.0, 100.0) / 100.0
        return PrinterSnapshot(
            // A report came back at all, so the printer is up and answering.
            ready = true,
            state = state,
            filename = filename,
            progress = progress.toFloat(),
            nozzle = number(print, "nozzle_temper"),
            nozzleTarget = number(print, "nozzle_target_temper"),
            bed = number(print, "bed_temper"),
            bedTarget = number(print, "bed_target_temper"),
            // Bambu reports only mc_remaining_time (minutes left), never elapsed
            // time, so there is no honest print_duration equivalent.
            printDuration = null,
            currentLayer = number(print, "layer_num")?.toInt()?.takeIf { it > 0 },
            totalLayers = number(print, "total_layer_num")?.toInt()?.takeIf { it > 0 },
            // One hotend, and the name the rest of this app expects to see here.
            activeExtruder = "extruder",
        )
    }
}

/** How many filaments a `.gcode.3mf` bundle's slice_info lists; 0 for a plain G-code file or a bundle without one. */
fun bambuBundleFilaments(file: File): Int = bambuBundleSliceInfo(file)?.let(BambuPrintProtocol::usedFilaments) ?: 0

/** A `.gcode.3mf` bundle's `Metadata/slice_info.config`; null for a plain G-code file or a bundle without one. */
fun bambuBundleSliceInfo(file: File): String? = try {
    java.util.zip.ZipFile(file).use { zip ->
        zip.getEntry("Metadata/slice_info.config")?.let { entry -> zip.getInputStream(entry).bufferedReader().use { it.readText() } }
    }
} catch (_: java.util.zip.ZipException) { null }

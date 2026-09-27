package com.nozzleitall.adapter.paxx

import com.nozzleitall.printer.*
import com.nozzleitall.printer.ext.FullSpectrumState
import com.nozzleitall.printer.ext.Snapmaker
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure U1/PAXX protocol rules: parsing Moonraker status into the shared model, detecting PAXX, and building commands.
 * No I/O, so every rule is unit-tested against recorded printer data.
 *
 * Provenance (docs/upstream/PROVENANCE.md, entry P-0001): the print_task_config field semantics, the
 * SET_PRINT_FILAMENT_CONFIG and start_local_print shapes and the toolhead limits are ported from the Snapmaker Orca fork
 * (src/libslic3r/U1PrintTask.{hpp,cpp} at 11bea5c981, AGPL-3.0), which documents them against Snapmaker/u1-klipper and
 * Snapmaker/u1-moonraker. PAXX detection follows the PAXX documentation
 * (snapmakeru1-extended-firmware.pages.dev: firmware_config.html, camera_support.html).
 */
object U1Protocol {
    const val PHYSICAL_TOOLHEADS = 4
    const val LOGICAL_FILAMENTS = 32
    private val u1Version = Regex("""^\d+\.\d+\.\d+\.\d+_\d{10,14}$""")

    /** Objects queried for one status reading: a single atomic Moonraker call. */
    val statusQuery: Map<String, String> = linkedMapOf(
        "webhooks" to "state,state_message", "print_stats" to "state,filename,print_duration,info,message",
        "virtual_sdcard" to "progress", "toolhead" to "extruder", "heater_bed" to "temperature,target",
        "extruder" to "temperature,target,nozzle_diameter", "extruder1" to "temperature,target,nozzle_diameter",
        "extruder2" to "temperature,target,nozzle_diameter", "extruder3" to "temperature,target,nozzle_diameter",
        "print_task_config" to "filament_exist,filament_vendor,filament_type,filament_sub_type,filament_color_rgba,filament_official",
    )

    fun mapState(webhooksState: String?, printState: String?): PrinterState = when {
        webhooksState == null -> PrinterState.UNKNOWN
        webhooksState == "startup" -> PrinterState.STARTING
        webhooksState == "shutdown" || webhooksState == "error" -> PrinterState.ERROR
        webhooksState != "ready" -> PrinterState.STARTING
        else -> when (printState) {
            "standby" -> PrinterState.READY
            "printing" -> PrinterState.PRINTING
            "paused" -> PrinterState.PAUSED
            "complete" -> PrinterState.FINISHED
            "cancelled" -> PrinterState.CANCELLED
            "error" -> PrinterState.ERROR
            else -> PrinterState.UNKNOWN
        }
    }

    fun normalizeColor(raw: String?): String? {
        val s = raw?.trim()?.removePrefix("#") ?: return null
        if ((s.length != 6 && s.length != 8) || !s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return "#" + s.substring(0, 6).uppercase()
    }

    private fun JSONArray?.str(i: Int): String? = this?.opt(i)?.let { if (it == JSONObject.NULL) null else it.toString() }?.trim()?.takeIf { it.isNotEmpty() && !it.equals("NONE", true) }
    private fun JSONArray?.bool(i: Int): Boolean = this?.opt(i) == true

    /** Parses the result of `printer/objects/query` with [statusQuery]. */
    fun parseStatus(result: JSONObject, route: ConnectionRoute, observedAt: Long = System.currentTimeMillis()): PrinterStatus {
        val status = result.optJSONObject("status") ?: return PrinterStatus(PrinterState.UNKNOWN, route, message = "The printer's reply had no status.", observedAtMillis = observedAt)
        val webhooks = status.optJSONObject("webhooks")
        val stats = status.optJSONObject("print_stats")
        val state = mapState(webhooks?.optString("state"), stats?.optString("state"))
        fun num(o: JSONObject?, k: String) = o?.opt(k)?.let { (it as? Number)?.toDouble() }?.takeIf { it.isFinite() }
        val active = status.optJSONObject("toolhead")?.optString("extruder").orEmpty()
        val cfg = status.optJSONObject("print_task_config")
        val heads = (0 until PHYSICAL_TOOLHEADS).mapNotNull { i ->
            val name = if (i == 0) "extruder" else "extruder$i"
            val ex = status.optJSONObject(name) ?: return@mapNotNull null
            val loaded = cfg?.optJSONArray("filament_exist").bool(i)
            val material = if (cfg == null || !loaded) null else Material(
                vendor = cfg.optJSONArray("filament_vendor").str(i), type = cfg.optJSONArray("filament_type").str(i)?.uppercase(),
                subType = cfg.optJSONArray("filament_sub_type").str(i), colorHex = normalizeColor(cfg.optJSONArray("filament_color_rgba").str(i)),
                fromTag = cfg.optJSONArray("filament_official").bool(i))
            Toolhead(i, num(ex, "temperature"), num(ex, "target"), num(ex, "nozzle_diameter"), loaded, material, active == name)
        }
        val job = if (state.isActiveJob) stats?.let {
            val info = it.optJSONObject("info")
            JobProgress(it.optString("filename"), (num(status.optJSONObject("virtual_sdcard"), "progress") ?: 0.0).coerceIn(0.0, 1.0).toFloat(),
                num(it, "print_duration")?.takeIf { d -> d >= 0 }, info?.optInt("current_layer")?.takeIf { l -> l > 0 }, info?.optInt("total_layer")?.takeIf { l -> l > 0 })
        } else null
        val bed = status.optJSONObject("heater_bed")?.let { Temperature(num(it, "temperature"), num(it, "target")) }
        val message = when (state) {
            PrinterState.ERROR, PrinterState.STARTING -> webhooks?.optString("state_message")?.takeIf { it.isNotBlank() } ?: stats?.optString("message")?.takeIf { it.isNotBlank() }
            else -> stats?.optString("message")?.takeIf { it.isNotBlank() }
        }
        val ext = if (cfg != null) mapOf(Snapmaker.FULL_SPECTRUM to fullSpectrum(heads, true).toExtension()) else emptyMap()
        return PrinterStatus(state, route, job, bed, heads, message, observedAt, ext)
    }

    /** Full Spectrum needs the U1's multi-toolhead filament report and at least two loaded, coloured toolheads. */
    fun fullSpectrum(heads: List<Toolhead>, hasTaskConfig: Boolean): FullSpectrumState {
        if (!hasTaskConfig) return FullSpectrumState(false, unavailableReason = "This printer does not report U1 toolhead materials.")
        val palette = heads.filter { it.loaded }.mapNotNull { it.material?.colorHex }
        return if (palette.size >= 2) FullSpectrumState(true, palette)
        else FullSpectrumState(false, palette, "Load coloured material in at least two toolheads to mix colours.")
    }

    data class Detection(val isU1: Boolean, val family: PrinterFamily, val evidence: String)

    /**
     * Decides what a Moonraker host is from LAN-only reads. [configFiles] is `server/files/list?root=config` paths,
     * [webcams] is `server/webcams/list`. PAXX installs its settings under `extended/` (extended2.cfg since PAXX
     * 1.1.0, extended.cfg before) and serves cameras at /webcam/webrtc; stock firmware has neither.
     */
    fun detect(printerInfo: JSONObject?, objects: List<String>, configFiles: List<String>, webcams: JSONArray?): Detection {
        val version = printerInfo?.optString("software_version").orEmpty()
        val isU1 = "print_task_config" in objects || u1Version.matches(version)
        val paxxConfig = configFiles.firstOrNull { it.startsWith("extended/extended2.cfg") || it.startsWith("extended/extended.cfg") }
        val paxxCamera = webcams?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("stream_url").startsWith("/webcam/webrtc") || it.optString("stream_url").startsWith("/webcam2/webrtc") } }
        return when {
            paxxConfig != null -> Detection(isU1, PrinterFamily.PAXX_U1, "Found PAXX settings file config/$paxxConfig.")
            isU1 && paxxCamera != null -> Detection(true, PrinterFamily.PAXX_U1, "Found a PAXX camera stream (${paxxCamera.optString("stream_url")}).")
            isU1 -> Detection(true, PrinterFamily.STOCK_U1, "Snapmaker U1 firmware $version with no PAXX settings. Stock U1 printers use the optional Stock U1 adapter.")
            else -> Detection(false, PrinterFamily.KLIPPER, "Klipper through Moonraker${if (version.isNotBlank()) " ($version)" else ""}.")
        }
    }

    fun cameras(webcams: JSONArray): List<CameraEndpoint> = (0 until minOf(webcams.length(), 16)).mapNotNull { webcams.optJSONObject(it) }
        .filter { it.optBoolean("enabled", true) }
        .mapNotNull { w ->
            val stream = w.optString("stream_url"); val snap = w.optString("snapshot_url")
            val name = w.optString("name", "Camera")
            if (name.equals("gui", true) || Regex("/screen(?:/|$)", RegexOption.IGNORE_CASE).containsMatchIn(stream)) return@mapNotNull null
            val id = w.optString("uid").ifBlank { name }
            when {
                stream.contains("webrtc", true) -> CameraEndpoint(id, name, CameraKind.WEBRTC, stream, snap.ifBlank { null })
                stream.isNotBlank() && w.optString("service").contains("mjpeg", true) -> CameraEndpoint(id, name, CameraKind.MJPEG_STREAM, stream, snap.ifBlank { null })
                snap.isNotBlank() -> CameraEndpoint(id, name, CameraKind.SNAPSHOT, snap, snap)
                else -> null
            }
        }

    // --- commands. Every value is validated here; a bad value throws before anything is sent. ---

    fun heaterName(toolhead: Int): String { require(toolhead in 0 until PHYSICAL_TOOLHEADS) { "The U1 has toolheads 1-$PHYSICAL_TOOLHEADS." }; return if (toolhead == 0) "extruder" else "extruder$toolhead" }
    fun nozzleTemperature(toolhead: Int, celsius: Int): String { require(celsius in 0..300) { "Nozzle temperature must be 0-300 °C." }; return "SET_HEATER_TEMPERATURE HEATER=${heaterName(toolhead)} TARGET=$celsius" }
    fun bedTemperature(celsius: Int): String { require(celsius in 0..110) { "Bed temperature must be 0-110 °C." }; return "SET_HEATER_TEMPERATURE HEATER=heater_bed TARGET=$celsius" }
    fun jog(axis: Char, mm: Double): String {
        val a = axis.uppercaseChar(); require(a in "XYZ") { "Unknown axis." }
        require(mm.isFinite() && mm != 0.0 && kotlin.math.abs(mm) <= 50.0) { "Moves are limited to 50 mm." }
        return "G91\nG1 $a${"%.2f".format(java.util.Locale.US, mm)} F${if (a == 'Z') 600 else 3000}\nG90"
    }
    fun selectToolhead(toolhead: Int): String { heaterName(toolhead); return "T$toolhead" }

    private fun clean(s: String?): String = (s ?: "").filter { it != '"' && it != '\\' && it >= ' ' }.trim().ifEmpty { "NONE" }
    fun setMaterial(toolhead: Int, m: Material, force: Boolean = true): String {
        heaterName(toolhead)
        val rgb = normalizeColor(m.colorHex) ?: throw IllegalArgumentException("Choose a colour for the material.")
        return "SET_PRINT_FILAMENT_CONFIG CONFIG_EXTRUDER=$toolhead VENDOR=\"${clean(m.vendor)}\" FILAMENT_TYPE=\"${clean(m.type)}\" FILAMENT_SUBTYPE=\"${clean(m.subType)}\" FILAMENT_COLOR_RGBA=${rgb.substring(1)}FF" + if (force) " FORCE=1" else ""
    }

    fun startLocalPrintBody(path: String, toolheadMap: List<Int>): JSONObject {
        require(toolheadMap.size <= LOGICAL_FILAMENTS) { "The U1 supports at most $LOGICAL_FILAMENTS filaments per print." }
        toolheadMap.forEachIndexed { i, t -> require(t == -1 || t in 0 until PHYSICAL_TOOLHEADS) { "Filament ${i + 1} is mapped to a toolhead the U1 does not have." } }
        val pairs = toolheadMap.withIndex().filter { it.value >= 0 }.joinToString(",") { "[${it.index},${it.value}]" }
        return JSONObject().put("path", path).put("print_plate", 1).apply { if (pairs.isNotEmpty()) put("options", JSONObject().put("map_table", "[$pairs]")) }
    }

    fun validateRemotePath(path: String): String {
        require(path.isNotBlank() && path.length <= 255 && !path.startsWith('/') && '\\' !in path && path.split('/').none { it == ".." || it == "." || it.isEmpty() }) { "Invalid file name on the printer." }
        return path
    }
}

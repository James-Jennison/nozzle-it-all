@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.nozzleitall.desktop.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.PrinterEntry
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.printer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Picks the printer a screen is about; shared by Monitor, Materials and Full Spectrum. */
@Composable
fun PrinterPicker(state: AppState): PrinterEntry? {
    val ids = state.fleet.order.value
    val current = state.selectedPrinter?.takeIf { it in ids } ?: ids.firstOrNull()
    if (ids.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { contentDescription = "Choose printer" }) {
        ids.forEach { id -> state.fleet.printers[id]?.let { e ->
            NzButton(e.config.identity.displayName, { state.selectedPrinter = id }, kind = if (id == current) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
        } }
    }
    return current?.let { state.fleet.printers[it] }
}

@Composable
fun NoPrinterYet(state: AppState, what: String) = Card(Modifier.fillMaxWidth()) {
    EmptyState(NzIcon.FLEET, "No printers yet", "Add a printer to see $what.") {
        NzButton("Go to Printers", { state.destination = com.nozzleitall.desktop.Destination.FLEET }, kind = ButtonKind.PRIMARY)
    }
}

@Composable
fun MonitorScreen(state: AppState) {
    Column(Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionHeader("Print & Monitor", "Watch a job and control the printer. Every command asks first.")
        val entry = PrinterPicker(state) ?: run { NoPrinterYet(state, "live status, cameras and controls"); return@Column }
        val status = entry.status.value
        val caps = entry.capabilities.value
        val flow = rememberActionFlow(entry)
        ActionFlowUi(flow, entry)
        entry.problem.value?.let { Banner(it, BannerKind.WARNING) }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                CameraPanel(entry)
                JobPanel(entry, status)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Card(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt(entry.config.identity.displayName, Nz.type.title, modifier = Modifier.weight(1f))
                        StatusPill(status.state)
                    }
                    RouteBadge(status.route)
                    status.message?.let { Txt(it, Nz.type.bodySmall, Nz.colors.textMuted) }
                    Txt("Updated ${ago(status.observedAtMillis)}", Nz.type.bodySmall, Nz.colors.textMuted)
                }
                TemperaturePanel(status)
                if (caps != null) ControlsPanel(entry, status, caps, flow)
            }
        }
    }
}

fun ago(millis: Long): String {
    val s = ((System.currentTimeMillis() - millis) / 1000).coerceAtLeast(0)
    return if (s < 5) "just now" else if (s < 90) "$s s ago" else "${s / 60} min ago"
}

@Composable
fun CameraPanel(entry: PrinterEntry) {
    val c = Nz.colors
    val cams = entry.cameras.value
    var image by remember(entry) { mutableStateOf<ImageBitmap?>(null) }
    var problem by remember(entry) { mutableStateOf<String?>(null) }
    var live by remember(entry) { mutableStateOf(false) }
    val camera = cams.firstOrNull()
    // The open stream, so leaving the screen closes it at once (a blocking socket read doesn't notice cancellation).
    val openStream = remember(entry, camera) { java.util.concurrent.atomic.AtomicReference<java.io.Closeable?>(null) }
    val ffmpeg = remember { com.nozzleitall.desktop.camera.FfmpegVideo.locate() }
    DisposableEffect(entry, camera) { onDispose { openStream.getAndSet(null)?.let { runCatching { it.close() } } } }
    LaunchedEffect(entry, camera) {
        var videoWorks = true // a video attempt that produced no frame falls back to MJPEG from then on
        while (isActive && camera != null) {
            val s = entry.session
            if (s == null || entry.status.value.state == PrinterState.OFFLINE) { delay(2_000); continue }
            if (camera.liveUrl != null || (videoWorks && camera.videoUrl != null && ffmpeg != null)) {
                // Live: the camera's H.264 video when ffmpeg can play it (full frame rate), otherwise its MJPEG stream.
                // Every frame is decoded as it arrives; on any failure, wait briefly and reconnect.
                try {
                    withContext(Dispatchers.IO) {
                        val video = if (videoWorks && camera.videoUrl != null && ffmpeg != null) runCatching { com.nozzleitall.desktop.camera.FfmpegVideo.start(ffmpeg, s.videoStream(camera)) }.getOrNull() else null
                        val mjpeg = if (video == null) s.liveStream(camera) else null
                        val source: java.io.Closeable = video ?: mjpeg!!
                        source.use {
                            openStream.set(source)
                            val frames = mjpeg?.let { MjpegReader(it) }
                            var shown = 0
                            while (isActive) {
                                val frame = (video?.next() ?: frames?.next()) ?: break
                                shown++
                                val decoded = runCatching { org.jetbrains.skia.Image.makeFromEncoded(frame).toComposeImageBitmap() }.getOrNull() ?: continue
                                image = decoded; live = true; problem = null
                            }
                            if (video != null && shown == 0 && camera.liveUrl != null) videoWorks = false
                        }
                    }
                } catch (e: Exception) { if (isActive) problem = e.message ?: "Camera unavailable." }
                finally { openStream.set(null); live = false }
                delay(2_000)
            } else {
                // Only for cameras with no live stream: stills, refreshed often.
                try {
                    val bytes = withContext(Dispatchers.IO) { s.snapshot(camera) }
                    image = org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap(); problem = null
                } catch (e: Exception) { problem = e.message ?: "Camera unavailable." }
                delay(if (entry.status.value.state.isActiveJob) 1_000 else 3_000)
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(NzIcon.CAMERA, c.textMuted, 18.dp); Spacer(Modifier.width(8.dp))
            Txt(camera?.name?.replaceFirstChar { it.uppercase() } ?: "Camera", Nz.type.title, modifier = Modifier.weight(1f))
            if (live) Pill("Live", c.accent)
        }
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(c.surfaceSunken), contentAlignment = Alignment.Center) {
            when {
                camera == null -> Txt(if (entry.capabilities.value?.camera == false) "Nozzle It All can't show this printer's camera." else "No camera found on this printer.", Nz.type.body, c.textMuted)
                image != null -> androidx.compose.foundation.Image(image!!, "Live camera view of ${entry.config.identity.displayName}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else -> Txt(problem ?: "Connecting to the camera…", Nz.type.body, c.textMuted)
            }
        }
        when {
            camera?.liveUrl == null && image != null -> Txt("This camera only offers still images, so they refresh every second or so.", Nz.type.bodySmall, c.textMuted)
            !live && image != null && problem != null -> Txt("The live feed dropped ($problem). Reconnecting…", Nz.type.bodySmall, c.textMuted)
        }
    }
}

@Composable
fun JobPanel(entry: PrinterEntry, status: PrinterStatus) {
    val c = Nz.colors
    Card(Modifier.fillMaxWidth()) {
        Txt("Current job", Nz.type.title)
        val job = status.job
        if (job == null) Txt(if (status.state == PrinterState.FINISHED) "The last job finished." else "Nothing is printing.", Nz.type.body, c.textMuted)
        else {
            Txt(job.fileName, Nz.type.body)
            ProgressBar(job.fraction, Nz.status.forState(status.state.glossaryId), label = "Print progress ${(job.fraction * 100).toInt()} percent")
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Metric("Done", "${(job.fraction * 100).toInt()}%")
                Metric("Elapsed", formatDuration(job.elapsedSeconds))
                job.totalLayers?.let { Metric("Layer", "${job.currentLayer ?: 0} of $it") }
            }
        }
        if (status.toolheads.isNotEmpty()) ToolheadStrip(status.toolheads)
    }
}

@Composable
fun TemperaturePanel(status: PrinterStatus) {
    Card(Modifier.fillMaxWidth()) {
        Txt("Temperatures", Nz.type.title)
        status.bed?.let { Row { Txt("Bed", Nz.type.body, modifier = Modifier.weight(1f)); Txt("${temp(it.current)} → ${temp(it.target)}", Nz.type.metricSmall) } }
        status.toolheads.forEach { t ->
            Row { Txt("Toolhead ${t.index + 1}" + if (t.active) " (active)" else "", Nz.type.body, modifier = Modifier.weight(1f)); Txt("${temp(t.nozzleTemperature)} → ${temp(t.nozzleTarget)}", Nz.type.metricSmall) }
        }
        if (status.bed == null && status.toolheads.isEmpty()) Txt("No temperature readings yet.", Nz.type.body, Nz.colors.textMuted)
    }
}

@Composable
fun ControlsPanel(entry: PrinterEntry, status: PrinterStatus, caps: Capabilities, flow: ActionFlow) {
    val c = Nz.colors
    val blocked = entry.guard?.needsReconcile == true || flow.busy
    Card(Modifier.fillMaxWidth()) {
        Txt("Controls", Nz.type.title)
        if (blocked && entry.guard?.needsReconcile == true) Txt("Commands are paused until Nozzle has checked the printer.", Nz.type.bodySmall, Nz.status.paused)
        fun offer(a: PrinterAction) = !blocked && status.state in a.allowedStates
        // Each control appears only if this printer's adapter reports it; nothing here depends on the printer's make.
        if (!caps.anyControl) Txt("${entry.config.identity.displayName} can be monitored from Nozzle It All but not controlled.", Nz.type.body, c.textMuted)
        if (caps.pausePrint || caps.resumePrint || caps.cancelPrint) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (caps.pausePrint) NzButton("Pause", { flow.request(PrinterAction.Pause) }, icon = NzIcon.PAUSE, enabled = offer(PrinterAction.Pause))
            if (caps.resumePrint) NzButton("Resume", { flow.request(PrinterAction.Resume) }, icon = NzIcon.PLAY, enabled = offer(PrinterAction.Resume))
            if (caps.cancelPrint) NzButton("Cancel print", { flow.request(PrinterAction.Cancel) }, kind = ButtonKind.DANGER, icon = NzIcon.STOP, enabled = offer(PrinterAction.Cancel))
        }
        if (caps.temperatures) {
            var nozzle by remember { mutableStateOf("") }
            var bed by remember { mutableStateOf("") }
            val active = status.toolheads.firstOrNull { it.active }?.index ?: 0
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.Bottom) {
                Field("Toolhead ${active + 1} °C", nozzle, { nozzle = it.filter(Char::isDigit).take(3) }, Modifier.width(120.dp), placeholder = "210")
                NzButton("Heat", { nozzle.toIntOrNull()?.let { flow.request(PrinterAction.SetNozzleTemperature(active, it)) } }, icon = NzIcon.HEAT, enabled = !blocked && nozzle.isNotBlank())
                Field("Bed °C", bed, { bed = it.filter(Char::isDigit).take(3) }, Modifier.width(100.dp), placeholder = "60")
                NzButton("Heat", { bed.toIntOrNull()?.let { flow.request(PrinterAction.SetBedTemperature(it)) } }, icon = NzIcon.HEAT, enabled = !blocked && bed.isNotBlank())
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NzButton("Toolhead ${active + 1} heater off", { flow.request(PrinterAction.SetNozzleTemperature(active, 0)) }, kind = ButtonKind.QUIET, enabled = !blocked)
                NzButton("Bed heater off", { flow.request(PrinterAction.SetBedTemperature(0)) }, kind = ButtonKind.QUIET, enabled = !blocked)
            }
        }
        // Wraps onto more lines in a narrow window rather than squeezing buttons.
        if (caps.motion) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NzButton("Home", { flow.request(PrinterAction.HomeAll) }, icon = NzIcon.HOME, enabled = offer(PrinterAction.HomeAll))
            listOf('X' to 10.0, 'X' to -10.0, 'Y' to 10.0, 'Y' to -10.0, 'Z' to 1.0, 'Z' to -1.0).forEach { (axis, mm) ->
                val a = PrinterAction.Jog(axis, mm)
                NzButton("$axis${if (mm > 0) "+" else "−"}${kotlin.math.abs(mm).let { if (it == it.toInt().toDouble()) it.toInt() else it }}", { flow.request(a) }, kind = ButtonKind.QUIET, enabled = offer(a))
            }
        }
        Txt("Physical results of commands are the printer's to confirm; Nozzle shows only what the printer reports.", Nz.type.bodySmall, c.textMuted)
    }
}

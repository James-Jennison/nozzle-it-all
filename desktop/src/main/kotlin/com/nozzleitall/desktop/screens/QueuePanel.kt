package com.nozzleitall.desktop.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.PrinterEntry
import com.nozzleitall.desktop.queue.QueueItem
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.printer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun PrinterEntry.isFree() = status.value.state in setOf(PrinterState.READY, PrinterState.FINISHED, PrinterState.CANCELLED)
private fun PrinterEntry.canTakeJobs() = capabilities.value?.let { it.uploadAndStart || (it.uploadJob && it.startPrint) } == true && "gcode" in (capabilities.value?.acceptedOutputs ?: emptySet())

/**
 * The prints waiting for a printer. Nothing starts by itself: "Start" asks, like every other command, and only a person can say the
 * plate is clear. A job for one printer waits for that printer; a job for "any" is offered the first free printer that can take it.
 */
@Composable
fun QueuePanel(state: AppState) {
    val items = state.fleet.queue.value
    if (items.isEmpty()) return
    val c = Nz.colors
    Card(Modifier.fillMaxWidth()) {
        Txt("Print queue (${items.size})", Nz.type.title)
        Txt("Waiting for a printer. Starting one asks first; nothing runs on its own.", Nz.type.bodySmall, c.textMuted)
        items.forEachIndexed { i, item -> QueueRow(state, item, first = i == 0, last = i == items.lastIndex) }
    }
}

@Composable
private fun QueueRow(state: AppState, item: QueueItem, first: Boolean, last: Boolean) {
    val c = Nz.colors
    val fleet = state.fleet
    val scope = rememberCoroutineScope()
    val ids = fleet.order.value
    val target: PrinterEntry? = item.printerId?.let { fleet.printers[it] }
    // The printer this row would run on now: its own, or for "any" the first free one that can take a job.
    val candidate = target ?: ids.mapNotNull { fleet.printers[it] }.firstOrNull { it.isFree() && it.canTakeJobs() }
    val flow = candidate?.let { rememberActionFlow(it) }
    var uploading by remember(item.id) { mutableStateOf<Float?>(null) }
    var problem by remember(item.id) { mutableStateOf<String?>(null) }
    var started by remember(item.id) { mutableStateOf(false) }
    // The file counts as handed over once the printer accepts the start; only then does it leave the queue.
    LaunchedEffect(flow?.message) { if (started && flow?.message?.second == BannerKind.SUCCESS) fleet.dequeue(item.id) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Txt(item.name, Nz.type.body, maxLines = 1)
                Txt("For " + (target?.config?.identity?.displayName ?: "any printer") + when {
                    candidate == null -> if (item.printerId != null) " (printer not found)" else " · no free printer yet"
                    !candidate.isFree() -> " · busy"
                    else -> " · ready"
                }, Nz.type.bodySmall, c.textMuted)
            }
            // Cycle the target through: any, then each printer.
            NzButton("Change printer", {
                val options = listOf<String?>(null) + ids
                val next = options[(options.indexOf(item.printerId) + 1) % options.size]
                fleet.retargetQueued(item.id, next)
            }, kind = ButtonKind.QUIET)
            NzButton("↑", { fleet.moveInQueue(item.id, -1) }, kind = ButtonKind.QUIET, enabled = !first)
            NzButton("↓", { fleet.moveInQueue(item.id, 1) }, kind = ButtonKind.QUIET, enabled = !last)
            NzButton("Remove", { fleet.dequeue(item.id) }, kind = ButtonKind.QUIET)
            if (candidate != null && flow != null) NzButton(
                if (uploading != null) "Sending…" else "Start on ${candidate.config.identity.displayName}",
                {
                    problem = null
                    val file = fleet.queuedFile(item)
                    val caps = candidate.capabilities.value
                    val remote = "nozzle/" + item.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60) + ".gcode"
                    started = true
                    if (caps?.uploadAndStart == true) flow.request(PrinterAction.UploadAndStart(file.absolutePath, remote))
                    else scope.launch {
                        val session = candidate.session
                        if (session == null) { problem = "${candidate.config.identity.displayName} isn't connected."; started = false; return@launch }
                        uploading = 0f
                        val r = withContext(Dispatchers.IO) { session.upload(file, remote) { s, t -> uploading = if (t > 0) s.toFloat() / t else null } }
                        uploading = null
                        when (r) {
                            is UploadResult.Uploaded -> flow.request(PrinterAction.StartJob(r.remotePath, item.toolheadMap))
                            is UploadResult.Interrupted -> { problem = "The transfer was interrupted: ${r.reason} Try again."; started = false }
                            is UploadResult.Failed -> { problem = r.reason; started = false }
                        }
                    }
                },
                kind = ButtonKind.PRIMARY, icon = NzIcon.PLAY, enabled = candidate.isFree() && uploading == null && !flow.busy, testTag = "queue-start-${item.id}")
        }
        uploading?.let { ProgressBar(it, label = "Sending to printer") }
        problem?.let { Banner(it, BannerKind.WARNING) }
        if (candidate != null && flow != null) ActionFlowUi(flow, candidate)
    }
}

package com.nozzleitall.desktop.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.history.JobRecord
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.printer.PrinterAction
import java.text.SimpleDateFormat
import java.util.Date

/** The prints this app has watched finish on one printer, newest first, each with a way to run it again. */
@Composable
fun HistoryScreen(state: AppState) {
    val c = Nz.colors
    Column(Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        val entry = PrinterHeader(state, com.nozzleitall.desktop.Destination.HISTORY, "Prints that ended while Nozzle It All was watching.")
            ?: run { NoPrinterYet(state, "this printer's past prints"); return@Column }
        val id = entry.config.identity.id
        val records = state.fleet.history.value.filter { it.printerId == id }
        val caps = entry.capabilities.value
        val flow = rememberActionFlow(entry)
        ActionFlowUi(flow, entry)
        if (records.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                EmptyState(NzIcon.LAYERS, "No prints recorded yet",
                    "When a print finishes, is stopped or fails while Nozzle It All is running, it is listed here, and you get a notification.")
            }
            return@Column
        }
        records.forEach { r ->
            Card(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Txt(r.fileName.substringAfterLast('/'), Nz.type.title, maxLines = 1)
                        Txt(describe(r), Nz.type.bodySmall, c.textMuted)
                    }
                    Pill(when (r.outcome) { "finished" -> "Finished"; "cancelled" -> "Stopped"; else -> "Failed" },
                        when (r.outcome) { "finished" -> Nz.status.finished; "cancelled" -> c.textMuted; else -> c.danger })
                    if (caps?.startPrint == true) NzButton("Print again", { flow.request(PrinterAction.StartJob(r.fileName)) },
                        kind = ButtonKind.SECONDARY, icon = NzIcon.PLAY, enabled = !flow.busy && entry.status.value.state in PrinterAction.StartJob("x").allowedStates,
                        testTag = "reprint-${r.id}")
                }
            }
        }
        Txt("Print again starts the same file from the printer's own storage. If it has been deleted there, the printer will say so.", Nz.type.bodySmall, c.textMuted)
    }
}

private fun describe(r: JobRecord): String {
    val whenText = SimpleDateFormat("EEE d MMM, h:mm a").format(Date(r.endedAtMillis))
    val took = r.elapsedSeconds?.takeIf { it > 0 }?.let { " · " + formatDuration(it) }.orEmpty()
    return whenText + took + (r.totalLayers?.let { " · $it layers" } ?: "")
}

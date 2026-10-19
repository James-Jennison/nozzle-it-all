package com.nozzleitall.desktop.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.Destination
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.printer.PrinterState
import com.nozzleitall.printer.PrinterStatus

/** One running or paused print, as the bar shows it. */
data class JobBarItem(val printerId: String, val printer: String, val file: String, val fraction: Float, val paused: Boolean, val remainingSeconds: Double?)

object JobBarModel {
    /** The prints to show: every printer that is printing or paused with a job, in the order given. */
    fun items(printers: List<Triple<String, String, PrinterStatus>>): List<JobBarItem> = printers.mapNotNull { (id, name, st) ->
        val job = st.job ?: return@mapNotNull null
        if (st.state != PrinterState.PRINTING && st.state != PrinterState.PAUSED) return@mapNotNull null
        JobBarItem(id, name, job.fileName, job.fraction.coerceIn(0f, 1f), st.state == PrinterState.PAUSED, JobEta.remainingSeconds(job.fraction, job.elapsedSeconds))
    }
}

/**
 * A slim bar across the top of every screen while something is printing: which printer, which file, how far, and when it ends.
 * Choosing one opens that printer's page. It is not shown on the printer page it would repeat.
 */
@Composable
fun JobBar(state: AppState) {
    val c = Nz.colors
    val fleet = state.fleet
    val items = JobBarModel.items(fleet.order.value.mapNotNull { id -> fleet.printers[id]?.let { Triple(id, it.config.identity.displayName, it.status.value) } })
        .filterNot { it.printerId == state.selectedPrinter && state.destination in setOf(Destination.MONITOR, Destination.MATERIALS, Destination.HISTORY) }
    if (items.isEmpty()) return
    Row(Modifier.fillMaxWidth().background(c.surface).border(1.dp, c.line).padding(horizontal = 14.dp, vertical = 6.dp).semantics { contentDescription = "Prints in progress" },
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        items.take(3).forEach { it ->
            val shape = RoundedCornerShape(10.dp)
            Row(Modifier.weight(1f, fill = false).clip(shape).background(c.surfaceRaised).clickable(onClickLabel = "Open ${it.printer}") { state.openPrinter(it.printerId) }
                .padding(horizontal = 10.dp, vertical = 5.dp).testTagFor(it.printerId), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(it.printer, Nz.type.label, maxLines = 1)
                Txt(it.file, Nz.type.bodySmall, c.textMuted, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                Box(Modifier.width(90.dp)) { ProgressBar(it.fraction, if (it.paused) Nz.status.paused else c.accent, label = "${it.printer} ${(it.fraction * 100).toInt()} percent") }
                Txt(buildString {
                    append("${(it.fraction * 100).toInt()}%")
                    if (it.paused) append(" · paused")
                    else it.remainingSeconds?.let { r -> append(" · ${formatDuration(r)} left · ends ${JobEta.finishesAt(System.currentTimeMillis(), r)}") }
                }, Nz.type.bodySmall, c.textMuted, maxLines = 1)
            }
        }
        if (items.size > 3) Txt("+${items.size - 3} more", Nz.type.bodySmall, c.textMuted)
    }
}

private fun Modifier.testTagFor(id: String) = semantics { testTag = "job-bar-$id" }

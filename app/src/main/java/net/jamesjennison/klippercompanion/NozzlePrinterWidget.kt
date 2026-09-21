package net.jamesjennison.klippercompanion

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

// Kiln palette constants (see CompanionTheme.kt for the Compose-side originals) - Glance can't
// read MaterialTheme.colorScheme, since widget content renders outside this app's own Compose
// hierarchy entirely (a separate RemoteViews host process), so the widget carries its own copy.
// Teal is reserved for status/progress/interactive affordances only (per the UX council's
// review, 2026-09-21) - it used to also color the bed reading, which both overloaded teal
// across too many meanings and, worse, marked a 50-110C heated bed with the same color used
// for "cool/idle" everywhere else. Bed temperature now renders in neutral WidgetText instead.
private val WidgetBackground = Color(0xFF0E1113)
private val WidgetCard = Color(0xFF14161A)
private val WidgetTeal = Color(0xFF5EEAD4)
private val WidgetEmber = Color(0xFFFB923C)
private val WidgetError = Color(0xFFFB7185) // matches CompanionTheme's error color
private val WidgetPaused = Color(0xFFFBBF24)
private val WidgetText = Color(0xFFEDF2F4)
private val WidgetDim = Color(0xFF9AA5AA)
private val WidgetTrack = Color(0xFF23272C)

internal data class WidgetPrinterStatus(
    val profile: PrinterProfile, val ready: Boolean, val displayState: String, val activeFilename: String, val progress: Float,
    val nozzle: Double?, val bed: Double?, val currentLayer: Int?, val totalLayers: Int?, val printDuration: Double?, val finishAt: String?,
)

private fun isActive(status: WidgetPrinterStatus) = status.ready && status.activeFilename.isNotBlank()

/**
 * Home-screen widget (P18): every saved printer's live status in one widget, narrowed to only
 * the actively printing/paused ones whenever at least one is active (falls back to showing every
 * saved printer when nothing's running, so the widget isn't blank most of the time). An earlier
 * design (per-widget-instance printer selection, via a configuration Activity) was replaced with
 * this after the owner clarified they wanted one widget covering every printer, not one printer
 * per placed instance. Uses Jetpack Glance rather than a classic AppWidgetProvider/RemoteViews
 * layout, to match this app's own all-Compose style (owner decision, 2026-09-20).
 */
class NozzlePrinterWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = context.getSharedPreferences("printer", 0)
        val secrets = CredentialStore.open(context)
        val profiles = try { PrinterPreferences.profiles(prefs, secrets) } catch (_: Exception) { emptyList() }

        // Every printer is polled in parallel, not one after another - with several saved
        // printers a sequential fetch would make the widget's own refresh latency scale with
        // printer count instead of staying roughly constant.
        val statuses = withContext(Dispatchers.IO) {
            coroutineScope { profiles.map { profile -> async { fetchStatus(profile) } }.awaitAll() }
        }
        // Surface only what's actually printing/paused when something is, so a glance at the
        // widget answers "is anything running" without scrolling past idle/offline printers.
        // Falls back to every saved printer when nothing's active, rather than an empty widget
        // most of the time (owner decision, 2026-09-21).
        val activeStatuses = statuses.filter(::isActive)
        val displayed = activeStatuses.ifEmpty { statuses }

        provideContent {
            Column(GlanceModifier.fillMaxWidth().background(WidgetBackground).cornerRadius(20.dp).padding(12.dp)) {
                if (displayed.isEmpty()) {
                    Text("Add a printer in the app", style = TextStyle(color = ColorProvider(WidgetText)))
                } else {
                    // LazyColumn rather than a plain Column: a fixed-height stack of every saved
                    // printer clips silently once it exceeds the launcher's grid allocation (flagged
                    // by every reviewer in the UX council pass, 2026-09-21) - Glance's LazyColumn
                    // scrolls within whatever height the launcher actually gives the widget instead.
                    LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                        itemsIndexed(displayed, itemId = { _, status -> status.profile.address.hashCode().toLong() }) { index, status ->
                            Column(GlanceModifier.fillMaxWidth()) {
                                PrinterRow(context, status)
                                if (index != displayed.lastIndex) Spacer(GlanceModifier.height(8.dp))
                            }
                        }
                    }
                    // A minimum 48dp touch target for the whole row, not just the text glyphs -
                    // the plain teal Text before this was undersized per Android's touch-target
                    // guidance (also flagged by the council pass).
                    Box(
                        modifier = GlanceModifier.fillMaxWidth().height(48.dp).padding(top = 4.dp)
                            .clickable(actionRunCallback<RefreshWidgetAction>()),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text("Refresh", style = TextStyle(color = ColorProvider(WidgetTeal), fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }
    private suspend fun fetchStatus(profile: PrinterProfile): WidgetPrinterStatus {
        val service = printerServiceFor(profile, profile.address)
        return try {
            val snapshot = service.snapshot()
            val metadata = if (snapshot.activeFilename.isNotBlank()) runCatching { service.metadata(snapshot.filename) }.getOrNull() else null
            val finishAt = estimatedFinishClockTime(estimatedRemaining(snapshot, metadata))
            WidgetPrinterStatus(profile, snapshot.ready, snapshot.displayState, snapshot.activeFilename, snapshot.activeProgress,
                snapshot.nozzle, snapshot.bed, snapshot.currentLayer, snapshot.totalLayers, snapshot.printDuration, finishAt)
        } catch (_: Exception) { WidgetPrinterStatus(profile, false, "offline", "", 0f, null, null, null, null, null, null) }
        finally { runCatching { service.close() } }
    }
}

@Composable
private fun PrinterRow(context: Context, status: WidgetPrinterStatus) {
    val printing = isActive(status)
    // Paused and error had no distinct treatment before (both fell into the same teal/plain-text
    // buckets as printing/idle) - flagged by the UX council pass, 2026-09-21, as the two states a
    // monitoring widget most needs to make obvious at a glance.
    val stateColor = when {
        !status.ready -> WidgetDim
        status.displayState == "error" -> WidgetError
        status.displayState == "paused" -> WidgetPaused
        printing -> WidgetTeal
        else -> WidgetText
    }
    val openIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_STAGE_ADDRESS, status.profile.address)
    }
    Column(GlanceModifier.fillMaxWidth().background(WidgetCard).cornerRadius(14.dp).padding(10.dp).clickable(actionStartActivity(openIntent))) {
        Row(GlanceModifier.fillMaxWidth()) {
            Text(status.profile.label, style = TextStyle(color = ColorProvider(WidgetText), fontWeight = FontWeight.Bold))
        }
        Text(if (status.ready) status.displayState.replaceFirstChar { it.titlecase() } else "Offline", style = TextStyle(color = ColorProvider(stateColor)))
        if (printing) {
            Text(status.activeFilename, style = TextStyle(color = ColorProvider(WidgetDim)))
            Spacer(GlanceModifier.height(4.dp))
            LinearProgressIndicator(progress = status.progress, modifier = GlanceModifier.fillMaxWidth().height(6.dp).cornerRadius(3.dp),
                color = ColorProvider(WidgetTeal), backgroundColor = ColorProvider(WidgetTrack))
            Spacer(GlanceModifier.height(4.dp))
            Text("${(status.progress * 100).toInt()}%" + layerSuffix(status.currentLayer, status.totalLayers), style = TextStyle(color = ColorProvider(WidgetText)))
            status.finishAt?.let { Text("Done at $it", style = TextStyle(color = ColorProvider(WidgetDim))) }
        }
        if (status.ready) Row(GlanceModifier.padding(top = 2.dp)) {
            // Ember for nozzle stays consistent with Temperature() in MainActivity.kt. Bed no
            // longer reuses teal here (see the palette comment at the top of this file) - it's
            // neutral text instead, since teal already carries status/progress/refresh meaning
            // elsewhere on this same card.
            Text("Nozzle ${temp(status.nozzle)}", style = TextStyle(color = ColorProvider(WidgetEmber)))
            Text("  ·  ", style = TextStyle(color = ColorProvider(WidgetDim)))
            Text("Bed ${temp(status.bed)}", style = TextStyle(color = ColorProvider(WidgetText)))
        }
    }
}
private fun temp(v: Double?) = v?.let { "%.0f°C".format(it) } ?: "—"
private fun layerSuffix(current: Int?, total: Int?) = if (current != null && total != null) " · layer $current/$total" else ""

class NozzlePrinterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NozzlePrinterWidget()
}

class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        NozzlePrinterWidget().update(context, glanceId)
    }
}

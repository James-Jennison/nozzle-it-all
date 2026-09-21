package net.jamesjennison.klippercompanion

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which saved printer a specific placed widget instance shows - keyed by the platform
 * appWidgetId, since multiple instances can each show a different printer (set from
 * NozzlePrinterWidgetConfigActivity when the widget is placed). Falls back to the favorited/
 * first saved printer - NozzlePrinterWidget's original v1 behavior - for a widget with no stored
 * selection: one placed before this existed, or whose selected printer was later forgotten. */
object WidgetPrinterSelection {
    private fun key(appWidgetId: Int) = "widget:$appWidgetId"
    fun get(prefs: SharedPreferences, appWidgetId: Int): String? = prefs.getString(key(appWidgetId), null)
    fun set(prefs: SharedPreferences, appWidgetId: Int, address: String) { prefs.edit().putString(key(appWidgetId), address).apply() }
    fun remove(prefs: SharedPreferences, appWidgetId: Int) { prefs.edit().remove(key(appWidgetId)).apply() }
}

/**
 * Home-screen widget (P18): at-a-glance status for a saved printer, chosen per widget instance
 * via NozzlePrinterWidgetConfigActivity when placed (falls back to the favorited/first profile if
 * never configured). Uses Jetpack Glance rather than a classic AppWidgetProvider/RemoteViews
 * layout, to match this app's own all-Compose style (owner decision, 2026-09-20).
 */
class NozzlePrinterWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = context.getSharedPreferences("printer", 0)
        val secrets = CredentialStore.open(context)
        val profiles = try { PrinterPreferences.profiles(prefs, secrets) } catch (_: Exception) { emptyList() }
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val widgetPrefs = context.getSharedPreferences("widget_printers", 0)
        val selected = WidgetPrinterSelection.get(widgetPrefs, appWidgetId)
        val profile = profiles.firstOrNull { it.address == selected } ?: profiles.firstOrNull { it.favorite } ?: profiles.firstOrNull()

        val status = if (profile == null) null else withContext(Dispatchers.IO) {
            val service = printerServiceFor(profile, profile.address)
            try {
                val snapshot = service.snapshot()
                // Only relevant while actively printing (estimatedRemaining itself returns null
                // otherwise), so the extra metadata round-trip is skipped for every other state.
                val metadata = if (snapshot.activeFilename.isNotBlank()) runCatching { service.metadata(snapshot.filename) }.getOrNull() else null
                val finishAt = estimatedFinishClockTime(estimatedRemaining(snapshot, metadata))
                WidgetStatus(profile, snapshot.ready, snapshot.displayState, snapshot.activeFilename, snapshot.activeProgress,
                    snapshot.nozzle, snapshot.bed, snapshot.currentLayer, snapshot.totalLayers, snapshot.printDuration, finishAt)
            } catch (_: Exception) { WidgetStatus(profile, false, "offline", "", 0f, null, null, null, null, null, null) }
            finally { runCatching { service.close() } }
        }

        provideContent {
            Column(GlanceModifier.fillMaxSize().background(Color(0xFF14161A)).padding(12.dp)) {
                if (status == null) {
                    Text("Add a printer in the app", style = TextStyle(color = ColorProvider(Color(0xFFEDF2F4))))
                } else {
                    val openIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(MainActivity.EXTRA_STAGE_ADDRESS, status.profile.address)
                    }
                    Column(GlanceModifier.fillMaxSize().clickable(actionStartActivity(openIntent))) {
                        Text(status.profile.label, style = TextStyle(color = ColorProvider(Color(0xFFEDF2F4)), fontWeight = FontWeight.Bold))
                        Text(if (status.ready) status.displayState.replaceFirstChar { it.titlecase() } else "Offline",
                            style = TextStyle(color = ColorProvider(Color(0xFF5EEAD4))))
                        if (status.ready && status.activeFilename.isNotBlank()) {
                            Text(status.activeFilename, style = TextStyle(color = ColorProvider(Color(0xFF9AA5AA))))
                            Text("${(status.progress * 100).toInt()}%" + layerSuffix(status.currentLayer, status.totalLayers),
                                style = TextStyle(color = ColorProvider(Color(0xFFEDF2F4))))
                            status.printDuration?.let { Text("Elapsed ${formatDuration(it)}", style = TextStyle(color = ColorProvider(Color(0xFF9AA5AA)))) }
                            status.finishAt?.let { Text("Done at $it", style = TextStyle(color = ColorProvider(Color(0xFF9AA5AA)))) }
                        }
                        if (status.ready) Text(temperatureLine(status.nozzle, status.bed), style = TextStyle(color = ColorProvider(Color(0xFF9AA5AA))))
                    }
                    Row(GlanceModifier.padding(top = 8.dp)) {
                        Text("Refresh", modifier = GlanceModifier.clickable(actionRunCallback<RefreshWidgetAction>()),
                            style = TextStyle(color = ColorProvider(Color(0xFF5EEAD4))))
                    }
                }
            }
        }
    }
    private fun layerSuffix(current: Int?, total: Int?) = if (current != null && total != null) " · layer $current/$total" else ""
    private fun temperatureLine(nozzle: Double?, bed: Double?): String {
        fun temp(v: Double?) = v?.let { "%.0f°C".format(it) } ?: "—"
        return "Nozzle ${temp(nozzle)} · Bed ${temp(bed)}"
    }
    private data class WidgetStatus(val profile: PrinterProfile, val ready: Boolean, val displayState: String, val activeFilename: String, val progress: Float,
        val nozzle: Double?, val bed: Double?, val currentLayer: Int?, val totalLayers: Int?, val printDuration: Double?, val finishAt: String?)
}

class NozzlePrinterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NozzlePrinterWidget()
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val widgetPrefs = context.getSharedPreferences("widget_printers", 0)
        appWidgetIds.forEach { WidgetPrinterSelection.remove(widgetPrefs, it) }
    }
}

class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        NozzlePrinterWidget().update(context, glanceId)
    }
}

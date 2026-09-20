package net.jamesjennison.klippercompanion

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
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

/**
 * Home-screen widget (P18): at-a-glance status for one saved printer - the favorited profile, or
 * else the first saved one. No per-widget-instance configuration in this v1 (every placed widget
 * shows the same printer); multi-printer/configurable widgets are a documented follow-up, not
 * built here. Uses Jetpack Glance rather than a classic AppWidgetProvider/RemoteViews layout, to
 * match this app's own all-Compose style (owner decision, 2026-09-20).
 */
class NozzlePrinterWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = context.getSharedPreferences("printer", 0)
        val secrets = CredentialStore.open(context)
        val profiles = try { PrinterPreferences.profiles(prefs, secrets) } catch (_: Exception) { emptyList() }
        val profile = profiles.firstOrNull { it.favorite } ?: profiles.firstOrNull()

        val status = if (profile == null) null else withContext(Dispatchers.IO) {
            val service = printerServiceFor(profile, profile.address)
            try {
                val snapshot = service.snapshot()
                WidgetStatus(profile, snapshot.ready, snapshot.displayState, snapshot.activeFilename, snapshot.activeProgress)
            } catch (_: Exception) { WidgetStatus(profile, false, "offline", "", 0f) }
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
                            Text("${(status.progress * 100).toInt()}%", style = TextStyle(color = ColorProvider(Color(0xFFEDF2F4))))
                        }
                    }
                    Row(GlanceModifier.padding(top = 8.dp)) {
                        Text("Refresh", modifier = GlanceModifier.clickable(actionRunCallback<RefreshWidgetAction>()),
                            style = TextStyle(color = ColorProvider(Color(0xFF5EEAD4))))
                    }
                }
            }
        }
    }
    private data class WidgetStatus(val profile: PrinterProfile, val ready: Boolean, val displayState: String, val activeFilename: String, val progress: Float)
}

class NozzlePrinterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NozzlePrinterWidget()
}

class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        NozzlePrinterWidget().update(context, glanceId)
    }
}

package net.jamesjennison.klippercompanion

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.launch

/**
 * Shown by the system when a NozzlePrinterWidget instance is placed (declared as this widget's
 * android:configure target in nozzle_widget_info.xml). Lets the owner pick which saved printer
 * that specific instance shows, so placing two widgets can show two different printers - the
 * widget itself falls back to the favorited/first saved printer if this is ever skipped or the
 * selection later goes stale (see WidgetPrinterSelection).
 */
class NozzlePrinterWidgetConfigActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Placing a widget is cancelled unless this activity explicitly returns RESULT_OK -
        // set the cancelled result first so backing out (or any early return below) doesn't
        // leave a half-placed widget.
        setResult(Activity.RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        setContent {
            CompanionTheme {
                val scope = rememberCoroutineScope()
                val context = androidx.compose.ui.platform.LocalContext.current
                val prefs = remember { getSharedPreferences("printer", 0) }
                val secrets = remember { CredentialStore.open(context) }
                val profiles = remember { runCatching { PrinterPreferences.profiles(prefs, secrets) }.getOrDefault(emptyList()) }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Choose a printer for this widget", style = MaterialTheme.typography.titleLarge)
                        if (profiles.isEmpty()) Text("No saved printers yet. Add one in the app first, then place the widget again.")
                        profiles.forEach { profile ->
                            OutlinedButton({
                                val widgetPrefs = getSharedPreferences("widget_printers", 0)
                                WidgetPrinterSelection.set(widgetPrefs, appWidgetId, profile.address)
                                scope.launch {
                                    val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
                                    runCatching { NozzlePrinterWidget().update(context, glanceId) }
                                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                                    finish()
                                }
                            }, Modifier.fillMaxWidth().testTag("widget-config-printer:${profile.address}")) { Text(profile.label) }
                        }
                        TextButton({ finish() }, Modifier.testTag("widget-config-cancel")) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

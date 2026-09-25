package net.jamesjennison.klippercompanion

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Whether the one-time welcome has been dealt with (finished OR skipped). Never shown again once set. */
object OnboardingPrefs {
    private const val FILE = "onboarding"; private const val KEY = "done"
    fun isDone(context: Context): Boolean = runCatching { context.getSharedPreferences(FILE, 0).getBoolean(KEY, false) }.getOrDefault(true) // if prefs are unreadable, never trap the user
    /** [addPrinter] false (Skip / Explore first) also stops the Add Printer wizard auto-opening on every later cold start. */
    fun markDone(context: Context, addPrinter: Boolean = true) {
        runCatching { context.getSharedPreferences(FILE, 0).edit().putBoolean(KEY, true).putBoolean(SUPPRESS_WIZARD, !addPrinter || wizardSuppressed(context)).apply() }
    }
    private const val SUPPRESS_WIZARD = "suppress_wizard"
    fun wizardSuppressed(context: Context): Boolean = runCatching { context.getSharedPreferences(FILE, 0).getBoolean(SUPPRESS_WIZARD, false) }.getOrDefault(false)
}

data class OnboardingPage(val title: String, val body: String)

val onboardingPages = listOf(
    OnboardingPage("Print from your pocket", "Slice models right on your phone. No computer needed."),
    OnboardingPage("Control your printers", "Works with Klipper, PrusaLink, OctoPrint and Bambu Lab printers. Setup can scan your network for them."),
    OnboardingPage("Your data stays yours", "Printers are reached over your own network. Alerts are optional and asked for later."),
)

/**
 * Short first-run welcome. Skip is always one tap and never blocks the app.
 * [onFinish] gets true when the user chose to add a printer now, false when they chose to explore first or skipped.
 */
@Composable fun OnboardingScreen(onFinish: (addPrinter: Boolean) -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val page = onboardingPages[index]
    val last = index == onboardingPages.lastIndex
    Surface(Modifier.fillMaxSize().testTag("onboarding"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ onFinish(false) }, modifier = Modifier.testTag("onboarding-skip")) { Text("Skip") }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).widthIn(max = 560.dp).align(Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(page.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.testTag("onboarding-title").semantics { heading() })
                Spacer(Modifier.height(16.dp))
                Text(page.body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.testTag("onboarding-body"))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp).semantics { contentDescription = "Page ${index + 1} of ${onboardingPages.size}" },
                horizontalArrangement = Arrangement.Center) {
                onboardingPages.indices.forEach { i ->
                    Box(Modifier.padding(4.dp).size(if (i == index) 10.dp else 8.dp).background(
                        if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
                }
            }
            if (last) {
                Button({ onFinish(true) }, modifier = Modifier.fillMaxWidth().testTag("onboarding-add-printer")) { Text("Add my printer") }
                TextButton({ onFinish(false) }, modifier = Modifier.fillMaxWidth().testTag("onboarding-explore")) { Text("Explore first") }
            } else {
                Button({ index++ }, modifier = Modifier.fillMaxWidth().testTag("onboarding-next")) { Text("Next") }
            }
        }
    }
}

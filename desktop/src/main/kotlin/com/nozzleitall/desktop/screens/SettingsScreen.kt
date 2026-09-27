package com.nozzleitall.desktop.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.PrinterStore
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.printer.Glossary
import com.nozzleitall.printer.external.AdapterAccount
import com.nozzleitall.printer.external.AdapterAccountState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(state: AppState) {
    val c = Nz.colors
    val fleet = state.fleet
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionHeader("Settings")
        Card(Modifier.fillMaxWidth()) {
            Txt("Appearance", Nz.type.title)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "Match system", "dark" to "Dark", "light" to "Light").forEach { (k, label) ->
                    NzButton(label, {
                        fleet.settings.value = fleet.settings.value.copy(theme = k)
                        PrinterStore.atomicWrite(state.paths.settings, fleet.settings.value.toJson().toString(2), private = false)
                    }, kind = if (fleet.settings.value.theme == k) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Txt("Away from home", Nz.type.title)
            Txt(Glossary.explanations.getValue("remote-access"), Nz.type.body, c.textMuted)
        }
        Card(Modifier.fillMaxWidth()) {
            Txt("Stock U1 support (optional)", Nz.type.title)
            Txt(Glossary.explanations.getValue("stock-u1"), Nz.type.body, c.textMuted)
            val installed = fleet.stockHelperInstalled
            if (!installed) Banner("Stock U1 support isn't installed on this computer. PAXX printers don't need it.", BannerKind.INFO)
            Toggle("Use Stock U1 support", fleet.settings.value.stockU1Enabled, { fleet.setStockEnabled(it) },
                if (installed) "Starts only when a Stock U1 printer is opened." else "Install nozzle-stock-u1-adapter to turn this on.")
            if (fleet.settings.value.stockU1Enabled && installed) StockAccountPanel(state)
        }
        ConnectorPanel(state)
        Card(Modifier.fillMaxWidth()) {
            Txt("Your data", Nz.type.title)
            Txt(Glossary.explanations.getValue("cloud"), Nz.type.body, c.textMuted)
            listOf("Settings and printers" to state.paths.config, "Projects" to state.paths.projects, "Advanced Workspace data" to state.paths.workspaceProfile, "Logs" to state.paths.logs)
                .forEach { (label, dir) -> Row { Txt(label, Nz.type.body, modifier = Modifier.width(220.dp)); Txt(dir.absolutePath, Nz.type.metricSmall, c.textMuted) } }
            Txt("Nozzle It All keeps its own folders and never reads or changes OrcaSlicer or Snapmaker Orca settings.", Nz.type.bodySmall, c.textMuted)
        }
        Card(Modifier.fillMaxWidth()) {
            Txt("About", Nz.type.title)
            Txt("${Glossary.PRODUCT_DESKTOP} ${state.version}", Nz.type.body)
            Txt("Free software under the GNU AGPL 3.0 or later. The Advanced Workspace and slicing engine are built from OrcaSlicer and Snapmaker Orca (AGPL-3.0), " +
                "which build on PrusaSlicer and Bambu Studio. Nozzle It All is not affiliated with Snapmaker, OrcaSlicer, Prusa Research or Bambu Lab.", Nz.type.bodySmall, c.textMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NzButton("Source code", { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI("https://github.com/James-Jennison/nozzle-it-all")) } }, kind = ButtonKind.QUIET)
                NzButton("Licences", { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI("https://nozzleitall.com/open-source/")) } }, kind = ButtonKind.QUIET)
            }
        }
    }
}

@Composable
private fun ConnectorPanel(state: AppState) {
    val c = Nz.colors
    var on by remember { mutableStateOf(state.connector.running) }
    var code by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Txt("Nozzle It All Web on this computer", Nz.type.title)
        Txt("Browsers limit what a web page can reach on your network. Turn this on to let Nozzle It All Web, open in a browser on this computer, " +
            "use the printers you've added here. It only listens on this computer, only answers Nozzle's own web app, and only after you pair the browser.", Nz.type.body, c.textMuted)
        problem?.let { Banner(it, BannerKind.WARNING) }
        Toggle("Allow Nozzle It All Web to use these printers", on, { v ->
            problem = null
            if (v) runCatching { state.connector.start() }.onFailure { problem = "Couldn't start: ${it.message}. Another program may be using port ${state.connector.port}." }
            else { state.connector.stop(); code = null }
            on = state.connector.running
        })
        if (on) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NzButton("Pair a browser", { code = state.connector.newPairingCode() }, kind = ButtonKind.SECONDARY)
            NzButton("Forget paired browsers (${state.connector.pairedCount})", { state.connector.forgetAll(); code = null }, kind = ButtonKind.QUIET)
        }
        code?.let { Txt("Pairing code: $it", Nz.type.metric); Txt("Enter it in Nozzle It All Web under Settings within two minutes. It works once.", Nz.type.bodySmall, c.textMuted) }
    }
}

@Composable
private fun StockAccountPanel(state: AppState) {
    val c = Nz.colors
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf<AdapterAccount?>(null) }
    var pasted by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val client = state.fleet.stockAccount
    LaunchedEffect(client) { account = client?.let { a -> withContext(Dispatchers.IO) { runCatching { a.account() }.onFailure { problem = it.message }.getOrNull() } } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt("Snapmaker account", Nz.type.label)
        problem?.let { Banner("Stock U1 support isn't responding: $it. PAXX printers are unaffected.", BannerKind.WARNING) }
        val a = account
        when (a?.state) {
            AdapterAccountState.SIGNED_IN -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(a.detail, Nz.type.body, modifier = Modifier.weight(1f))
                NzButton("Sign out", { scope.launch { account = withContext(Dispatchers.IO) { client?.signOut() } } }, kind = ButtonKind.SECONDARY)
            }
            null -> Txt("Checking…", Nz.type.body, c.textMuted)
            else -> {
                Txt(a.detail.ifBlank { "Not signed in." }, Nz.type.body, c.textMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NzButton("Sign in with your browser", { scope.launch {
                        account = withContext(Dispatchers.IO) { client?.beginSignIn() }
                        account?.signInUrl?.let { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(it)) } }
                    } }, kind = ButtonKind.SECONDARY)
                }
                if (a.state == AdapterAccountState.SIGNING_IN || a.state == AdapterAccountState.EXPIRED) {
                    Field("Sign-in token from Snapmaker's page", pasted, { pasted = it }, secret = true,
                        hint = "After signing in, Snapmaker shows a page with your sign-in details. Copy all of it and paste it here.")
                    NzButton("Finish signing in", { scope.launch { account = withContext(Dispatchers.IO) { client?.completeSignIn(pasted) }; pasted = "" } },
                        kind = ButtonKind.PRIMARY, enabled = pasted.isNotBlank())
                }
            }
        }
    }
}

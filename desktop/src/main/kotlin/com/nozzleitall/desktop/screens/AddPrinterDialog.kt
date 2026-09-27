package com.nozzleitall.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.PrinterStore
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.printer.*
import kotlinx.coroutines.launch

/**
 * Adding a printer: type its address (or its private-network address), Nozzle probes it on the LAN only, shows what it
 * found and why, and lets the user correct the firmware before saving. A stock-firmware U1 is routed to the optional
 * Stock U1 support, which is explained, never silently enabled.
 */
@Composable
fun AddPrinterDialog(state: AppState, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var probing by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<DiscoveredPrinter?>(null) }
    var firmware by remember { mutableStateOf(FirmwareFamily.PAXX) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

    fun probe() {
        if (address.isBlank()) { error = "Enter the printer's address."; return }
        probing = true; error = null; found = null
        scope.launch {
            val result = runCatching { state.fleet.probe(address.trim()) }.getOrDefault(emptyList()).firstOrNull()
            probing = false
            if (result == null) error = "No printer answered at ${address.trim()}. Check the address, that the printer is on, and that this computer is on the same network (or your private network)."
            else { found = result; firmware = result.suggestedFirmware; if (name.isBlank()) name = result.model }
        }
    }

    DialogWindow(onCloseRequest = onDone, title = "Add printer", state = rememberDialogState(width = 560.dp, height = 640.dp),
        onPreviewKeyEvent = { if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onDone(); true } else false }) {
        NozzleTheme(state.fleet.settings.value.theme) {
            val c = Nz.colors
            Column(Modifier.fillMaxSize().background(c.surface).padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Txt("Add printer", Nz.type.headline)
                Txt("Nozzle It All connects directly to your printer. Nothing goes through a Nozzle server.", Nz.type.body, c.textMuted)
                Field("Printer address", address, { address = it; found = null }, placeholder = "192.168.1.40 or u1.your-tailnet.ts.net",
                    hint = "At home, use the printer's local address. Away from home, use its address on your private network (for example Tailscale).",
                    error = error, focusRequester = focus, onSubmit = ::probe)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NzButton(if (probing) "Looking…" else "Find printer", ::probe, kind = ButtonKind.PRIMARY, enabled = !probing, testTag = "probe")
                }
                found?.let { f ->
                    Card(raised = true) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(NzIcon.CHECK, Nz.status.ready, 20.dp)
                            Txt("Found ${f.model}", Nz.type.title)
                        }
                        RouteBadge(f.route)
                        Txt(f.evidence, Nz.type.bodySmall, c.textMuted)
                        Txt("Firmware", Nz.type.label)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FirmwareFamily.entries.forEach { fw ->
                                NzButton(fw.label, { firmware = fw }, kind = if (firmware == fw) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
                            }
                        }
                        Txt(Glossary.term(firmware.glossaryId).description, Nz.type.bodySmall, c.textMuted)
                        if (firmware == FirmwareFamily.STOCK_U1) Banner(
                            if (!state.fleet.stockHelperInstalled) "Stock U1 printers need the optional Stock U1 support, which isn't installed. You can still save this printer; it will stay offline until Stock U1 support is installed and turned on."
                            else if (!state.fleet.settings.value.stockU1Enabled) "Stock U1 printers use optional Stock U1 support. Turn it on in Settings to connect. Your PAXX printers never need it."
                            else Glossary.explanations.getValue("stock-u1"), BannerKind.INFO)
                    }
                    Field("Name", name, { name = it }, placeholder = "Workshop U1")
                    Field("API key (only if your printer requires one)", apiKey, { apiKey = it }, secret = true,
                        hint = "Stored only on this computer, in a file only you can read.")
                }
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    NzButton("Cancel", onDone, kind = ButtonKind.QUIET)
                    NzButton("Add printer", {
                        val f = found ?: return@NzButton
                        val adapter = if (firmware == FirmwareFamily.STOCK_U1) "stock-u1" else "paxx-lan"
                        state.fleet.add(PrinterConfig(PrinterIdentity(PrinterStore.newId(), name.ifBlank { f.model }, f.model, firmware, f.address), adapter, apiKey.trim()))
                        onDone()
                    }, kind = ButtonKind.PRIMARY, enabled = found != null, testTag = "save-printer")
                }
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }
}

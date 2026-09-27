package com.nozzleitall.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.nozzleitall.desktop.AppState
import com.nozzleitall.desktop.EXPORT_ONLY
import com.nozzleitall.desktop.PrinterStore
import com.nozzleitall.desktop.prepare.ProfileCatalog
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.printer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What each family asks for. Everything else about a printer comes from its adapter's reported capabilities. */
private data class FamilyForm(val needsAddress: Boolean = true, val canProbe: Boolean = false, val secretLabel: String? = null, val secretRequired: Boolean = false,
                              val needsSerial: Boolean = false, val note: String? = null, val defaultProfile: String? = null)

private fun formFor(f: PrinterFamily) = when (f) {
    PrinterFamily.PAXX_U1, PrinterFamily.STOCK_U1 -> FamilyForm(canProbe = true, secretLabel = "API key (only if your printer requires one)", defaultProfile = "snapmaker_u1")
    PrinterFamily.KLIPPER -> FamilyForm(canProbe = true, secretLabel = "API key (only if Moonraker requires one)", defaultProfile = "generic_klipper")
    PrinterFamily.OCTOPRINT -> FamilyForm(secretLabel = "OctoPrint API key", secretRequired = true, note = "Create an API key in OctoPrint's settings (Application Keys) and paste it here.")
    PrinterFamily.PRUSA -> FamilyForm(secretLabel = "PrusaLink password", secretRequired = true, defaultProfile = "prusa_generic",
        note = "Find the PrusaLink password on the printer's screen under Settings → Network → PrusaLink.")
    PrinterFamily.BAMBU_LAB -> FamilyForm(secretLabel = "Access code", secretRequired = true, needsSerial = true, defaultProfile = "bambu_generic",
        note = "Turn on LAN mode on the printer (and Developer Mode on current firmware), then copy its access code and serial number from the printer's screen.")
    PrinterFamily.EXPORT_ONLY -> FamilyForm(needsAddress = false)
    else -> FamilyForm()
}

@Composable
fun AddPrinterDialog(state: AppState, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val fleet = state.fleet
    // Families this installation can actually connect to, from the adapters it ships; plus the optional Stock U1 support and export-only.
    val families = remember { (fleet.registry.builtInIds.flatMap { fleet.registry.adapter(it).families } + PrinterFamily.STOCK_U1 + PrinterFamily.EXPORT_ONLY)
        .distinct().sortedBy { PrinterFamily.known.indexOf(it).let { i -> if (i < 0) 99 else i } } }
    var family by remember { mutableStateOf(families.first()) }
    val form = formFor(family)
    var address by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var serial by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var profileId by remember(family) { mutableStateOf(formFor(family).defaultProfile ?: ProfileCatalog.DEFAULT_ID) }
    var profileQuery by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<DiscoveredPrinter?>(null) }
    var checked by remember { mutableStateOf<PrinterStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun adapterId(f: PrinterFamily) = if (f == PrinterFamily.STOCK_U1) "stock-u1" else if (f == PrinterFamily.EXPORT_ONLY) EXPORT_ONLY
        else fleet.registry.builtInIds.firstOrNull { f in fleet.registry.adapter(it).families } ?: ""

    fun draft() = PrinterConfig(PrinterIdentity(PrinterStore.newId(), name.ifBlank { ProfileCatalog.byId(profileId)?.model ?: family.label },
        model.ifBlank { found?.model ?: ProfileCatalog.byId(profileId)?.model ?: family.label }, found?.suggestedFamily ?: family, found?.address ?: address.trim(), profileId),
        adapterId(found?.suggestedFamily ?: family), secret.trim(), if (serial.isNotBlank()) mapOf("serial" to serial.trim()) else emptyMap())

    fun check() {
        if (form.needsAddress && address.isBlank()) { error = "Enter the printer's address."; return }
        busy = true; error = null; found = null; checked = null
        scope.launch {
            if (form.canProbe) {
                val r = runCatching { fleet.probe(address.trim()) }.getOrDefault(emptyList()).firstOrNull()
                if (r == null) error = "No printer answered at ${address.trim()}. Check the address, that the printer is on, and that this computer is on the same network (or your private network)."
                else { found = r; family = r.suggestedFamily; if (name.isBlank()) name = r.model }
            } else {
                // No discovery for this family: open a session with what the user typed and read its status once. Read-only.
                val cfg = draft()
                checked = withContext(Dispatchers.IO) { runCatching { fleet.registry.open(cfg).use { it.status() } }.getOrElse { PrinterStatus(PrinterState.OFFLINE, ConnectionRoute.LAN, message = it.message) } }
                if (checked!!.state == PrinterState.OFFLINE || checked!!.state == PrinterState.ERROR) error = checked!!.message ?: "The printer didn't answer."
            }
            busy = false
        }
    }

    DialogWindow(onCloseRequest = onDone, title = "Add printer", state = rememberDialogState(width = 640.dp, height = 760.dp),
        onPreviewKeyEvent = { if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onDone(); true } else false }) {
        NozzleTheme(fleet.settings.value.theme) {
            val c = Nz.colors
            Column(Modifier.fillMaxSize().background(c.surface).padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Txt("Add printer", Nz.type.headline)
                Txt("Nozzle It All connects directly to your printer. Nothing goes through a Nozzle server.", Nz.type.body, c.textMuted)
                Txt("What kind of printer?", Nz.type.label)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    families.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { f -> NzButton(f.label, { family = f; found = null; checked = null; error = null }, kind = if (family == f) ButtonKind.PRIMARY else ButtonKind.SECONDARY) }
                    } }
                }
                Txt(Glossary.terms[family.glossaryId]?.description ?: "", Nz.type.bodySmall, c.textMuted)
                form.note?.let { Banner(it, BannerKind.INFO) }
                if (family == PrinterFamily.STOCK_U1) Banner(
                    if (!fleet.stockHelperInstalled) "Stock U1 printers use optional Stock U1 support, which isn't installed. You can still save this printer; it stays offline until that support is installed and turned on."
                    else if (!fleet.settings.value.stockU1Enabled) "Stock U1 printers use optional Stock U1 support. Turn it on in Settings to connect. Other printers never need it."
                    else Glossary.explanations.getValue("stock-u1"), BannerKind.INFO)
                if (form.needsAddress) {
                    Field("Printer address", address, { address = it; found = null; checked = null }, placeholder = "192.168.1.40 or printer.your-tailnet.ts.net",
                        hint = "At home, the printer's local address. Away from home, its address on your private network (for example Tailscale).", error = error, onSubmit = ::check)
                    if (form.needsSerial) Field("Serial number", serial, { serial = it })
                    form.secretLabel?.let { Field(it, secret, { v -> secret = v }, secret = true, hint = "Stored only on this computer, in a file only you can read.") }
                    NzButton(if (busy) "Checking…" else if (form.canProbe) "Find printer" else "Check connection", ::check, kind = ButtonKind.SECONDARY,
                        enabled = !busy && (!form.secretRequired || secret.isNotBlank()) && (!form.needsSerial || serial.isNotBlank()), testTag = "probe")
                    found?.let { f -> Card(raised = true) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(NzIcon.CHECK, Nz.status.ready, 20.dp); Txt("Found ${f.model}", Nz.type.title) }
                        RouteBadge(f.route); Txt(f.evidence, Nz.type.bodySmall, c.textMuted)
                    } }
                    checked?.takeIf { it.state != PrinterState.OFFLINE && it.state != PrinterState.ERROR }?.let { s -> Banner("Connected: ${s.state.label}.", BannerKind.SUCCESS) }
                    Field("Model", model, { model = it }, placeholder = "Snapmaker U1, Prusa MK4S, Bambu Lab P1S…")
                }
                Field("Name", name, { name = it }, placeholder = "Workshop printer")
                // The slicing profile is independent of the connection: any printer can be sliced for, connected or not.
                Txt("Slicing profile", Nz.type.label)
                Field("Search printer models", profileQuery, { profileQuery = it }, placeholder = "Snapmaker, Prusa, Bambu, Voron…")
                val matches = remember(profileQuery) { ProfileCatalog.search(profileQuery).take(8) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    matches.forEach { p -> NzButton("${p.vendor} · ${p.model}", { profileId = p.id }, kind = if (profileId == p.id) ButtonKind.PRIMARY else ButtonKind.QUIET) }
                }
                Txt("Selected: ${ProfileCatalog.byId(profileId)?.name ?: profileId}", Nz.type.bodySmall, c.textMuted)
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    NzButton("Cancel", onDone, kind = ButtonKind.QUIET)
                    val ready = when {
                        family == PrinterFamily.EXPORT_ONLY -> true
                        form.canProbe -> found != null || family == PrinterFamily.STOCK_U1 && address.isNotBlank()
                        else -> address.isNotBlank() && (!form.secretRequired || secret.isNotBlank()) && (!form.needsSerial || serial.isNotBlank())
                    }
                    NzButton("Add printer", { fleet.add(draft()); onDone() }, kind = ButtonKind.PRIMARY, enabled = ready, testTag = "save-printer")
                }
            }
        }
    }
}

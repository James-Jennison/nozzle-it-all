package net.jamesjennison.klippercompanion

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable fun ProfileEditor(profile: PrinterProfile, close: ()->Unit, save: (String,String,String,String,PrinterKind,String,SlicingPrinterModel?)->String?, detectFirmware: ((String, (Result<FirmwareIdentity>)->Unit)->Unit)? = null, setCustomMachine: (String, CustomMachine?) -> Unit = { _, _ -> }, detectLanes: ((String, (Result<Int>)->Unit)->Unit)? = null) {
    var name by remember(profile) { mutableStateOf(profile.name) }
    var address by remember(profile) { mutableStateOf(profile.address) }
    // For a BAMBU_LAB profile this same field holds the access code from the printer's own screen -
    // same class of secret (a control-granting credential), so it uses the same encrypted slot.
    var apiKey by remember(profile) { mutableStateOf(profile.apiKey) }
    var serial by remember(profile) { mutableStateOf(profile.serial) }
    var kind by remember(profile) { mutableStateOf(profile.kind) }
    var slicingModel by remember(profile) { mutableStateOf(profile.slicingModel) }
    var customMachine by remember(profile) { mutableStateOf(profile.customMachine) }
    var customMachineError by remember(profile) { mutableStateOf<String?>(null) }
    var showKey by remember(profile) { mutableStateOf(false) }
    var showRemoteHelp by remember(profile) { mutableStateOf(false) }
    var error by remember(profile) { mutableStateOf<String?>(null) }
    // WO-13: local-only until Save is pressed, same as every other field here - detecting new
    // firmware doesn't persist anything by itself (PrinterModel.detectFirmware does that, only
    // once this dialog's own Save is confirmed via the slicingModel captured below).
    var detecting by remember(profile) { mutableStateOf(false) }
    var detectedVersion by remember(profile) { mutableStateOf(profile.declaredFirmwareVersion) }
    var detectNote by remember(profile) { mutableStateOf("") }
    // Runs automatically rather than waiting for a manual tap: the owner correctly pointed out
    // that requiring "Detect firmware now" as a separate step is an easy one to forget, and
    // there's no reason to wait when this profile is already reachable at its current address.
    // Still re-triggerable by hand below (the button stays) for the case this fires before the
    // printer is actually reachable, or the owner just wants to re-confirm after a firmware update.
    LaunchedEffect(slicingModel, address) {
        if(ElegooProfiles.isCosmos(slicingModel) && detectFirmware!=null && !detecting) {
            detecting=true;detectNote=""
            detectFirmware(profile.address) { result ->
                detecting=false
                result.onSuccess { detectedVersion=it.version; detectNote="Confirmed: ${it.app.ifBlank{"unknown firmware"}} ${it.version}" }
                result.onFailure { detectNote=it.message ?: "Could not read firmware. Is the printer connected?" }
            }
        }
    }
    if(showRemoteHelp) RemoteAccessHelpPanel { showRemoteHelp=false }
    AlertDialog(onDismissRequest=close,title={Text("Edit printer")},text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(name,{name=it.take(80)},label={Text("Printer name")},singleLine=true)
        if(kind==PrinterKind.BAMBU_LAB) {
            OutlinedTextField(address,{address=it},label={Text("Printer IP address")},placeholder={Text("192.168.1.50")},singleLine=true)
            Text("The address shown on the printer's network screen, with no http:// prefix.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(serial,{serial=it.take(40)},label={Text("Serial number")},singleLine=true,modifier=Modifier.testTag("bambu-serial"))
            OutlinedTextField(apiKey,{apiKey=it.take(200)},label={Text("Access code")},singleLine=true,modifier=Modifier.testTag("bambu-access-code"),
                visualTransformation=if(showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon={TextButton({showKey=!showKey}){Text(if(showKey) "Hide" else "Show")}})
            Text("Serial number and access code both come from the printer's own network settings. LAN mode must be on.",style=MaterialTheme.typography.bodySmall)
        } else if(kind==PrinterKind.OCTOPRINT) {
            OutlinedTextField(address,{address=it},label={Text("OctoPrint address")},placeholder={Text("octopi.local or 192.168.1.60:5000")},singleLine=true)
            OutlinedTextField(apiKey,{apiKey=it.take(200)},label={Text("API key")},singleLine=true,modifier=Modifier.testTag("octoprint-key"),
                visualTransformation=if(showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon={TextButton({showKey=!showKey}){Text(if(showKey) "Hide" else "Show")}})
        } else if(kind==PrinterKind.ELEGOO) {
            OutlinedTextField(address,{address=it},label={Text("Printer IP address")},placeholder={Text("192.168.1.50")},singleLine=true)
            Text("The address from the printer's own network settings. A Centauri Carbon needs nothing else.",style=MaterialTheme.typography.bodySmall)
            // Same field/encrypted slot as Bambu's access code - see printerServiceFor's comment.
            OutlinedTextField(apiKey,{apiKey=it.take(200)},label={Text("Access code (Centauri Carbon 2 only, if you set one)")},singleLine=true,modifier=Modifier.testTag("elegoo-access-code"),
                visualTransformation=if(showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon={TextButton({showKey=!showKey}){Text(if(showKey) "Hide" else "Show")}})
        } else if(kind==PrinterKind.CREALITY) {
            OutlinedTextField(address,{address=it},label={Text("Printer IP address")},placeholder={Text("192.168.1.50")},singleLine=true)
            Text("A Creality K1, K2 or Hi on Creality's own firmware needs nothing else. Nozzle It All reads its status and CFS slots and uploads sliced files; start the print on the printer's screen (starting from here isn't verified on a real printer yet).",style=MaterialTheme.typography.bodySmall)
        } else if(kind==PrinterKind.FLASHFORGE) {
            OutlinedTextField(address,{address=it},label={Text("Printer IP address")},placeholder={Text("192.168.1.50")},singleLine=true)
            OutlinedTextField(serial,{serial=it.take(40)},label={Text("Serial number")},singleLine=true,modifier=Modifier.testTag("flashforge-serial"))
            // Same field/encrypted slot as Bambu's access code - see printerServiceFor's comment.
            OutlinedTextField(apiKey,{apiKey=it.take(200)},label={Text("Access code")},singleLine=true,modifier=Modifier.testTag("flashforge-access-code"),
                visualTransformation=if(showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon={TextButton({showKey=!showKey}){Text(if(showKey) "Hide" else "Show")}})
            Text("Both are on the printer's own network settings screen. Nozzle It All reads its status and IFS slots and uploads sliced files; start the print on the printer's screen (starting from here isn't verified on a real printer yet).",style=MaterialTheme.typography.bodySmall)
        } else if(kind==PrinterKind.PRUSA_LINK) {
            OutlinedTextField(address,{address=it},label={Text("Printer IP address")},placeholder={Text("192.168.1.50")},singleLine=true)
            Text("The address shown on the printer's own screen under Settings > Network, with no http:// prefix.",style=MaterialTheme.typography.bodySmall)
            // Same field/encrypted slot as Bambu's access code - see printerServiceFor's comment.
            OutlinedTextField(apiKey,{apiKey=it.take(200)},label={Text("Prusa Link password")},singleLine=true,modifier=Modifier.testTag("prusa-password"),
                visualTransformation=if(showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon={TextButton({showKey=!showKey}){Text(if(showKey) "Hide" else "Show")}})
            Text("Shown on the printer's own screen under Settings > Network > Prusa Link, or its web UI. Not physically verified against real hardware yet - see FEATURE_PARITY_ROADMAP.md.",style=MaterialTheme.typography.bodySmall)
        } else {
            OutlinedTextField(address,{address=it},label={Text("Printer address")},singleLine=true)
            Text("A Tailscale address (100.x.x.x or *.ts.net), Cloudflare Tunnel or other https:// address also works away from home.",style=MaterialTheme.typography.bodySmall)
            TextButton({showRemoteHelp=true},modifier=Modifier.testTag("open-remote-access-help")){Text("How do I connect away from home?")}
            OutlinedTextField(apiKey,{apiKey=it.take(200)},label={Text("API key (optional)")},singleLine=true,
                visualTransformation=if(showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon={TextButton({showKey=!showKey}){Text(if(showKey) "Hide" else "Show")}})
            Text("Only needed if Moonraker requires authentication; copy it from Fluidd's or Mainsail's settings.",style=MaterialTheme.typography.bodySmall)
        }
        Text("Printer type",style=MaterialTheme.typography.labelLarge)
        Text("Generic Klipper and Snapmaker U1 both talk to Moonraker and differ only in which extra vendor controls appear: stock U1 firmware shows Bespok3d, PAXX firmware shows multiACE. Bambu Lab, Prusa Link, OctoPrint, Elegoo, Creality and Flashforge are different protocols entirely, with their own fields above.",style=MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(kind==PrinterKind.GENERIC_KLIPPER,{kind=PrinterKind.GENERIC_KLIPPER},label={Text("Generic Klipper")})
            FilterChip(kind==PrinterKind.SNAPMAKER_U1,{kind=PrinterKind.SNAPMAKER_U1},label={Text("Snapmaker U1 (stock)")},modifier=Modifier.testTag("kind-u1-stock"))
            FilterChip(kind==PrinterKind.SNAPMAKER_U1_PAXX,{kind=PrinterKind.SNAPMAKER_U1_PAXX},label={Text("Snapmaker U1 (PAXX)")},modifier=Modifier.testTag("kind-u1-paxx"))
            FilterChip(kind==PrinterKind.BAMBU_LAB,{kind=PrinterKind.BAMBU_LAB},label={Text("Bambu Lab")})
            FilterChip(kind==PrinterKind.PRUSA_LINK,{kind=PrinterKind.PRUSA_LINK},label={Text("Prusa Link")},modifier=Modifier.testTag("kind-prusa-link"))
            FilterChip(kind==PrinterKind.OCTOPRINT,{kind=PrinterKind.OCTOPRINT},label={Text("OctoPrint")},modifier=Modifier.testTag("kind-octoprint"))
            FilterChip(kind==PrinterKind.ELEGOO,{kind=PrinterKind.ELEGOO},label={Text("Elegoo")},modifier=Modifier.testTag("kind-elegoo"))
            FilterChip(kind==PrinterKind.CREALITY,{kind=PrinterKind.CREALITY},label={Text("Creality (K1 / K2 / Hi)")},modifier=Modifier.testTag("kind-creality"))
            FilterChip(kind==PrinterKind.FLASHFORGE,{kind=PrinterKind.FLASHFORGE},label={Text("Flashforge (AD5X / 5M)")},modifier=Modifier.testTag("kind-flashforge"))
        }
        // WO-13: which bundled slicer profile family this printer needs, if any. Deliberately
        // separate from "printer type" above - the U1 and a Centauri Carbon both speak
        // Moonraker-shaped Klipper (same kind), but need different slicer profiles.
        Text("Slicing profile",style=MaterialTheme.typography.labelLarge)
        Text("Which bundled OrcaSlicer profile to use when slicing a shared model for this printer. Leave unset if you never slice on-device for it.",style=MaterialTheme.typography.bodySmall)
        SlicingModelPicker(slicingModel) { slicingModel = it }
        // Lanes are read from the saved printer, so only for a saved Klipper printer (AFC and Happy Hare live in Moonraker).
        val laneDetector = detectLanes?.takeIf { profile.kind == PrinterKind.GENERIC_KLIPPER }?.let { d -> { cb: (Result<Int>) -> Unit -> d(profile.address, cb) } }
        CustomMachineEditor(slicingModel, profile.customMachine, laneDetector) { value, problem -> customMachine = value; customMachineError = problem }
        // COSMOS's real hard-e-stop risk (FirmwareIdentity.kt) is why this is a live read, not a
        // typed field: only ever set by detectFirmware actually reaching the printer, never guessed.
        ElegooProfiles.connectionProblem(slicingModel,kind)?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("elegoo-profile-problem")) }
        if(ElegooProfiles.isCosmos(slicingModel) && detectFirmware!=null) {
            Text(if(detectedVersion.isBlank()) "Firmware not yet confirmed - detect it before slicing for this printer." else "Last confirmed firmware: $detectedVersion",style=MaterialTheme.typography.bodySmall)
            TextButton({
                detecting=true;detectNote=""
                detectFirmware(profile.address) { result ->
                    detecting=false
                    result.onSuccess { detectedVersion=it.version; detectNote="Confirmed: ${it.app.ifBlank{"unknown firmware"}} ${it.version}" }
                    result.onFailure { detectNote=it.message ?: "Could not read firmware. Is the printer connected?" }
                }
            },enabled=!detecting,modifier=Modifier.testTag("detect-firmware")){Text(if(detecting)"Detecting…" else "Detect firmware now")}
            if(detectNote.isNotBlank()) Text(detectNote,style=MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        Text("Changing the address disconnects the active printer.")
    } },confirmButton={TextButton({setCustomMachine(profile.address,customMachine.takeIf { ElegooProfiles.firmwareFor(slicingModel)==null });error=save(profile.address,address,name,apiKey,kind,serial,slicingModel);if(error==null) close()},enabled=address.isNotBlank() && customMachineError==null && ElegooProfiles.connectionProblem(slicingModel,kind)==null) {Text("Save")}},dismissButton={TextButton(close){Text("Cancel")}})
}
@Composable fun FileDetails(state: ScreenState) {
    if(state.fileLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.fileMetadata?.let { m ->
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(m.filename,style=MaterialTheme.typography.titleMedium)
            state.thumbnail?.let { Image(it.asImageBitmap(),"Model thumbnail",Modifier.fillMaxWidth().heightIn(max=180.dp)) }
            Text("Slicer estimate: ${formatDuration(m.estimatedSeconds)}")
            Text("Layers: ${m.layers ?: "Unknown"} · Filament: ${formatMaterial(m.filamentMm)}")
            if(m.slicer.isNotBlank()) Text(m.slicer)
        } }
    }
    if(state.fileNote.isNotBlank()) Text(state.fileNote)
}
@Composable fun HistoryHeader(state: ScreenState, load: (Int)->Unit) {
    Text("Print history",style=MaterialTheme.typography.titleLarge)
    Text("Page of available Moonraker records. Summaries below cover only this page.")
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton({load(0)},enabled=state.connected&&!state.historyLoading,modifier=Modifier.testTag("refresh-history")){Text("Refresh history")}
        OutlinedButton({load((state.historyOffset-50).coerceAtLeast(0))},enabled=state.connected&&!state.historyLoading&&state.historyOffset>0){Text("Previous")}
        OutlinedButton({load(state.historyOffset+50)},enabled=state.connected&&!state.historyLoading&&state.historyPageSize>=50){Text("Next")}
    }
    if(state.historyLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if(state.historyNote.isNotBlank()) Text(state.historyNote)
    if(state.history.isNotEmpty()) {
        Text("Records ${state.historyOffset+1}–${state.historyOffset+state.history.size} (this page)")
        val duration=state.history.mapNotNull { it.duration };val material=state.history.mapNotNull { it.filamentMm }
        Text("${state.history.count { it.status=="completed" }} completed · Recorded duration ${formatDuration(duration.takeIf { it.isNotEmpty() }?.sum())}")
        Text("Recorded filament ${formatMaterial(material.takeIf { it.isNotEmpty() }?.sum())}; missing measurements excluded.")
    } else if(!state.historyLoading && state.historyNote.isBlank()) Text("No records on this page. Refresh to read the latest history.")
}

package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Replaces the old one-field "type an address, tap Connect" flow (owner request, 2026-09-22):
 * type + address + credentials, then a slicing-profile choice, then (for a Centauri Carbon)
 * live firmware confirmation, then a live connectivity test - each step a real check, not just a
 * form field, so a printer never gets silently added half-configured or unreachable. Every live
 * check here builds its own short-lived PrinterService directly (the same ad hoc pattern
 * SlicingCoordinator and NozzlePrinterWidget already use) rather than going through PrinterModel,
 * since the profile doesn't exist there yet - addProfile is the one and only commit at the end.
 */
private enum class WizardStep { TYPE_AND_ADDRESS, SLICING_PROFILE, FIRMWARE_CONFIRM, CONNECTIVITY_TEST }

@Composable fun AddPrinterWizard(existingAddresses: List<String>, addProfile: (PrinterProfile) -> String?, openPrinter: (String) -> Unit, close: () -> Unit) {
    var step by remember { mutableStateOf(WizardStep.TYPE_AND_ADDRESS) }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var serial by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(PrinterKind.GENERIC_KLIPPER) }
    var slicingModel by remember { mutableStateOf<SlicingPrinterModel?>(null) }
    var showKey by remember { mutableStateOf(false) }
    var typeError by remember { mutableStateOf<String?>(null) }
    var normalizedAddressResult by remember { mutableStateOf("") }
    var declaredFirmwareVersion by remember { mutableStateOf("") }
    var detecting by remember { mutableStateOf(false) }
    var detectNote by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testPassed by remember { mutableStateOf(false) }
    var testNote by remember { mutableStateOf("") }
    var finishError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun draftProfile() = PrinterProfile(normalizedAddressResult, name.trim().take(80), false, "", apiKey.trim().take(200), kind, serial.trim().take(40), slicingModel, declaredFirmwareVersion)

    fun runFirmwareDetection() {
        detecting = true; detectNote = ""
        scope.launch {
            val result = try {
                val service = withContext(Dispatchers.IO) { printerServiceFor(draftProfile(), normalizedAddressResult) }
                val identity = try { withContext(Dispatchers.IO) { service.firmwareIdentity() } } finally { runCatching { service.close() } }
                Result.success(identity)
            } catch (e: Exception) { Result.failure(e) }
            detecting = false
            result.onSuccess { declaredFirmwareVersion = it.version; detectNote = "Confirmed: ${it.app.ifBlank { "unknown firmware" }} ${it.version}" }
            result.onFailure { detectNote = it.message ?: "Could not read firmware. Is the printer connected?" }
        }
    }
    fun runConnectivityTest() {
        testing = true; testNote = ""; testPassed = false
        scope.launch {
            val result = try {
                val service = withContext(Dispatchers.IO) { printerServiceFor(draftProfile(), normalizedAddressResult) }
                val snapshot = try { withContext(Dispatchers.IO) { service.snapshot() } } finally { runCatching { service.close() } }
                Result.success(snapshot)
            } catch (e: Exception) { Result.failure(e) }
            testing = false
            result.onSuccess { testPassed = true; testNote = "Connected. ${it.displayState.replaceFirstChar { c -> c.titlecase() }}." }
            result.onFailure { testPassed = false; testNote = it.message ?: "Could not connect." }
        }
    }
    LaunchedEffect(step) { if(step == WizardStep.FIRMWARE_CONFIRM && declaredFirmwareVersion.isBlank()) runFirmwareDetection() }
    LaunchedEffect(step) { if(step == WizardStep.CONNECTIVITY_TEST && !testPassed) runConnectivityTest() }

    AlertDialog(onDismissRequest = close, title = { Text("Add printer") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when(step) {
                WizardStep.TYPE_AND_ADDRESS -> {
                    Text("Step 1 of ${if(slicingModel==SlicingPrinterModel.ELEGOO_CENTAURI_CARBON) 4 else 4}: printer type and address", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(name, { name = it.take(80) }, label = { Text("Printer name") }, singleLine = true)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(kind==PrinterKind.GENERIC_KLIPPER, {kind=PrinterKind.GENERIC_KLIPPER}, label={Text("Generic Klipper")})
                        FilterChip(kind==PrinterKind.SNAPMAKER_U1_PAXX, {kind=PrinterKind.SNAPMAKER_U1_PAXX}, label={Text("Snapmaker U1 (PAXX)")})
                        FilterChip(kind==PrinterKind.BAMBU_LAB, {kind=PrinterKind.BAMBU_LAB}, label={Text("Bambu Lab")})
                        FilterChip(kind==PrinterKind.PRUSA_LINK, {kind=PrinterKind.PRUSA_LINK}, label={Text("Prusa Link")}, modifier=Modifier.testTag("wizard-kind-prusa-link"))
                    }
                    if(kind==PrinterKind.BAMBU_LAB) {
                        OutlinedTextField(address, {address=it}, label={Text("Printer IP address")}, placeholder={Text("192.168.1.50")}, singleLine=true)
                        OutlinedTextField(serial, {serial=it.take(40)}, label={Text("Serial number")}, singleLine=true, modifier=Modifier.testTag("wizard-bambu-serial"))
                        OutlinedTextField(apiKey, {apiKey=it.take(200)}, label={Text("Access code")}, singleLine=true, modifier=Modifier.testTag("wizard-bambu-access-code"),
                            visualTransformation=if(showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon={TextButton({showKey=!showKey}){Text(if(showKey) "Hide" else "Show")}})
                        Text("Serial number and access code both come from the printer's own network settings. LAN mode must be on.", style=MaterialTheme.typography.bodySmall)
                    } else if(kind==PrinterKind.PRUSA_LINK) {
                        OutlinedTextField(address, {address=it}, label={Text("Printer IP address")}, placeholder={Text("192.168.1.50")}, singleLine=true)
                        OutlinedTextField(apiKey, {apiKey=it.take(200)}, label={Text("Prusa Link password")}, singleLine=true, modifier=Modifier.testTag("wizard-prusa-password"),
                            visualTransformation=if(showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon={TextButton({showKey=!showKey}){Text(if(showKey) "Hide" else "Show")}})
                    } else {
                        OutlinedTextField(address, {address=it}, label={Text("Printer address")}, singleLine=true, modifier=Modifier.testTag("wizard-address"))
                        Text("A Tailscale address (100.x.x.x or *.ts.net), Cloudflare Tunnel or other https:// address also works away from home.", style=MaterialTheme.typography.bodySmall)
                        OutlinedTextField(apiKey, {apiKey=it.take(200)}, label={Text("API key (optional)")}, singleLine=true,
                            visualTransformation=if(showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon={TextButton({showKey=!showKey}){Text(if(showKey) "Hide" else "Show")}})
                    }
                    typeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                WizardStep.SLICING_PROFILE -> {
                    Text("Step 2 of 4: slicing profile", style = MaterialTheme.typography.labelLarge)
                    Text("Which bundled OrcaSlicer profile to use when slicing a shared model for this printer. Leave unset if you never slice on-device for it.", style=MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(slicingModel==null, {slicingModel=null}, label={Text("None")})
                        FilterChip(slicingModel==SlicingPrinterModel.SNAPMAKER_U1, {slicingModel=SlicingPrinterModel.SNAPMAKER_U1}, label={Text("Snapmaker U1")})
                        FilterChip(slicingModel==SlicingPrinterModel.ELEGOO_CENTAURI_CARBON, {slicingModel=SlicingPrinterModel.ELEGOO_CENTAURI_CARBON}, label={Text("Elegoo Centauri Carbon")}, modifier=Modifier.testTag("wizard-slicing-centauri-carbon"))
                        FilterChip(slicingModel==SlicingPrinterModel.BAMBU_GENERIC, {slicingModel=SlicingPrinterModel.BAMBU_GENERIC}, label={Text("Bambu Lab")})
                        FilterChip(slicingModel==SlicingPrinterModel.PRUSA_GENERIC, {slicingModel=SlicingPrinterModel.PRUSA_GENERIC}, label={Text("Prusa")})
                        FilterChip(slicingModel==SlicingPrinterModel.PRUSA_XL_5T, {slicingModel=SlicingPrinterModel.PRUSA_XL_5T}, label={Text("Prusa XL (5 tools)")}, modifier=Modifier.testTag("slicing-model-prusa-xl"))
                        FilterChip(slicingModel==SlicingPrinterModel.GENERIC_KLIPPER, {slicingModel=SlicingPrinterModel.GENERIC_KLIPPER}, label={Text("Generic Klipper")})
                    }
                }
                WizardStep.FIRMWARE_CONFIRM -> {
                    Text("Step 3 of 4: firmware confirmation", style = MaterialTheme.typography.labelLarge)
                    Text("A Centauri Carbon's firmware must be confirmed before slicing for it - the wrong slicer profile can trigger a hard emergency stop mid-print on COSMOS 26.07.0+.", style=MaterialTheme.typography.bodySmall)
                    if(detecting) Text("Detecting…") else if(declaredFirmwareVersion.isNotBlank()) Text("Confirmed firmware: $declaredFirmwareVersion", color=MaterialTheme.colorScheme.primary) else if(detectNote.isNotBlank()) Text(detectNote, color=MaterialTheme.colorScheme.error)
                    TextButton({runFirmwareDetection()}, enabled=!detecting, modifier=Modifier.testTag("wizard-retry-firmware")){Text(if(detecting)"Detecting…" else "Retry detection")}
                    Text("You can skip this and confirm later from Edit printer - slicing for this printer will stay blocked until it's confirmed either way.", style=MaterialTheme.typography.bodySmall)
                }
                WizardStep.CONNECTIVITY_TEST -> {
                    Text("Step 4 of 4: connection test", style = MaterialTheme.typography.labelLarge)
                    if(testing) Text("Testing connection…") else if(testPassed) Text(testNote, color=MaterialTheme.colorScheme.primary) else if(testNote.isNotBlank()) Text(testNote, color=MaterialTheme.colorScheme.error)
                    TextButton({runConnectivityTest()}, enabled=!testing, modifier=Modifier.testTag("wizard-retry-connection")){Text(if(testing)"Testing…" else "Retry test")}
                }
            }
            finishError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        when(step) {
            WizardStep.TYPE_AND_ADDRESS -> Button({
                typeError = null
                val normalized = try { normalizedAddress(address, kind) } catch(e: IllegalArgumentException) { typeError = "Enter a valid printer address."; return@Button }
                if(normalized in existingAddresses) { typeError = "That printer address is already saved."; return@Button }
                normalizedAddressResult = normalized
                step = WizardStep.SLICING_PROFILE
            }, enabled = address.isNotBlank(), modifier = Modifier.testTag("wizard-next-1")) { Text("Next") }
            WizardStep.SLICING_PROFILE -> Button({ step = if(slicingModel==SlicingPrinterModel.ELEGOO_CENTAURI_CARBON) WizardStep.FIRMWARE_CONFIRM else WizardStep.CONNECTIVITY_TEST }, modifier = Modifier.testTag("wizard-next-2")) { Text("Next") }
            WizardStep.FIRMWARE_CONFIRM -> Button({ step = WizardStep.CONNECTIVITY_TEST }, enabled = !detecting, modifier = Modifier.testTag("wizard-next-3")) { Text(if(declaredFirmwareVersion.isNotBlank()) "Next" else "Skip for now") }
            WizardStep.CONNECTIVITY_TEST -> Button({
                finishError = addProfile(draftProfile())
                if(finishError == null) { openPrinter(normalizedAddressResult); close() }
            }, enabled = testPassed && !testing, modifier = Modifier.testTag("wizard-finish")) { Text("Finish") }
        }
    }, dismissButton = {
        TextButton({
            when(step) {
                WizardStep.TYPE_AND_ADDRESS -> close()
                WizardStep.SLICING_PROFILE -> step = WizardStep.TYPE_AND_ADDRESS
                WizardStep.FIRMWARE_CONFIRM -> step = WizardStep.SLICING_PROFILE
                WizardStep.CONNECTIVITY_TEST -> step = if(slicingModel==SlicingPrinterModel.ELEGOO_CENTAURI_CARBON) WizardStep.FIRMWARE_CONFIRM else WizardStep.SLICING_PROFILE
            }
        }) { Text(if(step==WizardStep.TYPE_AND_ADDRESS) "Cancel" else "Back") }
    })
}

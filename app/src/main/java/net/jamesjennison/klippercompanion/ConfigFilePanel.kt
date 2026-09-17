package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*

@Composable fun ConfigFilePanel(address: String, connected: Boolean, close: () -> Unit, factory: (String) -> ConfigFileReader = { Moonraker(it) },
    ready: Boolean = connected, printState: String = "", writerFactory: ((String) -> ConfigWriter)? = null,
    execute: ((PrinterCommand, Int) -> Unit)? = null, generation: Int = 0, clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    var content by remember(address) { mutableStateOf<ConfigFileContent?>(null) }
    var note by remember(address) { mutableStateOf("Loading configuration…") }
    var epoch by remember(address) { mutableIntStateOf(0) }
    var editing by remember(address) { mutableStateOf(false) }
    var draft by remember(address) { mutableStateOf("") }
    var editingAutoSection by remember(address) { mutableStateOf("") }
    var saveNotice by remember(address) { mutableStateOf("") }
    var pendingSave by remember(address) { mutableStateOf<String?>(null) }
    var savePreparedAt by remember(address) { mutableLongStateOf(0) }
    var saving by remember(address) { mutableStateOf(false) }
    var restartArmed by remember(address) { mutableStateOf(false) }
    var restartPreparedAt by remember(address) { mutableLongStateOf(0) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val enabled = connected && ready && printState in ConfigFile.allowedStates && !saving
    fun load() {
        editing = false; draft = ""; pendingSave = null; saveNotice = ""; restartArmed = false
        if (!connected) { content = null; note = "Disconnected. Connect to read the configuration."; return }
        val ticket = ++epoch
        note = "Loading configuration…"
        scope.launch {
            val reader = try { factory(address) } catch (_: Exception) { if (ticket == epoch) note = "Configuration unavailable."; return@launch }
            try {
                val result = withContext(Dispatchers.IO) { reader.configFile() }
                if (ticket != epoch) return@launch
                content = result
                note = "Loaded ${result.filename}."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == epoch) { content = null; note = e.message ?: "Configuration unavailable." } }
            finally { reader.close() }
        }
    }
    LaunchedEffect(address, connected, lifecycle) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) load()
    }
    fun reviewSave() {
        pendingSave = null; saveNotice = ""
        val validated = runCatching { ConfigFile.validateUserSection(draft) }
        val invalid = validated.exceptionOrNull()
        if (invalid != null) { saveNotice = invalid.message ?: "Invalid configuration."; return }
        val filename = content?.filename ?: return
        saving = true
        scope.launch {
            try {
                val reader = factory(address)
                val fresh = try { withContext(Dispatchers.IO) { reader.configFile() } } finally { reader.close() }
                if (fresh.autoSection != editingAutoSection) {
                    saveNotice = "Configuration changed on the printer since you loaded it. Cancel and reload before saving."
                } else {
                    pendingSave = ConfigFile.assemble(draft, fresh.autoSection)
                    savePreparedAt = clock()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { saveNotice = e.message ?: "Cannot verify the current configuration." }
            finally { saving = false }
        }
    }
    fun confirmSave() {
        val toSave = pendingSave; pendingSave = null
        if (toSave == null) return
        if (clock() - savePreparedAt !in 0..5000) { saveNotice = "Review the changes again; this confirmation expired."; return }
        val writer = writerFactory?.invoke(address) ?: return
        val filename = content?.filename ?: return
        saving = true
        scope.launch {
            try {
                val backupPath = withContext(Dispatchers.IO) { writer.backupConfig(filename) }
                withContext(Dispatchers.IO) { writer.writeConfig(filename, toSave) }
                load()
                saveNotice = "Saved. Backed up the previous version to $backupPath. Restart the printer to apply changes."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { saveNotice = e.message ?: "Save failed. Inspect the printer before trying again." }
            finally { writer.close(); saving = false }
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Configuration", style = MaterialTheme.typography.titleLarge)
                Text("Printer: $address")
                Text(if (writerFactory != null) "Editing changes only the section above SAVE_CONFIG. Saving backs up the previous file first; restart separately to apply." else "Read-only view. Editing, saving and restart are not available yet.", style = MaterialTheme.typography.bodySmall)
                Text(note, modifier = Modifier.testTag("config-status"), style = MaterialTheme.typography.bodySmall)
                content?.let { file ->
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Your configuration", style = MaterialTheme.typography.titleMedium)
                        if (editing) OutlinedTextField(draft, { draft = it; pendingSave = null }, modifier = Modifier.fillMaxWidth().testTag("config-editor"))
                        else Text(file.userSection, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("config-user-section"))
                        if (writerFactory != null) {
                            if (!editing) {
                                Button({ editing = true; draft = file.userSection; editingAutoSection = file.autoSection; saveNotice = "" }, enabled = enabled, modifier = Modifier.testTag("edit-config")) { Text("Edit") }
                            } else {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button({ reviewSave() }, enabled = enabled, modifier = Modifier.testTag("review-config-save")) { Text(if (saving) "Checking…" else "Review save") }
                                    TextButton({ editing = false; draft = ""; pendingSave = null; saveNotice = "" }, enabled = !saving, modifier = Modifier.testTag("cancel-config-edit")) { Text("Cancel edit") }
                                }
                                pendingSave?.let {
                                    Text("Ready to back up the current file and save your edits. This does not take effect until you restart.", style = MaterialTheme.typography.bodySmall)
                                    Button({ confirmSave() }, enabled = enabled, modifier = Modifier.testTag("confirm-config-save")) { Text("Back up and save") }
                                }
                            }
                            if (saveNotice.isNotEmpty()) Text(saveNotice, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("config-save-notice"))
                        }
                        if (file.hasAutoSection) {
                            Text("Auto-generated by SAVE_CONFIG", style = MaterialTheme.typography.titleMedium)
                            Text(file.autoSection, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("config-auto-section"))
                        }
                        if (execute != null) {
                            HorizontalDivider()
                            Text("Restart Klipper to apply a saved change. This is a soft restart - connected MCUs are not reset.", style = MaterialTheme.typography.bodySmall)
                            if (!restartArmed) {
                                Button({ restartArmed = true; restartPreparedAt = clock() }, enabled = enabled, modifier = Modifier.testTag("arm-restart")) { Text("Restart printer (reload config)") }
                            } else {
                                Button({
                                    restartArmed = false
                                    if (clock() - restartPreparedAt !in 0..5000) saveNotice = "Restart confirmation expired; tap Restart again."
                                    else execute(PrinterCommand("Restart printer (reload config)", "printer/restart", allowedStates = ConfigFile.allowedStates), generation)
                                }, enabled = enabled, modifier = Modifier.testTag("confirm-restart")) { Text("Confirm restart") }
                            }
                        }
                    }
                } ?: Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ load() }, enabled = connected, modifier = Modifier.testTag("refresh-config")) { Text("Refresh") }
                    TextButton(close) { Text("Close") }
                }
            }
        }
    }
}

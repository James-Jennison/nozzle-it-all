package net.jamesjennison.klippercompanion.environment

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nozzleitall.environment.*
import kotlinx.coroutines.*

/** User enters secrets directly; no prefill/reveal, saved-instance state, screenshot or logging. */
@Composable internal fun GoveeNameImport(store: AndroidGoveeNames) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var rememberKey by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun cancel() { store.cancel(); key = ""; dialog = false }
    DisposableEffect(lifecycle, store) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); cancel() }
    }
    OutlinedButton({
        dialog = true; key = ""; rememberKey = false
        scope.launch { saved = try { withContext(Dispatchers.IO) { store.hasSavedKey() } } catch (_: Exception) { false } }
    }, enabled = !busy) { Text("Import names from Govee") }
    Text("Optional account lookup for names only. Readings stay on local Bluetooth; no device-control commands.", style = MaterialTheme.typography.bodySmall)
    message?.let { Text(it) }
    TextButton({
        busy = true; cancel()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { try { withContext(NonCancellable + Dispatchers.IO) { store.forget() }; saved = false; message = "Saved Govee key and imported name list forgotten. Added sensor names/history are unchanged." }
            catch (_: Exception) { message = "Could not forget saved key; retry explicitly." }
            finally { busy = false } }
    }, enabled = !busy) { Text("Forget Govee key and imported names") }
    if (dialog) AlertDialog(onDismissRequest = { if (!busy) cancel() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("Read-only Govee name import") },
        text = {
            val privateView = LocalView.current
            DisposableEffect(privateView) {
                val previous = privateView.importantForAutofill
                privateView.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                onDispose { privateView.importantForAutofill = previous }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your key is sent only to Govee's official device-list API. Enter it here, never in chat. Some sensors may not appear in the API.")
            OutlinedTextField(key, { key = it.take(256) }, label = { Text("Govee API key") }, enabled = !busy,
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
            Row { Checkbox(rememberKey, { rememberKey = it }, enabled = !busy); Text("Remember key on this device (encrypted)") }
            if (saved) Text("A saved key is available. Leave the field empty to use it.")
        } },
        confirmButton = { TextButton({
            val entered = key.trim().takeIf { it.isNotEmpty() }; val persist = rememberKey
            key = ""; busy = true
            val request = store.begin()
            scope.launch {
                try {
                    val count = withContext(Dispatchers.IO) { store.import(entered, persist, request) }
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        message = "Imported $count Govee names. Exact model/address matches fill new sensors; otherwise choose the matching Govee name explicitly. Existing sensor names are not overwritten."
                        dialog = false
                    }
                } catch (_: CancellationException) { store.cancel() }
                catch (e: GoveeNameException) { message = e.reason.description; dialog = false }
                catch (_: Exception) { message = "Name import unavailable; no names changed. Retry explicitly."; dialog = false }
                finally { busy = false }
            }
        }, enabled = !busy && (key.isNotBlank() || saved)) { Text("Import names") } },
        dismissButton = { TextButton({ cancel() }, enabled = !busy) { Text("Cancel") } })
}

@Composable internal fun GoveeNamePicker(model: String, names: List<GoveeDeviceName>, onDismiss: () -> Unit, onChoose: (GoveeDeviceName) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose Govee name for $model") },
        text = { Column {
            Text("Select the name of this physical sensor. Cloud device IDs are not assumed to be Bluetooth addresses. This changes only NIA's label.")
            LazyColumn(Modifier.heightIn(max = 300.dp)) {
                items(names.filter { it.model == model }, key = { it.model + ":" + it.device }) { device ->
                    TextButton({ onChoose(device) }) { Text("${device.name} · ${device.model} · ID …${device.device.takeLast(5)}") }
                }
            }
        } }, confirmButton = { TextButton(onDismiss) { Text("Cancel") } })
}

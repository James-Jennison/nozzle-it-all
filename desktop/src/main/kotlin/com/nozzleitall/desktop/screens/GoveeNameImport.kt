package com.nozzleitall.desktop.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.nozzleitall.desktop.environment.DesktopGoveeNames
import com.nozzleitall.desktop.ui.*
import com.nozzleitall.environment.*
import kotlinx.coroutines.*

@Composable internal fun GoveeNameChoices(model: String, names: List<GoveeDeviceName>, dismiss: () -> Unit, choose: (GoveeDeviceName) -> Unit) {
    Txt("Choose this physical sensor's name. Cloud IDs are not assumed to be BLE addresses; only NIA's label changes.", Nz.type.bodySmall)
    LazyColumn(Modifier.heightIn(max = 240.dp)) {
        items(names.filter { it.model == model }, key = { it.model + ":" + it.device }) { entry ->
            NzButton("${entry.name} · ${entry.model} · ID …${entry.device.takeLast(5)}", { choose(entry) })
        }
    }
    NzButton("Cancel name selection", dismiss)
}

@Composable internal fun GoveeNameImport(store: DesktopGoveeNames) {
    var dialog by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun cancel() { store.cancel(); secret = ""; dialog = false }
    DisposableEffect(store) { onDispose { store.cancel(); secret = "" } }
    NzButton("Import names from Govee", { dialog = true }, enabled = !busy)
    NzButton("Forget imported Govee names", { cancel(); store.forget() })
    message?.let { Txt(it, Nz.type.bodySmall) }
    if (dialog) DialogWindow(onCloseRequest = { cancel() }, title = "Read-only Govee name import",
        state = rememberDialogState(width = 560.dp, height = 360.dp), resizable = false) {
        DisposableEffect(window) {
            val listener = object : java.awt.event.WindowAdapter() {
                override fun windowLostFocus(e: java.awt.event.WindowEvent) { cancel() }
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }
        NozzleTheme {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt("Names only; readings stay on local Bluetooth. No equipment-control requests.", Nz.type.body)
                Txt("Enter your key privately here, never in chat. It is used once, not saved on desktop. Some sensors may not appear in the API.", Nz.type.bodySmall)
                Field("Govee API key", secret, { secret = it.take(256) }, secret = true)
                NzButton("Import names", {
                    val entered = secret.trim(); secret = ""; busy = true
                    val request = store.begin()
                    scope.launch {
                        try {
                            val count = withContext(Dispatchers.IO) { store.import(entered, request) }
                            message = "Imported $count Govee names. Existing saved names are unchanged."
                        } catch (e: GoveeNameException) { message = e.reason.description }
                        catch (_: Exception) { message = "Name import unavailable; retry explicitly." }
                        finally { busy = false; secret = ""; dialog = false }
                    }
                }, enabled = !busy && secret.isNotBlank())
                NzButton("Cancel", { cancel() })
            }
        }
    }
}

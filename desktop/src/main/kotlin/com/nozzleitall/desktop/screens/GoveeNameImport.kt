package com.nozzleitall.desktop.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
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
    Txt("Matching devices are named automatically. This optional override changes only NIA's label; do not guess an unresolved device from its ID.", Nz.type.bodySmall)
    LazyColumn(Modifier.heightIn(max = 240.dp)) {
        items(names.filter { it.model == model }, key = { it.model + ":" + it.device }) { entry ->
            NzButton("${entry.name} · ${entry.model} · ID ${entry.device}", { choose(entry) })
        }
    }
    NzButton("Cancel name selection", dismiss)
}

@Composable internal fun GoveeNameImport(store: DesktopGoveeNames) {
    var dialog by remember { mutableStateOf(false) }
    var entryText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store) {
        try { withContext(Dispatchers.IO) { store.cachedDevices() } }
        catch (e: GoveeNameException) { message = e.reason.description }
    }
    fun cancel() { store.cancel(); entryText = ""; dialog = false }
    DisposableEffect(store) { onDispose { store.cancel(); entryText = "" } }
    NzButton("Import names from Govee", { dialog = true }, enabled = !busy)
    NzButton("Forget imported Govee names", { cancel(); busy = true
        val cleanup = store.requestForgetAsync()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { try { withContext(NonCancellable) { cleanup.await() }; message = "Imported directory forgotten. Added sensors and history are unchanged." }
            catch (e: GoveeNameException) { message = e.reason.description } finally { busy = false } }
    }, enabled = !busy)
    Txt("Names are saved locally and reused for discovery, including after restarting NIA. No key or cloud request is needed to discover again.", Nz.type.bodySmall)
    message?.let { Txt(it, Nz.type.bodySmall) }
    if (dialog) DialogWindow(onCloseRequest = { cancel() }, title = "Read-only Govee name import",
        state = rememberDialogState(width = 560.dp, height = 360.dp), resizable = false) {
        DisposableEffect(window) {
            val listener = object : java.awt.event.WindowAdapter() {
                override fun windowLostFocus(e: java.awt.event.WindowEvent) {
                    // IME/context-menu child windows are still private dialog interaction.
                    if (generateSequence(e.oppositeWindow) { it.owner }.none { it == window }) cancel()
                }
                override fun windowClosing(e: java.awt.event.WindowEvent) { cancel() }
                override fun windowClosed(e: java.awt.event.WindowEvent) { cancel() }
            }
            window.addWindowFocusListener(listener)
            window.addWindowListener(listener)
            onDispose { window.removeWindowFocusListener(listener); window.removeWindowListener(listener); entryText = "" }
        }
        NozzleTheme {
            Column(Modifier.fillMaxSize().background(Nz.colors.surface).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt("Names only; readings stay on local Bluetooth. No equipment-control requests.", Nz.type.body)
                Txt("Enter your key privately here, never in chat. Only the name directory is saved, not the key. You need the key only to refresh names from Govee. Some sensors may not appear in the API.", Nz.type.bodySmall)
                Txt("Desktop does not prevent operating-system screenshots. Closing or leaving this dialog clears the entry.", Nz.type.bodySmall)
                Field("Govee API key", entryText, { entryText = it.take(256) }, Modifier, "", true)
                NzButton("Import names", {
                    val entered = entryText.trim(); entryText = ""; busy = true
                    val request = try { store.begin() } catch (e: GoveeNameException) {
                        busy = false; dialog = false; message = e.reason.description; return@NzButton
                    }
                    scope.launch {
                        try {
                            val count = withContext(Dispatchers.IO) { store.import(entered, request) }
                            message = "Imported $count Govee names. Existing saved names are unchanged."
                        } catch (e: GoveeNameException) { message = e.reason.description }
                        catch (_: Exception) { message = "Name import unavailable; retry explicitly." }
                        finally { busy = false; entryText = ""; dialog = false }
                    }
                }, enabled = !busy && entryText.isNotBlank())
                NzButton("Cancel", { cancel() })
            }
        }
    }
}

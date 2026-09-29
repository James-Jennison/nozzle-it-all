package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pairing with a networked UltiMaker (UltiMakerPrinterService.requestCredentials / authStatus; upstream OrcaSlicer
 * UltiMaker.cpp's auth/request -> approve on the printer -> auth/check flow). Shared by Edit printer and the add-printer
 * wizard. [onCredentials] receives the issued id (kept in PrinterProfile.serial) and key (the encrypted apiKey slot);
 * nothing is saved until that dialog's own Save / Finish. Neither request changes anything on the printer except asking
 * the person on its screen. The key is never shown.
 */
@Composable fun UltiMakerPairing(address: String, authId: String, authKey: String, onCredentials: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    fun <T> withService(block: (UltiMakerPrinterService) -> T, done: (Result<T>) -> Unit) {
        busy = true; note = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val service = UltiMakerPrinterService(normalizedInputAddress(address, PrinterKind.ULTIMAKER), authId, authKey)
                    try { block(service) } finally { runCatching { service.close() } }
                }
            }
            busy = false
            done(result)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if(authId.isNotBlank() && authKey.isNotBlank()) "Paired (access id $authId)." else "Not paired yet. Status works without pairing; pairing is needed to send prints later.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ultimaker-pairing-state"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton({
                withService({ it.requestCredentials() }) { r ->
                    r.onSuccess { c ->
                        // Stored in fields capped at 40 / 200 characters: refuse rather than silently truncate a credential.
                        if (c.id.length > 40 || c.key.length > 200) note = "The printer issued credentials longer than Nozzle It All can store."
                        else { onCredentials(c.id, c.key); note = "Now allow Nozzle It All on the printer's screen, then tap Check approval." }
                    }
                    r.onFailure { note = it.message ?: "Could not reach the printer." }
                }
            }, enabled = !busy && address.isNotBlank(), modifier = Modifier.testTag("ultimaker-request-access")) { Text(if(busy) "Working…" else "Request access") }
            OutlinedButton({
                withService({ s -> val status = s.authStatus(); status to (status == UltiMakerApi.AuthStatus.AUTHORIZED && s.verify()) }) { r ->
                    r.onSuccess { (status, verified) -> note = UltiMakerApi.authProblem(status, verified) ?: "Approved: Nozzle It All is paired with this printer." }
                    r.onFailure { note = it.message ?: "Could not reach the printer." }
                }
            }, enabled = !busy && address.isNotBlank() && authId.isNotBlank(), modifier = Modifier.testTag("ultimaker-check-approval")) { Text("Check approval") }
        }
        if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ultimaker-pairing-note"))
    }
}

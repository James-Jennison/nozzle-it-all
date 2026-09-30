package net.jamesjennison.klippercompanion

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
 * The "Connect" button for the two Snapmaker kinds (P-0037), shared by Edit printer and the add-printer wizard. This is
 * the only place that asks a Snapmaker to accept Nozzle It All: it makes the printer ask the person on its screen.
 * Nothing is saved until that dialog's own Save / Finish.
 *
 * - SNAPMAKER_A_SERIES: `POST /api/v1/connect` (SnapmakerSstpPrinterService.connect). [onConnected] receives the token
 *   (the encrypted apiKey slot) and the series (the serial slot). The token is never shown.
 * - SNAPMAKER_SACP: the SACP hello with a connection name (SnapmakerSacpPrinterService.connect). [onConnected] receives
 *   the hello token unchanged (the apiKey slot) and the connection name (the serial slot).
 */
@Composable fun SnapmakerConnect(kind: PrinterKind, address: String, serial: String, apiKey: String, onConnected: (serial: String, apiKey: String) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val sacp = kind == PrinterKind.SNAPMAKER_SACP
    val connected = if (sacp) serial.isNotBlank() else apiKey.isNotBlank()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            when {
                !connected -> "Not connected yet. Tap Connect, then accept Nozzle It All on the printer's screen."
                sacp -> "Connects as \"$serial\"."
                serial.isNotBlank() -> "Connected (series $serial)."
                else -> "Connected."
            },
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("snapmaker-connect-state"),
        )
        OutlinedButton({
            busy = true; note = ""
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val normalized = normalizedInputAddress(address, kind)
                        if (sacp) {
                            val name = serial.ifBlank { defaultConnectionName() }
                            val service = SnapmakerSacpPrinterService(normalized, name, apiKey)
                            try {
                                val machine = service.connect()
                                Triple(name, apiKey, "Connected" + (machine?.let { " to a Snapmaker ${SnapmakerSacp.machineTypeName(it.type)} (firmware ${it.firmwareVersion})" } ?: "") + ".")
                            } finally { runCatching { service.close() } }
                        } else {
                            val service = SnapmakerSstpPrinterService(normalized, apiKey, serial)
                            try {
                                when (val r = service.connect()) {
                                    is SnapmakerSstp.ConnectResult.Connected -> {
                                        // Stored in fields capped at 40 / 200 characters: refuse rather than silently truncate.
                                        if (r.token.length > 200) throw ApiFailure("The printer issued a token longer than Nozzle It All can store.")
                                        Triple(r.series.take(40), r.token, "Connected" + (if (r.series.isNotBlank()) " (series ${r.series})" else "") + ".")
                                    }
                                    SnapmakerSstp.ConnectResult.AwaitingApproval -> Triple(serial, apiKey, SnapmakerSstp.AWAITING_APPROVAL)
                                }
                            } finally { runCatching { service.close() } }
                        }
                    }
                }
                busy = false
                result.onSuccess { (newSerial, newKey, message) -> if (newSerial != serial || newKey != apiKey) onConnected(newSerial, newKey); note = message }
                result.onFailure { note = it.message ?: "Could not reach the printer." }
            }
        }, enabled = !busy && address.isNotBlank(), modifier = Modifier.testTag("snapmaker-connect")) { Text(if (busy) "Waiting for the printer…" else "Connect") }
        if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("snapmaker-connect-note"))
    }
}

/** The name a J1 / Artisan shows for this phone, as Luban sends the computer's host name (luban/SacpTcpChannel.ts:73-81). */
private fun defaultConnectionName(): String = "Nozzle It All (${Build.MODEL.orEmpty().ifBlank { "Android" }})".take(40)

package net.jamesjennison.klippercompanion.environment

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nozzleitall.environment.Candidate
import com.nozzleitall.environment.DiscoveryReception

/** One clearly grouped naming target per actual discovered address. No radio/storage effects. */
@Composable internal fun GoveeDiscoveryCard(candidate: Candidate, reception: DiscoveryReception?, name: String,
    added: Boolean, busy: Boolean, onNameChange: (String) -> Unit, onAdd: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("govee-candidate-${candidate.address}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${candidate.model} · ${candidate.address}")
            Text(reception?.readings?.takeIf { it.isNotEmpty() }?.joinToString(" · ") { "${it.channel.name}: ${it.value} ${it.channel.unit}" } ?: "Readings unavailable")
            Text("Signal: ${reception?.rssiDbm?.let { "$it dBm" } ?: "unavailable"} · Last radio reception: ${reception?.receivedAt?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "unavailable"}", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, onNameChange, label = { Text("Name this sensor") }, enabled = !added && !busy,
                modifier = Modifier.fillMaxWidth().testTag("govee-name-${candidate.address}"))
            OutlinedButton(onAdd, enabled = !busy && !added && name.isNotBlank(), modifier = Modifier.testTag("govee-add-${candidate.address}")) {
                Text(if (added) "Already added" else "Add sensor")
            }
        }
    }
}

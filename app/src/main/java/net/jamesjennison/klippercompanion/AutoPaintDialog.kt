package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Asks which server should look at the model (any OpenAI-compatible one: OpenAI, a local Ollama or LM Studio, OpenRouter...) and,
 * optionally, what to tell it about the object. [onPaint] gets the settings (already saved) and the hint.
 */
@Composable
fun AutoPaintDialog(initial: AutoPaint.Settings, busy: Boolean, message: String?, onSave: (AutoPaint.Settings) -> Unit, onPaint: (AutoPaint.Settings, String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var key by remember { mutableStateOf(initial.apiKey) }
    var hint by remember { mutableStateOf("") }
    val settings = AutoPaint.Settings(url, model, key)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("AI auto-paint") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A vision model looks at the model and says which parts should print in which filament. Use any OpenAI-compatible server; the key is kept in the app's encrypted store.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(url, { url = it }, label = { Text("Server address") }, placeholder = { Text("https://api.openai.com/v1") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("autopaint-url"))
                OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("autopaint-model"))
                OutlinedTextField(key, { key = it }, label = { Text("API key (if the server needs one)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("autopaint-key"))
                OutlinedTextField(hint, { hint = it }, label = { Text("What is it? (optional)") }, placeholder = { Text("a locomotive: red body, black wheels") }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("autopaint-hint"))
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("autopaint-message")) }
            }
        },
        confirmButton = { TextButton({ onSave(settings); onPaint(settings, hint) }, enabled = !busy && settings.provider != null, modifier = Modifier.testTag("autopaint-run")) { Text(if (busy) "Painting…" else "Paint") } },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("Close") } },
    )
}

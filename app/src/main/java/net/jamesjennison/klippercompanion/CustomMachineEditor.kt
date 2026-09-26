package net.jamesjennison.klippercompanion

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** The bundled profile's own bed, height and start/end G-code, as the starting point for editing. Null when the model has no pack. */
internal fun defaultCustomMachine(model: SlicingPrinterModel, context: Context): CustomMachine? {
    val pack = slicingProfilePack(model, null) ?: return null
    return try { defaultCustomMachineFrom(pack.machineText(context)) } catch (_: Exception) { null }
}

internal fun defaultCustomMachineFrom(machineJson: String): CustomMachine {
    val shape = parseBedShape(machineJson)
    val xs = shape.points.map { it.first }; val ys = shape.points.map { it.second }
    fun text(key: String): String = JSONObject(machineJson).opt(key).let { v -> if (v is org.json.JSONArray) (0 until v.length()).joinToString("\n") { v.getString(it) } else v?.toString().orEmpty() }
    return CustomMachine(
        (xs.max() - xs.min()).toDouble(), (ys.max() - ys.min()).toDouble(), shape.heightMm.toDouble(),
        originAtCenter = xs.min() < 0f, startGcode = text("machine_start_gcode"), endGcode = text("machine_end_gcode"),
    )
}

private fun Double.fieldText(): String = if (this == Math.rint(this)) toLong().toString() else toString()

/**
 * Optional per-printer overrides for the slicing profile (bed size and origin, height, start/end G-code) for machines
 * that match no bundled model, such as custom-built printers. [onChange] reports the current value (null = off) and a
 * problem message when the entries are not usable, so the caller can block Save. Not offered for the COSMOS Centauri
 * Carbon, whose pack is never overridden.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CustomMachineEditor(model: SlicingPrinterModel?, current: CustomMachine?, onChange: (CustomMachine?, String?) -> Unit) {
    if (model == null || model == SlicingPrinterModel.ELEGOO_CENTAURI_CARBON) return
    val context = LocalContext.current
    val base = remember(model) { defaultCustomMachine(model, context.applicationContext) } ?: return
    val start = current ?: base
    var enabled by remember(model) { mutableStateOf(current != null) }
    var width by remember(model) { mutableStateOf(start.bedWidthMm.fieldText()) }
    var depth by remember(model) { mutableStateOf(start.bedDepthMm.fieldText()) }
    var height by remember(model) { mutableStateOf(start.maxHeightMm.fieldText()) }
    var center by remember(model) { mutableStateOf(start.originAtCenter) }
    var startGcode by remember(model) { mutableStateOf(start.startGcode) }
    var endGcode by remember(model) { mutableStateOf(start.endGcode) }
    val parsed = if (enabled) {
        val w = width.toDoubleOrNull(); val d = depth.toDoubleOrNull(); val h = height.toDoubleOrNull()
        if (w == null || d == null || h == null) null to "Enter the bed width, depth and height as numbers."
        else CustomMachine(w, d, h, center, startGcode, endGcode).let { it to it.problem() }
    } else null to null
    LaunchedEffect(enabled, parsed.first, parsed.second) { onChange(if (enabled) parsed.first else null, parsed.second) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Custom machine", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(enabled, { enabled = it }, modifier = Modifier.testTag("custom-machine-enable"))
            Text("Use my own bed size and start/end G-code instead of the bundled profile's", style = MaterialTheme.typography.bodyMedium)
        }
        if (enabled) {
            Text(
                "For printers the bundled profiles do not match, such as one you built yourself. Slicing uses these values in place of the profile's. " +
                    "The nozzle stays 0.4 mm. Check the bed in the slice preview and watch the first print.",
                style = MaterialTheme.typography.bodySmall,
            )
            val numbers = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            OutlinedTextField(width, { width = it.take(8) }, label = { Text("Bed width, X (mm)") }, singleLine = true, keyboardOptions = numbers, modifier = Modifier.fillMaxWidth().testTag("custom-machine-width"))
            OutlinedTextField(depth, { depth = it.take(8) }, label = { Text("Bed depth, Y (mm)") }, singleLine = true, keyboardOptions = numbers, modifier = Modifier.fillMaxWidth().testTag("custom-machine-depth"))
            OutlinedTextField(height, { height = it.take(8) }, label = { Text("Maximum height, Z (mm)") }, singleLine = true, keyboardOptions = numbers, modifier = Modifier.fillMaxWidth().testTag("custom-machine-height"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!center, { center = false }, label = { Text("Origin at a corner") }, modifier = Modifier.testTag("custom-machine-origin-corner"))
                FilterChip(center, { center = true }, label = { Text("Origin at the centre") }, modifier = Modifier.testTag("custom-machine-origin-centre"))
            }
            OutlinedTextField(startGcode, { startGcode = it.take(CustomMachine.MAX_GCODE_CHARS + 1) }, label = { Text("Start G-code") }, minLines = 3, maxLines = 8,
                supportingText = { Text("Runs before the print. Leave empty to keep the profile's own. Slicer placeholders such as [nozzle_temperature_initial_layer] work.") },
                modifier = Modifier.fillMaxWidth().testTag("custom-machine-start"))
            OutlinedTextField(endGcode, { endGcode = it.take(CustomMachine.MAX_GCODE_CHARS + 1) }, label = { Text("End G-code") }, minLines = 2, maxLines = 6,
                supportingText = { Text("Runs after the print. Leave empty to keep the profile's own.") },
                modifier = Modifier.fillMaxWidth().testTag("custom-machine-end"))
            parsed.second?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("custom-machine-error")) }
        }
    }
}

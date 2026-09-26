package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

// Two long-standing chip tags are kept so existing tests and habits still find them.
internal fun slicingModelTag(model: SlicingPrinterModel): String = when (model) {
    SlicingPrinterModel.ELEGOO_CENTAURI_CARBON -> "slicing-model-centauri-carbon"
    SlicingPrinterModel.PRUSA_XL_5T -> "slicing-model-prusa-xl"
    SlicingPrinterModel.PRUSA_XL -> "slicing-model-prusa-xl-single-tool" // would otherwise collide with the 5T tag above
    else -> "slicing-model-" + model.name.lowercase().replace('_', '-')
}

/** Models whose label matches [query] (case-insensitive, blank = all), in catalog order. */
internal fun matchingSlicingModels(query: String): List<SlicingModelInfo> {
    val q = query.trim()
    return if (q.isEmpty()) SlicingModelCatalog.all else SlicingModelCatalog.all.filter { it.label.contains(q, ignoreCase = true) || it.vendor.label.contains(q, ignoreCase = true) }
}

/**
 * Which bundled OrcaSlicer profile to slice with: a searchable list grouped by vendor. Every entry except a few is
 * "profile only" (never run on that model), and the selected entry says so.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SlicingModelPicker(selected: SlicingPrinterModel?, onSelect: (SlicingPrinterModel?) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = matchingSlicingModels(query)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            query, { query = it.take(40) }, label = { Text("Search printer models") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("slicing-model-search"),
        )
        FilterChip(selected == null, { onSelect(null) }, label = { Text("None") }, modifier = Modifier.testTag("slicing-model-none"))
        SlicingVendor.values().forEach { vendor ->
            val rows = shown.filter { it.vendor == vendor }
            if (rows.isNotEmpty()) {
                Text(vendor.label, style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { info ->
                        FilterChip(selected == info.model, { onSelect(info.model) }, label = { Text(info.label) }, modifier = Modifier.testTag(slicingModelTag(info.model)))
                    }
                }
            }
        }
        if (shown.isEmpty()) Text("No printer model matches \"$query\".", style = MaterialTheme.typography.bodySmall)
        selected?.let {
            val info = SlicingModelCatalog.info(it)
            Text(
                "Selected: ${info.label}." + if (info.verifiedOnHardware) "" else " Bundled profile, not yet confirmed on a real ${info.label}: watch the first print.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("slicing-model-selected"),
            )
        }
    }
}

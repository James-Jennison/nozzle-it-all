package net.jamesjennison.klippercompanion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp

// Two long-standing chip tags are kept so existing tests and habits still find them.
internal fun slicingModelTag(model: SlicingPrinterModel): String = when (model) {
    SlicingPrinterModel.ELEGOO_CENTAURI_CARBON -> "slicing-model-centauri-carbon"
    SlicingPrinterModel.PRUSA_XL_5T -> "slicing-model-prusa-xl"
    SlicingPrinterModel.PRUSA_XL -> "slicing-model-prusa-xl-single-tool" // would otherwise collide with the 5T tag above
    else -> "slicing-model-" + model.name.lowercase().replace('_', '-')
}

internal fun slicingVendorTag(vendor: SlicingVendor): String = "slicing-vendor-" + vendor.name.lowercase().replace('_', '-')

/**
 * Offered models whose label or vendor matches [query] (case-insensitive, blank = all), in catalog order. Profiles the
 * slicing engine can't slice yet (SlicingEngineSupport) are never offered.
 */
internal fun matchingSlicingModels(query: String): List<SlicingModelInfo> {
    val q = query.trim()
    val offered = SlicingEngineSupport.offered
    return if (q.isEmpty()) offered else offered.filter { it.label.contains(q, ignoreCase = true) || it.vendor.label.contains(q, ignoreCase = true) }
}

/** The matches grouped by vendor, vendors in catalog order, empty vendors left out. */
internal fun groupedSlicingModels(query: String): List<Pair<SlicingVendor, List<SlicingModelInfo>>> {
    val byVendor = matchingSlicingModels(query).groupBy { it.vendor }
    return SlicingVendor.values().mapNotNull { v -> byVendor[v]?.let { v to it } }
}

/**
 * Which bundled OrcaSlicer profile to slice with. About 380 models across 60 vendors are bundled, so vendors are
 * collapsed sections (a search opens every match, and the selected model's vendor stays open). Almost every entry is
 * "profile only" (never run on that model), and the selected entry says so.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SlicingModelPicker(selected: SlicingPrinterModel?, onSelect: (SlicingPrinterModel?) -> Unit) {
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(emptySet<SlicingVendor>()) }
    val groups = groupedSlicingModels(query)
    val searching = query.isNotBlank()
    val selectedVendor = selected?.let { SlicingModelCatalog.info(it).vendor }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The current choice first: with ~60 manufacturers below, a line at the bottom went unseen (found testing the
        // Add printer wizard on a real U1, where the scan had already chosen the profile).
        if (selected != null) {
            val info = SlicingModelCatalog.info(selected)
            Text("Selected: ${info.label}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("slicing-model-selected"))
            if (!info.verifiedOnHardware) Text("Bundled profile, not yet confirmed on a real ${info.label}: watch the first print.", style = MaterialTheme.typography.bodySmall)
        } else Text("Selected: none", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("slicing-model-selected"))
        OutlinedTextField(
            query, { query = it.take(40) }, label = { Text("Search printer models") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("slicing-model-search"),
        )
        FilterChip(selected == null, { onSelect(null) }, label = { Text("None") }, modifier = Modifier.testTag("slicing-model-none"))
        groups.forEach { (vendor, rows) ->
            val open = searching || vendor in expanded || vendor == selectedVendor
            TextButton({ expanded = if (vendor in expanded) expanded - vendor else expanded + vendor }, modifier = Modifier.testTag(slicingVendorTag(vendor))) {
                Text("${vendor.label} (${rows.size}) ${if (open) "\u25BE" else "\u25B8"}", style = MaterialTheme.typography.labelLarge)
            }
            if (open) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { info ->
                        FilterChip(selected == info.model, { onSelect(info.model) }, label = { Text(info.label) }, modifier = Modifier.testTag(slicingModelTag(info.model)))
                    }
                }
            }
        }
        if (groups.isEmpty()) Text("No printer model matches \"$query\".", style = MaterialTheme.typography.bodySmall)
    }
}

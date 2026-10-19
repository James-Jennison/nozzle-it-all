package net.jamesjennison.klippercompanion.project

import com.nozzleitall.printer.ext.MixedFilamentFormat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mixed-filament persistence and remap logic, pure so it's unit-testable without Room or Compose (see
 * ColourMixPersistenceTest). Mirrors Desktop's PrepareState: [applyRemoval] is `removeMixed`'s rule for one object's slot.
 *
 * The rows are stored as [MixedFilamentFormat.Row]'s own JSON (components, weights, gradient) in the project row's
 * `colorMixJson` column; the engine works out each row's slot number and colour from them, so only that much is kept.
 */
object ColourMixPersistence {
    /** The mixed rows as a project's stored text, or null when there are none to store. */
    fun encode(rows: List<MixedFilamentFormat.Row>): String? =
        if (rows.isEmpty()) null else JSONArray(rows.map { it.toJson() }).toString()

    fun decode(json: String?): List<MixedFilamentFormat.Row> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { JSONArray(json).let { a -> (0 until a.length()).map { MixedFilamentFormat.parseRow(a.getJSONObject(it)) } } }.getOrNull().orEmpty()
    }

    /**
     * After the mixed filament numbered [removedFilament] is removed: an object printing on it goes back to tool 1, and one
     * on a later mixed filament moves down one slot (the engine renumbers them), the same rule Desktop's removeMixed applies.
     */
    fun applyRemoval(toolSlotIndex: Int?, removedFilament: Int): Int? {
        val slot = toolSlotIndex ?: return null
        return when { slot == removedFilament -> 1; slot > removedFilament -> slot - 1; else -> slot }
    }
}

package net.jamesjennison.klippercompanion.project

import com.nozzleitall.printer.ext.PrusaColorMixFormat
import org.json.JSONObject

/**
 * Colour mixing (0.2.0) persistence and remap logic, pure so it's unit-testable without Room or Compose (see
 * ColourMixPersistenceTest). Mirrors Desktop's PrepareState: [applyFullSpectrumRemap] is `followRemap` for one
 * object's slot, [applyColorMixRemoval] is `removeVirtualExtruder`'s "back to tool 1" rule.
 *
 * ColorMix's virtual extruders are stored as [PrusaColorMixFormat]'s own slice-request JSON
 * (`{"version":1,"virtual_extruders":[...]}`, the same shape [PrusaColorMixFormat.sliceRequestJson] builds for a
 * slice) rather than inventing a second schema - Desktop keeps the same shape in its 3MF sidecar
 * ([PrusaColorMixFormat.SIDECAR]); Android has no 3MF export path (ProjectArchive.kt writes a .nozzleproj zip, not
 * 3MF), so this is that format kept in the project's own row instead of a sidecar file.
 */
object ColourMixPersistence {
    /** The virtual extruders as a project's `colorMixJson` column, or null when there are none to store. */
    fun encodeColorMix(virtual: List<PrusaColorMixFormat.Virtual>): String? =
        if (virtual.isEmpty()) null else PrusaColorMixFormat.sliceRequestJson(virtual)

    fun decodeColorMix(json: String?): List<PrusaColorMixFormat.Virtual> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            JSONObject(json).optJSONArray("virtual_extruders")?.let { a -> (0 until a.length()).map { PrusaColorMixFormat.parse(a.getJSONObject(it)) } }
        }.getOrNull().orEmpty()
    }

    /**
     * Snapmaker's own renumbering after a Full Spectrum mix is removed or edited (FullSpectrumFormat.Mixes.remap:
     * old slot -> new slot, 0 meaning "deleted"). An object not mentioned in [remap] keeps its slot; one mapped to 0
     * goes back to tool 1 - the same rule Desktop's PrepareState.followRemap applies.
     */
    fun applyFullSpectrumRemap(toolSlotIndex: Int?, remap: Map<Int, Int>): Int? {
        val slot = toolSlotIndex ?: return toolSlotIndex
        val mapped = remap[slot] ?: return toolSlotIndex
        return if (mapped == 0) 1 else mapped
    }

    /** Removing a ColorMix blend sends any object printing on it back to tool 1 (Desktop's removeVirtualExtruder). */
    fun applyColorMixRemoval(toolSlotIndex: Int?, removedId: Int): Int? = if (toolSlotIndex == removedId) 1 else toolSlotIndex
}

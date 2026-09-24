package net.jamesjennison.klippercompanion

import android.content.Context
import net.jamesjennison.klippercompanion.project.ProjectObject
import net.jamesjennison.klippercompanion.project.material

// Phase 8 (Consumer Slicer Plan §11, §16), first real increment - data model only this pass, not
// yet wired to any vendor's real toolchange G-code (see WORK_ORDER.md's own entry for what's
// deliberately deferred). `ToolCapability` mirrors the plan's own §11 schema
// (`ToolSlot(index, capability: SingleExtruder|IndependentTool|AmsSlot)`); `toolCountFor` is the
// real, per-printer-*model* signal this app actually has today - correcting an earlier version of
// this plan that assumed "independent multi-toolhead" was Snapmaker-U1-specific (see §11's own
// 2026-09-22 correction note): `PrinterKind.PRUSA_LINK` alone already covers both single-extruder
// (MK4/MK3.9/MINI) and 5-toolhead (XL) real hardware, so tool count must be resolved from the
// bundled machine.json a project actually targets, never assumed from vendor/PrinterKind alone.
enum class ToolCapability { SINGLE_EXTRUDER, INDEPENDENT_TOOL, AMS_SLOT }

data class ToolSlot(val index: Int, val capability: ToolCapability)

// machine.json's extruder_colour (a real, per-extruder array - confirmed against this app's own
// bundled profiles, e.g. slicer_profiles/snapmaker_u1/machine.json declaring 4 real entries,
// vs. every single-extruder profile declaring exactly 1) is OrcaSlicer's own authoritative
// per-machine extruder count - the same array nozzle_diameter/extruder_offset are always kept in
// lockstep with in every bundled profile. Falls back to 1 (single extruder) when absent/malformed
// rather than guessing higher - a missing/broken declaration must never be read as "more tools
// than this printer really has." Pure (no Context/AssetManager), matching parseBedShape's own
// testable-without-a-device convention.
internal fun parseToolCount(machineJson: String): Int {
    val obj = org.json.JSONObject(machineJson)
    val colors = obj.optJSONArray("extruder_colour")?.length() ?: 0
    // The Prusa XL 5T profile declares a single extruder_colour but five nozzle_diameter entries - the larger of the
    // two arrays is the machine's real extruder count.
    val nozzles = obj.optJSONArray("nozzle_diameter")?.length() ?: 0
    return maxOf(colors, nozzles).takeIf { it > 0 } ?: 1
}

internal fun SlicingProfilePack.readToolCount(context: Context): Int =
    parseToolCount(context.assets.open(machinePath).use { it.reader().readText() })

// Returns null exactly when slicingProfilePack() itself would (no bundled pack for this
// model/firmware-generation combination) - mirrors bedShapeFor's own convention, not a separate
// error shape.
internal fun toolCountFor(model: SlicingPrinterModel, cosmosGeneration: CosmosProfileGeneration?, context: Context): Int? =
    slicingProfilePack(model, cosmosGeneration)?.readToolCount(context)

// A single-extruder machine's one real slot is never AMS/toolchanger-shaped - real signal, not
// invented. A multi-slot machine could in principle be either INDEPENDENT_TOOL (a real physical
// toolchanger, e.g. Snapmaker U1/multiACE, Prusa XL) or AMS_SLOT (filament-swap-through-one-
// nozzle, e.g. a future multi-slot Bambu profile) - this app has no bundled profile today whose
// own machine.json distinguishes the two (OrcaSlicer's config format doesn't carry that
// distinction either; it only declares *how many* extruder identities exist, not the physical
// mechanism behind them), so toolSlotsFor conservatively reports INDEPENDENT_TOOL for every
// multi-slot case until a real per-model override is needed - not asserted as always correct,
// just the only real signal available today.
internal fun toolSlotsFor(toolCount: Int): List<ToolSlot> =
    if (toolCount <= 1) listOf(ToolSlot(0, ToolCapability.SINGLE_EXTRUDER))
    else (0 until toolCount).map { ToolSlot(it, ToolCapability.INDEPENDENT_TOOL) }

// Phase 8 follow-up (§11, §16, WO-27): the bundled filament.json's own real filament_diameter
// value (every bundled profile's own actual choice, e.g. "1.75" - not a hardcoded assumption).
// MultiToolFilamentConfig.kt's own multiToolFilamentOverrides() needs this as the base value to
// replicate into a real N-entry filament_diameter override - filament_diameter's own array
// *length* is the real signal libslic3r uses to compute how many extruders exist at slice time
// (confirmed by reading PrintApply.cpp directly - see WO-26), not machine.json's declared count,
// so a genuine multi-tool slice must extend it even when only tool *identity* (colour/type/
// temperature) is what the user actually cares about changing.
internal fun parseBaseFilamentDiameter(filamentJson: String): Double {
    val obj = org.json.JSONObject(filamentJson)
    val diameters = obj.optJSONArray("filament_diameter")
    return (0 until (diameters?.length() ?: 0)).mapNotNull { diameters?.optString(it)?.toDoubleOrNull() }.firstOrNull() ?: 1.75
}

internal fun SlicingProfilePack.readBaseFilamentDiameter(context: Context): Double =
    parseBaseFilamentDiameter(context.assets.open(filamentPath).use { it.reader().readText() })

// Phase 8 follow-up (§11, §16, WO-28): pure, so ProjectEditorScreen's own real per-object
// assignment UI can be unit-tested without a Compose test harness. `orderedObjects` is a
// project's own `ProjectObject` list in the *same order* the caller is about to hand the
// matching model files to `SlicingCoordinator.sliceProject` - both outputs are index-parallel to
// it, matching `sliceProject`'s own real `toolSlotIndices`/`slotMaterials` contract.
//
// toolSlotIndices: each object's own real, 1-based tool assignment (`toolSlotIndex`, null
// defaults to tool 1 - every real object must print on *some* tool, never left unassigned).
// slotMaterials: one real MaterialProfile per real tool slot (index i = slot i+1) - the first
// object found assigned to that slot, or null if none is (sliceProject's own real fallback then
// applies the printer's default material to that slot, not a crash or a silently wrong one).
internal fun multiToolSliceInputsFor(orderedObjects: List<ProjectObject>, toolCount: Int): Pair<List<Int>, List<MaterialProfile?>> {
    if (toolCount <= 1) return emptyList<Int>() to emptyList()
    val toolSlotIndices = orderedObjects.map { it.toolSlotIndex ?: 1 }
    val slotMaterials = (1..toolCount).map { slot -> orderedObjects.firstOrNull { (it.toolSlotIndex ?: 1) == slot }?.material() }
    return toolSlotIndices to slotMaterials
}

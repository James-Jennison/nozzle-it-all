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

// machine.json's physical extruder count: its nozzle_diameter entries, one per nozzle in every bundled profile (the Prusa
// XL 5T declares five nozzle_diameter entries but a single extruder_colour). extruder_colour is display colour, not
// hardware, and is sometimes stored as one string. Spools fed through one nozzle (CANVAS, AMS) come from the pack's
// filamentSlots instead (toolCountOf). Falls back to 1 when absent or
// malformed rather than guessing higher - a missing or broken declaration must never read as more tools than the
// printer has. Pure (no Context/AssetManager), matching parseBedShape's testable-without-a-device convention.
internal fun parseToolCount(machineJson: String): Int =
    (org.json.JSONObject(machineJson).optJSONArray("nozzle_diameter")?.length() ?: 0).takeIf { it > 0 } ?: 1

internal fun SlicingProfilePack.readToolCount(context: Context): Int =
    toolCountOf(machineText(context))

/** A pack's nozzles and filament slots: what multiToolFamily and toolSlotsFor decide from. */
data class ToolSetup(val nozzles: Int, val slots: Int) {
    val family: MultiToolFamily get() = multiToolFamily(nozzles, slots)
}

internal fun SlicingProfilePack.toolSetupOf(machineJson: String): ToolSetup =
    ToolSetup(parseToolCount(machineJson), toolCountOf(machineJson))

internal fun toolSetupFor(model: SlicingPrinterModel, cosmosGeneration: CosmosProfileGeneration?, context: Context, custom: CustomMachine? = null): ToolSetup? =
    slicingProfilePack(model, cosmosGeneration, custom)?.let { it.toolSetupOf(it.machineText(context)) }

// The pack's real slot count: machine.json's extruder count, or the pack's declared filament slots when larger (CANVAS:
// one nozzle, four lanes - see SlicingProfilePack.filamentSlots). Pure, so SlicingCoordinator and tests share it.
internal fun SlicingProfilePack.toolCountOf(machineJson: String): Int =
    maxOf(parseToolCount(machineJson), filamentSlots ?: 0)

// Returns null exactly when slicingProfilePack() itself would (no bundled pack for this
// model/firmware-generation combination) - mirrors bedShapeFor's own convention, not a separate
// error shape.
internal fun toolCountFor(model: SlicingPrinterModel, cosmosGeneration: CosmosProfileGeneration?, context: Context, custom: CustomMachine? = null): Int? =
    slicingProfilePack(model, cosmosGeneration, custom)?.readToolCount(context)

// Each slot's capability, from the same decision as multiToolFamily: one nozzle per slot is an independent tool
// (toolchanger, IDEX); on a machine whose spools share a nozzle (AMS, CFS, ACE, MMU3, CANVAS) every slot is a
// filament-swap slot.
internal fun toolSlotsFor(setup: ToolSetup): List<ToolSlot> = when (setup.family) {
    MultiToolFamily.SINGLE -> listOf(ToolSlot(0, ToolCapability.SINGLE_EXTRUDER))
    MultiToolFamily.TOOLCHANGER -> (0 until setup.slots).map { ToolSlot(it, ToolCapability.INDEPENDENT_TOOL) }
    MultiToolFamily.FILAMENT_SWAP, MultiToolFamily.MIXED -> (0 until setup.slots).map { ToolSlot(it, ToolCapability.AMS_SLOT) }
}

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

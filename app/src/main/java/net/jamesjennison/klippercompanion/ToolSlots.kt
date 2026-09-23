package net.jamesjennison.klippercompanion

import android.content.Context

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
    val colors = obj.optJSONArray("extruder_colour")
    return colors?.length()?.takeIf { it > 0 } ?: 1
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

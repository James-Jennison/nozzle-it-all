# Prepare is the workspace

Owner decision, 2026-09-27: Nozzle It All has no separate slicer workspace. The Orca workspace's feature set is the
basis of **Prepare**, rebuilt in Nozzle's own Compose UI on the shared Orca-derived engine (engine/native, the same
libslic3r source and bridge as Android and the Web App). The layout and visual design must be Nozzle's own, distinct
from OrcaSlicer, Bambu Studio, PrusaSlicer and Cura. Upstream names appear only in credits and licence text.

The Orca-derived "Advanced Workspace" program is retired: no navigation entry, no bridge, no package. Its branch
(`nozzle/advanced-workspace`) stays in a private repository for reference only.

## Reusing upstream (owner rule, 2026-09-27)

1. **Reuse, don't reinvent.** When Snapmaker Orca, OrcaSlicer or PrusaSlicer already has a feature, port its code and
   behaviour (engine changes included) instead of designing Nozzle's own logic. All three are AGPL-3.0, like Nozzle;
   credit goes in THIRD_PARTY_NOTICES.md.
2. **Their behaviour, Nozzle's look.** What a feature does comes from upstream; how it looks is Nozzle's own, and
   upstream names appear only in credits and licence text.
3. **When upstreams disagree, follow the printer's own slicer.** Snapmaker Orca's behaviour for Snapmaker printers,
   PrusaSlicer's for Prusa printers, upstream OrcaSlicer for everything else. Vendor features (Full Spectrum, Prusa's
   colour mixing) appear only for that vendor's printers, decided by capabilities, never vendor names in screens.
4. **Every departure is recorded.** Each port gets a [provenance entry](../upstream/PROVENANCE.md); anything that
   differs from upstream goes under its "Known divergence", with the reason, so a difference is never mistaken for a
   bug or the other way round.

## What Prepare must cover (the Orca workspace's feature set)

The survey behind this list, with sources and engine support per feature, is
[SLICER_FEATURE_SURVEY.md](SLICER_FEATURE_SURVEY.md).

| Area | Features | Status |
|---|---|---|
| Settings | Every engine setting, searchable, in Nozzle's own groups, generated from the engine's own metadata (`nozzle-engine --schema` → schemas/slicing/settings-schema.json, grouped by settings-groups.json) | **Done**: every setting in dense tabs (567 on the Snapmaker Orca engine base, in 28 groups), saved per project, proven to reach the engine |
| Engine input | Whole-project slice request (the 3MF with parts, per-object settings, modifiers, paint, height ranges, layer-height profile) | Next: everything below depends on it |
| Objects | Per-object and per-part settings, modifiers, negative volumes, multi-part objects, split | Planned |
| Plate | Multi-plate, auto-orient / lay flat, engine arrange, mesh repair report, measure, cut, text/SVG emboss | Planned |
| Painting | Supports, seams, colour; fuzzy skin later | Planned |
| Layers | Variable layer height, height-range modifiers, per-layer pause and colour change on one Z strip | Planned |
| Preview | Feature types, speed/flow/temperature/fan colouring, per-layer times, tool changes | Planned |
| Colour | Painted and multi-part 3MFs from Bambu Studio, Orca and PrusaSlicer: paint kept per triangle, shown on the plate, file filaments matched to slots (editable), sliced through libslic3r's own 3MF importer | **Done** |
| Materials | Full Spectrum colour mixing previews in the Filament card (**done**); mixed filament as a printable slot, prime tower, flush volumes (capability-gated) | Planned |
| Calibration | Flow, pressure advance, temperature, then retraction and max volumetric speed, each ending by saving into the material profile | Planned |
| Profiles | User profiles over the shared 376, inheritance, compare, unsaved-changes handling, bundle import/export | Planned |
| History | Undo/redo with a visible history, autosave and crash recovery | Planned (needed before painting and modifiers) |
| Keyboard | Command palette, assignable shortcuts, accessibility pass | Planned, alongside |

## Build order

1. **Done:** All settings (engine schema export, Nozzle grouping, search, validation, per-project overrides).
2. Whole-project engine request, plus undo/redo and autosave.
3. Orient, arrange, multi-plate, repair report.
4. Per-object settings and modifiers (a selection-aware inspector, not settings tabs).
5. Detailed G-code preview and the Z strip (height ranges, pauses, colour changes, variable layer height).
6. Painting: supports, seams, colour.
7. Calibration with save-to-profile.
8. Profiles: inheritance, compare, import/export.
9. Full Spectrum mixing and multi-material tools.
10. The rest of the survey's "Should" list, ordered by what people ask for.

## How Prepare differs from the incumbents

Direction from the survey (§4), used as design rules:
- An inspector that shows the settings for what's selected (plate, object, part, layer range), instead of global tabs.
- Tools named for the result ("Needs a hole", "Pause here for magnets"), not a row of icons.
- A command palette as a first-class way in, and every action reachable from the keyboard.
- One Z-axis strip beside the preview for everything per-layer.
- The plate reflects the chosen printer: bed, toolheads, and what's actually loaded.

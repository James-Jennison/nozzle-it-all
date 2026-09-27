# Upstream provenance

Every change imported or ported from an upstream project gets an entry here. Entries are never deleted; a
superseded entry gets a note. The intake workflow that produces new entries is described in
[UPSTREAM_INTAKE.md](UPSTREAM_INTAKE.md).

Fields: upstream repository and commit, what was imported, adaptations, affected subsystem and platforms, test
evidence, known divergence, and which areas it touches (PAXX, Stock U1, shared UI, slicing, packaging, project
interchange).

## P-0001 — U1 protocol rules (ported, not copied)

- **Upstream:** Snapmaker Orca fork, `/mnt/faststorage/Snapmaker-Orca/hybrid-orca-intake`, commit `11bea5c981`
  (branch `codex/upstream-feature-intake`; origin github.com/Snapmaker/OrcaSlicer). AGPL-3.0.
- **Imported:** the semantics in `src/libslic3r/U1PrintTask.{hpp,cpp}`: `print_task_config` field meanings
  (`filament_exist`, `filament_vendor`, `filament_type`, `filament_sub_type`, `filament_color_rgba`,
  `filament_official`), the `SET_PRINT_FILAMENT_CONFIG` command shape and its `FORCE=1` override, the
  `/server/files/start_local_print` body with `map_table`, and the limits of 4 physical toolheads and 32 logical
  filaments.
- **Adaptations:** re-implemented in Kotlin (`adapter-paxx/.../U1Protocol.kt`) against the shared printer model; no
  C++ was copied. Material edits always send `FORCE=1` because PAXX is the target; the Stock adapter disables
  material edits instead of sending it.
- **Subsystem / platforms:** PAXX adapter and Stock U1 adapter; Desktop now, Android and Web later.
- **Test evidence:** `PaxxLanAdapterTest` (13 tests) and `U1Protocol` parsing against the recorded U1 status in P-0002.
- **Known divergence:** the fork's colour-distance toolhead suggestion (`suggest_mapping`) is not ported yet.
- **Touches:** PAXX, Stock U1.

## P-0002 — recorded U1 Moonraker status (test fixture)

- **Upstream:** same fork and commit; file `tests/data/moonraker/u1_snapshot.json` (last changed in
  `b87f1581ac`). AGPL-3.0.
- **Imported:** the file, unchanged, as `adapter-paxx/src/test/resources/moonraker/u1_snapshot.json`.
- **Adaptations:** none.
- **Subsystem / platforms:** tests only.
- **Test evidence:** consumed by `PaxxLanAdapterTest.statusMapsRecordedU1DataOntoTheSharedModel`.
- **Touches:** PAXX, Stock U1.

## P-0003 — PAXX detection and camera paths (documentation, not code)

- **Source:** PAXX documentation at snapmakeru1-extended-firmware.pages.dev (`firmware_config.html`,
  `camera_support.html`), read 2026-09-26.
- **Used for:** detecting PAXX from `config/extended/extended2.cfg` (or `extended.cfg` before PAXX 1.1.0) and the
  `/webcam/webrtc`, `/webcam/snapshot.jpg` camera paths.
- **Test evidence:** `PaxxLanAdapterTest.probeRecognisesPaxxFromItsSettingsFile` and
  `probeRoutesStockFirmwareToTheOptionalAdapter` (against a stand-in, not a real printer).
- **Physical-printer status:** UNVERIFIED.
- **Touches:** PAXX.

## P-0004 — Snapmaker account endpoint (behaviour reference)

- **Upstream:** Snapmaker Orca fork `11bea5c981`, `src/slic3r/GUI/WebSMUserLoginDialog.cpp` (sign-in URL,
  `GET /api/common/accounts/current` with the raw token as `Authorization`, callback JSON carrying `access_token`).
- **Adaptations:** the fork signs in inside an embedded WebView; Nozzle uses the system browser and a pasted token
  instead, inside the optional Stock helper only (`stock-u1-adapter/.../SnapmakerAccount.kt`).
- **Test evidence:** `StockU1AdapterTest` against a local stand-in. Live service: UNVERIFIED.
- **Touches:** Stock U1 only.

## P-0005 — colour paint encoding (ported)

- **Upstream:** OrcaSlicer 824b216f (the engine pin), `src/libslic3r/TriangleSelector.cpp` (`deserialize`,
  `perform_split`) and `Model.cpp` (`FacetsAnnotation::set_triangle_from_string`). AGPL-3.0.
- **Imported:** the per-triangle `paint_color` encoding (hex nibbles read last to first; split codes, special side,
  children last-first; leaf states with the 0b11 escape) and the exact child geometry of 1-, 2- and 3-side splits.
- **Adaptations:** re-implemented in Kotlin (`project-format/.../Paint.kt`); PrusaSlicer's `slic3rpe:mmu_segmentation`
  is read with the same decoder. Per-part filaments from `model_settings.config` / `Slic3r_PE_model.config` are folded
  into the paint on read, so a Nozzle project carries colour in its geometry alone. Slicing hands the engine a 3MF so
  libslic3r's own importer reads the paint.
- **Subsystem / platforms:** project format and Prepare, Desktop.
- **Test evidence:** `PaintTest` (real Bambu Studio strings round-trip; leaves cover the triangle), `PaintedSliceTest`
  (painted faces print in the mapped slot), the FLEXI PANGOLIN file sliced to 77 tool changes on three toolheads.
- **Known divergence:** the saved project doesn't keep Bambu's `model_settings.config` (its part filaments are in the
  paint instead), so another slicer sees one part per object.
- **Touches:** project interchange, slicing, shared UI.

## P-0006 — filament-to-slot assignment and colour match (ported)

- **Upstream:** Snapmaker Orca, `/mnt/faststorage/Snapmaker-Orca/OrcaSlicer` at `cbf7bbb0b3`:
  `src/slic3r/GUI/filamentsync/FilamentSyncAlgorithm.cpp` (`compute_direct_override`, `compute_color_match`,
  `rgb_to_lab`) and `src/slic3r/Utils/ColorSpaceConvert.cpp` (`DeltaE00`); behaviour on open from
  `Plater::load_files`. AGPL-3.0.
- **Imported:** opening a file never matches by colour: file filament N uses slot N. Matching by colour is the
  explicit "Match" action: sRGB → linear → XYZ (D65) → CIELAB, CIEDE2000 distance, same material type first, each
  filament independently, ties to the lowest slot. `DeltaE00` is ported as written, including its hue averaging.
- **Adaptations:** Kotlin (`desktop/.../prepare/FilamentSync.kt`). Nozzle's slots are the printer's physical toolheads,
  so where Snapmaker Orca would add filament slots for a file with more filaments than loaded (or adopt the file's
  colours), Nozzle wraps filament N round the loaded slots and leaves the loaded colours as they are.
- **Subsystem / platforms:** Prepare, Desktop.
- **Test evidence:** `FilamentSyncTest`.
- **Known divergence:** the wrap-round and not adopting the file's colours, above (physical slots can't be added).
- **Touches:** shared UI, PAXX.

## P-0007 — the shared engine moves onto Snapmaker Orca (desktop first)

- **Upstream:** Snapmaker Orca `cbf7bbb0b3` (github.com/Snapmaker/OrcaSlicer), with fixes and features ported from
  upstream OrcaSlicer `824b216f` (the previous engine pin). AGPL-3.0.
- **Decision:** owner, 2026-09-27: move the shared engine onto Snapmaker Orca's libslic3r so Full Spectrum mixing is
  exactly Snapmaker's (its mixing is ~14k lines on an older OrcaSlicer base and can't be ported as a patch).
- **Imported:** Snapmaker Orca's libslic3r as it is, plus `engine/snapmaker/nozzle-engine.patch`:
  - headless build (no CURL/OpenGL/GLEW/glfw without the GUI; bridge hook; Android/browser conditions);
  - upstream's fix for `ConfigOptionEnumsGenericTempl` (no self-initialised `keys_map`, adopt it on `set()`, guard
    `serialize()`); Snapmaker's copy crashed writing the config into G-code;
  - upstream's `filament_flush_temp` / `filament_flush_volumetric_speed` settings and the `flush_temperatures`,
    `flush_volumetric_speeds`, `min_vitrification_temperature`, `max_print_z` and `initial_no_support_filament_id`
    G-code variables (owner-approved), so Bambu A1/P1/X1-family start G-code runs.
- **Adaptations in Nozzle's bridge (work on both bases):** per-feature filament keys set by whichever names the engine
  defines; a 0 in `wall_/sparse_infill_/solid_infill_filament` is treated as unset; `export_gcode` always gets a result
  object; `nozzle-engine` keeps stdout clean during libslic3r's static initialisation; the desktop CLI links the deps
  prefix's libjpeg ahead of OpenCV's copy.
- **Subsystem / platforms:** slicing. Desktop now (`engine/native/scripts/build_engine_snapmaker.sh`); Android and the
  Web App still on upstream OrcaSlicer `824b216f` until moved.
- **Test evidence:** the full desktop suite (34 tests, including a painted multicolour slice) on the pinned build; 370
  of 376 bundled printer profiles slice a test cube.
- **Known divergence:**
  - 567 engine settings instead of 723: Snapmaker's base predates about 177 of upstream's; 24 are Snapmaker-only
    (including Local-Z mixing), grouped in `settings-groups.json`.
  - Six newest Bambu printers (H2C, H2D, H2D Pro, H2S, P2S, X2D) are hidden on the desktop
    (`engine/snapmaker/unsupported-profiles.json`): they need upstream's multi-nozzle system (`filament_map`, nozzle
    groups, chamber hold). Owner decision: hide until ported and tested on a real machine, no stand-in values.
  - Upstream's fast-purge flush temperature (`filament_flush_temp_fast`, `prime_volume_mode`) isn't ported.
- **Touches:** slicing, packaging, PAXX (U1 prints), Bambu profiles.


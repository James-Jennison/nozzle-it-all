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
  Web App moved on 2026-09-27 (P-0010).
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

## P-0008 — Full Spectrum colour mixing (ported)

- **Upstream:** Snapmaker Orca `cbf7bbb0b3`: `src/slic3r/GUI/MixedColorMatchHelpers.cpp` (build_best_color_match_recipe and
  helpers, build_color_match_presets), `MixedFilamentBatchDialog.cpp` (Color Mixing Match: palette, reuse pass, match
  worker, id assignment and merging), `MixedFilamentDialog.cpp` (the four editor modes), `Plater.cpp` (apply path,
  Color Mixing panel, cleanup_unused_filaments_after_batch_match), `ColorSpaceConvert.cpp` (RGB2Lab, DeltaE00),
  `PresetBundle.cpp` (id remap; per-slot filament composition with flow-variant segments), and libslic3r's
  `MixedFilamentManager` used directly; the colour library `filaments_colours.json` and the U1 filament profiles from
  `resources/profiles/Snapmaker`. AGPL-3.0.
- **Imported:** the C++ runs inside `nozzle-engine --full-spectrum` (engine/native/bridge/full_spectrum.cpp); Nozzle's
  screens call it for every value (mix rows, labels, display colours, previews, searches, validation messages).
  Per-slot filament profiles are composed in Kotlin exactly as PresetBundle::full_fff_config() does
  (desktop/.../prepare/FilamentLibrary.kt).
- **Subsystem / platforms:** Prepare, slicing, Desktop. Mixes are Snapmaker's `mixed_filament_definitions`.
- **Test evidence:** FullSpectrumTest (6): labels and slot numbers, the owner's Snapmaker screenshot mapping, a painted
  mix printing as its two filaments alternating, per-slot profiles reaching the printer (temperatures per tool),
  renumbering and clean-up, the four editor modes.
- **Known divergence:**
  - Physical slots are printer toolheads: clean-up after a match never deletes them (Snapmaker deletes unused project
    filaments), and a filament still used by a surviving mix counts as used.
  - Removing the last mix with "−" renumbers like the row menu's Delete (Snapmaker's "−" builds no remap).
  - Match mode's search can be limited to chosen slots (`slots`), which Snapmaker's dialog doesn't offer.
  - Model colours from PrusaSlicer files aren't used (Snapmaker loads those as geometry only); colours are matched from
    what the model shows.
  - Filament profiles per slot are bundled for the Snapmaker U1 (0.4 nozzle) only so far.
- **Touches:** PAXX (U1), slicing, shared UI, project interchange.

## P-0009 — PrusaSlicer ColorMix (virtual extruders) (ported)

- **Upstream:** PrusaSlicer `version_2.9.6` (github.com/prusa3d/PrusaSlicer): `src/libslic3r/Feature/FullSpectrum/
  VirtualExtruder.{hpp,cpp}` and its integration in PrintObject, Print, PrintRegion, PrintApply, PrintObjectSlice,
  MultiMaterialSegmentation and TriangleSelector; `bundled_deps/prusa_fdm_mixer` (MIT); FullSpectrumDialog's
  numbering; presets from 3.0.0-alpha12's `VirtualExtruderPresets.cpp` (2.9.6 builds them inside its dialog). AGPL-3.0.
- **Imported:** into the Snapmaker-based engine patch (engine/snapmaker/nozzle-engine.patch); slicing takes the virtual
  extruders through a `virtual_extruders` request line (PrusaSlicer's sidecar format); `nozzle-engine --color-mix`
  (engine/native/bridge/color_mix.cpp) serves normalising, colour prediction, layer cycles, presets, numbering and
  the import remap to Nozzle's screens (desktop/.../prepare/PrusaColorMix*.kt). Projects keep PrusaSlicer's own
  `Metadata/Prusa_Slicer_full_spectrum.json`.
- **Also fixed in the engine patch:** Snapmaker's `Print::m_isBBLPrinter` was never initialised headless (its GUI and
  CLI set it), so headless slices picked Bambu-style or generic G-code at random; it is now false, as upstream Orca.
- **Test evidence:** PrusaColorMixTest: a 2:1 blend normalises to PrusaSlicer's cycle [1,2,1]; a cube on virtual
  extruder 6 on the Prusa XL 5T prints T0,T1,T0 layer by layer (and painted state 6 likewise, engine-side); numbering,
  presets and the sidecar round-trip. Snapmaker Full Spectrum tests still pass.
- **Known divergence:**
  - Offered on every printer with two or more slots, as PrusaSlicer does, except those with Snapmaker's Full Spectrum
    (owner rule, 2026-09-27: one mixing system per printer).
  - The engine doesn't read the sidecar from the 3MF itself (2.9.6's 3mf.cpp reader isn't ported): Nozzle reads it and
    passes it with the slice request.
  - A display colour read from a file is shown but not kept as an override (PrusaSlicer writes the effective colour
    whether or not it was overridden, so the two can't be told apart).
  - Bambu profiles now always take the generic G-code path (Snapmaker's CLI sets the flag from printer_model; the bridge
    doesn't yet).
  - Gradients are shown and printed from files but, as in PrusaSlicer's dialog, not edited.
- **Touches:** slicing, Prusa profiles, shared UI, project interchange.

## P-0010 — Android and the Web App move onto the Snapmaker Orca engine

- **Upstream:** Snapmaker Orca `cbf7bbb0b3` + `engine/snapmaker/nozzle-engine.patch`, the same source as the desktop
  engine (P-0007). AGPL-3.0.
- **Decision:** owner, 2026-09-27: every platform slices on the same base, so Full Spectrum mixing, PrusaSlicer
  ColorMix virtual extruders and per-slot filament profiles behave identically everywhere.
- **Moved:**
  - Android (arm64-v8a `libslic3rengine.so`): `engine/snapmaker/android/prepare_engine_root.sh` makes an engine root
    (`/mnt/faststorage/build-work/nozzle-android-sm`) from an untouched export of the pin; Gradle builds it by default
    (`-PnozzleEngine=snapmaker`; `-PnozzleEngine=upstream` still builds upstream OrcaSlicer `824b216f`, and
    `ORCASLICER_ENGINE_ROOT` still overrides both). The SBOM follows the chosen base.
  - Web App (`nozzle-engine.{js,wasm}`): `engine/wasm/scripts/build_engine_snapmaker.sh` (`INSTALL=1` puts it in
    `web/public/engine`); `build_engine.sh` still builds the upstream engine.
- **Addenda to the desktop patch (build-only, no slicing change):** `engine/snapmaker/libcxx-includes.patch`
  (`<sstream>` in LocalesUtils.cpp for LLVM libc++, Android and Web); `engine/snapmaker/android/0001-android-subproject-
  paths.patch` (NSIS snippet paths, because the engine is a CMake subproject of the app); the browser's existing
  patches 0001–0003 (0004 patches a file this base doesn't have) plus `engine/wasm/patches-snapmaker/0001-wasm32-nop-
  layer-id.patch` (32-bit `size_t` narrowing; unchanged value on 64-bit). `USE_BLOSC` is set off for both (their
  OpenVDB has no Blosc; this base's FindOpenVDB otherwise assumes it).
- **Dependencies:** unchanged except GMP 6.2.1, rebuilt with its C++ classes (`gmpxx.h`, `libgmpxx.a`) for both
  platforms, because this base's bundled libigl uses `mpq_class`. Android reuses orcaslicer-android-engine's prefix
  read-only (a symlink tree with the rebuilt GMP on top); the browser prefix's GMP was rebuilt in place.
- **Unsupported profiles:** the six Bambu profiles in `engine/snapmaker/unsupported-profiles.json` are no longer offered
  on Android (`SlicingEngineSupport`, bundled from the same JSON by the domain module; the picker leaves them out and a
  printer saved with one gets a clear "can't be sliced by this version yet" instead of a slice) or on the Web App
  (`profileIndex()` filters them; `loadProfile()` refuses them).
- **Also:** Android's and the Web App's slice stats read Bambu's G-code layout (per-filament weights, time in the
  header), as the desktop's do since 6d0ffe0/c49b14c.
- **Test evidence (2026-09-27):** Android: the Snapmaker engine root builds through Gradle (`libslic3rengine.so` with
  the same 19 JNI exports); `:app:testDebugUnitTest` 639 tests, 0 failures (1 live-server test skipped) and `:domain:test`
  pass; the upstream path still builds. Web: `smoke_node.mjs` slices the 20 mm cube on the U1 (3.70 g, 9m 13s; the
  upstream engine gives 3.70 g, 9m 2s), Prusa MK4, Prusa XL 5T, Bambu X1 Carbon and Centauri Carbon AFC profiles;
  vitest 26/26; Playwright layout + slice specs 34/34 (Chromium and Firefox) against a local `vite preview`.
- **Sizes:** `libslic3rengine.so` 57.56 MB → 54.41 MB (−3.14 MB, stored uncompressed in the APK; about −0.9 MB
  deflated in the AAB). `nozzle-engine.wasm` 15.44 MB → 13.02 MB (gzip 5.20 → 4.41 MB).
- **Known divergence / not yet done:**
  - The Android engine has not sliced on a device yet (only host builds and unit tests were run); slice a cube on a
    phone before a release. `-PnozzleEngine=upstream` is the way back.
  - CI (`.github/workflows/ci.yml`) builds this engine too since 2026-09-27: `engine/snapmaker/android/ci_engine_root.sh`
    fetches the pinned commit and GMP archive on the runner and prepares the root, checking every pinned hash.
  - Everything listed under P-0007's divergences applies to Android and the Web App too.
- **Touches:** slicing, Android packaging, Web App engine, Bambu profiles.

## P-0011 — Snapmaker U1 profile family, bed type and nozzles (ported)

- **Upstream:** Snapmaker Orca `cbf7bbb0b3`: `resources/profiles/Snapmaker` (machines "Snapmaker U1 (0.2/0.4/0.6/0.8
  nozzle)", their compatible process presets and filaments), and the printer card's rules in `src/slic3r/GUI/Plater.cpp`
  (bed-type list and default, nozzle tabs: diameter switches the whole printer preset, flow per nozzle) with
  `Preset::get_default_bed_type` and `FlowTypeHelper.cpp`. AGPL-3.0.
- **Imported:** `engine/snapmaker/library/snapmaker_u1` (scripts/bundle_printer_library.py) replaces the filament-only
  bundle; Prepare's printer card offers Snapmaker's bed types, nozzle diameters and per-nozzle flow, the Print header
  lists Snapmaker's process presets (e.g. "0.10mm Color Mixing"), and each nozzle size has its own filaments.
- **Test evidence:** PrinterSetupTest, PrinterLibraryTest (a 0.6 mm slice uses a 0.6 nozzle and 0.30 mm layers).
- **Known divergence:**
  - Snapmaker's U1 machines name "Snapmaker PLA" as the default filament, which is compatible only with its A-series;
    Nozzle uses "Snapmaker PLA Basic @U1" (0.4) or Generic PLA (other sizes) instead of the GUI's first-visible
    fallback. The 0.2 mm machine's "0.10 Standard" default doesn't exist; the first compatible process is used.
  - Printers without a profile family keep Nozzle's guided Draft/Standard/Fine layer heights until their families are
    bundled from their own slicers.
- **Touches:** PAXX (U1), slicing, shared UI.

## P-0012 — filament-changer lanes over Moonraker (ported)

- **Upstream:** OrcaSlicer `824b216f`, `src/slic3r/Utils/MoonrakerPrinterAgent.cpp` (`fetch_filament_info`,
  `fetch_moonraker_filament_data`, `fetch_hh_filament_info`). AGPL-3.0. Payload shape checked against
  AFC-Klipper-Add-On `484a09b` (`extras/AFC_lane.py` `send_lane_data`), the commit OpenCentauri COSMOS ships for CANVAS.
- **Imported:** Klipper printers other than the U1 report their filament changer's lanes as material slots: Moonraker's
  `lane_data` database namespace first (AFC, including the Elegoo CANVAS on COSMOS as lanes `CANVAS_1`–`CANVAS_4`), then
  Happy Hare's `mmu` object. A lane's slot is the tool it is mapped to, so Prepare's colour matching lines up with the
  file's `T` numbers. Desktop (`adapter-paxx` `FilamentLanes.kt`) and Web App (`web/src/printers/paxx.ts`).
- **Test evidence:** FilamentLanesTest (6, including a fake COSMOS Moonraker), web `printers.test.ts` lane tests.
- **Known divergence:**
  - Upstream fetches lanes only when the user syncs filaments; Nozzle reads them with each status reading, and asks for a
    missing `lane_data` namespace at most once a minute.
  - The printer has one nozzle; its temperature is shown on the lane feeding it (AFC `current_load`, Happy Hare `tool`),
    else on the first lane. Upstream has no equivalent (it fills Bambu-style AMS trays).
  - Upstream maps a lane's material to a filament preset id; Nozzle picks the slot's filament profile by type with its
    own matcher, as for every other printer.
  - Not on Android yet (its Moonraker client has its own U1-shaped material model). Not verified against a real CANVAS.
- **Touches:** Klipper printers (COSMOS, AFC, Happy Hare), Prepare material slots.

## P-0013 — Elegoo Centauri Carbon and Centauri Carbon 2 stock-firmware CANVAS profiles (imported)

- **Upstream:** ElegooSlicer (github.com/ELEGOO-3D/ElegooSlicer, Elegoo's OrcaSlicer fork) `2d507e39a9`,
  `resources/profiles/Elegoo` (bundle version 01.05.03.05): machines "Elegoo Centauri Carbon 0.2/0.4/0.6/0.8 nozzle" and
  "Elegoo Centauri Carbon 2 0.2/0.4/0.6/0.8 nozzle", the process presets and filament presets marked compatible with
  them. Defaults compared against its `src/libslic3r/PrintConfig.cpp`, flush logic against `Print.cpp` / `GCode.cpp`.
  AGPL-3.0. Owner rule: for Elegoo printers follow Elegoo's own slicer.
- **Imported:** `scripts/bundle_elegoo_canvas.sh` (`flatten_orca_profile.py` / `bundle_printer_library.py` gained
  `--nested` for ElegooSlicer's sub-folder layout, `--pin` and `--source`; the U1 library regenerates byte-identical).
  - Packs for every platform: `app/src/main/assets/slicer_profiles/elegoo_centauri_carbon_canvas` ("Elegoo Centauri
    Carbon 0.4 nozzle (CANVAS)") and `elegoo_centauri_carbon_2_canvas` ("Elegoo Centauri Carbon 2 0.4 nozzle (CANVAS)"),
    listed in index.json by hand with `tools: 4` (as the COSMOS AFC pack; build_profile_index.py would reset tools to 1).
    New ids: no stock Centauri Carbon profile was bundled before (the upstream-OrcaSlicer one is deliberately excluded,
    and the COSMOS ids are unchanged), so no saved profile id moves.
  - Desktop families: `engine/snapmaker/library/elegoo_centauri_carbon{,_2}_canvas` (4 nozzle sizes, 21 process
    presets, 36 / 41 Elegoo and Generic filament presets), so Prepare offers ElegooSlicer's nozzles, process presets
    and a per-slot Elegoo filament preset. (`bundle_printer_library.py`'s ids now spell "+" as "plus": "Elegoo PLA+"
    had collided with "Elegoo PLA"; no U1 id changes.)
  - Multi-colour is ElegooSlicer's: each change is `M6211 T[next_extruder] L[flush_length] M.. N.. Q.. R.. S..` then
    `T[next_extruder]` (the printer swaps and purges; `purge_in_prime_tower 0`, prime tower on), and the start G-code
    loads the first filament with `M6211 A1 L200 T..`. The engine computes `flush_length` the way ElegooSlicer does
    for this setup (flush volume x flush multiplier / filament area; the first, priming change to the loaded filament is
    `L0` in both).
- **Test evidence (2026-09-27):** ElegooCanvasTest `elegooStockCanvasProfilesSliceFourColoursWithM6211` slices a
  four-colour cube on each printer twice (the pack, as the Web App does, and the Desktop's 0.4 mm family machine with
  four different Elegoo PLA presets): about 295 M6211 changes to T0-T3 per slice, each followed by the same T, 34.92 mm purge
  (84 mm³ x 1), ElegooSlicer's CC/CC2 start and end blocks, Textured PEI bed temperature, no M600, no COSMOS macros.
  Whole desktop suite 49/49; the Web App engine (`smoke_node.mjs`) slices both packs; vitest 29/29.
- **Known divergence:**
  - Pinned where ElegooSlicer's profile leaves a setting unset and its default differs from Snapmaker Orca's (process
    files only): `flush_multiplier` 1 (Snapmaker Orca 0.3, which would purge 30% of ElegooSlicer's length),
    `independent_support_layer_height` 1 (0), `slowdown_for_curled_perimeters` 0 (1), `curr_bed_type` Textured PEI
    Plate (the machines' `default_bed_type 4`, which ElegooSlicer's GUI and the Desktop's printer card select; without
    it the Web App slices at Cool Plate's 35 °C).
  - Not pinned (different meaning in the two engines): ElegooSlicer's `wall_direction` default ccw with holes reversed
    vs Snapmaker Orca's auto; `ironing_angle` 0 relative vs -1; ElegooSlicer's `extruder_clearance_max_radius` 68 is
    renamed to `extruder_clearance_radius` there but dropped here (65 is used; affects only print-by-object clearance).
  - Keys this engine does not know, dropped at load: `auto_toolchange_command`, `bed_texture_area`, `support_mms`,
    `support_wan_network` (ElegooSlicer GUI / network upload only), `overhang_speed_classic`,
    `tree_support_branch_diameter_double_wall`, `adaptive_layer_height`, `internal_bridge_support_thickness`
    (ignored or undefined in ElegooSlicer's engine too). `wall_infill_order` and `chamber_temperatures` are converted
    by both engines' legacy handling. None changes the G-code.
  - Flush volumes: Nozzle's multi-material recipe uses one flush volume for every colour pair (84 mm³, all platforms);
    ElegooSlicer computes each pair from the colours and, for the Centauri Carbon 2, overrides some pairs from
    `flush/flush_volumes.json` (up to 900 mm³ from black to light colours). Purges can be shorter than ElegooSlicer's
    for dark-to-light changes; not ported.
  - The Web App and Android use only the 0.4 mm pack; Android doesn't list index.json profiles (as for COSMOS AFC).
  - For stock firmware only: the Centauri Carbon's start G-code calls `M729`, which e-stops COSMOS 26.07+. No firmware
    guard ties these ids to stock firmware on the Desktop or Web App.
  - Not yet printed on a real Centauri Carbon, Centauri Carbon 2 or CANVAS.
- **Touches:** Elegoo profiles, Prepare (nozzle sizes, process presets, filament presets), shared profile index.


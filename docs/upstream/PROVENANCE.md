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
  material edits instead of sending it. Every U1 start is `server.files.start_local_print` as JSON-RPC on Moonraker's
  websocket (as `U1LanPrintHost::start_print` does; Snapmaker/u1-moonraker a308cfa registers it for every transport
  except HTTP), with `bed_level: 1` so the firmware runs the start G-code's adaptive bed mesh.
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
  - Android reads the same lanes since P-0016 (shown, not colour-matched). Not verified against a real CANVAS.
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


## P-0014 — Elegoo Centauri Carbon / Centauri Carbon 2 LAN adapter with CANVAS slots (ported)

- **Upstream:** elegoo-link (github.com/ELEGOO-3D/elegoo-link) `46c7b814e0`, Apache-2.0, Copyright 2025 Shenzhen Elegoo
  Technology Co., Ltd.; the SDCP V3.0.0 document (github.com/cbd-tech/SDCP-Smart-Device-Control-Protocol-V3.0.0
  `f977215761`, no licence file, used as a protocol reference); ElegooSlicer `2d507e39a9` (AGPL-3.0), read only for
  its send dialog's defaults and slot mapping (`src/slic3r/GUI/Elegoo/PrintSendDialogEx.cpp`,
  `src/slic3r/Utils/Elegoo/ElegooLink.cpp`, `PrinterMmsManager.cpp`).
- **Imported (re-implemented in Kotlin, nothing copied), per file of `adapter-elegoo/`:**
  - `Sdcp.kt` / `SdcpSession.kt` — Centauri Carbon: `src/lan/adapters/elegoo_fdm_cc/elegoo_fdm_cc_protocol.cpp`
    (ws://host:3030/websocket, "ping" heartbeat), `elegoo_fdm_cc_message_adapter.cpp` (request envelope with outer
    `Id` = MainboardID, empty `Topic`, `From` 1; commands 0 status, 1 attributes, 128 start with `slot_map`, 129 pause,
    130 stop, 131 resume, 324 CANVAS; machine and print sub-status enums; status field names; Ack handling), the SDCP
    document (discovery, topics, Ack codes 1-7).
  - `Cc2.kt` / `Cc2Session.kt` / `MiniMqtt.kt` — Centauri Carbon 2: `src/lan/adapters/elegoo_fdm_cc2/elegoo_fdm_cc2_protocol.cpp`
    (MQTT 1883, user `elegoo`, access code or `123456`, client id `1_PC_nnnn`, topics, registration and its errors,
    `{"type":"PING"}` every 10 s), `elegoo_fdm_cc2_message_adapter.cpp` (methods 1002 status, 1020 start with
    `config.slot_map`, 1021 pause, 1022 stop, 2005 CANVAS, 6000 status events merged into the cached full status;
    machine_status/sub_status meanings; error codes), `src/lan/protocols/mqtt_protocol.cpp` (clean session, 60 s
    keep-alive, QoS 1). The MQTT client is a minimal MQTT 3.1.1 implementation instead of Paho.
  - `Canvas.kt` — both adapters' `handleCanvasStatus` (canvas_list/tray_list fields), ElegooSlicer's tray status
    reading (0 empty, 1 pre-loaded, 2 loaded) and blank-brand rule; `slot_map` items `{t, canvas_id, tray_id}`.
  - `ElegooUpload.kt` — `elegoo_fdm_cc_http_transfer.cpp` (multipart POST /uploadFile/upload, 1 MiB pieces, Check,
    S-File-MD5, Offset, Uuid, TotalSize; `code` "000000") and `elegoo_fdm_cc2_http_transfer.cpp` (PUT /upload with
    Content-Range, X-File-Name, X-File-MD5, X-Token; `error_code` 0).
  - `ElegooDiscovery.kt` — `src/lan/discovery/printer_discovery.cpp` and both discovery strategies ("M99999" to UDP
    3000; `{"id":0,"method":7000}` to UDP 52700), asked of one address only.
- **Adaptations:** LAN and private-network hosts only (checked before any packet); bounded replies; no retries and
  no auto-reconnect of commands; outcomes follow Nozzle's rules (non-zero Ack / error_code = Rejected, silence or a
  lost connection after sending = Unknown). Slots map to `PrinterStatus.toolheads` (canvas by canvas, tray by tray,
  from 0) and `StartJob.toolheadMap` maps back to the printer's own canvas/tray ids. Busy machine states (levelling,
  receiving a file, homing, ...) map to STARTING so no action is offered. Start uses ElegooSlicer's defaults
  (levelling off, time-lapse off, plate type 0 / "A").
- **Subsystem / platforms:** printer adapters; Desktop only (`nozzleAdapters` default now includes `elegoo`). New
  printer family `elegoo` (PrinterFamily.ELEGOO, glossary `family.elegoo`).
- **Test evidence (2026-09-27):** `:adapter-elegoo:test` 37 tests (fake SDCP printer on MockWebServer's WebSocket,
  in-test MQTT broker, UDP responders, MockWebServer uploads), `:printer-api:test`, `:desktop:test` (incl.
  MultiVendorAcceptanceTest) pass. Fixtures are built from the SDCP document's samples and elegoo-link's field names,
  not hardware captures.
- **Known divergence / unverified:**
  - Never run against a real Centauri Carbon or Centauri Carbon 2.
  - Upload port: elegoo-link posts the Centauri Carbon's pieces to the bare address (port 80); the SDCP document
    says port 3030. Nozzle follows elegoo-link.
  - Cmd 324's reply shape: the CC adapter reads the slots from `Data.Data`, the CC2 adapter from `canvas_info`;
    both are accepted. Whether CANVAS ids count from 0 or 1 isn't assumed: the printer's own ids are sent back.
  - SDCP `CurrentTicks` is read as seconds, as elegoo-link does (the SDCP document says milliseconds).
  - Centauri Carbon 2: no resume in elegoo-link's LAN method table, so pause and resume are not offered; a printer in
    cloud mode (lan_status 0) is untested.
  - No camera, temperature, motion, light, fan or material-edit controls; no Web App port. Android uses these sessions through `:transport` (P-0016).
- **Touches:** printer adapters, shared model (new family), glossary, Desktop's Add printer form.

## P-0015 — flushing volumes from filament colours, each printer's own slicer's way (ported)

- **Upstream** (AGPL-3.0), one calculation per printer family, following each printer's own slicer:
  - **Snapmaker Orca** `cbf7bbb0b3` (the U1 and other Snapmaker printers): `src/libslic3r/FlushVolCalc.cpp` with `RGB2HSV`
    from `src/slic3r/Utils/ColorSpaceConvert.cpp`; into support 230 mm³, out of support at least 420, cap 800.
  - **OrcaSlicer** `824b216f` (every profile from Orca's library: COSMOS, Bambu, Prusa and the rest): `FlushVolCalc.cpp` +
    `FlushVolPredictor.cpp`, which first uses measured flushes (`resources/flush/flush_data_*.txt`, chosen by the machine's
    `nozzle_flush_dataset`) for colours within CIEDE2000 5 of measured ones, then the same colour formula; out of support
    at least 700, cap 20000.
  - **ElegooSlicer** `2d507e39a9` (Elegoo printers): OrcaSlicer's, plus its per-printer overrides (`FlushVolumeRules.cpp`,
    `resources/profiles/Elegoo/flush/flush_volumes.json`, e.g. the CC2's 900 mm³ black to white), snapped to
    `StandardColorMatcher.cpp`'s palette.
  - All three: `Plater.cpp` `Sidebar::auto_calc_flushing_volumes` (matrix and support rules), `get_min_flush_volumes` (the
    machine's `nozzle_volume`, less a long retraction when cutting), `WipeTowerDialog.cpp` (the Flushing volumes window:
    matrix shown times the multiplier, Auto-calculate, multiplier 0–3).
- **Imported:** every multi-slot slice's `flush_volumes_matrix` is worked out from the slot colours instead of a fixed
  84 mm³: `:domain` `FlushVolumes` (desktop and Android, with the measured-flush data and Elegoo's rules as resources),
  `web/src/project/flush.ts` (Web App). The method is picked from the machine profile's model. The desktop's Filament card
  has the Flushing volumes window; an edited matrix is saved with the project's settings, and a slot whose colour changes
  later has its row and column worked out again, as upstream does.
- **Test evidence:** `schemas/fixtures/flush-volumes.json` is generated by `scripts/flush_volumes_golden.sh`, which compiles
  each slicer's own code (only Elegoo's rule-file loader is replaced by the same rules compiled in, and its short lookup
  restated): 6,144 Snapmaker, 4,428 OrcaSlicer (datasets 0–2) and 2,788 ElegooSlicer pairs. `:domain` FlushVolumesTest and
  the Web App's tests match every one. FlushVolumesSliceTest slices a two-colour cube on the CC2 CANVAS profile and reads
  the matrix (900 mm³ black to white) back from the G-code header. The shared `multitool.json` fixture now expects
  Snapmaker Orca's values for its input.
- **Known divergence:**
  - Multi-colour filaments (Bambu AMS multi-colour spools, where upstream takes the worst pair) aren't modelled; each slot
    has one colour.
  - Android works out every slot from the pack's base filament (support flags per slot aren't known there).
  - The Web App has no editor; it always works the matrix out from the colours.
  - Printers whose own slicer isn't OrcaSlicer-based (PrusaSlicer's `wiping_volumes_matrix`) use OrcaSlicer's
    calculation, as their bundled profiles come from Orca's library.
- **Touches:** multi-material slicing on every platform, Prepare's Filament card.

## P-0016 — Elegoo CANVAS on Android: profiles, Moonraker lanes, stock-file guard, Elegoo connection (ported)

- **Upstream:** no new upstream source; Android reuses what P-0011–P-0014 already ported. OrcaSlicer `824b216f`
  `MoonrakerPrinterAgent.cpp` lane rules (P-0012) re-implemented in `:domain` `FilamentSlots.kt` (`FilamentLanes`) from the
  desktop's `adapter-paxx` `FilamentLanes.kt`; the M729/M8213 rule from `U1Protocol.stockElegooCommand` (P-0012's
  follow-up, docs.opencentauri.cc) in `:domain` `ElegooProfiles.kt`; the Elegoo sessions (P-0014, elegoo-link
  Apache-2.0) used as they are: `:transport` now depends on `:adapter-elegoo`.
- **Imported:**
  - Android offers the three CANVAS packs as slicing models `ELEGOO_CENTAURI_CARBON_COSMOS_CANVAS`
    (`elegoo_centauri_carbon_cosmos_afc`), `ELEGOO_CENTAURI_CARBON_CANVAS` and `ELEGOO_CENTAURI_CARBON_2_CANVAS`
    (appended to the generated catalog; `scripts/bundle_vendor_profiles.py` keeps them in an `ADDED` list so a rerun
    neither drops their folders nor reorders the enum). Each has four filament slots (the index's `tools`: 4) although
    its machine.json declares one extruder, so multi-colour slicing passes four slots.
  - Firmware pairing (`ElegooProfiles.connectionProblem`, checked on save, in the wizard and before every slice): a COSMOS
    pack (plain or CANVAS) only for a Moonraker printer, and it keeps the existing live COSMOS-version guard unchanged; an
    Elegoo-firmware pack only for a printer connected as "Elegoo". Firmware-specific packs never take a custom machine.
  - Klipper printers' filament-changer lanes (AFC `lane_data`, then Happy Hare `mmu`) are read by Android's Moonraker
    client and shown under "Filament slots"; a COSMOS printer that reports an `AFC` object is suggested the CANVAS pack
    by "Scan network".
  - Android's Moonraker upload (`LiveFileChanges`) refuses a file containing M729/M8213 before any request.
  - New printer type "Elegoo" (`PrinterKind.ELEGOO`, `ElegooPrinterService`): status, CANVAS slots, send-and-start,
    pause/resume/cancel through the adapter's own `ActionGuard`; "Scan network" also sends Elegoo's two discovery
    messages (UDP 3000 "M99999", UDP 52700 method 7000) and parses replies with the adapter's `Sdcp`/`Cc2` rules.
- **Test evidence (2026-09-27):** `:app:testDebugUnitTest` 667 tests, 0 failures (new: ElegooProfilesTest,
  ElegooPrinterProfileSaveTest, FilamentLanesTest with the desktop's lane data, ElegooPrinterServiceTest over a fake
  session built with the adapter's own parsers and fixtures); `:adapter-elegoo:test` 37, `:transport:check`,
  `:app:lintDebug` 0 errors. Fakes only.
- **Known divergence:**
  - Android has no printer-driven colour matching, so lanes are shown, not matched to project slots; the desktop's
    placing of the nozzle temperature on the feeding lane is not reproduced (Android shows it in the status header).
  - Android reads lanes when the Filament slots panel opens, not with every status poll.
  - Start slot map: Android maps only the filaments the file selects (bare `T<n>` lines), leaving others unmapped; the
    desktop and Web App map every project slot. A single-colour file, or a printer without CANVAS, sends an empty map.
  - The Elegoo protocol is chosen from the printer's slicing model (Centauri Carbon 2 pack: MQTT; otherwise SDCP), not
    stored separately. No file list, camera or firmware-identity read for Elegoo printers.
  - `site-src/printer_models.json` (written by the generator) was not regenerated.
  - Nothing here has run against a real Centauri Carbon, Centauri Carbon 2 or CANVAS.
- **Touches:** Android slicing-model catalog, Add printer / Edit printer, slicing, Moonraker uploads, printer dashboard.

## P-0017 — per-object print settings (ported)

- **Upstream:** Snapmaker Orca `cbf7bbb0b3` (AGPL-3.0): `src/slic3r/GUI/GUI_Factories.cpp` `SettingsFactory`
  (`OBJECT_CATEGORY_SETTINGS`, `PART_CATEGORY_SETTINGS`, `get_visible_options(category, false)`, `get_options(false)`: the
  object menu's categories and order, and that any object or region option may be set on one object);
  `src/libslic3r/Format/bbs_3mf.cpp` (an object's config is written as `model_settings.config` object metadata beside
  `name`, `module` and `extruder`).
- **Imported:** the engine request takes `object_set\t<object>\t<key>\t<value>` lines (desktop CLI and the browser
  engine's parser); the shared engine sets them on that `ModelObject::config`, refusing keys that aren't object or region
  options. `nozzle-engine --schema` marks those keys `perObject`. The project manifest keeps each object's `settings`;
  Orca/Bambu 3MFs' object settings are read on open. The desktop's Objects card edits the selected object's settings
  (upstream's menu by category, each starting from the plate's value, reset removes it).
- **Test evidence:** ObjectSettingsTest (a two-cube plate where one cube has 100 % infill and 5 walls uses clearly more
  filament; an invalid key is refused), ObjectSettingsManifestTest, PaintTest (Bambu object metadata read as settings).
- **Known divergence:**
  - Per-part settings, modifiers and height ranges aren't editable yet (the part list exists upstream).
  - Nozzle doesn't write `model_settings.config`, so another slicer opening a Nozzle-saved project doesn't see its
    per-object settings (Nozzle's manifest keeps them).
  - Adding a single model from an Orca 3MF (rather than opening it as a project) doesn't carry its object settings.
  - The Web App and Android don't edit or send per-object settings yet (the browser engine's parser accepts them once
    rebuilt).
- **Touches:** Prepare objects, project format, slicing on the desktop.

## P-0018 — plate tools: Arrange, Auto orient, Split, Cut (ported)

- **Upstream:** Snapmaker Orca `cbf7bbb0b3` (AGPL-3.0), run by the engine itself (`nozzle-engine --plate`,
  `engine/native/bridge/plate_ops.cpp` over `slic3r_engine.cpp`): Arrange as `src/slic3r/GUI/Jobs/ArrangeJob.cpp` does it
  (`init_arrange_params`, then `libslic3r/ModelArrange.cpp` `get_instance_arrange_poly`, `Arrange.cpp`
  `update_arrange_params`, `update_selected_items_inflation`, `update_selected_items_axis_align`, `get_shrink_bedpts`,
  `arrangement::arrange`); Auto orient as `libslic3r/Orient.cpp` `orient(ModelInstance*)` (AutoOrienter); Cut with
  `TriangleMeshSlicer.cpp` `cut_mesh` (capped halves). Split is `TriangleMesh.cpp`/`MeshSplitImpl.hpp` `its_split`
  (triangles sharing an edge form one part, after merging coincident vertices as `its_merge_vertices` does) ported to
  Kotlin (`PlateOps.split`) so each part keeps its painted colours.
- **Imported:** the desktop plate toolbar (Arrange, Auto orient for the selection or everything, Split, Cut at a height);
  Arrange replaces Nozzle's own row packer, and adding or duplicating a model arranges the plate with it.
- **Test evidence:** PlateOpsTest (split keeps paint and treats an STL's repeated corners as one part; a 30°-tilted cube
  is stood back on a face; a cut cube gives two capped 10 mm halves; three stacked cubes are spread apart on the U1's
  plate; 200 cubes spill onto another plate).
- **Known divergence:**
  - Arrange uses Orca's default settings (automatic spacing, no rotation, no Y alignment); its settings dialog isn't
    offered yet. Objects that don't fit stay where they were (there's one plate until multi-plate exists).
  - Orca's `object_skirt_offset` (from a Print) isn't added to the clearance radius; it matters only for printing
    object by object.
  - Cut is a horizontal plane only (no rotated plane, connectors, dowels or flip/placement options) and the halves lose
    painted colours; upstream's cut gizmo has all of these.
  - Orient, split and cut bake their result into the object's mesh (Nozzle's placement is a Z turn and scale).
  - Not on Android (its own simpler orient/arrange) or the Web App yet.
- **Touches:** Prepare plate, shared engine.

## P-0019 — plates, plate names, object info (ported)

- **Upstream:** Snapmaker Orca `cbf7bbb0b3` (AGPL-3.0): `src/slic3r/GUI/PartPlate.cpp`/`.hpp` (`compute_colum_count`,
  `compute_shape_position`, `plate_stride_x/y` with `LOGICAL_PART_PLATE_GAP` 1/5: plates side by side in world coordinates)
  and `src/libslic3r/Format/bbs_3mf.cpp` (`<plate>` blocks in `model_settings.config`: `plater_id`, `plater_name`,
  `model_instance`/`object_id`; object metadata `name`, `extruder` and settings).
- **Imported:** the desktop project has plates: tabs to show, rename, add and remove (empty) plates; objects move between
  plates; Arrange puts what doesn't fit onto new plates (as Orca's arrange fills further beds); Slice slices the plate
  shown. Projects are saved with objects where Orca lays plates out, the manifest's plate list, and a
  `model_settings.config` with the plates, each object's filament and its own settings, so Orca/Bambu open the same
  plates (and see per-object settings, closing that P-0017 gap). Orca/Bambu multi-plate 3MFs open with their plates and
  names, using the file's own bed size for the layout. The Objects card shows each object's size, volume, triangle count
  and open edges.
- **Test evidence:** PlatesTest (Orca's column count and plate origins; another slicer's plates, names and bed read;
  a two-plate project with a renamed plate saves with Orca's layout and `model_settings.config`, reopens with each object
  on its plate at its place, and slices only the plate shown; a cube's volume 8000 mm³ and no open edges).
- **Known divergence:**
  - Orca shows every plate at once on one canvas; Nozzle shows one plate at a time, with tabs.
  - Per-plate settings (bed type, print sequence, layer-based settings) and "slice all plates" aren't there yet; a
    plate with objects can't be removed (move or remove them first) where Orca removes them with it.
  - Nozzle writes a minimal `model_settings.config` (no part/volume records, no plate thumbnails or per-plate G-code).
  - The Web App and Android have no plate list: the Web App keeps each object on its plate when it saves a desktop
    project, but shows them all on one bed.
- **Touches:** Prepare plates and objects, project format (desktop, Web App save).


## P-0020 — the engine moves into its own repository, nozzle-engine (built, not yet the default)

- **Upstream:** github.com/James-Jennison/nozzle-engine, commit `dc86dbf00d1d3239bf0a937cf0eefdb4459054b1`
  (`engine/fork/ENGINE_PIN.json`). A full-history fork of Snapmaker Orca at `cbf7bbb0b3` (the P-0010 base) with this
  repository's `engine/snapmaker/nozzle-engine.patch` and its addenda (libc++ includes, Android subproject paths, the
  browser patches 0001–0003 and the Snapmaker browser patch) applied as individual commits. AGPL-3.0.
- **Imported:** the engine itself, and the bridges: `app/src/main/cpp/bridge`, `engine/native/bridge` and
  `engine/wasm/bridge` from this repository at `a657948` now also live in the engine as `nozzle/bridge/{android,native,wasm}`
  (the engine's `tools/nozzle/bridge_drift.sh` reports any difference until this repository drops its copies).
- **Engine changes beyond P-0010** (each its own commit in nozzle-engine):
  - Plate origin initialised (`Print::m_origin`): headless slicing read uninitialised memory, so
    `EXCLUDE_OBJECT_DEFINE ... CENTER=` was garbage (about 1e-310) on beds centred at 0. Output change: `CENTER=0,0` on
    flsun_s1, flsun_t1, flsun_v400 and rolohaundesign_rolohaun_delta_flyer_refit.
  - Uninitialised members found with valgrind (`GCode::m_last_notgapfill_extrusion_role`,
    `RetractWhenCrossingPerimeters::m_layer`, `ModelVolume` cache state): the first-layer Z-hop was random on
    dremel_3d20 and re3d_gigabot_4_xlt. Output change on those two: the lift decision is now fixed (no top surface
    printed yet), so G-code is the same on every run.
  - More uninitialised state that made G-code differ between runs under parallel load (an extra short wall segment, a
    feed rate off by 2, a different retract/wipe sequence on some profiles): Arachne's `WallToolPathsParams` (a 0%
    `min_feature_size`, `min_bead_width`, `wall_transition_length` or `wall_transition_filter_deviation` was never copied,
    so the parameter was garbage) and `Extruder` state on shared-extruder printers. No expected output changed: the
    garbage usually held the intended value.
  - `NOZZLE_GCODE_RANDOM_SEED` makes profiles' `random()` custom G-code reproducible for tests (unset = clock, as before).
  - `--option-states` (desktop CLI) and `engine::print_option_states` (all three bridges): Orca's settings
    enable/visibility rules (`ConfigManipulation::toggle_print_fff_options`) and the values they force, computed by the
    engine, so settings screens can grey out and hide options as Orca does.
- **Adaptations:** new engine base `fork` next to `snapmaker` and `upstream`:
  - Desktop: `engine/native/scripts/build_engine_fork.sh`.
  - Android: `engine/fork/android/prepare_engine_root.sh` (CI: `ci_engine_root.sh`), reusing the Snapmaker root's
    dependency prefix; `app/src/main/cpp/CMakeLists.txt` uses the engine's own bridge when the engine has one;
    Gradle `-PnozzleEngine=fork` (SBOM lists nozzle-engine).
  - Web: `engine/wasm/scripts/build_engine_fork.sh`.
  - `engine/fork/export_source.sh` exports the pinned commit from a local checkout or fetches it.
  The default stays `snapmaker` until the fork base passes the device, printer and Web App acceptance runs.
- **Test evidence:**
  - Before the engine fixes, nozzle-engine built a byte-identical desktop engine to the P-0010 build (sha256
    `3aee8a34…`), and a byte-identical browser engine to a fresh build of the P-0010 sources with the current bridge
    (`nozzle-engine.wasm` `8511d6f9…`). The Android library matches in size and section layout; its bytes differ only
    where build-directory paths are embedded.
  - At the pinned commit, eight passes over all 379 bundled printer profiles, 24 slices at a time on a 64-core machine,
    produce identical G-code every time (373 slice; the six hidden Bambu H2/P2 profiles fail on every base, as
    documented in P-0010). Before the uninitialised-state fixes the same test gave 17 differing outputs. The engine's CI checks the settings
    schema and every profile's G-code against `tools/nozzle/golden/outputs.sha256` on each push.
- **Known divergence:** the output changes above; everything else is unchanged. The settings schema is identical to P-0010.
- **Touches:** slicing (all platforms), packaging (engine source and SBOM).

## P-0021 — nozzle-engine becomes the default engine on every platform

- **Upstream:** unchanged from P-0020: nozzle-engine `dc86dbf00d1d3239bf0a937cf0eefdb4459054b1` (`engine/fork/ENGINE_PIN.json`).
- **Imported:** nothing new.
- **Adaptations:** the default engine base is now `fork` (owner decision 2026-09-28):
  - Android: Gradle's `nozzleEngine` defaults to `fork`; CI prepares the engine with `engine/fork/android/ci_engine_root.sh`.
  - Desktop: `desktop/build.gradle.kts` and `SliceEngine.NATIVE_BUILD_OUTPUT` point at `engine/native/scripts/build_engine_fork.sh`'s output.
  - Web: `engine/wasm/scripts/build_engine_fork.sh` (`INSTALL=1`) builds what goes into `web/public/engine`.
  - `scripts/export_settings_schema.py` reads the schema from the fork's desktop engine.
  `-PnozzleEngine=snapmaker` (P-0010) and `-PnozzleEngine=upstream` still build until their patch paths and bridge copies
  are retired.
- **Test evidence:**
  - Razr (Android 17), side-by-side debug install: all 51 engine device tests pass on the fork base.
  - Web App smoke test: 5 of 5 printer profiles slice in the browser engine.
  - A real print: a 20 mm test cube sliced on the phone for the owner's Snapmaker U1 (PAXX firmware), printed clean and
    within tolerance (owner-confirmed 2026-09-28).
  - `engine/fork/android/ci_engine_root.sh` prepared the engine root on the CI runner (gthost-build01) from the pinned commit.
- **Known divergence:** none beyond P-0020.
- **Touches:** slicing (all platforms), CI.

## P-0022 — the older engine bases are retired

- **Upstream:** unchanged: nozzle-engine `dc86dbf00d1d3239bf0a937cf0eefdb4459054b1` (`engine/fork/ENGINE_PIN.json`).
- **Imported:** nothing.
- **Removed:** the Snapmaker Orca patch base (P-0010: `engine/snapmaker/nozzle-engine.patch`, its addenda and pin, the
  Android, desktop and browser build scripts for it) and the upstream OrcaSlicer `824b216f` base (`engine/ENGINE_PIN.json`,
  `engine/android-headless-engine.patch`, `engine/native/scripts/{build_engine,build_deps,env}.sh`,
  `engine/wasm/scripts/build_engine.sh`, `engine/wasm/patches`), and this repository's bridge copies
  (`app/src/main/cpp/bridge`, `engine/native/bridge`, `engine/wasm/bridge`): nozzle-engine carries all of them as commits
  and its own `nozzle/bridge`. Gradle has no `-PnozzleEngine` choice any more (`-PnozzleEngineRoot` moves the root).
- **Adaptations:**
  - `engine/fork/android/prepare_engine_root.sh` builds its own dependency prefix (a symlink tree over
    orcaslicer-android-engine's, with GMP rebuilt with C++ classes) instead of borrowing the Snapmaker root's;
    `ci_engine_root.sh` no longer fetches Snapmaker Orca.
  - The Android dependency archive hashes moved from `engine/ENGINE_PIN.json` to `engine/fork/android/DEPENDENCIES.json`
    (unchanged, 24 archives); `scripts/engine_pin.py` verifies only those, and the SBOM reads them from there.
  - Printer and filament data moved from `engine/snapmaker/` to `engine/profiles/` (`library/`, `resources/`,
    `unsupported-profiles.json`); where it comes from is in `engine/profiles/SOURCES.json` (OrcaSlicer `824b216f` and
    Snapmaker Orca `cbf7bbb`, as data sources only).
  - The source offer now names the engine actually shipped: the app's About dialog (`OpenSourceNotice`), the Desktop About
    text and the website's open-source page link nozzle-engine at the pinned commit and its change history, instead of
    OrcaSlicer `824b216f` and the old patch. `schemas/slicing/settings-schema.json` records nozzle-engine as its source
    (the 567 options are unchanged).
- **Test evidence:** full gate after a clean build (734 JVM tests, lint, SBOM), Web App typecheck and 33 unit tests, the
  Android engine root rebuilt by the new script (GMP identical to the tested one apart from embedded build paths), and all
  51 engine device tests on the Razr (Android 17).
- **Known divergence:** none; the engine is unchanged.
- **Touches:** build, CI, licensing notices, packaging.

## P-0023 — nozzle-engine with Bambu's multi-extruder / multi-nozzle support

- **Upstream:** nozzle-engine `e23c0df67b36854f52b6e57fd33c62bdd2831634` (`engine/fork/ENGINE_PIN.json`), from
  `dc86dbf` (P-0020 to P-0022) plus the engine's `e3/multi-nozzle` work (github.com/James-Jennison/nozzle-engine pull 1).
  Each change is its own commit there, ported from upstream OrcaSlicer (dev at `5298e49d`) with the differences listed.
- **Imported:** nothing into this repository; the engine carries the code.
- **Engine changes** (from upstream OrcaSlicer, AGPL-3.0):
  - G-code templates: `ceil()`, `floor()`, vector leniency. Divergence: an unindexed per-extruder vector reads the
    filament's extruder from `filament_map` converted to 0-based (upstream indexes with the 1-based value and reads the
    other nozzle; reported upstream as a draft).
  - Extruder variants: Bambu profiles' per-variant printer, process and filament settings are collapsed to each extruder
    / filament for the variant it has installed (only printers declaring more than one variant). Multi-filament prints
    give every filament its own copy of the filament profile's variant columns.
  - The start, tool-change, layer and timelapse G-code variables and options Bambu's H2S, P2S, H2D, H2D Pro, X2D and
    H2C profiles read (see the engine commits for the list).
  - Not ported: upstream's flush-minimising filament/nozzle grouping (the H2C's rack nozzles are assigned in filament
    order), perimeter-avoiding travel to the prime tower, tower interface layers, ramming cool-down, the timelapse
    position picker and farthest-point timelapse.
- **Adaptations:** the pin, the About dialog's engine commit and the website's open-source page name `e23c0df`;
  `schemas/slicing/settings-schema.json` regenerated (source commit only: the new options are profile keys the schema
  does not list); `engine/profiles/unsupported-profiles.json` and
  its message now say why the six Bambu profiles stay hidden: they slice, but none has been printed on a real machine,
  and the two-extruder models need a filament-to-extruder choice (`filament_map`) the app doesn't make yet.
- **Test evidence:** engine CI on gthost for the branch (run 36473192446: desktop, golden on all 380 profiles, contract,
  Android, WebAssembly); printed G-code unchanged on every profile that sliced before, single- and two-filament; this
  repository's gate (734 JVM tests, lint, site and schema checks), the Web App's typecheck, 33 unit tests and the browser
  engine smoke test on five printers (same layers, grams and times as before), and all 51 engine device tests on the Razr.
- **Known divergence:** printed G-code is unchanged for every printer already offered; the five older Bambu printers'
  config block lists one value per setting.
- **Touches:** slicing (all platforms), settings schema, licensing notice.

## P-0024 — default filament-to-extruder rule on Bambu multi-extruder printers

- **Upstream:** nozzle-engine `25ca1a2b6e66e1e3ebb6d77c841b7ab6a0dcb7af` (`engine/fork/ENGINE_PIN.json`,
  James-Jennison/nozzle-engine pull 2): `e23c0df` (P-0023) plus one commit.
- **Imported:** nothing into this repository.
- **Engine change:** a request that doesn't map every filament to an extruder (this app sends no `filament_map`) gets
  upstream OrcaSlicer's rule for printers without its grouping engine: filament *i* on extruder *i* while there are
  extruders, the rest on the master extruder (new option `master_extruder_id`). Only Bambu printers that declare extruder
  variants are affected. Upstream's own default for Bambu printers is its flush-minimising grouping, which is not ported.
- **Adaptations:** pin, About dialog, website and `engine/profiles/unsupported-profiles.json` updated. The app's tool slots
  are one per extruder, so slot *n* prints on extruder *n*, which is this rule; a per-filament choice needs more
  filaments than extruders (a Bambu AMS), which the app doesn't model yet.
- **Test evidence:** listed in the commit.
- **Known divergence:** none for printers already offered (G-code identical to P-0023 apart from the new setting line).
- **Touches:** slicing (Bambu multi-extruder profiles only).

## P-0025 — Bambu multi-colour (AMS) slicing

- **Upstream:** nozzle-engine `c8e5a4d7d402016df78addc52f3992834abd3b74` (`engine/fork/ENGINE_PIN.json`): `25ca1a2` (P-0024) plus one commit.
- **Imported:** nothing into this repository.
- **Engine change:** the Bambu `.gcode.3mf` bundle path takes a filament (AMS slot) per object
  (`slice_multi_object_bambu_bundle`, JNI `nativeSliceMultiObjectBambuBundleTools`; the older entry points still work),
  and `slice_info` now lists each used filament's type and colour, as upstream's Plater writes them. The filament's
  catalogue id stays empty: upstream converts it with Bambu's network plugin, which this app does not use.
- **Adaptations:** Bambu printers that take an AMS (X1, X1 Carbon, X1E, P1P, P1S, A1, A1 mini, and the hidden P2S and
  H2S) get four filament slots (`BambuAms`, the way the CANVAS packs declare theirs), which turns on the project's
  per-object slot assignment and material painting for them; `SlicingCoordinator.sliceProject` gives the Bambu bundle
  the same per-slot filament config and flushing volumes as other multi-material printers.
- **Not in this change:** starting an AMS print over LAN. The print command still sends `use_ams:false` with an empty
  AMS mapping, so a multi-colour file prints from the external spool until the mapping is added and tested on a real
  Bambu printer.
- **Test evidence:** listed in the commit.
- **Touches:** slicing (Bambu), project editor slots.

## P-0026 — Bambu AMS trays and print mapping (ported from Helix, gated)

- **Upstream:** Helix (github.com/FatBoy721/Helix, AGPL-3.0-or-later, main as of 2026-09-05):
  `android/app/src/main/java/org/crabcore/u1control/bambu/BambuPrintProtocol.kt` (the `toolToLane` / `use_ams` mapping
  our port had dropped) and `services/bambuReport.ts` (AMS tray numbering and occupancy), with its
  `scripts/fixtures/bambu-p1s-report.json`.
- **Imported:** `BambuAmsTrays.kt` (new, from `bambuReport.ts`); the mapping restored in `BambuPrintProtocol.kt`; the
  fixture as `domain/src/test/resources/bambu/helix-p1s-report.json`.
- **Adaptations:** trays become `FilamentSlot`s, so the Bambu printer screen's new "AMS slots" button shows them
  (read-only, one status probe); `BambuAms.matchTrays` matches a bundle's filaments (slice_info) to loaded trays by
  material, then colour; `BambuPrintRequest.toolToLane` carries the result.
- **Gate:** `BambuAms.AMS_PRINT_VERIFIED = false`. Until a tester with a Bambu printer and an AMS has started and
  completed a multi-filament print with this mapping, `BambuPrinterService.startPrint` still refuses multi-filament
  files and sends `use_ams:false` for everything else, exactly as before. The mapping itself is unit-tested against
  Helix's wire format only; it has never reached a printer.
- **Test evidence:** listed in the commit.
- **Touches:** Bambu printer screen (AMS slots), Bambu print command (unchanged on the wire while gated).

## P-0027 — per-object filament assignment keeps the prime tower

- **Upstream:** nozzle-engine `d17bfe544723a4c39fde05b260609a6811233975` (`engine/fork/ENGINE_PIN.json`, James-Jennison/nozzle-engine pull 4):
  `c8e5a4d` (P-0025) plus one commit.
- **Imported:** nothing into this repository.
- **Engine change:** a per-object filament assignment now also sets the object's `extruder`, as upstream's object list
  does. Before, `Print::apply` counted every per-object assignment as one filament and switched the prime tower off, so
  multi-colour prints assigned per object (CANVAS, Snapmaker U1, Bambu AMS) had no prime tower. Found by the Test Grid
  on the owner's Centauri Carbon + CANVAS: a two-colour print with no tower, and the colours bled.
- **Test evidence:** desktop CLI, two cubes on filaments 1 and 2: Centauri Carbon COSMOS CANVAS, U1 and X1 Carbon now
  print a tower (before: `enable_prime_tower = 0`, none); two cubes on one filament still print none. Engine CI on
  gthost (run 36577444466) and this repository's checks are listed in the commit.
- **Known divergence:** multi-colour prints assigned per object gain the prime tower their profile asks for (more
  filament, longer prints); single-filament prints are unchanged.
- **Touches:** slicing (multi-material, per-object assignment).

## P-0028 — thumbnails in the filament colours

- **Upstream:** nozzle-engine `d746c1b1c145b6e1027cb31af6b1155cc788a77a` (`engine/fork/ENGINE_PIN.json`, James-Jennison/nozzle-engine pull 5):
  `d17bfe5` (P-0027) plus three commits.
- **Imported:** nothing into this repository.
- **Engine change:** the headless thumbnail (embedded in the G-code and the Bambu bundle's plate image) drew every model
  as one merged mesh in a fixed orange. It now draws each printable part in its filament's `filament_colour` (the part's
  own filament, else its object's, else filament 1), and colour-painted areas in their painted filament's colour.
  Modifiers and negative volumes are no longer drawn. Very dark colours are lifted to a dark grey so a black part stays
  visible on a dark printer screen. Found by the owner on a Test Grid two-colour COSMOS print: its thumbnail was orange.
- **Test evidence:** desktop CLI, two cubes on filaments 1 and 2 of a Centauri Carbon COSMOS: red and blue in the
  thumbnail (before: both orange). Against d17bfe5 with the thumbnail blocks removed (including Qidi's `;gimage` /
  `;simage`), all 380 bundled printers' G-code is identical; 356 golden hashes change because they embed a thumbnail.
  BambuMultiColourDeviceTest now checks the plate thumbnail shows both filaments' colours.
- **Known divergence:** thumbnails only; the printed G-code is unchanged.
- **Touches:** printer-screen and file-list thumbnails (G-code and .gcode.3mf).

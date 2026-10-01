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

## P-0029 — the wipe tower is defined as a Klipper object

- **Upstream:** nozzle-engine `2b7b6cffaae4364ff38e7c3e5d8a79a50dacab5e` (`engine/fork/ENGINE_PIN.json`, James-Jennison/nozzle-engine pull 6):
  `d746c1b` (P-0028) plus two commits.
- **Imported:** nothing into this repository.
- **Engine change:** on Klipper, a print with a wipe tower also emits `EXCLUDE_OBJECT_DEFINE NAME=wipe_tower`, whose
  polygon is the hull of the tower's own extrusions on every layer. Klipper's adaptive bed mesh (`BED_MESH_CALIBRATE
  ADAPTIVE=1`, used by COSMOS and KAMP-style start macros) probes only under defined objects, so before this the tower
  was printed with the mesh clamped from the objects' area. It is defined only, never started: the tool-change macros
  run inside the tower's moves and must never be skipped, so excluding it in Mainsail/Fluidd does nothing.
- **Found by:** the owner's Test Grid two-colour print on the Centauri Carbon (COSMOS, CANVAS, adaptive mesh on). The
  G-code had a tower on every layer, and the printer's logs show it extruded the tower at its position, but the
  adaptive mesh covered only X/Y 105–151: the tower's area was never measured, and its height there was taken from
  the objects' edge. Nothing of the tower stayed on the bed. (An earlier note put the corner ~0.25 mm low; that came
  from a saved mesh made before the owner levelled the bed, so the real error there is unknown.)
- **Confirmed on hardware:** the next two-colour print on the same Centauri Carbon, sliced with this engine, printed the
  tower.
- **Test evidence:** desktop CLI, two cubes on two filaments: the defined area encloses every tower extrusion with
  ~0.25 mm to spare on the Centauri Carbon (rib wall: defined X 9.5–37.5 Y 214.5–242.0, printed X 9.8–37.2
  Y 214.7–241.8) and the U1 (rectangle). Golden outputs unchanged (single cube, no tower).
  SlicingProfilePacksDeviceTest checks a two-tool COSMOS slice defines the tower and covers its extrusions.
- **Known divergence:** Klipper G-code with a wipe tower gains one object line; Mainsail/Fluidd list a `wipe_tower`
  object. No upstream slicer checked defines the tower. `Print::first_layer_wipe_tower_corners()` still assumes a
  `prime_tower_width` rectangle (wrong for rib walls; upstream Orca tracks the real bbox); fixed in P-0030.
- **Touches:** Klipper G-code header (object definitions), printers' adaptive bed meshing.

## P-0030 — the wipe tower's real outline (upstream OrcaSlicer)

- **Upstream:** nozzle-engine `9a9f2319d89a3d0f7396c5b228c1d6bc6f03559f` (`engine/fork/ENGINE_PIN.json`, James-Jennison/nozzle-engine pull 7):
  `2b7b6cf` (P-0029) plus two commits. Ported from upstream OrcaSlicer's `WipeTower2::get_bbx()` / `get_rib_offset()`,
  `WipeTowerData::bbx` / `rib_offset` and their users.
- **Imported:** nothing into this repository.
- **Engine change:** `WipeTower2` records its real first-layer bounding box (outermost brim loop, or the wall) and, for a
  rib wall, the rib offset that puts the protruding first-layer corner at `wipe_tower_x/y` (Snapmaker Orca had it
  commented out; upstream re-enabled it). `first_layer_wipe_tower_corners()` (first-layer hull, adaptive-mesh
  placeholders, skirt), the tower/object collision check, the extrusion-extents helper, `wipe_tower_center_pos`, the
  preview/conflict tower and the Klipper `wipe_tower` object all use that placement.
- **Test evidence:** desktop CLI, two cubes on two filaments. Centauri Carbon (rib wall, `wipe_tower_x/y` 15/220, brim
  3): the tower now prints from X 11.8 / Y 216.8 (the brim; the wall starts at 15/220), before from X 9.8 / Y 214.7 (the
  wall 5 mm past its position); the Klipper `wipe_tower` object still encloses every tower extrusion. U1 (rectangle):
  G-code byte-identical. Golden outputs unchanged. SlicingProfilePacksDeviceTest checks the tower reaches past its
  configured corner by no more than its brim.
- **Known divergence:** rib-wall multi-colour prints place the tower ~2–5 mm differently (as upstream). Upstream's
  compacted-tower checks, which also read the box, are not in this engine.
- **Touches:** multi-material wipe tower placement (rib wall), first-layer hull and placeholders, collision checks.

## P-0031 — Prusa MMU3 slicing packs (CORE One, MK4S, MK3.9, MK3.5)

- **Upstream:** OrcaSlicer `5298e49d` (Prusa vendor 02.04.00.08: `Prusa CORE One MMU3 0.4 nozzle` and its process and
  filament) and PrusaSlicer `30ef59195e0f` (3.0.0-alpha12, `resources/presets/prusa-research-fff/PrusaResearch/`:
  `preset-printer-mk4.yaml` MK4S/MK3.9 MMU3 variants, `preset-printer-mk35.yaml` MK3.5 MMU3, `vendor.yaml` MMU3 feeders).
- **Imported:** four packs under `app/src/main/assets/slicer_profiles/prusa_*_mmu3/`; the generator and its PrusaSlicer
  preset evaluator under `scripts/prusa_mmu3/` (see its REPORT.md for every changed key and source).
- **Change:** multi-colour slicing for Prusa printers with an MMU3. Five filament slots through one nozzle
  (`PrusaMmu.filamentSlots`), filament-swap family. No engine change.
- **Test evidence:** desktop engine (nozzle-engine 9a9f231) on gthost, two cubes on slots 1 and 2 with five filaments:
  every pack slices with 102 `Tn` changes, a prime tower on every layer, no `M600`, and its own `M862.3` printer check;
  MK4S shows PrusaSlicer's MMU ramming (`M104` -20 C, `M591 S0`, `G4 S1.5`), MK3.5 its own (`M900 K0`). The
  SlicingProfilePacksDeviceTest checks the same through the app's multi-tool path.
- **Known divergence:** not verified on hardware (testers will print them). The start G-code's abrasive/high-flow check
  always sends `A0 F0`; purging uses the app's flush matrix instead of PrusaSlicer's `multimaterial_purging`.
- **Touches:** slicing profiles (Prusa), printer catalogue, website printer list.


## P-0032 — multi-colour slicing for Creality CFS, Anycubic ACE and Flashforge IFS printers

- **Upstream:** the packs are upstream OrcaSlicer's own (flattened, `824b216f`); the two pack changes follow CrealityPrint
  (local checkout, `resources/profiles/Creality/`).
- **Imported:** nothing new; `scripts/bundle_vendor_profiles.py` gains `fix_changers`, applied to six Creality packs.
- **Change:** `FilamentChangers` gives four filament slots (one unit) to the Creality CFS printers (K2, K2 Plus, K2 Pro,
  K2 SE, Hi, K1 / K1C / K1 SE / K1 Max CFS-C), the Anycubic ACE printers (Kobra 3, 3 Max, S1, S1 Max, X) and the
  Flashforge AD5X (IFS). Their firmware swaps on the plain `T<n>` the engine emits after the packs' own templates.
  Pack changes: K2, K2 Pro and K2 SE purge into the CFS chute (`purge_in_prime_tower` 0, as CrealityPrint and as the K2
  Plus and Hi already did); the K1, K1C and K1 SE CFS-C templates drop their `G1 X0 Y245` park, past the 220 mm bed
  (CrealityPrint parks at the tower's outer wall, where the engine's tower already leaves the nozzle; the K1 Max keeps it).
- **Test evidence:** desktop engine (nozzle-engine 9a9f231) on gthost, two cubes on slots 1 and 2 with four filaments:
  K2, K1 CFS-C, Kobra 3, Kobra S1 and AD5X slice with `T` changes, no `M600` and a prime tower; the K1 CFS-C's
  furthest Y is 223.4 (was 245 at every change).
- **Known divergence:** not verified on hardware. Which physical slot each filament uses is set on the printer; sending
  the mapping at print start (CFS websocket `colorMatch`, Flashforge `materialMappings`, Anycubic) is separate work. The
  Qidi Box is not included: its packs rely on OrcaSlicer's type-1 wipe tower, which this engine does not select yet.
- **Touches:** slicing (multi-material), six Creality slicing packs.

## P-0033 — Creality (CFS) and Flashforge (IFS) printer connections (ported, not copied)

- **Upstream:** OrcaSlicer `5298e49d` (local checkout): `src/slic3r/Utils/CrealityPrint.cpp`, `CrealityPrintAgent.cpp`,
  `CrealityHostDiscovery.cpp`, `Flashforge.cpp` / `.hpp`, and `src/slic3r/GUI/PrintHostDialogs.cpp`
  (FlashforgePrintHostSendDialog). AGPL-3.0. CrealityPrint `59ae8cb` (local checkout, AGPL-3.0):
  `src/slic3r/GUI/print_manage/Device/LanDeviceProbe.cpp`, `Klipper4408Interface.cpp`, `print_manage/data/DataType.cpp` and
  the minified `resources/web/deviceMgr/assets/BZCDzYbb.js` (cited as "line N @byte offset").
- **Imported:** nothing copied. The wire rules, re-implemented in Kotlin: `CrealityCfs` (`/info`, the port-9999 websocket
  with its `ok` heartbeat reply, `boxsInfo` parsing, `/upload/<name>`, `opGcodeFile` / `colorMatch` / `multiColorPrint`,
  the K1 vs K2 G-code directories) and `FlashforgeIfs` (port 8898 `/detail` and `/uploadGcode`, the `code`/`err` reply
  rule, `matlStationInfo` slots, the `materialMappings` base64 JSON, Orca's material-family check, the UDP 48899
  discovery reply). The `colorMatch` id uses CrealityPrint's `T{floor(T/4)+1}{A+T%4}`: Orca's `"T1"+('A'+i)` is wrong from
  T4 up. `SlicedFileFilaments` reads each file tool's type and colour from the sliced file's settings block.
- **Change:** `PrinterKind.CREALITY` (`CrealityPrinterService`) and `PrinterKind.FLASHFORGE` (`FlashforgePrinterService`,
  serial number + access code): live status, the CFS / IFS slots (read-only), uploading a sliced file, discovery (Creality
  `/info` in the TCP sweep, Flashforge's UDP probe). **Starting a print is gated off** (`CrealityCfs.START_VERIFIED`,
  `FlashforgeIfs.START_VERIFIED`, both false, as `BambuAms.AMS_PRINT_VERIFIED`): sending uploads the file (Flashforge with
  `printNow: false`) and refuses the start with "isn't verified on real hardware yet"; the start messages are built and
  unit-tested only. Never sent: CFS load/unload (`feedInOrOut`), `refreshBox`, `boxConfig`, homing, jogging, temperatures;
  pause / resume / cancel are not built.
- **Test evidence:** unit tests only (CrealityCfsTest, FlashforgeIfsTest, SlicedFileFilamentsTest in :domain;
  CrealityFlashforgeServiceTest in :app against a local MockWebServer). All fixtures are constructed from the upstream
  schemas, not captured: nothing here has met a real printer.
- **Known divergence / to verify on hardware:** Flashforge's `/detail` status field names are not on disk anywhere (a best
  guess); the Hi and K2 SE `/info` model codes are unknown; whether a K2 pushes its full state on connect; HTTPS/wss-only
  Creality printers are not supported; the start path (idle gate, upload listing wait, pushed-state confirmation) has never
  run. The file-tool -> slot map is one-to-one (T n -> slot n) until the UI can pass a chosen map.
- **Touches:** printer transports (Android), shared UI (printer type chips, add-printer wizard), Test Grid classification.

## P-0034 — wipe_tower_type: Qidi Box printers get the BBS wipe tower, and slice multi-colour

- **Upstream:** nozzle-engine `cedbf252e94d357d0d41795da59aebe2e4eb8e14` (`engine/fork/ENGINE_PIN.json`, James-Jennison/nozzle-engine
  pull 8): `9a9f231` (P-0030) plus upstream OrcaSlicer's `wipe_tower_type` option (4 commits) and the golden outputs.
- **Imported:** nothing into this repository beyond the pin.
- **Engine change:** the `wipe_tower_type` printer option and `Print::wipe_tower_type()` (Bambu always type 1, others the
  option, default type 2). Every wipe-tower decision uses it instead of `is_BBL_printer()`, so the 12 Qidi packs, which set
  `type1`, get the BBS tower instead of the Prusa-style one. The engine used to ignore the key, which gave a double
  purge and Prusa ramming and tube moves before the Box's own `CUT_FILAMENT`. Also: no null dereference of the priming
  list on a type-1 non-Bambu printer; `WipeTowerData::clear()` resets `height`; the BBS path retracts, lifts and travels
  to the tower when `change_filament_gcode` is empty; the BBS tower writes the printer's own G-code tags.
- **App change:** `FilamentChangers` gives the Qidi Box printers (Q2, Q2C, X-Plus 4, X-Max 4, X-Plus 5) four slots.
- **Test evidence:** on gthost, all 383 packs slice single-colour byte-identically to 9a9f231 apart from the new
  `; wipe_tower_type = ...` config line. Two-colour Qidi Q2 and X-Plus 4 have no Prusa ramming (310 → 7 ramming
  references, as on Bambu) and one `CUT_FILAMENT` per change. A Bambu X1C two-colour slice is byte-identical. Engine CI
  on gthost (run 36632934268).
- **Known divergence:** not verified on a Qidi printer. The type-1 tower is this engine's BBS generator, not upstream's
  newer BBS 2.x one.
- **Touches:** slicing (multi-material, wipe tower), settings schema (new option).

## P-0035 — Duet, UltiMaker, older Flashforge and Repetier-Server connections (ported, not copied)

- **Upstream:** OrcaSlicer `5298e49d` (local checkout, AGPL-3.0): `src/slic3r/Utils/Duet.cpp` / `.hpp`, `UltiMaker.cpp` /
  `.hpp`, `Flashforge.cpp` / `.hpp` with `TCPConsole.cpp` / `.hpp`, and `Repetier.cpp` / `.hpp`; compared with SuperSlicer's
  `Duet.cpp` and `Repetier.cpp` (PrusaSlicer lineage). UltiMaker status only: Cura `72521b7` (local checkout, LGPL-3.0),
  `plugins/UM3NetworkPrinting` (`Network/ClusterApiClient.py`, `Models/Http/ClusterPrinterStatus.py`,
  `ClusterPrintJobStatus.py`, `UltimakerNetworkedPrinterOutputDevice.py`, `resources/qml/MonitorPrintJobProgressBar.qml`,
  `MonitorPrinterCard.qml`) and `cura/PrinterOutput/Models/PrintJobOutputModel.py`: UltiMaker's own slicer, used because
  Orca's UltiMaker host reads no state.
- **Imported:** nothing copied. The wire rules, re-implemented in Kotlin with file:line citations:
  - `DuetRrf`: which API (`rr_connect?password=<pw or reprap>&time=...`, then the DSF `machine/status` probe), curl's
    escaping, the `err` rule, RRF `rr_upload` (raw POST body) / DSF `PUT machine/file/gcodes/<name>` (201 only),
    `rr_disconnect`, and the M32 / M37 start (`rr_gcode`, `machine/code`).
  - `UltiMakerApi`: the variant check, pairing (`auth/request` -> approve on the printer -> `auth/check/<id>` ->
    `auth/verify` with HTTP Digest), the Griffin header clean-up and `;PRINT.TIME`, the `print_job` upload; status from
    Cura's cluster-API reads (`/cluster-api/v1/printers`, `/print_jobs`, job matched by `printer_uuid` / `assigned_to`,
    progress `time_elapsed / time_total`, Cura's state names).
  - `FlashforgeLegacy`: upstream's split (both serial and access code -> port-8898 API, else the port-8899 console), the
    `~M` command texts byte for byte, TCPConsole's framing (`\n` after each command, read until a line is `ok`, raw data)
    and timeouts, connect (`~M601 S1`, `~M115`, `~M650` / `~M640`, `~M119`), upload (`~M28 <bytes> 0:/user/<name>`,
    4096-byte pieces, `~M29` on a new connection 3 s later), `~M23`, and the byte-wise file-name rule.
  - `RepetierServer`: `X-Api-Key`, `printer/info` identification (validate_repetier), `printer/list` slugs and `error`,
    `printer/model/<slug>` upload (`a=upload` + `filename`) and `printer/job/<slug>` (`name`, `autostart=true`).
- **Change:** `PrinterKind.DUET` (`DuetPrinterService`, board password in the encrypted apiKey slot),
  `PrinterKind.ULTIMAKER` (`UltiMakerPrinterService`; pairing id in `serial`, key in apiKey; `UltiMakerPairing` composable
  in Edit printer and the wizard), `PrinterKind.REPETIER` (`RepetierPrinterService`; API key in apiKey, printer slug in
  `serial`), and `PrinterKind.FLASHFORGE` without both credentials -> `FlashforgeLegacyPrinterService`. New transports,
  chips, Control-tab cards, Test Mode labels, Test Grid families `duet-rrf`, `ultimaker-lan`, `flashforge-legacy`,
  `repetier-server`. `PrinterCapabilities.readsPrinterState` (false for Duet and Repetier) and `sendAllowedStates`: an
  upload-only send may go out in the "unknown" state only while that kind's start is gated.
- **Gated (all four `START_VERIFIED` false; `startVerifiedFor` false for DUET, ULTIMAKER, REPETIER and FLASHFORGE, so
  the Test Grid declares no `upload_and_start`):** Duet M32 / M37 (upload never starts on a Duet); UltiMaker `print_job`
  (an UltiMaker prints every job it is sent, so **nothing** is sent: refused before any request); Flashforge legacy
  `~M23` (upload is `~M28` / data / `~M29` only); Repetier `printer/job` with `autostart=true` (upload goes to the model
  library, which never prints). Refusals contain "isn't verified on real hardware yet". Never sent at all: pause, resume,
  cancel / abort / stop, homing, jogging, temperatures, arbitrary G-code (not built for these kinds).
- **Test evidence:** unit tests only (DuetRrfTest, UltiMakerApiTest, FlashforgeLegacyTest, RepetierServerTest in :domain;
  DuetPrinterServiceTest, UltiMakerPrinterServiceTest, RepetierPrinterServiceTest against a local MockWebServer and
  FlashforgeLegacyServiceTest against a fake TCP printer on 127.0.0.1, in :app). All fixtures are constructed from the
  upstream code, not captured: nothing here has met a real printer.
- **Known divergence / to verify on hardware:** upstream reads no state for Duet, Repetier-Server or the Flashforge
  console (`~M105` / `~M27` are declared, never used), so these show "unknown"; the Flashforge console isn't "ready", so
  the app's send button stays off for it. Orca's UltiMaker variant check accepts only UltiMaker 3 and S5 (not enforced
  here); its UltiMaker `connect` / `start_print` / `rr_disconnect` are Duet copies an UltiMaker doesn't serve (not ported);
  a file without `;START_OF_HEADER` is refused instead of being uploaded empty; its `auth/request` third field (named
  "OrcaSlicer") isn't sent. The Flashforge console stops before `~M28` when its connect fails (upstream carries on), and
  always uses `~M650` (upstream picks `~M640` for a Klipper-flavoured profile). Duet `rr_upload` goes out as
  `application/x-www-form-urlencoded` like curl's POSTFIELDS; RRF's `rr_gcode` quotes are percent-encoded by OkHttp. A
  Repetier server with several printers needs its slug entered. No discovery for any of the four.
- **Touches:** printer transports (Android), shared UI (printer type chips, add-printer wizard, UltiMaker pairing),
  Test Grid classification.

## P-0036 — Anycubic LAN mode connection (Kobra 3 / S1 / X, ACE / ACE Pro; ported, not copied)

- **Upstream:** PRIMARY, ported from: anycubic-orca-plugin `458eee7` (local checkout, AGPL-3.0), an OrcaSlicer
  printer-connection plugin: `anycubic_orca_plugin/anycubic_lan.py`, `tests/test_plugin.py`, `docs/orcaslicer-plugin.md`,
  `AGENTS.md`. CROSS-CHECK: kobra-connect `3edba24` (local checkout, Apache-2.0): `kobra_connect/handshake.py`,
  `client.py`, `models.py`, `moonraker_bridge/state.py` / `bridge.py`, `docs/mqtt-commands.md`. Both licences are
  compatible with this repository's; nothing was copied from either, and neither was run.
- **Imported:** nothing copied. The wire rules, re-implemented in Kotlin with file:line citations to both references
  (`AnycubicLan`): the handshake (`GET :18910/info`; the signed `POST <ctrlInfoUrl>?ts&nonce&sign&did` with
  `sign = md5(md5(token[:16]) + ts + nonce)`; the reply's `data.info` decrypted with AES-128-CBC, key `token[16:32]`, IV
  `data.token`), MQTT 3.1.1 over TLS on 9883 with the decrypted `username` / `password`, the command topics
  `anycubic/anycubicCloud/v1/{slicer|web}/printer/<modelId>/<deviceId>/<type>`, the report subscription
  `anycubic/anycubicCloud/v1/printer/+/<modelId>/<deviceId>/#`, the message envelope (`type`, `action`, `timestamp`,
  `msgid`, `data`), `info` / `query` status parsing, `multiColorBox` / `getInfo` ACE slot parsing (slots, material,
  colour, loaded, active), the multipart `gcode_upload` (fields `filename` + `gcode`, the plugin's headers, one
  re-handshake on 401), the `print` / `start` message with `ams_settings.ams_box_mapping`, and (built, gated, never sent)
  pause / resume / stop, temperatures, homing, ACE feed and drying. The TLS link reuses the HiveMQ client and the
  certificate pin store Bambu's connection uses (`AnycubicMqttSession`); no new dependency.
- **Where the references disagree (the choice is recorded at each rule in `AnycubicLan` / `AnycubicMqttSession`):**
  `/info` without `modelId` / `ctrlInfoUrl` (primary: defaults; kobra-connect: refuse — refused); cloud mode
  (kobra-connect only — checked); `did` (primary: one fixed id; kobra-connect: random 32 — random per service); the IV
  (primary: as sent; kobra-connect: NUL-padded to 16 — padded); the broker (kobra-connect follows the reply's
  `broker` host and port — the printer's own host, the reply's port, else 9883); client certificate (kobra-connect presents
  `devicecrt` / `devicepk`; primary none — offered when returned); the report subscription (`printer/public/...` vs
  `printer/+/...` — `+`); the query namespace (kobra-connect's code: web; its doc and the primary: slicer — slicer);
  `msgid` (dashed UUID, as the primary and kobra-connect's doc); `project` vs `last_project` precedence (`project`
  first); state words (the union: the primary's `busy` / `pause` / `stoped`, kobra-connect's `pause` flag, `paused`,
  `error`); temperatures (primary `tempature/set`; kobra-connect `print/update` settings — kobra-connect's, on the web
  topic); homing (primary `axis/move`; kobra-connect's doc says MQTT has none — built, gated, never sent); the start
  message (kobra-connect's minimal form vs the primary's full ACE form — the primary's). kobra-connect has no ACE and no
  upload, so those are the primary's alone.
- **Change:** `PrinterKind.ANYCUBIC_LAN` (`AnycubicLanPrinterService`), address only: the MQTT credentials come from
  the printer's own handshake and are held in memory for one service, never stored, shown or logged. Live status and
  temperatures, the ACE / ACE Pro slots (read-only), uploading a sliced file (which never starts a print). Chips in Edit
  printer and the add-printer wizard, the Control-tab card with "ACE slots", send targets, Test Mode labels, Test Grid
  family `anycubic-lan` (status and material state only). Safety additions not in either reference: URLs the printer
  hands out (`ctrlInfoUrl`, `fileUploadurl`) are only followed on the printer's own host; model and device ids that would
  change an MQTT topic are refused; the first TLS certificate seen at a printer address is pinned (trust on first use,
  as Bambu's), forgotten when the printer is removed or re-added.
- **Gated (`AnycubicLan.START_VERIFIED` false; `startVerifiedFor(ANYCUBIC_LAN)` false, so the Test Grid declares no
  `upload_and_start`):** `print` / `start` (a send uploads, then refuses with "Uploaded <name> to the printer but did not
  start it: starting a print on an Anycubic printer from Nozzle It All isn't verified on real hardware yet. Start it from
  the printer's screen."); pause, resume, cancel, temperatures, homing / moving, ACE feed / unload / drying ("Nothing was
  sent: <what> on an Anycubic printer from Nozzle It All isn't verified on real hardware yet. Use the printer's screen.",
  before any request). The start path behind the gate (idle check, ACE mapping checked before the upload, a status read
  to confirm) has never run.
- **Test evidence:** unit tests only (`AnycubicLanTest` in :domain; `AnycubicLanPrinterServiceTest` against a local
  MockWebServer and a fake MQTT session, and `AnycubicTrustPinTest`, in :app; `GatedKindsTest` in :test-grid). Fixtures
  are the primary's own test values (handshake token / IV / credentials, status and ACE reports, the PETG mapping, the
  upload reply) and kobra-connect's documented messages; the ciphertexts were made from the primary's test values with
  `openssl enc`. Nothing here has met a real printer.
- **Known divergence / to verify on hardware:** whether the broker needs the client certificate (Kobra 3) or not (S1);
  whether its TLS certificate stays the same across reboots (the pin assumes so); which topic segment reports arrive on;
  whether a printer without an ACE answers `multiColorBox` (silence is read as "no ACE"); the upload's X-BBL headers and
  whether the daemon needs them; `ams_index` for a second ACE (only the first is mapped); the `md5` of the file name the
  primary sends in `print` / `start`; the state words during heating / levelling. No discovery (the primary's SSDP /
  `/info` probe is not ported), no camera (`:18088/flv`), no file list, no light, fan, speed or skip-object controls.
- **Touches:** printer transports (Android), shared UI (printer type chips, add-printer wizard, Control tab), Test Grid
  classification.

## P-0037 — Snapmaker 2.0 A-series, J1 and Artisan connections (ported from Luban and the SACP SDK, not copied)

- **Upstream:** PRIMARY, ported from: Snapmaker Luban `db573f5` (local partial clone, AGPL-3.0),
  `src/server/services/machine/`: `channels/SstpHttpChannel.ts` (Snapmaker 2.0 HTTP API), `channels/SacpTcpChannel.ts`,
  `channels/SacpChannel.ts`, `sacp/SacpClient.ts` (SACP over TCP), `ProtocolDetector.ts`, `types.ts`. WIRE FORMAT:
  `@snapmaker/snapmaker-sacp-sdk` 0.1.1 (local copy of the published package, ISC), `package/dist/`: `helper.js`,
  `communication/Header.js`, `Packet.js`, `Communication.js`, `Dispatcher.js`, `Response.js`, `models/*.js`. Both
  licences are compatible with this repository's; nothing was copied from either, and neither was run or installed.
  The Snapmaker-SACP GitHub repository carries no licence and was deliberately NOT used, read or cited.
- **Imported:** nothing copied. Re-implemented in Kotlin with file:line citations at each rule:
  `SnapmakerSstp` (the HTTP API: `POST /api/v1/connect` with a form `token`, approval on the touchscreen, `series` /
  `headType`; `GET /api/v1/status` fields; the multipart `POST /api/v1/upload` with `token` + `file`) and `SnapmakerSacp`
  (the SACP packet: SOF 0xAA55, length, version, receiver, CRC-8, sender, attribute, sequence, command set / id, payload,
  ones'-complement checksum; ACK matching on command + sequence; subscribe / unsubscribe; the hello `0x01/0x05` with
  host name, client name and token; hello heartbeat `0xb0/0x0b`; machine info `0x01/0x21`; the heartbeat, nozzle, bed,
  current-line and printing-time subscriptions; the printing file's info `0xac/0x1a`; the printer-driven upload
  `0xb0/0x00` / `0x01` / `0x02` in 60 KiB chunks with an md5; the printer's goodbye `0x01/0x06`).
- **Change:** two new kinds; the U1 kinds are untouched. `PrinterKind.SNAPMAKER_A_SERIES`
  (`SnapmakerSstpPrinterService`, A150 / A250 / A350 incl. Dual and Quick Swap kits) and `PrinterKind.SNAPMAKER_SACP`
  (`SnapmakerSacpPrinterService`, J1 / Artisan). Live status and temperatures (read-only) and uploading a sliced file,
  which never starts a print. The A-series token and the SACP hello token live in the encrypted apiKey slot, like other
  kinds' secrets; the token only travels in request bodies, never in a URL, message or log. The printer is asked to
  accept Nozzle It All only from the explicit "Connect" button in Edit printer / the add-printer wizard
  (`SnapmakerConnect`); status reads and uploads never send the A-series connect, and a J1 / Artisan with no connection
  name sends nothing at all. Chips in Edit printer and the wizard, send targets, a Control-tab card, Test Mode labels,
  Test Grid families `snapmaker-sstp` and `snapmaker-sacp`, and each kind limited to its own catalogue profiles
  (`SnapmakerModels`: A250 / A350 variants for the A-series, J1 / Artisan for SACP; there is no A150 profile).
- **Gated (`SnapmakerSstp.START_VERIFIED` / `SnapmakerSacp.START_VERIFIED` false; `startVerifiedFor` false):** starting a
  print (`POST /api/v1/start_print`; SACP `0xb0/0x08`) is refused after the upload with "Uploaded <name> to the printer but
  did not start it: starting a print on a Snapmaker printer from Nozzle It All isn't verified on real hardware yet. Start
  it from the printer's screen."; pause, resume, stop, temperatures, nozzle switching and homing / moving with "Nothing
  was sent: <what> on a Snapmaker printer from Nozzle It All isn't verified on real hardware yet. Use the printer's
  screen.", before any request. None of those messages is built. Laser and CNC work is never offered; an A-series
  reporting a laser or CNC head is refused at Connect.
- **Additions of this port (not in the references):** reply time limits (Luban's SACP requests wait without one unless
  retransmitted); a non-zero `0xb0/0x00` ACK is taken as a refused upload (Luban ignores it); a chunk request for another
  file's md5 is answered with the error byte 200; the A-series status is read without the token; closing an A-series
  service does not send `POST /api/v1/disconnect` (short-lived services share the one accepted session).
- **Test evidence:** unit tests only. `SnapmakerSacpTest` (known-answer packets computed with a line-by-line Python
  transliteration of the SDK's `helper.js` CRC-8 / checksum and `Header.js` / `Packet.js` serialisation, and payload
  fixtures packed with Python's `struct` from the SDK models' layouts) and `SnapmakerSstpTest` in :domain;
  `SnapmakerSstpPrinterServiceTest` (MockWebServer) and `SnapmakerSacpPrinterServiceTest` (a fake SACP peer on
  127.0.0.1) in :app; `GatedKindsTest` in :test-grid. Nothing here has met a real printer.
- **Known divergence / to verify on hardware:** the A-series HTTP port (Luban's `PORT_SCREEN_HTTP` is outside the
  partial clone; 8080 is assumed, an address with a port wins); whether `GET /api/v1/status` needs the token (Luban's
  heartbeat worker is outside the clone); the A-series state names (Luban's WorkflowStatus enum is in a package not
  used; idle / running / paused are inferred); the J1 / Artisan state (Luban's WORKFLOW_STATUS_MAP is not in the
  sources, so the state is always "unknown" and a send only uploads); the Artisan's machine-type number
  (SACP_TYPE_SERIES_MAP is not in the sources); whether each short-lived SACP service's hello re-prompts on the printer's
  screen. No discovery, camera, file list, fans, lights or enclosure.
- **Touches:** printer transports (Android), shared UI (printer type chips, add-printer wizard, Control tab), Test Grid
  classification, slicing-profile gating.

## P-0038 — USB-connected Marlin/Prusa-protocol serial printer (original driver on `android.hardware.usb`, facts only from GPL sources)

- **Upstream:** FACTS ONLY, never copied, from GPL-2.0-licensed Linux kernel USB-serial drivers, commit
  `551c722f40809618230001baccf219193e22fc5a` (local read-only clone at `linux-usb-serial/`): `drivers/usb/serial/ch341.c`
  (CH340/CH341 vendor requests, register addresses, baud prescaler/divisor formula, inverted modem-control byte),
  `drivers/usb/serial/cp210x.c` (Silicon Labs CP210x vendor requests, literal little-endian baud field, modem handshake
  value), `drivers/usb/serial/ftdi_sio.c` / `ftdi_sio.h` (FTDI SIO requests, the 48 MHz fractional divisor table, modem
  control mask+state encoding). GPL-2.0 is incompatible with this repository's licence, so nothing from these four files
  was read as code to transcribe: only public facts (control-request numbers, register addresses/values, ID tables,
  command sequences) were taken, and every baud-divisor calculation here was re-derived independently from the USB-IF's
  and each chip vendor's own public datasheets, not copied from the kernel's arithmetic. Cited in code comments as
  `linux/<file>:<line>`. CDC-ACM class constants (`SET_LINE_CODING`, `SET_CONTROL_LINE_STATE`, the control/data interface
  classes) come from the USB-IF's public CDC 1.2 specification, cross-checked only for definitions (never code) against
  `/usr/src/linux-headers-7.0.0-34/include/uapi/linux/usb/cdc.h` (also GPL-2.0, facts only). The host-side G-code
  streaming/resend/M115-capability protocol (Marlin's own documented serial protocol) was cross-referenced for facts only
  against Ultimaker Cura's USBPrinting plugin (LGPL-3.0, `plugins/USBPrinting/USBPrinterOutputDevice.py` /
  `avr_isp/`-adjacent serial handling) and PrusaSlicer's `src/slic3r/Utils/Serial.cpp` / `src/slic3r/GUI/Jobs/*` G-code
  sender path (AGPL-3.0). Neither plugin's code or comments were copied; only the line protocol's observable shape
  (checksummed `N<n> ...*<checksum>` lines, `ok`/`Resend:`/`busy:`/`Error:`/`start` replies, `M115`'s `Cap:` lines,
  `M27`/`M20` reply shapes) was used, which is also documented independently by Marlin's own firmware source and RepRap
  wiki pages.
- **Imported:** nothing copied. An original Kotlin USB-serial driver against Android's built-in `android.hardware.usb`
  host API (no new Gradle dependency, no network fetch of any kind): `UsbSerial.kt` (chip identification by USB class
  for CDC-ACM or by vendor:product ID table for CH34x/CP210x/FTDI; per-chip open/restart control-transfer sequences;
  baud encoding for each chip family) and `MarlinSerial.kt` (checksum, numbered-line framing, reply parsing, `M115`
  capability parsing, `M27`/`M20` parsing, a one-line-in-flight send window with resend recovery) in `:domain`;
  `UsbSerialPrinterService.kt` (a `PrinterService` reading firmware/temperatures and SD progress, built only against
  the plain `UsbSerialPort` interface) in `:transport`; `UsbSerialTransport.kt` (the concrete `UsbSerialPort`: opens
  the `UsbDeviceConnection`, claims the interface, issues the control sequence, runs bulk IN/OUT), `UsbSerialDeviceManager.kt`
  (USB permission request/broadcast, device enumeration, the synthetic `"usb:<vendorId>:<productId>:<serialNumber>"`
  identity) and a device-picker UI in the add-printer wizard and Edit printer, all in `:app` - `:transport` is plain
  JVM Kotlin (reused by `:desktop`) and its `verifyNoAndroidImports` check task forbids `android.*`/`androidx.*`
  imports there, so the real Android implementation cannot live alongside the interface it implements.
- **Change:** one new kind, `PrinterKind.USB_SERIAL` (`UsbSerialPrinterService`). Live temperatures (`M105` polling, or
  `M155 S2` auto-report when the firmware's `M115` `Cap:` line advertises `AUTOREPORT_TEMP:1`) and SD job progress
  (`M27`), read-only. No camera, no on-device slicing add-ons, no Klipper extras, no pause/resume/cancel. Connecting
  never restarts the printer's board: every chip is opened with DTR and RTS de-asserted (never toggled automatically),
  and the reader thread only ever writes `M110 N0`, `M115`, `M105`, `M155 S<n>` (gated on the capability check above),
  `M27` and `M20` — nothing that moves, heats, extrudes, changes settings or writes EEPROM/files. DTR/RTS per chip:
  - CDC-ACM (`UsbSerial.CdcAcm`): `SET_CONTROL_LINE_STATE` (request `0x22`) with a control-line bitmap (`0x01`=DTR,
    `0x02`=RTS); the open sequence sends this with both bits clear, so DTR/RTS start de-asserted; only the explicit
    restart action sends DTR set (RFC-1394-style CDC modem control, USB-IF CDC 1.2, `linux/ftdi_sio.h` not applicable
    here since CDC-ACM is a standard class, not a vendor driver).
  - CH340/CH341 (`UsbSerial.Ch34x`, `linux/ch341.c`): a `REQ_MODEM_CTRL` (`0xA4`) vendor request whose value is the
    control byte's bitwise complement (the chip's hardware convention is active-low on this wire) — `modemControlValue`
    inverts the bitmap before sending, so a DTR/RTS-clear request is *not* simply value `0`; the open sequence's
    `REQ_MODEM_CTRL` call passes both lines de-asserted, and only the explicit restart action asserts DTR.
  - CP210x (`UsbSerial.Cp210x`, `linux/cp210x.c`): `SET_MHS` vendor request with a mask+state pair (bits 0/1 select
    which of DTR/RTS to change, bits 8/9 carry the new value) — the open sequence's `SET_MHS` selects and clears both
    lines explicitly; only the explicit restart action selects and sets DTR.
  - FTDI FT232R/FT231X-class (`UsbSerial.Ftdi`, `linux/ftdi_sio.c`): `SIO_MODEM_CTRL` vendor request, same mask+state
    convention as CP210x (low byte state, high byte mask) — the open sequence clears both lines explicitly; only the
    explicit restart action sets DTR.
  If a board answers nothing within the reply timeout, `UsbSerialPrinterService` surfaces a clear message rather than
  raising DTR itself; only an explicit, user-initiated "Restart the printer's board to connect" action (`restartBoard()`)
  raises DTR, and its own UI text says it restarts the printer and must not be used while printing.
  Wired: `PrinterCapabilities.capabilitiesFor`/`startVerifiedFor` (`UsbSerialPrinter.START_VERIFIED`),
  `printerServiceFor`, `normalizedAddress`/`normalizedInputAddress` (`PrinterModel.kt`), a device-picker chip and panel
  in the add-printer wizard (`AddPrinterWizard.kt`) and Edit printer (`M1Panels.kt`), a Control-tab card
  (`MainActivity.kt`), refusal text in `SliceAndPrintPanel.kt`/`ProjectEditorScreen.kt` for the "can't save a file"
  case, the manifest's optional `android.hardware.usb.host` feature and `USB_DEVICE_ATTACHED` filter
  (`usb_device_filter.xml`), Test Mode labels (`TestModeScreen.kt`), Test Grid family `usb-serial`
  (`FirmwareFamilies.USB_SERIAL`, `AndroidTestTarget.adapterFor`) and `GatedKindsTest`.
- **Gated (`UsbSerialPrinter.START_VERIFIED` / `UPLOAD_VERIFIED` false; `startVerifiedFor` false):** starting a print
  (`M23`/`M24`) is refused with "USB-connected printers aren't verified on real hardware yet. <name> was not sent. Copy
  the sliced file to the printer's SD card or USB stick and start it from the printer's own screen."; pause, resume,
  stop, temperatures, nozzle switching, homing/moving and fans with "USB-connected printers aren't verified on real
  hardware yet: <what> isn't offered."; uploading a file (`M28`/`M29`) with "USB-connected printers aren't verified on
  real hardware yet. Uploading <name> to the printer's storage isn't offered. Copy the sliced file to the printer's SD
  card or USB stick manually." — all before any byte is sent. Sending a sliced file from the slicer/project-editor
  screens is refused outright, independent of the verified flags, with "Nozzle It All can't save a file to a
  USB-connected printer's storage. Copy it to the printer's SD card or USB stick manually, then print it from the
  printer's own screen." (`UsbSerialPrinter.CANNOT_SAVE_TO_PRINTER`) since this transport's address is a synthetic USB
  identity, not an uploadable HTTP endpoint. No console: user-typed or arbitrary G-code is never sent by this change.
  The `M28`/`M29` upload state machine (`UsbSerialPrinter.SdUpload`) is implemented and unit-tested behind this gate:
  nothing is written after `M28` until the firmware's "Writing to file" acknowledgement line arrives, and any other
  line aborts the upload.
- **Additions of this port (not in any reference):** the synthetic `"usb:<vendorId>:<productId>:<serialNumber>"` printer
  address (this repository's own encoding, not from any reference); CDC-ACM auto-detection by interface class rather
  than a fixed ID table; a CH34x-specific direction patch in `UsbSerialTransport` for `REQ_READ_VERSION` (the
  domain-layer control-sequence builder leaves this IN-direction substitution to the Android transport layer, since
  `:domain` has no dependency on `android.hardware.usb`'s direction constants).
- **Test evidence:** unit tests only, entirely off gthost-build01 (never run against a real device, and never contacted
  via adb/phone/network). `UsbSerialTest`, `MarlinSerialTest` and `UsbSerialPrinterTest` in `:domain` (chip
  identification, per-chip control sequences, baud vectors, checksums, line/capability/SD-status/file-list parsing,
  resend/busy handling, gate defaults and exact refusal strings, the `SdUpload` state machine);
  `UsbSerialPrinterServiceTest` in `:transport` (a fake in-memory `UsbSerialPort`: connect sequence, only-whitelisted-
  commands-written, DTR never raised without the explicit restart action, every gated control path writes zero bytes,
  resend recovery); `GatedKindsTest` in `:test-grid`.
- **Known divergence / to verify on hardware:** every chip's real-world behaviour under this driver (no CH34x, CP210x,
  FTDI or CDC-ACM adapter, and no Marlin/Prusa firmware, was available to test against); whether the reply timeout used
  for the "no answer" message is long enough for every board's boot time; whether every 8-bit AVR-class board really
  auto-resets on DTR the way the safety design assumes (some boards need RTS, some need both, some need neither - this
  driver never asserts either automatically regardless); the exact set of firmware capability strings various Marlin
  forks emit in `M115`'s `Cap:` lines beyond `AUTOREPORT_TEMP`; USB permission behavior across Android OEM skins/versions.
- **Touches:** printer transports (Android, original USB-serial driver), shared UI (printer type chips, add-printer
  wizard, Edit printer, Control tab, slicer/project-editor upload refusal), manifest (optional USB host feature, device
  filter), Test Mode labels, Test Grid classification and gated-kind tests.

## P-0039 — Android colour mixing (Snapmaker Full Spectrum / PrusaSlicer ColorMix), ported from Desktop

- **Upstream:** PRIMARY, an in-repo port: this app's own Desktop implementation (`desktop/.../FullSpectrum.kt`,
  `PrusaColorMix.kt`, `ColourMixingUi.kt`, `PrusaColorMixUi.kt`), which already drives the engine's Full Spectrum
  (`--full-spectrum`) and ColorMix (`--color-mix`, `virtual_extruders`) CLI modes. Nothing outside this repository
  was read or copied; this is Desktop's own logic reused, not a new implementation of either vendor feature.
- **Imported:** the pure-Kotlin request/response formats moved out of Desktop-only code into the shared, pure-JVM
  `:printer-api` module so both transports use one implementation (requirement 1): `FullSpectrumFormat` and
  `PrusaColorMixFormat` in `printer-api/src/main/kotlin/com/nozzleitall/printer/ext/ColourMixFormats.kt` (base
  request/error JSON, `Mixes`/`Mix` row parsing, `Virtual`/`Component`, the sidecar and `virtual_extruders` slice-
  request JSON shapes) plus the existing `ProfileFeatures`/`Snapmaker`/`Prusa` gating objects in `ColourMixing.kt`.
  Desktop's `FullSpectrum.kt`/`PrusaColorMix.kt` now delegate to these shared types instead of holding their own
  copies; Desktop's own `FullSpectrumTest.kt`/`PrusaColorMixTest.kt` were left in place, but needed a follow-up
  fix (commit `e2fef43`) to compile against the shared types before they passed again.
- **Change (Android-only additions):**
  - `app/.../ToolSlots.kt`: `colourMixFeaturesFor(kind: PrinterKind, toolCount: Int): Set<String>`, a small bridge
    from Android's `PrinterKind` onto the `PrinterFamily` ids `ProfileFeatures.of` gates on (Android has no
    `PrinterFamily`/`familyHint` of its own the way Desktop's `PrepareState` does) - `SNAPMAKER_U1_PAXX`/
    `SNAPMAKER_U1` map to `PrinterFamily.PAXX_U1`/`STOCK_U1` (Full Spectrum), every other kind falls through to
    ColorMix's "any other multi-slot printer" branch, and any target with fewer than two tool slots gets neither.
  - `app/.../AndroidColourMixing.kt`: `AndroidFullSpectrum` (`display`/`add`/`remove`) and `AndroidColorMix`
    (`normalize`/`nextId`), the JNI-backed transport objects Android's UI calls instead of Desktop's CLI process,
    both throwing `ColourMixEngineError` on an engine-reported failure.
  - `app/.../ProjectEditorScreen.kt` (Prepare's materials tab, the `toolCount > 1` branch): a "Colour mixing"
    section shown only when `colourMixFeaturesFor(...)` is non-empty (requirement 2), titled "Colour mixing - Full
    Spectrum" or "Colour mixing - ColorMix" depending which system the target offers - never both. Each lists its
    current mixes/blends with a Remove action and one "+ Add 50/50 mix (Tool 1 + Tool 2)" /
    "+ Add 50/50 blend (Tool 1 + Tool 2)" button (a deliberately trimmed single-action entry point compared to
    Desktop's full match/preset/gradient editor, sized for a phone - the plumbing behind it is the real thing, not
    a stub). A mix or blend, once added, can be assigned to an object from the same per-object tool-assignment
    dialog used for physical tools: a ColorMix blend as a "Blend <summary>" chip
    (`project-object-tool-blend-<id>`), a Full Spectrum mix as a "<mix label>" chip
    (`project-object-tool-mix-<id>`) - both mixing systems' mixes/blends are real, assignable virtual tools, not
    just entries in their own management list (the Full Spectrum chip was the one piece missing at first: a mix
    could be created but never actually assigned to anything). `sliceOnePlate` passes `mixedFilamentDefinitions`
    (Full Spectrum's config override, applied only when non-blank) and `virtualExtruders` (ColorMix's blends,
    serialised via `PrusaColorMixFormat.sliceRequestJson`, only when non-empty) into
    `SlicingCoordinator.sliceProject`, which already (from this feature's engine-wiring pass) folds
    `mixedFilamentDefinitions` into the sliced config overrides and switches to `NativeEngine.nativeSliceMultiObjectMix`
    instead of `nativeSliceMultiObjectEx` whenever `virtualExtruders` is non-blank; every printer with no mixing
    configured slices exactly as it did before this feature.
  - **Persistence:** `Project.mixedFilamentDefinitions`/`Project.colorMixJson` (`app/.../project/Project.kt`,
    `MIGRATION_7_8`) hold the project's own Full Spectrum definitions string and ColorMix virtual extruders
    (the latter as `PrusaColorMixFormat.sliceRequestJson`'s own JSON shape, via
    `ColourMixPersistence.encodeColorMix`/`decodeColorMix` in `app/.../project/ColourMixPersistence.kt`) - Android
    has no 3MF export path (`ProjectArchive.kt` writes a `.nozzleproj` zip, not 3MF, and nothing under `:app`
    references `Project3mf`/`ProjectManifest`), so this is Desktop's 3MF-sidecar content kept on the project row
    instead. `ProjectViewModel.setColourMixing(...)` saves both; a `LaunchedEffect(project?.id)` in
    `ProjectEditorScreen.kt` reloads them and rebuilds the Full Spectrum row list via `AndroidFullSpectrum.display`
    whenever a project is (re)opened, so a mix or blend survives closing the editor.
  - **Assignment follow-up on remove/edit:** removing or editing a Full Spectrum mix can renumber or delete other
    mixes' slot ids (`FullSpectrumFormat.Mixes.remap`); removing a ColorMix blend simply retires its own id. Either
    way, any object still pointing at an affected slot must not silently point at nothing - `ColourMixPersistence`
    (`app/.../project/ColourMixPersistence.kt`, unit-tested by `ColourMixPersistenceTest`) has the same two rules
    Desktop's `PrepareState.followRemap`/`removeVirtualExtruder` apply (a deleted slot falls back to tool 1),
    applied via `ProjectViewModel.remapToolSlots(...)` across every object on every plate, not just the active one,
    and persisted through the same Room update every other object edit uses.
  - **Preview colours:** the mixing UI's physical-colour list (`physical` in `ProjectEditorScreen.kt`) is now the
    exact `slotMaterials` list `multiToolSliceInputsFor` computes and `sliceOnePlate` sends the engine, not a
    separate "first object on this slot" lookup of its own - the two could previously disagree once objects were
    reordered or a slot's only object was removed, showing a mix/blend preview colour that didn't match what
    actually printed.
  - `app/.../testgrid/AndroidTestSlicer.kt`: `sliceColourMix(...)` (requirement 3), calling
    `SlicingCoordinator.sliceProject` with the same `mixedFilamentDefinitions`/`virtualExtruders` parameters the UI
    uses - the same code path, not a parallel test-only pipeline. `TestSlicer.slice(SliceRequest)` itself is left
    untouched, since `SliceRequest` (shared with Desktop's own Test Grid suites) carries no notion of mixing.
- **Engine dependency:** the four native entry points this relies on - `nativeFullSpectrum`, `nativeColorMix`,
  `nativeSliceMultiObjectMix`, `nativeSlicePaintSessionMix` in `NativeEngine.kt` - match nozzle-engine PR #9,
  pinned into this branch at nozzle-engine commit `f766512` (app commit `b739d6c`).
- **Test evidence:** `ColourMixFormatsTest` (`:printer-api`, JVM) covers the shared formats' error/base-request
  parsing, `Mixes`/row parsing including the gradient-fields-absent fallback, the `Virtual` file-round-trip
  behaviour (a `toJson()`→`parse()` round trip reads the written colour back as `effectiveHex`, not
  `colorOverride`, since `parse()` only trusts `colorOverride` from a live engine response carrying
  `effective_color`), the sidecar round trip and the `virtual_extruders` slice-request JSON shape. `ToolSlotsTest`
  (`:app`, JVM) covers `colourMixFeaturesFor`'s gating: Snapmaker U1 (stock and PAXX) with 2+ tools gets Full
  Spectrum, any other kind with 2+ tools gets ColorMix, and a single-tool target gets neither, regardless of kind.
  `ColourMixPersistenceTest` (`:app`, JVM) covers the encode/decode round trip and both remap rules above.
  `ColourMixingSlicingDeviceTest` (`:app`, instrumented, package `net.jamesjennison.klippercompanion`) slices the
  bundled Snapmaker U1 pack (4 physical tool slots) through `AndroidTestSlicer.sliceColourMix`: a ColorMix 50/50
  blend of tools 1 and 2 assigned to a cube, asserting non-empty g-code, at least 10 layers, and a tool change on
  at least 80% of layers; a Full Spectrum slice with a real `mixed_filament_definitions` string, asserting the
  sliced config block echoes both `FullSpectrumFormat.DEFINITIONS_KEY` and the definitions value; and a Full
  Spectrum mix built the real way (`AndroidFullSpectrum.add`, the same call the "+ Add 50/50 mix" button makes)
  sliced against an object assigned to that mix's own id, with the same alternating-tool-change assertion as the
  ColorMix blend test. Kept on Snapmaker U1 rather than a non-U1 multi-slot pack (see the test file's own header):
  U1 is the only bundled profile this app has proven slices multi-tool through `SlicingCoordinator`'s production
  path at all. Neither talks to a real printer (a local file slice against a bundled asset profile, like every
  other `*SlicingDeviceTest`).
- **Known divergence:** Android's colour-mixing UI is intentionally narrower than Desktop's - one "add a 50/50
  mix/blend" action per system plus Remove, not Desktop's full match/preset/gradient/manual-pattern editor; every
  value the UI writes (indices, fractions) is real and reaches the slice, so this is a scope reduction for a phone
  screen, not a placeholder.
- **Touches:** shared printer-mixing formats (`:printer-api`, `:desktop`), Android Prepare/project editor UI,
  Android's Test Grid slicer hook, unit and instrumented test suites.

## P-0040 — Print profiles (process presets) on Android, and a Color Mixing preset for the Centauri Carbon

- **What:** Android Prepare gets a Print profile picker: the OrcaSlicer process presets the printer vendor ships for the
  printer's machine, instead of one fixed process per pack. The chosen preset is saved per project
  (`Project.processPreset`, Room 8→9); Advanced settings apply on top, and a saved custom profile records the preset
  it was based on (`CustomProfile.basePreset`). `SlicingCoordinator.slice`/`sliceProject` take
  `processPreset: String?` (the full Orca preset name; null is the pack default).
- **Which presets (facts from OrcaSlicer at 824b216, decided by the engine):** `scripts/bundle_process_presets.py`
  writes `slicer_profiles/<pack>/processes.json`. A pack is offered its machine vendor's own instantiable process
  presets that nozzle-engine's `--compatible-presets` (nozzle-engine PR #10; OrcaSlicer's own
  `is_compatible_with_printer`) accepts for the pack's `machine.json`. Shared preset files are flattened once per
  vendor under `slicer_profiles/shared_processes/<vendor>/` with the same `fix_bed_type` repair the vendor packs get. The
  COSMOS packs are checked as Orca's "Elegoo Centauri Carbon 0.4 nozzle". The four Prusa MMU3 packs, whose machines no
  Orca vendor ships, offer their own process only.
- **Printer libraries:** the Snapmaker U1 and Elegoo CANVAS packs offer their printer library's presets
  (`engine/profiles/library`, the set Desktop uses), packaged by the `printerLibraryAssets` Gradle task as
  `printer_library/` assets, beside the pack's own process (still the default; the pack files are unchanged, so
  Android's default G-code doesn't change). The library preset a pack's process came from isn't listed twice (U1's
  "0.20 Standard" is the library's "0.20mm Standard" under its `renamed_from` name).
- **Derived preset (original, not copied):** `engine/profiles/derived/elegoo_centauri_color_mixing.json` makes
  "0.10mm Color Mixing @Elegoo CC 0.4 nozzle" and "...@Elegoo CC2 0.4 nozzle" from Elegoo's own 0.12mm Fine presets
  with the parameter changes of Snapmaker's U1 "0.10mm Color Mixing" recipe (listed in the file, with what was left
  out and why). Offered only on multi-filament packs (CANVAS, COSMOS AFC) and marked "(Nozzle It All)" in the picker.
  Not yet tested on a real printer.
- **Packaging:** the shared folder was first named `_processes`. Android packaging leaves out asset folders whose
  names start with "_", so none of its 972 files reached the APK and any non-default profile from it failed to open
  (found 2026-10-01 by the Centauri Carbon color reference slice in Test Mode on a Razr 2023; the unit tests read the
  source tree and passed). Renamed to `shared_processes`. `:app:verify<Variant>PackagedProfiles`, which every
  `assemble<Variant>` runs, opens the built APK and fails the build when a file a pack's `processes.json` lists is
  not in it.
- **Test evidence:** `ProcessPresetsTest` (`:domain`), `BasicSlicingTest`/`SettingsCatalogTest` (`:app`),
  `ElegooCanvasTest` (`:desktop`, slices with the derived preset through the real engine).

## P-0041 — Per-extruder lookups miscompiled on arm64 (the U1's `M140 S32769`): engine hardening, NDK 29, heater-target check

- **Upstream:** nozzle-engine `a3c56ef690cba3790fd9bad23143af5db4ecfe74` (`engine/fork/ENGINE_PIN.json`; James-Jennison/nozzle-engine
  branch `fix/arm64-get-at-miscompile`): `f766512` (the colour-mixing pin) plus one commit. Nothing was imported from
  outside this repository or the engine fork.
- **Root cause:** on 2026-09-30 the Snapmaker U1 refused `M140 S32769` ("heater_bed: Requested temperature (32769.0)
  out of range (0.0:100.0)") from the Test Grid's Full Spectrum swatch slice. The Android engine was built with NDK
  27.1.12297006 (clang 18.0.2). Its loop vectorizer miscompiles GCode.cpp's bed-temperature reduction over
  `print.extruders()` (unsigned ids) once the inlined `ConfigOptionVector::get_at` bounds check is involved: the vector
  lanes test `(size & 1) & ~(id & 1)` instead of `id < size`, so even ids read past the end of a one-element
  `bed_temperature_initial_layer` vector (a heap chunk header, 0x8001 = 32769) and in-range ids land on the first value.
  The IR is target-independent and the source is not undefined behaviour: GCC (the x86-64 desktop engine and the golden
  outputs), clang 21 (NDK 29) and Emscripten 3.1.74 / 6.0.10 (the web engine) compile the same source correctly, and
  `-fno-vectorize` fixes NDK 27.1 and 27.2. Two sites in the shipped engine were affected (the bed-temperature and the
  `min_vitrification_temperature` reductions in GCode.cpp). The mix-slice Test Grid test had passed earlier only
  because its evidence keeps the G-code's SHA-256, not the file, and no check read the heater targets.
- **Engine change:** `get_at()` indexes through `checked_index()`, whose empty asm statement makes the index opaque to
  the vectorizer (plain C++ rewrites of the check still miscompile under NDK 27; the barrier was verified on the Razr
  under NDK 27 -O3 and NDK 29, and compiles with GCC and Emscripten). The nullable `is_nil(idx)` overloads no longer
  index past the end (a missing per-extruder value is the first one's; an empty vector is nil). A Catch2 case in
  `tests/libslic3r/test_config.cpp` mirrors the GCode.cpp reductions. `tools/nozzle/build_android_engine.sh` builds
  with NDK 29.0.14206865.
- **App change:** `app/build.gradle.kts` `ndkVersion` 29.0.14206865 (clang 21), the same default in
  `engine/fork/android/prepare_engine_root.sh` and `ci_engine_root.sh`, README updated. The Android dependency prefix
  (Boost, CGAL, TBB, …) is still the NDK 27 build; libc++'s ABI is stable across NDK releases and the NDK 29 engine ran
  on the device against it. Test Grid: the new `heater_targets` G-code check (every M140/M190/M104/M109 or
  `SET_HEATER_TEMPERATURE` target within range, and every bed target one the filament profile declares) is in every
  suite's `scan_gcode` step, so a slice like the U1's fails at the scan instead of at the printer; the simulated slicer
  emits the profile's heater targets so the check is exercised by the JVM suites; `MANIFEST_SCHEMA.md` documents it.
- **Test evidence:** gthost-build01, 2026-09-30/10-01 (local evening of 2026-09-30):
  - Engine Catch2 `[Config]` (GCC x86-64): the new case passes (9 assertions) with the fix and fails 1 of 9 without it
    (the `is_nil(7)` of a one-element nullable vector, the out-of-bounds read). The one other `[Config]` failure,
    "DynamicPrintConfig serialization", fails identically on `f766512` and is unrelated (the fork's cereal round trip).
  - Engine `slic3r_cli_test` built for arm64 from the fix with NDK 29 and, as a control, with NDK 27.1: both slice the
    failing Test Grid request (`request-6.txt`, the U1 Full Spectrum swatch) on the Razr (ZP22235MHM) to `M140 S60`
    where the shipped engine emitted `M140 S32769`. NDK 29 `libslic3rengine.so` SHA-256
    `5455b5a865b533ae50dce6e8d792bc43aaa623fe0428c30fad1b05f1715128f0`.
  - `./gradlew :test-grid:test` (RunnerTest 34, ManifestTest 14): all pass, including
    `theHeaterTargetCheckCatchesABedTemperatureBeyondTheProfile`.
  - nozzle-engine CI on the fix (pull request #11, run 36812452726): success at 2026-10-01T04:00Z: desktop engine, golden outputs on every bundled printer profile
    (unchanged), engine contract, colour mixing through the Android bridge, Android engine (arm64-v8a, NDK 29) and the
    WebAssembly engine all green; native engine SHA-256 `9cf32883dc03ea7d00a2cdd86e1b4592db1f026f257777d97c28ad515a034455`
    (build record in `engine/fork/PROVENANCE.txt`).
  - Testing build (`Nozzle It All - Testing`, `.testgrid`) rebuilt on gthost with this pin and NDK 29: `application-label` "Nozzle It All - Testing",
    `com.nozzleitall.app.testgrid`, `libslic3rengine.so` SHA-256 `493123b57b59b560c084910fda2a1686e5948a5e73503b977c94f38e71c6521b`
    (clang 21.0.0 build). The first install (main + this fix) crashed at launch with Room's "A migration from 9 to 8 was
    required but not found": the owner's Testing app had been built from the color-reference branch (PR #54/#55, Room
    9), so the Testing app was rebuilt from `integration/color-reference-ndk29` (that branch with this fix merged in,
    `8d88a9c`, pushed, not for merging) and installed over it with `adb install -r` on 2026-09-30 at 21:45 local:
    launches, keeps the saved printers (lava, CC1) and the Room 9 database. Test Mode on the Razr against the real U1
    (lava, PAXX extended firmware, identified), suite "Snapmaker U1 on PAXX extended firmware", level 0: all four
    slicing tests PASS on the phone's engine, their `scan_gcode` steps run `heater_targets` and record `bedTargets: [60]`
    (single-material, multi-material, the 50/50 Full Spectrum swatch and the color reference tiles, 6 mixes with
    process "0.10mm Color Mixing @Snapmaker U1 (0.4 nozzle)"). The sliced files on the phone say `M140 S60` / `M190 S60`
    where the owner's print had got `M140 S32769`. The U1 print of the color reference tiles from this build is the
    owner's to re-run.
- **Known divergence:** none in slicing output: the fixed engine's x86-64 golden outputs are unchanged; on arm64 the
  only change is that per-extruder values are now read from the right element.
- **Touches:** engine build (NDK), slicing (per-extruder settings on Android), Test Grid checks and suites.

## P-0042 — The G-code thumbnail draws a mixed filament in the mix's color

- **Upstream:** nozzle-engine `490e5b5255ffb96b1d8740677e4a6196acc3cca5` (`engine/fork/ENGINE_PIN.json`; James-Jennison/nozzle-engine
  branch `fix/arm64-get-at-miscompile`, pull request 11): `a3c56ef` (P-0041) plus one commit. Nothing was imported from
  outside this repository or the engine fork; the colors come from code the engine already had (Snapmaker Orca's
  `MixedFilamentManager`, `src/libslic3r/MixedFilament.cpp`, and PrusaSlicer 2.9.6's
  `FullSpectrum::VirtualExtruder::effective_color`, `src/libslic3r/Feature/FullSpectrum/VirtualExtruder.cpp`).
- **Root cause:** on 2026-10-01 the owner's Snapmaker U1 color reference print (six tiles, six Full Spectrum mixes)
  showed a thumbnail with every tile in one color. The bridge's thumbnail renderer
  (`nozzle/bridge/android/thumbnail_render.cpp`, shared by the Android, desktop and web bridges) looked a part's
  filament id up in `filament_colour` alone. A mixed filament's id is past the physical filaments, so every Full
  Spectrum mix fell back to filament 1's color, and a ColorMix virtual extruder was drawn as whichever physical
  filament shared its id. (That the one color was white rather than the first toolhead's is the app's own bug, fixed
  separately: every Moonraker printer was read as an empty Qidi Box, so the slice never got the U1's colors.)
- **Engine change:** the renderer asks the engine for the color it shows for each mixed filament id, from the same
  state `Print::apply` builds (PrintApply.cpp): the physical colors padded to the filament count, then the model's
  virtual extruders (`effective_color`), else the mixed filament manager's auto-generated and custom rows
  (`MixedFilament::display_color`, ids resolved by `mixed_filament_from_id`). Physical filaments are drawn as before.
  The two includes are guarded with `__has_include`, so the bridge still builds against an engine without either
  feature.
- **App change:** the pin and the places that repeat it (`OpenSourceNotice.ENGINE_COMMIT`, the settings schema's
  `commit`, the site's open-source page). No app code changed.
- **Test evidence:** gthost-build01, 2026-10-01 (local early morning):
  - Engine `tools/nozzle/colourmix_test.sh` gained two checks, using the new `tools/nozzle/thumbnail_color.py`
    (standard library only: decodes the largest embedded thumbnail and tests its most common opaque pixel against a
    base color under the renderer's shading). The virtual-extruder slice (red + green, 50/50) must be drawn in
    `#535208`, what `--color-mix` answers for that mix; a `mixed_filament_definitions` slice on mixed filament 6
    (blue + yellow) must print with T2 and T3 only and be drawn in `#3E9967`, the Color Mixing panel's color for that
    row; neither may pass as filament 1's red. With the fix all six checks pass (worst channel error 0.6 and 0.5).
    Against the previous build (`a3c56ef`'s CI binaries) both thumbnail checks fail: the virtual extruder was drawn
    blue (filament 3) and the mix red (filament 1).
  - The six tiles of the color reference, sliced by the desktop engine with the U1's real colors (`#00FFFF`,
    `#D93B90`, `#F4C032`, `#9199A4`) and its six mixes: the previous build's thumbnail has one color (cyan,
    filament 1), the fixed build's has six.
  - nozzle-engine CI on the fix (pull request 11, run 36853299329): success at 2026-10-01T11:19Z: desktop engine,
    golden outputs on every bundled printer profile (unchanged), engine contract, color mixing through the Android
    bridge (with the two new thumbnail checks), Android engine (arm64-v8a, NDK 29) and the WebAssembly engine all
    green; native engine SHA-256 `0d851ce739ef85127b4c2cb59d72dfe974e8ff7c604eadd26053546d94ef7e81` (build record in
    `engine/fork/PROVENANCE.txt`).
  - Testing build (`Nozzle It All - Testing`, `com.nozzleitall.app.testgrid`) rebuilt on gthost from
    `integration/color-reference-ndk29` with this pin and the Qidi Box fix (pull request 59); the engine export's
    `.nozzle-engine-commit` is `490e5b5`, `libslic3rengine.so` SHA-256
    `73074b5e4cd3d031f26d5b40c9a7d0293ad286980c0f49d7aea1830a23c981d9`. Installed on the Razr 2023 (ZY22HXCVPM, a first
    install) on 2026-10-01 at 04:11 local; it launches. The color reference slice has not been re-run on a phone
    with this build yet (the U1 was mid-print).
- **Known divergence:** none in slicing output (golden outputs unchanged); only the embedded thumbnail of a slice
  that uses a mixed filament differs.
- **Touches:** G-code thumbnails (Android, desktop, web), engine tests.

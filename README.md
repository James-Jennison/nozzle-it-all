# Nozzle It All

A printer companion app that started as a Klipper/Moonraker monitor and is evolving into a
consumer-grade mobile 3D-print slicer and control platform. No memberships, advertising, cloud
account, or analytics — local-first is a deliberate, load-bearing design choice, not a gap to be
closed later (see [Local-first vs. cloud](#local-first-vs-cloud) below).

Where this project is headed: [`docs/CONSUMER_SLICER_PLAN.md`](docs/CONSUMER_SLICER_PLAN.md) — a
detailed, owner-approved architecture and roadmap (14 phases, Android + iOS + multi-OS Desktop)
audited against the real repository, not aspirational. Active/in-progress work is tracked in
[`docs/WORK_ORDER.md`](docs/WORK_ORDER.md). Earlier planning docs
([`FEATURE_PARITY_ROADMAP.md`](docs/FEATURE_PARITY_ROADMAP.md),
[`FREE_FEATURE_SCOPE.md`](docs/FREE_FEATURE_SCOPE.md)) predate this scope change and are
superseded by `CONSUMER_SLICER_PLAN.md` for the areas it covers.

## What's real today

- **Printer control**: connect to Klipper/Moonraker, Bambu Lab (LAN MQTT/FTPS), Prusa Link, and
  Snapmaker U1/PAXX printers. Monitor state/temperatures, continuous WebRTC camera video, browse
  G-code files, run macros, start/pause/resume/cancel with explicit confirmation. Saved printer
  profiles with one active printer at a time.
- **On-device slicing engine**: a real, AGPL-3.0-or-later, on-device slicing engine
  cross-compiled from upstream [OrcaSlicer](https://github.com/OrcaSlicer/OrcaSlicer)'s
  `libslic3r` (not a cloud call, not a mock) — see
  [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md). Produces real G-code from STL/3MF/OBJ
  against bundled per-printer profiles.
- **In-app 3D build-plate viewer**: real-time GLES30 rendering, move/rotate/scale object
  transform, real build-volume bounds checking against each printer's actual bed shape, and
  brush-based support painting (enforcer/blocker) using the same `TriangleSelector`/`AABBMesh`
  machinery the upstream GUI uses.
- **Post-slice toolpath preview** with a layer slider and real stats parsed from the sliced
  G-code (print time, filament weight/length).

Opt-in background print alerts (completed/error/cancelled/offline/back-online/paused) run via a
foreground service independent of the app being open. A separate "Projects" section (Files tab)
persists a real multi-object build plate across process death — add/duplicate/remove/rename/delete,
move/rotate/scale each object, a real collision check (a proper rotated-rectangle overlap test,
not just a bounding-box guess) that blocks slicing while objects overlap, and a one-tap
auto-arrange (real 2D bin packing, not a stub) to resolve it — backed by Room, and slices the whole
plate at once on-device with the same review-then-confirm pipeline (sliced 3D preview + stats,
printer-ready confirmation, upload, explicit Start print) the single-object share-intent/Prepare-tab
flow above already uses. This closes Phase 1 of the roadmap. A single `PrinterCapabilities` object
(one per vendor: Klipper/Moonraker, Snapmaker U1/PAXX, Bambu Lab, Prusa Link) now drives every
control-visibility decision in the app, replacing the printer-kind conditionals that used to be
scattered across the UI — closing Phase 2. A project now picks a single material (four bundled
presets, or a real spool read live from Spoolman — its actual configured nozzle/bed temperatures,
not invented ones) that flows straight into slicing, closing Phase 3. Slicing settings are now a
real beginner-tier surface — named quality presets (Draft/Standard/Fine) instead of a raw
layer-height number, a support Auto mode that reads the model's own real geometry to decide
whether it needs support, and bed-adhesion/copies controls — closing Phase 4. (See
`docs/WORK_ORDER.md`'s WO-17 through WO-20 entries for the detailed history, most current for this
area — it's updated more often than this file.) No multicolor or a model-discovery surface yet —
see the plan document's gap matrix for the fuller current-vs-target breakdown, though
`docs/WORK_ORDER.md` supersedes it for anything the two disagree on.

Only use a trusted LAN endpoint for printer control. HTTP is supported for conventional local
Moonraker installations. This app rejects URL credentials and does not store passwords or API
keys in plaintext (Android's `security-crypto` for saved printer profiles). Moonraker
authorization-required responses are shown as unsupported authentication; the app does not change
printer authorization settings. Camera URLs resolve relative to the configured web frontend
address, so prefer the Mainsail/Fluidd base URL when webcams use relative paths.

Mutating requests are never automatically replayed. A lost response is an unknown outcome; inspect
the printer before deciding on another action. Macros and slicing can move or heat the printer, or
consume filament; review behavior before confirming.

## Local-first vs. cloud

The app has no account, no telemetry, and no server of its own today. That stays the default as
the project grows. Where a real consumer feature genuinely needs something cloud-shaped (e.g. a
model-repository browser, or syncing a project across your own devices), the plan treats it as an
individually-justified, clearly-bounded, off-by-default exception — never a foregone conclusion.
See `CONSUMER_SLICER_PLAN.md` §4 and §12 for the actual reasoning per feature.

## Build and validate

Requires Android SDK 36, JDK 17, and NDK 27.1.12297006 (pinned — see `app/build.gradle.kts`'s own
comment on why). The native slicing engine additionally requires the owner's separate
`orcaslicer-android-engine` project (a patched OrcaSlicer checkout plus prebuilt
Boost/CGAL/GMP/MPFR/OpenVDB/OCCT/OpenCV cross-compiled for `arm64-v8a`) at a machine-local path —
see `app/src/main/cpp/CMakeLists.txt`'s header comment. There is no public/portable substitute for
this yet; a checkout without it can still build everything except the native `slic3rengine`
target.

Run `bash scripts/validate.sh` from the repository root for the standard JVM/lint/unit-test gate.
Physical-device UI and native-engine checks use `./gradlew connectedDebugAndroidTest` with the
exact intended device selected via `ANDROID_SERIAL`. Actual printer mutation acceptance is
owner-operated, on real hardware.

CI (`.github/workflows/ci.yml`) runs unit tests, lint, and the full native build on a self-hosted
runner provisioned with the pinned NDK/CMake and the `orcaslicer-android-engine` dependency —
required because the native build cannot run on a stock hosted GitHub runner. AWS Device Farm
instrumented-test coverage across a real device pool is planned but not yet wired up (needs AWS
credentials/project ARNs as repo secrets).

## Sources

- Moonraker protocol: https://moonraker.readthedocs.io/en/latest/external_api/
- Jetpack Compose: https://developer.android.com/develop/ui/compose
- OrcaSlicer: https://github.com/OrcaSlicer/OrcaSlicer

Live camera playback uses an isolated Android WebView with app-owned receive-only WebRTC code and
local signaling. It does not load camera-server JavaScript or use external STUN servers. Camera
URLs must remain on the configured printer host. Snapshot-only cameras remain explicitly labeled
as refreshed snapshots.

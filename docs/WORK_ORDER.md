# Work order

Derived from [`FEATURE_PARITY_ROADMAP.md`](FEATURE_PARITY_ROADMAP.md), last
re-derived 2026-09-20. That day: a Helix feature-set comparison surfaced a
missing emergency-stop control (built, P29, commit `f080790`); a stale
physical-acceptance status got corrected (see below); and the owner extended
"build it even though I don't own the hardware" into a standing principle,
under which Panda Breath (P31, commit `afde29d`), Spoolman (P13, commit
`7fa93f1`) and PAXX multiACE (P30, commit `70a8a7b`) all got built. A related
but distinct ask followed — "use the same type of logic for Prusa" — which
turned out to have no Helix logic to reference at all (Helix has zero Prusa
support), so Prusa (P26, commit `8a90a0b`) got built fresh from Prusa's own
published PrusaLink OpenAPI spec instead, including a from-scratch RFC 2617
HTTP Digest auth implementation. None of these four are physically verified —
see the "Later" tier below. Separately, **WO-1, WO-2 and WO-3 are now all
done**: WO-1's `PrinterTile` unverified-hardware indicator, WO-2's `KilnFrame`
panel restyle (planned in Plan Mode first), and WO-3's notification actions +
`NozzlePrinterWidget` (also planned in Plan Mode first, per the owner's
request) — see the roadmap's P18/P28/M7 sections for each one's commit hash
and what's still not physically verified. All three are dropped from the
numbered list below; WO-3's own device-verification need becomes its own
new item, WO-12, in the "Next" tier.
**Correction, same day:** this document previously said Phase 1/M2 physical
acceptance "needs an explicit, separate... owner go-ahead — this isn't a
default-yes," which had gone stale — that go-ahead was already given on
2026-09-19, and heater controls are already a complete physical PASS on the
real Snapmaker U1, with fan controls partially verified. See WO-6 below and the
roadmap's M2 section for the real current state. The roadmap is the source of
truth for scope, evidence and licensing detail; this document exists only to
turn its current state into a single ordered queue of next actions, so "what's
next" never requires re-reading the whole roadmap. Re-derive this list whenever
a phase below completes or the roadmap changes — don't let it drift, and don't
trust a status here without spot-checking the underlying code/docs first, the
way this correction had to.

Rule: work top to bottom within a tier. Skip an item only when it is explicitly
blocked (an owner action or hardware it names), and move to the next unblocked one.
Don't start a "Later" or "Parked" item ahead of an unblocked "Now"/"Next" item just
because it looks more interesting.

## Now — unblocked, no owner action needed to start

Empty as of 2026-09-20. Every unbuilt-code item in this document is now either
waiting on the owner being physically present with a printer (Next, below) or on
hardware/decisions the owner doesn't have yet (Later, below). Re-derive this
section from the roadmap the next time either changes.

*(WO-1, WO-2 and WO-3 are all done — see the intro above and the roadmap's P18/
P28/M7 sections for what each one built and its commit hash.)*

## Next — one specific owner action unblocks each of these

1. **WO-4 — Device-verify Bespok3d enrollment + remote screen (M8b).** Code and
   unit tests are done. Blocked only on the owner's own Snapmaker U1/PAXX SSH
   access code — once supplied, this is a verification pass, not new development.
2. **WO-5 — Device-verify the timelapse gallery (M8d).** Code and tests are done.
   Needs a live pass on the Razr against a printer that actually has
   moonraker-timelapse clips recorded.
3. **WO-6 — Continue the already-authorized physical acceptance pass.** Owner
   authorized live testing on 2026-09-19; `LIVE_HEATER_FAN_CONTROLS_ENABLED` is
   `true` and supervised, category-by-category sessions against the real
   Snapmaker U1 are ongoing (owner watching throughout, cross-checked against
   Moonraker's own status each time — not self-certified by the app). Done so
   far: heater controls (bed + nozzle) **complete PASS**; fan controls
   **partial** (`fan`, `cavity_fan` verified; `exhaust_fan`/`circulation_fan`
   found to be purifier-managed and hidden rather than left misleading;
   `e1_fan`–`e3_fan` untested). Emergency stop (P29) is now built (commit
   `f080790`) and ready for its own session. Still needs a session: the rest of
   fan controls, P07's speed/flow/movement/extrusion, P08 live file mutations,
   P09 live tracking, remaining hardware macro acceptance, and emergency stop.
   Needs the owner physically present with the printer each time, same as the
   sessions already done — not a go/no-go decision anymore, that part already
   happened. Helix's own shipped, real-world use of these same Moonraker calls
   (found while building emergency stop) is useful supporting context, not what
   actually unblocked this — the owner's own sign-off did, a day earlier.
4. **WO-12 — Device-verify the notification actions (P18).** The widget half is
   done: found and fixed a real bug on the first device pass (`exported="false"`
   on the receiver meant it never appeared in the widget picker at all, commit
   `96b30d5`), then confirmed live with real print data on the Razr 2023 —
   layer/elapsed/finish-time/temperatures all showing correctly. The widget's
   design changed twice more after that: the owner corrected an initial
   per-instance-picker build into showing every saved printer in one instance
   (commit `2fb4fdf`, superseding `bbc949e`'s per-instance selection), then a
   UX-council review (`frontend_ux` panel, 4 seats) ruled out a live camera
   thumbnail and drove four more fixes — scrollable list instead of clipping
   past 2 printers, distinct paused/error colors, a real 48dp Refresh touch
   target, and bed temperature off the overloaded status teal (same commit
   `2fb4fdf`). All device-verified live except the new paused/error colors,
   which have no live paused/error print to confirm against yet. Still open:
   trigger a real paused-state transition (or a fixture) and confirm both the
   notification shows Resume/Cancel and opens the right printer with the
   confirm dialog pre-staged, AND that the widget's paused/error card colors
   render as designed. Doesn't need any specific printer kind, just the owner
   present with any saved printer when a pause happens.

## Later — blocked on hardware the owner doesn't have, or needs a decision first

5. **WO-7 — Bambu Lab hardware acceptance (M8c / P25).** Code exists and is
   unit-tested; blocked on the owner owning or gaining access to real Bambu
   hardware. Not actionable until then.
6. **WO-8 — Prusa hardware acceptance (P26 / M7).** Code built 2026-09-20
   (`PrusaLinkPrinterService`, RFC 2617 digest auth, unit-tested against
   Prusa's own published PrusaLink OpenAPI spec — no Helix reference existed
   for this one, Helix has no Prusa support at all). Same hardware-availability
   blocker as WO-7; not actionable until the owner owns or gets access to real
   Prusa hardware.
7. **WO-9 — Physical acceptance for Panda Breath (P31), Spoolman (P13) and
   multiACE (P30).** All three built 2026-09-20, unit-tested against Helix's
   exact logic, none physically verified — no owner hardware exists for any of
   them (a chamber-heater/dryer accessory, a Spoolman install, or PAXX
   multiACE). Same hardware-availability blocker as WO-7/WO-8; needs either the
   owner acquiring the hardware or a volunteer/beta tester who has it, per M7's
   own precedent for evidence from hardware the owner doesn't personally own.
8. **WO-10 — M5 optional AI monitoring.** Needs an architecture decision (rented-
   server vs. on-device inference) before any implementation starts, plus a
   labelled evaluation set with acceptance thresholds set in advance.
9. **WO-11 — M6 fleet/production workflows.** Queues/bed-cleared workflow,
   maintenance/cost trends, shared-library/server-slicing feasibility. No fixed
   start date; the roadmap sequences it after M5.

## Parked — no scope, no target milestone

- **MakerWorld model browser** — folded into the same later phase as slicing,
  since it's a model-import source feeding it rather than standalone.

## Greenlit, not yet planned

10. **WO-13 — On-device slicing, owner-approved 2026-09-21.** Supersedes the
    earlier Parked entry: the owner rejected vendoring the third-party Snapmaker
    `u1-slicer-for-android` binary (unverified build provenance — no CI-built
    artifact, prebuilt `.so` committed directly to that repo, self-described as
    AI-"vibe"-coded) after a UX-council-adjacent investigation confirmed it also
    only covers the Snapmaker U1 and a Bambu beta, not the full required fleet.
    Instead greenlit: **cross-compile upstream OrcaSlicer (`OrcaSlicer/OrcaSlicer`)
    for Android ourselves**, built and attested in our own CI, because it's the
    only option with confirmed first-party support for the entire required fleet
    — Snapmaker U1 (actively maintained upstream profile), Bambu and Prusa
    (OrcaSlicer's own founding lineage), generic Klipper (explicit official
    network integration), and Elegoo Centauri Carbon (official since OrcaSlicer
    2.3.0). No existing Android port of this engine exists to adopt — the
    Snapmaker project is the only prior art, and it's narrower than what's
    needed here, so this is genuinely new native cross-compilation work (Boost/
    TBB/CGAL and friends for arm64-v8a), not a "vendor a binary" task.
    **Real added requirement surfaced during scoping, verified against
    OpenCentauri's own docs (`docs.opencentauri.cc/klipper-conversion/cosmos/
    install/`):** the Elegoo Centauri Carbon isn't one slicing target, it's
    three, and getting it wrong is not just a bad print —
    OpenCentauri-patched-firmware-vs-COSMOS start/end G-code are incompatible,
    and from COSMOS 26.07.0 onward, printing with the wrong profile's G-code
    (`M729`/`M8213`) **triggers a hard emergency stop mid-print** on real
    hardware. So `PrinterProfile` needs a firmware-identity field (family +
    version, at minimum for Centauri Carbon's Stock/OpenCentauri-patched/COSMOS
    split), and print-generation must refuse to slice against a stale/mismatched
    profile rather than silently emitting G-code that can fault the printer.
    **Phase 0 planned 2026-09-21 (Plan Mode), then re-scoped mid-implementation
    after a real feasibility finding:** upstream OrcaSlicer's dependency build
    (`deps/CMakeLists.txt`) has zero Android awareness — it's ~20 `ExternalProject`
    sub-builds (Boost 1.84 w/ Context, CGAL, GMP, MPFR, TBB, OpenVDB, OCCT, Qhull)
    written only for Linux/macOS/Windows. Checked how the Snapmaker project (the
    only real prior art) actually solved this: their `orcaslicer` submodule points
    to **their own fork** (`taylormadearmy/OrcaSlicer`, not vanilla upstream) with
    real Android source patches, and their 1,150-line native `CMakeLists.txt`
    **disables or stubs out** the hardest dependencies entirely rather than
    cross-compiling them (`SLIC3R_OPENVDB=0`, `extern/*_stub` headers for OpenVDB/
    OpenCV/FreeType/OpenSSL/etc.; no CGAL/GMP/MPFR/OCCT anywhere) — meaning no
    OpenVDB-based operations or CGAL/OCCT-based mesh booleans/STEP import exist in
    their Android build at all. Whatever they *do* cross-compile (Boost/TBB) comes
    from an `extern/` directory their own CMake comments describe as an
    **"ignored" (not committed), prebuilt Android dependency bundle** — i.e. even
    working prior art doesn't have a fully reproducible from-source build of its
    own hard dependencies; it's an unattested blob one layer deeper than the app
    binary that was already rejected.
    **Owner decision (2026-09-21), presented as an explicit trade-off:** proceed
    with a feature-reduced engine — OpenVDB/CGAL/OCCT-dependent features (advanced
    supports, mesh booleans, STEP import) disabled, same functional cut the
    Snapmaker team made — but build the *entire* remaining dependency chain,
    including Boost and TBB, from source ourselves, with no unattested prebuilt
    bundle anywhere in the pipeline. Source base is vanilla upstream
    `OrcaSlicer/OrcaSlicer` (not the Snapmaker fork), since we're doing our own
    Android portability patches rather than inheriting theirs. This is genuinely
    multi-session native engineering work; status and concrete next steps are
    tracked in the Phase 0 plan file rather than restated here on every update.
    **First real milestone landed, same day:** `third_party/orcaslicer` (pinned
    `v2.4.2`) and `third_party/onetbb` (pinned `v2021.13.0`) vendored as git
    submodules, not binaries — their exact commits are now part of
    `scripts/artifact-proof.py`'s embedded source-proof manifest, the concrete
    mechanism answering the provenance gap. `app/src/main/cpp/CMakeLists.txt`
    cross-compiles oneTBB for arm64-v8a via the real Gradle `externalNativeBuild`
    pipeline (NDK 27.1.12297006, `TBBMALLOC_BUILD=OFF` — `tbbmalloc` doesn't
    link cleanly against Android's bionic libc and isn't needed) and links a
    minimal JNI smoke bridge (`NativeSlicer.kt`/`nozzle_slicer_jni.cpp`)
    against it. **Device-verified 2026-09-21**: `NativeSlicerSmokeTest`
    (a real `oneapi::tbb::info::default_concurrency()` call through JNI, not a
    stub) passed on the Razr 2023 via `connectedDebugAndroidTest`; the APK
    genuinely contains `libnozzle_slicer.so` and `libtbb_debug.so` as real ELF
    aarch64 objects. Full existing gate (`testDebugUnitTest lintDebug
    assembleDebug`) still green.
    **Superseded same day: the owner had already independently built and
    verified a full-featured OrcaSlicer Android engine in a separate project
    (`orcaslicer-android-engine`, local to this machine).** Verified
    independently before relying on it: found leftover test artifacts on the
    same Razr 2023 device, pulled and inspected the real 13,370-line G-code
    output, then re-ran the standalone CLI tool live myself with a byte-
    identical result. That engine cross-compiles the *entire* dependency
    chain from source — Boost, CGAL, GMP, MPFR, OpenVDB, OCCT — no feature
    cut, superseding this project's own from-scratch reduced-scope attempt
    above. Integrated into Nozzle It All: `app/src/main/cpp/CMakeLists.txt`
    now builds directly against that project's patched OrcaSlicer checkout
    and prebuilt dependency prefix (reusing already-built work rather than
    repeating a ~2.5 hour dependency build); `app/src/main/cpp/bridge/` is
    the copied, working JNI bridge (`slic3r_engine.cpp/hpp`, `slic3r_jni.cpp`,
    with one local addition — direct config-override forwarding, needed to
    match the CLI tool's proven call pattern). `minSdk` raised 26→28
    (Boost.Locale needs `iconv()`, `__INTRODUCED_IN(28)`). See
    `THIRD_PARTY_NOTICES.md`'s OrcaSlicer entry for full detail including a
    real bug hit and fixed during integration (a Debug-vs-Release native
    build-type difference tripping a stale upstream placeholder-validation
    table — not an Android-specific issue). **Device-verified 2026-09-21**:
    `NativeEngineSmokeTest.realEngineSlicesRealGeometry` — the *real* engine,
    not the TBB-only smoke test above — slices a real bundled STL fixture and
    asserts real G-code content, passing cleanly on the Razr 2023 (confirmed
    via the JUnit XML report, no `<failure>` element). Full existing gate
    still green.
    **Overnight session, 2026-09-21 into 2026-09-22, owner-authorized unattended
    work (explicit scope: firmware-identity model, slicing UI, print-start
    pipeline wiring, real profile packs — not physical print-to-completion,
    which stayed gated behind human confirmation throughout, same as every
    other mutating command in this app):**
    - **Phase 1 (firmware identity), commit `afdb3ee`.** `FirmwareIdentity.kt`
      (pure logic: classifies a live `printer/info` reading as COSMOS or not,
      parses COSMOS version strings, decides profile-generation match —
      unparseable/unreadable never resolves to "safe"). `Moonraker.firmwareIdentity()`
      reads `printer/info` live. `PrinterProfile` gains `slicingModel` and
      `declaredFirmwareVersion` (set only by a live read, never guessed).
      Verified against the real CC1 at 192.168.1.x: confirmed live and
      running `"OpenCentauri Cosmos"` / `"Release - 26.08.0"` — genuinely past
      the 26.07.0 e-stop threshold, not a hypothetical.
    - **Phase 4 (real profile packs), commit `bd5e6fe`.** Five bundled
      machine/process/filament profile packs, each flattened offline from
      upstream OrcaSlicer's own bundled profiles by resolving their real
      `inherits` chains. The Centauri Carbon/COSMOS pack incorporates the
      actual OpenCentauri-endorsed COSMOS overlay (`cloud.orcaslicer.com/b/3fad3c38f25f`,
      linked from OpenCentauri's own docs and GitHub org) — the real fix for
      the e-stop risk, not a guess. Device-verified: all 5 packs produce real
      G-code through the real engine; the Centauri Carbon one specifically
      confirmed to contain COSMOS's `PRINT_START`/`PRINT_END` macros and *not*
      contain the dangerous old `M729`/`M8213` sequence.
    - **Phases 2/3 (slicing UI + print-start pipeline wiring), commit pending.**
      `SlicingCoordinator.slice()` ties Phase 1's live firmware check to Phase
      4's profile packs to the real engine call. `SliceAndPrintPanel` extends
      the existing share-intent flow (STL/3MF/OBJ now route here instead of
      the plain-import dialog) through slicing and upload, staging the actual
      print-start into the same `pending`/confirm-dialog machinery every other
      mutating command already uses — nothing was auto-printed, tonight or
      ever, from this flow. `ProfileEditor` gained a slicing-profile picker and
      a live "Detect firmware now" action wired to `PrinterModel.detectFirmware`.
      **A real, safety-relevant bug was found and fixed before it ever reached
      device testing**: `checkCentauriCarbonFirmwareMatch`'s `null`-generation
      shortcut (meant for "this call site isn't about a Centauri Carbon profile
      at all") would have silently treated "never confirmed" the same as
      "nothing to check" if reused naively inside `SlicingCoordinator` — fixed
      by an explicit guard before that function is ever called from here. **All
      5 tests in `SlicingCoordinatorDeviceTest` passed against the real U1 and
      real CC1**: a profile with no slicing model rejected before touching the
      network; the U1 slicing real G-code with no firmware check applying;
      a Centauri Carbon profile with *no* declared firmware correctly blocked
      even though the real printer was reachable (the bug above, caught before
      shipping); the correct declared generation (matching the live printer's
      actual `26.08.0`) slicing real, verified-safe COSMOS G-code against the
      real device; the wrong declared generation correctly blocked.
    - **Real, honest gap found during this work, not glossed over: Bambu Lab
      slicing is not wired up.** `Print::export_gcode()` produces plain
      `.gcode`; `BambuPrintRequest`/`bambuPrintName()` require a `.gcode.3mf`
      zip bundle (OrcaSlicer's separate `bbl_3mf` export path, not built).
      `SliceAndPrintPanel` says so plainly rather than attempting something
      that would fail `bambuPrintName`'s own validation.
    - **Still genuinely open:** no printer-picker UI (slicing always targets
      whichever printer is currently selected, matching `BambuPrintPanel`'s
      own existing convention, not a new picker); Bambu wiring (above); the
      `CosmosProfileGeneration.LEGACY` profile pack still doesn't exist;
      `SliceAndPrintPanel` itself has no Compose UI test yet, only its
      underlying `SlicingCoordinator` is device-tested; and no physical
      print has actually been carried through to completion on real hardware
      — every device test tonight stopped at "real G-code produced/blocked,"
      deliberately never at "confirmed and sent." That confirmation step
      needs the owner physically present, same as every other hardware
      acceptance pass in this project.
    - **Follow-up, same morning: `AddPrinterWizard`, commits pending.** The
      owner checked the app live and found two real gaps in what shipped
      overnight: the Prepare tab still showed its old placeholder (slicing
      was only reachable via share-intent, never wired into the tab that
      already existed for it — fixed by adding a "Pick a model to slice"
      entry point there, same `SliceAndPrintPanel`); and the Edit printer
      dialog's new slicing-profile/firmware section was unreachable because
      the dialog's content `Column` had no scroll modifier (fixed, same
      pattern already used elsewhere in this codebase, e.g. `HeaterPanel.kt`).
      A live slice attempt against the real CC1 then surfaced two more real
      issues: a firmware-read failure showed only a generic message with the
      actual exception swallowed (confirmed via `curl` that the printer was
      fully reachable a minute later — a transient blip made undiagnosable
      by the vague message; fixed to include the real exception), and manual
      "Detect firmware now" was an easy step to forget (fixed: runs
      automatically via `LaunchedEffect` as soon as a printer's slicing
      profile is set to Centauri Carbon).
      The owner then asked, twice, for printer setup to go through a proper
      wizard instead of the old single "type an address, tap Connect" flow
      (which created a bare, unconfigured `PrinterProfile` via
      `PrinterModel.connect()`'s own implicit fallback, with everything else
      requiring a separate trip to Edit printer). Scoped explicitly via
      `AskUserQuestion` before building: `AddPrinterWizard.kt` is a 4-step
      guided flow (type/address/credentials → slicing profile → firmware
      confirmation, Centauri-Carbon-only → a live connectivity test that
      must pass before Finish is enabled), replacing the old flow entirely.
      A new `PrinterModel.addProfile()` is the wizard's single commit step;
      every live check before that (firmware read, connectivity test) builds
      its own short-lived `PrinterService` directly, the same ad hoc pattern
      `SlicingCoordinator`/`NozzlePrinterWidget` already use, since the
      profile doesn't exist in `PrinterModel`'s own state until Finish.
      **Device-verified**: `AddPrinterWizardDeviceTest`'s full happy path —
      type the real U1's address, advance through slicing profile (none),
      let the live connectivity test resolve against the real printer, Finish
      — passed for real against `192.168.1.x`, confirming `addProfile` and
      `openPrinter` both fire with the correct committed profile. Full
      existing gate still green after every fix in this follow-up.
    - **Follow-up, same day: slice customization + two real bugs caught by an
      actual failed print on the CC1, commits pending.** Added a focused,
      deliberately bounded customization step to `SliceAndPrintPanel` (layer
      height, infill %, supports on/off — `SliceCustomization.kt`; not
      OrcaSlicer's full settings surface) reviewed before slicing starts,
      passed through `SlicingCoordinator.slice()`'s new `overrides` param into
      the same config-override mechanism already used for the
      `use_relative_e_distances` fix.
      **The owner then tried a real print of `squatchee_spin_mount` against
      the CC1 and only the purge line came out.** Pulled the real job history
      and `gcode_store` off the printer directly (Moonraker's own API, not a
      guess): status `cancelled` after 14s of real print time, with Klipper's
      own log showing `!! Move out of range: 6.875 -2.769 0.485` right after
      the KAMP purge routine. **Root cause**: `slic3r_engine.cpp`'s
      `slice_file()` called `ModelObject::ensure_on_bed()` (Z only) but never
      centered the model in X/Y — `Model::read_from_file()` places instances
      at the mesh's own local origin, not the bed's real coordinates, so a
      part not already centered on its own local (0,0) ends up sliced partly
      off-bed. Fixed with `model.center_instances_around_point(bed center)`
      (via `Slic3r::get_bed_shape(config)`), mirroring what OrcaSlicer's own
      GUI always does on import — this headless path had just never done it.
      **Verified two ways**: rebuilt the on-device `slic3r_cli_test` tool
      (already used earlier for CLI verification) and reran it on the Razr —
      the same cube fixture that would previously slice wherever its raw mesh
      coordinates put it now lands exactly straddling the bed center; and two
      new tests in `SlicingCoordinatorDeviceTest` (`slicedGcodeStaysWithinThe
      ConfiguredBedBounds`, parsing the real output's own `bed_shape` comment
      and every `G1 X/Y` move) passed for real against the U1.
      **Separately, the owner also asked for G-code thumbnails** (so a job
      shows a picture on the printer's own screen, not just a filename) —
      confirmed via the same job-history pull that our sliced files had no
      `thumbnails` metadata at all, unlike every desktop-sliced file already
      on that printer. Cause: `slice_file()` passed a null `thumbnail_cb` to
      `Print::export_gcode()`, even though the engine's own default
      `thumbnails` config value already requests 48x48 and 300x300 PNGs — the
      GUI normally supplies this callback by rendering its live OpenGL scene,
      which doesn't exist headless (`SLIC3R_GUI=OFF`). Added
      `thumbnail_render.cpp` — a small, from-scratch software rasterizer
      (isometric projection, per-triangle flat shading, a real z-buffer for
      hidden-surface removal) of the model's own sliced mesh, using
      libslic3r's existing (non-GUI) PNG encoding and G-code embedding
      machinery. **Verified for real**: decoded the actual embedded PNG
      output from a real device slice and visually confirmed a correctly
      shaded, recognizable isometric cube; a new
      `slicedGcodeEmbedsRealThumbnails` device test (checks for real
      `; thumbnail begin 48x48` / `300x300` blocks) passed against the U1.
      **Also fixed in passing, reported live by the owner**: the
      "Printer acknowledged Start …" command notice
      (`ScreenState.commandNotice`) had no owner to ever clear it — it just
      sat pinned over the bottom nav until some later command happened to
      overwrite it. `PrinterModel.dismissCommandNotice()` plus an explicit
      Dismiss action and a 6s auto-dismiss timer in `MainActivity`'s
      `snackbarHost` fix that.
      Full existing gate (`testDebugUnitTest lintDebug assembleDebug`) green;
      `SlicingCoordinatorDeviceTest`'s full 7-test suite passed against the
      real U1. **Still open**: no physical print has been carried through to
      completion since these fixes — that confirmation needs the owner
      physically present, same as every other hardware acceptance pass in
      this project.
    - **Follow-up, same day: automatic first-run setup, commit `3bac316`.**
      Repeated data wipes during this session's own device testing kept
      landing the app back on a bare "No printers connected" dashboard with
      no obvious path back to `AddPrinterWizard`, prompting the owner to ask
      for a real first-run setup flow. `MainActivity` now opens the wizard
      automatically the moment there's no saved profile and no active
      address (gated on both, and only once per install via
      `rememberSaveable`, so it never traps a user who cancels or later
      forgets every printer on purpose). While wiring this up, found real
      pre-existing breakage in `AddPrinterWizardDeviceTest`'s sibling tests
      from the original `AddPrinterWizard` migration itself: `openFixtureDashboard`'s
      offline path and two `CompanionScreenTest` assertions still referenced
      a `"connect-printer"` testTag the old single-field flow left behind,
      which no longer exists anywhere in the app — confirmed via `git stash`
      to already be broken on HEAD, unrelated to this change. Fixed properly:
      the offline path now goes through the real `saved-connect:$address`
      button; the compact/large-text reachability check now targets
      `open-add-printer-wizard`; `LivePrinterReadOnlyTest`'s live opt-in flow
      now drives the actual wizard. **Verified for real, not just in a test
      harness**: manually drove the wizard via `adb` on the real test device
      (a fresh install with zero saved printers) and added the actual CC1
      end to end — live firmware auto-detected as `Release - 26.08.0`, live
      connectivity test passed, landed on its real connected dashboard with
      live camera and temperatures. All 19
      `CompanionScreenTest`/`PrinterTilesDeviceTest`/`M2ShareIntentTest`/
      `AddPrinterWizardDeviceTest` cases and the 7 `SlicingCoordinatorDeviceTest`
      cases pass; full gate green.
- **LAN/Tailscale automatic URL failover (P16 addendum)** — Helix keeps both a LAN
  and a Tailscale URL per printer and alternates on a 6s connect timeout; our
  profiles are still single fixed addresses. Real resilience gap, not yet scoped.
- **Wear OS** — reopened as a live option but not yet scoped (glance-only vs.
  actions-from-the-watch) or given test hardware.
- **The four 2026-09-16 reference-pass ideas** — community model import, mid-print
  object exclusion, solo phone-initiated slicing, input-shaper/resonance
  calibration. Listed for visibility only; none has a backlog ID yet.

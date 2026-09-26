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

1. **WO-43 — Bundle the whole OrcaSlicer 0.4 mm printer library; searchable model picker (2026-09-26).**
   Requested for testers who own many models, then widened to a comprehensive list. `scripts/bundle_vendor_profiles.py`
   flattens 371 more models across 60 vendors (Bambu, Prusa, Creality, Anycubic, Qidi, Sovol, Voron, Ratrig, Artillery,
   Flashforge, ...) and generates `SlicingModelCatalog.kt` (the `SlicingPrinterModel` enum, `SlicingVendor`, one catalog
   row per model). 377 models total, 9 MB of JSON (compresses well in the APK). The chip rows in Add printer and Edit
   printer are replaced by `SlicingModelPicker` (search, collapsible vendor sections, "not yet confirmed" note).
   Existing enum names and packs are untouched, so saved printers keep their profile. Slicing works for every model;
   **sending only works over the four protocols the app speaks** (Moonraker, OctoPrint, PrusaLink, Bambu LAN).
   Real fixes the wider library forced: `parseMachineLimits`/`parseFilamentTemperatureRange` now accept the string
   forms some vendors write ("0.08,0.08"); the generator never uses a filament without a temperature range;
   `multiToolFamily` treats only Snapmaker U1 and Prusa XL as independent-tool machines (any other multi-material
   printer is a filament swap; a Prusa MK4 + MMU was wrongly a toolchanger). `SlicingModelCatalogTest` plus the
   updated `MultiMaterialTest`/`SliceValidationTest` cover it (619 unit tests, 0 failures; lint clean; androidTest
   compiles). `everyBundledCatalogModelSlicesRealGcode` in `SlicingProfilePacksDeviceTest` slices a cube with every
   pack through the real engine: **needs a Device Farm run (estimate and approval first).** Not done: preselecting
   the model from the printer (Bambu SSDP `DevModel` codes must come from real reports), and control/file-send for
   the newer Bambu models remain untested.
   **First real-device run (Pixel 9a, Android 15, 2.08 device-minutes, 2026-09-26):** 354 of 371 new profiles sliced;
   17 were rejected by OrcaSlicer's own validation. 16 (13 Bambu models, Anycubic Kobra Max/Plus and Vyper) lacked a real
   uncommented `G92 E0` at layer change, which Orca requires for Marlin-flavoured non-Bambu printers using relative
   extrusion (Orca's GUI marks Bambu printers via its preset bundle, which our headless bridge lacks); one (Creality
   Sermoon M300) defaulted to a bed plate its filament zeroes out. The generator now adds the reset where missing and
   picks a supported plate; `SlicingModelCatalogTest` enforces both rules on every pack. A second device run then
   passed all but one: **Bambu Lab A2L** uses G-code template variables this engine build does not define
   (`bed_heat_stable_wait_flag`, `hotend_heating_rate`, `temperature_vitrification`, ...) and fails with "Failed to
   generate G-code for invalid custom G-code", so it is excluded (`ENGINE_INCOMPATIBLE` in the generator) until the
   engine is updated. The catalog is 376 models. (Correction to the count above: my first read of the failure list stopped
   at a blank line inside one error message, so the run rejected more than the 17 it showed - the two Dremel models
   below were hidden behind it.) Third run: Dremel 3D40/3D45 set absolute extrusion yet carry a `G92 E0` line, which
   Orca forbids in that mode; the generator drops it (`fix_absolute_reset`) and a unit test enforces the rule.
1. **WO-42 — Split Snapmaker U1 into stock and PAXX printer types (2026-09-26).**
   `PrinterKind.SNAPMAKER_U1` (stock: Bespok3d, no multiACE, reported unverified) and
   `SNAPMAKER_U1_PAXX` (multiACE, no Bespok3d). Saved PAXX printers are unaffected (kind is stored
   by name). Network discovery now defaults a U1 to stock because Moonraker cannot tell the two
   apart; a PAXX owner switches the type. Chips added to Add printer and Edit printer; unit tests
   (607 total, 0 failures), lint and the androidTest compile pass. **Not yet run on a device:** the
   two new/updated Compose tests in `PrinterCapabilitiesDeviceTest` and `PrinterScanDeviceTest`
   need a Device Farm or connected run (state the minutes and get approval first).
1. **WO-4 — Device-verify Bespok3d enrollment + remote screen (M8b).** Code and
   unit tests are done. **Blocked by design on the owner's printer (2026-09-26):**
   Bespok3d targets *stock* Snapmaker U1 firmware ("no flashing"), and the
   enrollment preflight (`Bespok3dU1Preflight`) refuses extended/PAXX firmware; the
   owner's U1 runs PAXX, so the daemon (port 4269) is not there and enrollment is
   rejected. Earlier text assumed PAXX worked; it does not. Verification needs a
   stock-firmware U1, or upstream confirmation that PAXX is supported (ask
   Bespok3d/adapters and the paxx12 project). Do not bypass the preflight.
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
11. **WO-14 — Control tab UX redesign, owner-requested 2026-09-22.** The tab had
    grown to 13+ visually identical full-width `OutlinedButton` pills stacked in
    one `LazyColumn`, plus a flat, ungrouped macro list — the owner's own words:
    "one long page of pills and macros." Consulted a locally-installed multi-
    reviewer tool (`council`, an executable at `~/.local/bin/council`, not a
    Claude skill — confirmed only after guessing wrong first) with a sanitized
    task package (redacted screenshots, the real Control tab source, explicit
    requirements to preserve every `testTag` and the Emergency Stop's safety
    placement): 4 real reviewer seats (`council-ux-product`, `council-frontend`,
    `council-delivery`, `council-design`; ~$0.105 total real cost) unanimously
    flagged the ungrouped pill stack and the ungrouped macro list; 3 of 4
    endorsed keeping Emergency Stop isolated-by-position, one (`council-design`)
    argued color alone wasn't enough shape differentiation.
    **Shipped, commits `3bac316`/`3ad1b80`:**
    - Buttons regrouped into "Hardware controls" (`FilledTonalButton`, tiered
      above secondary controls) and "Diagnostics & status" (`OutlinedButton`),
      both in `FlowRow`s instead of one-per-row — every existing `testTag`
      unchanged, every conditional-visibility gate (`LIVE_HEATER_FAN_CONTROLS_ENABLED`,
      `nonKlipper`, `SNAPMAKER_U1_PAXX`) unchanged.
    - Emergency Stop resolved the one reviewer disagreement with the owner's
      own idea: a real stop-sign octagon (`StopOctagonShape`, a `GenericShape`
      with the corner-cut fraction that makes all eight edges equal), moved
      first in the tab per the owner's explicit "easily accessible, but can't
      be accidentally tapped" - accidental-tap protection now comes from shape
      + isolating padding + the pre-existing confirm dialog, not from being
      buried after a scroll.
    - Owner follow-up, same session: "only the absolutely necessary macros
      should be included." This app has no reliable way to guess which macros
      are essential — arbitrary per-printer Klipper config — so it doesn't try.
      New `MacrosBrowserPanel.kt` ("Advanced macros") holds the full inventory
      (search, hidden, group, organize, run — moved verbatim, nothing deleted
      or altered), with an inline notice (not another popup) explaining the
      split. The Control tab itself now shows only a "Favorite macros" section,
      omitted entirely (not an empty placeholder) until something is favorited
      in the browser. Command review/confirmation (`MacroForm` → `MacroReviewPanel`)
      unchanged.
    - **Real, separate bug found and root-caused during this work**: the owner
      reported repeatedly finding the app missing from the real test device
      after device-test runs, and a prior "it's installed" claim in this
      session turned out to be wrong when actually checked. Root-caused via
      logcat, not guessed: AGP's `connectedDebugAndroidTest` uninstalls both
      APKs once a run finishes unless told not to (`deletePackageX` for both
      packages right after "finished inst"). Fixed in `gradle.properties`
      (`android.injected.androidTest.leaveApksInstalledAfterRun` — the real
      property name, found by grepping the actual AGP 8.13.2 jar after a first
      guess at the name silently did nothing). Verified twice with
      `dumpsys package` after a real test run.
    - **Verified for real against the CC1**: favorited a macro in the new
      browser, confirmed it then shows in its own Control-tab section and
      nowhere else; the stop-sign octagon and grouped sections render
      correctly live; the confirm dialog still gates the actual estop command.
      New `CompanionScreenTest` cases (auto-wizard, macro scoping) plus the
      existing 29+ device tests across `CompanionScreenTest`,
      `ControlPreviewDeviceTest`, `ConsoleDeviceTest`, `PrinterTilesDeviceTest`,
      `MacroReviewPanelDeviceTest` and `MacroBoundsDeviceTest` all pass; full
      `testDebugUnitTest`/`lintDebug`/`assembleDebug` gate green.
12. **WO-15 — Full visual, on-device, in-app slicer, owner-requested 2026-09-22.**
    WO-13 shipped headless on-device slicing (numeric fields, no preview). Owner:
    "When I said on-device slicer, I meant I want a visual, on-device, in-app
    slicer" — then, after a description pass and confirmation, supplied real
    Prusa EasyPrint screenshots and, asked whether to scope this into a smaller
    increment, said directly: "No I want it all now." Scope: (A) tabbed slicer
    chrome, (B) a real 3D pre-slice model view, (C) a real post-slice 3D
    toolpath preview with real G-code stats and a print-ready confirmation, and
    (D) manual support-region painting on the model, matching EasyPrint's
    Select/Paint toolbar. Planned in Plan Mode against the real vendored
    libslic3r source (not guessed), approved, and built in that order because
    (D)'s touch-to-ray math depends on (B)'s camera/projection code existing
    first.
    **Shipped:**
    - **(A) Tabbed chrome** — `SliceAndPrintPanel.kt` rebuilt from a single
      scrolling `AlertDialog` into a full-screen Model/Settings/Printer tabbed
      flow (a paint brush needs real screen space and precise touch handling
      an `AlertDialog`'s bounds fight against). Every existing `testTag`
      (`slice-layer-height`, `slice-infill`, `slice-supports`,
      `slice-customize-next`) kept exactly as-is.
    - **(B) Pre-slice 3D model view** — `ModelViewer.kt`: raw GLES30
      (`GLSurfaceView`, not Filament/Sceneform — see the file's own header
      comment), a new native `engine::load_mesh_preview()` (shared
      `load_and_place_model()` helper factored out of `slice_file()`), real
      orbit/zoom camera, flat Lambertian shading matching the engine's own
      thumbnail renderer for visual consistency. Device-verified live against
      a real model on the real CC1 before the scope grew to (C)/(D).
    - **(C) Post-slice 3D toolpath preview** — new `SlicedPreview.kt`
      (`ToolpathGLRenderer`, sharing GL helpers factored into `GLSupport.kt`
      rather than duplicated), a layer slider over `GcodePreview`'s existing
      parsed `Toolpath`, and real stats — not invented — parsed by new
      `GcodeStats.kt` from the engine's actual G-code footer comments
      (verified against real sliced output before writing the regexes, e.g.
      `; estimated printing time (normal mode) = 14m 35s`). A lightweight
      "is the bed clear?" confirmation `AlertDialog` was inserted before the
      existing, unchanged final print-start confirm. Live bug found by the
      owner via screenshot ("The slice button is blocked by the navigation
      keys") and fixed (`.statusBarsPadding().navigationBarsPadding()`).
    - **(D) Support painting** — the highest-risk piece, since a wrong
      implementation risks bad real prints, so it was built against real,
      already-existing libslic3r machinery the real upstream GUI itself uses
      (`AABBMesh` for ray-mesh hit testing, `TriangleSelector::select_patch`
      for the actual brush algorithm, `ModelVolume::supported_facets` for
      persistence that `print.apply()` already consumes with zero new
      slicing-side wiring) — traced against the real vendored GUI source
      (`GLGizmoPainterBase.cpp`), not guessed, including the local-vs-world
      transform handling it depends on for correctness. New stateful native
      paint-session surface (`open_paint_session`/`paint_stroke`/
      `get_painted_facets`/`slice_paint_session`/`close_paint_session`) in
      `slic3r_engine.cpp`/`slic3r_jni.cpp`, each session behind its own
      `std::mutex` (added proactively — Kotlin-side call ordering is only
      defense in depth against a real race, not the actual guarantee) plus a
      global map mutex; `close_paint_session` extracts-then-locks-then-destroys
      so an in-flight stroke can never use-after-free. Kotlin side
      (`ModelViewer.kt`): touch-to-world-ray unprojection through the same
      view/projection matrices the camera already builds each frame, a
      dedicated single-threaded dispatcher to serialize stroke dispatch in
      gesture order, a translucent overlay pass re-rendering the currently
      painted facets each stroke for real-time feedback, and a Select/Paint
      mode toggle plus Enforcer/Blocker toggle matching the reference
      screenshots. `SlicingCoordinator.slice()`/`SliceAndPrintPanel.kt` route
      through the new `nativeSlicePaintSession` (reusing the session's already-
      painted in-memory model) instead of `nativeSliceFile` only when a real
      stroke was painted — opening Paint mode without painting anything must
      not itself change what gets sliced.
      **Real bug caught before any device test, by re-reading the wiring**:
      the paint session's native handle was being closed in a
      `DisposableEffect` scoped to `ModelViewer` itself, which unmounts once
      the flow moves from "customizing" to "slicing" — closing the session
      before `SlicingCoordinator.slice()`'s own, later-running effect could
      use it. Fixed by moving that `DisposableEffect` up to
      `SliceAndPrintPanel`, keyed on the shared document `uri` so it lives for
      the whole panel, not just the Model tab.
    - **Verified for real**: a purpose-built `overhang.stl` fixture (a genuine
      downward-facing overhang — libslic3r's `project_and_append_custom_facets`
      only ever projects *downward*-facing painted areas, so painting a flat
      top face is a structural no-op; found by reading `PrintObject.cpp`, not
      assumed) plus `support_type=normal(manual)` (disables auto overhang
      detection, isolating painting as the only variable) proves in
      `PaintSessionDeviceTest.paintedSupportsActuallyReachTheSlicedGcode` that
      a painted stroke changes the actual sliced G-code, not just that the JNI
      call didn't throw. All of `PaintSessionDeviceTest` (4 tests),
      `MeshPreviewDeviceTest` (3 tests) and the full existing
      `CompanionScreenTest`/`PrinterTilesDeviceTest`/`LivePrinterReadOnlyTest`/
      `SlicingCoordinatorMainThreadDeviceTest` regression set (20 tests, 1
      expected live-only skip) pass on the real CC1/real test device after the
      per-session-mutex hardening and the lifecycle fix above; full
      `testDebugUnitTest`/`lintDebug`/`assembleDebug` gate green. **Not yet
      done**: live, on-device visual confirmation of the paint overlay/brush
      UI itself through the real touchscreen (the native+Kotlin wiring is
      device-test-verified; the on-screen paint experience has not yet been
      eyeballed live).
    - **Scope widened, owner-requested 2026-09-22 (same day, later)**: a
      detailed follow-up spec asked for full EasyPrint-class mobile parity —
      multi-object scenes/arrange, real move/rotate/scale, printer/material
      selection, multicolor, job-status surfacing, offline export, project
      persistence, tablet layouts — "I want it all", implemented for real, no
      mocks. A real audit (file:line evidence, not impressions) found most of
      that genuinely absent, not just unwired: no object-transform code at
      all (the viewer only orbited the *camera*), no multi-object/scene
      concept, zero multicolor/material-profile code anywhere in the repo,
      no offline/export path, no persistence of in-progress slicer state, no
      tablet-responsive layout code. Told the owner directly this is many
      more real sessions of work, not one pass, and asked which piece to
      build first rather than spreading thin across all of it at once (the
      surest way to end up with exactly the shallow/dead UI the owner
      explicitly ruled out). Owner picked object transform first.
    - **(E) Object transform — move/rotate/scale, real, wired into slicing.**
      v1 scope: position on the bed plane (X/Y), rotation about Z only
      (turntable — arbitrary/place-on-face rotation and per-object
      duplicate/arrange are still open, real gaps, not hidden), and uniform
      scale. Not a preview-only overlay: the exact same numbers reach a real
      libslic3r `ModelInstance` transform (`set_offset`/`set_rotation`/
      `set_scaling_factor`) inside `engine::load_and_place_model()` — the one
      function every slice, mesh preview, and paint-session open already
      goes through — so what's on screen is what actually gets sliced.
      Real correctness detail traced against libslic3r, not assumed:
      `ModelInstance` rotate/scale pivot around the instance's own *local
      mesh origin*, not its bounding-box center, so `load_mesh_preview()` now
      also returns that real origin point (a 3-float header before the
      vertex buffer) and the live GL preview's model matrix (new `uModel`
      uniform, `ModelViewer.kt`) rotates/scales around that identical pivot —
      not a bounding-box approximation that would visibly disagree with the
      sliced result for any mesh whose local origin isn't its own centroid.
      New `ModelTransform.kt` (Kotlin) / `engine::ModelTransform` (native,
      `slic3r_engine.hpp`) carry the same 4 numbers through both sides. A
      new "Move / rotate / scale" mode (alongside the existing Select/Paint)
      drives one drag/pinch/twist gesture (`detectTransformGestures`) — pan
      unprojected through a real bed-plane ray/plane intersection (not a
      proportional screen-pixel guess), pinch → scale, twist → rotate Z —
      plus a live numeric readout and a Reset button. **Real interaction
      with WO-14 part D deliberately locked, not left to silently drift**: a
      paint session freezes whatever transform was current at
      `open_paint_session()` time (painting operates on the model's raw
      local mesh via a captured `trafo`), so the Transform control is
      disabled the moment a paint session opens — changing placement after
      painting starts would otherwise silently desync the paint overlay from
      what a further transform change would actually slice.
      **Verified**: `PaintSessionDeviceTest` (4), `MeshPreviewDeviceTest` (3,
      updated for the new mesh-preview origin header),
      `SlicingProfilePacksDeviceTest` and `NativeEngineSmokeTest` all pass on
      the real device against the new native transform code (identity
      transform for every pre-existing call site — unchanged default
      behavior). Full `testDebugUnitTest`/`lintDebug`/`assembleDebug` gate
      green. **Live on-screen confirmation done, 2026-09-22 (owner home,
      device unlocked on wifi)**: drove the real Model tab against
      `overhang.stl` via adb — the drag-to-move gesture updated the live
      readout and visibly moved the model along the real bed-plane
      ray/plane-intersected path; camera orbit in Select mode still works;
      switching to Paint mode visibly disabled the Transform chip and showed
      the "Placement is locked" notice; a paint stroke rendered real cyan
      overlay marks at the touch point. Not yet exercised live: the
      two-finger pinch/twist gestures specifically (`adb shell input` has no
      clean multi-touch primitive) — same gesture-detector code path as the
      confirmed pan, so lower risk, but still genuinely unverified live.
      Still open, not yet scoped: multi-object scenes, duplicate, and
      auto-arrange (real, absent gaps per the audit above).
    - **Real bug, owner screenshot, same day**: a wide/short model rendered
      floating with a large visible gap above the reference grid. Root cause:
      `buildGrid()` placed the grid at `center[2] - radius`, a bounding-
      *sphere* radius approximation, not the mesh's real lowest point — since
      that radius also grows with the model's XY footprint, anything wider
      than tall ends up with the grid well below its actual base.
      `MeshLoader.load()` was already computing the real `minZ` while
      scanning for the bounding box; it just wasn't kept. Fixed by adding it
      to `MeshGeometry` and using it directly as the grid's Z plane.
    - **Build-volume bounds checking (owner-picked next increment)**: real,
      not cosmetic. `BedShape.kt` reads the same real per-printer
      `machine.json` every slice already applies (`printable_area`/
      `printable_height`, `SlicingProfilePacks.kt`) — not an invented bed
      size — and `computeOutOfBounds()` (`ModelViewer.kt`) tests the model's
      live-transformed footprint corners against that real bed polygon (a
      real ray-casting point-in-polygon test) plus its live-transformed
      height against the bed's real max Z, using the identical rotate/
      scale/translate math as the GL preview and the native engine so the
      verdict agrees with what would actually be sliced. v1, honestly
      bounded: tests the axis-aligned footprint's 4 corners, not the mesh's
      real silhouette — a diagonal/irregular shape could still clip a bed
      edge between two corners without tripping it; documented in the code,
      not hidden. Out-of-bounds tints the model itself a warning red (not
      just a separate note easy to miss while actively dragging it) and
      disables the real "Slice" action until it fits — a functional gate,
      not just a visual one. `CosmosProfileGeneration.CURRENT` is used
      unconditionally for the bed-shape lookup (the only generation with a
      bundled pack right now, and bed *shape* doesn't differ by firmware
      generation for the same physical printer) — this is a placement aid,
      not a substitute for the real live-firmware safety gate
      `SlicingCoordinator.slice()` still enforces separately.
      **Verified**: new `BedShapeTest.kt` (6 real unit tests) parses every
      bundled `machine.json` this app actually ships (not a synthetic
      fixture) and exercises the point-in-polygon test's inside/outside/
      edge/no-real-polygon cases. Full `testDebugUnitTest`/`lintDebug`/
      `assembleDebug` gate green. **Live on-screen confirmation done,
      2026-09-22**: dragged `overhang.stl` (against the real U1 profile's
      bundled bed) well past its edge — the model tinted red, the exact
      warning text appeared, and the real "Slice" button visibly disabled;
      Reset placement brought it back to a valid, enabled state. Still open:
      real mesh-silhouette precision (still the v1 AABB-corner
      approximation).
13. **WO-16 — Phase 0 of the Consumer Slicer Plan, owner-approved 2026-09-22
    ("Let us begin Phase 0").** See `docs/CONSUMER_SLICER_PLAN.md` §16 for full
    scope/acceptance criteria. In progress; this entry covers what's landed so far.
    - **Native bridge, multi-object proof**: `engine::count_model_objects()`
      (`slic3r_engine.cpp/hpp`) / `nativeCountModelObjects`
      (`slic3r_jni.cpp`/`NativeEngine.kt`) loads a model the same real way
      `slice_file()`/`load_mesh_preview()` do and returns how many separate
      `ModelObject`s it actually contains — the specific proof Phase 0's
      acceptance criteria calls for, ahead of any UI using it yet.
    - **Real bug found and fixed while building the above**: every `.3mf` file
      tried — a real OrcaSlicer-native multi-object calibration fixture, a
      real single-object fixture, and a hand-crafted minimal spec-valid one —
      loaded with **zero objects** (`model.objects.empty()`), while `.stl`
      loading worked fine. Root cause: `load_and_place_model()` called
      `Model::read_from_file()` with its default `LoadStrategy`
      (`AddDefaultInstances` only), which does not include the separate
      `LoadModel` bit that `_BBS_3MF_Importer::_handle_start_item` gates all
      real object/instance creation behind — every `<build><item>` was
      silently accepted (no error) but never materialized. `.stl` loading
      never consults this flag at all, which is exactly why it always worked.
      Root-caused via temporary `__android_log_print` diagnostics added to the
      vendored `Model.cpp`/`Format/bbs_3mf.cpp` (reverted once identified,
      same discipline as WO-13's own `GCode.cpp` investigation): first proved
      the XML parse itself (every `<object>`/`<mesh>`/`<vertex>`/
      `<triangle>`/`<build>`/`<item>` element) was already 100% correct,
      before tracing the actual break to this one missing flag. Fix:
      `slic3r_engine.cpp` now explicitly passes
      `LoadStrategy::AddDefaultInstances | LoadStrategy::LoadModel`. This was
      a real gap in this app's own bridge code, not upstream OrcaSlicer — the
      desktop GUI's own file-open path always passed this flag. See
      `THIRD_PARTY_NOTICES.md`'s "Test fixtures" section for the full trace
      and the three fixtures (`multi_object.3mf`, `single_real.3mf`,
      `plain_two_objects.3mf`) that proved it was universal, not
      fixture-specific. **Verified**: `MultiObjectModelDeviceTest` (5 tests)
      green on 2 real devices after the fix, all previously red.
    - **Room persistence infrastructure**: `Project`/`ProjectObject` entities,
      `ProjectDao`, `AppDatabase` (`project/` package) — real, tested
      (`ProjectPersistenceDeviceTest`, 3 device tests: round-trip, cascade
      delete, Flow observation), deliberately empty/unused by any UI yet, per
      Phase 0's explicit scope. `ProjectObject` wraps the existing
      `ModelTransform` rather than a parallel representation.
    - **CI + self-hosted runner**: created the project's first GitHub-hosted
      repo (`James-Jennison/nozzle-it-all`, private — no GitHub remote existed
      before this) and pushed `codex/android-mvp`. Provisioned `gthost-build01`
      as a self-hosted GitHub Actions runner (owner-directed, an existing
      AMD EPYC box already used for other projects) — installed as a systemd
      service, with the exact pinned NDK (27.1.12297006) and CMake (3.22.1)
      matching `app/build.gradle.kts`'s own pins, plus ninja. Owner decision,
      §22 open question 5: self-hosted runner (not vendoring a prebuilt
      artifact) is how CI solves the `ORCASLICER_ENGINE_ROOT` native-build
      dependency. `orcaslicer-android-engine`'s patched source + prebuilt
      `deps/install/arm64-v8a` (~3.3 GB) rsynced to the runner.
      `app/build.gradle.kts` gained an additive, CI-only
      `ORCASLICER_ENGINE_ROOT` environment-variable override (local dev, no
      env var set, is completely unaffected) since the runner's path differs
      from this dev box's. `.github/workflows/ci.yml` runs unit tests, lint,
      and the full native build on the runner. **Not yet wired up**: AWS
      Device Farm instrumented-test coverage — needs AWS credentials and a
      real project/device-pool ARN as repo secrets, neither of which exist
      yet; tracked as a real, explicit gap in the workflow file itself, not
      silently skipped.
      **Real bug found and fixed getting the first CI run green**: the
      native build failed on the runner (`ninja: error: '.../deps/install/
      arm64-v8a/lib/libz.so' ... missing and no known rule to make it`) even
      though the file was really there — many of the rsynced prebuilt
      dependency's own CMake package-config files (`lib/cmake/boost_*/*.cmake`
      and others), `.pc` pkg-config files, and `.la` libtool archives have
      this dev box's absolute build path (`/mnt/faststorage/
      orcaslicer-android-engine/...`) baked in from when they were originally
      built here — a normal consequence of how CMake/autotools installs work,
      not something rsync could avoid. Patching every such file was rejected
      as fragile (breaks again on the next re-sync). Fix: symlinked
      `/mnt/faststorage/orcaslicer-android-engine` on the runner to the real
      rsynced location (`/home/jjennison/orcaslicer-android-engine`) — every
      baked-in absolute path resolves correctly through it, and it's this
      project's own `CMakeLists.txt`-default path besides, so the CI-only
      `ORCASLICER_ENGINE_ROOT` env var override stays correct as a second,
      redundant layer. **Verified: first fully green CI run, 2026-09-22**
      (`build-and-test`, run 35774884218) — real native engine build
      producing a real `app-debug.apk`, on the actual self-hosted runner, not
      just locally.
    - **Pre-existing device-variance flakiness noted, not fixed here**: a full
      `connectedDebugAndroidTest` run surfaced ~4-5 UI-test failures (e.g.
      `M2DeviceTest`, `DashboardDeviceTest`, `BedMeshPanelDeviceTest`,
      `ActiveFilenameDeviceTest`, `ConfigSavePanelDeviceTest` — the last only
      appeared in one of two isolated reruns, consistent with flakiness, not a
      stable failure) — all "scroll to/find node" assertions, all against
      screens this WO's actual changes (native bridge + Room, both scoped
      entirely away from these UI panels) never touch. Confirmed via isolated
      single-device reruns on both physical devices (`ANDROID_SERIAL`-scoped)
      that these reproduce independent of running both devices at once.
      `docs/HANDOFF_M4A_2026-09-17.md` already documents the Razr 2023, not
      the Razr 2026 (a foldable with a different screen), as "the established
      evidence device" — real prior art that this is a known device-coverage
      gap, exactly the class of problem this WO's own AWS Device Farm piece
      exists to eventually surface systematically. Not investigated further
      here; tracked so a future session doesn't mistake it for a regression
      from this WO's changes.
    - **AWS Device Farm wiring completed and verified with a real run,
      2026-09-22**: project ARN and the curated "Top Devices" pool ARN (5
      real devices) gathered via CloudShell; a narrowly-scoped IAM user
      (`nozzle-it-all-ci`, Device Farm run/upload/read actions only) created
      for CI credentials. `.github/workflows/ci.yml` gained a `device-farm`
      job that uploads both APKs, schedules a real INSTRUMENTATION run, and
      polls to completion, failing the job on anything other than `PASSED`.
      **Two real bugs found getting the first runs working**: (1)
      `actions/upload-artifact` preserves each file's full relative repo
      path when given multiple source paths rather than flattening into the
      artifact root, so the `device-farm` job's `curl` step couldn't find
      the downloaded APKs - fixed by staging both into a flat directory
      before upload. (2) A transcribed AWS secret access key (read off a
      CloudShell screenshot rather than typed from a known-exact value) was
      wrong, so the first credentialed run failed with
      `UnrecognizedClientException` - the owner regenerated the key and set
      the GitHub secrets directly themselves, which is also just the more
      correct way to handle it (the value never needs to pass through this
      agent at all). **First real run** (`ci-13f7d06`, 5 real physical
      devices: Google Pixel 10 Pro XL, Google Pixel 9a, Samsung Galaxy A34,
      Samsung Galaxy S25+, Samsung Galaxy Tab A9) - 696 tests, 95% passed,
      7 unique failures, all reproducing identically across every device:
      - 4 are the same pre-existing device-variance flakiness already
        logged above (`M2DeviceTest`, `BedMeshPanelDeviceTest`,
        `ActiveFilenameDeviceTest`, `DashboardDeviceTest`) - now confirmed
        on a *third* device category (AWS's cloud fleet), not just the two
        physical Razrs.
      - 2 are a **real, separate bug class found by this run**:
        `AddPrinterWizardDeviceTest.addingTheRealU1CompletesEveryStepAndCommitsTheProfile`
        and
        `SlicingCoordinatorDeviceTest.centauriCarbonProfileWithTheCorrectDeclaredGenerationSlicesRealCosmosGcode`
        both hardcode a real LAN printer IP (192.168.1.x / .114) with no
        skip guard, unlike every other real-hardware test in this suite
        (`LivePrinterReadOnlyTest`, `LiveFileHardwareTest`,
        `LivePreviewHardwareTest`, etc., all gated on an explicit
        `InstrumentationRegistry` argument via `Assume.assumeTrue`) - they
        hung to timeout on every device in the pool since Device Farm's
        cloud devices obviously can't reach a home LAN. **Fixed**: both
        given the same `assumeTrue`-gated opt-in pattern
        (`approved_add_printer_u1` / `approved_live_cosmos_slice`
        instrumentation args), matching the established convention exactly.
        Without this fix, the `device-farm` CI job could never pass, ever,
        regardless of code correctness - a real, load-bearing fix for the
        CI gate to mean anything.
      - 1 is a **genuinely new, non-flaky finding, not yet root-caused**:
        `ConfigSavePanelDeviceTest.backsUpBeforeWritingAndReportsTheBackupPath`
        failed identically on all 5 Device Farm devices (`expected:<1> but
        was:<0>` on the write-count assertion) despite passing reliably on
        both physical Razr phones - looks like a real Compose
        recomposition-timing difference specific to Device Farm's device
        environment (different from the "scroll to/can't find node" shape
        of the other flaky tests), not chased further here. Tracked as a
        real open item, not swept in with the already-known flakiness.
    - **Cost control (owner decision, 2026-09-22)**, after seeing the real
      minute cost of the first two runs (1000-minute one-time free trial;
      822 remained after ~178 minutes across exploration + two real CI
      runs; $0.17/device-minute after the trial - a full 5-device run like
      the first two costs ~$15-16 at that rate): `device-farm` changed from
      running on every push to **manual-only**
      (`workflow_dispatch` - `gh workflow run ci.yml` or the Actions tab's
      "Run workflow" button); `build-and-test` still runs on every push at
      no AWS cost. Created `ci-small-pool` (3 real devices: a recent Pixel
      phone, a recent Galaxy phone, a Pixel Tablet - real ARNs, gathered via
      `aws devicefarm list-devices`, not placeholders) as the new default
      pool for routine runs - roughly 40% fewer device-minutes than the
      curated 5-device "Top Devices" pool used for the first two runs, at
      the cost of narrower coverage per run. Device Farm bills per
      device-minute, summed across every device in the pool - a 5-device
      run isn't "however long it took," it's the sum of all 5 devices' own
      times (~91 minutes total for the first real run, even though
      wall-clock was much shorter since devices run in parallel).
    - **Real, permanent gap found while picking `ci-small-pool`'s
      devices**: AWS Device Farm's entire device fleet has **zero
      foldables and zero Motorola devices** (confirmed via
      `list-devices` - no Galaxy Z Fold/Flip, no Pixel Fold, no Razr, no
      Motorola device of any kind). This app's two real physical test
      devices are both Motorola Razr foldables, and `docs/
      HANDOFF_M4A_2026-09-17.md` already documents real foldable-specific
      quirks (dual displays, `adb exec-out screencap` needing a real
      `--display-id`) that Device Farm structurally cannot exercise. The
      physical Razr phones remain necessary for foldable-specific
      verification regardless of how much Device Farm coverage is added -
      not a gap Device Farm can close, tracked so it isn't mistaken for an
      oversight later.
    - **All 5 remaining flaky/new-finding tests root-caused and fixed for
      real, 2026-09-22**, once a genuinely free, fast local reproduction
      environment became available: the existing `24Seven_API_35` AVD
      (Pixel 7 profile, Android 15 - already present on this dev box for
      an unrelated project, not created for this) reproduced all 5
      failures identically to the real AWS Device Farm devices, at zero
      AWS cost and with full logcat/semantics-tree access for real
      diagnosis instead of guessing. Every fix below was verified on the
      emulator, then the full suite (153 instrumented tests, unit tests,
      lint) re-run clean, then the specific fixes re-verified on a real
      physical Razr 2023 too - none were blind patches:
      - **`DashboardDeviceTest.hiddenCardsKeepConnectionAndCommandFeedback`**:
        never set `savedPrinters` in its `ScreenState` - `CompanionScreen`
        only renders a `saved-connect:$address` tile per entry in
        `state.savedPrinters` (`MainActivity.kt`'s
        `items(state.savedPrinters, ...)`), with no fallback for a bare
        address. Deterministic on every device, always; happened to look
        like flakiness only because it was tangled up with the other
        failures. Fixed by adding the missing field.
      - **`BedMeshPanelDeviceTest.loadsAndDisplaysMeshHeatmap`**: waited
        for the `mesh-canvas` tag without ever switching off
        `BedMeshPanel`'s own default view (`view3d=true`, 3D surface) -
        the tag only exists in the Heatmap branch. Fixed by clicking the
        "Heatmap" chip before waiting on the canvas.
      - **`ActiveFilenameDeviceTest.retainedFilenameClearsOnCompletionInTileAndDetailAndReturnsForNextJob`**:
        three independent bugs in one test, found via a real semantics-tree
        dump (Compose's `printToLog`). (1) The first `"0%"` assertion
        expected a percentage to exist on the dashboard tile when there's
        no active file - `PrinterTiles.kt` intentionally shows only "No
        active file" with no percentage node at all in that case; fixed to
        `assertDoesNotExist`. (2) The second `"0%"` assertion (after
        `openFixtureDashboard()`, a different screen - the detail hero
        card) was already correct as `assertExists`, left unchanged.
        (3) A `"Standby"` assertion after `openFixtureDashboard()` expected
        the tile view's titlecased text ("Standby" -
        `PrinterTiles.kt`'s `.replaceFirstChar { it.titlecase() }`), but
        the detail view uppercases it instead (`"STANDBY"` -
        `MainActivity.kt`'s hero card); fixed to expect the correct casing
        for that screen.
      - **`M2DeviceTest.printingAllowsMacroPreparationButDisablesDispatch`**:
        three independent bugs. (1) Only ever "passed" on the physical
        Razr phones from leftover `SharedPreferences` state (a `"TEST"`
        macro favorited by hand during earlier manual testing) -
        `macroOptions` lives in `SharedPreferences` ("macro-options",
        keyed by a SHA-256 hash of the printer address), not `ScreenState`,
        so a fresh test/device could never satisfy the Control tab's
        real, owner-requested "favorites only" filter
        (`macroOptions[name]?.favorite==true`). Fixed by seeding that
        exact preference entry in `@Before`/clearing it in `@After` so the
        test is self-contained and leaves no state for sibling tests
        reusing the same fixture address. (2) Even with (1) fixed,
        `onNodeWithText("Run").performScrollTo()` still failed - a real
        semantics dump showed the Control tab's `LazyColumn` hadn't
        composed that far down yet (`performScrollTo()` needs the target
        node to already exist; a virtualized item that's never been
        scrolled into view doesn't). Fixed via
        `performScrollToNode(hasText("Run"))` on the container, the same
        pattern this suite's own `DashboardTestNavigation.kt` already used
        correctly elsewhere. (3) The final assertions
        (`"Command: TEST"` displayed, `"Confirm"` disabled) were
        unreachable for a third, deeper reason: `MacroReviewPanel` (opened
        after "Review command") has no way to receive a fake `MacroReader`
        through `CompanionScreen`, which hardcodes the real factory
        (`state.moonrakerFor(a)`) - its live check against the fake
        `http://fixture.local/` host simply never resolves in any
        automated environment. Separately, even a *successful* check would
        never produce a disabled "Confirm" button:
        `MacroTools.prepare()` throws when `printState` isn't in
        `allowedStates` (which `"printing"` isn't), so the real confirm
        button never renders at all rather than rendering disabled - the
        test's whole mental model of "disabled dispatch" didn't match
        current code. Fixed by splitting into two tests: the original now
        only verifies the real "Run" → "Review command" flow correctly
        opens `MacroReviewPanel` (still through `CompanionScreen`, still
        real), and a new `macroReviewPanelBlocksDispatchWhilePrinting`
        tests `MacroReviewPanel` directly with an injected fake
        `MacroReader` (the same pattern this suite already uses for other
        reader-dependent panels, e.g. `BedMeshPanelDeviceTest`'s fake
        `MeshReader`) - verifying the exact real
        `MacroTools.prepare()`/`allowedStates` logic deterministically,
        with no live-network dependency.
      - **`ConfigSavePanelDeviceTest.backsUpBeforeWritingAndReportsTheBackupPath`**:
        a real race condition, not device-speed luck -
        `ConfigFilePanel.kt`'s save flow runs `backupConfig()` and
        `writeConfig()` as two separate, sequential
        `withContext(Dispatchers.IO)` calls, not one atomic step. The test
        waited only for `backups.isNotEmpty()` and then immediately
        asserted on `writes` with zero wait in between, racing the second
        dispatcher hand-off. Reproduced locally on the emulator (not just
        on Device Farm), confirming it was real, not infrastructure noise.
        Fixed by waiting for both lists to populate before asserting on
        either.
    - **Phase 0 closed out, 2026-09-22.** All three scope items done: CI +
      AWS Device Farm (live, verified, cost-controlled), Room +
      `Project`/`ProjectObject` infrastructure (built, tested, empty/unused
      by UI as scoped), and the native multi-object proof
      (`count_model_objects`/`MultiObjectModelDeviceTest` - `load_and_place_model`
      never actually flattened via `model.mesh()`; only `load_mesh_preview`
      does, deliberately, for its own single-mesh preview - Phase 0's real
      job was proving this with a real test, done, plus the genuine
      `LoadStrategy` 3MF-loading bug found and fixed along the way). One
      honest partial: no literal Room *migration* tests were written, since
      there's no prior schema version to migrate from yet (v1) - a
      defensible call, not silently declared done. **Actually threading
      `Model::objects` all the way into the viewer/slicer (not just proving
      it's possible) is real, correctly-scoped Phase 1 work, not a Phase 0
      gap** - see Phase 1's own entry below once it starts.
14. **WO-17 — Phase 1 of the Consumer Slicer Plan, owner-approved 2026-09-22
    ("close out Phase 0 and begin Phase 1").** Phase 1 turns the single-object
    viewer into a real multi-object build plate with persistence (§16). This
    entry covers what's landed so far.
    - **Real multi-object native slicing, the true prerequisite for
      everything else in this phase**: `engine::slice_multi_object()` /
      `nativeSliceMultiObject` (`slic3r_engine.cpp/hpp`, `slic3r_jni.cpp`,
      `NativeEngine.kt`) loads N model files, each placed exactly the way
      the existing single-object path already does (real per-object
      bed-centering, then that object's own real `ModelTransform` on top),
      merges them into one `Model` via `Model::add_object(const
      ModelObject&)` (a real libslic3r API), and slices them together into
      one G-code file - additive, not a replacement of `slice_file()`.
      Deliberately does not attempt collision detection itself (a separate,
      real Phase 1 UI concern) - slicing overlapping objects produces
      overlapping geometry, same as the real upstream GUI would.
      **Verified**: `MultiObjectSlicingDeviceTest` (2 device tests) proves
      two real, distinct objects (by G-code object id, not a naive
      `"; printing object"` occurrence count - that marker is written once
      per layer per object, not once per object; a 20mm cube at typical
      layer height produced ~100 markers per object, ~200 total for two -
      caught by an early wrong assertion, not assumed) actually get sliced
      together with real, correctly separated placement (a 60mm toolpath
      X-spread check, not absolute coordinates - the stock factory
      `printable_area` centers objects around bed X=100mm, not X=0, a real
      bed-shape detail also caught by an early wrong assertion). Both
      passing on a real physical device (Razr 2026). Full
      `testDebugUnitTest`/`lintDebug`/`connectedDebugAndroidTest` gate
      re-run clean afterward (one `ActiveFilenameDeviceTest` failure seen
      in the full 147-test run was confirmed pre-existing device-load
      flakiness, not a regression - passed cleanly in isolation, and this
      WO's own changes are pure C++/JNI with no path through that test's
      Compose UI code at all).
    - **Real Room-backed project persistence**: `ProjectViewModel`
      (`project/ProjectViewModel.kt`) is the real state owner for a
      multi-object build plate - add/duplicate/remove objects, per-object
      transform updates, rename/delete project, all backed by the existing
      Phase 0 `ProjectDao` (gained `deleteObject`) and a new
      `ProjectFileStore` that copies imported model files into this
      project's own persisted `filesDir` storage (not `cacheDir`, unlike
      `SliceAndPrintPanel`'s existing single-share-intent flow - a saved
      project's files must survive process death). **Verified**:
      `ProjectViewModelDeviceTest` (3 device tests) round-trips add/
      duplicate/transform/remove through a real file-backed Room database,
      closed and reopened as a genuinely separate `AppDatabase`/
      `ProjectViewModel` instance between writes and reads (the closest an
      instrumented test gets to "survives process death" without an actual
      process restart) - proves persisted object files are real, separate
      copies per object (not shared references), survive the reopen for
      objects that weren't removed, and are actually deleted (both the Room
      row and the file) for objects/projects that were. Also proves
      unsupported file types are rejected before any file is copied. All 3
      passing on a real physical device (Razr 2026).
    - **The real `List<RenderableObject>` multi-object renderer**
      (`ProjectWorkspace.kt`): built as a genuinely separate renderer class
      (`ProjectGLRenderer`) from `ModelViewer.kt`'s existing single-object
      `MeshGLRenderer`, matching this codebase's own precedent
      (`SlicedPreview.kt`'s `ToolpathGLRenderer` is already a separate
      renderer sharing `GLSupport.kt`, not a retrofit of `MeshGLRenderer`) -
      keeps the existing, already-tested single-object share-intent flow
      completely unchanged rather than risking it. Renders every object on
      the plate at once, each with its own real transform (same
      `T(offset)*T(pivot)*Rz*S*T(-pivot)` model-matrix order the native
      engine and `MeshGLRenderer` already use, so what's shown is what
      would actually slice), diffs per-object GL state by object id (no
      full re-upload on a transform-only change), tap-to-select (a real
      ray/bounding-sphere test, `pickObject`), and drag/pinch/rotate the
      currently-selected object via the same real ray/plane bed-intersection
      math as `ModelViewer`'s own Transform mode (`unprojectRay`/
      `rayPlaneXY`/`computeOutOfBounds`, made `internal` in `ModelViewer.kt`
      for this exact reuse, rather than risking two copies quietly
      diverging). Per-object out-of-bounds tinting reuses the same real
      per-printer bed-polygon check. Compiles clean and the full
      `testDebugUnitTest`/`lintDebug`/`assembleDebug` gate passes.
    - **The Files tab's "Projects" section and `ProjectEditorScreen`**
      (owner-confirmed entry point, 2026-09-22 - "New Projects tab/section",
      kept separate from the existing single-object share-intent/Prepare-tab
      flow rather than retrofitting it): `MainActivity.kt`'s Files tab gains
      a third Files/History/Projects chip, backed directly by
      `AppDatabase.get(context).projectDao().observeProjects()` (a real Room
      Flow, not a stub list) - "New project" prompts for a name, creates it
      via `ProjectViewModel.newProject()`, and opens `ProjectEditorScreen`
      (add model via the real document picker, duplicate/remove the
      selected object, `ProjectWorkspace` for the live 3D plate). **Verified
      manually on real hardware (Razr 2026)**: created a project, added a
      real `cube.stl` through the system file picker (persisted, rendered
      correctly), duplicated it (two real objects rendered side-by-side at
      their real distinct offsets, not overlapping), tapped the second cube
      to re-select it (the real ray/bounding-sphere pick, `pickObject`,
      correctly retargeted the highlight to the tapped object) - screenshots
      taken at each step, no crashes in logcat. Full
      `testDebugUnitTest`/`lintDebug`/`assembleDebug` gate passes.
    - **`ProjectWorkspaceDeviceTest`** (automated coverage for the manual
      pass above) - real, loaded `cube.stl` geometry, two fixture objects
      80mm apart, tap-to-select and drag-to-move driven through Compose's
      own touch-injection against the real `ProjectGLRenderer`/GLSurfaceView
      on real hardware, not a mock. **A real bug surfaced writing it**:
      `pickObject`'s sphere-center math used `geometry.center + offset`
      directly, which is only correct for the identity transform (zero
      rotation, unit scale) - it silently ignores the pivot
      (`geometry.origin`) that the renderer's own model matrix and
      `computeOutOfBounds` both already rotate/scale about, so a rotated or
      scaled object's real tap target would have drifted from what's
      actually drawn. Fixed to share the identical rotate-about-pivot math.
      Separately, an early version of the test itself picked screen
      fractions by assuming the two fixtures would mirror symmetrically
      (they don't - azimuth 45° makes one fixture nearer the camera than
      the other, giving it a much larger and differently-positioned screen
      footprint) - caught by scanning real tap outcomes across the
      viewport on the real device rather than trusting the geometry math
      alone, the same discipline this WO's native-slicing tests used
      earlier. 3 tests, all passing on Razr 2026; full
      `testDebugUnitTest`/`lintDebug`/`assembleDebug` gate re-run clean.
    - **Slicing and printing a whole project** - the largest remaining
      piece from the previous entry, now real: `SlicingCoordinator` gained
      `sliceProject()`, a multi-object counterpart to the existing
      `slice()` sharing its firmware-confirmation/profile-pack resolution
      logic (refactored into a private `resolveProfilePaths` both now
      call, rather than a second copy that could drift) and calling
      `engine::slice_multi_object` via `nativeSliceMultiObject` instead of
      slicing one file. `ProjectEditorScreen` gained the real settings/
      Slice/review/print pipeline - layer height, infill, supports
      (`SliceCustomization`, unchanged), a Slice action, then the same
      real stages `SliceAndPrintPanel` already established for the
      single-object flow (sliced 3D toolpath preview + real G-code stats,
      a printer-ready confirmation, a real upload via `LiveFileChanges`,
      then an explicit Start print tap - `Moonraker.start`). Deliberately
      a separate, parallel implementation, not a shared component with
      `SliceAndPrintPanel` - keeps that already-tested single-object flow
      untouched, matching this WO's own established precedent for the
      renderer. **Verified**: `sliceProjectPlacesEachRealObjectAtItsOwnTransform`
      (`SlicingCoordinatorDeviceTest`) proves the coordinator wires real
      `ModelTransform` values through to the native call correctly (2
      distinct per-object G-code ids, correct real toolpath X-spread) -
      and a full manual pass on real hardware (Razr 2026, real Snapmaker
      U1 at 192.168.1.x): created a project, added a real `cube.stl`,
      tapped Slice, got a real sliced result (9m1s, 3.7g, 1.24m, a real
      101-layer 3D toolpath preview), confirmed printer-ready, watched a
      real upload complete (`Ready: project-<uuid>.gcode`) with Start
      print enabled - stopped short of actually tapping Start print to
      avoid triggering a real physical print during verification. Full
      `testDebugUnitTest`/`lintDebug`/`assembleDebug` gate and the
      existing `SlicingCoordinatorDeviceTest`/`SlicingCoordinatorMainThreadDeviceTest`
      suites re-run clean after the refactor (9/9 passing on Razr 2026).
    - **Still open, from the previous entry**: a device test for
      `ProjectEditorScreen`'s own slice/review/print UI flow (covered
      manually only - see the entry above), support painting for a
      project's objects (single-object-only today, see `ModelViewer`'s
      Paint mode), and whether the existing single-object
      `SliceAndPrintPanel.kt`/Prepare-tab flow stays separate long-term or
      eventually folds into this one. Both remain open below.
    - **Phase 1 closed out, 2026-09-23 - auto-arrange, collision
      detection, and project rename/delete, the plan's own remaining §16
      Phase 1 scope items**:
      - `ProjectArrange.kt`: a real 2D `Footprint` (a rotated rectangle in
        world space, built from the object's own real bounding box and
        transform pivot - the same rotate-about-pivot math `pickObject`/
        `computeOutOfBounds`/the GL renderer's model matrix already use,
        so it agrees with what's actually on screen), a real
        separating-axis-theorem overlap test (`footprintsOverlap` - not a
        coarser AABB-only or bounding-circle check, which would either
        miss a real corner overlap between two independently-rotated
        objects or falsely flag two rotated objects whose *un-rotated*
        bounding boxes merely touch), and a real first-fit-decreasing-
        height shelf-packing `autoArrange` (a standard, legitimate 2D
        bin-packing heuristic - not a stub - that genuinely uses rotation,
        choosing per item whichever of its two axis-aligned orientations
        packs flatter). **A real bug caught by its own test, not assumed
        correct**: the first version of `autoArrange`'s orientation choice
        was backwards - it always picked the *taller* orientation, the
        opposite of what shelf packing wants - caught by
        `autoArrangePicksTheNarrowerOrientationPerItem` failing on its
        first run, not by inspection.
      - `ProjectEditorScreen` wires both in: a live pairwise collision
        check (recomputed from current object state each recomposition)
        that tints colliding objects red in `ProjectWorkspace` (reusing
        the same red-tint channel `computeOutOfBounds` already drives,
        since both mean "this can't be sliced safely as placed") and
        disables Slice while any pair overlaps; an "Auto-arrange" button
        that resolves the whole plate in one tap using the real bed width
        (`bedShapeFor`, the same source `SliceAndPrintPanel` already
        reads - not an invented default).
      - `MainActivity`'s project list gained real Rename/Delete actions
        per row, both reusing `ProjectViewModel.renameProject`/
        `deleteProject` (loaded fresh per action) rather than calling
        `ProjectDao` directly, so delete's real file cleanup
        (`ProjectFileStore.deleteProject`) can't be bypassed by a second,
        thinner code path.
      - **Verified**: `ProjectArrangeTest` (10 JVM unit tests - footprint
        math against a mesh whose local origin isn't its own centroid,
        overlap detection including a real rotated-rectangle SAT case an
        AABB check would get wrong, shelf-wrapping, empty input, the
        orientation bug above) plus a full manual pass on real hardware
        (Razr 2026): duplicated an object directly onto itself, watched
        the real collision warning appear and both objects render red
        with Slice disabled, tapped Auto-arrange and watched the plate
        resolve to a real non-overlapping side-by-side layout with Slice
        re-enabled; renamed a project (persisted, survived returning to
        the list) and deleted one (confirmed both the Room row and its
        persisted object files were actually gone via `run-as find`, not
        just removed from the visible list). Full
        `testDebugUnitTest`/`lintDebug`/`assembleDebug` gate and
        `ProjectWorkspaceDeviceTest`/`ProjectViewModelDeviceTest` re-run
        clean afterward (6/6 passing on Razr 2026). **Not** covered by an
        automated `connectedDebugAndroidTest` for the rename/delete UI
        specifically - `MainActivity`'s project list reads the app's real
        production `AppDatabase` singleton (no test-database override
        exists for it, unlike `ProjectViewModelDeviceTest`'s own isolated
        file-backed database), so an automated UI test here would mutate
        real user data rather than a fixture; the underlying
        rename/delete logic itself is already device-tested via
        `ProjectViewModel` directly.
      - This closes every scope item the plan's own §16 Phase 1 entry
        lists (object list, add/duplicate/delete, per-object transform,
        auto-arrange, collision detection, save/load/rename/drafts) and
        both of its acceptance criteria (multi-object persistence across
        a simulated process death; slicing a real multi-object plate for
        correct per-object G-code) - **Phase 1 is done**. Phase 2
        (Printer Capability Layer) is next per the plan's own
        recommended sequencing, and can run independently of Phase 1's
        own remaining polish items above.
15. **WO-18 — Phase 2 of the Consumer Slicer Plan, owner-approved 2026-09-23
    ("continue" through Phase 2, full migration).** Replaces the
    `PrinterKind`-shaped `bambu`/`prusa`/`nonKlipper` conditionals scattered
    across the UI with one real `PrinterCapabilities` object (§10).
    - **`PrinterCapabilities.kt`** (new): `PrinterTransport` (MOONRAKER/
      BAMBU_MQTT/PRUSA_LINK) plus a real capability data class -
      `supportsPauseResumeCancel`, `supportsCamera`, `supportsKlipperExtras`
      (the ~15-reader-interface Moonraker-only group, kept as one flag since
      nothing in this codebase differentiates within it today),
      `supportsNativePrintFileFlow` (Bambu's `.gcode.3mf` routing),
      `acceptsOnDeviceSlicedGcode`, `hasBespok3d`/`hasMultiAce` (PAXX add-ons),
      `verifiedOnRealHardware`, plus the plan's own not-yet-implemented
      fields (`hasFilamentSensor`/`supportsJog`/`supportsBedLevelingTrigger`/
      `supportsTimelapseTrigger`, explicit `false` for every vendor, not
      omitted) - resolved by a pure `capabilitiesFor(PrinterKind)` function,
      deliberately synchronous (no Context/IO) since it only replaces the
      profile-derivable UI-gating checks; bed shape/bundled slicer profile
      stay exactly where they already lived (`SlicingCoordinator`'s
      `resolveProfilePaths`, `bedShapeFor`) since those are a real, separate,
      already-async concern (which slicer profile) Phase 3 (materials)
      builds on next, not the transport/vendor duality this phase targets.
    - **Migrated every real `when(kind)`/`nonKlipper`-style UI gate**
      (`MainActivity.kt`'s `bambu`/`prusa`/`nonKlipper` flags and all ~13 use
      sites, `PrinterTiles.kt`'s `unverifiedOnRealHardware`) to read
      `capabilities` instead - zero behavior change for the paths that were
      already correct, confirmed by the full existing device-test suite
      staying green (see Verified below).
    - **Two real bugs found and fixed along the way** (not part of the
      refactor itself, but surfaced by mapping every kind-branch before
      migrating them):
      - `FilePanels.kt`'s "Follow active print"/"Printer file changes"
        buttons were gated on `kindFor != BAMBU_LAB`, which left them
        *visible* for a Prusa Link printer too - `LivePrintPreviewPanel`/
        `LiveFilePanel` construct a `Moonraker` client directly, so tapping
        either against a real Prusa printer would have hit the wrong
        host/protocol. Now gated on `capabilities.transport == MOONRAKER`.
      - `SliceAndPrintPanel.kt` and `ProjectEditorScreen.kt` both only
        blocked Bambu Lab from on-device slicing; neither blocked Prusa
        Link, even though the upload step (`LiveFileChanges`/
        `Moonraker.start`) unconditionally speaks Moonraker's own protocol,
        which `PrusaLinkPrinterService` doesn't implement (no generic
        file-upload endpoint) - slicing would have "succeeded" and then
        failed or misbehaved at the upload step against a real Prusa
        printer. Both screens now honestly block Prusa Link the same way
        they already blocked Bambu Lab (`acceptsOnDeviceSlicedGcode`).
    - **Deliberately out of scope, not overlooked**: the ~28 panels that
      construct a `Moonraker` client directly rather than going through
      `PrinterService` (`MainActivity`'s `state.moonrakerFor`/bare
      `Moonraker(...)` factories) - real architectural debt, but not a
      `when(kind)` branch, and MainActivity's own capability gating already
      keeps Bambu/Prusa printers from ever reaching those panels; profile
      creation forms (`M1Panels.kt`/`AddPrinterWizard.kt`'s per-vendor
      credential fields and model chips) - those author a profile's own
      `kind`/`slicingModel` before any capability object could exist, a
      different concern from gating an already-resolved profile.
    - **Verified**: `PrinterCapabilitiesTest` (9 JVM unit tests - one
      capability-resolution test per vendor integration, the plan's own
      Tests requirement, plus the not-yet-implemented-capabilities and
      `ScreenState.capabilitiesFor` resolution cases) and
      `PrinterCapabilitiesDeviceTest` (4 real Compose UI tests - a Bambu
      profile hides every Klipper-only control and shows its own
      limitations card, a Prusa profile hides Klipper extras but keeps
      real pause/resume/cancel and shows its own limitations card, a
      Snapmaker U1/PAXX profile shows its vendor add-on buttons, a generic
      Klipper profile shows everything with no limitations card - the
      plan's own "a capability-gated control is hidden for printers that
      lack it" requirement). Given the plan's own risk note ("needs the
      existing 100+ device tests re-run in full, not spot-checked"), the
      **entire** `connectedDebugAndroidTest` suite was re-run on real
      hardware after the migration, not just the new/touched tests: 159
      tests, 0 failures, 0 errors, 8 skipped (real-hardware-opt-in tests,
      unaffected) - confirmed clean on Razr 2026. Full
      `testDebugUnitTest`/`lintDebug`/`assembleDebug` gate passes, plus a
      manual screenshot pass against the real Snapmaker U1 confirming the
      Control tab renders identically (Bespok3d/multiACE, hardware
      controls, diagnostics group all present and correct).
    - **Still open** (Phase 2's remaining scope per the plan's own §16
      entry): none of the plan's own listed scope items remain - object
      list/add/duplicate/delete were Phase 1; Phase 2's stated scope
      ("capability resolution per printer, migrate every conditional UI
      path... to read capabilities instead of ad-hoc kind checks") is
      fully covered. The acceptance criteria ("adding a 5th printer vendor
      requires only a new `PrinterTransport` implementation and a
      capability-resolution function - zero new `when(kind)` branches in
      UI code") holds for every UI path migrated above. **Phase 2 is
      done.**
16. **WO-19 — Phase 3 of the Consumer Slicer Plan, owner-approved 2026-09-23
    ("continue and complete Phase 3").** Promotes material from "three
    slicer settings" to a real, slicing-connected entity (§11), scoped to
    the plan's own Phase 3 boundary: single material per project, not
    per-object/per-tool assignment (that's `MaterialAssignment`/`ToolSlot`,
    real §11 concepts left unbuilt until Phase 8's multi-tool UI actually
    needs them - every printer integration in this codebase is
    single-extruder today).
    - **`Materials.kt`** (new): `MaterialProfile` (id, displayName, type,
      manufacturer, colorHex, tempNozzleC/tempBedC, source: BUNDLED/
      SPOOLMAN/CUSTOM), 4 real bundled profiles (PLA/PETG/ABS/TPU, standard
      FDM starting temperatures), `SpoolmanSpool.toMaterialProfile()` (a
      real *source* conversion, not a fork of Spoolman's own model - see
      §11's own framing), and `MaterialProfile.toOverrides()` mapping to
      the real OrcaSlicer config keys this app's bundled profile packs
      actually use (`nozzle_temperature`/`nozzle_temperature_initial_layer`,
      plus all four `*_plate_temp`/`*_plate_temp_initial_layer` bed-temp
      keys, since which single one a given machine profile's own start
      G-code references depends on its `bed_type` setting - overriding
      every variant guarantees the one that's actually used gets the real
      value).
    - **Real Spoolman temperature data, not invented**: `Spoolman.kt`'s
      `SpoolmanSpool` gained `tempNozzleC`/`tempBedC`, parsed from
      Spoolman's own real `settings_extruder_temp`/`settings_bed_temp`
      Filament fields - confirmed against the actual upstream schema
      (`Donkie/Spoolman`, `spoolman/api/v1/models.py`'s `Filament` class,
      both `int | None`) via `gh api`, not guessed at. Null when a real
      filament entry has neither configured (common - not every Spoolman
      filament has them set), which correctly produces no override rather
      than a fabricated default.
    - **Persistence**: `ProjectObject` gains `materialDisplayName`/
      `materialTempNozzleC`/`materialTempBedC` (the existing
      `materialId` column was already planned ahead as of Phase 0, unused
      until now) - a denormalized snapshot of the picked profile's real
      values, not a live Spoolman ID re-resolved at slice time (a spool
      picked days before slicing might be renamed, edited, or its
      Spoolman server unreachable by then). **A real Room migration**
      (`MIGRATION_1_2`, version 1→2, `ALTER TABLE ... ADD COLUMN`) rather
      than `fallbackToDestructiveMigration()` - by the time this shipped,
      real project data already existed from this session's own testing,
      and this app's own stated principle is that project state must
      never silently vanish (§20). `ProjectViewModel.setProjectMaterial()`
      applies one material to every object at once (the real
      "single-material-per-project" enforcement point) and a newly added
      object picks up whatever material the project's existing objects
      already share.
    - **`ProjectEditorScreen`**: a "Material" row (current selection +
      "Choose") above Slicing settings, opening a picker - "None", the 4
      bundled profiles, and (when the target printer is Moonraker-backed
      and connected) its live Spoolman inventory, each spool shown with
      its real configured temperature or an honest "no temperature set."
      The picked material's `toOverrides()` merges into the same override
      map `SliceCustomization` already populates, on top of (not instead
      of) layer height/infill/supports.
    - **Verified**: `MaterialsTest` (6 JVM unit tests - bundled-profile
      sanity, Spoolman conversion including the real-schema field names,
      override-map correctness, the null-stays-null case) and
      `SpoolmanTest`'s extended coverage for the new real temperature
      fields. `ProjectViewModelDeviceTest` gained a real Room round-trip
      test (material applies to existing *and* newly-added objects,
      survives a database reopen, clearing it back to null actually
      clears every object). **The plan's own Phase 3 acceptance
      criterion, verified twice**: `materialProfileTemperaturesReachTheRealSlicedGcode`
      (`SlicingCoordinatorDeviceTest`) slices a real cube with PETG's
      bundled profile and confirms the actual G-code contains real
      `M109 S240`/`M140 S80`/`M190 S80` commands - not that the override
      map was merely accepted; and a full manual pass on real hardware
      (Razr 2026, real Snapmaker U1): picked PETG in the Material picker,
      sliced, then read the real sliced G-code straight off the device
      (`run-as ... cat cache/sliced-output/project.gcode`) and confirmed
      the same `M109 S240`/`M140 S80`/`M190 S80` commands were actually
      there. No leftover v1-schema project existed on-device to exercise
      the literal `ALTER TABLE` migration path live (the one test project
      from earlier this session had already been deleted during WO-17's
      own rename/delete verification) - a real gap in this pass, though a
      low-risk one (a standard, minimal `ADD COLUMN` migration). Full
      `testDebugUnitTest`/`lintDebug`/`assembleDebug` gate and the
      **entire** `connectedDebugAndroidTest` suite re-run clean after the
      schema change: 161 tests, 0 failures, 0 errors, 8 skipped, on Razr
      2026.
    - **Still open** (Phase 3's remaining scope per the plan's own §16
      entry): none - object/material model, Spoolman wiring, and the
      single-material-per-project picker are exactly what Phase 3 scoped;
      `MaterialAssignment`/`ToolSlot`/multi-tool UI are explicitly Phase 8,
      not this phase. **A "Materials" section in Settings** (the plan's
      own UI/screens line also names this) was **not** built as a
      separate persistent screen - this app's Settings tab is
      printer-management-focused with no other natural home for a
      materials catalog, and the picker living directly in
      `ProjectEditorScreen` (the actual "Prepare" surface for this app's
      multi-object flow) satisfies the real acceptance criterion without
      inventing a screen with no other content. Worth revisiting if a
      real need for a *persistent, editable* custom-material catalog
      shows up later (today: Bundled + live Spoolman only, no saved
      Custom profiles yet - `MaterialSource.CUSTOM` exists in the type but
      nothing constructs one). **Phase 3 is done.**
17. **WO-20 — Phase 4 of the Consumer Slicer Plan, owner-approved 2026-09-23
    ("continue"), plus a real text-contrast bug fix (owner-reported mid-phase,
    2026-09-23: "several of your screens have text that is difficult to
    read, especially around the slicer... the text itself is dark against
    a dark background").**
    - **`BasicSlicing.kt`** (new): the plan's own "basic-mode settings"
      surface - `QualityPreset` (Draft/Standard/Fine → real 0.28/0.2/0.12mm
      layer heights, Standard matching this app's pre-existing default so
      an untouched project behaves identically to before this phase),
      `SupportMode` (Off/Auto/On), `BasicSliceSettings`, and the plan's own
      "intelligent defaulting engine" piece: `meshNeedsSupport()`, a real
      overhang detector over the mesh's own actual vertex/normal data (a
      face needs support if its real normal points >45° below horizontal,
      excluding the object's own real bed-contact base at `minZ`) - not a
      guess, not left to the owner to already know. Z-axis rotation (the
      only rotation `ModelTransform` supports) never changes a normal's Z
      component, so the check is valid at any live rotation without
      needing to re-transform the mesh.
    - **`ProjectEditorScreen`**: replaced the raw layer-height text field
      with real Quality preset chips, added Support Off/Auto/On chips
      (Auto shows a live "detected: this model needs/doesn't need
      support" readout, driven by the same real geometry check that feeds
      the actual slice), a Brim (bed adhesion) toggle (only overrides
      `brim_width` when turned off - on leaves each bundled profile's own
      real default of 5mm in effect, not a re-typed copy of it), and a
      "Copies of selected" control (duplicates/removes down to N total
      instances of the selected object, then runs the same real
      auto-arrange path so N copies never land stacked on top of each
      other).
    - **A real, unrelated bug found and fixed along the way**: the owner
      reported hard-to-read text, "especially around the slicer." Root
      cause: `ProjectEditorScreen`/`SliceAndPrintPanel` both render as
      full-screen overlays via a plain `Box(...).background(...)`, which
      never sets `LocalContentColor` - every `Text()` without its own
      explicit `color` fell back to Compose Material3's own top-level
      default (`Color.Black`), nearly invisible against this theme's
      near-black background. `MainActivity`'s own screen never showed
      this because it renders inside `Scaffold`, which *is* a `Surface`
      under the hood and sets `LocalContentColor` correctly for free -
      these two full-screen panels, added outside that, never got it.
      Confirmed real via screenshots before and after (several labels -
      "Objects on this plate," "Material," "Slicing settings," "Brim (bed
      adhesion)," the screen's own title - were essentially unreadable
      dark-on-dark). Fixed by replacing the plain `Box` root with
      `Surface(..., color = MaterialTheme.colorScheme.background)` in
      both files - `Surface` sets `LocalContentColor` via
      `contentColorFor(color)` automatically, so this is the real,
      minimal fix (not a per-`Text` color patch every future addition to
      either screen would have to remember on its own). A codebase-wide
      grep confirmed no other full-screen panel uses the same broken
      bare-`Box` pattern - every other panel either renders inside
      `Scaffold` or inside `AlertDialog` (itself `Surface`-backed).
    - **Verified**: `BasicSlicingTest` (10 JVM unit tests - overhang
      detection against hand-built triangle fixtures including a real
      SAT-adjacent shallow-angle threshold case, quality-preset layer
      heights, explicit-vs-auto support precedence, the brim
      on/off-only-overrides-when-off behavior) and
      `BasicSlicingDeviceTest` (4 real device tests using the same real
      overhang fixture `PaintSessionDeviceTest` already established -
      `meshNeedsSupport` against real loaded geometry for both a flat
      cube and a genuinely overhanging pillar-and-cap model, then a full
      real slice proving AUTO mode actually emits real "support material"
      extrusion in the G-code for the overhanging model, and that an
      explicit Off choice suppresses it even when the geometry says
      otherwise). A full manual pass on real hardware (Razr 2026, real
      Snapmaker U1): created a project, selected Fine quality, sliced, and
      confirmed the real output was 167 layers for a 20.05mm model at
      0.12mm (20.05/0.12 ≈ 167.1) versus 101 layers at Standard's 0.2mm
      from an earlier slice of the same model - the real math, not just
      that the field accepted a value. Also visually re-verified both
      slicer screens after the contrast fix (screenshots before/after).
      Full `testDebugUnitTest`/`lintDebug`/`assembleDebug` gate and the
      **entire** `connectedDebugAndroidTest` suite re-run clean: 165
      tests, 0 failures, 0 errors, 8 skipped, on Razr 2026.
    - **Not built this entry**: "copies" only affects placement via
      auto-arrange (no dedicated per-copy transform UI beyond that); no
      golden-default table exists yet cross-referencing printer × material
      × model-geometry combinations against an approved expected-defaults
      table (the plan's own Tests line) - today's defaults are fixed,
      sensible constants (Standard quality, 15% infill, Auto support) plus
      the one real geometry-driven decision (support), not a broader
      per-printer/per-material defaulting matrix; nothing in this
      codebase's existing capability/material model yet varies quality or
      infill by printer or material, so there was no real matrix to
      generate defaults from yet. **Phase 4 is done** against the plan's
      own stated scope (quality preset, strength, supports on/auto/off,
      adhesion/brim, copies, geometry-driven defaulting) and acceptance
      criterion (picking printer + material + quality preset - all now
      real decisions with trustworthy defaults - is enough to reach
      Print).
18. **WO-21 — Phase 5 of the Consumer Slicer Plan, owner-approved 2026-09-23
    ("continue").** "Extend the existing toolpath preview with per-material
    coloring and pre-slice validation" (§16). Continuous bounds/collision
    validation for multi-object was already real as of Phase 1
    (`ProjectWorkspace`'s own live out-of-bounds/collision checks, WO-17) -
    this phase's real remaining scope was the slice-time half.
    - **`SliceValidation.kt`** (new): `MachineLimits`
      (min/max_layer_height, real per-printer machine.json fields) and
      `FilamentTemperatureRange` (nozzle_temperature_range_low/high, real
      per-profile-pack filament.json fields) - both parsed from the exact
      same bundled asset files every slice already applies, not invented
      separately. `validateSliceConfiguration()` produces
      `SliceValidationIssue`s: a layer height outside the printer's real
      declared range **blocks** slicing (a genuine hardware/firmware
      bound); a material's nozzle temperature outside the bundled
      profile's own declared range is a **non-blocking warning** (real,
      but not a hard limit - PETG/ABS commonly print legitimately hotter
      than a PLA-centric bundled default, so this informs rather than
      second-guesses a deliberate choice).
      **Confirmed this has real teeth today, not just in theory**: this
      app's own bundled PETG (240°C) and ABS (250°C) `MaterialProfile`
      presets (Phase 3) genuinely exceed every bundled profile pack's own
      declared 190-230°C range - a real, currently-true mismatch, caught
      by a dedicated test and reproduced live on real hardware.
    - **Wired into both slicing flows**: `ProjectEditorScreen` shows every
      validation issue live (as settings change, not only after tapping
      Slice) and disables Slice while a blocking one exists;
      `SliceAndPrintPanel` (single-object, no material selection) gets
      the layer-height check on its own "Slice" step. Neither flow's
      existing behavior changed for a configuration that was already
      valid.
    - **Per-material toolpath coloring**: `SlicedPreview` gained
      `materialColorHex`, converting a real material's `colorHex` (a
      Spoolman-reported color, when one exists) into the sliced-preview's
      extrusion-path color via a real hex parser (`parseHexColor`),
      falling back to the existing print-orange default when no material
      or no color is set - `ProjectEditorScreen` passes the project's
      current material's color through.
    - **Verified**: `SliceValidationTest` (12 JVM unit tests - real parse
      coverage against every bundled machine.json/filament.json, the
      pure validation decision logic including the real PETG/ABS-exceeds-
      range case, absence-of-data never being treated as "out of range")
      and `SlicedPreviewTest` (4 unit tests for the hex-color parser).
      A full manual pass on real hardware (Razr 2026, real Snapmaker U1):
      picked PETG, watched the real live warning
      ("PETG's nozzle temperature (240°C) is outside this printer's
      bundled profile's declared range (190°C-230°C)...") appear with
      Slice still enabled, sliced successfully, and confirmed the
      preview's extrusion color correctly falls back to the default
      orange for a bundled material with no configured color (no crash,
      no regression) - a live Spoolman-sourced color couldn't be
      end-to-end verified on this device (no moonraker-spoolman
      configured on the real U1 used throughout this session), so that
      specific path relies on `SlicedPreviewTest`'s unit coverage plus
      code review rather than a real-device screenshot. Full
      `testDebugUnitTest`/`lintDebug`/`assembleDebug` gate and the
      **entire** `connectedDebugAndroidTest` suite re-run clean: 165
      tests, 0 failures, 0 errors, 8 skipped, on Razr 2026.
    - **Not built this entry**: the blocking layer-height check has no
      real device test exercising an actual out-of-range value, since
      every bundled profile's own min/max_layer_height today comfortably
      contains all three `QualityPreset` values (Draft 0.28/Standard
      0.2/Fine 0.12mm against ranges of roughly 0.05-0.35mm) - the check
      is real and unit-tested against synthetic bounds, but has no
      currently-true real-world trigger the way the material-temperature
      warning does. **Phase 5 is done** against the plan's own stated
      scope and acceptance criterion (a genuinely invalid configuration
      is now caught before slicing starts, with an actionable message).
19. **WO-22 — Phase 6 of the Consumer Slicer Plan, owner-directed 2026-09-22
    ("I mean OrcaSlicer has the ability to print to Bambu printers, surely
    you can get the information from the Orca source?" / "And if not from
    there, Bambuddy allows Bambu control, surely there is something you
    can use from that source").** Real, partial progress on Phase 6's
    "reliable slice → transfer → print across all 4 vendor integrations"
    (§16): Bambu Lab on-device slicing is now real; Prusa Link upload is
    still not built (separate, still-open gap, unchanged this entry).
    - **Owner explicitly rejected deferring Bambu.** Initial research
      (background agent) found `store_bbs_3mf` (the real bundle writer,
      `libslic3r/Format/bbs_3mf.cpp`, already linked into this headless
      engine) but its only known caller, `PartPlateList::store_to_3mf_
      structure`, is GUI-module code (`slic3r/GUI/PartPlate.cpp`,
      wxWidgets-linked) that this app's `SLIC3R_GUI=OFF` build can't use -
      the agent's initial read leaned toward deferring Bambu. Owner
      pushed back directly, twice, naming two real sources to dig into
      further rather than accept that conclusion: the vendored OrcaSlicer
      source itself, and Bambuddy (`maziggy/bambuddy`, a real self-hosted
      Bambu management project). Reading both further did settle it:
      `PlateData` (the struct `store_bbs_3mf` actually consumes) is a
      plain, non-GUI `libslic3r` struct - buildable directly without
      `PartPlateList` at all - and `CLI::export_project` (`OrcaSlicer.cpp`)
      confirmed a much simpler, non-GUI reference for the real
      `SaveStrategy` flags a bundle export needs. Bambuddy's own
      `slicer_api.py` docstring independently confirmed OrcaSlicer's CLI
      has a working, documented `.gcode.3mf` export path. Neither source
      was skimmed for a reassuring quote - both were read closely enough
      to find the concrete facts that made the real implementation
      possible (see below).
    - **`engine::slice_bambu_bundle`** (new, `slic3r_engine.cpp/.hpp`):
      builds a `PlateData`/`StoreParams` directly (bypassing the GUI-only
      `PartPlateList` path entirely) and calls the real `store_bbs_3mf`
      with the same `SaveStrategy` flags the desktop GUI's own "send to
      printer" action uses (`Silence|WithGcode|SkipModel|SkipAuxiliary`).
      Produces a real Bambu-compatible `.gcode.3mf`: real embedded
      G-code, a real MD5 computed by the writer itself from the embedded
      bytes (not precomputed), a real thumbnail (reusing this engine's
      existing headless rasterizer), and a real `slice_info.config` with
      the bundled machine.json's own declared printer model.
    - **Two real, independently-confirmed bugs fixed along the way, not
      guessed at:**
      - `bambu_generic/machine.json` declared `"gcode_flavor": "klipper"` -
        wrong; the real upstream OrcaSlicer Bambu profile chain (`Bambu
        Lab A1 0.4 nozzle.json` → `fdm_bbl_3dp_001_common.json` →
        `fdm_machine_common.json`) declares `"marlin"`, and nothing in
        that chain overrides it back. Fixed to match.
      - **The real blocker**, root-caused via a temporary boost::log→
        logcat diagnostic (this build never installs a boost::log sink,
        so `bbs_3mf.cpp`'s own narrating `BOOST_LOG_TRIVIAL` calls went
        nowhere observable - added, used, then removed once done, same
        discipline as WO-13's own native-debugging precedent): every
        real attempt failed with `store_bbs_3mf` returning `false`,
        traced to `Model::get_backup_path()` falling back to the bare,
        root-relative `"/orcaslicer_model/..."` - a real "Read-only file
        system" failure on Android - because this app never calls
        `Slic3r::set_temporary_dir()`, so `Utils.cpp`'s own
        `g_temporary_dir` was empty. Fixed by pointing it at the caller's
        own writable output directory before slicing (every call site
        already passes an app-cache-relative bundle path), plus a
        best-effort `Model::remove_backup_path_if_exist()` cleanup after
        export.
    - **Wired into the real print flow, not left native-only**:
      `PrinterCapabilities.acceptsOnDeviceSlicedGcode` is now `true` for
      `BAMBU_LAB`; `SlicingCoordinator.slice()` branches to
      `nativeSliceBambuBundle`/a `.gcode.3mf` output for a Bambu target
      instead of `nativeSliceFile`; `SliceAndPrintPanel` skips the
      Moonraker-only `LiveFileChanges` upload step for a Bambu target
      (the bundle uploads as part of one real print command instead,
      `BambuPrinterService.startPrint` - the same FTPS+MQTT flow
      `BambuPrintPanel`'s own share-intent path already uses) and, for
      the toolpath/stats review step, extracts the real embedded
      `Metadata/plate_1.gcode` out of the bundle into a throwaway temp
      file first (the existing `GcodePreview`/`GcodeStatsParser` parsers
      expect plain G-code text, not a zip). `ProjectEditorScreen`'s
      separate multi-object plate flow deliberately still excludes Bambu
      Lab - it only slices through `nativeSliceMultiObject`, which has no
      Bambu-bundle counterpart yet - a real, explicit, still-open gap,
      not an oversight.
    - **Verified**: `BambuBundleDeviceTest` (new, 7 real device tests
      against the real bundled Bambu profile pack and a real cube
      fixture) - a genuine non-trivial zip, the real 3MF container files,
      real G-code inside `Metadata/plate_1.gcode` with a real OrcaSlicer
      header, the archive's own declared MD5 verified against an
      independently-computed MD5 of the actual embedded bytes (not a
      placeholder), a real non-empty PNG thumbnail, `slice_info.config`
      containing the real bundled printer model ("Bambu Lab A1"), and no
      leftover intermediate `.gcode.tmp` file. `PrinterCapabilitiesTest`
      updated to assert the new, true `acceptsOnDeviceSlicedGcode`
      value for Bambu Lab. Full `testDebugUnitTest`/`lintDebug`/
      `assembleDebug` gate and the **entire** device-test suite re-run
      clean: 172 tests, 0 failures, on Razr 2026 (`ZP22235MHM`) only -
      the Razr 2023 (`ZY22HXCVPM`) is in use by another project this
      session and was deliberately left untouched, run via direct
      `adb shell am instrument` rather than gradle's
      `connectedDebugAndroidTest` (which enumerates every attached
      device with no built-in single-device filter).
    - **Not built this entry, real and explicit**: Prusa Link upload
      (Phase 6's other real gap - `PUT /api/v1/files/{storage}/{path}`
      with `Print-After-Upload`, plus a real pre-existing storage-path
      bug this session's earlier research already found -
      `PrusaLinkPrinterService` hardcodes `local` but MK4/MK3.9/MINI/XL
      firmware only has `/usb`); multi-object Bambu bundle export
      (`ProjectEditorScreen`'s plate flow); no real Bambu Lab hardware
      exists to verify a printer actually accepts and prints this bundle
      (the owner has none) - everything above is verified as far as
      real, structural, on-device checks can go without it, matching
      this project's own standing, honest disclosure convention for
      every Bambu/PrusaLink integration in this codebase.
20. **WO-23 — Phase 6 completion, owner-directed 2026-09-22 ("Finish Prusa
    Link upload, and multi-object Bambu bundle export").** Closes both
    real gaps WO-22 left open.
    - **Prusa Link real upload+print** (`PrusaLinkPrinterService.
      uploadAndPrint`): one real `PUT /api/v1/files/{storage}/{path}`
      with `Print-After-Upload: ?1` and `Overwrite: ?1` (prusa3d/
      Prusa-Link-Web's own published `spec/openapi.yaml`, fetched and
      read directly, not assumed) - upload and print-start in one call,
      not a separate round trip. **Real, pre-existing bug fixed**:
      every call site in this class (file listing, print-start, and the
      new upload) hardcoded `local` as the target storage; a real
      MK4/MK3.9/MINI/XL only ever exposes a writable `/usb` (its own
      `LOCAL`-type entry, when present, is the printer's tiny internal
      flash and reports `read_only: true` - confirmed against
      `Prusa-Firmware-Buddy`'s own source, not guessed). Fixed by adding
      `resolveWritableStorage()` - a real `GET /api/v1/storage` call
      that picks the first `available && !read_only` entry's own
      declared `path`, used by every call site instead of a literal.
      **Real large-upload fix**: OkHttp's own reactive digest
      `Authenticator` (`PrusaLinkDigestAuthenticator`, this session's own
      from-scratch RFC 2617 implementation - PrusaLink's entire API
      requires digest auth with no alternative scheme) only answers a
      401 after the request already tried once - for a large streamed
      G-code body, that means sending the whole file to the printer
      twice. `PrusaLinkDigestAuthenticator` now remembers the last real
      challenge it answered and exposes `preemptiveHeader()`, so
      `uploadAndPrint()` attaches a real, already-computed Authorization
      header up front (falling back to one genuinely unauthenticated
      attempt - letting the reactive path handle it as before - only if
      a stale nonce is itself rejected). A separate `uploadClient` (real,
      longer write/read/call timeouts than the small JSON status/control
      client) is used for the upload call specifically, since a
      multi-hundred-megabyte file over a real LAN link can genuinely
      exceed the 4s/6s/8s timeouts sized for small requests.
      `PrinterCapabilities.acceptsOnDeviceSlicedGcode` is now `true` for
      `PRUSA_LINK`; `SliceAndPrintPanel`/`ProjectEditorScreen` both skip
      their Moonraker-only `LiveFileChanges` upload step for a Prusa Link
      target and dispatch a single `PrinterCommand(prusaLinkPrintRequest
      = ...)` instead, the same shape `bambuPrintRequest` already uses
      for Bambu.
    - **Multi-object Bambu bundle export** (`engine::
      slice_multi_object_bambu_bundle`/`nativeSliceMultiObjectBambuBundle`):
      the real multi-object counterpart to WO-22's `slice_bambu_bundle` -
      same relationship `slice_multi_object` already has to `slice_file`.
      The shared PlateData/StoreParams-building tail (`bundle_model`,
      `slic3r_engine.cpp`) was factored out of `slice_bambu_bundle` so
      both the single- and multi-object bundle paths write the identical
      real bundle shape (real embedded G-code, real MD5, real thumbnail,
      real `slice_info.config`) rather than a second, parallel
      bundle-writing implementation. `ProjectEditorScreen`'s own
      `acceptsSlicedGcode` gate no longer excludes Bambu Lab - it now
      trusts `PrinterCapabilities` directly, same as `SliceAndPrintPanel`.
    - **Verified**: `PrusaLinkPrinterServiceTest` gained real MockWebServer
      contract tests for `resolveWritableStorage()` (picks the real
      writable `/usb` entry over a read-only `/local` one; fails honestly
      when nothing is writable) and `uploadAndPrint()` (a real `PUT` to
      the resolved storage path with both documented headers present, the
      real file bytes in the request body; a rejected upload surfaces as
      a real `ApiFailure`, not a silent no-op). `PrinterCapabilitiesTest`
      updated for Prusa Link's new `acceptsOnDeviceSlicedGcode`.
      `MultiObjectBambuBundleDeviceTest` (new, 4 device tests): a real
      bundle containing two distinct real per-object G-code ids (not a
      flattened single object), a real toolpath X-coordinate spread
      confirming the two objects are genuinely placed 60mm apart inside
      the bundle (not sliced twice at the same spot), the embedded MD5
      matching the embedded G-code, and mismatched transform-array
      lengths failing with a real exception - mirroring the same real
      checks `BambuBundleDeviceTest`/`MultiObjectSlicingDeviceTest`
      already run for their own single-object/plain-`.gcode` cases. Full
      `testDebugUnitTest`/`lintDebug`/`assembleDebug` gate and the
      **entire** device-test suite re-run clean on Razr 2026
      (`ZP22235MHM`) only, via direct `adb shell am instrument` - same
      discipline as WO-22, the Razr 2023 left untouched throughout.
    - **Not built this entry, real and explicit**: no real Prusa Link or
      Bambu Lab hardware exists to verify either upload path against a
      physical printer (the owner has neither) - both are verified as
      far as real contract-tier (PrusaLink, no owned hardware - M7's own
      documentation-tier standard) and structural on-device (Bambu)
      checks can go without it. Phase 6's own acceptance criterion (the
      same Prepare→Print flow working identically across all vendors) is
      now met for all four integrations this app has - Klipper/Snapmaker
      via Moonraker, Bambu Lab via its bundle+FTPS/MQTT path, Prusa Link
      via its own upload+print endpoint.
21. **WO-24 — Phase 7 of the Consumer Slicer Plan, owner-directed 2026-09-22
    ("continue").** Closes the real control gaps the audit (§2.7) found:
    "No jog/movement controls, no filament load/unload command, no
    bed-leveling *trigger* ... found anywhere in the control surface."
    All four are Moonraker/Klipper-only (`PrinterCapabilities.supportsJog`/
    `supportsBedLevelingTrigger`/`supportsTimelapseTrigger`/
    `supportsFilamentLoadUnload`, now `true` for `GENERIC_KLIPPER`/
    `SNAPMAKER_U1_PAXX`, still `false` for Bambu/Prusa Link - neither
    vendor's real transport/API has an equivalent, confirmed by reading
    Prusa Link's own published `openapi.yaml` directly rather than
    assumed) - and each is further gated live per-printer where a static
    transport flag alone would risk a dead button (§20).
    - **Jog** (`JogPanel.kt`, new): real relative-move G-code
      (`G91`/`G1`/`G90`) plus `G28` homing, sent via
      `printer/gcode/script` - the same mechanism Console.kt's raw-command
      entry and `Moonraker.macro()` already use, not a new protocol.
      Deliberately skips this codebase's usual review-then-confirm
      two-step (HeaterPanel/LedPanel's own pattern) - those panels need it
      because they first fetch live server state to validate a request;
      jogging needs no such round trip, and every mainstream Klipper UI
      (Mainsail, Fluidd, KlipperScreen) treats it as immediate, repeated
      taps. The real safety gate is instead: only enabled on an idle,
      ready printer (`HeaterControls.idleStates`, the same real gate
      heating already uses), plus Klipper's own firmware-side kinematic
      limits rejecting an out-of-range move regardless.
    - **Bed-leveling trigger** (`BedMeshPanel.kt`): a real "Calibrate
      now" button sending `BED_MESH_CALIBRATE` (`klippy/extras/
      bed_mesh.py`, read directly - this command is only registered when
      `[bed_mesh]` is configured). Gated on a **new, real per-printer
      signal**, `MeshReader.supportsBedMeshCalibration()` (a real
      `printer/objects/list` query) - `meshStatus()` alone can't
      distinguish "not configured" from "configured but never
      calibrated" (Moonraker's `objects/query` just omits an
      unregistered object rather than erroring), so a static capability
      flag alone would have shown a dead button on any Klipper printer
      without `[bed_mesh]`. Keeps the existing review-then-confirm
      pattern (a real physical bed probe, worth the same protection
      heater/LED changes get).
    - **Timelapse trigger** (`TimelapsePanel.kt`): a real "Render now"
      button calling the real, documented `POST /machine/timelapse/
      render` endpoint (`mainsail-crew/moonraker-timelapse`'s own
      component source, read directly - confirmed real values:
      `started`/`skipped`/`running`/`error`, not a bare Moonraker "ok").
      **New `TimelapseReader.renderTimelapse()`** is called directly
      against the reader (like `timelapses()`/`meshStatus()` already
      are for reads) rather than through the generic `PrinterCommand`/
      `execute()` pipeline, because that pipeline's `command()` requires
      exactly the string `"ok"` back and this endpoint genuinely returns
      a richer JSON object - routing it through the generic path would
      have made every real success look like a failure.
    - **Filament load/unload**: no new backend at all - Klipper ships no
      built-in load/unload command, so real support means finding the
      printer's own live macro (`LOAD_FILAMENT`/`UNLOAD_FILAMENT`, the
      near-universal Klipper macro convention, or `M701`/`M702`) in its
      already-fetched macro catalog and reusing the exact same real
      macro-run pipeline (`MacroForm` → `MacroReviewPanel` → `execute`)
      the existing "Favorite macros" section already uses - a new
      "Filament" section in `MainActivity`'s Control tab that only
      appears when one of those real macro names is actually present.
    - **Verified**: `MoonrakerTest` gained real MockWebServer contract
      tests for `renderTimelapse()` (a real success response, a real
      non-exceptional `skipped` status, a missing-component 404
      surfacing as a real `ApiFailure`) and `supportsBedMeshCalibration()`
      (true only when `bed_mesh` is actually in the live object list).
      `PrinterCapabilitiesTest` updated for the four new flags.
      `JogPanelDeviceTest` (5 tests): the real default/changed step
      distance in the sent G-code, real `G28`/`G28 Z` homing scripts,
      and every jog control disabled with an explicit message while
      printing. `BedMeshPanelDeviceTest`/`TimelapsePanelDeviceTest`
      gained real trigger-button tests (hidden without the live
      capability signal; a confirmed tap sends the real command/calls
      the real reader method and shows its real result). Full
      `testDebugUnitTest`/`lintDebug`/`assembleDebug` gate and the
      **entire** device-test suite re-run clean on Razr 2026
      (`ZP22235MHM`) only, via direct `adb shell am instrument` - same
      discipline as WO-22/WO-23, the Razr 2023 left untouched throughout.
    - **Not built this entry, real and explicit**: no real Klipper/
      Snapmaker hardware was reachable from this session to verify any
      of the four against a physical printer (the owner's own earlier
      real-hardware verification in this codebase used a real Snapmaker
      U1/Centauri Carbon, not available to this session) - all four are
      verified as far as real MockWebServer contract tests and
      Compose device tests against fake readers can go without it.
      Filament sensor status (`hasFilamentSensor`, a separate, still
      real-`false`-everywhere capability - no printer integration here
      reports one) remains unbuilt, matching Phase 7's own scope (it
      names filament load/unload, not sensing).
22. **WO-25 — Phase 8 first real increment, owner-directed 2026-09-22
    ("Data model first" - explicitly chosen over three deeper, riskier
    options: Snapmaker U1 end-to-end, Prusa XL toolchanger, or a plan-
    only pass - after a scoping question given Phase 8's own flagged
    highest-risk status).** Real ToolSlot/MaterialAssignment data model
    (§11) plus the minimum real native plumbing to make per-object tool
    assignment possible later, deliberately with **no new user-facing
    UI this entry** - see "Not built" below for exactly why.
    - **Real per-printer-*model* tool count** (`ToolSlots.kt`,
      `parseToolCount`): reads the bundled `machine.json`'s own
      `extruder_colour` array length - the same real, authoritative
      OrcaSlicer signal `nozzle_diameter`/`extruder_offset` are always
      kept in lockstep with. Confirms `slicer_profiles/snapmaker_u1/
      machine.json` already declares 4 real extruders; every other
      bundled profile declares 1. **Corrects this plan's own §11 model**
      (see the 2026-09-22 correction note added there in this same
      session, prompted by the owner pointing out Prusa's real XL
      toolchanger): tool count must be resolved per bundled printer
      *model*, never assumed from `PrinterKind`/vendor - `PRUSA_LINK`
      alone already spans single-extruder (MK4/MK3.9/MINI) and
      5-toolhead (XL) real hardware, though no bundled XL profile
      exists yet to actually resolve a real 5 for it (a real, disclosed
      gap, not silently assumed).
    - **Real schema**: `ProjectObject` gains `toolSlotIndex: Int?`
      (Room migration 2→3, a real `ALTER TABLE` - same discipline
      `MIGRATION_1_2` already established, no destructive fallback).
      `ProjectViewModel.setObjectMaterial(objectId, material,
      toolSlotIndex)` - genuine per-object assignment, independent of
      `setProjectMaterial`'s existing "keep every object in lockstep"
      behavior (unchanged, still what every single-extruder project
      correctly wants).
    - **Real native plumbing, empirically verified NOT sufficient alone**:
      `engine::slice_multi_object`/`nativeSliceMultiObject` gained a
      per-object `tool_index` parameter that sets the real OrcaSlicer
      per-object `"extruder"` config option (`ModelObject::config.
      set("extruder", N)` before `Model::add_object()` - the same
      mechanism the desktop GUI's own "Set extruder" uses, confirmed by
      reading `PrintConfig.cpp`/`Model.hpp`/`Model.cpp` directly, not
      assumed). **Real finding, not guessed**: this alone produces zero
      observable difference in the sliced G-code today. Diffing two real
      slices of the same two objects on the Snapmaker U1 profile (the
      one bundled machine.json with >1 real extruder) - one with tool
      indices `[1,2]`, one with `[0,0]` - showed no difference beyond
      filenames baked into comments. Root cause, also confirmed by
      reading the bundled asset directly: `slicer_profiles/snapmaker_u1/
      filament.json` configures exactly one real filament slot (every
      `filament_*` array has length 1, no `filament_colour` key at all)
      despite its own machine.json declaring 4 - OrcaSlicer's per-object
      `"extruder"` selects *which configured filament slot* prints an
      object, and only one is ever configured here, so every request
      normalizes to that same slot. A real multi-slot filament config is
      separate, larger, not-yet-built work. The parameter is kept
      (harmless - `tool_index = 0`, unchanged for every existing caller,
      is exactly today's behavior) as real, correct groundwork for that
      later increment, not removed and not pretended to already work.
    - **Deliberately no UI this entry**: given the native finding above,
      surfacing a per-object material/tool picker now - even gated to
      the one printer with >1 declared extruder - would be exactly the
      "looks real, does nothing" trap this codebase treats as a hard
      line (§9). The next Phase 8 increment (Snapmaker U1 or Prusa XL
      end-to-end, an explicit option this session declined for now) is
      where a real multi-slot filament config gets built and a picker
      can honestly ship.
    - **Verified**: `ToolSlotsTest` (6 unit tests - real tool count
      against every bundled `machine.json`, the real Snapmaker U1 4/
      everyone-else 1 split, a safe fallback-to-1 for missing/malformed
      data, `toolSlotsFor`'s own real capability mapping).
      `ToolAssignmentSlicingDeviceTest` (3 device tests) proves the new
      native parameter is real and harmless - doesn't crash, doesn't
      drop an object, doesn't corrupt single-tool slicing, and a
      mismatched array length fails with a real exception - while
      explicitly *not* asserting a G-code difference it doesn't
      actually produce yet (see its own header comment for the full
      evidence trail). Full `testDebugUnitTest`/`lintDebug`/
      `assembleDebug` gate and the **entire** device-test suite re-run
      clean on Razr 2026 (`ZP22235MHM`) only, same discipline as
      WO-22 through WO-24.
    - **Not built this entry, real and explicit**: no per-object
      material/tool-assignment UI (see above); no multi-slot filament
      config for Snapmaker U1 or any other profile; no Prusa XL bundled
      profile (still just `PRUSA_GENERIC`, single-extruder); purge/flush
      estimation and toolchange visualization (Phase 8's own stated
      Objective, both genuinely downstream of a working multi-tool
      slice existing first); no real Snapmaker U1/Prusa XL/Bambu AMS
      hardware exists to verify any of this against a physical printer
      (the owner has none of the three).
23. **WO-26 — Phase 8 breakthrough, same session continuation ("continue")
    following WO-25.** Root-caused and fixed the exact real gap WO-25
    documented ("this alone does NOT yet produce a differentiated
    tool-change") - a genuine per-object multi-tool slice against the
    real bundled Snapmaker U1 profile now works, verified end to end,
    not assumed.
    - **Real root cause #1**: the generic per-object `"extruder"` config
      key (the same one the desktop GUI's own "Set extruder" writes)
      reached `ModelObject::config` correctly (confirmed via a temporary
      `__android_log_print` diagnostic, since removed) but had zero
      effect on the sliced G-code. Reading `PrintApply.cpp`/
      `PrintObject.cpp` directly found why: `region_config_from_model_
      volume()` - the real function building the per-region config
      GCode generation actually reads tool selection from - only looks
      at six concrete per-feature filament-id keys
      (`outer_wall_filament_id`/`inner_wall_filament_id`/
      `sparse_infill_filament_id`/`internal_solid_filament_id`/
      `top_surface_filament_id`/`bottom_surface_filament_id`).
      `DynamicPrintConfig::normalize_fdm()`, the real function that
      would normally translate `"extruder"` into those six keys, is
      commented out in this vendored engine's own `PrintApply.cpp` -
      confirmed by reading it, not assumed. **Fixed**: `engine::
      slice_multi_object` now sets those six keys directly (reproducing
      `normalize_fdm`'s own real behavior by hand) instead of the
      generic `"extruder"` key.
    - **Real root cause #2**: even with those keys set, every requested
      tool silently clamped back to 1
      (`PrintObject.cpp::clamp_feature_filament_to_valid`), because
      libslic3r computes how many extruders *really* exist from
      `filament_diameter`'s own array length (`PrintApply.cpp`: `size_t
      num_extruders = m_config.filament_diameter.size()`) - not
      `machine.json`'s `nozzle_diameter`/`extruder_colour` (which only
      bound how many *could* exist, per `ToolSlots.kt`'s own
      `parseToolCount`). The bundled `snapmaker_u1/filament.json` only
      ever declares one real `filament_diameter` entry. **Fixed by a
      caller-side config override**, not a further native change:
      `config_overrides` can already set `filament_diameter` (and the
      other per-slot keys) to a real 4-entry array via the existing
      generic mechanism.
    - **`tool_index`'s real convention corrected and documented**: it is
      OrcaSlicer's own 1-based filament/extruder identity (1 = the
      first real slot → `T0`, 2 → `T1`, ...), not 0-based - confirmed
      empirically (requesting tool 1/tool 2 for two objects produced
      real `T0`/`T1` in the sliced output, not `T1`/`T2`). `0` remains
      the "unassigned, printer default" sentinel.
    - **Verified end to end against the real bundled Snapmaker U1
      profile** (`ToolAssignmentSlicingDeviceTest`, rewritten - 4 device
      tests): two objects assigned to different real tool slots, with a
      real 4-slot filament config override (`filament_diameter`/
      `filament_colour`/`filament_type`/`nozzle_temperature`/
      `nozzle_temperature_initial_layer`), produce a genuine `T1`
      tool-change command in the sliced G-code; the same two objects
      assigned to the *same* slot produce no `T1` (a real negative
      control, proving the tool change tracks the assignment rather
      than always appearing once a multi-slot config exists); the
      existing default (`tool_index=0`, no multi-slot override) still
      slices ordinary real G-code unchanged; a mismatched tool-index
      array length still fails with a real exception. Full
      `testDebugUnitTest`/`lintDebug`/`assembleDebug` gate and the
      **entire** device-test suite re-run clean on Razr 2026
      (`ZP22235MHM`) only, same discipline as every prior WO this
      session.
    - **Not built this entry, real and explicit**: the real 4-slot
      filament-config recipe above is hardcoded in the device test, not
      yet generalized into production Kotlin that builds it from an
      arbitrary `MaterialAssignment` list - doing that safely means
      replicating *every* other `filament_*` array key from the base
      bundled profile (not just the five varied here) to avoid an
      out-of-range read elsewhere in libslic3r for a key this entry
      didn't think to override, real, careful work not rushed into this
      same pass. Still no per-object assignment UI, no purge/flush
      estimation, no toolchange visualization, no Prusa XL bundled
      profile, and no real Snapmaker U1/Prusa XL/Bambu AMS hardware to
      verify any of this against a physical printer - all unchanged
      from WO-25's own list.
24. **WO-27 — Phase 8, same session continuation ("continue") following
    WO-26.** Generalizes WO-26's hardcoded 4-slot recipe into real
    production code driven by an arbitrary `MaterialAssignment` list,
    correcting WO-26's own "not built" note along the way: replicating
    *every* `filament_*` array key turned out not to be necessary -
    confirmed by reading `Config.hpp` directly, `ConfigOptionVector::
    get_at(i)` clamps to index 0 (`values.front()`) whenever `i` is past
    the array's real length, rather than reading out of bounds, so any
    key this code doesn't explicitly override safely and correctly
    falls back to slot 1's own bundled value for every other slot - only
    `filament_diameter` (the structural, extruder-count-determining key)
    and the real identity keys (colour/type/temperature) need it.
    - **`MultiToolFilamentConfig.overridesFor`** (new): the real,
      general form of WO-26's proven recipe - takes a real base filament
      diameter and a `List<MaterialProfile?>` (index i = real tool slot
      i+1, null = unassigned, falls back to a real fallback material) and
      builds the same five override keys WO-26 verified end to end. A
      material with no declared `tempNozzleC` (a real, valid case - see
      `MaterialProfile`'s own header comment) falls back to the fallback
      material's own temperature, then a safe PLA-family default, never
      a zero/invalid value.
    - **`ToolSlots.kt` gains `parseBaseFilamentDiameter`**: the bundled
      `filament.json`'s own real `filament_diameter` value (not a
      hardcoded "1.75" assumption), the base value
      `MultiToolFilamentConfig` replicates into the real N-entry
      override.
    - **`SlicingCoordinator.sliceProject` wired end to end**: a new
      `slotMaterials` parameter (empty by default - unchanged behavior
      for every existing caller) builds the real multi-slot filament
      config from `resolved.profilePaths`' own already-materialized
      filament file (no second profile-pack lookup) and merges it into
      the slice's overrides. **Real bug caught building this, not
      guessed**: `slotMaterials` must have exactly one entry per the
      target's own real declared tool count (`ToolSlots.kt`'s
      `parseToolCount`) - an early test passing only 2 entries for a
      4-extruder Snapmaker U1 target hit a genuine libslic3r validation
      failure ("Flush volumes matrix do not match to the correct
      size!"), a real config inconsistency between the filament array's
      length and the machine's own declared extruder count. Fixed with
      an explicit, actionable `require()` check in `sliceProject` itself
      rather than letting that raw engine error surface to a caller who
      passed the wrong slot count.
    - **Verified**: `MultiToolFilamentConfigTest` (6 unit tests - the
      real override map built from real materials, null-slot/no-color/
      no-temperature fallback behavior, whole-number diameter
      formatting) and new `ToolSlotsTest` cases for
      `parseBaseFilamentDiameter` (every bundled `filament.json`, the
      real Snapmaker U1 1.75mm value, safe fallback on missing/malformed
      data). `SlicingCoordinatorDeviceTest` gained a real end-to-end
      proof (`sliceProjectWithRealSlotMaterialsProducesARealToolChange`)
      that `sliceProject`'s own `slotMaterials` parameter - not a
      hand-built override map - produces a genuine `T1` tool-change
      against the real bundled Snapmaker U1 profile. Full
      `testDebugUnitTest`/`lintDebug`/`assembleDebug` gate and the
      **entire** device-test suite re-run clean on Razr 2026
      (`ZP22235MHM`) only, same discipline as every prior WO this
      session.
    - **Not built this entry, real and explicit**: still no per-object
      assignment UI (`ProjectEditorScreen` doesn't yet let an owner pick
      a material *and* a tool slot per object - it still only offers the
      single project-wide material picker WO-19 built); no purge/flush
      estimation; no toolchange visualization; no Prusa XL bundled
      profile; no real Snapmaker U1/Prusa XL/Bambu AMS hardware to
      verify any of this against a physical printer - all unchanged from
      WO-25/WO-26's own lists. The real slicing mechanism is now fully
      proven and production-ready; what remains is exposing it in the UI.
25. **WO-28 — Phase 8, same session continuation ("continue") following
    WO-27.** Builds the real per-object assignment UI WO-27's own "not
    built" note named - the last piece of Phase 8's own stated
    acceptance criterion ("assign 2+ colors to a real model").
    - **`ProjectEditorScreen` real UI, gated on the target's own real
      tool count** (`toolCountFor`, loaded alongside `bedShape`/
      `machineLimits` in the same existing `LaunchedEffect`): for a
      single-tool target (every printer today except Snapmaker U1),
      nothing changes - the existing single, project-wide material
      picker stays exactly as it was. For a genuinely multi-tool target,
      each row in "Objects on this plate" gains its own real material +
      tool-slot summary and an "Assign" button opening a real picker
      (tool-slot chip row, 1..toolCount, plus the same Bundled/Spoolman
      material list the single-material picker already loads) that
      calls `ProjectViewModel.setObjectMaterial` (WO-25's own genuine
      per-object method, not `setProjectMaterial`'s lockstep one) - the
      single project-wide "Material" row is replaced with an explanatory
      hint instead of shown alongside a control that would now be
      misleading.
    - **`ToolSlots.kt` gains `multiToolSliceInputsFor`**: the real,
      pure logic building `sliceProject`'s own `toolSlotIndices`/
      `slotMaterials` parameters from a project's real `ProjectObject`
      list - extracted out of the composable specifically so it's
      directly unit-testable without a Compose test harness (this
      screen had none before this entry). An unassigned object
      defaults to tool 1 (every real object must print on *some* tool);
      a tool slot nothing was assigned to gets a `null` material, which
      `sliceProject`/`MultiToolFilamentConfig` already fall back to the
      printer's default for, not a crash.
    - **`startSlicing()` real bug avoided, not hit**: the tool
      assignments and the model files handed to `sliceProject` are now
      built from the *same* already-filtered `(ProjectObject, File,
      ModelTransform)` triple list, not two independently-filtered
      lists that could desync if an object's URI ever failed to parse -
      caught during design, not live.
    - **Verified**: `ToolSlotsTest` gained 6 new cases for
      `multiToolSliceInputsFor` (single-tool target produces no
      assignments at all; real index-parallel multi-tool output;
      unassigned-object-defaults-to-tool-1; an unassigned slot's `null`
      material; an empty object list produces empty/null output, not an
      exception). Full `testDebugUnitTest`/`lintDebug`/`assembleDebug`
      gate and the **entire** device-test suite re-run clean on Razr
      2026 (`ZP22235MHM`) only, same discipline as every prior WO this
      session.
    - **Not built this entry, real and explicit**: no dedicated Compose
      device test for `ProjectEditorScreen` itself (this screen had no
      existing UI test harness before this entry, and building one from
      scratch - fake `ScreenState`/`execute`/file providers - is real,
      separate work not rushed into this same pass; the pure logic
      backing the new UI is unit-tested instead, per this entry's own
      `multiToolSliceInputsFor` extraction). No purge/flush estimation,
      no toolchange visualization, no Prusa XL bundled profile, no real
      Snapmaker U1/Prusa XL/Bambu AMS hardware to verify any of this
      against a physical printer - all unchanged from prior entries.
      **Phase 8's own acceptance criterion is now structurally
      satisfiable end to end** (assign 2+ real tool/material
      combinations in the real UI, slice, get real distinct tool-change
      G-code) but has not been walked through manually on a real device
      screen this entry - the underlying mechanism (WO-25/26/27) and
      this new UI's own logic (this entry) are each independently
      verified, not yet exercised together as one live user flow.
26. **WO-29 — Phase 8, same session continuation, closes WO-28's own
    disclosed device-test gap.** WO-28 shipped the per-object
    material+tool assignment UI with only a unit-tested pure function
    (`multiToolSliceInputsFor`) behind it - no device test of
    `ProjectEditorScreen` itself. Adds `ProjectEditorScreenDeviceTest.kt`
    (4 real device tests), seeding real projects into the screen's own
    real singleton `AppDatabase` (no injection point exists here,
    matching `ProjectViewModelDeviceTest`'s real-Room discipline but
    against the actual production database):
    - a control test proving the existing single-tool "Choose a
      material" dialog still opens correctly (baseline for the newer
      per-object dialog)
    - single-tool targets show only the shared material picker, no
      per-object UI
    - multi-tool targets (Snapmaker U1) show per-object Assign controls
      instead, one per object on the plate
    - assigning a tool+material to one object updates only that
      object's row, leaving a second, untouched object on its default
    - **Two real Compose-testing issues found and fixed along the way**:
      off-screen rows need `performScrollTo()` before `performClick()`
      (coordinate-based dispatch requires the target within the visible
      viewport); a second `AlertDialog`'s own popup window is invisible
      to `onRoot()`/`onNodeWith*` without `useUnmergedTree = true` - this
      cost the most debugging time, since the dialog being open but its
      buttons receiving clicks outside their actual visible bounds
      looked identical to the dialog silently not opening.
    - **Closes Phase 8's own remaining disclosed gap from WO-28**: the
      per-object assign flow is now exercised end to end on a real
      device screen (Razr 2026), not just proven independently at the
      logic layer and the native-slicing layer. Still not exercised
      against real multi-tool printer hardware (the owner has none of
      the three: Snapmaker U1 with multiple loaded materials, Prusa XL,
      or Bambu AMS) and purge/flush estimation and toolchange
      visualization remain unbuilt - unchanged from WO-25 through
      WO-28's own lists.
    - **Verified**: full `testDebugUnitTest`/`lintDebug`/`assembleDebug`
      gate and the complete device-test suite (194 tests) re-run clean
      on Razr 2026 (`ZP22235MHM`).
27. **WO-30 — Owner request 2026-09-23: the Prepare tab becomes the real
    in-app slicer, and the plate gets a desktop-style toolbar.** Owner
    reference: "Desktop-like power adapted to mobile" (EasyPrint-style
    screenshots), refined through the session to "make these our own, do
    not blatantly copy them", "keep the bottom navigation tabs", "the
    model chooser should be in the actual slicer window, not a separate
    pill that starts the process", and finally "the Prepare tab should
    default right to the in-app slicer" with no landing pill at all.
    - **`MainActivity.kt` real navigation change**: the Prepare tab used
      to run the system file picker before any slicer UI existed
      (pick-model-to-slice -> pickedModel -> a single-object
      `SliceAndPrintPanel` share-intent reuse). Replaced entirely:
      entering the Prepare tab now auto-creates and opens a real project
      (`ProjectEditorScreen`, the same multi-object editor Files >
      Projects already uses) immediately - "Add models" inside that
      screen is the real in-window model chooser. Closing it returns to
      Home rather than instantly reopening a fresh one. The old
      `pickModel`/`pickedModel` wiring is now dead and removed. **The
      share-intent entry point (another app sending a model to this
      one) is unchanged** - it still opens the single-object
      `SliceAndPrintPanel` directly, separate from the Prepare tab.
    - **`ProjectEditorScreen.kt` real toolbar**: the plate gained a
      left-hand toolbar (Duplicate, Hide, Reset, Remove, Layout, then
      Move/Rotate added in a follow-up below) next to the real 3D
      `ProjectWorkspace`, a live mm dimensions readout for the selected
      object, and a "Models N/N" switcher - this app's own take, not a
      copy of the reference's icon set or layout. Move/Scale/Rotate
      stay as the existing combined drag/pinch/twist gesture rather
      than splitting into three modes, matching `ModelViewer.kt`'s
      established Select/Transform pattern. Hide is a local, UI-only
      visibility toggle - a hidden object still slices exactly as if
      shown, an explicit scope choice, not a half-built
      exclude-from-slice feature. A later follow-up (below) split the
      EDIT stage into Model/Settings/Printer tabs instead of one long
      scrolling page, after owner visual-design feedback.
    - **Real, pre-existing pinch/rotate bug found and fixed** (owner
      report, live on the Razr 2026: "it allows me to pinch momentarily
      then jumps back to full size. Attempted rotation has no effect."):
      `detectTransformGestures`' own pan/zoom/rotation callback
      parameters are incremental since the *previous* callback, not
      cumulative since the gesture started (confirmed against Compose
      foundation's own `TransformGestureDetector.kt`); `ProjectWorkspace`
      was re-reading its "current" transform from a `remember`ed
      parameter frozen for the whole gesture, so every callback
      discarded all prior deltas. Fixed with a local running transform
      accumulated across callback invocations. Two new
      `ProjectWorkspaceDeviceTest.kt` tests reproduce this with real
      synthetic multi-touch and assert the real cumulative result
      (~4.7x scale, ~90 degrees) - verified failing against the pre-fix
      code (scale 1.04, rotation 4.5 degrees, the exact reported
      symptom) before confirming the fix.
    - **Real inverted-rotation bug found and fixed** (owner report, live
      on the Razr 2026: "rotation is rotating the opposite direction
      than intended"): a genuine sign mismatch between
      `detectTransformGestures`' screen-space rotation convention
      (positive = clockwise) and `Matrix.rotateM`'s OpenGL right-hand
      rule (positive = counterclockwise, as viewed from this plate's
      default camera orbit) - fixed by subtracting instead of adding.
    - **Real empty-plate grid bug found and fixed**: the 3D viewport
      only drew the bed grid once an object existed - `onSurfaceCreated`
      only re-armed `pendingObjects` for a non-empty plate, so the
      empty-plate grid was also lost across any pause/resume
      (backgrounding tears down and recreates the GL context). Fixed by
      keying that re-arm on `bedShape` being known too.
    - **Move/Rotate toolbar toggle** (owner request, live on the Razr
      2026: "I should be able to rotate the model just by swiping around
      the box, not having to necessarily pinch and rotate"): a real
      `WorkspaceInteractionMode` (MOVE, the existing default; ROTATE,
      new) gesture-mode parameter - in ROTATE mode a one-finger drag's
      horizontal distance becomes a rotation instead of an offset
      change; a genuine two-finger twist still works in both modes
      (they never conflict). Move/Rotate join the plate toolbar as a
      real persistent mode toggle with its own highlighted "active"
      state.
    - **Verified**: `PlateToolbarDeviceTest.kt` (Duplicate/Hide/Reset/
      model-switcher, real Room read-back, not just UI assertion);
      `ProjectWorkspaceDeviceTest.kt` gained cases for the cumulative
      pinch/rotate fix and the new ROTATE-mode one-finger swipe. Full
      `testDebugUnitTest`/`lintDebug`/`assembleDebug` gate passed
      throughout; the full device suite reached 200 tests passing on
      the Razr 2026 (`ZP22235MHM`) as of the inverted-rotation fix. The
      final follow-up (Move/Rotate toggle) verified its specific
      new/changed tests (`PlateToolbarDeviceTest` 4/4,
      `ProjectWorkspaceDeviceTest` 6/6) passing before the owner needed
      to disconnect USB/ADB for the day - **the full ~200-test device
      suite was mid-run and did not finish**; re-running it fully is the
      first step before further work builds on top of this entry.
      Manually walked the Prepare -> editor -> Add Models -> toolbar
      flow, the Model/Settings/Printer tabs, and the pause/resume grid
      fix live on that device.
28. **WO-31 — R8 minification and resource shrinking for release builds
    (commit `cfb6466`, 2026-09-23).**
    - **Real change**: `app/build.gradle.kts` enables minification and
      resource shrinking for the release variant only; `app/proguard-rules.pro`
      gains a JNI keep rule for `org.orcaslicer.engine.NativeEngine` (its
      names are baked into static JNI symbols in the native library) plus
      `-dontwarn` groups for optional dependencies confirmed absent.
      Debug and test variants are unchanged.
    - **Measured** (release APK): installed size 109.9 MB -> 73.6 MB;
      download size 47.1 MB -> 34.8 MB; dex 41.5 MB (5 files) -> 5.9 MB
      (1 file). Native libs unchanged: `libslic3rengine.so` is 57.5 MB,
      now about 78% of the APK.
    - **Verified**: the minified build launches; a native mesh-preview JNI
      call works; a slice reaches the native engine.
    - **Not verified on the minified build**: Bambu MQTT/TLS
      (hivemq/Netty/BouncyCastle); `EncryptedSharedPreferences` (Tink);
      Bespok3d SSH/signature checks (jsch/bcpg); Room; Glance.
    - **Not built this entry**: the instrumented suite cannot run against a
      minified build (R8 strips Kotlin stdlib classes the test harness
      needs in the shared process), so these need a manual smoke test;
      no signing config exists yet.
29. **WO-32 — Fix false "Unsupported coordinate magnitude." on UUID-named
    Klipper objects (commit `ffd41db`, 2026-09-23).**
    - **Real bug** (owner-reported, Razr 2026): the sliced-result screen
      showed the error instead of the layer preview/stats. **Root cause**:
      the project editor stores models under random UUID filenames and the
      engine names objects after them, so Klipper G-code carries
      `EXCLUDE_OBJECT_DEFINE NAME=<uuid>.stl_id_0_copy_0` lines;
      `GcodePreview.parse` tokenized parameters on every line, so a UUID
      tail like `b36836200` parsed as B=36836200 and tripped the
      10,000,000 sanity bound.
    - **Fix**: only G0-G3 and G92 lines parse parameters.
    - **Verified**: `M2Test` (2 new tests) and `SlicingProfilePacksDeviceTest`
      (end to end); full device suite 202/202 on the Razr 2026.
30. **WO-33 — Architecture decisions: Desktop, Web and shared modules
    (owner-approved 2026-09-23, from an architecture audit).** Decisions
    only; nothing here is built. Detail: `CONSUMER_SLICER_PLAN.md` §6b and
    Phases 9S, 9a-9g, 13, 14.
    - **Decided, owner-approved 2026-09-23:**
      1. Platform family: Android + Windows desktop + Linux desktop + Web.
         iOS and macOS remain deferred. Web is a committed production
         target, not optional.
      2. Web: a PWA served by an owner-operated "Nozzle Engine Service".
         The same headless engine and printer adapters ship (a) embedded in
         the Desktop app as its local agent (loopback by default, LAN only
         after pairing) and (b) as a self-hostable headless service
         (container/systemd). Slicing and printer protocols run in the
         service; the browser is UI plus offline cache; the service serves
         its own PWA same-origin (avoids mixed content, CORS and Local
         Network Access problems). Printer credentials stay in the
         service/agent and never reach a central Nozzle service.
         **Non-goal:** no Nozzle-hosted multi-tenant service and no Nozzle
         account. Reason (audit): browsers cannot reach Bambu printers
         directly (MQTT 8883, implicit FTPS 990 and camera port 6000 are raw
         TCP), Moonraker needs `cors_domains` plus Local Network Access
         approval, PrusaLink CORS is unverified.
      3. WebAssembly slicing is not the plan: a time-boxed, gated spike
         only. The dependency chain (CGAL/GMP/MPFR, OCCT 7.6, OpenCV 4.6,
         Boost 1.86 incl. Locale, oneTBB with about 234 parallel call sites)
         is untested in WASM; community ports (OrcaWasm, orcaslicer-wasm)
         exist but their claims are unverified. It becomes an optional
         "small local slice in the browser" tier only if the spike passes:
         build with `emcmake`; stub OCCT/OpenCV/OpenVDB/assimp in turn;
         cross-build Boost/CGAL/TBB or shim; byte-compare G-code with
         Android on real models; measure peak memory vs the wasm32 4 GiB
         cap; verify Worker-termination cancellation; COOP/COEP hosting in
         three browsers.
      4. Desktop: Kotlin Multiplatform + Compose Desktop shell, LWJGL/OpenGL
         3.3 viewport behind a renderer interface, and the slicer engine as
         an isolated worker process behind a plain C API (crash isolation,
         hard-kill cancellation). The Android JNI bridge stays as a second
         thin front-end over the same C++ engine core. Upstream OrcaSlicer's
         wxWidgets GUI is not embedded.
      5. Delivery order after Android Phase 9: shared-module extraction ->
         Engine Service + Linux Desktop -> Web v1 -> Windows Desktop (owner
         chose Web before Windows).
      6. New phases (existing numbering kept; the set is now Phases 0-14
         plus 9S): Phase 9S shared `:domain`/`:transport` extraction plus a
         command/undo model, before multi-plate work; 9a settings
         tiers/search/custom+inheriting profiles/compare; 9b multi-plate +
         undo/redo + project export/import; 9c cut/place-on-face/measure/
         mirror/auto-orient; 9d painting in the project editor + modifiers +
         blockers + seam painting; 9e calibration workflow; 9f slice
         cancel/progress/foreground service/memory handling; 9g release
         engineering (signing, minified-build smoke test, SBOM,
         attestations, engine pinning); Phase 14 Web (14a service API +
         auth + pairing, 14b PWA shell, 14c prepare + 3D viewport, 14d
         preview + device, 14e offline, 14x WASM spike). Phase 11 sync is
         hosted by the self-hostable Engine Service. Phase 13 gains 13a
         engine C API + worker (Linux first; early Windows MSVC
         headless-build spike), 13b shell + viewport, 13c workstation
         features, 13d packaging/signing/attestations.
      7. Engine provenance: pin the engine source (exact Orca revision plus
         the committed patch). **Approved TODO, not done.** Today the engine
         is built from an unreleased upstream nightly (`824b216f`,
         `version.inc` 2.5.0-dev) with the Android patch uncommitted, in a
         directory with no git remote, and the app's bridge sources
         (`app/src/main/cpp/bridge/slic3r_engine.cpp`) have diverged from
         the engine repo's `jni/` copy (the app copy is authoritative).
         Also approved, not done: commission a qualified legal review (AGPL
         obligations across Android/Desktop/WASM/service; LGPL static
         linking of GMP/MPFR/OCCT; CGAL GPL/commercial parts; mcut dual
         GPL/commercial; libigl copyleft; whether the OpenSSL 1.1.1w line is
         end-of-life - to verify). No legal conclusions are recorded here.
    - **OPEN, not decided:** Web UI technology (Compose Multiplatform Web vs
      TypeScript; needs a spike, Web-target stability not verified);
      whether/how much time to spend on the WASM spike; Play distribution vs
      offline-first for the 57.5 MB engine; Phase 11 sync conflict policy;
      hardware for physical acceptance (Prusa XL, Bambu AMS, MMU, CFS) or an
      explicit "unverified" label; signing identity (Azure Artifact Signing
      eligibility rules).
    - **Not built this entry:** everything above. Docs only; no source,
      build or test changes.
31. **WO-34 - Phase 9 (advanced slicing) built, 2026-09-23 (commits a08aa2d through the 9g commit).**
    Owner asked for Phase 9 to be completed; every sub-phase below was built and device-tested on the Razr 2026
    (ZP22235MHM) with the full suite green after each (204 -> 238 tests, all passing at each commit).
    - **9f cancel/progress/foreground/memory** (`a08aa2d`, `a66ec82`, `676f2a8`): cooperative cancel of a running slice
      (`nativeCancelSlice`, `CancellationException`, partial output removed, `SliceOutcome.Cancelled`), polled progress
      (`nativeSliceProgress`), one slice at a time, editor progress bar + Cancel, `SliceService` foreground service, native
      out-of-memory reported as an actionable message. A real crash was found and fixed on the way (stopping the service
      before `startForeground` ran killed the process). Memory-pressure recovery beyond the message is not built.
    - **9a advanced settings** (`d7b091d`): `SettingsCatalog` of 27 real process keys (tiers Basic/Advanced/Expert,
      search, validated ranges, help text), saved custom profiles and compare. `SettingsCatalogDeviceTest` slices every
      catalog value through the real engine; it found and fixed two over-wide ranges (line width, first layer height, both
      nozzle-relative, documented as assuming a 0.4 mm nozzle). Per-filament/machine vector keys are not in the catalog.
    - **9S undo** (`1d8daad`): snapshot undo/redo for every project edit (drag coalescing, 50 steps). Removing an object no
      longer deletes its model file at once (undo needs it, and a duplicate shares the original's file - the old behaviour
      would have broken the duplicate); unreferenced files are pruned when a project is reopened. Shared-module extraction (the
      other half of 9S): new Gradle JVM modules `:domain` (40 files: slicing settings, mesh editing, G-code parsing, printer
      capability/control models, the printer-service interface) and `:transport` (21 files: Moonraker, Prusa Link, Bambu
      MQTT/FTPS/camera, Bespok3d SSH and client), same package names so no imports changed; a `verifyNoAndroidImports`
      check (run in CI) keeps both Android-free. Splits needed: PrinterService abstractions out of Moonraker.kt,
      MoonrakerRules, NozzleLog (logging sink), MeshGeometry/ModelTransform/OverlayGroup data classes, MeshEdit.cut as an app
      extension (native), `internal` widened to public across the module boundary. This is a plain-JVM extraction, not
      Kotlin Multiplatform: nothing was compiled for a non-Android target yet, so JDK-only APIs the Android runtime lacks
      would not be caught by these modules' own compile.
    - **9b multi-plate + export/import** (`95c42c7`): plates table (Room 3->4), plate chips, move object to plate, slice the
      active plate, `.nozzleproj` zip export/import with zip-slip/size/extension/number validation.
    - **9c mirror/lay-on-face/auto-orient/measure/cut** (`974eeb9`): edits are baked into a new STL in the project (so preview and
      slicer agree and undo swaps the file); native capped cut via libslic3r `cut_mesh` (volumes of the halves add up to the
      original on device). Lay-flat and measure use a tap ray-cast against the real triangles.
    - **9d painting/modifiers/blockers** (`e64a18c`): support and seam paint strokes plus modifier/support-blocker/enforcer
      regions, stored per object in a bed-independent object frame (Room 4->5) and replayed natively at slice time. Device
      tests prove painted enforcers create support where none existed, a blocker removes it, a modifier changes walls, and a
      seam stroke moves the seam. A real bug was found: the preview loads on a default bed while the slice centres on the
      printer's bed, so world-frame coordinates were offset (fixed by using the object frame). Painted overlays and region
      outlines render on the model (screenshot verified).
    - **9e calibration** (`6447993`): temperature tower, pressure-advance tower (Klipper only), flow cube; G-code post-processing
      inserts the height-dependent commands; a project flag (Room 5->6) applies it after slicing. Not printed on real hardware.
    - **9g release engineering**: env-driven signing (no key in the repo), `releaseSmoke` build type, offline CycloneDX SBOM
      (`generateSbom`), engine pin (`engine/ENGINE_PIN.json`, `scripts/engine_pin.py verify`, patch copied into the repo),
      tag-triggered CI release job with build-provenance attestations (docs/RELEASE.md).
    - **Follow-up pass (same day, after the owner pointed out Phase 9 was not actually complete):** slice all plates (each
      plate's result kept, switchable in the review), drag-to-move and grow/shrink for regions, memory-pressure handling
      (refuse to start while Android reports low memory; detect and explain a slice killed on a previous run), and an in-app
      **self-check** (Settings > Run self-check) that exercises Room, Tink, OpenPGP, jsch, OkHttp, HiveMQ/Netty, the FTPS client
      and Glance against loopback/local data. Run against the fully obfuscated release APK it **found three real shipped-build
      bugs the debug build hid**: BouncyCastle lost its digests (OpenPGP package verification would have failed: "no such
      algorithm: SHA-512"), jsch lost its key-pair classes, and the HiveMQ/Netty client failed to initialise (Bambu MQTT would
      not have connected). Fixed with keep rules in `app/proguard-rules.pro`; all 9 checks now pass in the release build. Cost:
      release APK 77.4 MB -> 85.1 MB.
    - **Still not verified / not built:** the release CI job has never run and there is no real signing key (owner decision);
      nothing was printed on physical hardware; the self-check proves the libraries load and fail cleanly on loopback, not
      that a real Bambu/U1 session works; regions have no on-screen gizmo (drag to move, +/- buttons to resize, numeric dialog
      for exact values); memory handling cannot shrink native memory mid-slice, it only refuses to start under pressure and
      explains an interrupted slice afterwards; the suite against `releaseSmoke` (238 tests) predates the new keep rules.
32. **WO-35 - Phase 8 (multicolor / multimaterial) closed, 2026-09-23.**
    Built on top of WO-25 to WO-29 (tool slots, per-object assignment, real multi-tool slicing). Device-tested on the Razr 2026;
    full suite green.
    - **Toolchange visualization:** the G-code preview now tracks `T<n>` commands (`ToolChange`, per-segment `tool`, `toolsUsed`);
      the sliced preview colours every extrusion by the tool that printed it, with a legend and "N change(s) on this layer".
    - **Review stats:** toolchange count, per-tool grams, and an estimated purge waste, parsed from the real footer of a two-tool U1
      slice (`; total filament change`, `; filament used [g]`, `; flush_volumes_matrix`). `GcodeStats` also fixed a latent bug:
      "filament used [mm]" was read as the first tool only.
    - **Hardware-family model** (`MultiToolFamily`: single / independent tools / filament swap) and **material compatibility
      warnings** (`MaterialCompatibility`: PLA with ABS-class, flexible with rigid, nozzle and bed temperature spreads).
    - **Purge / prime-tower UI - and a real finding:** the engine ignores `enable_prime_tower=1` on the Snapmaker U1 profile (footer
      stays 0, no tower is generated; verified). Independent-tool machines therefore do not purge in this engine, so the tower and
      flush settings (`prime_tower_width`, `flush_multiplier`, `flush_into_*`) are only offered for the filament-swap family, the
      editor says why on a toolchanger, and the review shows an explanation instead of a purge estimate. The filament-swap path
      (switch, flush settings, estimate, bleed note) is unit-tested only: no bundled filament-swap multi-slot profile exists.
    - **Per-painted-region material:** a Material paint kind (a tool number per stroke, stored as code 10+tool) replayed natively
      into `mmu_segmentation_facets`. Device test: one cube assigned to tool 1 prints only `T0` normally; with a tool-2 stroke on its
      side the G-code gains `T1` and both tools extrude.
    - **Prusa XL 5T pack added (same day, after the owner asked why it was missing):** `slicer_profiles/prusa_xl_5t` (Prusa XL 5T 0.4
      nozzle / 0.20mm Speed @Prusa XL 5T 0.4 / Generic PLA @Prusa XL 5T), flattened by the new `scripts/flatten_orca_profile.py`, selectable
      as "Prusa XL (5 tools)" in the printer wizard and settings. Device-tested: 5 tools, 360 mm bed, a plain slice, and a two-tool slice
      whose G-code changes tools 100 times with both tools extruding. Three real problems found and fixed on the way: the profile's
      `extruder_colour` has one entry but `nozzle_diameter` has five (tool count now takes the larger); the profile ships no 5x5
      flush matrix, so a 5-slot slice failed validation (`MultiToolFilamentConfig` now always generates a square matrix for the real
      slot count); upstream declares `min_layer_height` as a plain string here, not an array (parser accepts both). Like the U1, the
      engine forces the prime tower off although the XL process profile enables it. Not checked against a physical XL.
    - **Still not done, and why:** nothing is verified against a physical XL or U1; no
      physical multi-tool print was run; Bambu AMS multi-material is not wired (the Bambu bundle path is single-material, and
      libslic3r's AMS handling is unverified here).

33. **WO-36 - Phase 10 (Discover / MyMiniFactory) built against the documented API, 2026-09-23.**
    Read the real contract first (`MyMiniFactory/api-documentation`: OpenAPI file and oauth2-instructions). Facts that shaped the
    design: browse/search/detail/files accept a developer **API key** (`key` query parameter), but `download_url` and
    `archive_download_url` are documented as **OAuth-only, "not with API key"**; for mobile clients MyMiniFactory prescribes the
    implicit grant followed by a "mobile login" exchange (needs the client's public `client_key`) and a refresh call.
    - **Built:** `MmfParser` (strict: https-only URLs without credentials, control characters stripped, lengths/counts capped, HTML
      fields ignored, license terms reported per the API and never guessed); `MyMiniFactoryClient` (search with every documented
      filter, detail, files; bounded responses; errors mapped to plain messages; the API key never appears in an error; downloads
      are https-only, size-capped, follow at most three redirects, and the OAuth token is sent only to `*.myminifactory.com`);
      `MyMiniFactoryOAuth` + `MmfAuthManager` (authorize URL with anti-forgery state, strict redirect parsing, mobile login, refresh
      before expiry, sign-out on a refused refresh, session kept when merely offline); `MmfArchive` (safe zip unpacking); a
      **Discover tab** (search, sort, remix/commercial/no-support filters, paging, license chips, detail with credit line and full
      license statements, file picker, sign-in/sign-out, key setup); `MmfImporter` (download, unpack, create a project, save the
      credit line - shown in the editor - Room 6->7); the `nozzleitall://mmf-auth` deep link; encrypted key/session storage
      (`MmfSettings`); a self-check entry for the client. Optional build-time keys: `MMF_API_KEY`, `MMF_CLIENT_KEY` (gradle property
      or environment, never committed); otherwise the user pastes their own in Discover.
    - **Verified:** 28 new unit tests (parser, client against MockWebServer, auth, archive) and 14 device tests (setup and key
      validation, results and license chips, sort/filter/paging requests, duplicate-page robustness, error display, detail
      credit/license, sign-in gating, a full download of a single STL plus a zip into a project with attribution and cleaned-up temp
      files, forged sign-in state rejected, tab and deep-link routing, manifest scope); all 10 self-checks pass on the obfuscated
      release build. Two real bugs fixed on the way: overlapping result pages crashed the list (now de-duplicated by id), and result
      cards / license lists were not merged for accessibility.
    - **Live check done later the same day (owner's API key, client `nozzle_it_all`):** search, sort, remix filter, paging (per_page up
      to 60), object detail, files, and bad-key (401) / missing-model (404) behaviour all match the docs; `MmfLiveDeviceTest` (6 tests, skipped
      unless the build has `MMF_API_KEY`) passes against the real service, including the real Discover screen. The real API differs
      from the OpenAPI file in two ways that were fixed: `files` is an object `{total_count, items}` (not an array), and deleted models
      still appear in results (dropped). Confirmed: with an API key only ~2% of files carry a `download_url` (20 of 868 sampled) and an
      unauthenticated download link returns 404, so downloads really do need the OAuth sign-in. `https://nozzleitall.com/mmf-auth` is
      deployed. **Still unverified: an actual sign-in** (needs the owner's MyMiniFactory login: the authorize endpoint accepts the client and
      shows its login page, but does not check the redirect address until after login), the mobile-login exchange, and a real file download.
    - **(Superseded) as first written - no live MyMiniFactory call had been made** (that needs a developer client only the owner can create):
      real response shapes beyond the OpenAPI file, the search query syntax, rate limits, what hosts `download_url` really points
      at, whether the sign-in redirect `nozzleitall://mmf-auth` is accepted (it must be registered on the client), and whether
      MyMiniFactory's terms allow shipping one shared API key in the app (the user-supplied-key path avoids that). The meaning of the
      `share` license term is an interpretation of the schema's one-line description. Paid ("store") models are marked but
      purchase is not handled; downloading may simply fail for them.
    - **Owner steps to make it live:** create a client at myminifactory.com/pages/for-developers (client key `nozzle_it_all`), register `nozzleitall://mmf-auth` as
      its redirect, then either build with `MMF_API_KEY`/`MMF_CLIENT_KEY` or paste the keys in Discover.

- **LAN/Tailscale automatic URL failover (P16 addendum)** — Helix keeps both a LAN
  and a Tailscale URL per printer and alternates on a 6s connect timeout; our
  profiles are still single fixed addresses. Real resilience gap, not yet scoped.
- **Wear OS** — reopened as a live option but not yet scoped (glance-only vs.
  actions-from-the-watch) or given test hardware.
- **The four 2026-09-16 reference-pass ideas** — community model import, mid-print
  object exclusion, solo phone-initiated slicing, input-shaper/resonance
  calibration. Listed for visibility only; none has a backlog ID yet.

## WO-37: Printer discovery and Bambu emulator checks (2026-09-24)
- **Scan network** (Add printer wizard): `PrinterScanner` sweeps the phone's private /24 for Moonraker (`/server/info`) and PrusaLink (`/api/version`) by their real replies, classifies the Snapmaker U1 and Centauri Carbon from `printer/info`, and listens for Bambu SSDP announcements (joins the multicast group, also probes each host directly), which carry the serial. Verified live on the real U1 and CC1 (`PrinterScanDeviceTest`, opt-in `-e approved_scan true`).
- **fakebambu** (github.com/jc21/fakebambu, no licence file: run only as an external test tool, never copied or shipped) built from source in Docker and run on the dev machine. `FakeBambuDeviceTest` (opt-in `-e fakebambu_host/serial/code`) passes: SSDP discovery with serial, MQTT status, wrong-code failure, FTPS upload with size check, wizard scan-to-finish. Emulator gaps, not client bugs: its TLS certificate CN is the device name (run it with `-name <serial>` to match real printers), and it never sends the explicit `project_file` `result: success` acknowledgement, so the print-start test asserts the client reaches the acknowledgement wait. **The acknowledgement path is still unverified against a real Bambu printer.**
- Found and fixed by it: the scanner never received Bambu announcements because it did not join the SSDP multicast group.

## WO-38: AWS Device Farm signing failure, root cause and fix (2026-09-24/25)
- **Symptom:** every Device Farm run after Sept 22 was "Skipped" (0 jobs) on `ci-small-pool`, and on the one-device pool each job errored in ~5 s with "Signing error with app or tests ... error while re-signing your app or test package". No run of the current test package could start.
- **What it was not:** billing (trial minutes remained), the pool's devices (all available), the app (a Sept-22 app and today's app both signed), the signature scheme/key (identical), APK size (a 4.3 MB padded package signed), or any single test file (bisecting 32 new test files by halves left every subset signing).
- **What it was:** the amount of test work in one run. The 144-test package of Sept 22 signed; the 308-test package did not, whether unfiltered or filtered with a 68-class list, while filters naming a few classes (6 to 18 tests) signed every time. The exact limit is unknown (somewhere between 144 and 272 tests); AWS gives no message beyond the signing error.
- **Fix:** CI (`.github/workflows/ci.yml`) splits the suite into 4 balanced shards (about 68 tests each, generated at build time from the test sources) and schedules one Device Farm run per shard, in sequence, through a single manual dispatch. The manual run also accepts `device_pool_arn`, `test_filter`, and existing upload ARNs. The LAN/printer-only test files (which skip themselves without arguments) are left out of the Device Farm build (`-PdeviceFarmTests`), and the unused `room-testing` dependency was removed.
- **Result:** shards 1-4 on a Google Pixel 9a (Android 15), 2026-09-25: 69 + 70 + 76 + 67 = 282 tests, 0 failures, about 33 device-minutes. The small pool (Pixel 10, Galaxy S25, Pixel Tablet) accepts filtered runs (18/18 passed); a full sharded run of it has not been done. Its earlier "Skipped" runs were not separately explained.
- The CI user (`nozzle-it-all-ci`) cannot call `devicefarm:ListJobs` or `GetDevicePoolCompatibility`; the workflow prints what `GetRun` returns instead.

## WO-39 — AGPL source offer / About screen (2026-09-25)
Settings → "About & credits" now carries the AGPL-3.0 notice and the corresponding-source offer: this app's repo and LICENSE, THIRD_PARTY_NOTICES, the OrcaSlicer source at the pinned commit (`OpenSourceNotice.ENGINE_COMMIT`, enforced equal to `engine/ENGINE_PIN.json` by `OpenSourceNoticeTest`), the headless patch, and the app version. Verified on the Razr (`MmfNavigationDeviceTest`, screenshot). Also: `main` is now the GitHub default branch (created at `f8209e3`, the commit that passed the full 846-test Device Farm run on Pixel 10 / Pixel Tablet / Galaxy S25).

## WO-40 — First-run onboarding (2026-09-25)
Three short pages (slice on the phone / control your printers / your data stays yours), Skip always one tap, last page "Add my printer" or "Explore first". Shown only for a brand-new install (no printers, no address, `onboarding.done` unset); finishing or skipping persists `done`, and Skip/Explore also persists `suppress_wizard` so the Add Printer wizard no longer auto-opens on every cold start (`CompanionScreen(autoOpenWizard=…)`). No network calls or permissions on first run. UI tests use `NozzleTestRunner`, which marks onboarding done so tests that launch `MainActivity` reach the real screens. Design reviewed by the `frontend_ux` council: kept 3 pages; adopted its persistence fix (all exit paths suppress the wizard), dropped the "found automatically on Wi-Fi" and MyMiniFactory copy, heading semantics. Not adopted: a 2-page cut (one seat) and a Settings reset. Verified on the Razr (OnboardingDeviceTest, screenshot); no Device Farm run yet.

## WO-41 — Brand identity applied (2026-09-25)
Owner commissioned a brand from Lovart.ai (violet accent, "Infill" nozzle mark). The export was raster boards only, so the mark was traced to vector (`brand/`), and applied: adaptive launcher icon with monochrome layer, Play icon, favicon set (also in `site/`), and a new default **Violet** accent (dark `#A78BFA`, light `#6D28D9`; existing users keep their saved accent). The export's contrast claims were wrong for raw `#8B5CF6` (4.48:1 on the dark background, not 7.1:1; white text on it 4.23:1), hence the lighter/darker text shades. Verified on the Razr (launcher icon, screenshot; DashboardDeviceTest/OnboardingDeviceTest/CompanionScreenTest). Not done: splash screen, lockup/wordmark use in-app, marketing graphics, and a licence check of Lovart's terms for commercial use.
WO-41 addendum (second Lovart export): Play icon replaced by Lovart's full-bleed square (downscaled to 512); dark launch window + Android 12 splash with the brand mark (`Theme.NozzleItAll`, `values-v31`). Lovart's feature graphic, social preview and screenshot frames depict a UI the app does not have and are deliberately not used.

## Decision record, 2026-09-25 — web app hosting (owner)
Owner confirmed **Plan A**: the web app is a PWA in front of an engine the *user* runs (Desktop agent or self-hosted Engine Service); Nozzle serves only the page files. A Nozzle-hosted slicing service ("Plan B": upload a model, slice on our server) was raised and deferred: **explore later**, as a separate decision, because it changes the privacy promise (models uploaded to us), running cost and abuse controls, and the site/privacy copy. Printer control would still need a local agent under either plan, since a cloud service cannot reach a home network.
